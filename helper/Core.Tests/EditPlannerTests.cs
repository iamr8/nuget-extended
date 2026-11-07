using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Planning;
using NuGetExtended.Core.Protocol;
using NuGetExtended.Core.Versions;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class EditPlannerTests
{
    private const string Root = "/repo";

    private static ValueSite Meta(string file, int line, string raw, string type = "PackageReference", string id = "Polly", string name = "Version") =>
        new("metadata", file, line, 5, name, "attribute", raw, type, "Include", id, null, null);

    private static ValueSite Prop(string file, int line, string name, string raw) =>
        new("property", file, line, 5, name, "property", raw, null, null, null, null, null);

    private static PackageItem Ref(string id, string? version, ValueSite? site) => new(id, "PackageReference", version, null, site, null, null);
    private static PackageItem Central(string id, string version, ValueSite site) => new(id, "PackageVersion", version, null, site, null, null);

    private static EvaluatedProject Proj(string name, bool cpm, params PackageItem[] items) =>
        new($"{Root}/{name}/{name}.csproj", name, new[] { new EvaluatedTfm("net8.0", "", cpm, false, $"{Root}/Directory.Packages.props", items, Array.Empty<string>()) },
            new[] { $"{Root}/{name}/{name}.csproj" }, null);

    /// A candidate with no dependency-framework groups (supports any TFM) unless [frameworks] is given.
    private static Candidate Cand(string version, params string[] frameworks) =>
        new(NuGetVersion.Parse(version), true, null, frameworks.Select(NuGetFramework.Parse).ToList());

    private static readonly Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> FixedCandidates = (_, _, _) => new[] { Cand("7.2.4"), Cand("13.0.4") };

    private static UpgradePlan Plan(IEnumerable<UpgradeRow> rows, params EvaluatedProject[] all) =>
        EditPlanner.Plan(rows.ToList(), all, FixedCandidates, new ScanOptions(), Root);

    [Fact]
    public void RangeKeepsShape()
    {
        var a = Proj("A", false, Ref("Polly", "[7.0.0,8.0.0)", Meta($"{Root}/A/A.csproj", 4, "[7.0.0,8.0.0)")));

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);

        var edit = Assert.Single(plan.Edits);
        Assert.Equal("[7.2.4,8.0.0)", edit.Value);
        Assert.Equal("[7.0.0,8.0.0)", edit.Expected);
        Assert.Equal("attribute", edit.Target);
    }

    [Fact]
    public void PinMovesToNewPin()
    {
        var a = Proj("A", false, Ref("Polly", "[7.0.0]", Meta($"{Root}/A/A.csproj", 4, "[7.0.0]")));

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);

        Assert.Equal("[7.2.4]", Assert.Single(plan.Edits).Value);
    }

    [Fact]
    public void UpperOnlyRangeAtTargetBecomesRangeShapedPin()
    {
        var a = Proj("A", false, Ref("Polly", "(,3.0.0]", Meta($"{Root}/A/A.csproj", 4, "(,3.0.0]")));

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "3.0.0") }, a);

        Assert.Equal("[3.0.0,3.0.0]", Assert.Single(plan.Edits).Value);
    }

    [Fact]
    public void CentralVersionIsOneEditForAllProjectsAndListsOthers()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        var a = Proj("A", true, Ref("Polly", null, null), Central("Polly", "7.0.0", central));
        var b = Proj("B", true, Ref("Polly", null, null), Central("Polly", "7.0.0", central));

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a, b);

        Assert.Single(plan.Edits);
        Assert.Equal($"{Root}/Directory.Packages.props", plan.Edits[0].File);
        Assert.Equal(new Change(b.Path, "Polly"), Assert.Single(plan.AlsoChanges));
        Assert.Equal(2, plan.RestoreProjects.Count);
    }

    [Fact]
    public void CentralConsumerOnIncompatibleFrameworkBlocksTarget()
    {
        // Same id, one site, two projects on different TFMs: the target framework check applies to
        // every consumer of a shared site, not only the row's own project.
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, false, $"{Root}/Directory.Packages.props", new[] { Ref("Polly", null, null), Central("Polly", "7.0.0", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/A/A.csproj" }, null);
        var b = new EvaluatedProject($"{Root}/B/B.csproj", "B",
            new[] { new EvaluatedTfm("net6.0", "", true, false, $"{Root}/Directory.Packages.props", new[] { Ref("Polly", null, null), Central("Polly", "7.0.0", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/B/B.csproj" }, null);
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> net8Only = (_, _, _) => new[] { Cand("7.2.4", "net8.0") };

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a, b },
            net8Only, new ScanOptions(), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    // Break: Accepts reads one consumer's candidate data for every consumer of the site.
    [Fact]
    public void EachConsumerIsCheckedAgainstItsOwnCandidateData()
    {
        // One central site, two projects with their own feeds. Only B's feed lists 7.2.4 as net8.0-only,
        // and B targets net6.0: A's data alone would accept it.
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        EvaluatedProject P(string name, string tfm) => new($"{Root}/{name}/{name}.csproj", name,
            new[] { new EvaluatedTfm(tfm, "", true, false, $"{Root}/Directory.Packages.props", new[] { Ref("Polly", null, null), Central("Polly", "7.0.0", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/{name}/{name}.csproj" }, null);
        var a = P("A", "net8.0");
        var b = P("B", "net6.0");
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> perProject =
            (path, _, _) => path == a.Path ? new[] { Cand("7.2.4") } : new[] { Cand("7.2.4", "net8.0") };

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a, b },
            perProject, new ScanOptions(), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void ConsumerWithEmptyFrameworkStringDoesNotThrowAndSkipsTfmCheck()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, false, $"{Root}/Directory.Packages.props", new[] { Ref("Polly", null, null), Central("Polly", "7.0.0", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/A/A.csproj" }, null);
        var b = new EvaluatedProject($"{Root}/B/B.csproj", "B",
            new[] { new EvaluatedTfm("", "", true, false, $"{Root}/Directory.Packages.props", new[] { Ref("Polly", null, null), Central("Polly", "7.0.0", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/B/B.csproj" }, null);

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a, b);

        Assert.Single(plan.Edits);
    }

    [Fact]
    public void SharedPropertyListsOtherPackageAndNeedsVersionForAll()
    {
        var prop = Prop($"{Root}/Directory.Build.props", 2, "AspVer", "7.0.0");
        var a = Proj("A", false, Ref("Polly", "7.0.0", prop), Ref("Polly.Extensions", "7.0.0", prop));

        var ok = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a },
            (_, _, _) => new[] { Cand("7.2.4") }, new ScanOptions(), Root);
        Assert.Equal("property", Assert.Single(ok.Edits).Target);
        Assert.Contains(new Change(a.Path, "Polly.Extensions"), ok.AlsoChanges);

        var missing = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a },
            (_, _, id) => id == "Polly" ? new[] { Cand("7.2.4") } : Array.Empty<Candidate>(), new ScanOptions(), Root);
        Assert.Empty(missing.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(missing.Skipped).Reason);
    }

    [Fact]
    public void ConsumerWithNarrowerRangeBlocksTarget()
    {
        var prop = Prop($"{Root}/Directory.Build.props", 2, "V", "[7.0.0,7.1.0)");
        var a = Proj("A", false, Ref("Polly", "[7.0.0,7.1.0)", prop));

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void VersionOverrideUnderCpmEditsTheOverrideSiteNotCentral()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        var overrideSite = Meta($"{Root}/A/A.csproj", 5, "7.0.0", "PackageReference", "Polly", "VersionOverride");
        var reference = new PackageItem("Polly", "PackageReference", null, "7.0.0", central, overrideSite, null);
        var a = Proj("A", true, reference);

        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);

        var edit = Assert.Single(plan.Edits);
        Assert.Equal($"{Root}/A/A.csproj", edit.File);
        Assert.Equal("VersionOverride", edit.Name);
    }

    [Fact]
    public void FloatingIsSkipped()
    {
        var a = Proj("A", false, Ref("Polly", "7.*", Meta($"{Root}/A/A.csproj", 4, "7.*")));
        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);
        Assert.Equal("floating version - restore picks the newest match", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void OutsideRepositoryIsSkipped()
    {
        var a = Proj("A", false, Ref("Polly", "7.0.0", Meta("/elsewhere/Directory.Build.props", 4, "7.0.0")));
        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);
        Assert.Equal("the version is set outside the repository", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void SiteInThePackageFolderInsideTheRepositoryIsSkipped()
    {
        // A home-directory dotfiles repo makes the repository root `~`, so a package's own
        // build/*.props (an imported Update=) would count as inside it.
        var a = Proj("A", false, Ref("Polly", "7.0.0", Meta($"{Root}/.nuget/packages/polly/7.0.0/build/Polly.props", 4, "7.0.0")));
        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a },
            FixedCandidates, new ScanOptions(), Root, outsideRoots: new[] { $"{Root}/.nuget/packages/" });
        Assert.Empty(plan.Edits);
        Assert.Equal("the version is set outside the repository", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void RepositoryCaseRuleFollowsTheOs()
    {
        // One rule with ProjectEvaluator: ignore case on Windows only.
        var a = Proj("A", false, Ref("Polly", "7.0.0", Meta("/REPO/Directory.Build.props", 4, "7.0.0")));
        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);
        Assert.Equal(OperatingSystem.IsWindows() ? 1 : 0, plan.Edits.Count);
    }

    [Fact]
    public void TransitiveUnderCpmInsertsCentralAndReference()
    {
        var a = Proj("A", true);
        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Equal(2, plan.Edits.Count);
        Assert.Contains(plan.Edits, e => e.Op == "insert" && e.ItemType == "PackageVersion" && e.File == $"{Root}/Directory.Packages.props" && e.Value == "2.1.0");
        Assert.Contains(plan.Edits, e => e.Op == "insert" && e.ItemType == "PackageReference" && e.File == a.Path);
    }

    // Break: Plan inserts a transitive reference without checking the target against every TFM of the project.
    [Fact]
    public void TransitiveInsertIsSkippedWhenTheTargetDropsAFrameworkOfTheProject()
    {
        // A targets net8.0 and net6.0. Leaf 2.1.0 only declares net8.0, and the inserted reference
        // would apply to both frameworks, so the net6.0 build would break.
        var tfms = new[] { "net8.0", "net6.0" }.Select(f =>
            new EvaluatedTfm(f, "", true, false, $"{Root}/Directory.Packages.props", Array.Empty<PackageItem>(), Array.Empty<string>())).ToArray();
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A", tfms, new[] { $"{Root}/A/A.csproj" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0", "net8.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Empty(plan.RestoreProjects);
        Assert.Equal("the new version does not support every target framework of this project", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void TransitiveInsertIsKeptWhenTheTargetSupportsEveryFrameworkOfTheProject()
    {
        var tfms = new[] { "net8.0", "net6.0" }.Select(f =>
            new EvaluatedTfm(f, "", false, false, null, Array.Empty<PackageItem>(), Array.Empty<string>())).ToArray();
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A", tfms, new[] { $"{Root}/A/A.csproj" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0", "net6.0", "net8.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Equal("insert", Assert.Single(plan.Edits).Op);
        Assert.Empty(plan.Skipped);
    }

    [Fact]
    public void TransitiveOptionOffIsSkipped()
    {
        var a = Proj("A", true);
        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("transitive package - enable transitive upgrades", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void CpmOffTransitiveInsertsPackageReferenceWithVersion()
    {
        var a = Proj("A", false);
        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        var edit = Assert.Single(plan.Edits);
        Assert.Equal("insert", edit.Op);
        Assert.Equal("PackageReference", edit.ItemType);
        Assert.Equal(a.Path, edit.File);
        Assert.Equal("2.1.0", edit.Value);
    }

    [Fact]
    public void TransitivePinningInsertsOnlyCentralNoReference()
    {
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, true, $"{Root}/Directory.Packages.props", Array.Empty<PackageItem>(), Array.Empty<string>()) },
            new[] { $"{Root}/A/A.csproj" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        var edit = Assert.Single(plan.Edits);
        Assert.Equal("PackageVersion", edit.ItemType);
    }

    [Fact]
    public void ExistingCentralItemWithNoEditableSiteIsSkippedNotDuplicated()
    {
        // The central PackageVersion item exists (so a duplicate insert must never happen) but has
        // no editable site of its own - e.g. its Version chains to another property.
        var central = new PackageItem("Leaf", "PackageVersion", "2.0.0", null, null, null,
            "property 'XVer' points to another property");
        var a = Proj("A", true, central);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("property 'XVer' points to another property", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void IdsNeedingCandidatesIncludesTheRowsOwnIdForAnExistingCentralSiteWithNoOtherConsumer()
    {
        // BuildSiteMap would not see A as a consumer of "Leaf" (SiteResolver treats it as
        // transitive: no PackageReference), yet Plan counts A as a synthetic consumer of the
        // existing central site (see TransitivePinningWithNoDirectConsumerRejectsTargetOutsideCentralRange
        // below) - so the handler must fetch candidates for "Leaf" too, or that check never runs.
        var central = Meta($"{Root}/Directory.Packages.props", 3, "[1.0.0,2.0.0)", "PackageVersion", "Leaf");
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, true, $"{Root}/Directory.Packages.props",
                new[] { Central("Leaf", "[1.0.0,2.0.0)", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/A/A.csproj" }, null);

        var need = EditPlanner.IdsNeedingCandidates(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a });

        var entry = Assert.Single(need);
        Assert.Equal("Leaf", entry.Id);
        Assert.Equal(a.Path, entry.ProjectPath);
    }

    // Break: IdsNeedingCandidates skips a transitive row, so the handler fetches nothing for the insert's framework check.
    [Fact]
    public void IdsNeedingCandidatesIncludesATransitiveRowsOwnId()
    {
        var a = Proj("A", false);

        var need = EditPlanner.IdsNeedingCandidates(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a });

        Assert.Equal((a.Path, "Leaf"), (need.Single().ProjectPath, need.Single().Id));
    }

    // Break: IdsNeedingCandidates keeps one project per id (TryAdd by id), so the second feed context is never fetched.
    [Fact]
    public void IdsNeedingCandidatesListsEachConsumersOwnFeedContext()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "7.0.0", "PackageVersion");
        var a = Proj("A", true, Ref("Polly", null, null), Central("Polly", "7.0.0", central));
        var b = Proj("B", true, Ref("Polly", null, null), Central("Polly", "7.0.0", central));

        var need = EditPlanner.IdsNeedingCandidates(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a, b });

        Assert.Equal(new[] { a.Path, b.Path }, need.Select(n => n.ProjectPath).OrderBy(x => x));
    }

    [Fact]
    public void IdsNeedingCandidatesListsOneFeedContextOnceEvenWithManyFrameworks()
    {
        var site = Meta($"{Root}/A/A.csproj", 4, "7.0.0");
        EvaluatedTfm Tfm(string f, params string[] sources) =>
            new(f, "", false, false, null, new[] { Ref("Polly", "7.0.0", site) }, sources);
        var same = new EvaluatedProject($"{Root}/A/A.csproj", "A", new[] { Tfm("net8.0"), Tfm("net6.0") }, new[] { $"{Root}/A/A.csproj" }, null);
        var split = new EvaluatedProject($"{Root}/A/A.csproj", "A", new[] { Tfm("net8.0", "/feed1"), Tfm("net6.0", "/feed2") }, new[] { $"{Root}/A/A.csproj" }, null);
        var row = new[] { new UpgradeRow(same.Path, "net8.0", "Polly", "7.2.4") };

        Assert.Single(EditPlanner.IdsNeedingCandidates(row, new[] { same }));
        Assert.Equal(2, EditPlanner.IdsNeedingCandidates(row, new[] { split }).Count);
    }

    [Fact]
    public void TransitivePinningWithNoDirectConsumerRejectsTargetOutsideCentralRange()
    {
        // Pinning on, no PackageReference anywhere for Leaf: the row's own project is the only
        // thing touching this site, and it is not a "consumer" by SiteResolver's rules (no
        // PackageReference), so `users` is empty. Without a site-level rewrite check independent of
        // consumers, `users.All(...)` on an empty list is vacuously true and would accept a target
        // above the range, writing an inverted "[2.1.0,2.0.0)".
        var central = Meta($"{Root}/Directory.Packages.props", 3, "[1.0.0,2.0.0)", "PackageVersion", "Leaf");
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, true, $"{Root}/Directory.Packages.props",
                new[] { Central("Leaf", "[1.0.0,2.0.0)", central) }, Array.Empty<string>()) },
            new[] { $"{Root}/A/A.csproj" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void ReadOnlyFileIsSkipped()
    {
        var dir = Path.Combine(Path.GetTempPath(), "a9-readonly-" + Guid.NewGuid());
        Directory.CreateDirectory(dir);
        var file = Path.Combine(dir, "A.csproj");
        File.WriteAllText(file, "");
        var info = new FileInfo(file);
        info.IsReadOnly = true;
        try
        {
            var a = new EvaluatedProject(file, "A",
                new[] { new EvaluatedTfm("net8.0", "", false, false, null,
                    new[] { Ref("Polly", "7.0.0", Meta(file, 4, "7.0.0")) }, Array.Empty<string>()) },
                new[] { file }, null);

            var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, new[] { a },
                FixedCandidates, new ScanOptions(), dir);

            Assert.Equal("the file is read-only", Assert.Single(plan.Skipped).Reason);
        }
        finally
        {
            info.IsReadOnly = false;
            Directory.Delete(dir, true);
        }
    }

    private static EvaluatedProject CpmProj(string name, string[] frameworks, params PackageItem[] items) =>
        new($"{Root}/{name}/{name}.csproj", name,
            frameworks.Select(f => new EvaluatedTfm(f, "", true, false, $"{Root}/Directory.Packages.props", items, Array.Empty<string>())).ToArray(),
            new[] { $"{Root}/{name}/{name}.csproj" }, null);

    // Break (#61 gap 1): two projects insert the same new central PackageVersion, and the higher
    // version wins without a check against the other project's framework.
    [Fact]
    public void SharedCentralInsertPicksAVersionEveryInsertingProjectSupports()
    {
        var a = CpmProj("A", new[] { "net8.0" });
        var b = CpmProj("B", new[] { "net6.0" });
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> candidates =
            (_, _, _) => new[] { Cand("8.0.0", "net6.0", "net8.0"), Cand("9.0.0", "net8.0") };

        var plan = EditPlanner.Plan(
            new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "9.0.0"), new UpgradeRow(b.Path, "net6.0", "Leaf", "8.0.0") },
            new[] { a, b }, candidates, new ScanOptions(Transitive: true), Root);

        Assert.Equal("8.0.0", Assert.Single(plan.Edits, e => e.ItemType == "PackageVersion").Value);
        Assert.Equal(2, plan.Edits.Count(e => e.ItemType == "PackageReference"));
        Assert.Empty(plan.Skipped);
    }

    // Break (#61 gap 1): no shared version fits, yet the inserts still happen.
    [Fact]
    public void SharedCentralInsertIsSkippedWhenNoVersionFitsEveryProject()
    {
        var a = CpmProj("A", new[] { "net8.0" });
        var b = CpmProj("B", new[] { "net6.0" });
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> candidates =
            (_, _, _) => new[] { Cand("8.0.0", "net8.0"), Cand("9.0.0", "net8.0") };

        var plan = EditPlanner.Plan(
            new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "9.0.0"), new UpgradeRow(b.Path, "net6.0", "Leaf", "8.0.0") },
            new[] { a, b }, candidates, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal(2, plan.Skipped.Count);
    }

    // Break (#61 gap 2): the existing-central route checks only the row's own framework, but the
    // inserted versionless PackageReference applies to every framework of the project.
    [Fact]
    public void ExistingCentralRouteChecksEveryFrameworkOfTheProject()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "2.0.0", "PackageVersion", "Leaf");
        var a = CpmProj("A", new[] { "net8.0", "net6.0" }, Central("Leaf", "2.0.0", central));
        var b = CpmProj("B", new[] { "net8.0" }, Central("Leaf", "2.0.0", central), Ref("Leaf", null, null));

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a, b },
            (_, _, _) => new[] { Cand("2.1.0", "net8.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    // Break (#61 gap 2): IdsNeedingCandidates fetches data only for the row's own framework, so the
    // check above has nothing to read for a framework with its own sources (TFMs with the same
    // sources share one fetch).
    [Fact]
    public void IdsNeedingCandidatesIncludesEveryFrameworkOfAnExistingCentralRow()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "2.0.0", "PackageVersion", "Leaf");
        var items = new[] { Central("Leaf", "2.0.0", central) };
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A", new[]
        {
            new EvaluatedTfm("net8.0", "", true, false, $"{Root}/Directory.Packages.props", items, Array.Empty<string>()),
            new EvaluatedTfm("net6.0", "", true, false, $"{Root}/Directory.Packages.props", items, new[] { "/feeds/net6" }),
        }, new[] { $"{Root}/A/A.csproj" }, null);

        var ids = EditPlanner.IdsNeedingCandidates(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a });

        Assert.Equal(new[] { "net6.0", "net8.0" }, ids.Where(x => x.ProjectPath == a.Path).Select(x => x.Tfm.Framework).OrderBy(f => f));
    }

    [Fact]
    public void TransitiveWithExistingCentralItemSetsCentralAndInsertsReference()
    {
        // A does not reference Leaf directly, but its own evaluated items still carry the central
        // PackageVersion entry (imported from Directory.Packages.props, same as any other project's
        // evaluation would). B is a real, direct consumer.
        var central = Meta($"{Root}/Directory.Packages.props", 3, "2.0.0", "PackageVersion", "Leaf");
        var a = Proj("A", true, Central("Leaf", "2.0.0", central));
        var b = Proj("B", true, Central("Leaf", "2.0.0", central), Ref("Leaf", null, null));

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a, b },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Equal(2, plan.Edits.Count);
        Assert.Contains(plan.Edits, e => e.Op == "set" && e.File == $"{Root}/Directory.Packages.props" && e.Value == "2.1.0");
        Assert.Contains(plan.Edits, e => e.Op == "insert" && e.ItemType == "PackageReference" && e.File == a.Path);
        Assert.Contains(new Change(b.Path, "Leaf"), plan.AlsoChanges);
    }

    [Fact]
    public void TransitiveWithExistingCentralItemSkipsCleanlyWhenAConsumerBlocksTheTarget()
    {
        // Before the fix, the versionless PackageReference insert into A was added as soon as the
        // row was seen, independent of whether the central "set" edit the row also feeds ever
        // succeeded - so a blocked target (here: B's net6.0 can't use a net8.0-only version) left an
        // orphan insert with no matching edit for the version it depends on.
        var central = Meta($"{Root}/Directory.Packages.props", 3, "2.0.0", "PackageVersion", "Leaf");
        var a = Proj("A", true, Central("Leaf", "2.0.0", central));
        var b = new EvaluatedProject($"{Root}/B/B.csproj", "B",
            new[] { new EvaluatedTfm("net6.0", "", true, false, $"{Root}/Directory.Packages.props",
                new[] { Central("Leaf", "2.0.0", central), Ref("Leaf", null, null) }, Array.Empty<string>()) },
            new[] { $"{Root}/B/B.csproj" }, null);
        Func<string, EvaluatedTfm, string, IReadOnlyList<Candidate>> net8Only = (_, _, _) => new[] { Cand("2.1.0", "net8.0") };

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a, b },
            net8Only, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("no version fits every project that shares this version", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void TransitiveExistingCentralReferenceInsertOutsideRepoIsSkippedNotRoutedToTheSite()
    {
        var central = Meta($"{Root}/Directory.Packages.props", 3, "2.0.0", "PackageVersion", "Leaf");
        var a = new EvaluatedProject("/elsewhere/A/A.csproj", "A",
            new[] { new EvaluatedTfm("net8.0", "", true, false, $"{Root}/Directory.Packages.props",
                new[] { Central("Leaf", "2.0.0", central) }, Array.Empty<string>()) },
            new[] { "/elsewhere/A/A.csproj" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Leaf", "2.1.0") }, new[] { a },
            (_, _, _) => new[] { Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("the version is set outside the repository", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void TwoTransitiveRowsForSameIdDedupeToOneCentralInsertWithHighestTarget()
    {
        var a = Proj("A", true);
        var b = Proj("B", true);

        var plan = EditPlanner.Plan(new[]
        {
            new UpgradeRow(a.Path, "net8.0", "Leaf", "2.0.0"),
            new UpgradeRow(b.Path, "net8.0", "Leaf", "2.1.0"),
        }, new[] { a, b }, (_, _, _) => new[] { Cand("2.0.0"), Cand("2.1.0") }, new ScanOptions(Transitive: true), Root);

        var central = Assert.Single(plan.Edits, e => e.ItemType == "PackageVersion");
        Assert.Equal("2.1.0", central.Value);
        Assert.Contains(plan.Edits, e => e.ItemType == "PackageReference" && e.File == a.Path);
        Assert.Contains(plan.Edits, e => e.ItemType == "PackageReference" && e.File == b.Path);
    }

    [Fact]
    public void MultiTfmProjectHasSeparateSitesPerFramework()
    {
        var net8 = new EvaluatedTfm("net8.0", "", false, false, null,
            new[] { Ref("Polly", "7.0.0", Meta($"{Root}/A/A.csproj", 4, "7.0.0")) }, Array.Empty<string>());
        var net6 = new EvaluatedTfm("net6.0", "", false, false, null,
            new[] { Ref("Polly", "6.0.0", Meta($"{Root}/A/A.csproj", 8, "6.0.0")) }, Array.Empty<string>());
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A", new[] { net8, net6 }, new[] { $"{Root}/A/A.csproj" }, null);

        var plan = Plan(new[]
        {
            new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4"),
            new UpgradeRow(a.Path, "net6.0", "Polly", "6.0.1"),
        }, a);

        Assert.Equal(2, plan.Edits.Count);
    }

    [Fact]
    public void RowWithUnknownFrameworkIsSkippedNotFallenBackTo()
    {
        var a = Proj("A", false, Ref("Polly", "7.0.0", Meta($"{Root}/A/A.csproj", 4, "7.0.0")));
        var plan = Plan(new[] { new UpgradeRow(a.Path, "net9.0", "Polly", "7.2.4") }, a);
        Assert.Equal("target framework not found - re-scan", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void RowOnFailedProjectIsSkippedWithItsError()
    {
        var a = new EvaluatedProject($"{Root}/A/A.csproj", "A", Array.Empty<EvaluatedTfm>(), Array.Empty<string>(), "restore failed");
        var plan = Plan(new[] { new UpgradeRow(a.Path, "net8.0", "Polly", "7.2.4") }, a);
        Assert.Equal("restore failed", Assert.Single(plan.Skipped).Reason);
    }

    [Fact]
    public void DirectiveSiteProducesASetEditWithDirectiveTarget()
    {
        // A file-based app (.cs, SDK 10+): the version site is the `#:package` directive line
        // itself, not a project file element - Target must come through as "directive", not
        // "attribute"/"child"/"property", and none of the A9 checks (rewrite, repo root,
        // read-only, TFM) should treat it any differently from an ordinary metadata site.
        var site = new ValueSite("directive", $"{Root}/app.cs", 2, 1, "Humanizer.Core", "directive", "2.14.1", null, null, null, null, null);
        var item = new PackageItem("Humanizer.Core", "PackageReference", "2.14.1", null, site, null, null);
        var a = new EvaluatedProject($"{Root}/app.cs", "app",
            new[] { new EvaluatedTfm("net10.0", "", false, false, null, new[] { item }, Array.Empty<string>()) },
            new[] { $"{Root}/app.cs" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net10.0", "Humanizer.Core", "3.0.10") }, new[] { a },
            (_, _, _) => new[] { Cand("3.0.10") }, new ScanOptions(), Root);

        var edit = Assert.Single(plan.Edits);
        Assert.Equal("set", edit.Op);
        Assert.Equal("directive", edit.Target);
        Assert.Equal("Humanizer.Core", edit.Name);
        Assert.Equal("2.14.1", edit.Expected);
        Assert.Equal("3.0.10", edit.Value);
        Assert.Contains(a.Path, plan.RestoreProjects);
    }

    [Fact]
    public void DirectiveWithNoVersionIsSkippedNotInserted()
    {
        // `#:package Serilog` with no `@version`: FileBasedApps.Evaluate records this as a
        // PackageItem with no VersionSite and a SiteProblem - there is nowhere to write the edit.
        var item = new PackageItem("Serilog", "PackageReference", null, null, null, null, "the directive has no version");
        var a = new EvaluatedProject($"{Root}/app.cs", "app",
            new[] { new EvaluatedTfm("net10.0", "", false, false, null, new[] { item }, Array.Empty<string>()) },
            new[] { $"{Root}/app.cs" }, null);

        var plan = EditPlanner.Plan(new[] { new UpgradeRow(a.Path, "net10.0", "Serilog", "3.0.0") }, new[] { a },
            (_, _, _) => new[] { Cand("3.0.0") }, new ScanOptions(), Root);

        Assert.Empty(plan.Edits);
        Assert.Equal("the directive has no version", Assert.Single(plan.Skipped).Reason);
    }
}
