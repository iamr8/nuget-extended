# Changelog

All notable changes to **NuGet (Extended)** (formerly *dotnet outdated GUI*) are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0]

### Changed
- **Renamed to NuGet (Extended).** The tool window, settings page and notifications use the new
  name. Your saved tool window place and notification settings reset once.
- **No `dotnet-outdated` tool needed.** The plugin ships its own engine. It uses your .NET SDK's
  MSBuild and NuGet (the .NET 6 SDK or later), with your NuGet.config, package source mapping and
  credential providers.
- **Upgrades keep version ranges.** `[12.0.1,14.0.0)` becomes `[13.0.4,14.0.0)`, not a fixed
  `13.0.4`. The new version is the newest one inside the range. A version above the
  upper bound is never offered, and is hidden unless **Show versions outside the range** is on.
- **Exact package upgrades.** Upgrading `Foo` no longer also upgrades `Foo.Bar`.
- **Maximum version** never goes above a range's upper bound.
- **Faster checks.** One engine per solution keeps project data between scans and uses NuGet's HTTP
  cache. Package sources are asked at the same time, not one by one.
- **Private packages are checked too.** A package with `PrivateAssets="all"` is now checked against
  the project's target framework like any other. A new version that drops the framework is no
  longer offered.

### Added
- Versions in properties (`Version="$(PollyVersion)"`): the property is updated, and other packages
  that share it are listed before anything changes.
- A package that shares a version property with another package is blocked when the other package
  has no such version. The row cannot be checked, its name shows in red, and the info icon says why.
- An upgrade plan before any change: files, shared versions, skipped packages. One Undo reverts all
  files.
- Floating versions (`2.*`) update through a restore, with no file edit.
- Out-of-date projects are restored before the scan. Turn it off with **Never run dotnet restore**.
- A project opened in safe mode (not trusted) is never restored and its file-based apps are not
  read. Out-of-date projects in it are listed as skipped.
- **Group by Package** view (eye icon in the toolbar). Each package shows once, with one row per
  project and target framework under it. Under Central Package Management, the project rows follow
  the package row, because the central version changes for every project. Projects on another
  version (a `VersionOverride`, or a different version without CPM) get a package row per version,
  and each one can be checked alone.
- **Right-click menu** on list rows. A package row: *Upgrade to {version}*, *Go to Version
  Definition* (the line in `Directory.Packages.props` or the project file), *Open on nuget.org*,
  *Open Project Page* (when the feed gives one), *Show in NuGet Window* and *Copy Package Id*.
  A project row: *Open {Project}.csproj* and *Upgrade All in This Project*.
- **Recommendations.** After an update check, a blue banner lists the packages with a new major
  version (not a pre-release). Click a name to scroll to it and check it. Turn it off in the eye
  menu with **Show Recommendations**. A closed banner comes back with the next scan.

### Fixed
- Upper-only ranges like `(,3.0.0]` no longer fail the scan.
- Cancel during a scan no longer leaves the toolbar disabled.
- EAP builds are now `0.2.0-eap.*`, so they sort above the released 0.1.4.
- The plugin no longer restores at the same time as Rider's own NuGet restore. Both wrote the same
  obj files, and Rider's restore failed with "…nuget.g.props already exists". The plugin now waits
  for Rider, and restores only what Rider did not.
- **Pre-release label** now keeps only pre-releases with that label (`rc` matches `rc.1`).
- **Maximum version** accepts a single number (`8` means any 8.x), and the settings page rejects
  text it can't read instead of ignoring it.
- **Include file-based apps** works on its own; it no longer needs "Find projects in subfolders".
- A version shared by several projects is now checked in each project's own NuGet.config and
  sources, not only those of the first project.
- `RestoreSources` in a project now replaces the NuGet.config sources, as restore does.
  `RestoreAdditionalProjectSources` still adds to them. A relative folder in either one is
  relative to the project folder.
- Floating versions (`2.*`) now update in projects whose lock file has any name
  (`NuGetLockFilePath`, `packages.<project>.lock.json`), not only `packages.lock.json`. Only the
  projects of the checked floating packages are re-resolved.
- A package is marked with the failed source when its version list loads but its metadata call
  fails. Before, the row was hidden.
- A version in a child element with its own condition (`<Version Condition="...">`) is skipped
  with a reason, not edited.
- A transitive package added as a reference now needs a version that supports every target
  framework of the project.
- Two projects with the same name (`src/Foo`, `tests/Foo`) are both listed and scanned.
- A file-based app that cannot be read (deleted, folder gone, `dotnet` cannot start) is listed as
  a skipped project. Before, it opened the IDE error report.
- A project added to the solution after the tool window opened is now found. The project list is
  read again before each scan and each upgrade. Projects you left out of the **Scope** stay out.
