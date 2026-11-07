package com.github.iamr8.nugetextended.cli

/** Pre-release policy. */
enum class PreRelease { Auto, Always, Never }

/** Version lock. */
enum class VersionLock { None, Major, Minor }

/** Blank, or 1 to 4 numbers ("8", "8.0", "8.0.1") - the forms the engine reads as a maximum version. */
fun isValidMaximumVersion(text: String): Boolean = text.isBlank() || Regex("""\d+(\.\d+){0,3}""").matches(text.trim())

/** NuGet credential log level. */
enum class CredLogLevel { Debug, Verbose, Information, Minimal, Warning, Error }

/**
 * Every scan and upgrade option the plugin exposes. Plain, mutable, pure — so the engine request
 * building is unit-testable and the type can be bound to the options form and persisted
 * directly (a no-arg constructor is synthesized because all parameters have defaults).
 *
 * Defaults chosen for safety:
 *  - [includeUpToDate] = false  (off by default - listing every package is heavy on big solutions)
 *  - [ignoreFailedSources] = true  (a single flaky feed shouldn't abort the whole scan)
 *  - [idleTimeoutSeconds] = 300  (slow private feeds)
 */
data class OutdatedOptions(
    // Which packages are analyzed
    var includeAutoReferences: Boolean = false,
    var transitive: Boolean = false,
    var transitiveDepth: Int = 1,
    /** List every package, including up-to-date ones. Off by default. */
    var includeUpToDate: Boolean = false,
    /** Show a newer version that is outside the range (gray, not checkable). Off by default: it reads like an upgrade. */
    var showCappedVersions: Boolean = false,

    // Version policy
    var preRelease: PreRelease = PreRelease.Auto,
    var preReleaseLabel: String = "",
    var versionLock: VersionLock = VersionLock.None,
    var maximumVersion: String = "",
    var olderThanDays: Int = 0,

    // Discovery
    var recursive: Boolean = false,
    var includeFileBasedApps: Boolean = false,
    var includeFilters: MutableList<String> = mutableListOf(),
    var excludeFilters: MutableList<String> = mutableListOf(),

    // Reliability / sources
    var noRestore: Boolean = false,
    var ignoreFailedSources: Boolean = true,
    var idleTimeoutSeconds: Int = 300,
    var runtime: String = "",
    var credLogLevel: CredLogLevel = CredLogLevel.Warning,

    // View
    /** Group the list by package (children per project) instead of by project. */
    var groupByPackage: Boolean = false,
) {
    /** Copies every field from [other] in place (keeps this instance's identity for UI bindings). */
    fun assignFrom(other: OutdatedOptions) {
        includeAutoReferences = other.includeAutoReferences
        transitive = other.transitive
        transitiveDepth = other.transitiveDepth
        includeUpToDate = other.includeUpToDate
        showCappedVersions = other.showCappedVersions
        preRelease = other.preRelease
        preReleaseLabel = other.preReleaseLabel
        versionLock = other.versionLock
        maximumVersion = other.maximumVersion
        olderThanDays = other.olderThanDays
        recursive = other.recursive
        includeFileBasedApps = other.includeFileBasedApps
        includeFilters = other.includeFilters.toMutableList()
        excludeFilters = other.excludeFilters.toMutableList()
        noRestore = other.noRestore
        ignoreFailedSources = other.ignoreFailedSources
        idleTimeoutSeconds = other.idleTimeoutSeconds
        runtime = other.runtime
        credLogLevel = other.credLogLevel
        groupByPackage = other.groupByPackage
    }

    /** Independent deep copy (lists not shared). */
    fun deepCopy(): OutdatedOptions =
        copy(includeFilters = includeFilters.toMutableList(), excludeFilters = excludeFilters.toMutableList())
}
