using System.Text.Json;
using NuGetExtended.Core.Versions;

namespace NuGetExtended.Core.Protocol;

public static class ProtocolInfo
{
    public const int Version = 1;
}

public sealed record Hello(int Protocol, string HelperVersion, string SdkVersion, string SdkPath, string Runtime);

public sealed record Fatal(string Message);

public sealed record EventLine(string Event, object Data, int? Id = null);

public sealed record Request(int Id, string Method, JsonElement Params);

public sealed record ErrorInfo(string Kind, string Message, string? Details);

public sealed record ResponseLine(int Id, object? Result, ErrorInfo? Error);

public sealed record ScanParams(string SolutionDir, IReadOnlyList<string> Projects, ScanOptions Options);

public sealed record PackageRow(string Id, string Requested, string? Resolved, string? Target, string Severity,
    string? Capped, bool RestoreOnly, bool Transitive, bool AutoReferenced, string? Reason,
    string? Blocked = null, // Target exists but another id on the same version site lacks it (SharedVersions)
    bool Central = false, // The version is the CPM central item: an upgrade moves every project that uses it
    string? VersionFile = null, int VersionLine = 0, // Where the version is set (1-based line); null for a transitive row
    string? ProjectUrl = null, // The feed's project URL for the target (or newest) version; only for ids with an update
    string? VersionProperty = null); // The property name when the version is `$(Name)`; every package that uses it moves together

public sealed record FrameworkRows(string Framework, IReadOnlyList<PackageRow> Packages);

public sealed record ProjectRows(string Path, string Name, IReadOnlyList<FrameworkRows> Frameworks);

public sealed record Failure(string Project, string Summary, string Details);

public sealed record SourceFailure(string Source, string Message, bool SignInNeeded);

public sealed record ScanResult(IReadOnlyList<ProjectRows> Projects, IReadOnlyList<string> Stale,
    IReadOnlyList<Failure> Failures, IReadOnlyList<SourceFailure> SourceFailures);

public sealed record UpgradeRow(string Project, string Framework, string Id, string Target);

public sealed record PlanParams(string SolutionDir, IReadOnlyList<string> AllProjects, IReadOnlyList<UpgradeRow> Rows, ScanOptions Options);

public sealed record Edit(
    string Op,              // "set" | "insert"
    string File,
    string Target,          // "attribute" | "child" | "property" | "item" | "directive"
    string? ItemType, string? IdentityAttr, string? Identity,
    string? Condition, string? GroupCondition,
    string Name,            // metadata / property name; for "item" inserts: "Version"
    string? Expected,       // current raw text (null for inserts)
    string Value,
    int Line, int Column);

public sealed record Change(string Project, string Id);

public sealed record Skip(string Project, string Id, string Reason);

public sealed record UpgradePlan(IReadOnlyList<Edit> Edits, IReadOnlyList<string> RestoreProjects,
    IReadOnlyList<Change> AlsoChanges, IReadOnlyList<Skip> Skipped);