- The project search no longer freezes the IDE, and a failure there no longer leaves the toolbar
  disabled. It runs in the background with the scan. A `.cs` file it cannot read is skipped.
  A solution file it cannot read no longer breaks the tool window or the scan. A balloon says so once.
- A package source that offers only registration types your SDK's NuGet does not know (for example
  a private feed on an older SDK) is now reported as a failed source. It no longer opens the IDE
  error dialog.
- Transitive upgrades check the version that ends up in the file against every target framework.
  When two projects add the same new central version, it is the highest version both support.
  A package added next to an existing central version is checked on every framework of the project.
- The engine starts again on Rider 2024.3 and 2025.1. A plugin lookup used an API those versions
  do not have, so the tool window could not find its engine there.
- A project choice made in **Scope** while a scan runs is no longer lost. The list loads again
  with the new scope when the scan ends.

## [0.1.4] - 2026-08-29

### Fixed
- **Much faster update check on large solutions.** The check now runs `dotnet outdated` once over
  the whole solution (as the CLI does), unless you narrow the **Scope** picker to a subset. Before,
  if any single project failed to restore (e.g. an `NU1102` version that doesn't exist — common
  mid-migration), the plugin silently re-ran every project one at a time, turning a ~15s scan into
  a minutes-long crawl. A whole-solution failure now surfaces the CLI's own error (which names the
  broken project) instead.

### Added
- Toolbar **Select All / Deselect All** button — checks every outdated package in one click, or
  clears them all. The label and icon follow the current state.
- A **checkbox on each project header** — toggles every outdated package under that project. It
  shows a three-state view (all / partial / none) and stays in sync with the toolbar button and
  the individual package checkboxes.
- **Speed-search match highlighting** — as you type to filter the list, the matched characters in
  the package name and project header are now highlighted, like Rider's own lists.

## [0.1.3] - 2026-07-26

### Fixed
- Failures coming from your solution — an unresolvable package version (`NU1102`), an unrestored
  project, a missing CLI — no longer raise the IDE's "Report error" dialog. They are shown as a
  notification with a short, actionable message and a **Copy Details** action for the full CLI
  output, and are logged as warnings instead of errors.

### Changed
- Error reporting now goes through the JetBrains Marketplace Exception Analyzer (the platform's
  own reporter) instead of Sentry, so reporting a plugin exception no longer prompts about
  certificates or third-party network access. Sentry, its dependency, and the baked-in DSN are gone.
- Unresolvable package versions get a dedicated message pointing at the project file /
  `Directory.Packages.props` instead of a wall of MSBuild output.

## [0.1.2] - 2026-07-18

### Changed
- Rewrote the plugin/Marketplace description to lead with Central Package Management and NuGet
  version-range/floating handling, and to summarize the list/severity/multi-select/search features.

## [0.1.1] - 2026-07-18

### Added
- Support for older Rider builds — compatible with Rider 2024.3 (build 243) and newer.
- Opt-in error reporting to Sentry (only when the user clicks "Report" in the IDE error dialog);
  uses an isolated client that doesn't touch the IDE's own error handling.

### Changed
- Group the package list by project and target framework, with a `ProjectName · netX` section
  header per group (matching the `dotnet outdated` CLI), instead of a single flat list.
- Color the entire new-version value by severity (no longer per-character portion).
- Per-row checkboxes (multi-select + Space to toggle) to choose packages to update; grayed
  project headers; type-to-search (speed search) by package/project name.

### Fixed
- Upgrade no longer passes scan/source flags (notably `-ifs`/`--ignore-failed-sources`) to
  `dotnet outdated -u` — those are forwarded to a nested restore that rejects them and failed
  every upgrade. Upgrade now sends only version-policy + timeout flags.

## [0.1.0] - 2026-07-17

### Added
- Tool window that lists NuGet packages of the open solution's projects with their current version.
- **Check for Updates** runs `dotnet outdated` to show available updates.
- Rider NuGet-style list view: `Name · CurrentVersion` on the left, new version right-aligned.
- Severity coloring of the new version (green = patch, yellow = minor, red = major / pre-release).
- In-place upgrade of selected packages via `dotnet outdated -u`.
- **Scope** picker over the open solution's projects.
- Settings page (Settings | Tools | dotnet outdated GUI) exposing every `dotnet outdated` argument,
  persisted per project. "List all packages" toggle (off by default).
- CLI presence check with an install prompt linking to the dotnet-outdated repository.
- Editor banner suggesting the tool when a `.csproj`/`Directory.Packages.props` file is opened.
- Errors routed to the IDE error reporter.

[0.2.0]: https://github.com/iamr8/nuget-extended/compare/v0.1.4...v0.2.0
[0.1.4]: https://github.com/iamr8/nuget-extended/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/iamr8/nuget-extended/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/iamr8/nuget-extended/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/iamr8/nuget-extended/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/iamr8/nuget-extended/releases/tag/v0.1.0
