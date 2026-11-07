using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Protocol;
using NuGetExtended.Core.Scanning;
using NuGetExtended.Core.Versions;

namespace NuGetExtended.Core.Planning;

public static class EditPlanner
{
    internal sealed record Consumer(string Project, EvaluatedTfm Tfm, string Id, string? Requested);

    private sealed record Repo(string Root, IReadOnlyList<string> Outside)
    {
        public bool Contains(string file) => IsUnder(file, Root) && !Outside.Any(o => o.Length > 0 && IsUnder(file, o));
    }

    public static UpgradePlan Plan(
        IReadOnlyList<UpgradeRow> rows,
        IReadOnlyList<EvaluatedProject> all,
        // Candidate data of one consumer (project, tfm, id), read from that project's own feed context.
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> candidates,
        ScanOptions options,
        string repoRoot,
        Func<string, EvaluatedTfm, string, string?>? failedReason = null,
        IReadOnlyList<string>? outsideRoots = null)
    {
        // Folders that never count as the repository, even under repoRoot (the NuGet package
        // folder, the dotnet root): a home-directory repo would otherwise take them in.
        var repo = new Repo(repoRoot, outsideRoots ?? Array.Empty<string>());
        var edits = new List<Edit>();
        var skipped = new List<Skip>();
        var also = new List<Change>();
        var restore = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var byPath = all.ToDictionary(p => p.Path, StringComparer.OrdinalIgnoreCase);
        var (consumers, sites) = BuildSiteMap(all);
        // Inserts that ride along a "set" edit (the versionless PackageReference next to an
        // existing central item): held back until the site's target is actually picked, so a
        // skipped set never leaves an orphan insert behind (or vice versa).
        var pendingInserts = new Dictionary<string, List<Edit>>();
        // New central PackageVersion inserts by (central file, id): projects that insert the same item
        // must agree on one version that every one of them supports (#61), so they are planned together.
        var centralInserts = new Dictionary<(string, string), List<(UpgradeRow Row, EvaluatedProject Project, EvaluatedTfm Tfm)>>();

        var bySite = new Dictionary<string, List<UpgradeRow>>();
        foreach (var row in rows)
        {
            if (!byPath.TryGetValue(row.Project, out var project)) { skipped.Add(new Skip(row.Project, row.Id, "project not found")); continue; }
            if (project.Error != null) { skipped.Add(new Skip(row.Project, row.Id, project.Error)); continue; }
            var tfm = project.Frameworks.FirstOrDefault(f => f.Framework == row.Framework);
            if (tfm == null) { skipped.Add(new Skip(row.Project, row.Id, "target framework not found - re-scan")); continue; }

            var (site, problem, transitive, viaExistingCentral) = ResolveRowSite(tfm, row.Id);

            if (transitive)
            {
                // No PackageReference/GlobalPackageReference anywhere in this TFM, and (under CPM)
                // no existing central item either: a genuinely new dependency, handled by Inserts.
                if (!options.Transitive) { skipped.Add(new Skip(row.Project, row.Id, "transitive package - enable transitive upgrades")); continue; }
                if (tfm.CpmEnabled && tfm.CentralFile != null)
                {
                    var insertKey = (tfm.CentralFile.ToLowerInvariant(), row.Id.ToLowerInvariant());
                    if (!centralInserts.TryGetValue(insertKey, out var group)) centralInserts[insertKey] = group = new();
                    group.Add((row, project, tfm));
                    continue;
                }
                if (!FitsEveryFramework(project, row.Id, NuGetVersion.Parse(row.Target), candidates))
                {
                    skipped.Add(new Skip(row.Project, row.Id, "the new version does not support every target framework of this project"));
                    continue;
                }
                var inserts = Inserts(project, tfm, row, skipped, repo).ToList();
                if (inserts.Count > 0) { edits.AddRange(inserts); restore.Add(project.Path); }
                continue;
            }

            if (viaExistingCentral)
            {
                // A CPM central PackageVersion item for this id already exists (a sibling project
                // references it, so it shows up in this project's own evaluated items too, imported
                // from the central file) even though this project has no PackageReference of its
                // own: route through the normal edit path (bySite / consumers / Accepts) instead of
                // ever inserting a duplicate PackageVersion (NU1506) - even when that item's own
                // site cannot be edited (site == null below skips with its own problem).
                if (!options.Transitive) { skipped.Add(new Skip(row.Project, row.Id, "transitive package - enable transitive upgrades")); continue; }
                if (site == null) { skipped.Add(new Skip(row.Project, row.Id, problem ?? "no version is set for this package")); continue; }

                // Checked up front: a row whose reference-insert target is outside the repository or
                // read-only gets exactly one outcome (a Skip), never both a Skip and a possible
                // "set" edit from the site it would otherwise still route to.
                if (!tfm.TransitivePinning && !SiteAllowed(project.Path, repo, row.Project, row.Id, skipped)) continue;

                var centralKey = SiteResolver.Key(site);
                sites.TryAdd(centralKey, site);
                if (!bySite.TryGetValue(centralKey, out var centralList)) bySite[centralKey] = centralList = new List<UpgradeRow>();
                centralList.Add(row);
                // This project has no direct reference today, but the plan is about to give it one
                // (or, with TransitivePinning, it inherits the central version once restored): count
                // it as a consumer so the TFM and known-version checks apply to it too - otherwise a
                // site with no other, pre-existing consumer would validate against an empty list.
                // One consumer per TFM: the inserted versionless reference applies to every TFM of the project (#61).
                if (!consumers.TryGetValue(centralKey, out var consumerList)) consumers[centralKey] = consumerList = new List<Consumer>();
                foreach (var t in project.Frameworks) consumerList.Add(new Consumer(project.Path, t, row.Id, null));
                // A's own restore need (a new direct or transitively-pinned dependency on the site)
                // does not depend on whether the central set edit itself is picked.
                restore.Add(project.Path);
                if (!tfm.TransitivePinning)
                {
                    if (!pendingInserts.TryGetValue(centralKey, out var pendingList)) pendingInserts[centralKey] = pendingList = new List<Edit>();
                    pendingList.Add(new Edit("insert", project.Path, "item", "PackageReference", "Include", row.Id, null, null, "", null, "", 0, 0));
                }
                continue;
            }

            if (site == null) { skipped.Add(new Skip(row.Project, row.Id, problem ?? "no version is set for this package")); continue; }
            var key = SiteResolver.Key(site);
            if (!bySite.TryGetValue(key, out var list)) bySite[key] = list = new List<UpgradeRow>();
            list.Add(row);
        }

        foreach (var group in centralInserts.Values)
        {
            // The highest asked-for version that every inserting project supports on all its TFMs.
            var target = group.Select(g => g.Row.Target).Distinct()
                .OrderByDescending(NuGetVersion.Parse)
                .FirstOrDefault(t => group.All(g => FitsEveryFramework(g.Project, g.Row.Id, NuGetVersion.Parse(t), candidates)));
            if (target == null)
            {
                var reason = group.Count == 1
                    ? "the new version does not support every target framework of this project"
                    : "no version supports every target framework of the projects that get this package";
                group.ForEach(g => skipped.Add(new Skip(g.Row.Project, g.Row.Id, reason)));
                continue;
            }
            foreach (var (row, project, tfm) in group)
            {
                var inserts = Inserts(project, tfm, row with { Target = target }, skipped, repo).ToList();
                if (inserts.Count > 0) { edits.AddRange(inserts); restore.Add(project.Path); }
            }
        }

        foreach (var (key, siteRows) in bySite)
        {
            var site = sites.TryGetValue(key, out var s) ? s : null;
            if (site == null) { siteRows.ForEach(r => skipped.Add(new Skip(r.Project, r.Id, "site not found"))); continue; }
            if (!SiteAllowedForAll(site.File, repo, siteRows, skipped)) continue;
            if (RangeText.IsFloating(site.RawText)) { siteRows.ForEach(r => skipped.Add(new Skip(r.Project, r.Id, "floating version - restore picks the newest match"))); continue; }

            var users = consumers.TryGetValue(key, out var u) ? u : new List<Consumer>();
            var needsKnownVersionCheck = site.Kind == "property" || users.Select(c => c.Id).Distinct(StringComparer.OrdinalIgnoreCase).Count() > 1;
            // The site's own rewrite is checked independent of `users`: a site with no consumer at
            // all (pinning on, no PackageReference anywhere - `users.All` on an empty list is
            // vacuously true) must not still accept a target the rewrite itself cannot express
            // (e.g. a range whose upper bound is now below the new lower bound).
            var target = siteRows
                .Select(r => NuGetVersion.Parse(r.Target))
                .OrderByDescending(v => v)
                .FirstOrDefault(v => SiteRewriteOk(site, v) && users.All(c => Accepts(c, site, v, needsKnownVersionCheck, candidates)));
            if (target == null)
            {
                // A source failure while fetching one of the site's consumer ids explains an empty
                // candidate list better than the generic reason (same text Scan gives a row it could
                // not check for the same cause).
                var reason = users.Select(c => failedReason?.Invoke(c.Project, c.Tfm, c.Id)).FirstOrDefault(r => r != null)
                    ?? "no version fits every project that shares this version";
                siteRows.ForEach(r => skipped.Add(new Skip(r.Project, r.Id, reason)));
                continue;
            }

            var value = RangeText.Rewrite(site.RawText, target)!;
            edits.Add(new Edit("set", site.File, site.Form, site.ItemType, site.IdentityAttr, site.Identity,
                site.Condition, site.GroupCondition, site.Name, site.RawText, value, site.Line, site.Column));
            if (pendingInserts.TryGetValue(key, out var pending)) edits.AddRange(pending);
            foreach (var c in users)
            {
                restore.Add(c.Project);
                var change = new Change(c.Project, c.Id);
                if (!siteRows.Any(r => string.Equals(r.Project, c.Project, StringComparison.OrdinalIgnoreCase) &&
                                       string.Equals(r.Id, c.Id, StringComparison.OrdinalIgnoreCase)) &&
                    !also.Any(x => string.Equals(x.Project, change.Project, StringComparison.OrdinalIgnoreCase) &&
                                   string.Equals(x.Id, change.Id, StringComparison.OrdinalIgnoreCase)))
                    also.Add(change);
            }
        }

        return new UpgradePlan(Dedupe(edits), restore.ToList(), also, skipped);
    }

