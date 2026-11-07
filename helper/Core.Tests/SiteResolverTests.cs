using NuGetExtended.Core.Model;
using NuGetExtended.Core.Planning;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class SiteResolverTests
{
    private const string Central = "/repo/Directory.Packages.props";
    private const string Csproj = "/repo/A/A.csproj";

    private static ValueSite Site(string file, string name, string itemType) =>
        new("metadata", file, 3, 5, name, "attribute", "1.0.0", itemType, "Include", "Lib", null, null);

    private static PackageItem Reference(string? version = null, string? versionOverride = null) =>
        new("Lib", "PackageReference", version, versionOverride,
            version == null ? null : Site(Csproj, "Version", "PackageReference"),
            versionOverride == null ? null : Site(Csproj, "VersionOverride", "PackageReference"), null);

    private static PackageItem CentralItem(string itemType = "PackageVersion") =>
        new("Lib", itemType, "1.0.0", null, Site(Central, "Version", itemType), null, null);

    private static EvaluatedTfm Tfm(bool cpm, params PackageItem[] items) =>
        new("net8.0", "", cpm, false, cpm ? Central : null, items, Array.Empty<string>());

    // Break: IsCentral returns false for a CPM row that reads the central PackageVersion.
    [Fact]
    public void CpmPackageVersionIsCentral() =>
        Assert.True(SiteResolver.IsCentral(Tfm(true, Reference(), CentralItem()), "Lib"));

    // Break: IsCentral ignores VersionOverride and reports the central item.
    [Fact]
    public void VersionOverrideIsNotCentral() =>
        Assert.False(SiteResolver.IsCentral(Tfm(true, Reference(versionOverride: "2.0.0"), CentralItem()), "Lib"));

    // Break: IsCentral only looks at PackageVersion, not GlobalPackageReference.
    [Fact]
    public void GlobalPackageReferenceIsCentral() =>
        Assert.True(SiteResolver.IsCentral(Tfm(true, CentralItem("GlobalPackageReference")), "Lib"));

    // Break: IsCentral returns true whenever a PackageVersion exists, even with CPM off.
    [Fact]
    public void DirectVersionWithoutCpmIsNotCentral() =>
        Assert.False(SiteResolver.IsCentral(Tfm(false, Reference(version: "1.0.0"), CentralItem()), "Lib"));

    // Break: a transitive row under CPM is never central, but the planner routes it to the
    // existing central item (ViaExistingCentral), so that upgrade moves every project.
    [Fact]
    public void TransitiveWithExistingCentralItemIsCentral() =>
        Assert.True(SiteResolver.IsCentral(Tfm(true, CentralItem()), "Lib"));

    // Break: a transitive row with no central item is reported as central.
    [Fact]
    public void TransitiveWithoutCentralItemIsNotCentral() =>
        Assert.False(SiteResolver.IsCentral(Tfm(true), "Lib"));
}
