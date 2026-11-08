package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.model.SeverityColor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ThreeStateCheckBox
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** Maps a severity bucket to a theme-aware color. */
fun SeverityColor.toJBColor(): Color = when (this) {
    SeverityColor.RED -> JBColor(0xD64B4B, 0xE06C6C)
    SeverityColor.YELLOW -> JBColor(0xB8860B, 0xD9A441)
    SeverityColor.GREEN -> JBColor(0x3C9A3C, 0x549E54)
    SeverityColor.NONE -> JBColor.foreground()
}

internal sealed class ListEntry
/** A row with a three-state checkbox over its own package rows. */
internal sealed class GroupEntry(val packages: List<PackageEntry>) : ListEntry() {
    /** Its package rows are hidden (the chevron points right). */
    var collapsed: Boolean = false
}
/** A `Project · framework` section header carrying its own package rows for the section toggle. */
internal class HeaderEntry(val label: ProjectLabel, packages: List<PackageEntry>) : GroupEntry(packages) {
    /** The full title (with the framework): what speed search matches. */
    val title: String get() = label.full
}

/** A project + target framework. A project with several target frameworks always shows them, so its rows differ. */
internal data class ProjectLabel(val name: String, val framework: String, val multiTarget: Boolean) {
    val full = "$name  ·  $framework"
    fun text(showFramework: Boolean) = if (showFramework || multiTarget) full else name
}
/**
 * By-package view: one package version with a `Project · framework` child row per place that uses it.
 * When a package has a CPM central row and other rows, [tag] is `central` or `override`.
 */
internal class PackageGroupEntry(val id: String, packages: List<PackageEntry>, val tag: String? = null) : GroupEntry(packages) {
    init { packages.forEach { it.parent = this } }
    /** Rows never change after build, so the summary is computed once, not per paint. */
    val summary: GroupSummary by lazy { PackageListLogic.groupSummary(this) }
}
/** A package row. With [child] (its project) it is a child of a [PackageGroupEntry]. */
internal class PackageEntry(val dep: DepRow, val target: String, val framework: String = "", val child: ProjectLabel? = null) : ListEntry() {
    var checked: Boolean = false
    /** The package row this project row sits under (by-package view). */
    var parent: PackageGroupEntry? = null

    /** A CPM central child: its version is shared, so only its package row toggles it. */
    val followsGroup: Boolean get() = child != null && dep.central
}

/** What a package row shows: one value when all children agree, else `various`. */
internal data class GroupSummary(val current: String, val newVersion: String, val color: SeverityColor, val variable: String? = null)

/** What the eye menu shows on top of names and versions. */
internal data class ListDisplay(val showFramework: Boolean = false, val showVariable: Boolean = false)

/** What a project row under a package row adds: only the values that differ from the package row (null = hidden). */
internal data class ChildParts(val current: String?, val variable: String?, val newVersion: String?)

/** One checked row, as the engine's planUpgrade needs it. */
data class CheckedRow(val project: String, val framework: String, val id: String, val target: String, val restoreOnly: Boolean)

internal fun PackageEntry.toCheckedRow() = CheckedRow(target, framework, dep.name, dep.newVersion, dep.restoreOnly)
/** The rows a recommendation click checks, and the list index it scrolls to (-1 when gone). */
internal class Recommendation(val index: Int, val rows: List<PackageEntry>)

/** Aggregate checkbox state of a section's outdated rows. */
internal enum class CheckState { NONE, SOME, ALL }

/** Pure list logic (no Swing) — unit-tested; [PackageListView] is the thin Swing wiring. */
internal object PackageListLogic {
    const val TAG_CENTRAL = "central"
    const val TAG_OVERRIDE = "override"

    /** Section headers + package rows in display order: sections sorted, deps outdated-first by name. */
    fun buildEntries(sections: List<PackageSection>): List<ListEntry> {
        val entries = ArrayList<ListEntry>()
        val multi = multiTargets(sections)
        for (section in sorted(sections)) {
            val packages = section.deps
                .sortedWith(compareByDescending<DepRow> { it.outdated }.thenBy { it.name.lowercase() })
                .map { PackageEntry(it, section.upgradeTarget, section.framework) }
            entries.add(HeaderEntry(label(section, multi), packages))
            entries.addAll(packages)
        }
        return entries
    }

