package com.viwa.android.ui.screens.idle

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.viwa.android.BuildConfig
import com.viwa.android.R
import com.viwa.android.ui.screens.customer.ViwaElectronAssets
import com.viwa.android.ui.screens.customer.buildCustomerBackgroundExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

private const val TAG = "IdleVideoOverlay"

private const val CROSSFADE_MS = 500
/**
 * За сколько мс до конца начинаем кроссфейд.
 * Должно быть >= времени буферизации на реальном железе (~2 с).
 */

private const val CROSSFADE_TRIGGER_MS = 1_500L
/** Максимальное время ожидания готовности следующего плеера (запасной сценарий). */

private const val READY_WAIT_TIMEOUT_MS = 3_000L

/** Если idle-плеер не вышел в READY/BUFFERING — закрыть оверлей, не держать белый экран. */
private const val FIRST_FRAME_TIMEOUT_MS = 5_000L

private inline fun idleVideoLog(block: () -> Unit) {
    if (BuildConfig.DEBUG) block()
}
/**
 * Полноэкранный скринсейвер из включённых видео [enabledVideoIds].
 *
 * Два ExoPlayer (A/B ping-pong): пока A играет, B загружает следующий ролик.
 * Кроссфейд по ExoPlayer events + одноразовый delay до границы, без 100 ms polling.
 */

@UnstableApi

@Composable

