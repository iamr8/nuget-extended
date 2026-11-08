package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.engine.HelperBugException
import org.junit.Assert.assertEquals
import org.junit.Test

class OutdatedPanelLogicTest {

    // --- staleSkipReasons ---------------------------------------------------

    @Test
    fun `a leftover stale project not named by any restore failure gets the normal reason`() {
        val result = staleSkipReasons(
            stale = listOf("/repo/A.csproj"),
            restoredLabels = emptySet(),
            noRestore = false,
            restoreFailed = false,
        )
        assertEquals(listOf("A" to "restore did not bring its packages up to date"), result)
    }

    @Test
    fun `every leftover stale project gets the restore-failed reason when the restore failed`() {
        val result = staleSkipReasons(
            stale = listOf("/repo/A.csproj", "/repo/B.csproj"),
            restoredLabels = emptySet(),
            noRestore = false,
            restoreFailed = true,
        )
        assertEquals(
            listOf(
                "A" to "restore failed - see Copy Details",
                "B" to "restore failed - see Copy Details",
            ),
            result,
        )
    }

    @Test
    fun `a project already named by a restore failure is not listed twice`() {
        val result = staleSkipReasons(
            stale = listOf("/repo/A.csproj", "/repo/B.csproj"),
            restoredLabels = setOf("A.csproj"),
            noRestore = false,
            restoreFailed = true,
        )
        assertEquals(listOf("B" to "restore failed - see Copy Details"), result)
    }

    @Test
    fun `a whole-solution restore failure names the sln, not a same-named project`() {
        // dotnet new's default layout names the solution after its first project (App.sln +
        // App.csproj). A restore failure for App.sln must not be mistaken for one on App.csproj -
        // App.csproj still needs its own "restore failed" line.
        val result = staleSkipReasons(
            stale = listOf("/repo/App.csproj", "/repo/B.csproj"),
            restoredLabels = setOf("App.sln"),
            noRestore = false,
            restoreFailed = true,
        )
        assertEquals(
            listOf(
                "App" to "restore failed - see Copy Details",
                "B" to "restore failed - see Copy Details",
            ),
            result,
        )
    }

    @Test
    fun `restore off wins over a restore failure reason`() {
        val result = staleSkipReasons(
            stale = listOf("/repo/A.csproj"),
            restoredLabels = emptySet(),
            noRestore = true,
            restoreFailed = false,
        )
        assertEquals(listOf("A" to "not restored (restore is off in settings)"), result)
    }

    @Test
    fun `an untrusted project is skipped with the trust reason`() {
        val result = staleSkipReasons(
            stale = listOf("/repo/A.csproj"),
            restoredLabels = emptySet(),
            noRestore = false,
            restoreFailed = false,
            trusted = false,
        )
        assertEquals(listOf("A" to "project not trusted - restore is off"), result)
    }

    // --- trust gate -----------------------------------------------------------

    @Test
    fun `restore runs only for a trusted project with restore on`() {
        assertEquals(true, restoreAllowed(trusted = true, noRestore = false))
        assertEquals(false, restoreAllowed(trusted = false, noRestore = false))
        assertEquals(false, restoreAllowed(trusted = true, noRestore = true))
    }

    @Test
    fun `an untrusted project sends no file-based apps to the engine`() {
        val paths = listOf("/r/A/A.csproj", "/r/tools/app.cs", "/r/tools/B.CS")
        assertEquals(listOf("/r/A/A.csproj"), enginePaths(paths, trusted = false))
        assertEquals(paths, enginePaths(paths, trusted = true))
    }

    // --- mergeScope -----------------------------------------------------------

    @Test
    fun `a project added since the last scan is included`() {
        // Break: mergeScope returns oldIncluded unchanged (new projects are never added).
        val result = mergeScope(setOf("A", "B"), listOf("A", "B"), listOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), result.toList())
    }

    @Test
    fun `a project the user excluded stays excluded when another one is added`() {
        // Break: mergeScope includes every new name, so the excluded project comes back.
        val result = mergeScope(setOf("A", "C"), listOf("A", "B", "C"), listOf("A", "B", "C", "D"))
        assertEquals(listOf("A", "C", "D"), result.toList())
    }

    @Test
    fun `a removed project leaves the scope`() {
        // Break: mergeScope keeps names that are no longer in the solution.
        val result = mergeScope(setOf("A", "B"), listOf("A", "B"), listOf("A"))
        assertEquals(listOf("A"), result.toList())
    }

    @Test
    fun `the first discovery includes every project`() {
        // Break: mergeScope keeps only oldIncluded, so a solution found late starts empty.
        val result = mergeScope(emptySet(), emptyList(), listOf("A", "B"))
        assertEquals(listOf("A", "B"), result.toList())
    }

    @Test
    fun `when every included project is removed the scope falls back to all`() {
        // Break: mergeScope returns the empty set, so the scope shows 0 of N.
        val result = mergeScope(setOf("A"), listOf("A", "B"), listOf("B"))
        assertEquals(listOf("B"), result.toList())
    }

    // --- pickedScope ----------------------------------------------------------

    @Test
    fun `a project added while the picker was open joins the picked scope`() {
        // Break: the picker saves its checkboxes as they are, so a scan that re-read the solution
        // while the popup was open loses the new project (#60).
        val result = pickedScope(setOf("A"), listOf("A", "B"), listOf("A", "B", "C"))
        assertEquals(listOf("A", "C"), result.toList())
    }

    @Test
    fun `an empty pick means every current project`() {
        // Break: an empty pick falls back to the projects the popup showed, not the current ones.
        val result = pickedScope(emptySet(), listOf("A", "B"), listOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), result.toList())
    }

    // --- internalErrorDetails -------------------------------------------------

    @Test
    fun `a helper bug report carries the helper's own stack trace`() {
        val details = "System.InvalidOperationException: boom\n   at Helper.Scan()"
        assertEquals(listOf(details), internalErrorDetails(HelperBugException("boom", details)).toList())
        assertEquals(emptyList<String>(), internalErrorDetails(HelperBugException("boom", null)).toList())
        assertEquals(emptyList<String>(), internalErrorDetails(IllegalStateException("plugin bug")).toList())
    }

    // --- restoreOnlyPlan ------------------------------------------------------

    private fun row(project: String, restoreOnly: Boolean) =
        CheckedRow(project = project, framework = "net9.0", id = "Foo", target = "2.0.0", restoreOnly = restoreOnly)

    @Test
    fun `restore-only rows are counted per row, projects kept distinct`() {
        val checked = listOf(
            row("A.csproj", restoreOnly = true),
            row("A.csproj", restoreOnly = true), // second floating package in the same project
            row("B.csproj", restoreOnly = false),
        )
        val (projects, rowCount) = restoreOnlyPlan(checked, noRestore = false)
        assertEquals(listOf("A.csproj"), projects)
        assertEquals(2, rowCount)
    }

    @Test
    fun `restore off drops every restore-only row and project`() {
        val checked = listOf(row("A.csproj", restoreOnly = true))
        val (projects, rowCount) = restoreOnlyPlan(checked, noRestore = true)
        assertEquals(emptyList<String>(), projects)
        assertEquals(0, rowCount)
    }

    @Test
    fun `no restore-only rows checked`() {
        val checked = listOf(row("A.csproj", restoreOnly = false))
        val (projects, rowCount) = restoreOnlyPlan(checked, noRestore = false)
        assertEquals(emptyList<String>(), projects)
        assertEquals(0, rowCount)
    }
}