    /**
     * Package rows (outdated first, then by name), each followed by its children sorted by project and
     * framework. One package row holds the CPM central children; the other children get one package
     * row per current version (VersionOverride, or projects on different versions without CPM). So a
     * click on a package row never moves a project that holds another version.
     */
    fun buildByPackage(sections: List<PackageSection>): List<ListEntry> {
        val multi = multiTargets(sections)
        return sorted(sections)
            .flatMap { s -> s.deps.map { PackageEntry(it, s.upgradeTarget, s.framework, label(s, multi)) } }
            .groupBy { it.dep.name.lowercase() }
            .values
            .sortedWith(compareByDescending<List<PackageEntry>> { c -> c.any { it.dep.outdated } }.thenBy { it.first().dep.name.lowercase() })
            .flatMap { children ->
                val (central, own) = children.partition { it.dep.central }
                val byVersion = own.groupBy { it.dep.current }.values
                buildList {
                    if (central.isNotEmpty()) add(PackageGroupEntry(central.first().dep.name, central, if (own.isEmpty()) null else TAG_CENTRAL))
                    byVersion.forEach { add(PackageGroupEntry(it.first().dep.name, it, if (central.isEmpty()) null else TAG_OVERRIDE)) }
                }
            }
            .flatMap { listOf(it) + it.packages }
    }

    /** Project files that appear with more than one target framework. */
    private fun multiTargets(sections: List<PackageSection>): Set<String> =
        sections.groupBy { it.upgradeTarget }.filterValues { s -> s.map { it.framework }.distinct().size > 1 }.keys

    private fun label(section: PackageSection, multi: Set<String>) =
        ProjectLabel(section.projectName, section.framework, section.upgradeTarget in multi)

    private fun sorted(sections: List<PackageSection>) = sections.sortedWith(compareBy({ it.projectName.lowercase() }, { it.framework }))


    /** Package rows a click or Space on [entry] toggles: none for a central child. */
    fun toggleTargets(entry: ListEntry?): List<PackageEntry> = when (entry) {
        is GroupEntry -> entry.packages.filter { it.dep.outdated }
        is PackageEntry -> if (entry.dep.outdated && !entry.followsGroup) listOf(entry) else emptyList()
        null -> emptyList()
    }

    fun groupSummary(group: PackageGroupEntry): GroupSummary {
        fun one(values: List<String>) = values.distinct().singleOrNull() ?: "various"
        val checkable = group.packages.filter { it.dep.outdated }
        val target = if (checkable.isEmpty()) "" else one(checkable.map { it.dep.newVersion })
        val color = if (target.isEmpty() || target == "various") SeverityColor.NONE else checkable.first().dep.color
        val variable = group.packages.map { it.dep.versionProperty }.distinct().singleOrNull()
        return GroupSummary(one(group.packages.map { it.dep.current }), target, color, variable)
    }

    /** What a row shows. A project row under a package row ([summary]) shows only values that differ from it. */
    fun childParts(child: PackageEntry, summary: GroupSummary?, showVariable: Boolean): ChildParts = ChildParts(
        current = child.dep.current.takeIf { it != summary?.current },
        variable = child.dep.versionProperty?.takeIf { showVariable && (summary == null || it != summary.variable) },
        newVersion = child.dep.newVersion.takeIf { it.isNotEmpty() && it != summary?.newVersion },
    )

    fun hasChecked(entries: List<ListEntry>): Boolean =
        entries.any { it is PackageEntry && it.checked && it.dep.outdated }

    /** Checkable (outdated) package rows. */
    fun outdatedEntries(entries: List<ListEntry>): List<PackageEntry> =
        entries.filterIsInstance<PackageEntry>().filter { it.dep.outdated }

    /** Ids of packages with a checkable major upgrade, once each, in list order. */
    fun majorIds(entries: List<ListEntry>): List<String> =
        entries.filterIsInstance<PackageEntry>().filter { it.dep.major }.map { it.dep.name }.distinctBy { it.lowercase() }

    /** Banner text for [count] packages with a new major version, of which the first [shown] are links. */
    fun recommendationText(count: Int, shown: Int): String {
        val head = if (count == 1) "1 package has" else "$count packages have"
        val more = count - shown
        return "$head a new major version. Click one to check it." + if (more > 0) " +$more more in the list." else ""
    }

