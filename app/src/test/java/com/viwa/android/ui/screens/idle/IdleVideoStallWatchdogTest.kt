package com.viwa.android.ui.screens.idle

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleVideoStallWatchdogTest {
    private fun readySnapshot(
        positionMs: Long = 1_000L,
        isPlaying: Boolean = true,
    ) = IdleVideoStallWatchdog.Snapshot(
        positionMs = positionMs,
        playbackState = Player.STATE_READY,
        isPlaying = isPlaying,
        playWhenReady = true,
    )

    private fun playingContext(
        nowMs: Long = 0L,
        lifecyclePaused: Boolean = false,
        crossfading: Boolean = false,
    ) = IdleVideoStallWatchdog.WatchContext(
        lifecyclePaused = lifecyclePaused,
        crossfading = crossfading,
        nowMs = nowMs,
    )

    @Test
    fun `should monitor only when ready playWhenReady and not paused or crossfading`() {
        val snapshot = readySnapshot()
        assertTrue(IdleVideoStallWatchdog.shouldMonitor(snapshot, playingContext()))
        assertFalse(
            IdleVideoStallWatchdog.shouldMonitor(
                snapshot,
                playingContext(lifecyclePaused = true),
            ),
        )
        assertFalse(
            IdleVideoStallWatchdog.shouldMonitor(
                snapshot,
                playingContext(crossfading = true),
            ),
        )
        assertFalse(
            IdleVideoStallWatchdog.shouldMonitor(
                snapshot.copy(playWhenReady = false),
                playingContext(),
            ),
        )
        assertFalse(
            IdleVideoStallWatchdog.shouldMonitor(
                snapshot.copy(playbackState = Player.STATE_BUFFERING),
                playingContext(),
            ),
        )
    }

    @Test
    fun `position advanced detects forward progress and loop restart`() {
        assertTrue(IdleVideoStallWatchdog.positionAdvanced(1_000L, 1_200L))
        assertFalse(IdleVideoStallWatchdog.positionAdvanced(1_000L, 1_020L))
        assertTrue(IdleVideoStallWatchdog.positionAdvanced(9_500L, 100L))
    }

    @Test
    fun `first tick seeds progress baseline without recovery`() {
        val result =
            IdleVideoStallWatchdog.tick(
                state = IdleVideoStallWatchdog.WatchState(),
                snapshot = readySnapshot(positionMs = 500L),
                context = playingContext(nowMs = 1_000L),
                activeAssetIndex = 2,
            )

        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, result.action)
        assertNull(result.diagnostic)
        assertEquals(500L, result.state.lastProgressPositionMs)
        assertEquals(1_000L, result.state.lastProgressAtMs)
        assertEquals(2, result.state.monitoringAssetIndex)
    }

    @Test
    fun `progress resets stall timer and recovery attempts`() {
        var state =
            IdleVideoStallWatchdog.WatchState(
                lastProgressPositionMs = 500L,
                lastProgressAtMs = 0L,
                recoveryAttempts = 2,
                monitoringAssetIndex = 0,
            )

        val result =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 2_000L),
                context = playingContext(nowMs = 5_000L),
                activeAssetIndex = 0,
            )

        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, result.action)
        assertEquals(0, result.state.recoveryAttempts)
        assertEquals(2_000L, result.state.lastProgressPositionMs)
    }

    @Test
    fun `intentional pause clears progress tracking`() {
        val state =
            IdleVideoStallWatchdog.WatchState(
                lastProgressPositionMs = 500L,
                lastProgressAtMs = 0L,
                recoveryAttempts = 1,
                monitoringAssetIndex = 0,
            )

        val result =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(),
                context = playingContext(nowMs = 20_000L, lifecyclePaused = true),
                activeAssetIndex = 0,
            )

        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, result.action)
        assertNull(result.state.lastProgressPositionMs)
        assertNull(result.state.lastProgressAtMs)
    }

    @Test
    fun `stall before threshold does not recover`() {
        val state =
            IdleVideoStallWatchdog.WatchState(
                lastProgressPositionMs = 1_000L,
                lastProgressAtMs = 0L,
                monitoringAssetIndex = 0,
            )

        val result =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 1_000L),
                context = playingContext(nowMs = 10_000L),
                activeAssetIndex = 0,
            )

        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, result.action)
    }

    @Test
    fun `stall at threshold triggers seek reprepare then rotate`() {
        var state =
            IdleVideoStallWatchdog.WatchState(
                lastProgressPositionMs = 1_000L,
                lastProgressAtMs = 0L,
                monitoringAssetIndex = 3,
            )

        val first =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 1_000L, isPlaying = false),
                context = playingContext(nowMs = 16_000L),
                activeAssetIndex = 3,
            )
        assertEquals(IdleVideoStallWatchdog.RecoveryAction.SeekReprepare, first.action)
        assertTrue(first.diagnostic!!.contains("assetIndex=3"))
        assertTrue(first.diagnostic!!.contains("action=seekReprepare"))
        state = first.state

        val duringBackoff =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 1_000L),
                context = playingContext(nowMs = 17_000L),
                activeAssetIndex = 3,
            )
        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, duringBackoff.action)

        val second =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 1_000L),
                context = playingContext(nowMs = 32_000L),
                activeAssetIndex = 3,
            )
        assertEquals(IdleVideoStallWatchdog.RecoveryAction.SeekReprepare, second.action)
        state = second.state

        val third =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 1_000L),
                context = playingContext(nowMs = 48_000L),
                activeAssetIndex = 3,
            )
        assertEquals(IdleVideoStallWatchdog.RecoveryAction.RotateAsset, third.action)
        assertTrue(third.diagnostic!!.contains("action=rotate"))
        assertEquals(0, third.state.recoveryAttempts)
    }

    @Test
    fun `recovery backoff grows with attempts`() {
        assertEquals(2_000L, IdleVideoStallWatchdog.recoveryBackoffMs(0))
        assertEquals(4_000L, IdleVideoStallWatchdog.recoveryBackoffMs(1))
        assertEquals(8_000L, IdleVideoStallWatchdog.recoveryBackoffMs(2))
        assertEquals(30_000L, IdleVideoStallWatchdog.recoveryBackoffMs(10))
    }

    @Test
    fun `asset index change reseeds baseline`() {
        val state =
            IdleVideoStallWatchdog.WatchState(
                lastProgressPositionMs = 1_000L,
                lastProgressAtMs = 0L,
                recoveryAttempts = 2,
                monitoringAssetIndex = 0,
            )

        val result =
            IdleVideoStallWatchdog.tick(
                state = state,
                snapshot = readySnapshot(positionMs = 200L),
                context = playingContext(nowMs = 20_000L),
                activeAssetIndex = 1,
            )

        assertEquals(IdleVideoStallWatchdog.RecoveryAction.None, result.action)
        assertEquals(1, result.state.monitoringAssetIndex)
        assertEquals(0, result.state.recoveryAttempts)
        assertEquals(200L, result.state.lastProgressPositionMs)
    }
}
