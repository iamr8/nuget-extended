using System.Collections.Concurrent;
using System.Reflection;
using System.Text;
using System.Text.Json;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class ServerTests
{
    private static HelperProcess Start()
    {
        var dir = Path.Combine(Path.GetTempPath(), "hx-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        return HelperProcess.Start(dir);
    }

    [Fact]
    public void PingAnswers()
    {
        using var h = Start();
        var r = h.Request("ping", new { });
        Assert.True(r.GetProperty("result").GetProperty("pong").GetBoolean());
    }

    [Fact]
    public void UnknownMethodIsAnError()
    {
        using var h = Start();
        var r = h.Request("nope", new { });
        Assert.Equal("unknown", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void BadJsonDoesNotKillTheServer()
    {
        using var h = Start();
        h.SendRaw("{not json");
        var r = h.Request("ping", new { });
        Assert.True(r.GetProperty("result").GetProperty("pong").GetBoolean());
    }

    [Fact]
    public void CancelStopsTheActiveRequest()
    {
        using var h = Start();
        var sleepId = h.Send("sleep", new { ms = 30_000 });
        h.Send("cancel", new { targetId = sleepId });
        var r = h.ReadResponse(sleepId, 5_000);
        Assert.Equal("cancelled", r.GetProperty("error").GetProperty("kind").GetString());
    }

    // Break: drop the ValueKind checks in the "cancel" case of Server.RunAsync (the helper then exits).
    [Theory]
    [InlineData("{\"id\":7,\"method\":\"cancel\"}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":null}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":[]}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":{}}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":{\"targetId\":null}}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":{\"targetId\":\"3\"}}")]
    [InlineData("{\"id\":7,\"method\":\"cancel\",\"params\":{\"targetId\":1.5}}")]
    public void MalformedCancelIsAnErrorNotACrash(string line)
    {
        using var h = Start();
        h.SendRaw(line);
        var r = h.ReadResponse(7, 5_000);
        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
        var ping = h.Request("ping", new { }, 5_000);
        Assert.True(ping.GetProperty("result").GetProperty("pong").GetBoolean());
    }

    [Fact]
    public void SecondRequestIsBusyWhileOneRuns()
    {
        using var h = Start();
        var sleepId = h.Send("sleep", new { ms = 30_000 });
        var r = h.Request("sleep", new { ms = 1 });
        Assert.Equal("busy", r.GetProperty("error").GetProperty("kind").GetString());
        h.Send("cancel", new { targetId = sleepId });
    }

    [Fact]
    public void ShutdownEndsTheProcess()
    {
        using var h = Start();
        h.Send("shutdown", new { });
        Assert.True(h.WaitForExit(5_000));
    }

    // Break: Server.Execute frees the one-at-a-time slot only after it writes the response. A client
    // that sends its next request as soon as it reads a response then gets "busy" (it made
    // FeedServiceTests.SecondCallAfterSourceRecoversIsNotPoisonedByFirstFailure flaky on CI).
    // In-process, so the test decides the order: the first response is held until the server has
    // taken the second request.
    [Fact]
    public void NextRequestRightAfterAResponseIsNotBusy()
    {
        var firstSent = new ManualResetEventSlim();
        var secondTaken = new ManualResetEventSlim();
        var input = new ScriptedInput(firstSent, secondTaken,
            "{\"id\":1,\"method\":\"quick\",\"params\":{}}",
            "{\"id\":2,\"method\":\"quick\",\"params\":{}}");
        var output = new HoldingOutput(holdId: 1, seen: firstSent, release: secondTaken);
        StartInProcessServer(input, output);

        var second = output.Take(2);
        input.End();

        Assert.True(second.TryGetProperty("result", out var result) && result.ValueKind == JsonValueKind.Object, second.ToString());
    }

    private static Task<object> Quick(JsonElement p, IProgress<string> progress, CancellationToken ct) =>
        Task.FromResult<object>(new { ok = true });

    /// Helper.Tests does not reference Helper.dll (it would pull MSBuild into the test process), so
    /// the server is reached by reflection. Server itself touches no MSBuild type.
    private static void StartInProcessServer(TextReader input, TextWriter output)
    {
        var serverType = Assembly.LoadFrom(HelperProcess.HelperDll).GetType("NuGetExtended.Helper.Server", throwOnError: true)!;
        var server = Activator.CreateInstance(serverType, input, output)!;
        var handler = Delegate.CreateDelegate(serverType.GetNestedType("Handler")!,
            typeof(ServerTests).GetMethod(nameof(Quick), BindingFlags.NonPublic | BindingFlags.Static)!);
        serverType.GetMethod("Register")!.Invoke(server, new object[] { "quick", handler });
        serverType.GetMethod("RunAsync")!.Invoke(server, null);
    }

    /// Gives the first line at once, the second only after [firstSent], then waits for End().
    /// [secondTaken] is set when the server asks for a line after the second: it has handled it.
    /// Server.RunAsync awaits each read before the next, so the reads never overlap.
    private sealed class ScriptedInput : TextReader
    {
        private readonly ManualResetEventSlim _firstSent, _secondTaken, _end = new();
        private readonly string[] _lines;
        private int _next;

        public ScriptedInput(ManualResetEventSlim firstSent, ManualResetEventSlim secondTaken, params string[] lines) =>
            (_firstSent, _secondTaken, _lines) = (firstSent, secondTaken, lines);

        public void End() => _end.Set();

        public override Task<string?> ReadLineAsync() => Task.Run(() =>
        {
            var n = _next++;
            if (n == 0) return _lines[0];
            if (n == 1) { _firstSent.Wait(5_000); return _lines[1]; }
            _secondTaken.Set();
            _end.Wait(10_000);
            return (string?)null;
        });
    }

    /// Holds the line of [holdId] until [release] is set (or 3 s pass), so the server reads the next
    /// request while that response is still unsent. The "quick" handler writes no progress events.
    private sealed class HoldingOutput : TextWriter
    {
        private readonly int _holdId;
        private readonly ManualResetEventSlim _seen, _release;
        private readonly BlockingCollection<JsonElement> _lines = new();

        public HoldingOutput(int holdId, ManualResetEventSlim seen, ManualResetEventSlim release) =>
            (_holdId, _seen, _release) = (holdId, seen, release);

        public override Encoding Encoding => Encoding.UTF8;

        public override void WriteLine(string? value)
        {
            var doc = JsonDocument.Parse(value!).RootElement.Clone();
            if (doc.GetProperty("id").GetInt32() == _holdId)
            {
                _seen.Set();
                _release.Wait(3_000);
            }
            _lines.Add(doc);
        }

        public JsonElement Take(int id)
        {
            while (_lines.TryTake(out var line, 10_000))
                if (line.GetProperty("id").GetInt32() == id) return line;
            throw new TimeoutException($"no response for id {id}");
        }
    }
}
