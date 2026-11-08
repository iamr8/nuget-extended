using System.Text.Json;
using NuGetExtended.Core.Protocol;

namespace NuGetExtended.Helper;

/// A user or environment failure (bad project, missing feed): shown as a balloon, never a bug report.
public sealed class UserException : Exception
{
    public string? Details { get; }
    public UserException(string message, string? details = null) : base(message) => Details = details;
}

public sealed class Server
{
    public delegate Task<object> Handler(JsonElement @params, IProgress<string> progress, CancellationToken ct);

    private readonly TextReader _input;
    private readonly TextWriter _output;
    private readonly object _writeLock = new();
    private readonly Dictionary<string, Handler> _handlers = new(StringComparer.Ordinal);
    private readonly object _activeLock = new();
    private CancellationTokenSource? _active;
    private int _activeId;

    public Server(TextReader input, TextWriter output)
    {
        _input = input;
        _output = output;
        Register("ping", (_, _, _) => Task.FromResult<object>(new { pong = true }));
    }

    public void Register(string method, Handler handler) => _handlers[method] = handler;

    public async Task<int> RunAsync()
    {
        var running = new List<Task>();
        string? line;
        while ((line = await _input.ReadLineAsync()) != null)
        {
            Request? req;
            try
            {
                req = JsonSerializer.Deserialize<Request>(line, Json.Options);
            }
            catch (JsonException e)
            {
                Console.Error.WriteLine($"bad request line: {e.Message}");
                continue;
            }
            if (req == null) continue;

            switch (req.Method)
            {
                case "shutdown":
                    CancelActive();
                    await Task.WhenAll(running);
                    return 0;
                case "cancel":
                    // A bad cancel is a bad request, not a reason to exit the helper.
                    if (req.Params.ValueKind != JsonValueKind.Object ||
                        !req.Params.TryGetProperty("targetId", out var t) ||
                        t.ValueKind != JsonValueKind.Number || !t.TryGetInt32(out var targetId))
                    {
                        Write(new ResponseLine(req.Id, null, new ErrorInfo("user", "cancel needs a numeric targetId.", null)));
                        continue;
                    }
                    if (targetId == _activeId) CancelActive();
                    Write(new ResponseLine(req.Id, new { cancelled = true }, null));
                    continue;
            }

            if (!_handlers.TryGetValue(req.Method, out var handler))
            {
                Write(new ResponseLine(req.Id, null, new ErrorInfo("unknown", $"Unknown method '{req.Method}'.", null)));
                continue;
            }

            // Methods that never block may run beside an active request; the rest are one at a time.
            var exclusive = req.Method != "ping" && req.Method != "invalidate";
            CancellationTokenSource cts;
            lock (_activeLock)
            {
                if (exclusive && _active != null)
                {
                    Write(new ResponseLine(req.Id, null, new ErrorInfo("busy", "Another request is running.", null)));
                    continue;
                }
                cts = new CancellationTokenSource();
                if (exclusive) { _active = cts; _activeId = req.Id; }
            }

            running.RemoveAll(x => x.IsCompleted);
            running.Add(Task.Run(() => Execute(req, handler, cts, exclusive)));
        }
        return 0;
    }

    private async Task Execute(Request req, Handler handler, CancellationTokenSource cts, bool exclusive)
    {
        var progress = new Progress<string>(text => Write(new EventLine("progress", new { text }, req.Id)));
        ResponseLine response;
        try
        {
            var result = await handler(req.Params, progress, cts.Token);
            response = new ResponseLine(req.Id, result, null);
        }
        catch (OperationCanceledException)
        {
            response = new ResponseLine(req.Id, null, new ErrorInfo("cancelled", "Cancelled.", null));
        }
        catch (UserException e)
        {
            response = new ResponseLine(req.Id, null, new ErrorInfo("user", e.Message, e.Details));
        }
        catch (Exception e)
        {
            response = new ResponseLine(req.Id, null, new ErrorInfo("bug", e.Message, e.ToString()));
        }
        // Free the slot before the response goes out: a client may send its next request as soon
        // as it reads this line, and must not get "busy".
        lock (_activeLock)
        {
            if (exclusive && _active == cts) { _active = null; _activeId = 0; }
        }
        cts.Dispose();
        Write(response);
    }

    private void CancelActive()
    {
        lock (_activeLock) _active?.Cancel();
    }

    private void Write(object line)
    {
        var text = JsonSerializer.Serialize(line, line.GetType(), Json.Options);
        lock (_writeLock)
        {
            _output.WriteLine(text);
            _output.Flush();
        }
    }
}
