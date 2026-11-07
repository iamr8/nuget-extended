using System.Collections.Concurrent;
using System.Net;
using NuGet.Common;
using NuGet.Configuration;
using NuGet.Frameworks;
using NuGet.Protocol;
using NuGet.Protocol.Core.Types;
using NuGet.Versioning;
using NuGetExtended.Core.Versions;

namespace NuGetExtended.Helper.Feeds;

public sealed record SourceFailureInfo(string Source, string Message, bool SignInNeeded);

/// [Failed] is only the sources of this context that failed during this call for this id
/// (not the FeedService-wide history in [FeedService.Failures]).
public sealed record VersionList(IReadOnlyList<NuGetVersion> Versions, IReadOnlyList<SourceFailureInfo> Failed);

/// [Failed] mirrors [VersionList.Failed]: the sources of this context that failed during this one
/// GetCandidatesDetailedAsync call for this one id, not the FeedService-wide history.
public sealed record CandidateList(IReadOnlyList<Candidate> Candidates, IReadOnlyList<SourceFailureInfo> Failed);

/// The sources one project may use, after NuGet.config (per project dir) and source mapping.
/// A plain constructor, not `required` init properties: LangVersion is pinned to 10.0 for this project.
public sealed class FeedContext
{
    public IReadOnlyList<SourceRepository> Sources { get; }
    public PackageSourceMapping Mapping { get; }

    public FeedContext(IReadOnlyList<SourceRepository> sources, PackageSourceMapping mapping)
    {
        Sources = sources;
        Mapping = mapping;
    }

    public IEnumerable<SourceRepository> For(string id)
    {
        if (!Mapping.IsEnabled) return Sources;
        var names = Mapping.GetConfiguredPackageSources(id);
        return Sources.Where(s => names.Contains(s.PackageSource.Name, StringComparer.OrdinalIgnoreCase));
    }
}

/// NuGet's own client: settings, credentials, HTTP cache, V2/V3/local feeds. Same setup as dotnet restore.
/// One instance per request (not per process): the helper lives long, and a process-lifetime instance
/// would keep stale cached failures, pile up failures across scans, and hold stale NuGet.config
/// settings and NuGet's own in-memory resource caches forever. Credential setup and the log level are
/// the only things shared across instances (see SharedLogger / CredentialsReady below).
public sealed class FeedService : IDisposable
{
    private readonly ScanOptions _options;
    private readonly ConcurrentDictionary<string, FeedContext> _contexts = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Lazy<Task<(IReadOnlyList<NuGetVersion> Value, SourceFailureInfo? Failure)>>> _versions = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Lazy<Task<(IReadOnlyList<Candidate> Value, SourceFailureInfo? Failure)>>> _candidates = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, SourceFailureInfo> _failures = new(StringComparer.OrdinalIgnoreCase);
    // Per-source concurrency bound (spec: 16 per source, not one global gate): a source that is slow
    // or down must not throttle unrelated sources sharing this FeedService.
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _perSourceGates = new(StringComparer.OrdinalIgnoreCase);

    // Credential providers and the log level are process-wide NuGet state: set up once, shared by
    // every FeedService instance for the life of the helper.
    private static readonly SharedLogger Logger = new();
    private static readonly Lazy<bool> CredentialsReady = new(() =>
    {
        NuGet.Credentials.DefaultCredentialServiceUtility.SetupDefaultCredentialService(Logger, nonInteractive: true);
        return true;
    }, LazyThreadSafetyMode.ExecutionAndPublication);

    public FeedService(ScanOptions options)
    {
        _options = options;
        Logger.Level = Enum.TryParse<LogLevel>(options.CredLogLevel, true, out var l) ? l : LogLevel.Warning;
        _ = CredentialsReady.Value;
    }

    public IReadOnlyList<SourceFailureInfo> Failures => _failures.Values.ToList();

    /// [restoreSources] (the project's RestoreSources) replace the NuGet.config sources when there are any;
    /// [additionalSources] (RestoreAdditionalProjectSources) are added to whichever sources apply.
    public FeedContext Context(string projectDir, IReadOnlyList<string> restoreSources, IReadOnlyList<string>? additionalSources = null)
    {
        var key = projectDir + "|" + string.Join(";", restoreSources) + "|" + string.Join(";", additionalSources ?? Array.Empty<string>());
        return _contexts.GetOrAdd(key, _ =>
        {
            var settings = Settings.LoadDefaultSettings(projectDir);
            var configured = SettingsUtility.GetEnabledSources(settings).ToList();
            var enabled = restoreSources.Count > 0
                ? restoreSources.Select(r => FromProject(r, projectDir, configured)).ToList()
                : configured;
            foreach (var extra in (additionalSources ?? Array.Empty<string>()).Select(e => FromProject(e, projectDir, configured)))
                if (!enabled.Any(s => string.Equals(s.Source, extra.Source, StringComparison.OrdinalIgnoreCase)))
                    enabled.Add(extra);
            var provider = Repository.Provider.GetCoreV3();
            return new FeedContext(
                enabled.Select(s => new SourceRepository(s, provider)).ToList(),
                PackageSourceMapping.GetPackageSourceMapping(settings));
        });
    }

