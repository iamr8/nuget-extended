using System.Diagnostics;
using System.Net;
using System.Text.Json;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class ScanTests
{
    private static (string dir, string project) Setup()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4", "8.8.0" }), ("Serilog", new[] { "0.1.6", "2.12.0", "3.0.0", "4.4.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup>
    <PackageReference Include=""Polly"" Version=""[7.0.0,8.0.0)"" />
    <PackageReference Include=""Serilog"" Version=""(,3.0.0]"" />
  </ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        return (dir, project);
    }

    private static JsonElement Scan(HelperProcess h, string dir, IEnumerable<string> projects, object? options = null) =>
        h.Request("scan", new { solutionDir = dir, projects, options = options ?? new { } }).GetProperty("result");

    private static JsonElement Row(JsonElement result, string id) =>
        result.GetProperty("projects")[0].GetProperty("frameworks")[0].GetProperty("packages").EnumerateArray()
            .Single(p => p.GetProperty("id").GetString() == id);

    [Fact]
    public void ScanRespectsRangesAndUpperOnly()
    {
        var (dir, project) = Setup();
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("7.2.4", Row(r, "Polly").GetProperty("target").GetString());
        Assert.Equal("8.8.0", Row(r, "Polly").GetProperty("capped").GetString());
        Assert.Equal("3.0.0", Row(r, "Serilog").GetProperty("target").GetString());
        Assert.Empty(r.GetProperty("stale").EnumerateArray());
    }

    private static (string dir, string project) SharedPropertySetup(params string[] yVersions)
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Lib.X", new[] { "5.0.1", "5.0.2" }), ("Lib.Y", yVersions));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework><LibVersion>5.0.1</LibVersion></PropertyGroup>
  <ItemGroup>
    <PackageReference Include=""Lib.X"" Version=""$(LibVersion)"" />
    <PackageReference Include=""Lib.Y"" Version=""$(LibVersion)"" />
  </ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        return (dir, project);
    }

    // Break: Scan does not run the shared-version check (no "blocked" on the row).
    [Fact]
    public void SharedPropertyBlocksTargetAnotherIdLacks()
    {
        var (dir, project) = SharedPropertySetup("5.0.1");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("5.0.2", Row(r, "Lib.X").GetProperty("target").GetString());
        Assert.Contains("Lib.Y", Row(r, "Lib.X").GetProperty("blocked").GetString());
    }

    // Break: Scan blocks every row on a shared property, published or not.
    [Fact]
    public void SharedPropertyDoesNotBlockWhenEveryIdHasTarget()
    {
        var (dir, project) = SharedPropertySetup("5.0.1", "5.0.2");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("5.0.2", Row(r, "Lib.X").GetProperty("target").GetString());
        Assert.Equal(JsonValueKind.Null, Row(r, "Lib.X").GetProperty("blocked").ValueKind);
        Assert.Equal(JsonValueKind.Null, Row(r, "Lib.Y").GetProperty("blocked").ValueKind);
    }

    // Break: Scan treats a group with a failed source as a known version list (a false block).
    [Fact]
    public void SharedPropertyDoesNotBlockWhenASourceFailed()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                ctx.Response.StatusCode = 401;
                ctx.Response.Close();
            }
        });
        var (dir, project) = SharedPropertySetup("5.0.1");
        // Added after restore (see FailedSourceMarksRowInsteadOfHidingIt): the failed source may
        // hold Lib.Y 5.0.2, so its version list is unknown, not "missing 5.0.2".
        FixtureSolution.WriteNuGetConfig(dir, ("local", Path.Combine(dir, "feed")), ("private", $"http://127.0.0.1:{port}/v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("5.0.2", Row(r, "Lib.X").GetProperty("target").GetString());
        Assert.Equal(JsonValueKind.Null, Row(r, "Lib.X").GetProperty("blocked").ValueKind);
    }

    // Break: skip the framework check for a PrivateAssets="all" package (the old development-dependency bypass).
    [Fact]
    public void PrivatePackageWhoseNewVersionDropsTheFrameworkIsNotOffered()
    {
        var dir = FixtureSolution.NewDir();
        // 2.0.0 has dependency groups for net9.0 only: a project on FixtureSolution.Tfm cannot use it.
        var feed = FixtureFeed.Create(dir,
            new FixtureFeed.Package("Build.Tool", "1.0.0", Array.Empty<string>()),
            new FixtureFeed.Package("Build.Tool", "2.0.0", new[] { "net9.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Build.Tool"" Version=""1.0.0"" PrivateAssets=""all"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project }, new { includeUpToDate = true });

        Assert.Equal(JsonValueKind.Null, Row(r, "Build.Tool").GetProperty("target").ValueKind);
    }

    [Fact]
    public void OfflineListingHasNoTargets()
    {
        var (dir, project) = Setup();
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project }, new { checkUpdates = false, includeUpToDate = true });

        Assert.Equal(JsonValueKind.Null, Row(r, "Polly").GetProperty("target").ValueKind);
        Assert.Equal("7.0.0", Row(r, "Polly").GetProperty("resolved").GetString());
    }

    [Fact]
    public void StaleProjectIsReportedNotScanned()
    {
        var (dir, project) = Setup();
        // A real edit, not just a touched file time: A7's semantic stale check treats a no-op
        // restore (file time changed, content the same) as not stale, by design.
        var content = File.ReadAllText(project).Replace("[7.0.0,8.0.0)", "[7.0.0,9.0.0)");
        File.WriteAllText(project, content);
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal(project, r.GetProperty("stale")[0].GetString());
        Assert.Empty(r.GetProperty("projects").EnumerateArray());
    }

    [Fact]
    public void MissingProjectsFieldIsAUserErrorNotABug()
    {
        var dir = FixtureSolution.NewDir();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir });

        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void MissingOptionsFieldDefaultsInsteadOfFailing()
    {
        var (dir, project) = Setup();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { project } });

        Assert.Equal("7.2.4", Row(r.GetProperty("result"), "Polly").GetProperty("target").GetString());
    }

    [Fact]
    public void MissingPathsFieldIsAUserErrorNotABug()
    {
        var dir = FixtureSolution.NewDir();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("invalidate", new { });

        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void BadNuGetConfigIsAUserErrorNotABug()
    {
        var (dir, project) = Setup();
        // Broken XML (an unclosed attribute): Settings.LoadDefaultSettings throws
        // NuGetConfigurationException for this, verified separately by reflection against the dll.
        File.WriteAllText(Path.Combine(dir, "NuGet.config"), @"<?xml version=""1.0"" encoding=""utf-8""?>
<configuration>
  <packageSources><add key=""x""</packageSources>
</configuration>");
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { project }, options = new { } });

        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void FailedSourceMarksRowInsteadOfHidingIt()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                ctx.Response.StatusCode = 401;
                ctx.Response.Close();
            }
        });

        var dir = FixtureSolution.NewDir();
        // No newer version exists in the working feed: Target stays null for reasons unrelated to
        // the failed source, so the row would otherwise vanish under the up-to-date filter.
        var feed = FixtureFeed.Create(dir, ("Widget", new[] { "1.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Widget"" Version=""1.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project); // against the local feed only, so restore succeeds

        // The 401 source is added after restore: the stale check is semantic (requested range text
        // vs. the assets file), not source-aware, so this alone does not make the project stale.
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed), ("private", $"http://127.0.0.1:{port}/v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        var row = Row(r, "Widget");
        Assert.Equal(JsonValueKind.Null, row.GetProperty("target").ValueKind);
        Assert.Contains("sign-in needed", row.GetProperty("reason").GetString());
    }

    // Break: Scan reads candidates with GetCandidatesAsync, which drops the failure of the metadata call.
    [Fact]
    public void FailedMetadataCallMarksRowInsteadOfHidingIt()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        var root = $"http://127.0.0.1:{port}";
        listener.Prefixes.Add($"{root}/");
        listener.Start();
        // A V3 feed whose version list (flat container) works but whose metadata (registration) fails.
        // The index lists both registration types, as nuget.org does: the NuGet in SDK 6 knows
        // RegistrationsBaseUrl/3.4.0 but not /3.6.0, and without a known type it throws (baseUrl is null).
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                var body = ctx.Request.Url!.AbsolutePath switch
                {
                    "/v3/index.json" => $@"{{""version"":""3.0.0"",""resources"":[{{""@id"":""{root}/flat/"",""@type"":""PackageBaseAddress/3.0.0""}},{{""@id"":""{root}/reg/"",""@type"":""RegistrationsBaseUrl/3.4.0""}},{{""@id"":""{root}/reg/"",""@type"":""RegistrationsBaseUrl/3.6.0""}}]}}",
                    "/flat/widget/index.json" => @"{""versions"":[""1.0.0"",""2.0.0""]}",
                    _ => null,
                };
                if (body == null)
                {
                    ctx.Response.StatusCode = 500;
                }
                else
                {
                    ctx.Response.ContentType = "application/json";
                    ctx.Response.OutputStream.Write(System.Text.Encoding.UTF8.GetBytes(body));
                }
                ctx.Response.Close();
            }
        });

        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Widget", new[] { "1.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Widget"" Version=""1.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project); // against the local feed only
        FixtureSolution.Write(dir, "NuGet.config", $@"<?xml version=""1.0"" encoding=""utf-8""?>
<configuration>
  <config><add key=""globalPackagesFolder"" value=""{Path.Combine(dir, ".packages")}"" /></config>
  <packageSources><clear /><add key=""local"" value=""{feed}"" /><add key=""private"" value=""{root}/v3/index.json"" allowInsecureConnections=""true"" /></packageSources>
</configuration>");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        var row = Row(r, "Widget");
        Assert.Equal(JsonValueKind.Null, row.GetProperty("target").ValueKind);
        Assert.Contains("'private' failed", row.GetProperty("reason").GetString());
    }

    private static (string dir, string project) SourcePropertySetup(string property)
    {
        var dir = FixtureSolution.NewDir();
        var configFeed = FixtureFeed.Create(Path.Combine(dir, "a"), ("Polly", new[] { "7.0.0", "9.0.0" }));
        var projectFeed = FixtureFeed.Create(Path.Combine(dir, "b"), ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("config", configFeed));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework><{property}>{projectFeed}</{property}></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        return (dir, project);
    }

    // Break: add RestoreSources to the NuGet.config sources instead of replacing them (9.0.0 comes back).
    [Fact]
    public void RestoreSourcesReplaceTheConfigSources()
    {
        var (dir, project) = SourcePropertySetup("RestoreSources");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("7.2.4", Row(r, "Polly").GetProperty("target").GetString());
    }

    // Break: drop RestoreAdditionalProjectSources, or let it replace the config sources.
    [Fact]
    public void RestoreAdditionalProjectSourcesAddToTheConfigSources()
    {
        var (dir, project) = SourcePropertySetup("RestoreAdditionalProjectSources");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("9.0.0", Row(r, "Polly").GetProperty("target").GetString()); // config has 9.0.0, the project's source adds 7.2.4
    }

    // Break: resolve a relative RestoreSources / RestoreAdditionalProjectSources entry against the helper's folder, not the project's.
    [Theory]
    [InlineData("RestoreSources")]
    [InlineData("RestoreAdditionalProjectSources")]
    public void RelativeSourceEntryIsResolvedAgainstTheProjectFolder(string property)
    {
        var dir = FixtureSolution.NewDir();
        FixtureFeed.Create(Path.Combine(dir, "b"), ("Polly", new[] { "7.0.0", "7.2.4" }));
        // No NuGet.config source: the project's own entry is the only way to reach the feed. The helper
        // runs from `dir`, the project from `dir/P`, so "../b/feed" only points at the feed from there.
        FixtureSolution.WriteNuGetConfig(dir);
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework><{property}>../b/feed</{property}></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project });

        Assert.Equal("7.2.4", Row(r, "Polly").GetProperty("target").GetString());
    }

    [Fact]
    public void ProjectEvaluationFailureAppearsInFailuresOthersStillScan()
    {
        var (dir, project) = Setup();
        FixtureSolution.Write(dir, "Bad/Bad.csproj", @"<Project Sdk=""Microsoft.NET.Sdk""><PropertyGroup>"); // malformed: unclosed elements
        var badProject = Path.Combine(dir, "Bad/Bad.csproj");
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { project, badProject });

        var failure = r.GetProperty("failures").EnumerateArray().Single();
        Assert.Equal("Bad", failure.GetProperty("project").GetString());
        Assert.Equal("7.2.4", Row(r, "Polly").GetProperty("target").GetString()); // the good project still scanned
    }

    [Fact]
    public void InvalidateMakesTheNextScanSeeAnEditedProject()
    {
        var (dir, project) = Setup();
        using var h = HelperProcess.Start(dir);
        var r1 = Scan(h, dir, new[] { project });
        Assert.Empty(r1.GetProperty("stale").EnumerateArray());

        // ProjectEvaluator caches by path: without invalidating, this edit would not be seen.
        var content = File.ReadAllText(project).Replace("[7.0.0,8.0.0)", "[7.0.0,9.0.0)");
        File.WriteAllText(project, content);
        var inv = h.Request("invalidate", new { paths = new[] { project } }).GetProperty("result");
        Assert.Equal(1, inv.GetProperty("invalidated").GetInt32());

        var r2 = Scan(h, dir, new[] { project });
        Assert.Equal(project, r2.GetProperty("stale")[0].GetString());
    }

    [Fact]
    public void EvaluationSendsOneProgressEventPerProject()
    {
        // A cold, large solution must not look idle to the plugin's timeout while it evaluates.
        var (dir, project) = Setup();
        FixtureSolution.Write(dir, "Q/Q.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
</Project>");
        using var h = HelperProcess.Start(dir);
        var events = new List<JsonElement>();

        var id = h.Send("scan", new { solutionDir = dir, projects = new[] { project, Path.Combine(dir, "Q/Q.csproj") }, options = new { checkUpdates = false } });
        h.ReadResponse(id, events: events);
        // Progress events are posted to the thread pool and can trail the response a little: one
        // more round trip lets any late one arrive.
        h.ReadResponse(h.Send("invalidate", new { paths = Array.Empty<string>() }), events: events, eventsId: id);

        var texts = events.Select(e => e.GetProperty("data").GetProperty("text").GetString()!).ToList();
        Assert.Equal(2, texts.Count(t => t.StartsWith("Evaluated ")));
    }

    [Fact]
    public void DotnetHostPathIsTheDotnetInUseEvenWhenInherited()
    {
        // MSBuild sees environment variables as properties, so the evaluated Version shows the value.
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""X"" Version=""$(DOTNET_HOST_PATH)"" /></ItemGroup>
</Project>");
        using var h = HelperProcess.Start(dir, new Dictionary<string, string> { ["DOTNET_HOST_PATH"] = "/not/the/dotnet" });

        var r = h.Request("evaluate", new { path = Path.Combine(dir, "P/P.csproj"), runtime = "" }).GetProperty("result");

        var host = r.GetProperty("frameworks")[0].GetProperty("items")[0].GetProperty("version").GetString()!;
        Assert.NotEqual("/not/the/dotnet", host);
        Assert.True(File.Exists(host), host);
        Assert.StartsWith("dotnet", Path.GetFileName(host));
    }

    private static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    [Fact]
    public void LargeSolutionIsFastWhenWarmAndMemoryIsStable()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        var projects = new List<string>();
        // Keep <TargetFrameworks> (plural) even when it collapses to one TFM (SDK 6, where
        // FixtureSolution.Tfm is net6.0 too), so the project still goes through multi-target
        // evaluation.
        var tfms = string.Join(";", new[] { "net6.0", FixtureSolution.Tfm }.Distinct());
        for (var i = 0; i < 60; i++)
        {
            FixtureSolution.Write(dir, $"P{i}/P{i}.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFrameworks>{tfms}</TargetFrameworks></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
            projects.Add(Path.Combine(dir, $"P{i}/P{i}.csproj"));
        }
        // Run from dir, not the test process's own cwd: dotnet picks the SDK from the working
        // directory's global.json, and this must be the same pinned SDK Restore(sln) below uses -
        // otherwise an unpinned default SDK can create a newer solution format (.slnx) the pinned
        // SDK under test cannot restore.
        RunExpectSuccess("dotnet", $"new sln -o \"{dir}\" -n All", dir);
        var sln = Directory.GetFiles(dir, "All.sln*").Single();
        RunExpectSuccess("dotnet", $"sln \"{sln}\" add {string.Join(" ", projects.Select(p => "\"" + p + "\""))}", dir);
        FixtureSolution.Restore(sln);
        using var h = HelperProcess.Start(dir);

        Scan(h, dir, projects); // cold
        var sw = Stopwatch.StartNew();
        var warm = Scan(h, dir, projects);
        sw.Stop();
        var mem1 = h.Request("memory", new { }).GetProperty("result").GetProperty("bytes").GetInt64();
        Scan(h, dir, projects);
        var mem2 = h.Request("memory", new { }).GetProperty("result").GetProperty("bytes").GetInt64();

        Assert.Equal(60, warm.GetProperty("projects").GetArrayLength());
        Assert.True(sw.ElapsedMilliseconds < 1000, $"warm scan took {sw.ElapsedMilliseconds} ms");
        Assert.True(mem2 < mem1 * 1.2, $"memory grew from {mem1} to {mem2}");
    }

    // Break: Scan does not set "central", or sets it for a VersionOverride row too.
    [Fact]
    public void CentralFlagFollowsTheVersionSource()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Serilog", new[] { "3.0.0", "4.4.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "Directory.Packages.props", @"<Project>
  <PropertyGroup><ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally></PropertyGroup>
  <ItemGroup><PackageVersion Include=""Serilog"" Version=""3.0.0"" /></ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Serilog"" /></ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "B/B.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Serilog"" VersionOverride=""3.0.0"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        var b = Path.Combine(dir, "B/B.csproj");
        FixtureSolution.Restore(a);
        FixtureSolution.Restore(b);
        using var h = HelperProcess.Start(dir);

        var r = Scan(h, dir, new[] { a, b });

        bool Central(string name) => r.GetProperty("projects").EnumerateArray()
            .Single(p => p.GetProperty("name").GetString() == name)
            .GetProperty("frameworks")[0].GetProperty("packages")[0].GetProperty("central").GetBoolean();
        Assert.True(Central("A"));
        Assert.False(Central("B"));
    }

    private static void RunExpectSuccess(string exe, string args, string? workingDirectory = null)
    {
        var psi = new ProcessStartInfo(exe, args) { RedirectStandardOutput = true, RedirectStandardError = true };
        if (workingDirectory != null) psi.WorkingDirectory = workingDirectory;
        FixtureSolution.ScrubMsBuildEnv(psi);
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
        p.WaitForExit();
        Assert.True(p.ExitCode == 0, $"'{exe} {args}' failed ({p.ExitCode}):\n{output}");
    }
}
