using NuGetExtended.Core.Model;

namespace NuGetExtended.Core.Planning;

public static class SiteResolver
{
    public static (ValueSite? Site, string? Problem, bool Transitive) Resolve(EvaluatedTfm tfm, string id)
    {
        var reference = Find(tfm, id, "PackageReference");
        var global = Find(tfm, id, "GlobalPackageReference");
        if (reference == null && global == null) return (null, null, true);

        if (reference?.VersionOverride != null) return (reference.OverrideSite, reference.SiteProblem, false);
        var central = CentralItem(tfm, id);
        if (central != null) return (central.VersionSite, central.SiteProblem, false);
        var item = reference ?? global!;
        return (item.VersionSite, item.SiteProblem ?? (item.VersionSite == null ? "no version is set for this package" : null), false);
    }

    /// True when the row's version is the CPM central item, so its upgrade moves every project that
    /// uses that item. Same routing as [Resolve], plus the planner's ViaExistingCentral case for a
    /// transitive row: a VersionOverride is never central.
    public static bool IsCentral(EvaluatedTfm tfm, string id) =>
        Find(tfm, id, "PackageReference")?.VersionOverride == null && CentralItem(tfm, id) != null;

    /// The CPM central item for [id]: its PackageVersion, else its GlobalPackageReference. Null when CPM is off.
    private static PackageItem? CentralItem(EvaluatedTfm tfm, string id) =>
        tfm.CpmEnabled ? Find(tfm, id, "PackageVersion") ?? Find(tfm, id, "GlobalPackageReference") : null;

    private static PackageItem? Find(EvaluatedTfm tfm, string id, string type) =>
        tfm.Items.FirstOrDefault(i => i.ItemType == type && string.Equals(i.Id, id, StringComparison.OrdinalIgnoreCase));

    public static string Key(ValueSite s) =>
        $"{s.File.ToLowerInvariant()}|{s.Kind}|{s.Name}|{s.Line}|{s.Column}";
}
