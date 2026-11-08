package com.github.iamr8.nugetextended

import com.github.iamr8.nugetextended.engine.FrameworkRows
import com.github.iamr8.nugetextended.engine.PackageRow
import com.github.iamr8.nugetextended.engine.ProjectRows
import com.github.iamr8.nugetextended.engine.ScanResult
import com.github.iamr8.nugetextended.model.SeverityColor
import com.github.iamr8.nugetextended.ui.OutdatedRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdatedRowsTest {

    private fun result(vararg rows: PackageRow) =
        ScanResult(projects = listOf(ProjectRows("/r/A/A.csproj", "A", listOf(FrameworkRows("net8.0", rows.toList())))))

    @Test
    fun targetRowIsCheckableAndColoredBySeverity() {
        val dep = OutdatedRows.toDep(PackageRow("Polly", "[7.0.0,8.0.0)", "7.0.0", "7.2.4", "Minor", capped = "8.8.0"))
        assertTrue(dep.outdated)
        assertEquals("7.2.4", dep.newVersion)
        assertEquals(SeverityColor.YELLOW, dep.color)
        assertEquals("8.8.0 is newer but outside [7.0.0,8.0.0).", dep.note)
    }

    @Test
    fun requestedTextIsTrimmedForDisplay() {
        // A <Version> child element keeps the file's line breaks and indent in its raw text.
        val dep = OutdatedRows.toDep(PackageRow("Polly", "\r\n        7.1.0\r\n      ", null, "7.2.4", "Minor", capped = "8.8.0"))
        assertEquals("7.1.0", dep.requested)
        assertEquals("7.1.0", dep.current)
        assertEquals("8.8.0 is newer but outside 7.1.0.", dep.note)
    }

    @Test
    fun cappedVersionIsHiddenByDefault() {
        // A newer version outside the range is not an upgrade; showing it reads like one.
        val dep = OutdatedRows.toDep(PackageRow("Polly", "[7.2.4,8.0.0)", "7.2.4", null, "None", capped = "8.8.0", reason = "capped by range"))
        assertFalse(dep.outdated)
        assertEquals("", dep.newVersion)
        assertEquals("capped by range: [7.2.4,8.0.0)", dep.note)
    }

    @Test
    fun cappedVersionShowsGrayWhenAsked() {
        val dep = OutdatedRows.toDep(PackageRow("Polly", "[7.2.4,8.0.0)", "7.2.4", null, "None", capped = "8.8.0", reason = "capped by range"), showCapped = true)
        assertFalse(dep.outdated)
        assertEquals("8.8.0", dep.newVersion)
        assertEquals(SeverityColor.NONE, dep.color)
    }

    @Test
    fun cappedOnlyRowIsDroppedFromTheUpdateList() {
        // Nothing to upgrade inside the range: the row is up to date, so it is not in the outdated list.
        val row = PackageRow("Polly", "[7.2.4,8.0.0)", "7.2.4", null, "None", capped = "8.8.0", reason = "capped by range")
        assertTrue(OutdatedRows.fromScan(result(row), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun cappedOnlyRowStaysWhenAskedOrWhenListingAll() {
        val row = PackageRow("Polly", "[7.2.4,8.0.0)", "7.2.4", null, "None", capped = "8.8.0", reason = "capped by range")
        assertEquals("8.8.0", OutdatedRows.fromScan(result(row), emptyList(), emptyList(), showCapped = true).single().deps.single().newVersion)
        assertEquals("", OutdatedRows.fromScan(result(row), emptyList(), emptyList(), listAll = true).single().deps.single().newVersion)
    }

    @Test
    fun restoreOnlyRowIsCheckable() {
        val dep = OutdatedRows.toDep(PackageRow("Serilog", "2.*", "2.10.0", "2.14.1", "Minor", restoreOnly = true))
        assertTrue(dep.outdated)
        assertTrue(dep.restoreOnly)
        assertEquals("Floating version 2.*: restore picks 2.14.1.", dep.note)
    }

    @Test
    fun upToDateRowHasNoNewVersion() {
        val dep = OutdatedRows.toDep(PackageRow("Dapper", "2.1.0", "2.1.0"))
        assertFalse(dep.outdated)
        assertEquals("", dep.newVersion)
        assertNull(dep.note)
    }

    @Test
    fun currentFallsBackToRequestedWhenNotResolved() {
        assertEquals("1.2.3", OutdatedRows.toDep(PackageRow("X", "1.2.3", null)).current)
    }

    @Test
    fun sectionsCarryProjectPathAndFramework() {
        val sections = OutdatedRows.fromScan(result(PackageRow("B", "1.0.0", "1.0.0"), PackageRow("a", "1.0.0", "1.0.0")), emptyList(), emptyList())
        val s = sections.single()
        assertEquals("A", s.projectName)
        assertEquals("net8.0", s.framework)
        assertEquals("/r/A/A.csproj", s.upgradeTarget)
        assertEquals(listOf("a", "B"), s.deps.map { it.name })
    }

    @Test
    fun signInNeededReasonShowsShortGrayMarker() {
        val dep = OutdatedRows.toDep(PackageRow("Polly", "1.0.0", "1.0.0", reason = "sign-in needed for source 'MyFeed'"))
        assertFalse(dep.outdated)
        assertEquals("sign-in needed", dep.newVersion)
        assertEquals(SeverityColor.NONE, dep.color)
        assertEquals("sign-in needed for source 'MyFeed'", dep.note)
    }

    @Test
    fun sourceFailedReasonShowsShortGrayMarker() {
        val dep = OutdatedRows.toDep(PackageRow("Polly", "1.0.0", "1.0.0", reason = "package source 'MyFeed' failed"))
        assertFalse(dep.outdated)
        assertEquals("source failed", dep.newVersion)
        assertEquals(SeverityColor.NONE, dep.color)
        assertEquals("package source 'MyFeed' failed", dep.note)
    }

    @Test
    fun includeAndExcludeFiltersAreCaseInsensitiveSubstrings() {
        assertTrue(OutdatedRows.keep("Microsoft.EntityFrameworkCore", listOf("entity"), emptyList()))
        assertFalse(OutdatedRows.keep("Polly", listOf("entity"), emptyList()))
        assertFalse(OutdatedRows.keep("Microsoft.EntityFrameworkCore.Design", emptyList(), listOf("DESIGN")))
        val sections = OutdatedRows.fromScan(result(PackageRow("Polly", "1.0.0", "1.0.0")), emptyList(), listOf("poll"))
        assertTrue(sections.isEmpty())
    }

    // Break: toDep ignores `blocked`, so the row stays checkable.
    @Test
    fun blockedRowIsNotCheckableButKeepsItsVersion() {
        val why = "Lib.Y has no version 5.0.2."
        val dep = OutdatedRows.toDep(PackageRow("Lib.X", "5.0.1", "5.0.1", "5.0.2", "Patch", blocked = why))
        assertFalse(dep.outdated)
        assertEquals("5.0.2", dep.newVersion)
        assertEquals(why, dep.blocked)
        assertEquals(why, dep.note)
    }

    // Break: toDep drops the engine's `central` flag, so the by-package view enables a CPM child.
    @Test
    fun centralFlagReachesTheRow() {
        assertTrue(OutdatedRows.toDep(PackageRow("Serilog", "3.0.0", "3.0.0", "4.4.0", "Major", central = true)).central)
        assertFalse(OutdatedRows.toDep(PackageRow("Serilog", "3.0.0", "3.0.0", "4.4.0", "Major")).central)
    }

    // Break: toDep drops where the version is set or the project URL, so the row menu has nothing to open.
    @Test
    fun versionLocationAndProjectUrlReachTheRow() {
        val raw = PackageRow("Serilog", "3.0.0", "3.0.0", null, "None",
            versionFile = "/r/Directory.Packages.props", versionLine = 3, projectUrl = "https://serilog.net")
        val dep = OutdatedRows.toDep(raw)
        assertEquals("/r/Directory.Packages.props", dep.versionFile)
        assertEquals(3, dep.versionLine)
        assertEquals("https://serilog.net", dep.projectUrl)
    }

    // Break: fromScan drops a blocked row as if it had no target.
    @Test
    fun blockedRowStaysInTheList() {
        val row = PackageRow("Lib.X", "5.0.1", "5.0.1", "5.0.2", "Patch", blocked = "Lib.Y has no version 5.0.2.")
        val deps = OutdatedRows.fromScan(result(row), emptyList(), emptyList()).single().deps
        assertEquals(listOf("Lib.X"), deps.map { it.name })
    }
}
