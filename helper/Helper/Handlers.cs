using System.Collections.Concurrent;
using System.Text.Json;
using NuGet.Configuration;
using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Planning;
using NuGetExtended.Core.Protocol;
using NuGetExtended.Core.Scanning;
using NuGetExtended.Core.Versions;
using NuGetExtended.Helper.Feeds;
using NuGetExtended.Helper.Projects;

namespace NuGetExtended.Helper;

public sealed class Handlers
{
    private readonly ProjectEvaluator _evaluator;

    public Handlers(ProjectEvaluator evaluator) => _evaluator = evaluator;

    public async Task<object> Scan(JsonElement raw, IProgress<string> progress, CancellationToken ct)
    {
        ScanParams p;
        try
        {
            p = raw.Deserialize<ScanParams>(Json.Options) ?? throw new UserException("Bad scan request.");
        }
        catch (JsonException)
        {
            throw new UserException("Bad scan request.");
        }
        if (p.Projects == null || p.SolutionDir == null) throw new UserException("Bad scan request.");
        var o = p.Options ?? new ScanOptions();
        // File-based apps (.cs, SDK 10+) are opt-in: dropped here, before evaluation, when the
        // caller has not turned the option on - never surfaced as a row or a failure either way.
        var scanPaths = DistinctPaths(o.IncludeFileBasedApps ? p.Projects : p.Projects.Where(path => !path.EndsWith(".cs", StringComparison.OrdinalIgnoreCase)));
        progress.Report($"Evaluating {scanPaths.Count} project(s)...");
        // One event per project: the plugin's idle timeout restarts on each one.
        var evaluated = _evaluator.EvaluateAll(scanPaths, o.Runtime, ct, n => progress.Report($"Evaluated {n} of {scanPaths.Count} project(s)"));

        // Each assets file is parsed once per scan, for all its target frameworks.
        // The stale check and the rows share the result.
        // Only the per-framework views stay in memory, not the parsed file.
        var aliases = evaluated.Where(e => e.Error == null).SelectMany(e => e.Frameworks)
            .GroupBy(t => t.AssetsFile, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.Select(t => t.Framework).ToList(), StringComparer.OrdinalIgnoreCase);
        var assets = new ConcurrentDictionary<string, IReadOnlyDictionary<string, AssetsData?>>(StringComparer.OrdinalIgnoreCase);
        AssetsData? Read(EvaluatedTfm t) =>
            assets.GetOrAdd(t.AssetsFile, f => AssetsReader.ReadAll(f, aliases[f], o.Runtime))[t.Framework];

        var failures = new List<Failure>();
        var stale = new List<string>();
        var fresh = new List<EvaluatedProject>();
        foreach (var e in evaluated)
        {
            if (e.Error != null) failures.Add(new Failure(e.Name, e.Error, e.Error));
            else if (RestoreState.IsStale(e, Read)) stale.Add(e.Path);
            else fresh.Add(e);
        }

        // Collect every (project, tfm, asset) first, then fetch each id once. Every TFM reached here
        // belongs to a project IsStale already accepted as fresh, which means Read(tfm) already
        // returned non-null for every one of its TFMs - so a null here would be a bug, not a case
        // to route to `stale` a second time.
        var work = new List<(EvaluatedProject P, EvaluatedTfm T, RowBuilder.Asset A, string Requested)>();
        foreach (var e in fresh)
        foreach (var tfm in e.Frameworks)
        {
            var data = Read(tfm)!;
            foreach (var a in data.Packages)
            {
                if (a.AutoReferenced && !o.IncludeAutoReferences) continue;
                if (!a.Direct && (!o.Transitive || a.Depth > o.TransitiveDepth)) continue;
                var requested = a.Direct ? RowBuilder.Requested(tfm, a.Id) ?? a.RequestedRange ?? "" : a.RequestedRange ?? "";
                work.Add((e, tfm, new RowBuilder.Asset(a.Id, a.Resolved, a.Direct, a.Depth, a.AutoReferenced), requested));
            }
        }

        var candidates = new ConcurrentDictionary<string, IReadOnlyList<Candidate>?>(StringComparer.OrdinalIgnoreCase);
        // Per-group source failures, next to `candidates`: a row with no Target whose group hit a
        // failed source would otherwise just vanish under the filter below (IgnoreFailedSources
        // means "keep scanning", not "hide the fact that a source was unreachable").
        var groupFailed = new ConcurrentDictionary<string, IReadOnlyList<SourceFailureInfo>>(StringComparer.OrdinalIgnoreCase);
        // Every published version per group (listed or not, like the planner's candidates): the
        // shared-version check needs them even for an id with nothing newer to offer. A group with
        // a failed source has no entry: the missing version may sit on that source.
        var published = new ConcurrentDictionary<string, IReadOnlyCollection<NuGetVersion>>(StringComparer.OrdinalIgnoreCase);
        IReadOnlyList<SourceFailure> sourceFailures = Array.Empty<SourceFailure>();
        if (o.CheckUpdates)
        {
            // A new instance per scan, not one for the process: a long-lived FeedService keeps
            // stale source failures and settings (see the comment on FeedService itself).
            using var feeds = new FeedService(o);
            var groups = work.GroupBy(w => (Dir: Path.GetDirectoryName(w.P.Path)!, Sources: FeedKey.Sources(w.T), Id: w.A.Id.ToLowerInvariant())).ToList();

            // Contexts are primed one at a time, before the parallel fetch loop, keyed by (Dir,
            // Sources) - both plain strings, so this actually dedupes (a Distinct() over tuples
            // holding the RestoreSources list itself would compare list references, not content,
            // and under-dedupe). Context() can throw on a bad NuGet.config; priming first fails
            // fast, before any group starts hitting the network, and keeps the translation to
            // UserException in one place instead of duplicated per parallel iteration. (This is a
            // fail-fast choice, not a workaround for exception wrapping: `await
            // Parallel.ForEachAsync(...)` does not wrap a fault in AggregateException - await
            // unwraps and rethrows the original exception - so catching it inside the loop would
            // work too.)
            foreach (var group in groups.GroupBy(g => (g.Key.Dir, g.Key.Sources)).Select(gg => gg.First()))
            {
                try
                {
                    feeds.Context(group.Key.Dir, group.First().T.RestoreSources, group.First().T.AdditionalSources);
                }
                catch (NuGetConfigurationException e)
                {
                    throw new UserException(FirstLine(e), e.ToString());
                }
            }

            var done = 0;
            await Parallel.ForEachAsync(groups, new ParallelOptions { MaxDegreeOfParallelism = 32, CancellationToken = ct }, async (g, token) =>
            {
                var ctx = feeds.Context(g.Key.Dir, g.First().T.RestoreSources, g.First().T.AdditionalSources);
                var versions = await feeds.GetVersionsAsync(ctx, g.First().A.Id, token);
                var lowest = g.Where(w => w.A.Resolved != null).Select(w => w.A.Resolved!).DefaultIfEmpty().Min();
                IReadOnlyList<Candidate>? list;
                // The version list and the metadata call can fail on different sources: both count.
                var failed = versions.Failed;
                if (lowest != null && versions.Versions.Any(v => v > lowest))
                {
                    // Anything that is not "Always" or "Never" is Auto (matches TargetSelector).
                    var includePre = o.PreRelease == "Always" ||
                        (o.PreRelease != "Never" && g.Any(w => w.A.Resolved?.IsPrerelease == true));
                    var detailed = await feeds.GetCandidatesDetailedAsync(ctx, g.First().A.Id, includePre, token);
                    list = detailed.Candidates;
                    failed = versions.Failed.Concat(detailed.Failed).DistinctBy(f => f.Source).ToList();
                }
                else list = Array.Empty<Candidate>();
                var key = g.Key.Dir + "|" + g.Key.Sources + "|" + g.Key.Id;
                candidates[key] = list;
                if (versions.Failed.Count == 0) published[key] = new HashSet<NuGetVersion>(versions.Versions);
                if (failed.Count > 0) groupFailed[key] = failed;
                progress.Report($"Checked {Interlocked.Increment(ref done)} package(s)");
            });
            sourceFailures = feeds.Failures.Select(f => new SourceFailure(f.Source, f.Message, f.SignInNeeded)).ToList();
        }

        var now = DateTimeOffset.UtcNow;
        var shared = new SharedVersions(fresh);
        var projects = work
            .GroupBy(w => w.P.Path)
            .Select(pg => new ProjectRows(pg.Key, pg.First().P.Name, pg
                .GroupBy(w => w.T.Framework)
                .Select(tg => new FrameworkRows(tg.Key, tg.Select(w =>
                {
                    var key = GroupKey(w.P.Path, w.T, w.A.Id);
                    candidates.TryGetValue(key, out var c);
                    var site = SiteResolver.Resolve(w.T, w.A.Id).Site;
                    var row = RowBuilder.Row(w.A, w.Requested, o.CheckUpdates ? c : null, NuGetFramework.Parse(w.T.Framework), o, now)
                        with
                        {
                            Central = SiteResolver.IsCentral(w.T, w.A.Id),
                            VersionFile = site?.File,
                            VersionLine = site?.Line ?? 0,
                            VersionProperty = site?.Kind == "property" ? site.Name : null,
                        };
                    // Don't overwrite an existing Reason (bad range text, not resolved): only a row
                    // that would otherwise vanish (no Target, no Reason) gets the source failure.
                    if (row.Target == null && row.Reason == null && groupFailed.TryGetValue(key, out var gf) && gf.Count > 0)
                        row = row with { Reason = SourceFailureReason(gf[0]) };
                    if (row.Target != null && !row.RestoreOnly)
                        row = row with
                        {
                            Blocked = shared.Blocker(w.T, w.A.Id, NuGetVersion.Parse(row.Target),
                                (path, t, id) => published.TryGetValue(GroupKey(path, t, id), out var v) ? v : null),
                        };
                    return row;
                })
                .Where(r => o.IncludeUpToDate || !o.CheckUpdates || r.Target != null || r.Capped != null || r.Reason != null)
                .OrderBy(r => r.Id, StringComparer.OrdinalIgnoreCase).ToList()))
                .Where(f => f.Packages.Count > 0).ToList()))
            .Where(pr => pr.Frameworks.Count > 0)
            .ToList();

        return new ScanResult(projects, stale.Distinct().ToList(), failures, sourceFailures);
    }

