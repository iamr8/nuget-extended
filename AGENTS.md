# AGENTS.md — NuGet (Extended)

Context, conventions, and rules for anyone (human or AI) working in this repo.

## What this is

A **JetBrains Rider plugin** (formerly *dotnet outdated GUI*) that checks the open solution's
NuGet packages for updates and upgrades them in place, keeping version ranges and Central Package
Management intact. A bundled .NET engine (`helper/`) does the work with the user's own SDK
MSBuild and NuGet.

- Repo: https://github.com/iamr8/nuget-extended
- Plugin id: `com.github.iamr8.dotnetoutdated` (never changes) · name: **NuGet (Extended)** (`PluginText.NAME`)
- Base package / Gradle group: `com.github.iamr8`

## Toolchain & build

- **Kotlin**, IntelliJ Platform Gradle Plugin `2.11.0`, target **Rider 2026.1 (build 261)**.
- **Kotlin plugin version must match Rider's bundled Kotlin metadata** — `2.3.0` for build 261.
  Older compilers fail with "incompatible version of Kotlin".
- Platform dependency is **local Rider** (`/Applications/Rider.app`) when present, else
  `rider("2026.1.4")` is downloaded (CI).
- **JDK**: platform-261 bytecode needs `--release 21`. The build runs on **JDK 22** via
  `org.gradle.java.home` in `gradle.properties` (machine-specific path). A plain `java` of 11 is
  too old to launch Gradle. In CI, `-Dorg.gradle.java.home="$JAVA_HOME"` overrides it (setup-java 21).
- **.NET SDK** (6 or later) builds the engine: `buildHelper` (`dotnet publish helper/Helper`) runs
  before `prepareSandbox`, and its output ships in the plugin's `helper/` folder.
- **Version**: single source of truth is the **`VERSION`** file (no extension); `build.gradle.kts`
  reads it into the plugin version. It is the version in progress (see Conventions).
- `until-build` is intentionally unset (forward IDE compatibility / Marketplace-friendly).

### Common commands

```bash
export JAVA_HOME=<jdk-22-home>
./gradlew test                                   # unit tests (pure logic)
./gradlew verifyPluginStructure buildPlugin       # validate + package -> build/distributions/*.zip
./gradlew runIde                                  # sandbox Rider to drive the UI
# install a local build into the real Rider for manual testing:
rm -rf "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins/nuget-extended-rider"
unzip -q build/distributions/nuget-extended-rider-$(cat VERSION).zip -d "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins"
```

## Architecture

```
helper/   .NET engine (net6.0, RollForward=LatestMajor): Protocol/Server (JSON lines), SdkHost (MSBuildLocator),
          ProjectEvaluator, AssetsReader, RestoreState, FeedService, TargetSelector, RangeText, EditPlanner
engine/   HelperProcess, HelperClient, HelperService (one helper per solution), FileWatch, Protocol DTOs
edit/     EditText (pure: locate edits), EditApplier (documents, one undo step)
cli/      DotnetRunner + RestoreCommand, ScanPlan, SolutionModel, DotnetLocator, CliFailures
model/    Severity (severity -> color)
settings/ OutdatedOptions (persisted), OutdatedOptionsService, OutdatedConfigurable
ui/       OutdatedToolWindowFactory, OutdatedPanel, PackageListView, OutdatedRows, UpgradeSummary,
          DotnetProjectNotificationProvider
```

### Key behaviors