    /// A source from a project property. A relative folder is relative to the project folder, as in
    /// restore (not to this process's folder). A source that NuGet.config also lists keeps its name and credentials.
    private static PackageSource FromProject(string source, string projectDir, IReadOnlyList<PackageSource> configured)
    {
        if (!Uri.TryCreate(source, UriKind.Absolute, out _) && !Path.IsPathRooted(source))
            source = Path.GetFullPath(source, projectDir);
        return configured.FirstOrDefault(c => string.Equals(c.Source, source, StringComparison.OrdinalIgnoreCase)) ?? new PackageSource(source);
    }

    /// All versions of [id] from the allowed sources (flat container: fast). Failures are recorded
    /// per source (FeedService-wide, in [Failures]) and also returned here, scoped to this call.
    public async Task<VersionList> GetVersionsAsync(FeedContext ctx, string id, CancellationToken ct)
    {
        var all = new List<NuGetVersion>();
        var failed = new List<SourceFailureInfo>();
        // All sources start at once, so the time is the slowest source, not the sum. Results are read
        // in source order. If several fail (IgnoreFailedSources off), the first in source order throws.
        var pending = new List<Task<(IReadOnlyList<NuGetVersion> Value, SourceFailureInfo? Failure)>>();
        foreach (var source in ctx.For(id))
        {
            var key = source.PackageSource.Source + "|" + id;
            pending.Add(_versions.GetOrAdd(key, _ => new Lazy<Task<(IReadOnlyList<NuGetVersion>, SourceFailureInfo?)>>(
                () => Guarded(source, async cache =>
                {
                    var res = await source.GetResourceAsync<FindPackageByIdResource>(ct);
                    return (IReadOnlyList<NuGetVersion>)(await res.GetAllVersionsAsync(id, cache, Logger, ct)).ToList();
                }, Array.Empty<NuGetVersion>(), ct))).Value);
        }
        foreach (var (list, failure) in await WhenAllSources(pending, ct))
        {
            all.AddRange(list);
            if (failure != null) failed.Add(failure);
        }
        return new VersionList(all.Distinct().OrderBy(v => v).ToList(), failed);
    }

    /// Listed flag, publish date and dependency frameworks per version (registration: slower, only for ids with an upgrade),
    /// plus the per-call source failures (mirrors [GetVersionsAsync] returning a [VersionList]): a
    /// missing candidate can then be attributed to a failed source, not only to a genuinely absent version.
    public async Task<CandidateList> GetCandidatesDetailedAsync(FeedContext ctx, string id, bool includePrerelease, CancellationToken ct)
    {
        var all = new List<Candidate>();
        var failed = new List<SourceFailureInfo>();
        // Same as [GetVersionsAsync]. Reading in source order keeps GroupBy(Version).First() below:
        // the first source that has a version gives its metadata.
        var pending = new List<Task<(IReadOnlyList<Candidate> Value, SourceFailureInfo? Failure)>>();
        foreach (var source in ctx.For(id))
        {
            var key = source.PackageSource.Source + "|" + id + "|" + includePrerelease;
            pending.Add(_candidates.GetOrAdd(key, _ => new Lazy<Task<(IReadOnlyList<Candidate>, SourceFailureInfo?)>>(
                () => Guarded(source, async cache =>
                {
                    await RequireKnownRegistration(source, ct);
                    var res = await source.GetResourceAsync<PackageMetadataResource>(ct);
                    var md = await res.GetMetadataAsync(id, includePrerelease, includeUnlisted: true, cache, Logger, ct);
                    return (IReadOnlyList<Candidate>)md.Select(m => new Candidate(
                        m.Identity.Version,
                        m.IsListed,
                        m.Published,
                        (m.DependencySets ?? Enumerable.Empty<NuGet.Packaging.PackageDependencyGroup>())
                            .Select(g => g.TargetFramework).ToList())).ToList();
                }, Array.Empty<Candidate>(), ct))).Value);
        }
        foreach (var (list, failure) in await WhenAllSources(pending, ct))
        {
            all.AddRange(list);
            if (failure != null) failed.Add(failure);
        }
        return new CandidateList(all.GroupBy(c => c.Version).Select(g => g.First()).OrderBy(c => c.Version).ToList(), failed);
    }

