package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.model.SeverityColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMenuTest {

    private fun dep(
        name: String = "Serilog",
        outdated: Boolean = true,
        current: String = "1.0.0",
        newV: String = "2.0.0",
        central: Boolean = false,
        file: String? = "/r/A/A.csproj",
        line: Int = 4,
        url: String? = null,
    ) = DepRow(
        name = name, current = current, newVersion = if (outdated) newV else "",
        color = if (outdated) SeverityColor.RED else SeverityColor.NONE, outdated = outdated,
        central = central, versionFile = file, versionLine = line, projectUrl = url,
    )

    private fun section(project: String, path: String, vararg deps: DepRow, framework: String = "net8.0") =
        PackageSection(project, framework, path, deps.toList())

    private inline fun <reified T : MenuItem> List<MenuItem>.one(): T = filterIsInstance<T>().single()

    // Break: a package row has no upgrade item, or it upgrades rows of other packages.
    @Test
    fun packageRowOffersUpgradeToItsTarget() {
        val entries = PackageListLogic.buildEntries(listOf(section("A", "/r/A/A.csproj", dep(), dep("Polly"))))
        val serilog = entries.filterIsInstance<PackageEntry>().single { it.dep.name == "Serilog" }

        val upgrade = RowMenu.itemsFor(serilog, entries).one<MenuItem.Upgrade>()

        assertEquals("Upgrade to 2.0.0", upgrade.text)
        assertEquals(listOf(CheckedRow("/r/A/A.csproj", "net8.0", "Serilog", "2.0.0", false)), upgrade.rows)
    }

    // Break: an up-to-date row offers an upgrade.
    @Test
    fun upToDateRowHasNoUpgrade() {
        val entries = PackageListLogic.buildEntries(listOf(section("A", "/r/A/A.csproj", dep(outdated = false))))
        val items = RowMenu.itemsFor(entries.filterIsInstance<PackageEntry>().single(), entries)
        assertTrue(items.none { it is MenuItem.Upgrade })
    }

    // Break: the central package row upgrades only one project, or the go-to item is missing.
    @Test
    fun centralPackageRowUpgradesEveryProjectAndGoesToTheCentralFile() {
        val props = "/r/Directory.Packages.props"
        val entries = PackageListLogic.buildByPackage(listOf(
            section("A", "/r/A/A.csproj", dep(central = true, file = props, line = 3)),
            section("B", "/r/B/B.csproj", dep(central = true, file = props, line = 3)),
        ))
        val items = RowMenu.itemsFor(entries.filterIsInstance<PackageGroupEntry>().single(), entries)

        assertEquals(listOf("/r/A/A.csproj", "/r/B/B.csproj"), items.one<MenuItem.Upgrade>().rows.map { it.project })
        assertEquals(MenuItem.GoToVersion(props, 3), items.one<MenuItem.GoToVersion>())
    }

    // Break: a package row whose children set the version in different files picks one of them.
    @Test
    fun noGoToItemWhenTheVersionIsSetInSeveralPlaces() {
        val entries = PackageListLogic.buildByPackage(listOf(
            section("A", "/r/A/A.csproj", dep(file = "/r/A/A.csproj")),
            section("B", "/r/B/B.csproj", dep(file = "/r/B/B.csproj")),
        ))
        val items = RowMenu.itemsFor(entries.filterIsInstance<PackageGroupEntry>().single(), entries)
        assertTrue(items.none { it is MenuItem.GoToVersion })
    }

    // Break: the nuget.org link misses the version, or the project page item ignores the feed's URL.
    @Test
    fun packageRowLinksToNuGetOrgAndTheProjectPage() {
        val entries = PackageListLogic.buildEntries(listOf(section("A", "/r/A/A.csproj", dep(url = "https://github.com/serilog/serilog"))))
        val items = RowMenu.itemsFor(entries.filterIsInstance<PackageEntry>().single(), entries)
        val urls = items.filterIsInstance<MenuItem.OpenUrl>().map { it.url }

        assertEquals(listOf("https://www.nuget.org/packages/Serilog/1.0.0", "https://github.com/serilog/serilog"), urls)
        assertEquals("Serilog", items.one<MenuItem.CopyId>().id)
        assertEquals("Serilog", items.one<MenuItem.ShowInNuGet>().id)
    }

    // Break: a project URL from the feed opens with any scheme (javascript:, file:), not only http(s).
    @Test
    fun projectUrlWithOtherSchemeIsNotOffered() {
        val entries = PackageListLogic.buildEntries(listOf(section("A", "/r/A/A.csproj", dep(url = "javascript:alert(1)"))))
        val urls = RowMenu.itemsFor(entries.filterIsInstance<PackageEntry>().single(), entries)
            .filterIsInstance<MenuItem.OpenUrl>().map { it.url }
        assertEquals(listOf("https://www.nuget.org/packages/Serilog/1.0.0"), urls)
    }

    // Break: a project header offers package items, or its upgrade takes rows of another project.
    @Test
    fun projectHeaderOpensTheProjectAndUpgradesOnlyItsRows() {
        val entries = PackageListLogic.buildEntries(listOf(
            section("A", "/r/A/A.csproj", dep(), dep("Polly", outdated = false)),
            section("B", "/r/B/B.csproj", dep()),
        ))
        val header = entries.filterIsInstance<HeaderEntry>().single { it.title.startsWith("A") }
        val items = RowMenu.itemsFor(header, entries)

        assertEquals(MenuItem.OpenProjectFile("/r/A/A.csproj"), items.one<MenuItem.OpenProjectFile>())
        assertEquals("Open A.csproj", items.one<MenuItem.OpenProjectFile>().text)
        assertEquals(listOf("Serilog"), items.one<MenuItem.UpgradeProject>().rows.map { it.id })
        assertFalse(items.any { it is MenuItem.CopyId })
    }

    // Break: a child row of the by-package view gets package items instead of project items.
    @Test
    fun childRowInTheByPackageViewIsAProjectRow() {
        val entries = PackageListLogic.buildByPackage(listOf(section("A", "/r/A/A.csproj", dep())))
        val child = entries.filterIsInstance<PackageEntry>().single()
        val items = RowMenu.itemsFor(child, entries)

        assertEquals("/r/A/A.csproj", items.one<MenuItem.OpenProjectFile>().path)
        assertTrue(items.none { it is MenuItem.CopyId })
    }
}
