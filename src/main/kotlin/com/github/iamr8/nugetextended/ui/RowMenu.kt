package com.github.iamr8.nugetextended.ui

import java.io.File

/** One item of a list row's right-click menu. [PackageListView] shows it; the panel runs it. */
internal sealed class MenuItem(val text: String) {
    class Upgrade(version: String?, val rows: List<CheckedRow>) :
        MenuItem(if (version != null) "Upgrade to $version" else "Upgrade to the Newest in Range")
    data class GoToVersion(val file: String, val line: Int) : MenuItem("Go to Version Definition")
    class OpenUrl(text: String, val url: String) : MenuItem(text)
    data class ShowInNuGet(val id: String) : MenuItem("Show in NuGet Window")
    data class CopyId(val id: String) : MenuItem("Copy Package Id")
    data class OpenProjectFile(val path: String) : MenuItem("Open ${File(path).name}")
    class UpgradeProject(val rows: List<CheckedRow>) : MenuItem("Upgrade All in This Project")
}

/** Pure: which menu items a row gets. A package row gets package items; a project row (a section header, or a child row of the by-package view) gets project items. */
internal object RowMenu {

    fun itemsFor(entry: ListEntry, all: List<ListEntry>): List<MenuItem> = when (entry) {
        is HeaderEntry -> entry.packages.firstOrNull()?.let { projectItems(it.target, all) }.orEmpty()
        is PackageGroupEntry -> packageItems(entry.id, entry.packages)
        is PackageEntry -> if (entry.childLabel != null) projectItems(entry.target, all) else packageItems(entry.dep.name, listOf(entry))
    }

    private fun packageItems(id: String, rows: List<PackageEntry>): List<MenuItem> = buildList {
        val checkable = rows.filter { it.dep.outdated }
        if (checkable.isNotEmpty()) {
            add(MenuItem.Upgrade(checkable.map { it.dep.newVersion }.distinct().singleOrNull(), checkable.map { it.toCheckedRow() }.distinct()))
        }
        rows.mapNotNull { r -> r.dep.versionFile?.let { MenuItem.GoToVersion(it, r.dep.versionLine) } }.distinct().singleOrNull()?.let { add(it) }
        val current = rows.map { it.dep.current }.distinct().singleOrNull()
        add(MenuItem.OpenUrl("Open on nuget.org", "https://www.nuget.org/packages/$id" + (current?.let { "/$it" } ?: "")))
        rows.firstNotNullOfOrNull { webUrl(it.dep.projectUrl) }?.let { add(MenuItem.OpenUrl("Open Project Page", it)) }
        add(MenuItem.ShowInNuGet(id))
        add(MenuItem.CopyId(id))
    }

    private fun projectItems(path: String, all: List<ListEntry>): List<MenuItem> = buildList {
        add(MenuItem.OpenProjectFile(path))
        val rows = PackageListLogic.outdatedEntries(all).filter { it.target == path }.map { it.toCheckedRow() }.distinct()
        if (rows.isNotEmpty()) add(MenuItem.UpgradeProject(rows))
    }

    /** The project URL comes from a package feed: only an absolute http(s) URL may open in the browser. */
    private fun webUrl(text: String?): String? =
        text?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}