    /// Every (project, TFM, package id) that shares an edit site with one of [rows] (including the
    /// row's own), once per feed context ([FeedKey]): the caller can build a FeedContext from each. What
    /// a caller must have Candidate data for before calling [Plan] - Accepts' known-version and
    /// target-framework checks read it for every consumer of a touched site, from that consumer's own
    /// feeds, not only the row's. A transitive row adds its project's TFMs (the insert's framework check).
    /// A row whose project/TFM/site cannot be resolved contributes nothing (Plan skips it the same way).
    public static IReadOnlyList<(string ProjectPath, EvaluatedTfm Tfm, string Id)> IdsNeedingCandidates(
        IReadOnlyList<UpgradeRow> rows, IReadOnlyList<EvaluatedProject> all)
    {
        var byPath = all.ToDictionary(p => p.Path, StringComparer.OrdinalIgnoreCase);
        var (consumers, _) = BuildSiteMap(all);
        var result = new Dictionary<string, (string, EvaluatedTfm, string)>(StringComparer.OrdinalIgnoreCase);

        void Add(string projectPath, EvaluatedTfm t, string id) => result.TryAdd(FeedKey.Of(projectPath, t, id), (projectPath, t, id));

        void AddSite(string key)
        {
            if (!consumers.TryGetValue(key, out var list)) return;
            foreach (var c in list) Add(c.Project, c.Tfm, c.Id);
        }

        foreach (var row in rows)
        {
            if (!byPath.TryGetValue(row.Project, out var project) || project.Error != null) continue;
            var tfm = project.Frameworks.FirstOrDefault(f => f.Framework == row.Framework);
            if (tfm == null) continue;

            var (site, _, transitive, viaExistingCentral) = ResolveRowSite(tfm, row.Id);
            if (transitive)
            {
                // A new reference has no site, but Plan checks its target against the project's frameworks.
                foreach (var t in project.Frameworks) Add(project.Path, t, row.Id);
                continue;
            }
            if (site == null) continue;
            AddSite(SiteResolver.Key(site));
            // The row's own (project, tfm, id) needs candidates too, even when BuildSiteMap would
            // not see it as a consumer of this site (a project with no PackageReference of its own,
            // routed to an existing CPM central item): Plan counts it as a synthetic consumer there
            // (see the ViaExistingCentral branch), and its TFM/known-version check needs this data.
            Add(project.Path, tfm, row.Id);
            // Plan checks such a row on every TFM of the project, since the inserted reference applies to all.
            if (viaExistingCentral) foreach (var t in project.Frameworks) Add(project.Path, t, row.Id);
        }
        return result.Values.ToList();
    }

