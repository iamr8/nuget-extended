using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Protocol;
using NuGetExtended.Core.Versions;

namespace NuGetExtended.Core.Scanning;

public static class RowBuilder
{
    public static string? Requested(EvaluatedTfm tfm, string id)
    {
        bool Is(PackageItem i, string type) => i.ItemType == type && string.Equals(i.Id, id, StringComparison.OrdinalIgnoreCase);
        var reference = tfm.Items.FirstOrDefault(i => Is(i, "PackageReference"));
        if (reference?.VersionOverride != null) return reference.VersionOverride;
        if (tfm.CpmEnabled)
        {
            var central = tfm.Items.FirstOrDefault(i => Is(i, "PackageVersion") || Is(i, "GlobalPackageReference"));
            if (central?.Version != null) return central.Version;
        }
        return reference?.Version;
    }

    /// The assets facts a row needs (a plain copy, so Core does not depend on NuGet.ProjectModel).
    public sealed record Asset(string Id, NuGetVersion? Resolved, bool Direct, int Depth, bool AutoReferenced);

    public static PackageRow Row(Asset asset, string requested, IReadOnlyList<Candidate>? candidates,
        NuGetFramework framework, ScanOptions options, DateTimeOffset now)
    {
        var resolved = asset.Resolved;
        if (!VersionRange.TryParse(requested, allowFloating: true, out var range))
            return new PackageRow(asset.Id, requested, resolved?.ToNormalizedString(), null, "None", null, false, !asset.Direct,
                asset.AutoReferenced, $"version text '{requested}' is not a NuGet version or range");
        if (resolved == null || candidates == null)
            return new PackageRow(asset.Id, requested, resolved?.ToNormalizedString(), null, "None", null, false, !asset.Direct,
                asset.AutoReferenced, resolved == null ? "not resolved - restore the project" : null);

        var s = TargetSelector.Select(range, resolved, candidates, framework, options, now);
        return new PackageRow(
            asset.Id,
            requested,
            resolved.ToNormalizedString(),
            s.Target?.ToNormalizedString(),
            Severity.Of(resolved, s.Target),
            s.Capped?.ToNormalizedString(),
            s.RestoreOnly,
            !asset.Direct,
            asset.AutoReferenced,
            s.Target == null && s.Capped != null ? (range.IsFloating ? "capped by floating version" : "capped by range") : null)
        {
            // The target's page, else the newest version that has one.
            ProjectUrl = candidates.FirstOrDefault(c => c.Version == s.Target && c.ProjectUrl != null)?.ProjectUrl
                ?? candidates.Where(c => c.ProjectUrl != null).MaxBy(c => c.Version)?.ProjectUrl,
        };
    }
}
