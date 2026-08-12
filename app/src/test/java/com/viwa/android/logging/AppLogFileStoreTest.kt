package com.viwa.android.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLogFileStoreTest {
    private lateinit var context: Context
    private lateinit var store: AppLogFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AppLogFileStore(context)
    }

    @Test
    fun `prepareShipSnapshot returns gzip and commit clears pending`() {
        // given
        store.appendLine("2026-08-12T10:00:00Z I/Test: first line")
        store.appendLine("2026-08-12T10:00:01Z W/Test: second line")

        // when
        val snapshot = store.prepareShipSnapshot()

        // then
        assertTrue(snapshot != null)
        assertTrue(snapshot!!.gzipBytes.isNotEmpty())
        assertTrue(snapshot.shippedByteCount > 0)
        assertTrue(snapshot.periodStart.contains("2026-08-12"))

        store.commitShip(snapshot.shippedByteCount)

        assertFalse(store.hasPendingContent())
    }

    @Test
    fun `new lines written during snapshot remain after commit`() {
        // given
        store.appendLine("2026-08-12T10:00:00Z I/Test: shipped")
        val snapshot = store.prepareShipSnapshot()
        assertTrue(snapshot != null)

        store.appendLine("2026-08-12T10:00:02Z I/Test: after snapshot")

        // when
        store.commitShip(snapshot!!.shippedByteCount)

        // then
        assertTrue(store.hasPendingContent())
    }
}
