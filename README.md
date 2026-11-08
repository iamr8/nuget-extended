<h1><img src="src/main/resources/META-INF/pluginIcon.svg" width="32" height="32" alt=""> NuGet (Extended)</h1>

A JetBrains Rider plugin that checks the whole solution for NuGet updates and upgrades them in
place. It **keeps your version ranges** and works with **Central Package Management**. These are
two things Rider's own NuGet window does not do yet.

[![Build](https://img.shields.io/github/actions/workflow/status/iamr8/nuget-extended/build.yml?branch=main&style=flat-square&label=build)](https://github.com/iamr8/nuget-extended/actions/workflows/build.yml)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/iamr8/nuget-extended/codeql.yml?branch=main&style=flat-square&label=codeql)](https://github.com/iamr8/nuget-extended/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/iamr8/nuget-extended?style=flat-square)](https://github.com/iamr8/nuget-extended/releases)
[![Last commit](https://img.shields.io/github/last-commit/iamr8/nuget-extended?style=flat-square)](https://github.com/iamr8/nuget-extended/commits/main)
[![License: MIT](https://img.shields.io/github/license/iamr8/nuget-extended?style=flat-square)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32989?style=flat-square&label=marketplace)](https://plugins.jetbrains.com/plugin/32989)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32989?style=flat-square&label=downloads)](https://plugins.jetbrains.com/plugin/32989)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32989?style=flat-square)](https://plugins.jetbrains.com/plugin/32989/reviews)

<img width="207" height="374" alt="Screenshot 2026-07-18 at 12 21 01 PM" src="https://github.com/user-attachments/assets/aa9f0b5c-3f42-4878-a71a-096f28ba71e0" />

There is no extra tool to install. The plugin uses the MSBuild and NuGet of your own .NET SDK.
It was called *dotnet outdated GUI* before.

> [!WARNING]
> **Tested only on Rider 2026.2 with the .NET 10 SDK, on macOS.** It is not tested by hand on the
> .NET 6 or 8 SDK, on older Rider versions, or on Windows or Linux. The plugin claims support for
> these, and CI checks them in part: the engine tests run on SDK 6, 8 and 10, and the Plugin
> Verifier checks the Rider API from 2024.3 up. Real use on them may still find problems. If you
> see one, please [open an issue](https://github.com/iamr8/nuget-extended/issues).

## Features

- **Version ranges stay ranges.** `[12.0.1,14.0.0)` upgrades to `[13.0.4,14.0.0)`. The new version
  is the newest one inside the range. A version above the upper bound is never offered.
- **Central Package Management.** The plugin updates the central `<PackageVersion>` in
  `Directory.Packages.props` (or a `VersionOverride`). The `<PackageReference>` with no version
  stays as it is.
- **Versions in properties.** For `Version="$(PollyVersion)"`, the plugin updates the property. It
  lists the other packages that share it first.
- **Floating versions** (`3.*`) update by a restore, with no file edit.
- **A clear list.** Packages are grouped by project and target framework. The new version has a
  color by SemVer change: green = patch, yellow = minor, red = major or pre-release.
- **Group by Package.** The eye icon switches the list to one row per package, with its projects
  under it. A package from `Directory.Packages.props` is checked once for all projects. Projects on
  another version (a `VersionOverride`, or a different version without CPM) get their own row.
- **Right-click a row** to upgrade just that package or project, jump to the line that sets the
  version, or open the package on nuget.org or its project page.
- **Recommendations.** After an update check, a banner lists packages with a new major version.
  Click one to jump to it and check it. The eye menu turns it off.
- **Collapsible groups.** Chevrons open and close projects and packages; groups start collapsed.
- **Shared `$(Name)` versions.** The eye menu can show the property name. Packages on one property check
  together, because one edit moves them all.
- **A plan before each change.** A dialog shows the files, the packages that share a version, and
  what it skips (and why). One **Undo** reverts every file.
- **Your NuGet setup.** It uses your `NuGet.config`, package source mapping and credential
  providers (Azure Artifacts, GitHub Packages, and others).
- **Works beside Rider.** After an upgrade, it waits for Rider's own NuGet restore to end before it
  runs its own.

## Requirements

- JetBrains Rider 2024.3 or later.
- The .NET SDK 6 or later. The solution's `global.json` picks the SDK.

## Install

From the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32989):

1. In Rider, open **Settings → Plugins → Marketplace**.
2. Search for **NuGet (Extended)**.
3. Click **Install**, then restart Rider.

Or download a `.zip` from [Releases](https://github.com/iamr8/nuget-extended/releases). Install it
with **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Usage

1. Open the **NuGet (Extended)** tool window (right side).
2. Click **Check for Updates**. It finds newer versions for the projects in **Scope**. It restores
   the projects whose restore is out of date first.
3. Check the packages you want. Use a row's checkbox, or select many rows and press
   <kbd>Space</kbd>. Type to search by name.
4. Click **Update Selected**, read the plan, and click **Upgrade**. The plugin edits the files,
   restores, and checks again.

A package at the newest version inside its range is up to date, so it is not in the list. To see
the newer version outside the range (gray, not checkable), turn on **Show versions outside the
range**.

**Reload Packages** (↻) lists every package with no update check. It needs **List all packages**
in the settings.

> [!NOTE]
> A problem in your solution shows as a notification, with **Copy Details** for the full output.
> Examples are an unrestored project, a version that does not exist, or a failing package source.
> A real plugin error goes to Rider's error reporter. You choose whether to send it to the plugin's
> page on the JetBrains Marketplace.

### Settings

Open them from the ⚙ button in the tool window, or from **Settings → Tools → NuGet (Extended)**.
They are saved per project in `.idea/nuget-extended.xml`.

| Group | Settings |
|---|---|
| Packages analyzed | List all packages, show versions outside the range, auto-referenced packages, transitive packages and depth |
| Version policy | Pre-release and label, version lock, maximum version, only versions older than N days |
| Discovery | Projects in subfolders (no solution open), file-based apps (`#:package`, .NET 10 SDK), name filters |
| Sources | Never run `dotnet restore`, ignore failed sources, engine timeout, runtime identifier, credential log level |

## Development

The build needs a JDK (see `gradle.properties`) and the .NET SDK 6 or later for the engine in
`helper/`.

```bash
dotnet test helper/Core.Tests && dotnet test helper/Helper.Tests   # engine tests
./gradlew test          # plugin unit tests
./gradlew buildPlugin   # build/distributions/nuget-extended-rider-<version>.zip, engine included
./gradlew runIde        # a sandbox Rider with the plugin
```

```
helper/   .NET engine (net6.0): MSBuild evaluation, assets, NuGet feeds, target selection, upgrade plan
engine/   helper process, JSON-lines client, file watcher
edit/     applies the engine's edits to IDE documents (one undo step)
cli/      restore runner, scan scope, solution parsing, dotnet locator, failure messages
model/    severity -> color
settings/ options, settings page
ui/       tool window, panel, grouped list, rows, confirm text
```

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md). The license is
[MIT](LICENSE).
