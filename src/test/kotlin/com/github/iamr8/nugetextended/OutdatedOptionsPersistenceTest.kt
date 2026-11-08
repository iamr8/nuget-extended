package com.github.iamr8.nugetextended

import com.github.iamr8.nugetextended.cli.CredLogLevel
import com.github.iamr8.nugetextended.cli.OutdatedOptions
import com.github.iamr8.nugetextended.cli.PreRelease
import com.github.iamr8.nugetextended.cli.VersionLock
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves [OutdatedOptions] survives the exact XML (de)serialization that
 * `PersistentStateComponent` uses, including enums and string lists.
 */
class OutdatedOptionsPersistenceTest {

    private fun roundTrip(o: OutdatedOptions): OutdatedOptions =
        XmlSerializer.deserialize(XmlSerializer.serialize(o), OutdatedOptions::class.java)

    @Test
    fun defaultsRoundTrip() {
        val o = OutdatedOptions()
        assertEquals(o, roundTrip(o))
    }

    @Test
    fun fullyPopulatedRoundTrips() {
        val o = OutdatedOptions(
            includeAutoReferences = true,
            transitive = true,
            transitiveDepth = 4,
            includeUpToDate = false,
            showCappedVersions = true,
            preRelease = PreRelease.Always,
            preReleaseLabel = "rc.1",
            versionLock = VersionLock.Minor,
            maximumVersion = "8.0",
            olderThanDays = 14,
            recursive = true,
            includeFileBasedApps = true,
            includeFilters = mutableListOf("Alpha", "Beta"),
            excludeFilters = mutableListOf("Preview"),
            noRestore = true,
            ignoreFailedSources = false,
            idleTimeoutSeconds = 250,
            runtime = "linux-x64",
            credLogLevel = CredLogLevel.Error,
            groupByPackage = true,
            showRecommendations = false,
            showFramework = true,
            showVariable = true,
            collapseGroups = false,
        )
        assertEquals(o, roundTrip(o))
    }
}
