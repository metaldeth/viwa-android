package com.viwa.android.ui.screens.idle

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake-player coordinator exercising watchdog decisions end-to-end without ExoPlayer. */
class IdleVideoStallCoordinatorTest {
    private class FakePlayer(
        var positionMs: Long = 0L,
        var playbackState: Int = Player.STATE_READY,
        var isPlaying: Boolean = true,
        var playWhenReady: Boolean = true,
    ) {
        var seekCalls = 0
        var prepareCalls = 0
        var stopCalls = 0
        var loadedAssetIndex: Int? = null

        fun snapshot() =
            IdleVideoStallWatchdog.Snapshot(
                positionMs = positionMs,
                playbackState = playbackState,
                isPlaying = isPlaying,
                playWhenReady = playWhenReady,
            )

        fun seekReprepare() {
            stopCalls++
            seekCalls++
            prepareCalls++
            playbackState = Player.STATE_READY
            isPlaying = true
        }

        fun loadAsset(index: Int) {
            loadedAssetIndex = index
            positionMs = 0L
            seekReprepare()
        }
    }

    private fun runTick(
        coordinator: StallRecoveryCoordinator,
        player: FakePlayer,
        nowMs: Long,
        assetIndex: Int,
        lifecyclePaused: Boolean = false,
        crossfading: Boolean = false,
    ) {
        val result =
            IdleVideoStallWatchdog.tick(
                state = coordinator.watchState,
                snapshot = player.snapshot(),
                context =
                    IdleVideoStallWatchdog.WatchContext(
                        lifecyclePaused = lifecyclePaused,
                        crossfading = crossfading,
                        nowMs = nowMs,
                    ),
                activeAssetIndex = assetIndex,
            )
        coordinator.applyTick(result, player, assetCount = 4) { nextIndex -> player.loadAsset(nextIndex) }
    }

    private class StallRecoveryCoordinator {
        var watchState = IdleVideoStallWatchdog.WatchState()

        fun applyTick(
            result: IdleVideoStallWatchdog.TickResult,
            player: FakePlayer,
            assetCount: Int,
            rotateLoader: (Int) -> Unit,
        ) {
            watchState = result.state
            when (result.action) {
                IdleVideoStallWatchdog.RecoveryAction.None -> Unit
                IdleVideoStallWatchdog.RecoveryAction.SeekReprepare -> player.seekReprepare()
                IdleVideoStallWatchdog.RecoveryAction.RotateAsset -> {
                    val current = watchState.monitoringAssetIndex
                    rotateLoader((current + 1) % assetCount)
                }
            }
        }
    }

    @Test
    fun `coordinator seek reprepare on first stall then rotate after repeated stalls`() {
        val player = FakePlayer(positionMs = 900L)
        val coordinator = StallRecoveryCoordinator()

        runTick(coordinator, player, nowMs = 0L, assetIndex = 1)
        runTick(coordinator, player, nowMs = 5_000L, assetIndex = 1)
        runTick(coordinator, player, nowMs = 16_000L, assetIndex = 1)

        assertEquals(1, player.seekCalls)
        assertEquals(1, player.prepareCalls)

        player.positionMs = 900L
        runTick(coordinator, player, nowMs = 32_000L, assetIndex = 1)
        assertEquals(2, player.seekCalls)

        player.positionMs = 900L
        runTick(coordinator, player, nowMs = 48_000L, assetIndex = 1)
        assertEquals(2, player.loadedAssetIndex)
        assertTrue(player.loadedAssetIndex == 2)
    }

    @Test
    fun `coordinator ignores stall while crossfading`() {
        val player = FakePlayer(positionMs = 500L)
        val coordinator = StallRecoveryCoordinator()

        runTick(coordinator, player, nowMs = 0L, assetIndex = 0)
        runTick(coordinator, player, nowMs = 20_000L, assetIndex = 0, crossfading = true)

        assertEquals(0, player.seekCalls)
        assertNull(coordinator.watchState.lastProgressPositionMs)
    }
}
