package com.github.iamr8.nugetextended

import com.github.iamr8.nugetextended.cli.CredLogLevel
import com.github.iamr8.nugetextended.cli.OutdatedOptions
import com.github.iamr8.nugetextended.cli.PreRelease
import com.github.iamr8.nugetextended.cli.VersionLock
import com.github.iamr8.nugetextended.cli.isValidMaximumVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdatedOptionsTest {

    private fun populated() = OutdatedOptions(
        includeAutoReferences = true,
        transitive = true,
        transitiveDepth = 3,
        includeUpToDate = true,
        showCappedVersions = true,
        preRelease = PreRelease.Always,
        preReleaseLabel = "rc",
        versionLock = VersionLock.Major,
        maximumVersion = "8.0",
        olderThanDays = 7,
        recursive = true,
        includeFileBasedApps = true,
        includeFilters = mutableListOf("A"),
        excludeFilters = mutableListOf("B"),
        noRestore = true,
        ignoreFailedSources = false,
        idleTimeoutSeconds = 200,
        runtime = "linux-x64",
        credLogLevel = CredLogLevel.Error,
        groupByPackage = true,
    )

    @Test
    fun deepCopyEqualsButListsAreIndependent() {
        val original = populated()
        val copy = original.deepCopy()
        assertEquals(original, copy)
        assertNotSame(original.includeFilters, copy.includeFilters)

        copy.includeFilters.add("X")
        assertEquals(listOf("A"), original.includeFilters) // original untouched
    }

    @Test
    fun assignFromCopiesEveryFieldAndDetachesLists() {
        val source = populated()
        val target = OutdatedOptions() // defaults
        target.assignFrom(source)
        assertEquals(source, target)

        target.excludeFilters.add("Y")
        assertEquals(listOf("B"), source.excludeFilters) // source untouched
    }

    @Test
    fun maximumVersionAcceptsWhatTheEngineParses() {
        listOf("", " ", "8", "8.0", "8.0.1", "8.0.1.2", " 9.1 ").forEach { assertTrue(it, isValidMaximumVersion(it)) }
        listOf("8.x", "v8", "8.0.1.2.3", "8.0-rc", "latest", "8.").forEach { assertFalse(it, isValidMaximumVersion(it)) }
    }
}
