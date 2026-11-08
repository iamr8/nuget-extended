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
internal sealed class GroupEntry(val packages: List<PackageEntry>) : ListEntry()
/** A `Project · framework` section header carrying its own package rows for the section toggle. */
internal class HeaderEntry(val title: String, packages: List<PackageEntry>) : GroupEntry(packages)
/**
 * By-package view: one package version with a `Project · framework` child row per place that uses it.
 * When a package has a CPM central row and other rows, [tag] is `central` or `override`.
 */
internal class PackageGroupEntry(val id: String, packages: List<PackageEntry>, val tag: String? = null) : GroupEntry(packages)
/** A package row. With [childLabel] (`Project · framework`) it is a child of a [PackageGroupEntry]. */
internal class PackageEntry(val dep: DepRow, val target: String, val framework: String = "", val childLabel: String? = null) : ListEntry() {
    var checked: Boolean = false

    /** A CPM central child: its version is shared, so only its package row toggles it. */
    val followsGroup: Boolean get() = childLabel != null && dep.central
}

/** What a package row shows: one value when all children agree, else `various`. */
internal data class GroupSummary(val current: String, val newVersion: String, val color: SeverityColor)

/** One checked row, as the engine's planUpgrade needs it. */
data class CheckedRow(val project: String, val framework: String, val id: String, val target: String, val restoreOnly: Boolean)

internal fun PackageEntry.toCheckedRow() = CheckedRow(target, framework, dep.name, dep.newVersion, dep.restoreOnly)

/** Aggregate checkbox state of a section's outdated rows. */
internal enum class CheckState { NONE, SOME, ALL }

/** Pure list logic (no Swing) — unit-tested; [PackageListView] is the thin Swing wiring. */
internal object PackageListLogic {
    const val TAG_CENTRAL = "central"
    const val TAG_OVERRIDE = "override"