    /// Resolves where row (tfm, id) really lives, folding [SiteResolver.Resolve] together with the
    /// CPM existing-central-item case for an otherwise-transitive row (shared by [Plan] and
    /// [IdsNeedingCandidates] so this lookup exists in one place): when CPM is on and a
    /// PackageVersion item for [id] already exists, this is never "transitive" - route to that
    /// item's site (ViaExistingCentral = true), or, when the item has no editable site of its own
    /// (e.g. it is itself a chained or mixed property), skip with that item's own [Problem]. Only a
    /// genuinely absent central item (or CPM off) falls through as Transitive = true, for [Inserts].
    private static (ValueSite? Site, string? Problem, bool Transitive, bool ViaExistingCentral) ResolveRowSite(EvaluatedTfm tfm, string id)
    {
        var (site, problem, transitive) = SiteResolver.Resolve(tfm, id);
        if (!transitive) return (site, problem, false, false);
        if (!tfm.CpmEnabled) return (null, null, true, false);
        var central = tfm.Items.FirstOrDefault(i => i.ItemType == "PackageVersion" && string.Equals(i.Id, id, StringComparison.OrdinalIgnoreCase));
        if (central == null) return (null, null, true, false);
        return (central.VersionSite, central.VersionSite == null ? (central.SiteProblem ?? "no version is set for this package") : null, false, true);
    }

