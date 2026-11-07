package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.cli.OutdatedOptions

// JSON-lines protocol with the helper engine (helper/Core/Protocol/Messages.cs). Keys are camelCase.
// Response types give every field a default: Gson then uses the no-arg constructor.

data class Hello(
    val protocol: Int = 0,
    val helperVersion: String = "",
    val sdkVersion: String = "",
    val sdkPath: String = "",
    val runtime: String = "",
)

data class ErrorInfo(val kind: String = "", val message: String = "", val details: String? = null)

data class PackageRow(
    val id: String = "",
    val requested: String = "",
    val resolved: String? = null,
    val target: String? = null,
    val severity: String = "None",
    val capped: String? = null,
    val restoreOnly: Boolean = false,
    val transitive: Boolean = false,
    val autoReferenced: Boolean = false,
    val reason: String? = null,
    /** Why [target] cannot be applied: another package on the same version site lacks it. */
    val blocked: String? = null,
    /** The version is the CPM central item: an upgrade moves every project that uses it. */
    val central: Boolean = false,
)

data class FrameworkRows(val framework: String = "", val packages: List<PackageRow> = emptyList())

data class ProjectRows(val path: String = "", val name: String = "", val frameworks: List<FrameworkRows> = emptyList())

data class EngineFailure(val project: String = "", val summary: String = "", val details: String = "")

data class SourceFailure(val source: String = "", val message: String = "", val signInNeeded: Boolean = false)

data class ScanResult(
    val projects: List<ProjectRows> = emptyList(),
    val stale: List<String> = emptyList(),
    val failures: List<EngineFailure> = emptyList(),
    val sourceFailures: List<SourceFailure> = emptyList(),
)

/** The options the engine uses (helper/Core/Versions/ScanOptions.cs). */
data class EngineOptions(
    val includeAutoReferences: Boolean,
    val transitive: Boolean,
    val transitiveDepth: Int,
    val includeUpToDate: Boolean,
    val preRelease: String,
    val preReleaseLabel: String,
    val versionLock: String,
    val maximumVersion: String,
    val olderThanDays: Int,
    val ignoreFailedSources: Boolean,
    val runtime: String,
    val credLogLevel: String,
    val includeFileBasedApps: Boolean,
    val checkUpdates: Boolean,
) {
    companion object {
        fun from(o: OutdatedOptions, checkUpdates: Boolean) = EngineOptions(
            includeAutoReferences = o.includeAutoReferences,
            transitive = o.transitive,
            transitiveDepth = o.transitiveDepth,
            includeUpToDate = o.includeUpToDate,
            preRelease = o.preRelease.name,
            preReleaseLabel = o.preReleaseLabel,
            versionLock = o.versionLock.name,
            maximumVersion = o.maximumVersion,
            olderThanDays = o.olderThanDays,
            ignoreFailedSources = o.ignoreFailedSources,
            runtime = o.runtime,
            credLogLevel = o.credLogLevel.name,
            includeFileBasedApps = o.includeFileBasedApps,
            checkUpdates = checkUpdates,
        )
    }
}

data class ScanParams(val solutionDir: String, val projects: List<String>, val options: EngineOptions)

data class UpgradeRow(val project: String, val framework: String, val id: String, val target: String)

data class PlanParams(
    val solutionDir: String,
    val allProjects: List<String>,
    val rows: List<UpgradeRow>,
    val options: EngineOptions,
)

/** One text change. `target`: attribute | child | property | directive | item (insert). */
data class Edit(
    val op: String = "",
    val file: String = "",
    val target: String = "",
    val itemType: String? = null,
    val identityAttr: String? = null,
    val identity: String? = null,
    val condition: String? = null,
    val groupCondition: String? = null,
    val name: String = "",
    val expected: String? = null,
    val value: String = "",
    val line: Int = 0,
    val column: Int = 0,
)

data class Change(val project: String = "", val id: String = "")

data class Skip(val project: String = "", val id: String = "", val reason: String = "")

data class UpgradePlan(
    val edits: List<Edit> = emptyList(),
    val restoreProjects: List<String> = emptyList(),
    val alsoChanges: List<Change> = emptyList(),
    val skipped: List<Skip> = emptyList(),
)
