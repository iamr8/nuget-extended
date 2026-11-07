using NuGet.Frameworks;
using NuGet.Versioning;

namespace NuGetExtended.Core.Versions;

public sealed record Candidate(NuGetVersion Version, bool Listed, DateTimeOffset? Published, IReadOnlyList<NuGetFramework> DependencyFrameworks,
    string? ProjectUrl = null);

public sealed record Selection(NuGetVersion? Target, NuGetVersion? Capped, bool RestoreOnly);

public static class TargetSelector
{
    public static Selection Select(
        VersionRange requested,
        NuGetVersion resolved,
        IReadOnlyList<Candidate> candidates,
        NuGetFramework framework,
        ScanOptions options,
        DateTimeOffset now)
    {
        var includePre = options.PreRelease switch
        {
            "Always" => true,
            "Never" => false,
            _ => resolved.IsPrerelease,
        };
        var label = options.PreReleaseLabel.Trim();
        var versions = Filter(candidates, framework, options, now)
            .Where(v => !v.IsPrerelease || (includePre && HasLabel(v, label)))
            .ToList();
        var behavior = Behavior(options.VersionLock, includePre);
        var prefix = resolved.IsPrerelease
            ? (string.IsNullOrWhiteSpace(options.PreReleaseLabel) ? resolved.ReleaseLabels.First() : options.PreReleaseLabel.Trim())
            : string.Empty;
        var optionMax = ParseMaximum(options.MaximumVersion);

        if (requested.IsFloating)
        {
            var policyFloat = new FloatRange(behavior, resolved, prefix);
            var policy = new VersionRange(new VersionRange(resolved, true, optionMax, optionMax != null), policyFloat);

            // What a plain "dotnet restore" would pick: the highest LISTED version matching the
            // requested range and its float. Restore knows nothing of OlderThanDays, the
            // pre-release option, the version lock or TFM filtering - those are policy, not restore.
            var pick = candidates.Where(c => c.Listed).Select(c => c.Version)
                .Where(v => requested.Satisfies(v) && requested.Float!.Satisfies(v))
                .DefaultIfEmpty().Max();
            var pickIsNewer = pick != null && pick > resolved;
            var pickPassesPolicy = pickIsNewer && versions.Contains(pick!) && policy.Satisfies(pick!) && policyFloat.Satisfies(pick!);

            var cappedF = BestIn(policy, versions);
            var outsideRequestedFloat = cappedF != null && cappedF > resolved && !requested.Float!.Satisfies(cappedF)
                ? cappedF
                : null;
            var pickBlockedByPolicy = pickIsNewer && !pickPassesPolicy ? pick : null;
            var cappedFloat = Higher(outsideRequestedFloat, pickBlockedByPolicy);

            return new Selection(
                pickPassesPolicy ? pick : null,
                cappedFloat,
                pickPassesPolicy);
        }

        // A pin moves (spec); an upper-only range floats from the resolved version.
        var isPin = requested.HasLowerAndUpperBounds && requested.MinVersion == requested.MaxVersion;
        var lower = requested.HasLowerBound && !isPin ? requested.MinVersion : resolved;
        var lowerInclusive = !requested.HasLowerBound || isPin || requested.IsMinInclusive;
        var upper = isPin ? null : requested.MaxVersion;
        var upperInclusive = !isPin && requested.IsMaxInclusive;
        (upper, upperInclusive) = Cap(upper, upperInclusive, optionMax);

        var target = BestIn(new VersionRange(new VersionRange(lower, lowerInclusive, upper, upperInclusive), new FloatRange(behavior, resolved, prefix)), versions);
        if (target != null && target <= resolved) target = null;

        var (openUpper, openInclusive) = Cap(null, false, optionMax);
        var unbounded = BestIn(new VersionRange(new VersionRange(lower, lowerInclusive, openUpper, openInclusive), new FloatRange(behavior, resolved, prefix)), versions);
        var capped = unbounded != null && unbounded > resolved && (target == null || unbounded > target) ? unbounded : null;

        return new Selection(target, capped, false);
    }

    /// FindBestMatch falls back to a version inside the bounds but outside the float when no
    /// candidate satisfies the float (e.g. a version-locked major with no candidate left in that
    /// major). A best match only counts when it also satisfies the float itself.
    private static NuGetVersion? BestIn(VersionRange range, IReadOnlyList<NuGetVersion> versions)
    {
        var best = range.FindBestMatch(versions);
        return best != null && range.Float != null && !range.Float.Satisfies(best) ? null : best;
    }

    /// An empty label allows every pre-release; otherwise the first label part must start with it ("rc" matches "rc.1", "rc2").
    private static bool HasLabel(NuGetVersion v, string label) =>
        label.Length == 0 || v.ReleaseLabels.FirstOrDefault()?.StartsWith(label, StringComparison.OrdinalIgnoreCase) == true;

    private static NuGetVersion? Higher(NuGetVersion? a, NuGetVersion? b)
    {
        if (a == null) return b;
        if (b == null) return a;
        return a > b ? a : b;
    }

    private static (NuGetVersion?, bool) Cap(NuGetVersion? upper, bool inclusive, NuGetVersion? optionMax)
    {
        if (optionMax == null) return (upper, inclusive);
        if (upper == null || optionMax < upper) return (optionMax, true);
        return (upper, inclusive);
    }

    private static NuGetVersionFloatBehavior Behavior(string versionLock, bool includePre) => versionLock switch
    {
        "Major" => includePre ? NuGetVersionFloatBehavior.PrereleaseMinor : NuGetVersionFloatBehavior.Minor,
        "Minor" => includePre ? NuGetVersionFloatBehavior.PrereleasePatch : NuGetVersionFloatBehavior.Patch,
        _ => includePre ? NuGetVersionFloatBehavior.AbsoluteLatest : NuGetVersionFloatBehavior.Major,
    };

    /// Missing parts mean "any": "8" allows every 8.x, "8.0" every 8.0.x.
    private static NuGetVersion? ParseMaximum(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (int.TryParse(text.Trim(), out var major) && major >= 0)
            return new NuGetVersion(new Version(major, int.MaxValue, int.MaxValue, int.MaxValue));
        if (!Version.TryParse(text.Trim(), out var v)) return null;
        var build = v.Build == -1 ? int.MaxValue : v.Build;
        var revision = v.Revision == -1 ? int.MaxValue : v.Revision;
        return new NuGetVersion(new Version(v.Major, v.Minor, build, revision));
    }

    private static IEnumerable<NuGetVersion> Filter(IReadOnlyList<Candidate> candidates, NuGetFramework framework,
        ScanOptions options, DateTimeOffset now)
    {
        foreach (var c in candidates)
        {
            if (!c.Listed) continue;
            if (options.OlderThanDays > 0 && c.Published is { } p && p > now.AddDays(-options.OlderThanDays)) continue;
            if (!SupportsFramework(framework, c.DependencyFrameworks)) continue;
            yield return c.Version;
        }
    }

    /// Whether a candidate whose dependency groups target [dependencyFrameworks] can be used by a
    /// project targeting [framework]. No dependency groups means the package declares no frameworks,
    /// so it does not restrict them.
    public static bool SupportsFramework(NuGetFramework framework, IReadOnlyList<NuGetFramework> dependencyFrameworks) =>
        dependencyFrameworks.Count == 0 ||
        new FrameworkReducer().GetNearest(framework, dependencyFrameworks) != null;
}
