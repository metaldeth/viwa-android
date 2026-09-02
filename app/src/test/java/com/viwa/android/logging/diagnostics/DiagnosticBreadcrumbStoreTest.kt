package com.viwa.android.logging.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticBreadcrumbStoreTest {
    private lateinit var context: Context
    private lateinit var ioScope: CoroutineScope
    private lateinit var store: DiagnosticBreadcrumbStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = File(context.filesDir, "diagnostics")
        dir.deleteRecursively()
        ioScope = CoroutineScope(SupervisorJob())
        store = DiagnosticBreadcrumbStore(context, ioScope)
    }

    @Test
    fun roundTrip_persistsSnapshotAtomically() {
        store.startBootSession("boot-1")
        store.recordControllerOp(7L, "written", "0xd0", 12L)
        store.recordPour("pour-1", "begin")
        store.appendTrail("event-a")
        assertTrue(store.flushNowSync())

        val loaded = store.readPreviousSnapshot()
        assertNotNull(loaded)
        assertEquals("boot-1", loaded!!.bootSessionId)
        assertEquals(7L, loaded.lastControllerOpId)
        assertEquals("0xd0", loaded.lastControllerCmd)
        assertEquals("pour-1", loaded.pourId)
        assertTrue(loaded.trail.isNotEmpty())
        assertTrue(File(context.filesDir, "diagnostics/last-state.json").exists())
    }

    @Test
    fun trail_isBounded() {
        store.startBootSession("boot-trail")
        repeat(40) { index ->
            store.appendTrail("e$index")
        }
        assertTrue(store.flushNowSync())

        val loaded = store.readPreviousSnapshot()
        assertNotNull(loaded)
        assertTrue(loaded!!.trail.size <= DiagnosticBreadcrumbSnapshot.TRAIL_LIMIT)
    }

    @Test
    fun immediateFlush_winsOverStaleDebouncedWrite() =
        runBlocking {
            store.startBootSession("boot-race")
            store.recordControllerOp(1L, "begin", "0x01")
            ioScope.launch {
                delay(900)
                store.recordControllerOp(99L, "written", "0x99", 1L)
            }
            delay(100)
            store.recordCrashAndFlushSync("TestError", "stale must not win")
            delay(1_200)

            store.flushNowSync()
            val loaded = store.readPreviousSnapshot()
            assertNotNull(loaded)
            assertEquals("TestError", loaded!!.lastCrashType)
            assertEquals("TestError", loaded.lastCrashType)
        }

    @Test
    fun crashSnapshot_isNotOverwrittenByOlderPendingWrite() =
        runBlocking {
            store.startBootSession("boot-crash")
            store.recordPour("pour-a", "begin")
            ioScope.launch {
                delay(1_000)
                store.recordControllerOp(50L, "written", "0x50", 3L)
            }
            delay(50)
            assertTrue(store.recordCrashAndFlushSync("RuntimeException", "Bearer abcdefghijklmnop token"))
            delay(1_200)
            store.flushNowSync()

            val loaded = store.readPreviousSnapshot()
            assertNotNull(loaded)
            assertEquals("RuntimeException", loaded!!.lastCrashType)
            assertFalse(loaded.lastCrashMessage.orEmpty().contains("Bearer"))
            assertFalse(loaded.lastCrashMessage.orEmpty().contains("abcdefghijklmnop"))
        }
}