    /// The project URL of the target version, else of the newest version that has one.
    public async Task<object> PlanUpgrade(JsonElement raw, IProgress<string> progress, CancellationToken ct)
    {
        PlanParams p;
        try
        {
            p = raw.Deserialize<PlanParams>(Json.Options) ?? throw new UserException("Bad planUpgrade request.");
        }
        catch (JsonException)
        {
            throw new UserException("Bad planUpgrade request.");
        }
        if (p.Rows == null || p.AllProjects == null || p.SolutionDir == null) throw new UserException("Bad planUpgrade request.");
        foreach (var row in p.Rows)
        {
            if (row?.Project == null || row.Framework == null || row.Id == null || row.Target == null || !NuGetVersion.TryParse(row.Target, out _))
                throw new UserException("Bad planUpgrade request.");
        }
        if (p.AllProjects.Any(path => path == null)) throw new UserException("Bad planUpgrade request.");
        var o = p.Options ?? new ScanOptions();
        progress.Report("Finding where each version is set...");
        var allProjects = DistinctPaths(p.AllProjects);
        var all = _evaluator.EvaluateAll(allProjects, o.Runtime, ct, n => progress.Report($"Evaluated {n} of {allProjects.Count} project(s)"));

        // Every (project, tfm, id) that shares an edit site with one of the rows (including sites shared
        // by more than the row's own id or TFM): EditPlanner.Plan's known-version and TFM checks read
        // Candidate data for every consumer of a touched site, from that consumer's own feed context
        // (NuGet.config folder + restore sources), not only the row's. Keyed by GroupKey, like Scan.
        var need = EditPlanner.IdsNeedingCandidates(p.Rows, all);
        var candidates = new ConcurrentDictionary<string, IReadOnlyList<Candidate>>(StringComparer.OrdinalIgnoreCase);
        var idFailures = new ConcurrentDictionary<string, SourceFailureInfo>(StringComparer.OrdinalIgnoreCase);
        if (need.Count > 0)
        {
            // A new instance per call, not one for the process (see the comment on Scan above).
            using var feeds = new FeedService(o);

            // Contexts are primed first, one at a time per (dir, sources) group, so a bad
            // NuGet.config fails fast and translates to UserException in one place - same as Scan.
            foreach (var group in need.GroupBy(n => (Dir: Path.GetDirectoryName(n.ProjectPath)!, Sources: FeedKey.Sources(n.Tfm))))
            {
                try
                {
                    feeds.Context(group.Key.Dir, group.First().Tfm.RestoreSources, group.First().Tfm.AdditionalSources);
                }
                catch (NuGetConfigurationException e)
                {
                    throw new UserException(FirstLine(e), e.ToString());
                }
            }

            await Parallel.ForEachAsync(need, new ParallelOptions { MaxDegreeOfParallelism = 32, CancellationToken = ct }, async (n, token) =>
            {
                var (projectPath, tfm, id) = n;
                var ctx = feeds.Context(Path.GetDirectoryName(projectPath)!, tfm.RestoreSources, tfm.AdditionalSources);
                // GetCandidatesDetailedAsync's own Failed list is scoped to this one id and this one
                // call - FeedService.Failures is process-wide, keyed only by source name, and would
                // misattribute a different id's failure on the same source under parallelism.
                // includePrerelease: true - the target itself was already chosen (and filtered) by
                // the scan; this fetch only needs to know whether it exists and what it depends on.
                var result = await feeds.GetCandidatesDetailedAsync(ctx, id, includePrerelease: true, token);
                var key = GroupKey(projectPath, tfm, id);
                candidates[key] = result.Candidates;
                if (result.Failed.Count > 0) idFailures[key] = result.Failed[0];
            });
        }

        // A "no version fits" skip caused by a failed source (not a real version mismatch) gets the
        // same reason text Scan gives a row it cannot check for the same cause - checked for every
        // consumer of the site, not only the row's own id.
        // Never write into the global packages folder or the dotnet root, even when they sit
        // under the repository root (ProjectEvaluator skips the same folders as inputs).
        var outside = all.SelectMany(e => e.Frameworks).Select(t => t.PackagesRoot).OfType<string>()
            .Append(SdkHost.DotnetRoot).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        return EditPlanner.Plan(p.Rows, all,
            (path, t, id) => candidates.TryGetValue(GroupKey(path, t, id), out var c) ? c : Array.Empty<Candidate>(),
            o, RepoRoot(p.SolutionDir),
            (path, t, id) => idFailures.TryGetValue(GroupKey(path, t, id), out var f) ? SourceFailureReason(f) : null, outside);
    }