    /** A click on recommended package [id]: check its major rows and scroll to its first package row. */
    fun recommend(entries: List<ListEntry>, id: String): Recommendation {
        fun hit(e: PackageEntry) = e.dep.major && e.dep.name.equals(id, ignoreCase = true)
        val index = entries.indexOfFirst { it is PackageEntry && hit(it) || it is PackageGroupEntry && it.packages.any(::hit) }
        return Recommendation(index, entries.filterIsInstance<PackageEntry>().filter(::hit))
    }

    /** [targets] plus every checkable row on the same `$(Name)` property site: one edit moves them all. */
    fun withShared(targets: List<PackageEntry>, entries: List<ListEntry>): List<PackageEntry> {
        fun site(e: PackageEntry) = e.dep.versionProperty?.let { e.dep.versionFile to e.dep.versionLine }
        val sites = targets.mapNotNullTo(HashSet(), ::site)
        if (sites.isEmpty()) return targets
        return (targets + outdatedEntries(entries).filter { site(it) in sites }).distinct()
    }

    /** The entries the list shows: rows of a collapsed group are left out. */
    fun visible(entries: List<ListEntry>): List<ListEntry> {
        val hidden = entries.filterIsInstance<GroupEntry>().filter { it.collapsed }.flatMapTo(HashSet<ListEntry>()) { it.packages }
        return if (hidden.isEmpty()) entries else entries.filterNot { it in hidden }
    }

    /** Groups a search opened that close again when it ends: all but the group holding the [found] row. */
    fun closeAfterSearch(opened: List<GroupEntry>, found: ListEntry?): List<GroupEntry> =
        opened.filterNot { g -> g.packages.any { it === found } }

    /** True when at least one row is checkable. */
    fun hasOutdated(entries: List<ListEntry>): Boolean = outdatedEntries(entries).isNotEmpty()

    /** True when there is at least one outdated row and all of them are checked. */
    fun allOutdatedChecked(entries: List<ListEntry>): Boolean {
        val outdated = outdatedEntries(entries)
        return outdated.isNotEmpty() && outdated.all { it.checked }
    }

    /** Checked outdated rows, one per project + framework + package. */
    fun checkedRows(entries: List<ListEntry>): List<CheckedRow> =
        entries.filterIsInstance<PackageEntry>()
            .filter { it.checked && it.dep.outdated }
            .map { it.toCheckedRow() }
            .distinct()

    /** Next checkbox state for a selection: if any is unchecked, check all; else uncheck all. */
    fun nextToggleState(selectedOutdated: List<PackageEntry>): Boolean =
        !selectedOutdated.all { it.checked }

    /** Aggregate state of a header's outdated rows: NONE, SOME (partial), or ALL. */
    fun sectionCheckState(header: GroupEntry): CheckState {
        val outdated = header.packages.filter { it.dep.outdated }
        if (outdated.isEmpty()) return CheckState.NONE
        val checked = outdated.count { it.checked }
        return when (checked) {
            0 -> CheckState.NONE
            outdated.size -> CheckState.ALL
            else -> CheckState.SOME
        }
    }

    /** True when the header has at least one checkable (outdated) row. */
    fun sectionHasOutdated(header: GroupEntry): Boolean = header.packages.any { it.dep.outdated }

    /**
     * Text a list entry is matched against by speed search: the package name for a row, the section
     * title for a header, empty otherwise. This is also the text whose matched characters the
     * renderer highlights.
     */
    fun searchText(entry: ListEntry?): String = when (entry) {
        is PackageEntry -> entry.child?.full ?: entry.dep.name
        is HeaderEntry -> entry.title
        is PackageGroupEntry -> entry.id
        null -> ""
    }
}

/**
 * Grouped package list mirroring Rider's NuGet view: a
 * `ProjectName · framework` header per project+TFM section, then one row per package with a
 * checkbox — `Name · Current` on the left, new version (colored by severity) right-aligned.
 * Check multiple outdated packages to drive "Update Selected". Type to speed-search by name.
 */
