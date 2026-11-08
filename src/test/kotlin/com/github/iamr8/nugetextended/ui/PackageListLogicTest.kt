package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.engine.PackageRow
import com.github.iamr8.nugetextended.model.SeverityColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageListLogicTest {

    private fun dep(name: String, outdated: Boolean = true, current: String = "1.0.0", newV: String = "2.0.0", central: Boolean = false, major: Boolean = false) =
        DepRow(
            name = name,
            current = current,
            newVersion = if (outdated) newV else "",
            color = if (outdated) SeverityColor.RED else SeverityColor.NONE,
            outdated = outdated,
            central = central,
            major = major,
        )

    private fun section(project: String, framework: String, target: String, deps: List<DepRow>) =
        PackageSection(project, framework, target, deps)

    @Test
    fun buildEntriesSortsSectionsAndPutsHeaderThenOutdatedFirst() {
        val sections = listOf(
            section("Zeta", "net8.0", "/z.csproj", listOf(dep("Bravo", outdated = false), dep("Alpha"))),
            section("Alpha", "net8.0", "/a.csproj", listOf(dep("Solo"))),
        )
        val entries = PackageListLogic.buildEntries(sections)

        // Section order: Alpha before Zeta.
        val headers = entries.filterIsInstance<HeaderEntry>().map { it.title }
        assertEquals(listOf("Alpha  ·  net8.0", "Zeta  ·  net8.0"), headers)

        // First entry is the Alpha header, then its package.
        assertTrue(entries[0] is HeaderEntry)
        assertEquals("Solo", (entries[1] as PackageEntry).dep.name)

        // Zeta section: outdated (Alpha) before up-to-date (Bravo).
        val zetaPkgs = entries.dropWhile { !(it is HeaderEntry && it.title.startsWith("Zeta")) }
            .filterIsInstance<PackageEntry>().map { it.dep.name }
        assertEquals(listOf("Alpha", "Bravo"), zetaPkgs)
    }

    @Test
    fun hasCheckedReflectsCheckedOutdatedRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B", outdated = false)))),
        )
        assertFalse(PackageListLogic.hasChecked(entries))

        (entries.first { it is PackageEntry && it.dep.name == "A" } as PackageEntry).checked = true
        assertTrue(PackageListLogic.hasChecked(entries))
    }

    @Test
    fun checkedRowsCarryProjectFrameworkIdAndTarget() {
        val entries = PackageListLogic.buildEntries(
            listOf(
                section("P1", "net8.0", "/p1.csproj", listOf(dep("Foo"), dep("Bar"))),
                section("P2", "net6.0", "/p2.csproj", listOf(dep("Foo"))),
            ),
        )
        entries.filterIsInstance<PackageEntry>().forEach { it.checked = true }

        val rows = PackageListLogic.checkedRows(entries).sortedWith(compareBy({ it.project }, { it.id }))
        assertEquals(
            listOf(
                CheckedRow("/p1.csproj", "net8.0", "Bar", "2.0.0", false),
                CheckedRow("/p1.csproj", "net8.0", "Foo", "2.0.0", false),
                CheckedRow("/p2.csproj", "net6.0", "Foo", "2.0.0", false),
            ),
            rows,
        )
    }

    @Test
    fun checkedRowsIgnoreUpToDateEvenIfFlagged() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("UpToDate", outdated = false)))),
        )
        (entries.first { it is PackageEntry } as PackageEntry).checked = true // shouldn't happen via UI, but guard anyway
        assertTrue(PackageListLogic.checkedRows(entries).isEmpty())
    }

    @Test
    fun outdatedEntriesKeepsOnlyCheckableRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B", outdated = false)))),
        )
        assertEquals(listOf("A"), PackageListLogic.outdatedEntries(entries).map { it.dep.name })
    }

    @Test
    fun hasOutdatedTrueOnlyWhenACheckableRowExists() {
        val none = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A", outdated = false)))),
        )
        assertFalse(PackageListLogic.hasOutdated(none))

        val some = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        assertTrue(PackageListLogic.hasOutdated(some))
    }

    @Test
    fun allOutdatedCheckedIgnoresUpToDateRowsAndNeedsAtLeastOne() {
        val empty = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A", outdated = false)))),
        )
        assertFalse(PackageListLogic.allOutdatedChecked(empty)) // no checkable row

        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B"), dep("Up", outdated = false)))),
        )
        assertFalse(PackageListLogic.allOutdatedChecked(entries)) // none checked
        PackageListLogic.outdatedEntries(entries).forEach { it.checked = true }
        assertTrue(PackageListLogic.allOutdatedChecked(entries)) // up-to-date row doesn't block
    }

    @Test
    fun sectionCheckStateReflectsNonePartialAll() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B"), dep("Up", outdated = false)))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        val outdated = PackageListLogic.outdatedEntries(entries)

        assertEquals(CheckState.NONE, PackageListLogic.sectionCheckState(header))
        outdated.first().checked = true
        assertEquals(CheckState.SOME, PackageListLogic.sectionCheckState(header))
        outdated.forEach { it.checked = true }
        assertEquals(CheckState.ALL, PackageListLogic.sectionCheckState(header)) // up-to-date row ignored
    }

    @Test
    fun sectionWithNoOutdatedIsNoneAndHasNoOutdated() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("Up", outdated = false)))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        assertFalse(PackageListLogic.sectionHasOutdated(header))
        assertEquals(CheckState.NONE, PackageListLogic.sectionCheckState(header))
    }

    @Test
    fun headerPackagesShareInstancesWithFlatRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        val row = entries.filterIsInstance<PackageEntry>().single { it.dep.name == "A" }
        header.packages.first { it.dep.name == "A" }.checked = true
        assertTrue(row.checked) // toggling via the header mutates the same row object
    }

    @Test
    fun nextToggleStateChecksWhenAnyUnchecked_elseUnchecks() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("P", "net8.0", "/p.csproj", listOf(dep("A"), dep("B")))),
        )
        val pkgs = entries.filterIsInstance<PackageEntry>()
        assertTrue(PackageListLogic.nextToggleState(pkgs)) // none checked -> check
        pkgs.forEach { it.checked = true }
        assertFalse(PackageListLogic.nextToggleState(pkgs)) // all checked -> uncheck
    }

    @Test
    fun searchTextIsPackageNameForRows() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("MyProj", "net8.0", "/p.csproj", listOf(dep("Newtonsoft.Json")))),
        )
        val row = entries.filterIsInstance<PackageEntry>().single()
        assertEquals("Newtonsoft.Json", PackageListLogic.searchText(row))
    }

    @Test
    fun searchTextIsSectionTitleForHeaders() {
        val entries = PackageListLogic.buildEntries(
            listOf(section("MyProj", "net8.0", "/p.csproj", listOf(dep("A")))),
        )
        val header = entries.filterIsInstance<HeaderEntry>().single()
        // Header title is also what the renderer highlights, so it must match the section label.
        assertEquals("MyProj  ·  net8.0", header.title)
        assertEquals("MyProj  ·  net8.0", PackageListLogic.searchText(header))
    }

    @Test
    fun searchTextIsEmptyForNull() {
        // ListSpeedSearch may hand the accessor a null element (e.g. an empty model); must not throw.
        assertEquals("", PackageListLogic.searchText(null))
    }

    // Break: a blocked row counts as checkable, so the section toggle or Select All checks it.
    @Test
    fun blockedRowIsSkippedBySectionToggleAndSelectAll() {
        val blocked = OutdatedRows.toDep(PackageRow("Lib.X", "5.0.1", "5.0.1", "5.0.2", "Patch", blocked = "Lib.Y has no version 5.0.2."))
        val entries = PackageListLogic.buildEntries(listOf(section("A", "net8.0", "/a.csproj", listOf(blocked, dep("Polly")))))

        PackageListLogic.outdatedEntries(entries).forEach { it.checked = true }

        assertEquals(listOf("Polly"), PackageListLogic.checkedRows(entries).map { it.id })
        assertEquals(CheckState.ALL, PackageListLogic.sectionCheckState(entries.filterIsInstance<HeaderEntry>().single()))
        assertTrue(PackageListLogic.allOutdatedChecked(entries))
    }

    // --- Group by package --------------------------------------------------

    private fun byPackage(vararg sections: PackageSection) = PackageListLogic.buildByPackage(sections.toList())

    private fun group(entries: List<ListEntry>, id: String, tag: String? = null) =
        entries.filterIsInstance<PackageGroupEntry>().single { it.id == id && it.tag == tag }

    // Break: buildByPackage keeps the by-project order, or does not put children under their package.
    @Test
    fun buildByPackagePutsOutdatedPackagesFirstWithProjectChildren() {
        val entries = byPackage(
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog"))),
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog"), dep("Polly", outdated = false))),
            section("C", "net6.0", "/c.csproj", listOf(dep("Serilog"))),
        )

        assertEquals(
            listOf("Serilog", "A  ·  net8.0", "B  ·  net8.0", "C  ·  net6.0", "Polly", "A  ·  net8.0"),
            entries.map { PackageListLogic.searchText(it) },
        )
    }

    // Break: central and VersionOverride rows share one package row, so one click also moves the
    // projects that hold their own version.
    @Test
    fun overrideRowsGetTheirOwnPackageRow() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", central = true))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog", central = true))),
            section("C", "net8.0", "/c.csproj", listOf(dep("Serilog", current = "0.9.0"))),
        )

        assertEquals(
            listOf("Serilog", "A  ·  net8.0", "B  ·  net8.0", "Serilog", "C  ·  net8.0"),
            entries.map { PackageListLogic.searchText(it) },
        )
        assertEquals(listOf("/a.csproj", "/b.csproj"), PackageListLogic.toggleTargets(group(entries, "Serilog", "central")).map { it.target })
        assertEquals(listOf("/c.csproj"), PackageListLogic.toggleTargets(group(entries, "Serilog", "override")).map { it.target })
    }

    // Break: projects on different versions share one package row, so one click moves both versions.
    @Test
    fun eachVersionGetsItsOwnPackageRowWithoutCpm() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", current = "1.0.0"))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog", current = "2.0.0", newV = "3.0.0"))),
            section("C", "net8.0", "/c.csproj", listOf(dep("Serilog", current = "1.0.0"))),
        )
        val groups = entries.filterIsInstance<PackageGroupEntry>()

        assertEquals(listOf(listOf("/a.csproj", "/c.csproj"), listOf("/b.csproj")), groups.map { g -> g.packages.map { it.target } })
        assertEquals(listOf(null, null), groups.map { it.tag })
    }

    // Break: two VersionOverride versions share one package row.
    @Test
    fun eachOverrideVersionGetsItsOwnPackageRow() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", central = true))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog", current = "0.8.0"))),
            section("C", "net8.0", "/c.csproj", listOf(dep("Serilog", current = "0.9.0"))),
        )
        val groups = entries.filterIsInstance<PackageGroupEntry>()

        assertEquals(listOf("central", "override", "override"), groups.map { it.tag })
        assertEquals(listOf(listOf("/a.csproj"), listOf("/b.csproj"), listOf("/c.csproj")), groups.map { g -> g.packages.map { it.target } })
    }

    // Break: a package without CPM gets a tag, though it has only one package row.
    @Test
    fun packageWithOneVersionSourceHasNoTag() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog"))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog"))),
        )
        assertEquals(listOf("/a.csproj", "/b.csproj"), group(entries, "Serilog").packages.map { it.target })
    }

    // Break: a central child toggles alone, which suggests a per-project upgrade CPM cannot do.
    @Test
    fun centralChildDoesNotToggleAloneButOverrideChildDoes() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", central = true))),
            section("C", "net8.0", "/c.csproj", listOf(dep("Serilog"))),
        )
        val central = group(entries, "Serilog", "central").packages.single()
        val override = group(entries, "Serilog", "override").packages.single()

        assertTrue(PackageListLogic.toggleTargets(central).isEmpty())
        assertEquals(listOf(override), PackageListLogic.toggleTargets(override))
    }

    // Break: the central rule leaks into the by-project view, where every row toggles alone.
    @Test
    fun centralRowTogglesAloneInTheByProjectView() {
        val entries = PackageListLogic.buildEntries(listOf(section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", central = true)))))
        val row = entries.filterIsInstance<PackageEntry>().single()

        assertEquals(listOf(row), PackageListLogic.toggleTargets(row))
    }

    // Break: the package row's three-state counts blocked or up-to-date children.
    @Test
    fun packageRowStateCountsOnlyCheckableChildren() {
        val blocked = OutdatedRows.toDep(PackageRow("Serilog", "1.0.0", "1.0.0", "2.0.0", "Major", blocked = "Serilog.X has no version 2.0.0."))
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog"))),
            section("B", "net8.0", "/b.csproj", listOf(blocked)),
            section("C", "net8.0", "/c.csproj", listOf(dep("Serilog", outdated = false))),
        )
        val g = group(entries, "Serilog")
        assertEquals(CheckState.NONE, PackageListLogic.sectionCheckState(g))

        PackageListLogic.toggleTargets(g).forEach { it.checked = true }

        assertEquals(CheckState.ALL, PackageListLogic.sectionCheckState(g))
        assertEquals(listOf("/a.csproj"), PackageListLogic.checkedRows(entries).map { it.project })
    }

    // Break: the package row shows one child's version when the children disagree.
    @Test
    fun packageRowShowsVariousWhenChildrenDisagree() {
        // Same version, different ranges: each range offers its own target.
        val mixed = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", current = "1.0.0", newV = "2.0.0"))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog", current = "1.0.0", newV = "3.0.0"))),
        )
        assertEquals(GroupSummary("1.0.0", "various", SeverityColor.NONE), PackageListLogic.groupSummary(group(mixed, "Serilog")))

        val same = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog"))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Serilog"))),
        )
        assertEquals(GroupSummary("1.0.0", "2.0.0", SeverityColor.RED), PackageListLogic.groupSummary(group(same, "Serilog")))
    }

    // Break: checking the package row sends rows with the wrong project or framework to the planner.
    @Test
    fun checkedRowsFromThePackageRow() {
        val entries = byPackage(
            section("A", "net8.0", "/a.csproj", listOf(dep("Serilog", central = true))),
            section("A", "net6.0", "/a.csproj", listOf(dep("Serilog", central = true))),
        )

        PackageListLogic.toggleTargets(group(entries, "Serilog")).forEach { it.checked = true }

        assertEquals(
            listOf(
                CheckedRow("/a.csproj", "net6.0", "Serilog", "2.0.0", false),
                CheckedRow("/a.csproj", "net8.0", "Serilog", "2.0.0", false),
            ),
            PackageListLogic.checkedRows(entries).sortedBy { it.framework },
        )
    }

    // Break: majorIds lists a package twice, lists a non-major one, or loses list order.
    @Test
    fun majorIdsListEachMajorPackageOnceInListOrder() {
        val entries = PackageListLogic.buildEntries(listOf(
            section("A", "net8.0", "/a.csproj", listOf(dep("Zed", major = true), dep("Minor"), dep("Alpha", major = true))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Zed", major = true))),
        ))
        assertEquals(listOf("Alpha", "Zed"), PackageListLogic.majorIds(entries))
    }

    // Break: recommend checks a non-major row of the package, or scrolls to a child or header instead of the package row.
    @Test
    fun recommendChecksTheMajorRowsAndPointsAtTheFirstPackageRow() {
        val sections = listOf(
            section("A", "net8.0", "/a.csproj", listOf(dep("Other"), dep("Polly", major = true))),
            section("B", "net8.0", "/b.csproj", listOf(dep("Polly", current = "7.0.0", newV = "7.2.0"))),
        )

        val byProject = PackageListLogic.buildEntries(sections)
        val p = PackageListLogic.recommend(byProject, "polly")
        assertEquals(2, p.index) // header A, Other, Polly
        assertEquals(listOf("/a.csproj"), p.rows.map { it.target })

        val byPackage = PackageListLogic.buildByPackage(sections)
        val g = PackageListLogic.recommend(byPackage, "Polly")
        assertTrue(byPackage[g.index] is PackageGroupEntry)
        assertTrue(g.rows.single() in (byPackage[g.index] as PackageGroupEntry).packages)
        assertEquals(listOf("/a.csproj"), g.rows.map { it.target })
    }

    // Break: the banner text gets the plural wrong, or counts the hidden packages wrong.
    @Test
    fun recommendationTextCountsAndNamesTheHiddenOnes() {
        assertEquals("1 package has a new major version. Click one to check it.", PackageListLogic.recommendationText(1, 1))
        assertEquals("3 packages have a new major version. Click one to check it.", PackageListLogic.recommendationText(3, 3))
        assertEquals("12 packages have a new major version. Click one to check it. +2 more in the list.", PackageListLogic.recommendationText(12, 10))
    }
}
