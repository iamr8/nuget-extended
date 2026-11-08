package com.github.iamr8.nugetextended.ui

import com.github.iamr8.nugetextended.PluginText
import com.github.iamr8.nugetextended.cli.CliFailures
import com.github.iamr8.nugetextended.cli.DotnetRunner
import com.github.iamr8.nugetextended.cli.OutdatedOptions
import com.github.iamr8.nugetextended.cli.ScanPlan
import com.github.iamr8.nugetextended.cli.Solution
import com.github.iamr8.nugetextended.cli.SolutionModel
import com.github.iamr8.nugetextended.edit.EditApplier
import com.github.iamr8.nugetextended.engine.EngineOptions
import com.github.iamr8.nugetextended.engine.HelperException
import com.github.iamr8.nugetextended.engine.HelperFatalException
import com.github.iamr8.nugetextended.engine.HelperService
import com.github.iamr8.nugetextended.engine.HelperTimeoutException
import com.github.iamr8.nugetextended.engine.HelperUserException
import com.github.iamr8.nugetextended.engine.PlanParams
import com.github.iamr8.nugetextended.engine.RiderRestore
import com.github.iamr8.nugetextended.engine.ScanParams
import com.github.iamr8.nugetextended.engine.ScanResult
import com.github.iamr8.nugetextended.engine.SourceFailure
import com.github.iamr8.nugetextended.engine.UpgradePlan
import com.github.iamr8.nugetextended.engine.UpgradeRow
import com.github.iamr8.nugetextended.settings.OutdatedConfigurable
import com.github.iamr8.nugetextended.settings.OutdatedOptionsService
import com.intellij.execution.ExecutionException
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.impl.isTrusted
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Root component of the "NuGet (Extended)" tool window. */
class OutdatedPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val engine = HelperService.getInstance(project)
    private val restorer = DotnetRunner()
    private val optionsService = OutdatedOptionsService.getInstance(project)
    private val listView = PackageListView(onSelectionChanged = { toolbar.updateActionsAsync() }, onMenuItem = ::runMenuItem)
    private val status = JBLabel(" ")

    private val toolbar: ActionToolbar = buildToolbar()

    // Volatile: a scan task re-reads both on its background thread; the EDT reads them for the toolbar.
    @Volatile private var solution: Solution? = null
    /** [SolutionProject.key]s of the solution's projects to include in the view (empty = show everything). */
    @Volatile private var includedProjects: MutableSet<String> = linkedSetOf()
    /** Last scan result; the view is built from this. */
    private var allRows: List<PackageSection> = emptyList()
    private var updatesChecked = false
    private var skippedProjects = 0
    private var busy = false
    /** EDT only: the scope changed while a scan ran, so the list loads again when it ends. */
    private var relistAfterScan = false
    private var listedOnce = false
    /** Holds the recommendations banner, under the toolbar. */
    private val recommendations = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(0, 8, 6, 8) }
    /** The user closed the banner: it stays hidden until the next scan. */
    private var recommendationsClosed = false
    /** True while the solution file is unreadable, so its balloon shows once per failure streak. Declared before `init`. */
    @Volatile private var solutionUnreadable = false

    init {
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(recommendations, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(listView.component), BorderLayout.CENTER)
        add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)

        discoverSolution()
    }

    /** Lazy: on first show, list packages only if enabled. */
    override fun addNotify() {
        super.addNotify()
        if (!listedOnce) {
            listedOnce = true
            if (optionsService.options.includeUpToDate) runListPackages()
            else setStatus("Press Check for Updates to find outdated packages.")
        }
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ReloadPackagesAction())
            add(CheckForUpdatesAction())
            add(ScopeAction())
            addSeparator()
            add(SelectAllAction())
            add(UpdateAction())
            addSeparator()
            add(ViewGroup())
            add(OptionsAction())
        }
        return ActionManager.getInstance()
            .createActionToolbar("NuGetExtended", group, true)
            .also { it.targetComponent = this }
    }

    private fun basePath(): String = project.basePath ?: System.getProperty("user.dir")

    /**
     * Restore and file-based-app evaluation run the repository's MSBuild code, so an untrusted
     * (safe mode) project gets neither. `com.intellij.ide.impl.isTrusted` is the one call that exists
     * from 2024.3 on; its replacement `TrustedProjects.isProjectTrusted(Project)` is not in 2024.3.
     */
    @Suppress("DEPRECATION")
    private fun projectTrusted(): Boolean = project.isTrusted()

    /** The engine runs from the solution folder, so that folder's global.json picks the SDK. */
    private fun workDir(): String = solution?.solutionPath?.let { File(it).parent } ?: basePath()

    private fun discoverSolution() {
        try {
            solution = SolutionModel.discover(File(basePath()), project.name)
            solutionUnreadable = false
        } catch (e: IOException) {
            // The window must still open. The scope stays empty and the next scan reads the file again.
            solutionReadFailed(e, known = false)
        }
        includedProjects = solution?.projects?.map { it.key }?.toMutableSet() ?: linkedSetOf()
        toolbar.updateActionsAsync()
    }

    /**
     * Re-reads the solution so projects added or removed since the last scan are seen, and returns
     * it with this scan's scope (see [mergeScope]). Background thread only: it reads files and touches
     * no UI. The saved scope is merged on the EDT, where the scope picker also writes it, so a pick
     * made during the scan is not lost (#60). When the solution file cannot be read (permission, a
     * sharing lock), the last known solution and scope stay, and the scan goes on with them.
     */
    private fun refreshSolution(): Pair<Solution?, Set<String>> {
        val known = solution
        val before = known?.projects?.map { it.key }.orEmpty()
        val fresh = try {
            SolutionModel.discover(File(basePath()), project.name)
        } catch (e: IOException) {
            solutionReadFailed(e, known = known != null)
            return known to includedProjects.toSet()
        }
        solutionUnreadable = false
        val after = fresh?.projects?.map { it.key }.orEmpty()
        solution = fresh
        onEdt { includedProjects = mergeScope(includedProjects, before, after) }
        return fresh to mergeScope(includedProjects, before, after)
    }

    /**
     * The solution file cannot be read (permission, a sharing lock): an environment failure, so a
     * balloon, not an error report. Shown once per failure streak; the next good read resets it.
     * Safe from any thread: [notifyFailure] posts to the EDT.
     */
    private fun solutionReadFailed(e: IOException, known: Boolean) {
        if (solutionUnreadable) {
            LOG.warn("${PluginText.NAME}: could not read the solution file again", e)
            return
        }
        solutionUnreadable = true
        val effect = if (known) "the scan uses the last known projects" else "the scan looks for project files in the folder instead"
        notifyFailure("Could not read the solution file", listOf(ScanFailure("Solution", effect, e.toString())), updateStatus = false)
    }

    private fun scopeLabel(): String {
        val sln = solution ?: return "Scope: (no solution)"
        val total = sln.projects.size
        return "Scope: ${sln.name} (${includedProjects.size}/$total)"
    }

    private fun setStatus(text: String) {
        status.text = text.ifBlank { " " }
    }

    /**
     * A dotnet / user-project failure (unrestored project, a package version that doesn't exist,
     * a failing feed, …) — *not* a plugin bug. Surfaced as a balloon with the full output one
     * click away, plus `LOG.warn` for idea.log.
     *
     * Deliberately never `LOG.error`: that opens the IDE's fatal-error dialog and would push other
     * people's broken solutions into the plugin's Marketplace Exception Analyzer.
     */
    private fun notifyFailure(
        context: String,
        failures: List<ScanFailure>,
        type: NotificationType = NotificationType.WARNING,
        updateStatus: Boolean = true,
    ) {
        if (failures.isEmpty()) return
        val details = failures.joinToString("\n\n") { it.details }
        LOG.warn("${PluginText.NAME}: $context\n$details")

        val shown = failures.take(MAX_SHOWN_FAILURES)
        val body = buildString {
            shown.forEach { append(StringUtil.escapeXmlEntities(it.line)).append("<br/>") }
            val more = failures.size - shown.size
            if (more > 0) append("…and $more more.")
        }
        onEdt {
            if (updateStatus) setStatus("$context — see the notification for details.")
            notificationGroup()
                .createNotification(context, body, type)
                .addAction(
                    NotificationAction.createSimpleExpiring("Copy Details") {
                        CopyPasteManager.getInstance().setContents(StringSelection("$context\n\n$details"))
                    },
                )
                .notify(project)
        }
    }

    /** An unexpected plugin-side exception: this one *is* worth reporting (IDE error reporter). */
    private fun reportInternalError(context: String, throwable: Throwable) {
        // The helper's own stack trace (or its stderr tail) goes into the report as text.
        LOG.error("${PluginText.NAME}: $context", throwable, *internalErrorDetails(throwable))
        onEdt { setStatus("$context — see the IDE error report for details.") }
    }

    /** Engine failures: the user's environment -> balloon; engine or plugin bugs -> error report. */
    private fun reportEngineError(context: String, error: Throwable) {
        when (error) {
            is HelperUserException, is HelperFatalException, is HelperTimeoutException ->
                notifyFailure(context, listOf(ScanFailure("Engine", error.message ?: context, (error as HelperException).details.orEmpty())))
            else -> reportInternalError(context, error)
        }
    }

    /** After an update check (only then do rows carry `major`), a banner lists the packages with a new major version. A click checks one. EDT only. */
    private fun updateRecommendations() {
        recommendations.removeAll()
        val ids = if (!recommendationsClosed && optionsService.options.showRecommendations) listView.majorIds() else emptyList()
        if (ids.isNotEmpty()) {
            val shown = ids.take(MAX_RECOMMENDATIONS)
            val text = PackageListLogic.recommendationText(ids.size, shown.size)
            val banner = InlineBanner(text, EditorNotificationPanel.Status.Info).setCloseAction { recommendationsClosed = true }
            shown.forEach { id -> banner.addAction(id) { listView.recommend(id) } }
            recommendations.add(banner)
        }
        recommendations.revalidate()
        recommendations.repaint()
    }

    private fun notificationGroup() =
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

    /** Render the list from [allRows] and update the status line. EDT only. */
    private fun render() {
        listView.display = display()
        listView.setData(allRows, optionsService.options.groupByPackage)
        val total = allRows.sumOf { it.deps.size }
        val projects = allRows.map { it.projectName }.distinct().size
        val outdated = allRows.sumOf { s -> s.deps.count { it.outdated } }
        val blocked = allRows.sumOf { s -> s.deps.count { it.blocked != null } }
        val blockedText = if (blocked > 0) ", $blocked blocked by a shared version" else ""
        val skipped = if (skippedProjects > 0) " ($skippedProjects skipped)" else ""
        setStatus(
            when {
                total == 0 && skippedProjects == 0 && updatesChecked -> "All packages are up to date."
                total == 0 && skippedProjects == 0 -> "No NuGet packages found."
                total == 0 -> "No packages listed$skipped."
                !updatesChecked -> "$total package(s) in $projects project(s)$skipped. Press Check for Updates."
                outdated == 0 && blocked == 0 -> "$total package(s) in $projects project(s) — all up to date$skipped."
                else -> "$total package(s) in $projects project(s), $outdated outdated$blockedText$skipped."
            },
        )
    }

    private fun showScopePicker(anchor: JComponent) {
        val projects = solution?.projects ?: return
        if (projects.isEmpty()) return
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
        val boxes = projects.map { p ->
            JBCheckBox(p.name, p.key in includedProjects).also { it.toolTipText = p.path; panel.add(it) }
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(panel), boxes.firstOrNull())
            .setRequestFocus(true)
            .setTitle("Projects in ${solution?.name}")
            .createPopup()
        val before = includedProjects.toSet()
        popup.setFinalRunnable {
            val picked = projects.indices.filter { boxes[it].isSelected }.map { projects[it].key }.toSet()
            // A scan may have re-read the solution while the popup was open.
            val current = solution?.projects?.map { it.key } ?: projects.map { it.key }
            val next = pickedScope(picked, projects.map { it.key }, current)
            if (next != before) {
                includedProjects = next
                toolbar.updateActionsAsync()
                // Re-list only when the selection actually changed; after the running scan if there is one.
                if (busy) relistAfterScan = true else runListPackages()
            }
        }
        popup.showUnderneathOf(anchor)
    }

    /** Phase 1: packages and current versions from the last restore (no update check). */
    private fun runListPackages() = runScanTask(checkUpdates = false)

    /** Phase 2: check the package sources for newer versions. */
    private fun runScan() = runScanTask(checkUpdates = true)

    private fun runScanTask(checkUpdates: Boolean) {
        if (busy) return
        busy = true
        relistAfterScan = false // this scan reads the current scope
        toolbar.updateActionsAsync()
        setStatus(if (checkUpdates) "Checking for updates…" else "Finding packages…")
        FileDocumentManager.getInstance().saveAllDocuments() // the engine reads project files from disk

        val options = optionsService.options.deepCopy()
        val trusted = projectTrusted()
        val title = if (checkUpdates) "${PluginText.NAME}: checking for package updates" else "${PluginText.NAME}: listing NuGet packages"
        val hardFailContext = if (checkUpdates) "Update check failed" else "Listing packages failed"
        val skipContext = if (checkUpdates) "Some projects were skipped during the update check" else "Some projects were skipped while listing"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var rows: List<PackageSection> = emptyList()
            private val failures = mutableListOf<ScanFailure>()
            private var sources: List<SourceFailure> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Discovery reads the solution and may walk the tree, so it runs here, not on the EDT.
                indicator.text = "Finding projects…"
                val (sln, scope) = refreshSolution()
                val paths = enginePaths(ScanPlan.projectPaths(sln, scope, File(basePath()), options.recursive, options.includeFileBasedApps), trusted)
                indicator.text = "Analyzing ${paths.size} project(s)…"
                var result = scanOnce(paths, options, checkUpdates, indicator)
                var restoreFailed = false
                var restoredLabels = emptySet<String>()
                if (result.stale.isNotEmpty() && restoreAllowed(trusted, options.noRestore)) {
                    val restoreFailures = restoreBesideRider(result.stale, emptyList(), options, indicator, afterUpgrade = false, graceMs = 1_500)
                    failures += restoreFailures
                    restoreFailed = restoreFailures.isNotEmpty()
                    restoredLabels = restoreFailures.map { it.label }.toSet()
                    indicator.text = "Analyzing ${paths.size} project(s)…"
                    result = scanOnce(paths, options, checkUpdates, indicator)
                }
                // Every leftover stale project is listed as skipped - a restore failure alone doesn't
                // explain every one of them (e.g. a whole-solution restore names only the .sln), so a
                // project not already named by one still needs its own reason.
                staleSkipReasons(result.stale, restoredLabels, options.noRestore, restoreFailed, trusted).forEach { (label, reason) ->
                    failures += ScanFailure(label, reason, "")
                }
                result.failures.forEach { failures += ScanFailure(it.project, CliFailures.describe(it.summary, it.details), it.details) }
                sources = result.sourceFailures
                rows = OutdatedRows.fromScan(result, options.includeFilters, options.excludeFilters, options.showCappedVersions, options.includeUpToDate)
            }

            override fun onSuccess() = onEdt {
                busy = false
                finishScan(rows, failures, checkUpdates, hardFailContext, skipContext)
                notifySources(sources)
                relistIfScopeChanged()
            }

            override fun onCancel() = cancelled()

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportEngineError(hardFailContext, error)
                toolbar.updateActionsAsync()
                relistIfScopeChanged()
            }
        })
    }

    /** EDT only: loads the list again when the scope picker changed the scope during the scan that just ended. */
    private fun relistIfScopeChanged() {
        if (!relistAfterScan) return
        relistAfterScan = false
        runListPackages()
    }

    private fun scanOnce(paths: List<String>, options: OutdatedOptions, checkUpdates: Boolean, indicator: ProgressIndicator): ScanResult =
        engine.call(
            workDir(), "scan",
            ScanParams(workDir(), paths, EngineOptions.from(options, checkUpdates)),
            ScanResult::class.java, timeoutMs(options), indicator,
        )

    /**
     * `dotnet restore` of [changed] + [alwaysOurs], but never at the same time as Rider's own restore:
     * both write the same obj files. Waits for Rider first; when Rider restored the changed projects
     * meanwhile, only [alwaysOurs] still needs our restore.
     */
    private fun restoreBesideRider(
        changed: List<String>,
        alwaysOurs: List<String>,
        options: OutdatedOptions,
        indicator: ProgressIndicator,
        afterUpgrade: Boolean,
        graceMs: Long,
    ): List<ScanFailure> {
        indicator.text = "Waiting for Rider's NuGet restore…"
        val outcome = RiderRestore.await(
            busy = { RiderRestore.isBusy(project) },
            graceMs = graceMs, maxMs = restoreTimeoutMs(options), pollMs = 500,
            now = System::currentTimeMillis, sleep = Thread::sleep, checkCanceled = indicator::checkCanceled,
        )
        val ours = RiderRestore.oursAfter(outcome, changed, alwaysOurs)
            ?: return listOf(ScanFailure("Restore", "Rider's NuGet restore is still running - re-scan when it ends", ""))
        if (outcome == RiderRestore.Outcome.RIDER_RESTORED) changed.forEach(engine::markChanged) // Rider rewrote their obj files
        if (ours.isEmpty()) return emptyList()
        indicator.text = "Restoring ${ours.size} project(s)…"
        // [alwaysOurs] are the floating projects: only they are restored with --force-evaluate.
        return restore(ours, options, indicator, afterUpgrade, forceEvaluate = alwaysOurs)
    }

    /** Runs `dotnet restore`, then tells the engine: restore rewrites the obj folder's `*.nuget.g.props`, which evaluation imports. */
    private fun restore(paths: List<String>, options: OutdatedOptions, indicator: ProgressIndicator, afterUpgrade: Boolean, forceEvaluate: List<String>): List<ScanFailure> {
        val failures = try {
            restorer.restore(
                paths, solution?.solutionPath, solution?.projects?.map { it.path }.orEmpty(),
                options.runtime, workDir(), restoreTimeoutMs(options), indicator, afterUpgrade, forceEvaluate,
            )
        } catch (e: ExecutionException) {
            // dotnet could not even be started - an environment failure, not a plugin bug.
            return listOf(failureOf("Restore", e.message.orEmpty(), "", "Could not run dotnet restore: ${e.message.orEmpty()}"))
        }
        paths.forEach(engine::markChanged)
        return failures.map {
            failureOf(it.label, it.stderr, it.stdout, if (it.timedOut) "timed out" else CliFailures.describe(it.stderr, it.stdout))
        }
    }

    private fun finishScan(
        rows: List<PackageSection>,
        failures: List<ScanFailure>,
        checked: Boolean,
        hardFailContext: String,
        skipContext: String,
    ) {
        allRows = rows // PackageListView sorts sections/rows for a stable order
        skippedProjects = failures.size
        updatesChecked = checked
        recommendationsClosed = false
        render()
        updateRecommendations()
        // Nothing came back at all -> tell the user why. Partial results already say "(n skipped)"
        // in the status line, so that case gets a quiet, non-status-hijacking balloon.
        if (rows.isEmpty() && failures.isNotEmpty()) notifyFailure(hardFailContext, failures)
        else if (failures.isNotEmpty()) notifyFailure(skipContext, failures, NotificationType.INFORMATION, updateStatus = false)
        toolbar.updateActionsAsync()
    }

    private fun notifySources(sources: List<SourceFailure>) {
        val lines = sources.map {
            ScanFailure(it.source, if (it.signInNeeded) "sign-in needed - check the credential provider" else it.message, it.message)
        }
        notifyFailure("Some package sources failed", lines, NotificationType.INFORMATION, updateStatus = false)
    }

    /** Runs a row-menu item. EDT only. */
    private fun runMenuItem(item: MenuItem) {
        when (item) {
            is MenuItem.Upgrade -> runUpgrade(item.rows)
            is MenuItem.UpgradeProject -> runUpgrade(item.rows)
            is MenuItem.GoToVersion -> openFile(item.file, item.line)
            is MenuItem.OpenProjectFile -> openFile(item.path, 0)
            is MenuItem.OpenUrl -> BrowserUtil.browse(item.url)
            is MenuItem.CopyId -> CopyPasteManager.getInstance().setContents(StringSelection(item.id))
            is MenuItem.ShowInNuGet -> {
                // Rider has no public API to select a package in its NuGet window: open it, with the id ready to paste in its search.
                CopyPasteManager.getInstance().setContents(StringSelection(item.id))
                ToolWindowManager.getInstance(project).getToolWindow(RIDER_NUGET_TOOL_WINDOW)?.activate(null)
            }
        }
    }

    /** Opens [path] at the 1-based [line] (0 = the top). */
    private fun openFile(path: String, line: Int) {
        val file = EditApplier.findLocal(path) ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun runUpgrade(checked: List<CheckedRow> = listView.checkedRows()) {
        if (busy) return
        if (checked.isEmpty()) return

        busy = true
        toolbar.updateActionsAsync()
        setStatus("Planning the upgrade…")
        FileDocumentManager.getInstance().saveAllDocuments()

        val options = optionsService.options.deepCopy()
        val trusted = projectTrusted()
        val restoreOff = !restoreAllowed(trusted, options.noRestore)
        val rows = checked.filterNot { it.restoreOnly }.map { UpgradeRow(it.project, it.framework, it.id, it.target) }
        val (restoreOnly, restoreOnlyRowCount) = restoreOnlyPlan(checked, restoreOff)
        if (restoreOff && checked.any { it.restoreOnly }) {
            // Restore is the only way a floating row gets its new version, and restore is off -
            // there is nothing this upgrade can do for it, so it never reaches the confirm dialog.
            notifyFailure(
                "Restore is off",
                listOf(
                    ScanFailure(
                        "Floating packages",
                        "${checked.count { it.restoreOnly }} floating package(s) need a restore to pick up their new version. " +
                            (if (trusted) "Restore is off in settings" else "Restore is off for an untrusted project") +
                            ", so they were not upgraded.",
                        "",
                    ),
                ),
                NotificationType.INFORMATION,
                updateStatus = false,
            )
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "${PluginText.NAME}: planning the upgrade", true) {
            private var plan = UpgradePlan()

            override fun run(indicator: ProgressIndicator) {
                // Re-read first: the restore after the upgrade also reads the solution.
                val sln = refreshSolution().first
                if (rows.isEmpty()) return
                // Every solution project may share a version with a checked row, so all are consumers.
                val allIncluded = sln?.projects?.map { it.key }?.toSet().orEmpty()
                val allPaths = enginePaths(ScanPlan.projectPaths(sln, allIncluded, File(basePath()), options.recursive, options.includeFileBasedApps), trusted)
                plan = engine.call(
                    workDir(), "planUpgrade",
                    PlanParams(workDir(), allPaths, rows, EngineOptions.from(options, checkUpdates = true)),
                    UpgradePlan::class.java, timeoutMs(options), indicator,
                )
            }

            override fun onSuccess() = onEdt {
                busy = false
                toolbar.updateActionsAsync()
                applyPlan(plan, restoreOnly, restoreOnlyRowCount, options, restoreOff)
            }

            override fun onCancel() = cancelled()

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportEngineError("Upgrade planning failed", error)
                toolbar.updateActionsAsync()
            }
        })
    }

    /** EDT: confirm, edit the files as one undo step, restore, re-scan. */
    private fun applyPlan(plan: UpgradePlan, restoreOnly: List<String>, restoreOnlyRowCount: Int, options: OutdatedOptions, restoreOff: Boolean) {
        val skipped = plan.skipped.map { ScanFailure("${it.id} (${File(it.project).nameWithoutExtension})", it.reason, "") }
        if (plan.edits.isEmpty() && restoreOnly.isEmpty()) {
            if (skipped.isEmpty()) setStatus("Nothing to upgrade.") else notifyFailure("Nothing was upgraded", skipped)
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            UpgradeSummary.text(plan, restoreOnlyRowCount),
            "Upgrade Packages",
            "Upgrade",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) {
            setStatus("Upgrade cancelled.")
            return
        }

        val applied = EditApplier.apply(project, plan.edits, "Upgrade NuGet packages")
        if (applied is EditApplier.Result.Failed) {
            notifyFailure("Upgrade not applied", listOf(ScanFailure("Upgrade", applied.reason, "")))
            return
        }
        if (skipped.isNotEmpty()) notifyFailure("Some packages were skipped", skipped, NotificationType.INFORMATION, updateStatus = false)

        val toRestore = (plan.restoreProjects + restoreOnly).distinct()
        if (restoreOff || toRestore.isEmpty()) {
            runScan()
            return
        }
        busy = true
        toolbar.updateActionsAsync()
        setStatus("Restoring ${toRestore.size} project(s)…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "${PluginText.NAME}: restoring after the upgrade", true) {
            private var failures: List<ScanFailure> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                failures = restoreBesideRider(plan.restoreProjects, restoreOnly, options, indicator, afterUpgrade = true, graceMs = 5_000)
            }

            override fun onSuccess() = onEdt {
                busy = false
                if (failures.isNotEmpty()) notifyFailure("Restore failed after the upgrade", failures)
                else setStatus("Upgrade complete. Re-checking…")
                runScan()
            }

            // The edits are already applied - a cancelled restore still leaves the list showing
            // stale versions, so re-scan rather than just resetting to idle.
            override fun onCancel() = onEdt {
                busy = false
                toolbar.updateActionsAsync()
                runScan()
            }

            override fun onThrowable(error: Throwable) = onEdt {
                busy = false
                reportInternalError("Restore failed", error)
                toolbar.updateActionsAsync()
            }
        })
    }

    private fun cancelled() = onEdt {
        busy = false
        setStatus("Cancelled.")
        toolbar.updateActionsAsync()
        relistIfScopeChanged()
    }

    /** Builds a [ScanFailure]: short summary for the UI, raw output kept for "Copy Details". */
    private fun failureOf(label: String, stderr: String, stdout: String, summary: String): ScanFailure {
        val raw = listOf(stderr, stdout).filter { it.isNotBlank() }.joinToString("\n").trim()
        return ScanFailure(label, summary, raw)
    }

    private fun timeoutMs(options: OutdatedOptions): Long = options.idleTimeoutSeconds.coerceAtLeast(MIN_TIMEOUT_SECONDS) * 1000L

    private fun restoreTimeoutMs(options: OutdatedOptions): Long = maxOf(5 * 60 * 1000L, (options.idleTimeoutSeconds + 60) * 1000L)

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block)

    // --- Toolbar actions ---------------------------------------------------

    private inner class ReloadPackagesAction : AnAction(
        "Reload Packages",
        "List all current packages (enable \"List all packages\" in settings)",
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            // Listing everything is the heavy capability; only available when the toggle is on.
            e.presentation.isEnabled = !busy && optionsService.options.includeUpToDate
        }
        override fun actionPerformed(e: AnActionEvent) = runListPackages()
    }

    private inner class CheckForUpdatesAction : AnAction(
        "Check for Updates",
        "Check the package sources for newer versions",
        AllIcons.Vcs.Fetch,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !busy
        }
        override fun actionPerformed(e: AnActionEvent) = runScan()
    }

    private inner class ScopeAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.text = scopeLabel()
            e.presentation.icon = AllIcons.General.Filter
            e.presentation.isEnabled = !busy && (solution?.projects?.isNotEmpty() == true)
        }
        override fun actionPerformed(e: AnActionEvent) {
            showScopePicker(e.inputEvent?.component as? JComponent ?: toolbar.component)
        }
    }

    private inner class SelectAllAction : AnAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val allChecked = listView.allOutdatedChecked()
            e.presentation.text = if (allChecked) "Deselect All" else "Select All"
            e.presentation.description = "Toggle the checkbox on every outdated package"
            e.presentation.icon = if (allChecked) AllIcons.Actions.Unselectall else AllIcons.Actions.Selectall
            e.presentation.isEnabled = !busy && listView.hasOutdated()
        }
        override fun actionPerformed(e: AnActionEvent) = listView.toggleSelectAll()
    }

    private inner class UpdateAction : AnAction("Update Selected", "Upgrade the checked packages", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !busy && listView.hasChecked()
        }
        override fun actionPerformed(e: AnActionEvent) = runUpgrade()
    }

    /** The eye menu: how the list is grouped. */
    private inner class ViewGroup : DefaultActionGroup("View", true) {
        init {
            templatePresentation.icon = AllIcons.Actions.Show
            add(ViewModeAction(byPackage = false, "Group by Project"))
            add(ViewModeAction(byPackage = true, "Group by Package"))
            addSeparator()
            add(ShowRecommendationsAction())
            add(DisplayAction("Show Target Framework", { it.showFramework }) { o, v -> o.showFramework = v })
            add(DisplayAction("Show Version Variable", { it.showVariable }) { o, v -> o.showVariable = v })
        }
    }

    private fun display() = optionsService.options.let { ListDisplay(it.showFramework, it.showVariable) }

    /** An eye-menu option that only changes what the rows show: a repaint, checks stay. */
    private inner class DisplayAction(
        text: String,
        private val get: (OutdatedOptions) -> Boolean,
        private val set: (OutdatedOptions, Boolean) -> Unit,
    ) : ToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = get(optionsService.options)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            set(optionsService.options, state)
            listView.display = display()
        }
    }

    private inner class ShowRecommendationsAction : ToggleAction("Show Recommendations") {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = optionsService.options.showRecommendations
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            optionsService.options.showRecommendations = state
            if (state) recommendationsClosed = false
            updateRecommendations()
        }
    }

    private inner class ViewModeAction(private val byPackage: Boolean, text: String) : ToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = optionsService.options.groupByPackage == byPackage
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return // a click on the selected mode
            optionsService.options.groupByPackage = byPackage
            render() // rebuilds the list, so checks are cleared
            toolbar.updateActionsAsync()
        }
    }

    private inner class OptionsAction : AnAction("Settings", "Open ${PluginText.NAME} settings", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, OutdatedConfigurable::class.java)
        }
    }

    companion object {
        /** Id of Rider's own NuGet tool window. */
        private const val RIDER_NUGET_TOOL_WINDOW = "NuGet"
        /** The banner links at most this many packages. */
        private const val MAX_RECOMMENDATIONS = 10
        private val LOG = logger<OutdatedPanel>()
        private const val MIN_TIMEOUT_SECONDS = 30
        /** Must match the <notificationGroup id="…"> in plugin.xml. */
        private const val NOTIFICATION_GROUP = PluginText.NAME
        /** Balloons stay readable; the rest is in "Copy Details" and idea.log. */
        private const val MAX_SHOWN_FAILURES = 3
    }

    /**
     * A unit that couldn't be scanned: [summary] is the short, actionable line shown to the user,
     * [raw] the untouched output kept for "Copy Details" / idea.log.
     */
    private data class ScanFailure(val label: String, val summary: String, val raw: String) {
        val line: String get() = "$label: $summary"
        val details: String get() = if (raw.isBlank()) line else "$line\n$raw"
    }
}

