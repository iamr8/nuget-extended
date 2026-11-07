package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.PluginText
import com.github.iamr8.nugetextended.cli.DotnetLocator
import com.google.gson.JsonObject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * One helper process per open solution. Starts on first use, restarts after a crash (once per
 * call) or when [restartLater] was called, stops with the project.
 */
@Service(Service.Level.PROJECT)
class HelperService(@Suppress("unused") private val project: Project) : Disposable {

    private val lock = Any()
    private var process: HelperProcess? = null
    private var processDir: String? = null
    @Volatile private var restartPending = false
    @Volatile private var disposed = false
    private val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A file the engine may have cached changed. Sent before the next request, in order. */
    fun markChanged(path: String) {
        changed.add(path)
    }

    /** global.json or NuGet.config changed: the next call starts a fresh helper. */
    fun restartLater() {
        restartPending = true
    }

    /**
     * Starts the helper if needed (up to 60 s), sends [method], and blocks for the response.
     * Must run off the EDT.
     */
    fun <T> call(
        workDir: String,
        method: String,
        params: Any,
        type: Class<T>,
        idleTimeoutMs: Long,
        indicator: ProgressIndicator?,
    ): T {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val restartOnce = RestartOnce()
        while (true) {
            val proc = ensure(workDir)
            try {
                flushChanges(proc, idleTimeoutMs)
                return proc.client.request(
                    method, params, type, idleTimeoutMs,
                    isCancelled = { indicator?.isCanceled == true },
                    onProgress = { text -> indicator?.text2 = text },
                )
            } catch (_: HelperCancelledException) {
                // After the cancel grace the client marks itself closed (stuck helper): stop it now,
                // as the timeout path does. Not when interrupted: stop() waits on the process and
                // would throw InterruptedException instead of the cancel.
                if (!proc.isAlive && !Thread.currentThread().isInterrupted) stop()
                throw ProcessCanceledException()
            } catch (e: HelperTimeoutException) {
                // The client may have marked itself closed after the cancel grace expired; don't
                // make the next call wait for that to surface.
                if (!proc.isAlive) stop()
                throw e
            } catch (e: HelperCrashedException) {
                stop()
                if (!restartOnce.allow(disposed)) throw e
                LOG.warn("${PluginText.NAME}: engine stopped, starting it again\n${e.details.orEmpty()}")
            }
        }
    }

    private fun flushChanges(proc: HelperProcess, idleTimeoutMs: Long) {
        if (changed.isEmpty()) return
        val paths = changed.toList()
        changed.removeAll(paths.toSet())
        requeueOnFailure(changed, paths) {
            proc.client.request("invalidate", mapOf("paths" to paths), JsonObject::class.java, idleTimeoutMs)
        }
    }

    private fun ensure(workDir: String): HelperProcess = synchronized(lock) {
        if (disposed) throw ProcessCanceledException()
        val current = process
        if (current != null && current.isAlive && !restartPending && processDir == workDir) return current
        current?.close()
        process = null
        restartPending = false
        changed.clear() // a new helper has no cache
        val dll = helperDll()
            ?: throw HelperFatalException("The engine files are missing from the plugin. Reinstall the plugin.")
        HelperProcess.start(DotnetLocator.resolve(), dll, workDir) { LOG.info("engine: $it") }.also {
            LOG.info("${PluginText.NAME}: engine ${it.hello.helperVersion} on SDK ${it.hello.sdkVersion}, runtime ${it.hello.runtime}")
            process = it
            processDir = workDir
        }
    }

    /** Closes the running helper, waiting up to 5 s for it to exit. Must run off the EDT. */
    fun stop() = synchronized(lock) {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        process?.close()
        process = null
    }

    // dispose() can run on the EDT at project close, so the close (which waits up to 5 s) goes
    // to a pooled thread instead of blocking it. disposed is set before that hand-off, so a
    // call() already inside ensure() either finishes starting its helper before the pooled
    // stop() takes the lock (and then gets stopped by it), or sees disposed and never starts one.
    override fun dispose() {
        disposed = true
        ApplicationManager.getApplication().executeOnPooledThread { stop() }
    }

    companion object {
        private val LOG = logger<HelperService>()
        private const val PLUGIN_ID = "com.github.iamr8.dotnetoutdated"

        fun getInstance(project: Project): HelperService = project.service()

        // Not PluginId.getId(...): built against a newer SDK, Kotlin compiles it to PluginId.Companion,
        // which Rider 2024.3 and 2025.1 do not have (NoSuchFieldError; the Plugin Verifier reports it).
        private fun helperDll(): Path? =
            PluginManagerCore.plugins.firstOrNull { it.pluginId.idString == PLUGIN_ID }?.pluginPath
                ?.resolve("helper")?.resolve("Helper.dll")
                ?.takeIf { Files.isRegularFile(it) }

        /**
         * Runs [action]; on any failure, adds [paths] back into [changed] before rethrowing, so a
         * failed `invalidate` (one `call` does not restart the helper for: timeout, user, bug)
         * does not lose the cache invalidation. A crash also requeues here, harmlessly - the
         * restart that follows clears [changed] anyway.
         */
        internal fun <T> requeueOnFailure(changed: MutableSet<String>, paths: List<String>, action: () -> T): T =
            try {
                action()
            } catch (e: Exception) {
                changed.addAll(paths)
                throw e
            }
    }
}

/** One per [HelperService.call]: a crash is retried once, and never after the service is disposed. */
internal class RestartOnce {
    private var restarted = false

    fun allow(disposed: Boolean): Boolean {
        if (restarted || disposed) return false
        restarted = true
        return true
    }
}
