using System.Net;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class FeedServiceTests
{
    [Fact]
    public void ListsVersionsFromLocalFeed()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4", "8.8.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("versions", new { projectDir = dir, id = "polly" });

        var versions = r.GetProperty("result").GetProperty("versions").EnumerateArray().Select(v => v.GetString()).ToList();
        Assert.Equal(new[] { "7.0.0", "7.2.4", "8.8.0" }, versions);
    }

    [Fact]
    public void SourceMappingLimitsSources()
    {
        var dir = FixtureSolution.NewDir();
        var a = FixtureFeed.Create(Path.Combine(dir, "a"), ("Polly", new[] { "7.0.0" }));
        var b = FixtureFeed.Create(Path.Combine(dir, "b"), ("Polly", new[] { "9.0.0" }));
        FixtureSolution.Write(dir, "NuGet.config", $@"<configuration>
  <packageSources><clear /><add key=""a"" value=""{a}"" /><add key=""b"" value=""{b}"" /></packageSources>
  <packageSourceMapping>
    <packageSource key=""a""><package pattern=""Polly"" /></packageSource>
    <packageSource key=""b""><package pattern=""*"" /></packageSource>
  </packageSourceMapping>
</configuration>");
        using var h = HelperProcess.Start(dir);

        var r = h.Request("versions", new { projectDir = dir, id = "Polly" });

        var versions = r.GetProperty("result").GetProperty("versions").EnumerateArray().Select(v => v.GetString()).ToList();
        Assert.Equal(new[] { "7.0.0" }, versions); // exact pattern beats "*"
    }

    [Fact]
    public void UnauthorizedSourceIsReportedAndSkipped()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                var ctx = await listener.GetContextAsync();
                ctx.Response.StatusCode = 401;
                ctx.Response.Close();
            }
        });

        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed), ("private", $"http://127.0.0.1:{port}/v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("versions", new { projectDir = dir, id = "Polly" }).GetProperty("result");

        Assert.Equal(new[] { "7.0.0" }, r.GetProperty("versions").EnumerateArray().Select(v => v.GetString()));
        var failure = r.GetProperty("failures").EnumerateArray().Single();
        Assert.Equal("private", failure.GetProperty("source").GetString());
        Assert.True(failure.GetProperty("signInNeeded").GetBoolean());
    }

    [Fact]
    public void ServerErrorIsReportedButNotSignInNeeded()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        // The path segment "api401" is a decoy: it makes the source URL (and so the exception
        // text) contain the digits "401" even though the real status is 500, unrelated to
        // sign-in. A naive "does the text contain 401" check would misreport this as sign-in
        // needed; the fix must look only at the actual HTTP status / message shape.
        listener.Prefixes.Add($"http://127.0.0.1:{port}/api401/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                ctx.Response.StatusCode = 500;
                ctx.Response.Close();
            }
        });

        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed), ("private", $"http://127.0.0.1:{port}/api401/v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("versions", new { projectDir = dir, id = "Polly" }).GetProperty("result");

        Assert.Equal(new[] { "7.0.0" }, r.GetProperty("versions").EnumerateArray().Select(v => v.GetString()));
        var failure = r.GetProperty("failures").EnumerateArray().Single();
        Assert.Equal("private", failure.GetProperty("source").GetString());
        Assert.False(failure.GetProperty("signInNeeded").GetBoolean());
    }

    [Fact]
    public void CancelDuringHttpFetchReportsCancelledNotFailure()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        var baseUrl = $"http://127.0.0.1:{port}/";
        listener.Prefixes.Add(baseUrl);
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                var path = ctx.Request.Url!.AbsolutePath;
                if (path.EndsWith("/v3/index.json"))
                {
                    RespondJson(ctx, "{\"version\":\"3.0.0\",\"resources\":[{\"@id\":\"" + baseUrl +
                                      "flat/\",\"@type\":\"PackageBaseAddress/3.0.0\"}]}");
                }
                else
                {
                    // The flat-container request never answers: this is what a cancelled fetch looks like.
                    await Task.Delay(-1);
                }
            }
        });

        var dir = FixtureSolution.NewDir();
        FixtureSolution.WriteNuGetConfig(dir, ("private", baseUrl + "v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var id = h.Send("versions", new { projectDir = dir, id = "Polly" });
        Thread.Sleep(1000);
        h.Send("cancel", new { targetId = id });
        var r = h.ReadResponse(id, 10_000);

        Assert.Equal("cancelled", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void SecondCallAfterSourceRecoversIsNotPoisonedByFirstFailure()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        var baseUrl = $"http://127.0.0.1:{port}/";
        listener.Prefixes.Add(baseUrl);
        listener.Start();
        // Flips only after the first "versions" call has fully returned (see below), so this does
        // not depend on how many times NuGet retries the service-index fetch internally.
        var recovered = false;
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                var path = ctx.Request.Url!.AbsolutePath;
                if (path.EndsWith("/v3/index.json"))
                {
                    if (!Volatile.Read(ref recovered))
                    {
                        ctx.Response.StatusCode = 401;
                        ctx.Response.Close();
                    }
                    else
                    {
                        RespondJson(ctx, "{\"version\":\"3.0.0\",\"resources\":[{\"@id\":\"" + baseUrl +
                                          "flat/\",\"@type\":\"PackageBaseAddress/3.0.0\"}]}");
                    }
                }
                else if (path.Contains("/flat/"))
                {
                    RespondJson(ctx, "{\"versions\":[\"7.0.0\"]}");
                }
                else
                {
                    ctx.Response.StatusCode = 404;
                    ctx.Response.Close();
                }
            }
        });

        var dir = FixtureSolution.NewDir();
        FixtureSolution.WriteNuGetConfig(dir, ("private", baseUrl + "v3/index.json"));
        using var h = HelperProcess.Start(dir);

        // Blocks until the first call (and all of NuGet's own internal retries within it) is done.
        var r1 = h.Request("versions", new { projectDir = dir, id = "Polly" }).GetProperty("result");
        Assert.Empty(r1.GetProperty("versions").EnumerateArray());
        Assert.NotEmpty(r1.GetProperty("failures").EnumerateArray());

        Volatile.Write(ref recovered, true);
        var r2 = h.Request("versions", new { projectDir = dir, id = "Polly" }).GetProperty("result");
        var versions2 = r2.GetProperty("versions").EnumerateArray().Select(v => v.GetString()).ToList();
        Assert.Equal(new[] { "7.0.0" }, versions2);
        Assert.Empty(r2.GetProperty("failures").EnumerateArray());
    }

    // Break: a feed whose service index lists no registration type this NuGet client knows ends the
    // scan as a "bug" error (the IDE error reporter), not as a failure of that one source.
    [Fact]
    public void FeedWithUnknownRegistrationTypeIsASourceFailure()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        var baseUrl = $"http://127.0.0.1:{port}/";
        listener.Prefixes.Add(baseUrl);
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                var path = ctx.Request.Url!.AbsolutePath;
                if (path.EndsWith("/v3/index.json"))
                    RespondJson(ctx, "{\"version\":\"3.0.0\",\"resources\":[" +
                                     "{\"@id\":\"" + baseUrl + "flat/\",\"@type\":\"PackageBaseAddress/3.0.0\"}," +
                                     "{\"@id\":\"" + baseUrl + "reg/\",\"@type\":\"RegistrationsBaseUrl/99.0.0\"}]}");
                else if (path.EndsWith("/flat/polly/index.json"))
                    RespondJson(ctx, "{\"versions\":[\"7.0.0\",\"7.2.4\"]}");
                else
                {
                    ctx.Response.StatusCode = 404;
                    ctx.Response.Close();
                }
            }
        });

        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        // Added after the restore, so only the scan reads it.
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed), ("private", baseUrl + "v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { project }, options = new { } });

        Assert.False(r.TryGetProperty("error", out var error) && error.ValueKind != System.Text.Json.JsonValueKind.Null, error.ToString());
        var failure = r.GetProperty("result").GetProperty("sourceFailures").EnumerateArray().Single();
        Assert.Equal("private", failure.GetProperty("source").GetString());
    }

    private static void RespondJson(HttpListenerContext ctx, string json)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(json);
        ctx.Response.ContentType = "application/json";
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
        ctx.Response.Close();
    }

    private static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }
}