internal class PackageListView(
    private val onSelectionChanged: () -> Unit,
    /** Runs a row-menu item (see [RowMenu]). */
    private val onMenuItem: (MenuItem) -> Unit,
) {

    private val model = DefaultListModel<ListEntry>()
    /** Every entry, also the rows of collapsed groups (checks act on them too); the model holds only the shown ones. */
    private var all: List<ListEntry> = emptyList()
    /** Groups a running search opened; null when no search runs. */
    private var openedForSearch: List<GroupEntry>? = null

    /** What the rows show besides names and versions; a change only repaints (checks stay). */
    var display = ListDisplay()
        set(value) { field = value; list.repaint() }

    private val renderer = EntryRenderer { display }
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = renderer
        addListSelectionListener { dropHeaderSelections() }
    }

    init {
        // Toggle a checkbox by clicking it.
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // The PopupHandler below owns right clicks.
                if (e.isPopupTrigger || !SwingUtilities.isLeftMouseButton(e)) return
                val index = list.locationToIndex(e.point)
                if (index < 0) return
                when (val entry = model.getElementAt(index)) {
                    is PackageEntry -> if (entry.dep.blocked != null && renderer.hitsInfo(list, index, e.point)) {
                        showBlocked(entry.dep.blocked, e)
                    } else if (e.x <= checkboxEnd(entry)) {
                        toggle(PackageListLogic.toggleTargets(entry))
                    }
                    // The chevron opens or closes the group; the checkbox after it toggles every outdated package under it.
                    is GroupEntry -> when {
                        e.x <= JBUI.scale(CHEVRON_HIT_WIDTH) -> setCollapsed(listOf(entry), !entry.collapsed)
                        e.x <= JBUI.scale(CHEVRON_HIT_WIDTH) + CHECKBOX_HIT_WIDTH -> toggle(PackageListLogic.toggleTargets(entry))
                    }
                }
            }
        })
        // Left closes and Right opens the selected package rows, as in a tree.
        for ((key, collapse) in listOf("LEFT" to true, "RIGHT" to false))
            list.registerKeyboardAction({ setCollapsed(selectedGroups(), collapse) }, KeyStroke.getKeyStroke(key), JComponent.WHEN_FOCUSED)
        // Space toggles the checkbox on all selected outdated rows.
        list.registerKeyboardAction(
            { toggleCheckedForSelection() },
            KeyStroke.getKeyStroke("SPACE"),
            JComponent.WHEN_FOCUSED,
        )
        // Right-click menu: package items on a package row, project items on a project row.
        list.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val index = list.locationToIndex(Point(x, y))
                if (index < 0) return
                val items = RowMenu.itemsFor(model.getElementAt(index), all)
                if (items.isEmpty()) return
                if (!list.isSelectedIndex(index)) list.selectedIndex = index
                val group = DefaultActionGroup(items.map { item ->
                    object : DumbAwareAction(item.text) {
                        override fun actionPerformed(e: AnActionEvent) = onMenuItem(item)
                    }
                })
                ActionManager.getInstance().createActionPopupMenu("NuGetExtended.RowMenu", group).component.show(comp, x, y)
            }
        })
        // Rider-style speed search: hidden until you type, then filters/navigates by text.
        val search = ListSpeedSearch.installOn(list) { PackageListLogic.searchText(it) }
        // Search sees only shown rows: open every group while it runs, then close them again
        // except the one holding the found row.
        search.addChangeListener {
            val opened = openedForSearch
            if (search.isPopupActive && opened == null) {
                val closed = all.filterIsInstance<GroupEntry>().filter { it.collapsed }
                openedForSearch = closed
                if (closed.isNotEmpty()) {
                    setCollapsed(closed, false)
                    search.refreshSelection() // the first key searched before the rows were shown
                }
            } else if (!search.isPopupActive && opened != null) {
                openedForSearch = null
                setCollapsed(PackageListLogic.closeAfterSearch(opened, list.selectedValue), true)
                list.ensureIndexIsVisible(list.selectedIndex)
            }
        }
    }

    val component: JComponent get() = list

    /** Rebuilds the list (checks are cleared): grouped by package when [byPackage], else by project. */
    fun setData(sections: List<PackageSection>, byPackage: Boolean = false, collapsed: Boolean = false) {
        all = if (byPackage) PackageListLogic.buildByPackage(sections) else PackageListLogic.buildEntries(sections)
        openedForSearch = null
        setAllCollapsed(collapsed)
    }

    /** Collapses or expands every group; checks stay. */
    fun setAllCollapsed(collapsed: Boolean) {
        all.filterIsInstance<GroupEntry>().forEach { it.collapsed = collapsed }
        showVisible()
    }

    /** Opens or closes [groups] in place: only their rows move, so selection and scroll stay. */
    private fun setCollapsed(groups: List<GroupEntry>, collapsed: Boolean) {
        for (group in groups.filter { it.collapsed != collapsed && it.packages.isNotEmpty() }) {
            group.collapsed = collapsed
            val at = model.indexOf(group)
            if (at < 0) continue
            // A group's rows follow it in the model, in both views.
            if (collapsed) model.removeRange(at + 1, at + group.packages.size)
            else model.addAll(at + 1, group.packages)
        }
    }

    private fun selectedGroups(): List<GroupEntry> = list.selectedValuesList.filterIsInstance<GroupEntry>()

    /** Rebuilds the model from [all] without the rows of collapsed groups. */
    private fun showVisible() {
        model.clear()
        model.addAll(PackageListLogic.visible(all))
    }

    fun hasChecked(): Boolean = PackageListLogic.hasChecked(all)

    /** True when at least one checkable (outdated) row exists. */
    fun hasOutdated(): Boolean = PackageListLogic.hasOutdated(all)

    /** True when every outdated row is checked (drives the Select/Deselect All label). */
    fun allOutdatedChecked(): Boolean = PackageListLogic.allOutdatedChecked(all)

    /** Package ids with a stable major upgrade, for the recommendations banner. */
    fun majorIds(): List<String> = PackageListLogic.majorIds(all)

    /** Checks the major rows of [id] and scrolls to its package row. */
    fun recommend(id: String) {
        val r = PackageListLogic.recommend(all, id)
        if (r.index < 0) return
        // Open the groups that hold its rows, so the rows it checks can be seen.
        setCollapsed(all.filterIsInstance<GroupEntry>().filter { g -> g.packages.any { it in r.rows } }, false)
        val index = model.indexOf(all[r.index])
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        setChecked(PackageListLogic.withShared(r.rows, all), true)
    }

    /** Check all outdated rows if any is unchecked, else uncheck them all. */
    fun toggleSelectAll() {
        val outdated = PackageListLogic.outdatedEntries(all)
        if (outdated.isEmpty()) return
        setChecked(outdated, PackageListLogic.nextToggleState(outdated))
    }

    /** Check all [targets] if any is unchecked, else uncheck them all. Rows on the same `$(Name)` follow. */
    private fun toggle(targets: List<PackageEntry>) {
        if (targets.isEmpty()) return
        setChecked(PackageListLogic.withShared(targets, all), PackageListLogic.nextToggleState(targets))
    }

    private fun setChecked(targets: List<PackageEntry>, state: Boolean) {
        targets.forEach { it.checked = state }
        list.repaint()
        onSelectionChanged()
    }

    /** Right edge of the checkbox click area: a child row's checkbox is indented. */
    private fun checkboxEnd(entry: PackageEntry): Int =
        CHECKBOX_HIT_WIDTH + JBUI.scale(CHILD_INDENT) // every package row sits under a group

    /** Checked outdated rows for the upgrade. */
    fun checkedRows(): List<CheckedRow> = PackageListLogic.checkedRows(all)

    private fun toggleCheckedForSelection() =
        toggle(list.selectedIndices.flatMap { PackageListLogic.toggleTargets(model.getElementAt(it)) }.distinct())

    /** The shared-version message in a balloon under the click. */
    private fun showBlocked(message: String, e: MouseEvent) {
        val html = HtmlChunk.text(message).wrapWith(HtmlChunk.body().style("width: 320px")).wrapWith(HtmlChunk.html()).toString()
        val content = JBLabel(html, AllIcons.General.Information, JLabel.LEFT).apply { border = JBUI.Borders.empty(4) }
        JBPopupFactory.getInstance().createBalloonBuilder(content)
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .createBalloon()
            .show(RelativePoint(e), Balloon.Position.below)
    }

    /** Headers aren't actionable; keep them out of the selection. */
    private fun dropHeaderSelections() {
        val headers = list.selectedIndices.filter { model.getElementAt(it) is HeaderEntry }
        headers.forEach { list.selectionModel.removeSelectionInterval(it, it) }
    }

    private class EntryRenderer(private val display: () -> ListDisplay) : ListCellRenderer<ListEntry> {
        private val headerPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 8, 2, 8) }
        private val header = SimpleColoredComponent().apply { isOpaque = false }
        private val headerCheck = ThreeStateCheckBox().apply {
            isOpaque = false // display-only; the JList mouse listener drives toggling, not the component
            border = JBUI.Borders.emptyRight(6)
        }
        private val headerWest = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        private val headerChevron = JLabel().apply { border = JBUI.Borders.emptyRight(4) }
        private val groupChevron = JLabel().apply { border = JBUI.Borders.emptyRight(4) }
        private val groupLead = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        private val rowBorder = JBUI.Borders.empty(3, 8, 3, 10)
        private val childBorder = JBUI.Borders.empty(3, 8 + CHILD_INDENT, 3, 10)
        private val groupPanel = JPanel(BorderLayout()).apply { border = rowBorder }
        private val groupCheck = ThreeStateCheckBox().apply {
            isOpaque = false // display-only, like the header checkbox
            border = JBUI.Borders.emptyRight(6)
        }
        private val groupLeft = SimpleColoredComponent().apply { isOpaque = false }
        private val groupRight = SimpleColoredComponent().apply { isOpaque = false }
        private val groupWest = JPanel(BorderLayout()).apply { isOpaque = false }

        private val rowPanel = JPanel(BorderLayout()).apply { border = rowBorder }
        private val checkBox = JCheckBox().apply { isOpaque = false; border = JBUI.Borders.emptyRight(6) }
        private val westPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        private val left = SimpleColoredComponent().apply { isOpaque = false }
        private val right = SimpleColoredComponent().apply { isOpaque = false }
        /** Shown on a blocked row; a click on it opens the reason (see [hitsInfo]). */
        private val info = JLabel(AllIcons.General.Information).apply { border = JBUI.Borders.emptyLeft(6) }

        init {
            // The text takes the width left of the version column and is cut there: no overlap.
            westPanel.add(checkBox, BorderLayout.WEST)
            westPanel.add(left, BorderLayout.CENTER)
            westPanel.add(info, BorderLayout.EAST)
            headerWest.add(headerChevron)
            headerWest.add(headerCheck)
            headerWest.add(header)
            headerPanel.add(headerWest, BorderLayout.WEST)
            groupLead.add(groupChevron)
            groupLead.add(groupCheck)
            groupWest.add(groupLead, BorderLayout.WEST)
            groupWest.add(groupLeft, BorderLayout.CENTER)
            groupPanel.add(groupWest, BorderLayout.CENTER)
            groupPanel.add(groupRight, BorderLayout.EAST)
            rowPanel.add(westPanel, BorderLayout.CENTER)
            rowPanel.add(right, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out ListEntry>,
            entry: ListEntry,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component = when (entry) {
            is HeaderEntry -> {
                headerPanel.isOpaque = true
                headerPanel.background = list.background // headers ignore selection highlight
                headerCheck.background = list.background
                // The section checkbox mirrors its packages: all / partial / none. Kept visible but
                // greyed when nothing is checkable, so headers stay aligned (matches the row checkboxes).
                headerCheck.isEnabled = PackageListLogic.sectionHasOutdated(entry)
                headerCheck.state = checkState(entry)
                headerChevron.icon = chevron(entry)
                header.clear()
                header.append(entry.label.text(display().showFramework), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                // Highlight the speed-search match inside the header title, like Rider's own lists.
                SpeedSearchUtil.applySpeedSearchHighlighting(list, header, false, false)
                headerPanel
            }

            is PackageGroupEntry -> {
                val bg = if (selected) list.selectionBackground else list.background
                groupPanel.isOpaque = true
                groupPanel.background = bg
                groupCheck.background = bg
                groupCheck.isEnabled = PackageListLogic.sectionHasOutdated(entry)
                groupCheck.state = checkState(entry)
                groupChevron.icon = chevron(entry)
                val summary = entry.summary
                groupLeft.clear()
                groupRight.clear()
                val nameAttr = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, list.selectionForeground)
                else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                groupLeft.append(entry.id, nameAttr)
                entry.tag?.let { groupLeft.append(" ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                groupLeft.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                groupLeft.append(summary.current, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                summary.variable?.takeIf { display().showVariable }?.let { groupLeft.append("  \$($it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                SpeedSearchUtil.applySpeedSearchHighlighting(list, groupLeft, false, selected)
                if (summary.newVersion.isNotEmpty()) {
                    val attr = when {
                        summary.color != SeverityColor.NONE -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, summary.color.toJBColor())
                        else -> SimpleTextAttributes.GRAYED_ATTRIBUTES // "various"
                    }
                    groupRight.append(summary.newVersion, attr)
                }
                groupPanel
            }

            is PackageEntry -> {
                val bg = if (selected) list.selectionBackground else list.background
                rowPanel.isOpaque = true
                rowPanel.background = bg
                val child = entry.child
                rowPanel.border = childBorder // every package row sits under a group
                rowPanel.toolTipText = if (entry.followsGroup && entry.dep.outdated) CENTRAL_NOTE else entry.dep.note
                checkBox.background = bg
                // Only outdated packages are checkable; a central child follows its package row.
                checkBox.isEnabled = entry.dep.outdated && !entry.followsGroup
                checkBox.isSelected = entry.checked && entry.dep.outdated

                left.clear()
                right.clear()
                info.isVisible = entry.dep.blocked != null
                val nameAttr = when {
                    entry.dep.blocked != null -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    entry.followsGroup -> SimpleTextAttributes.GRAYED_ATTRIBUTES // not checkable: its package row decides
                    selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                val show = display()
                val parts = PackageListLogic.childParts(entry, entry.parent?.summary, show.showVariable)
                left.append(child?.text(show.showFramework) ?: entry.dep.name, nameAttr)
                parts.current?.let {
                    left.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    left.append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                parts.variable?.let { left.append("  \$($it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                // Highlight the speed-search match inside the row text, so the matched characters
                // stand out as you type — same as Rider's native list search. (`mainTextOnly=false`:
                // SimpleColoredComponent has no main-text index set here, so this is well-defined.)
                SpeedSearchUtil.applySpeedSearchHighlighting(list, left, false, selected)

                parts.newVersion?.let { newVersion ->
                    val attr = when {
                        entry.followsGroup -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        entry.dep.outdated && entry.dep.color != SeverityColor.NONE ->
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, entry.dep.color.toJBColor())
                        !entry.dep.outdated -> SimpleTextAttributes.GRAYED_ATTRIBUTES // newer, but outside the range
                        selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    right.append(newVersion, attr)
                }
                rowPanel
            }
        }

        private fun chevron(group: GroupEntry) = if (group.collapsed) UIUtil.getTreeCollapsedIcon() else UIUtil.getTreeExpandedIcon()

        private fun checkState(group: GroupEntry): ThreeStateCheckBox.State = when (PackageListLogic.sectionCheckState(group)) {
            CheckState.ALL -> ThreeStateCheckBox.State.SELECTED
            CheckState.SOME -> ThreeStateCheckBox.State.DONT_CARE
            CheckState.NONE -> ThreeStateCheckBox.State.NOT_SELECTED
        }

        /**
         * True when [point] (list coordinates) falls on the info icon of row [index]. The renderer
         * is a rubber stamp with no real bounds, so this lays it out for that cell first.
         */
        fun hitsInfo(list: JList<out ListEntry>, index: Int, point: Point): Boolean {
            val cell = list.getCellBounds(index, index) ?: return false
            val stamp = getListCellRendererComponent(list, list.model.getElementAt(index), index, false, false)
            if (!info.isVisible) return false
            stamp.setBounds(0, 0, cell.width, cell.height)
            invalidateDeep(stamp)
            layoutDeep(stamp)
            var x = info.x
            var y = info.y
            var parent = info.parent
            while (parent != null && parent !== stamp) {
                x += parent.x
                y += parent.y
                parent = parent.parent
            }
            return Rectangle(x, y, info.width, info.height).contains(point.x - cell.x, point.y - cell.y)
        }

        private fun invalidateDeep(c: Component) {
            c.invalidate()
            if (c is Container) c.components.forEach { invalidateDeep(it) }
        }

        private fun layoutDeep(c: Component) {
            if (c !is Container) return
            c.doLayout()
            c.components.forEach { layoutDeep(it) }
        }
    }

    private companion object {
        const val CHECKBOX_HIT_WIDTH = 32 // left px region that toggles the checkbox
        const val CHILD_INDENT = 22 // extra left inset of a package row under its group
        const val CHEVRON_HIT_WIDTH = 28 // left px region of a group row that opens or closes it
        const val CENTRAL_NOTE = "Set in the central package file, so it changes for every project. Use the package row."
    }
}