/**
 * Pure: the (label, reason) pair to show for each leftover stale project - one whose file name a
 * restore failure already names (`restoredLabels`, e.g. `App.csproj` or a whole-solution `App.sln`)
 * is dropped, so it isn't listed twice. Matched on the full file name, not the bare project name:
 * a solution restore failure names the `.sln`, which must not accidentally match a same-named
 * project's `.csproj`.
 */
internal fun staleSkipReasons(
    stale: List<String>,
    restoredLabels: Set<String>,
    noRestore: Boolean,
    restoreFailed: Boolean,
    trusted: Boolean = true,
): List<Pair<String, String>> {
    val reason = when {
        !trusted -> "project not trusted - restore is off"
        noRestore -> "not restored (restore is off in settings)"
        restoreFailed -> "restore failed - see Copy Details"
        else -> "restore did not bring its packages up to date"
    }
    return stale
        .filterNot { File(it).name in restoredLabels }
        .map { File(it).nameWithoutExtension to reason }
}

/**
 * Pure: the distinct projects to restore for the checked floating (restore-only) rows, and how many
 * rows that is (for [UpgradeSummary]) - both empty when restore is off, since restore is the only
 * way a floating row ever gets its new version.
 */
internal fun restoreOnlyPlan(checked: List<CheckedRow>, noRestore: Boolean): Pair<List<String>, Int> {
    if (noRestore) return emptyList<String>() to 0
    val rows = checked.filter { it.restoreOnly }
    return rows.map { it.project }.distinct() to rows.size
}