    /// A V3 feed whose service index lists only registration types this NuGet client does not know
    /// (e.g. only `RegistrationsBaseUrl/3.6.0` for the SDK 6 client) makes NuGet throw an
    /// ArgumentNullException or NullReferenceException deep inside. Report it as a failure of that
    /// source instead. Local folders and V2 feeds have no service index and are not affected.
    private static async Task RequireKnownRegistration(SourceRepository source, CancellationToken ct)
    {
        var index = await source.GetResourceAsync<ServiceIndexResourceV3>(ct);
        if (index != null && index.GetServiceEntryUri(ServiceTypes.RegistrationsBaseUrl) == null)
            throw new InvalidDataException("the source offers no package registration type that this NuGet version supports");
    }

    /// Task.WhenAll reports a fault before a cancel when both happen. A cancelled call must still end as a cancel.
    private static async Task<T[]> WhenAllSources<T>(IEnumerable<Task<T>> tasks, CancellationToken ct)
    {
        try
        {
            return await Task.WhenAll(tasks);
        }
        catch (UserException) when (ct.IsCancellationRequested)
        {
            throw new OperationCanceledException(ct);
        }
    }

    /// Returns the failure alongside the (possibly empty) result, scoped to this one source+call,
    /// rather than making the caller re-derive it from the FeedService-wide [_failures] dict (which
    /// only ever holds the latest failure per source name and would misattribute an unrelated id).
    private async Task<(T Value, SourceFailureInfo? Failure)> Guarded<T>(SourceRepository source, Func<SourceCacheContext, Task<T>> action, T empty, CancellationToken ct)
    {
        var gate = _perSourceGates.GetOrAdd(source.PackageSource.Source, _ => new SemaphoreSlim(16));
        await gate.WaitAsync(ct);
        try
        {
            using var cache = new SourceCacheContext(); // NuGet HTTP cache on, so warm scans are fast
            return (await action(cache), null);
        }
        catch (Exception) when (ct.IsCancellationRequested)
        {
            // NuGet's HTTP resources catch our OperationCanceledException internally and rethrow it
            // wrapped in their own protocol exception (e.g. FatalProtocolException). Unwrap that back
            // to a real cancellation so the server reports "cancelled", not a source failure.
            throw new OperationCanceledException(ct);
        }
        catch (Exception e) when (IsSourceFailure(e))
        {
            var signIn = IsSignInNeeded(e);
            var info = new SourceFailureInfo(source.PackageSource.Name, FirstLine(e), signIn);
            _failures[source.PackageSource.Name] = info;
            if (!_options.IgnoreFailedSources)
                throw new UserException($"Package source '{source.PackageSource.Name}' failed: {FirstLine(e)}", e.ToString());
            return (empty, info);
        }
        finally
        {
            gate.Release();
        }
    }

    /// User/environment failures (bad or down feed, bad credentials, disk trouble): never a bug report.
    private static bool IsSourceFailure(Exception e) =>
        e is NuGetProtocolException or HttpRequestException or TimeoutException or TaskCanceledException
            or IOException or UnauthorizedAccessException or InvalidDataException;

    /// Walks the inner-exception chain for an actual 401/403, never a loose text search (a source
    /// URL or GUID can contain the digits "401" without meaning "unauthorized").
    private static bool IsSignInNeeded(Exception e)
    {
        for (var cur = e; cur != null; cur = cur.InnerException)
        {
            if (cur is HttpRequestException { StatusCode: HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden })
                return true;
            if (cur.Message.Contains("401 (Unauthorized)") || cur.Message.Contains("403 (Forbidden)"))
                return true;
        }
        return false;
    }

    private static string FirstLine(Exception e)
    {
        var inner = e;
        while (inner.InnerException != null) inner = inner.InnerException;
        return inner.Message.Split('\n')[0].Trim();
    }

    public void Dispose()
    {
        foreach (var gate in _perSourceGates.Values) gate.Dispose();
    }

    /// Shared across every FeedService instance for the helper's lifetime: NuGet's credential setup
    /// takes one ILogger, and it is set up once per process. The level is volatile so a new
    /// FeedService's constructor can update it (from its own ScanOptions) without a lock.
    private sealed class SharedLogger : LoggerBase
    {
        private volatile LogLevel _level = LogLevel.Warning;
        public LogLevel Level { set => _level = value; }

        public override void Log(ILogMessage message)
        {
            if ((int)message.Level >= (int)_level) Console.Error.WriteLine($"nuget {message.Level}: {message.Message}");
        }
        public override Task LogAsync(ILogMessage message) { Log(message); return Task.CompletedTask; }
    }
}