- **Core value prop (lead with this in all user-facing copy)**: upgrades **keep version ranges**
  (range is intent: the offered version is the highest inside the range; newer ones are "capped by
  range" and hidden unless the user turns them on), and work with **Central Package Management**, `VersionOverride` and
  `$(Prop)` versions. Floating versions update by restore only.
- **Engine**: one helper process per solution, started from the solution folder (its `global.json`
  picks the SDK). The helper is read-only; it returns edits that the plugin applies.
- **Scan**: `scan` returns rows plus `stale` projects (assets differ from what the project asks
  for). The plugin restores the stale ones (unless "Never run dotnet restore") and scans again.
  "List all packages" rows need no network.
- **Upgrade**: `planUpgrade` -> confirm dialog (plan, shared versions, skips) -> `EditApplier`
  (all files or none, one undo step) -> restore -> re-scan. Exact package ids.
- **Grouped list**: `ProjectName · framework` header (grayed) per project+TFM; package rows show a
  checkbox + `Name · Current` (left) and the new version (right, whole-value colored by severity).
  Severity follows NuGet/SemVer: green=patch, yellow=minor, red=major/pre-release.
- **Checkboxes** (only outdated rows checkable) + Space toggles selection; **speed search** by name.
- **Error routing** (two distinct classes, never mixed):
  - *User/environment failures* (engine cannot start, no .NET 6+ SDK, unrestored project, `NU1102`,
    failing package source, a plan that no longer matches the files) → notification balloon via the
    `NuGet (Extended)` `<notificationGroup>` + `LOG.warn`. The short message comes from `CliFailures.describe`;
    the raw CLI output is behind the balloon's **Copy Details** action. Never `LOG.error` —
    that opens the IDE fatal-error dialog and would fill the Marketplace Exceptions tab with
    other people's broken solutions.
  - *Plugin bugs* (unexpected `Throwable` in a background task) → `LOG.error`, i.e. the IDE error
    reporter, which submits to the **JetBrains Marketplace Exception Analyzer**
    (`<errorHandler implementation="com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter"/>`,
    platform-provided since 2023.3). Reports land on the plugin's *Exceptions* tab
    (`plugins.jetbrains.com/plugin/32989/edit/exception-analyzer`) and only for Marketplace-installed
    builds — the Report action is absent/inert in `runIde` and local-zip installs.

### Gotchas

- **Never ship NuGet or MSBuild DLLs in the helper.** All `NuGet.*` / `Microsoft.Build` references
  are `ExcludeAssets="runtime"`; the SDK's own copies load at run time. Shipping them breaks with a
  `NuGet.Frameworks` version clash.
- **Register MSBuild first.** `SdkHost.Register` runs before any code that touches `Microsoft.Build`
  or `NuGet.*` types; such code sits in `NoInlining` methods or other classes.
- **Stale = semantic, not file times.** A no-op restore does not rewrite `project.assets.json`.
- **Only protocol JSON on the helper's stdout.** Logs go to stderr (the plugin writes them to idea.log).
- Edit locations from MSBuild are 1-based; the column points at `<`. The key (item type, identity,
  condition, expected text) must match; the location only breaks ties.

## Testing policy

Every functional change needs a test where practical. Pure logic (command builders, parsing,
severity, solution parsing, options round-trip) is unit-tested (JUnit4).

The engine has its own tests: `helper/Core.Tests` (pure, in-process) and `helper/Helper.Tests`
(runs the helper against fixture projects and a local folder feed - no network). CI runs them on
SDK 6, 8 and 10. These may run locally with `dotnet test`.

**Verification happens in CI, not locally.** Don't run Gradle locally to prove a change works —
open the PR and let its checks do it. `build.yml` runs, on every PR: `test`,
`verifyPluginProjectConfiguration`, `verifyPluginStructure`, `buildPlugin`, and the **Plugin
Verifier on every supported Rider line** (`verify.yml`). A red check is the signal to fix; a green
one is the evidence.
UI behavior that no check can cover is confirmed by installing the built zip in real Rider.

## CI / release

- Workflows: `build.yml` (test + verify + buildPlugin + artifact, and calls `verify.yml`),
  `verify.yml` (reusable: the Plugin Verifier, one job per Rider line, newest patch of each, from
  2024.3 to the current release; the list is its matrix. It writes a Marketplace-style table to the
  run summary and, on a PR, to one comment that each run updates), `codeql.yml` (security; two jobs.
  java-kotlin needs a real compile: `clean --no-daemon --no-build-cache`. csharp (the helper) uses
  `build-mode: none`), `compatibility.yml` (calls `verify.yml` weekly and on demand; pin released
  Riders, since `recommended()` can resolve 404 EAPs),
  `release.yml`, plus Dependabot. Actions are pinned to latest majors. `build.yml`, `release.yml`
  and `verify.yml` install the .NET SDK (`actions/setup-dotnet@v6`); `codeql.yml` does not
  (no Gradle task it runs reaches the helper build). `build.yml` and `codeql.yml` cancel a
  superseded run on a PR (`concurrency`); runs on `main`, `release`, schedule and dispatch are never cancelled.
  `build.yml` also runs the helper tests on SDK 6/8/10 and a packaging check that starts the
  bundled helper.
- **EAP dev builds**: every successful `build.yml` run on **`main`** publishes an **EAP GitHub
  pre-release** (the `eap` job) — NOT the Marketplace. The plugin version is
  `<VERSION>-eap.<yyyyMMdd>.<run>` (`VERSION` = main's target, overriding via `-PpluginVersion`),
  e.g. `0.1.4-eap.20260829.104`. Because `main`'s `VERSION` is ahead of the last release, the EAP
  sorts *above* the released build, so it installs over it in Rider and reads as the target version.
  The tag is `eap-<yyyyMMdd>.<run>`. The release notes name the target version (the `VERSION` file /
  milestone) and list the PRs merged since the last stable `v*` tag. For local testing: download the
  zip, install via Settings → Plugins → ⚙ → Install from Disk; uninstall it before installing a
  Marketplace release.
  When `release.yml` ships `v<VERSION>`, its last step deletes every EAP pre-release (and tag) whose
  target version is at or below `<VERSION>`.
- **Release model**: branch-based.
  - `main` = development; `build.yml` builds + verifies + publishes an EAP pre-release (above).
    It never publishes to the Marketplace.
  - To release: bump `VERSION` **in the same PR**, then merge that PR into the **`release`**
    branch. `release.yml` gates on the version — if `VERSION` > the last released `v*` tag it
    tags `v<VERSION>`, builds, creates a GitHub Release, and publishes to the Marketplace
    (when `PUBLISH_TOKEN` is set). If `VERSION` is identical to or lower than the last tag, it
    **skips** (no tag/release/publish). Keep `main` and `release` in sync after a release.
- **Secrets**: `PUBLISH_TOKEN` (Marketplace publish), `SYNC_PAT` (release→main sync PR).
  No error-reporting secret: exceptions go to the Marketplace Exception Analyzer, which needs none.

## Conventions & rules

- **Commits**: Conventional Commits.
- **Changelog targets a version, never `[Unreleased]`**: every user-facing change goes in
  `CHANGELOG.md` under the current in-progress version section — the one matching the `VERSION`
  file (e.g. `## [0.1.4]`). There is no `[Unreleased]` section. If the top section's version is
  already released (a `v*` tag exists for it), bump `VERSION` and start a new section for the next
  version. The top (newest) section carries no date until it ships; add the release date when it is
  tagged. Keep a bottom link line per version (`[x.y.z]: …/compare/v<prev>...v<this>`).
- **Git identity** in this repo: `iamr8` / `arash.shabbeh@gmail.com`. Push auth uses gh
  (repo-local credential helper `!gh auth git-credential`), not the machine keychain.
- **Never commit secrets/tokens.** DSN is injected, tokens live in env / GitHub secrets.
- Keep logic pure and small; put testable code where it can be unit-tested.
- License: **MIT** (`LICENSE`).

### Pull requests

Every PR must be enriched — not just a title:

- **Body** follows [`.github/pull_request_template.md`](.github/pull_request_template.md):
  *Summary* (what & why, `Closes #NN`), *Changes*, *Testing*, *Checklist*, screenshots for UI.
- **Assignee** set (normally the author, e.g. `iamr8`).
- At least one **label**: `bug`, `enhancement`, `documentation`, `ci`, or `dependencies`
  (create a fitting one if none applies).
- **Milestone** set to the current target version — the next version after the last release
  (e.g. after `0.1.3`, target `0.1.4`). Ask which version to target when it isn't obvious. The
  milestone must equal the `VERSION` file (in the PR / on `main`) before merge, and matches the
  CHANGELOG section (see **Commits**). `main` always carries the *next* target version, so its
  `VERSION` is ahead of the `release` branch's.
- **Base branch**: `main` for development; a **release** PR targets the `release` branch and
  includes the `VERSION` bump (see the Release model above).
- Keep it focused — one concern per PR; record user-facing changes in `CHANGELOG.md` under the
  current version section (see **Commits** above).
```