/**
 * Pure: the scope after the solution is re-read, by [SolutionProject.key]. A project the user left out
 * stays out, a project added since [oldKeys] comes in, a removed one goes. An empty result means all,
 * as in the scope picker.
 */
internal fun mergeScope(oldIncluded: Set<String>, oldKeys: List<String>, newKeys: List<String>): MutableSet<String> {
    val merged = newKeys.filterTo(linkedSetOf()) { it in oldIncluded || it !in oldKeys }
    return if (merged.isEmpty()) newKeys.toMutableSet() else merged
}

/**
 * Pure: the scope the picker saves. [picked] came from the popup's [shownKeys] (empty means all); a
 * project added since the popup opened ([currentKeys]) joins, as in [mergeScope].
 */
internal fun pickedScope(picked: Set<String>, shownKeys: List<String>, currentKeys: List<String>): MutableSet<String> =
    mergeScope(picked.ifEmpty { shownKeys.toSet() }, shownKeys, currentKeys)

/** Pure: restore runs the repository's MSBuild targets, so only a trusted project with restore on gets it. */
internal fun restoreAllowed(trusted: Boolean, noRestore: Boolean): Boolean = trusted && !noRestore

/** Pure: an untrusted project sends no `.cs` file-based apps (evaluating one runs `dotnet build`). */
internal fun enginePaths(paths: List<String>, trusted: Boolean): List<String> =
    if (trusted) paths else paths.filterNot { it.endsWith(".cs", ignoreCase = true) }

/** Pure: the helper's stack trace (kind `bug`) or stderr tail (crash), as report text. */
internal fun internalErrorDetails(throwable: Throwable): Array<String> =
    listOfNotNull((throwable as? HelperException)?.details?.takeIf { it.isNotBlank() }).toTypedArray()
