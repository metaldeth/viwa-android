package com.viwa.android.logging.diagnostics

import android.content.Context
import android.os.SystemClock
import com.viwa.android.di.AppIoScope
import com.viwa.android.logging.ScreenStateLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Durable on-device breadcrumb for post-mortem field diagnostics.
 * In-memory updates are cheap; disk writes are serialized and generation-guarded.
 */
@Singleton
class DiagnosticBreadcrumbStore
@Inject
constructor(
    @ApplicationContext context: Context,
    @AppIoScope private val ioScope: CoroutineScope,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val stateFile = File(dir, "last-state.json")
    private val stateLock = ReentrantLock()
    private val writerLock = ReentrantLock()

    private var latestSnapshot: DiagnosticBreadcrumbSnapshot? = null
    private var snapshotGeneration = 0L
    private var lastPersistedGeneration = 0L
    private var lastHeartbeatPersistUptimeMs = 0L
    private var debounceJob: Job? = null
    private var bootSessionId: String = ""
    private var protectedCrashType: String? = null
    private var protectedCrashMessage: String? = null

    @Volatile
    var configChangeInProgress: Boolean = false
        private set

    fun readPreviousSnapshot(): DiagnosticBreadcrumbSnapshot? =
        stateLock.withLock {
            latestSnapshot ?: readSnapshotFromDiskUnsafe()
        }

    fun currentBootSessionId(): String =
        stateLock.withLock {
            bootSessionId
        }

    fun startBootSession(sessionId: String) {
        stateLock.withLock {
            bootSessionId = sessionId
            val now = Instant.now().toString()
            val base = latestSnapshot ?: readSnapshotFromDiskUnsafe()
            latestSnapshot =
                base?.copy(
                    bootSessionId = sessionId,
                    updatedAt = now,
                    uptimeMs = SystemClock.elapsedRealtime(),
                    screenRoute = ScreenStateLogger.route,
                    screenSnapshot = ScreenStateLogger.snapshot(),
                    pourId = null,
                    pourPhase = null,
                    mainThreadStalled = false,
                )
                    ?: DiagnosticBreadcrumbSnapshot(
                        bootSessionId = sessionId,
                        updatedAt = now,
                        uptimeMs = SystemClock.elapsedRealtime(),
                        screenRoute = ScreenStateLogger.route,
                        screenSnapshot = ScreenStateLogger.snapshot(),
                    )
            snapshotGeneration++
        }
        protectedCrashType = null
        protectedCrashMessage = null
        requestPersist(PersistPriority.HIGH)
    }

    fun appendTrail(event: String) {
        applyMutation(trailEvent = event)
    }

    fun recordControllerOp(
        opId: Long,
        phase: String,
        cmdHex: String,
        writeMs: Long? = null,
    ) {
        applyMutation(trailEvent = "ctrl:$phase op=$opId cmd=$cmdHex") { snapshot ->
            snapshot.copy(
                lastControllerOpId = opId,
                lastControllerOpPhase = phase,
                lastControllerCmd = cmdHex,
                lastControllerWriteMs = writeMs ?: snapshot.lastControllerWriteMs,
            )
        }
    }

    fun recordPour(
        pourId: String?,
        phase: String,
    ) {
        val trail =
            if (pourId != null) {
                "pour:$phase id=$pourId"
            } else {
                null
            }
        applyMutation(trailEvent = trail) { snapshot ->
            snapshot.copy(
                pourId = pourId ?: snapshot.pourId,
                pourPhase = phase,
            )
        }
    }

    fun recordHeartbeatRoutine(
        heartbeatMs: Long,
        stalled: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val shouldPersist =
            stateLock.withLock {
                now - lastHeartbeatPersistUptimeMs >= HEARTBEAT_PERSIST_INTERVAL_MS
            }
        applyMutation(
            priority = if (shouldPersist) PersistPriority.NORMAL else PersistPriority.MEMORY_ONLY,
            trailEvent = null,
        ) { snapshot ->
            snapshot.copy(
                mainThreadHeartbeatMs = heartbeatMs,
                mainThreadStalled = stalled,
            )
        }
        if (shouldPersist) {
            stateLock.withLock {
                lastHeartbeatPersistUptimeMs = now
            }
        }
    }

    fun recordWatchdogStall(
        heartbeatMs: Long,
        ageMs: Long,
    ) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "main.watchdog.stall ageMs=$ageMs",
        ) { snapshot ->
            snapshot.copy(
                mainThreadHeartbeatMs = heartbeatMs,
                mainThreadStalled = true,
            )
        }
    }

    fun recordWatchdogRecovered(
        heartbeatMs: Long,
        ageMs: Long,
    ) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "main.watchdog.recovered ageMs=$ageMs",
        ) { snapshot ->
            snapshot.copy(
                mainThreadHeartbeatMs = heartbeatMs,
                mainThreadStalled = false,
            )
        }
    }

    fun recordWatchdogBackgroundDuringStall(heartbeatMs: Long) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "main.watchdog.backgroundDuringStall",
        ) { snapshot ->
            snapshot.copy(
                mainThreadHeartbeatMs = heartbeatMs,
                mainThreadStalled = false,
            )
        }
    }

    fun recordCrashAndFlushSync(
        errorType: String,
        message: String,
    ): Boolean {
        val sanitized = DiagnosticsSanitizer.sanitizeCrashMessage(message)
        stateLock.withLock {
            protectedCrashType = errorType
            protectedCrashMessage = sanitized
            applyMutationLocked(
                trailEvent = "crash:$errorType",
                mutator = { snapshot ->
                    snapshot.copy(
                        lastCrashType = errorType,
                        lastCrashMessage = sanitized,
                    )
                },
            )
        }
        debounceJob?.cancel()
        debounceJob = null
        return flushNowSync(CRASH_FLUSH_TIMEOUT_MS)
    }

    fun recordActivityLifecycle(
        event: String,
        hasWindowFocus: Boolean,
        isFinishing: Boolean,
        isChangingConfigurations: Boolean,
        activityClass: String,
    ) {
        if (isChangingConfigurations && event in CONFIG_CHANGE_EVENTS) {
            configChangeInProgress = true
        } else if (event == "onStart" || event == "onResume") {
            configChangeInProgress = false
        }
        val priority =
            if (event in HIGH_PRIORITY_LIFECYCLE_EVENTS) {
                PersistPriority.HIGH
            } else {
                PersistPriority.NORMAL
            }
        applyMutation(
            priority = priority,
            trailEvent = "activity:$event focus=$hasWindowFocus class=$activityClass cfg=$isChangingConfigurations",
        ) { snapshot ->
            snapshot.copy(
                activityLifecycleEvent = event,
                activityClass = activityClass,
                activityHasWindowFocus = hasWindowFocus,
                activityIsFinishing = isFinishing,
            )
        }
    }

    fun recordForegroundTransition(
        count: Int,
        event: String,
        activityClass: String,
    ) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "foreground:$event count=$count class=$activityClass",
        ) { snapshot ->
            snapshot.copy(
                activityForegroundCount = count,
                activityLifecycleEvent = "foreground.$event",
                activityClass = activityClass,
            )
        }
    }

    fun recordOutgoingLaunch(summary: String) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "launch:$summary",
        ) { snapshot ->
            snapshot.copy(lastOutgoingLaunch = summary.take(OUTGOING_LAUNCH_LIMIT))
        }
    }

    fun recordForegroundDestination(
        packageName: String,
        className: String,
        observedAtMs: Long,
        probeStatus: String,
    ) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "foreground:$probeStatus pkg=$packageName class=$className",
        ) { snapshot ->
            snapshot.copy(
                lastForegroundPackage = packageName.take(FOREGROUND_PACKAGE_LIMIT),
                lastForegroundClass = className.take(FOREGROUND_CLASS_LIMIT),
                lastForegroundObservedAt = observedAtMs,
                foregroundProbeStatus = probeStatus,
            )
        }
    }

    fun recordForegroundProbeStatus(status: String) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "foreground:probe_$status",
        ) { snapshot ->
            snapshot.copy(foregroundProbeStatus = status.take(FOREGROUND_PROBE_STATUS_LIMIT))
        }
    }

    fun markExitReported(timestamp: Long) {
        applyMutation(
            priority = PersistPriority.HIGH,
            trailEvent = "startup.exit_reported ts=$timestamp",
        ) { snapshot ->
            snapshot.copy(lastReportedExitTimestamp = timestamp)
        }
    }

    /** Test / crash path: best-effort synchronous persist without coroutine runBlocking. */
    fun flushNowSync(timeoutMs: Long = CRASH_FLUSH_TIMEOUT_MS): Boolean {
        debounceJob?.cancel()
        debounceJob = null
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNs) {
            if (writerLock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    return persistLatestLocked(force = true)
                } finally {
                    writerLock.unlock()
                }
            }
        }
        return false
    }

    internal fun awaitPersistForTests(timeoutMs: Long = 2_000L): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNs) {
            val generation =
                stateLock.withLock {
                    snapshotGeneration
                }
            if (generation <= lastPersistedGeneration && generation > 0L) {
                return true
            }
            flushNowSync(100L)
            Thread.sleep(20)
        }
        return stateLock.withLock { snapshotGeneration <= lastPersistedGeneration }
    }

    private enum class PersistPriority {
        MEMORY_ONLY,
        NORMAL,
        HIGH,
    }

    private fun applyMutation(
        priority: PersistPriority = PersistPriority.NORMAL,
        trailEvent: String? = null,
        mutator: (DiagnosticBreadcrumbSnapshot) -> DiagnosticBreadcrumbSnapshot = { it },
    ) {
        stateLock.withLock {
            applyMutationLocked(trailEvent, mutator)
        }
        if (priority != PersistPriority.MEMORY_ONLY) {
            requestPersist(priority)
        }
    }

    private fun applyMutationLocked(
        trailEvent: String?,
        mutator: (DiagnosticBreadcrumbSnapshot) -> DiagnosticBreadcrumbSnapshot,
    ) {
        val current = latestSnapshot ?: readSnapshotFromDiskUnsafe() ?: DiagnosticBreadcrumbSnapshot()
        var next =
            mutator(current).copy(
                bootSessionId = bootSessionId.ifBlank { current.bootSessionId },
                updatedAt = Instant.now().toString(),
                uptimeMs = SystemClock.elapsedRealtime(),
                screenRoute = ScreenStateLogger.route,
                screenSnapshot = ScreenStateLogger.snapshot(),
            )
        if (trailEvent != null) {
            next = next.copy(trail = appendTrail(next.trail, trailEvent))
        }
        latestSnapshot = next
        snapshotGeneration++
    }

    private fun appendTrail(
        trail: List<String>,
        event: String,
    ): List<String> {
        val stamped = "${SystemClock.elapsedRealtime() % 100_000}:$event"
        return (trail + stamped).takeLast(DiagnosticBreadcrumbSnapshot.TRAIL_LIMIT)
    }

    private fun requestPersist(priority: PersistPriority) {
        when (priority) {
            PersistPriority.MEMORY_ONLY -> Unit
            PersistPriority.HIGH -> {
                debounceJob?.cancel()
                debounceJob =
                    ioScope.launch {
                        persistLatest(force = true)
                    }
            }
            PersistPriority.NORMAL -> {
                debounceJob?.cancel()
                debounceJob =
                    ioScope.launch {
                        delay(DEBOUNCE_MS)
                        persistLatest(force = false)
                    }
            }
        }
    }

    private suspend fun persistLatest(force: Boolean) {
        if (!writerLock.tryLock(5, TimeUnit.SECONDS)) return
        try {
            persistLatestLocked(force)
        } finally {
            writerLock.unlock()
        }
    }

    private fun persistLatestLocked(force: Boolean): Boolean {
        val (snapshot, generation) =
            stateLock.withLock {
                latestSnapshot to snapshotGeneration
            }
        if (snapshot == null) return false
        if (!force && generation <= lastPersistedGeneration) return false
        val payloadSnapshot = mergeProtectedCrash(snapshot)
        if (writeSnapshotToDisk(payloadSnapshot, generation)) {
            lastPersistedGeneration = generation
            return true
        }
        return false
    }

    private fun mergeProtectedCrash(snapshot: DiagnosticBreadcrumbSnapshot): DiagnosticBreadcrumbSnapshot {
        val crashType = protectedCrashType
        val crashMessage = protectedCrashMessage
        if (crashType != null && snapshot.lastCrashType == null) {
            return snapshot.copy(
                lastCrashType = crashType,
                lastCrashMessage = crashMessage,
            )
        }
        return snapshot
    }

    private fun writeSnapshotToDisk(
        snapshot: DiagnosticBreadcrumbSnapshot,
        generation: Long,
    ): Boolean =
        runCatching {
            if (generation <= lastPersistedGeneration) return false
            val tmp = File(dir, "last-state-$generation.tmp")
            val payload = json.encodeToString(snapshot)
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(stateFile)) {
                tmp.copyTo(stateFile, overwrite = true)
            }
            tmp.delete()
            cleanupStaleTempFiles(generation)
            true
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "diagnostics.persist.failed")
        }.getOrDefault(false)

    private fun cleanupStaleTempFiles(currentGeneration: Long) {
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("last-state-") && file.name.endsWith(".tmp")) {
                val gen = file.name.removePrefix("last-state-").removeSuffix(".tmp").toLongOrNull()
                if (gen != null && gen < currentGeneration) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun readSnapshotFromDiskUnsafe(): DiagnosticBreadcrumbSnapshot? =
        runCatching {
            if (!stateFile.exists()) return null
            val text = stateFile.readText(Charsets.UTF_8)
            if (text.isBlank()) return null
            json.decodeFromString<DiagnosticBreadcrumbSnapshot>(text)
        }.getOrNull()

    companion object {
        private const val TAG = "ViwaDiag"
        private const val DEBOUNCE_MS = 750L
        private const val HEARTBEAT_PERSIST_INTERVAL_MS = 30_000L
        private const val CRASH_FLUSH_TIMEOUT_MS = 200L
        private const val OUTGOING_LAUNCH_LIMIT = 200
        private const val FOREGROUND_PACKAGE_LIMIT = 120
        private const val FOREGROUND_CLASS_LIMIT = 160
        private const val FOREGROUND_PROBE_STATUS_LIMIT = 64
        private val CONFIG_CHANGE_EVENTS = setOf("onPause", "onStop")
        private val HIGH_PRIORITY_LIFECYCLE_EVENTS =
            setOf(
                "onStop",
                "onDestroy",
                "userLeaveHint",
                "windowFocusLost",
                "foreground.stopped",
            )
    }
}