    /// Every consumer of every site, computed once, and the site itself by key. Shared by [Plan],
    /// [IdsNeedingCandidates] and [SharedVersions] so the traversal exists in one place.
    internal static (Dictionary<string, List<Consumer>> Consumers, Dictionary<string, ValueSite> Sites) BuildSiteMap(IReadOnlyList<EvaluatedProject> all)
    {
        var consumers = new Dictionary<string, List<Consumer>>();
        var sites = new Dictionary<string, ValueSite>();
        foreach (var p in all)
        foreach (var t in p.Frameworks)
        foreach (var id in t.Items.Select(i => i.Id).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var (site, _, transitive) = SiteResolver.Resolve(t, id);
            if (site == null || transitive) continue;
            var key = SiteResolver.Key(site);
            sites[key] = site;
            if (!consumers.TryGetValue(key, out var list)) consumers[key] = list = new List<Consumer>();
            list.Add(new Consumer(p.Path, t, id, RowBuilder.Requested(t, id)));
        }
        return (consumers, sites);
    }

    /// Whether rewriting [site]'s raw text for [target] even produces valid, satisfying text -
    /// independent of any consumer (a site can have zero consumers - see [Accepts]'s caller).
    private static bool SiteRewriteOk(ValueSite site, NuGetVersion target)
    {
        var rewritten = RangeText.Rewrite(site.RawText, target);
        return rewritten != null && VersionRange.TryParse(rewritten, out var range) && range.Satisfies(target);
    }

    private static bool Accepts(Consumer c, ValueSite site, NuGetVersion target, bool needsKnownVersionCheck, Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> candidates)
    {
        if (!SiteRewriteOk(site, target)) return false;
        if (c.Requested != null && VersionRange.TryParse(c.Requested, out var current) && current.HasUpperBound &&
            !current.Satisfies(target) && !(current.HasLowerAndUpperBounds && current.MinVersion == current.MaxVersion))
            return false;

        // Version lock and pre-release of other consumers are not checked (spec): only whether the
        // target is a real, published version for this consumer's id, and whether it supports this
        // consumer's TFM. When the caller provided no candidate data for this id, a site that does
        // not require the known-version check is still accepted; one that does (property, or a site
        // shared by more than one id) is not.
        var candidate = candidates(c.Project, c.Tfm, c.Id).FirstOrDefault(x => x.Version == target);
        if (candidate == null) return !needsKnownVersionCheck;
        return SupportsTfm(c.Tfm, candidate);
    }