    /// The feed group a (project, tfm, id) belongs to: same folder (NuGet.config), same sources, same id.
    private static string GroupKey(string projectPath, EvaluatedTfm t, string id) => FeedKey.Of(projectPath, t, id);

    /// One entry per project file: `A/../A/A.csproj` and `A/A.csproj` evaluate to the same Path, and
    /// EditPlanner's `ToDictionary(p => p.Path, OrdinalIgnoreCase)` would throw on the duplicate key.
    /// Same full path and comparer as the evaluator's cache key.
    private static List<string> DistinctPaths(IEnumerable<string> paths) =>
        paths.Select(Path.GetFullPath).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

    /// The repository root: the nearest folder with .git above the solution, else the solution folder.
    private static string RepoRoot(string solutionDir)
    {
        for (var d = new DirectoryInfo(solutionDir); d != null; d = d.Parent)
            if (Directory.Exists(Path.Combine(d.FullName, ".git")) || File.Exists(Path.Combine(d.FullName, ".git")))
                return d.FullName;
        return solutionDir;
    }

    public Task<object> Invalidate(JsonElement raw, IProgress<string> _, CancellationToken __)
    {
        if (!raw.TryGetProperty("paths", out var pathsEl) || pathsEl.ValueKind != JsonValueKind.Array)
            throw new UserException("Bad invalidate request.");
        List<string> paths;
        try
        {
            paths = pathsEl.EnumerateArray().Select(x => x.GetString()!).ToList();
        }
        catch (InvalidOperationException)
        {
            throw new UserException("Bad invalidate request.");
        }
        _evaluator.Invalidate(paths);
        return Task.FromResult<object>(new { invalidated = paths.Count });
    }

    private static string FirstLine(Exception e)
    {
        var inner = e;
        while (inner.InnerException != null) inner = inner.InnerException;
        return inner.Message.Split('\n')[0].Trim();
    }

    private static string SourceFailureReason(SourceFailureInfo f) =>
        f.SignInNeeded ? $"sign-in needed for source '{f.Source}'" : $"package source '{f.Source}' failed";
}
