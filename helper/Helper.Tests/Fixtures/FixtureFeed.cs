using System.IO.Compression;
using System.Text;

namespace NuGetExtended.Helper.Tests.Fixtures;

/// A local folder feed of tiny .nupkg files (a .nuspec only). Deterministic: no network.
public static class FixtureFeed
{
    public sealed record Package(string Id, string Version, string[] Frameworks, bool Listed = true, string? ProjectUrl = null);

    public static string Create(string dir, params (string Id, string[] Versions)[] packages) =>
        Create(dir, packages.SelectMany(p => p.Versions.Select(v => new Package(p.Id, v, Array.Empty<string>()))).ToArray());

    public static string Create(string dir, params Package[] packages)
    {
        var feed = Path.Combine(dir, "feed");
        Directory.CreateDirectory(feed);
        foreach (var p in packages)
        {
            var path = Path.Combine(feed, $"{p.Id}.{p.Version}.nupkg".ToLowerInvariant());
            using var zip = ZipFile.Open(path, ZipArchiveMode.Create);
            var groups = string.Concat(p.Frameworks.Select(f => $"<group targetFramework=\"{f}\" />"));
            var nuspec = $@"<?xml version=""1.0"" encoding=""utf-8""?>
<package xmlns=""http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd"">
  <metadata>
    <id>{p.Id}</id><version>{p.Version}</version><authors>fixture</authors><description>fixture</description>
    {(p.ProjectUrl != null ? $"<projectUrl>{p.ProjectUrl}</projectUrl>" : "")}
    {(groups.Length > 0 ? "<dependencies>" + groups + "</dependencies>" : "")}
  </metadata>
</package>";
            var entry = zip.CreateEntry($"{p.Id}.nuspec");
            using var w = new StreamWriter(entry.Open(), new UTF8Encoding(false));
            w.Write(nuspec);
        }
        return feed;
    }
}