    /// Whether [version] of [id] supports every TFM of [project]: a new reference applies to all of
    /// them, not only the row's. Without candidate data for a TFM there is nothing to check (same as Accepts).
    private static bool FitsEveryFramework(EvaluatedProject project, string id, NuGetVersion version,
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> candidates) =>
        project.Frameworks.All(t => candidates(project.Path, t, id).FirstOrDefault(x => x.Version == version) is not { } known || SupportsTfm(t, known));

    private static bool SupportsTfm(EvaluatedTfm t, Candidate candidate) =>
        // An empty TFM string (should not occur in real evaluated data, but must never crash the
        // planner) has nothing for NuGetFramework.Parse to work with: skip the TFM check for it.
        string.IsNullOrEmpty(t.Framework) || TargetSelector.SupportsFramework(NuGetFramework.Parse(t.Framework), candidate.DependencyFrameworks);

    private static IEnumerable<Edit> Inserts(EvaluatedProject p, EvaluatedTfm t, UpgradeRow row, List<Skip> skipped, Repo repo)
    {
        if (t.CpmEnabled)
        {
            if (t.CentralFile == null) { skipped.Add(new Skip(row.Project, row.Id, "central package file not found")); yield break; }
            if (!SiteAllowed(t.CentralFile, repo, row.Project, row.Id, skipped)) yield break;
            if (!t.TransitivePinning && !SiteAllowed(p.Path, repo, row.Project, row.Id, skipped)) yield break;
            yield return new Edit("insert", t.CentralFile, "item", "PackageVersion", "Include", row.Id, null, null, "Version", null, row.Target, 0, 0);
            if (!t.TransitivePinning)
                yield return new Edit("insert", p.Path, "item", "PackageReference", "Include", row.Id, null, null, "", null, "", 0, 0);
            yield break;
        }
        if (!SiteAllowed(p.Path, repo, row.Project, row.Id, skipped)) yield break;
        yield return new Edit("insert", p.Path, "item", "PackageReference", "Include", row.Id, null, null, "Version", null, row.Target, 0, 0);
    }

    /// Inserts get the same repository/read-only guard a "set" edit gets on its site (item 2 of the
    /// fix round): true when [file] may be written to, false (with a Skip recorded) otherwise.
    private static bool SiteAllowed(string file, Repo repo, string project, string id, List<Skip> skipped)
    {
        if (!repo.Contains(file)) { skipped.Add(new Skip(project, id, "the version is set outside the repository")); return false; }
        if (File.Exists(file) && new FileInfo(file).IsReadOnly) { skipped.Add(new Skip(project, id, "the file is read-only")); return false; }
        return true;
    }

    private static bool SiteAllowedForAll(string file, Repo repo, List<UpgradeRow> rows, List<Skip> skipped)
    {
        if (!repo.Contains(file)) { rows.ForEach(r => skipped.Add(new Skip(r.Project, r.Id, "the version is set outside the repository"))); return false; }
        if (File.Exists(file) && new FileInfo(file).IsReadOnly) { rows.ForEach(r => skipped.Add(new Skip(r.Project, r.Id, "the file is read-only"))); return false; }
        return true;
    }

    /// Collapses inserts that target the same file, item type and identity (several rows can each
    /// ask for the same transitive id, or two projects can both need the same central PackageVersion):
    /// one edit survives, the one with the highest parsed Value (a version), or any of them when
    /// Value carries no version (the versionless PackageReference insert).
    private static List<Edit> Dedupe(List<Edit> edits)
    {
        NuGetVersion? V(Edit e) => NuGetVersion.TryParse(e.Value, out var v) ? v : null;
        var inserts = edits.Where(e => e.Op == "insert")
            .GroupBy(e => (e.File.ToLowerInvariant(), e.ItemType, e.Identity?.ToLowerInvariant()))
            .Select(g => g.OrderByDescending(V).First());
        return edits.Where(e => e.Op != "insert").Concat(inserts).ToList();
    }

    private static bool IsUnder(string file, string root) => PathRules.IsUnder(file, root);
}