    /** Section headers + package rows in display order: sections sorted, deps outdated-first by name. */
    fun buildEntries(sections: List<PackageSection>): List<ListEntry> {
        val entries = ArrayList<ListEntry>()
        for (section in sorted(sections)) {
            val packages = section.deps
                .sortedWith(compareByDescending<DepRow> { it.outdated }.thenBy { it.name.lowercase() })
                .map { PackageEntry(it, section.upgradeTarget, section.framework) }
            entries.add(HeaderEntry(title(section), packages))
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
    fun buildByPackage(sections: List<PackageSection>): List<ListEntry> =
        sorted(sections)
            .flatMap { s -> s.deps.map { PackageEntry(it, s.upgradeTarget, s.framework, title(s)) } }
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

    private fun sorted(sections: List<PackageSection>) = sections.sortedWith(compareBy({ it.projectName.lowercase() }, { it.framework }))

    private fun title(section: PackageSection) = "${section.projectName}  ·  ${section.framework}"

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
        return GroupSummary(one(group.packages.map { it.dep.current }), target, color)
    }

    fun hasChecked(entries: List<ListEntry>): Boolean =
        entries.any { it is PackageEntry && it.checked && it.dep.outdated }

    /** Checkable (outdated) package rows. */
    fun outdatedEntries(entries: List<ListEntry>): List<PackageEntry> =
        entries.filterIsInstance<PackageEntry>().filter { it.dep.outdated }

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
        is PackageEntry -> entry.childLabel ?: entry.dep.name
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
    private val renderer = EntryRenderer()
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
                    // The section or package checkbox toggles every outdated package under it.
                    is GroupEntry -> if (e.x <= CHECKBOX_HIT_WIDTH) toggle(PackageListLogic.toggleTargets(entry))
                }
            }
        })
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
                val items = RowMenu.itemsFor(model.getElementAt(index), entries())
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
        ListSpeedSearch.installOn(list) { PackageListLogic.searchText(it) }
    }

    val component: JComponent get() = list

    /** Rebuilds the list (checks are cleared): grouped by package when [byPackage], else by project. */
    fun setData(sections: List<PackageSection>, byPackage: Boolean = false) {
        model.clear()
        model.addAll(if (byPackage) PackageListLogic.buildByPackage(sections) else PackageListLogic.buildEntries(sections))
    }

    fun hasChecked(): Boolean = PackageListLogic.hasChecked(entries())

    /** True when at least one checkable (outdated) row exists. */
    fun hasOutdated(): Boolean = PackageListLogic.hasOutdated(entries())

    /** True when every outdated row is checked (drives the Select/Deselect All label). */
    fun allOutdatedChecked(): Boolean = PackageListLogic.allOutdatedChecked(entries())

    /** Check all outdated rows if any is unchecked, else uncheck them all. */
    fun toggleSelectAll() {
        val outdated = PackageListLogic.outdatedEntries(entries())
        if (outdated.isEmpty()) return
        val newState = PackageListLogic.nextToggleState(outdated)
        outdated.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    /** Check all [targets] if any is unchecked, else uncheck them all. */
    private fun toggle(targets: List<PackageEntry>) {
        if (targets.isEmpty()) return
        val newState = PackageListLogic.nextToggleState(targets)
        targets.forEach { it.checked = newState }
        list.repaint()
        onSelectionChanged()
    }

    /** Right edge of the checkbox click area: a child row's checkbox is indented. */
    private fun checkboxEnd(entry: PackageEntry): Int =
        CHECKBOX_HIT_WIDTH + if (entry.childLabel != null) JBUI.scale(CHILD_INDENT) else 0

    /** Checked outdated rows for the upgrade. */
    fun checkedRows(): List<CheckedRow> = PackageListLogic.checkedRows(entries())

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

    private fun entries(): List<ListEntry> = (0 until model.size()).map { model.getElementAt(it) }

    /** Headers aren't actionable; keep them out of the selection. */
    private fun dropHeaderSelections() {
        val headers = list.selectedIndices.filter { model.getElementAt(it) is HeaderEntry }
        headers.forEach { list.selectionModel.removeSelectionInterval(it, it) }
    }

    private class EntryRenderer : ListCellRenderer<ListEntry> {
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

        private val rowBorder = JBUI.Borders.empty(3, 8, 3, 10)
        private val childBorder = JBUI.Borders.empty(3, 8 + CHILD_INDENT, 3, 10)
        private val groupPanel = JPanel(BorderLayout()).apply { border = rowBorder }
        private val groupCheck = ThreeStateCheckBox().apply {
            isOpaque = false // display-only, like the header checkbox
            border = JBUI.Borders.emptyRight(6)
        }
        private val groupLeft = SimpleColoredComponent().apply { isOpaque = false }
        private val groupRight = SimpleColoredComponent().apply { isOpaque = false }
        private val groupWest = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        private val rowPanel = JPanel(BorderLayout()).apply { border = rowBorder }
        private val checkBox = JCheckBox().apply { isOpaque = false; border = JBUI.Borders.emptyRight(6) }
        private val westPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        private val left = SimpleColoredComponent().apply { isOpaque = false }
        private val right = SimpleColoredComponent().apply { isOpaque = false }
        /** Shown on a blocked row; a click on it opens the reason (see [hitsInfo]). */
        private val info = JLabel(AllIcons.General.Information).apply { border = JBUI.Borders.emptyLeft(6) }

        init {
            westPanel.add(checkBox)
            westPanel.add(left)
            westPanel.add(info)
            headerWest.add(headerCheck)
            headerWest.add(header)
            headerPanel.add(headerWest, BorderLayout.WEST)
            groupWest.add(groupCheck)
            groupWest.add(groupLeft)
            groupPanel.add(groupWest, BorderLayout.WEST)
            groupPanel.add(groupRight, BorderLayout.EAST)
            rowPanel.add(westPanel, BorderLayout.WEST)
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
                header.clear()
                header.append(entry.title, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
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
                val summary = PackageListLogic.groupSummary(entry)
                groupLeft.clear()
                groupRight.clear()
                val nameAttr = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, list.selectionForeground)
                else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                groupLeft.append(entry.id, nameAttr)
                entry.tag?.let { groupLeft.append(" ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                groupLeft.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                groupLeft.append(summary.current, SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
                rowPanel.border = if (entry.childLabel != null) childBorder else rowBorder
                rowPanel.toolTipText = if (entry.followsGroup && entry.dep.outdated) CENTRAL_NOTE else entry.dep.note
                checkBox.background = bg
                // Only outdated packages are checkable; a central child follows its package row.
                checkBox.isEnabled = entry.dep.outdated && !entry.followsGroup
                checkBox.isSelected = entry.checked && entry.dep.outdated

                left.clear()
                right.clear()
                info.isVisible = entry.dep.blocked != null
                val nameAttr = if (entry.dep.blocked != null) {
                    SimpleTextAttributes.ERROR_ATTRIBUTES
                } else if (selected) {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                } else {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                left.append(entry.childLabel ?: entry.dep.name, nameAttr)
                left.append("  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                left.append(entry.dep.current, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Highlight the speed-search match inside the row text, so the matched characters
                // stand out as you type — same as Rider's native list search. (`mainTextOnly=false`:
                // SimpleColoredComponent has no main-text index set here, so this is well-defined.)
                SpeedSearchUtil.applySpeedSearchHighlighting(list, left, false, selected)

                if (entry.dep.newVersion.isNotEmpty()) {
                    val attr = when {
                        entry.dep.outdated && entry.dep.color != SeverityColor.NONE ->
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, entry.dep.color.toJBColor())
                        !entry.dep.outdated -> SimpleTextAttributes.GRAYED_ATTRIBUTES // newer, but outside the range
                        selected -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, list.selectionForeground)
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    right.append(entry.dep.newVersion, attr)
                }
                rowPanel
            }
        }

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
        const val CHILD_INDENT = 22 // extra left inset of a child row in the by-package view
        const val CENTRAL_NOTE = "Set in the central package file, so it changes for every project. Use the package row."
    }
}
