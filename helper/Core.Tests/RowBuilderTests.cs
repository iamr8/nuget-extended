using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Scanning;
using NuGetExtended.Core.Versions;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class RowBuilderTests
{
    private static PackageItem I(string type, string id, string? version, string? over = null) =>
        new(id, type, version, over, null, null, null);

    [Fact]
    public void RequestedPrefersOverrideThenCentralThenReference()
    {
        var tfm = new EvaluatedTfm("net8.0", "", true, false, null, new[]
        {
            I("PackageReference", "A", null),
            I("PackageVersion", "A", "[1.0,2.0)"),
            I("PackageReference", "B", null, "1.5.0"),
            I("PackageVersion", "B", "1.0.0"),
        }, Array.Empty<string>());

        Assert.Equal("[1.0,2.0)", RowBuilder.Requested(tfm, "a"));
        Assert.Equal("1.5.0", RowBuilder.Requested(tfm, "B"));
    }

    [Fact]
    public void RowCarriesTargetCappedAndSeverity()
    {
        var asset = new RowBuilder.Asset("Polly", NuGetVersion.Parse("7.0.0"), true, 0, false);
        var now = DateTimeOffset.UtcNow;
        var cands = new[] { "7.0.0", "7.2.4", "8.8.0" }
            .Select(v => new Candidate(NuGetVersion.Parse(v), true, now.AddDays(-30), Array.Empty<NuGetFramework>())).ToList();

        var row = RowBuilder.Row(asset, "[7.0.0,8.0.0)", cands, NuGetFramework.Parse("net8.0"), new ScanOptions(), now);

        Assert.Equal("7.2.4", row.Target);
        Assert.Equal("8.8.0", row.Capped);
        Assert.Equal("Minor", row.Severity);
        Assert.Equal("[7.0.0,8.0.0)", row.Requested);
    }

    [Fact]
    public void BadRangeTextIsAReason()
    {
        var asset = new RowBuilder.Asset("X", NuGetVersion.Parse("1.0.0"), true, 0, false);
        var row = RowBuilder.Row(asset, "[oops", new List<Candidate>(), NuGetFramework.Parse("net8.0"), new ScanOptions(), DateTimeOffset.UtcNow);
        Assert.Null(row.Target);
        Assert.Equal("version text '[oops' is not a NuGet version or range", row.Reason);
    }

    // Break: Row picks the project URL of another version than the target, or drops it when only an older version has one.
    [Fact]
    public void RowTakesTheTargetsProjectUrlElseTheNewestOne()
    {
        var asset = new RowBuilder.Asset("Polly", NuGetVersion.Parse("7.0.0"), true, 0, false);
        var now = DateTimeOffset.UtcNow;
        Candidate C(string v, string? url) => new(NuGetVersion.Parse(v), true, now.AddDays(-30), Array.Empty<NuGetFramework>(), url);

        var withTarget = RowBuilder.Row(asset, "[7.0.0,8.0.0)",
            new[] { C("7.0.0", "https://old"), C("7.2.4", "https://target"), C("8.8.0", "https://newest") },
            NuGetFramework.Parse("net8.0"), new ScanOptions(), now);
        Assert.Equal("https://target", withTarget.ProjectUrl);

        var targetHasNone = RowBuilder.Row(asset, "[7.0.0,8.0.0)",
            new[] { C("7.0.0", "https://old"), C("7.2.4", null), C("8.8.0", "https://newest") },
            NuGetFramework.Parse("net8.0"), new ScanOptions(), now);
        Assert.Equal("https://newest", targetHasNone.ProjectUrl);
    }
}