fun IdleVideoOverlay(
    enabledVideoIds: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val files = remember(enabledVideoIds) {
        enabledVideoIds.map { id -> "$id.mp4" }.ifEmpty { return@remember emptyList() }
    }
    if (files.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
        )
        return
    }
    val playerA = remember(context) { buildCustomerBackgroundExoPlayer(context, repeatMode = Player.REPEAT_MODE_OFF) }
    val playerB = remember(context) { buildCustomerBackgroundExoPlayer(context, repeatMode = Player.REPEAT_MODE_OFF) }
    val alphaA = remember { Animatable(0f) }
    val alphaB = remember { Animatable(0f) }
    var activeIsA by remember { mutableStateOf(true) }
    var lifecyclePaused by remember { mutableStateOf(false) }
    var crossfading by remember { mutableStateOf(false) }
    val counter = remember { intArrayOf(0) }
    var stallWatchState by remember { mutableStateOf(IdleVideoStallWatchdog.WatchState()) }
    DisposableEffect(playerA, playerB) {
        onDispose {
            playerA.release()
            playerB.release()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    lifecyclePaused = true
                    playerA.pause()
                    playerB.pause()
                }
                Lifecycle.Event.ON_START -> {
                    lifecyclePaused = false
                    val active = if (activeIsA) playerA else playerB
                    if (active.playbackState == Player.STATE_READY ||
                        active.playbackState == Player.STATE_BUFFERING
                    ) {
                        active.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    fun uri(idx: Int) =
        Uri.parse(
            "${ViwaElectronAssets.ASSET_URI_PREFIX}/video/${files[idx % files.size]}",
        )
    fun preload(player: ExoPlayer, idx: Int) {
        val name = files[idx % files.size]
        idleVideoLog {
            Timber.tag(TAG).d("preload %d (%s) → %s", idx % files.size, name, if (player === playerA) "A" else "B")
        }
        player.setMediaItem(MediaItem.fromUri(uri(idx)))
        player.prepare()
    }
    fun recoverStalledPlayer(
        player: ExoPlayer,
        action: IdleVideoStallWatchdog.RecoveryAction,
        activeAssetIndex: Int,
    ) {
        when (action) {
            IdleVideoStallWatchdog.RecoveryAction.None -> Unit
            IdleVideoStallWatchdog.RecoveryAction.SeekReprepare -> {
                val pos = player.currentPosition.coerceAtLeast(0L)
                player.pause()
                player.seekTo(pos)
                if (
                    player.playbackState == Player.STATE_IDLE ||
                        player.playbackState == Player.STATE_ENDED
                ) {
                    player.prepare()
                }
                player.playWhenReady = true
                player.play()
            }
            IdleVideoStallWatchdog.RecoveryAction.RotateAsset -> {
                val nextIdx = (activeAssetIndex + 1) % files.size
                idleVideoLog {
                    Timber.tag(TAG).d("stall rotate asset %d → %d", activeAssetIndex, nextIdx)
                }
                preload(player, nextIdx)
                player.seekTo(0)
                player.playWhenReady = true
                player.play()
            }
        }
    }
    suspend fun awaitPlayerState(
        player: ExoPlayer,
        targetState: Int,
    ) {
        if (player.playbackState == targetState) return
        suspendCancellableCoroutine { cont ->
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == targetState && cont.isActive) {
                            player.removeListener(this)
                            cont.resume(Unit)
                        }
                    }
                }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }
    }
    suspend fun waitForCrossfadeWindow(active: ExoPlayer, next: ExoPlayer) {
        if (active.playbackState == Player.STATE_ENDED) {
            val waitStart = System.currentTimeMillis()
            while (next.playbackState != Player.STATE_READY &&
                System.currentTimeMillis() - waitStart < READY_WAIT_TIMEOUT_MS
            ) {
                if (next.playbackState != Player.STATE_READY) {
                    awaitPlayerState(next, Player.STATE_READY)
                }
            }
            return
        }
        while (true) {
            when (active.playbackState) {
                Player.STATE_ENDED -> {
                    val waitStart = System.currentTimeMillis()
                    while (next.playbackState != Player.STATE_READY &&
                        System.currentTimeMillis() - waitStart < READY_WAIT_TIMEOUT_MS
                    ) {
                        awaitPlayerState(next, Player.STATE_READY)
                    }
                    return
                }
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    val dur = active.duration
                    val pos = active.currentPosition
                    if (dur != C.TIME_UNSET && dur > 0) {
                        if (IdleVideoCrossfadeTiming.shouldCrossfadeNow(
                                durationMs = dur,
                                positionMs = pos,
                                triggerBeforeEndMs = CROSSFADE_TRIGGER_MS,
                                nextPlayerReady = next.playbackState == Player.STATE_READY,
                            )
                        ) {
                            idleVideoLog {
                                Timber.tag(TAG).d("crossfade window: remaining=%dms", dur - pos)
                            }
                            return
                        }
                        val delayMs =
                            IdleVideoCrossfadeTiming.crossfadeDelayMs(
                                durationMs = dur,
                                positionMs = pos,
                                triggerBeforeEndMs = CROSSFADE_TRIGGER_MS,
                            )
                        if (delayMs != null && next.playbackState != Player.STATE_READY) {
                            val waitBudget = (delayMs + READY_WAIT_TIMEOUT_MS).coerceAtMost(dur - pos)
                            val waitStart = System.currentTimeMillis()
                            while (next.playbackState != Player.STATE_READY &&
                                System.currentTimeMillis() - waitStart < waitBudget
                            ) {
                                awaitPlayerState(next, Player.STATE_READY)
                            }
                        }
                        if (delayMs != null) {
                            delay(delayMs.coerceAtMost(60_000L))
                            if (next.playbackState != Player.STATE_READY) {
                                val waitStart = System.currentTimeMillis()
                                while (next.playbackState != Player.STATE_READY &&
                                    System.currentTimeMillis() - waitStart < READY_WAIT_TIMEOUT_MS
                                ) {
                                    awaitPlayerState(next, Player.STATE_READY)
                                }
                            }
                            return
                        }
                    }
                    awaitPlayerState(active, Player.STATE_ENDED)
                }
                else -> awaitPlayerState(active, Player.STATE_READY)
            }
        }
    }
    LaunchedEffect(Unit) {
        preload(playerA, 0)
        playerA.playWhenReady = true
        preload(playerB, 1)
        alphaA.animateTo(1f, tween(CROSSFADE_MS, easing = LinearEasing))
        while (true) {
            val isA = activeIsA
            val active = if (isA) playerA else playerB
            val next = if (isA) playerB else playerA
            val fromAlpha = if (isA) alphaA else alphaB
            val toAlpha = if (isA) alphaB else alphaA
            waitForCrossfadeWindow(active, next)
            next.seekTo(0)
            next.playWhenReady = true
            crossfading = true
            try {
                launch { fromAlpha.animateTo(0f, tween(CROSSFADE_MS, easing = LinearEasing)) }
                toAlpha.animateTo(1f, tween(CROSSFADE_MS, easing = LinearEasing))
            } finally {
                crossfading = false
            }
            activeIsA = !isA
            counter[0]++
            val preloadTarget = if (activeIsA) playerB else playerA
            preload(preloadTarget, counter[0] + 1)
        }
    }
    LaunchedEffect(playerA, playerB) {
        while (isActive) {
            delay(IdleVideoStallWatchdog.TICK_INTERVAL_MS)
            if (crossfading || lifecyclePaused) continue
            val active = if (activeIsA) playerA else playerB
            val result =
                IdleVideoStallWatchdog.tick(
                    state = stallWatchState,
                    snapshot =
                        IdleVideoStallWatchdog.Snapshot(
                            positionMs = active.currentPosition,
                            playbackState = active.playbackState,
                            isPlaying = active.isPlaying,
                            playWhenReady = active.playWhenReady,
                        ),
                    context =
                        IdleVideoStallWatchdog.WatchContext(
                            lifecyclePaused = lifecyclePaused,
                            crossfading = crossfading,
                            nowMs = System.currentTimeMillis(),
                        ),
                    activeAssetIndex = counter[0],
                )
            stallWatchState = result.state
            if (result.action != IdleVideoStallWatchdog.RecoveryAction.None) {
                result.diagnostic?.let { Timber.tag(TAG).w(it) }
                recoverStalledPlayer(active, result.action, counter[0])
            }
        }
    }
    LaunchedEffect(playerA) {
        delay(FIRST_FRAME_TIMEOUT_MS)
        val state = playerA.playbackState
        if (
            state != Player.STATE_READY &&
            state != Player.STATE_BUFFERING &&
            !playerA.isPlaying
        ) {
            Timber.tag(TAG).w("Idle video failed to start (state=%d) — dismiss", state)
            onDismiss()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = alphaA.value },
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.idle_player_view, null) as PlayerView)
                    .apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        player = playerA
                    }
            },
        )
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = alphaB.value },
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.idle_player_view, null) as PlayerView)
                    .apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        player = playerB
                    }
            },
        )
        // Поверх TextureView: иначе тап часто не доходит до Compose clickable родителя.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
        )
    }
}
