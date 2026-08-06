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
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

private const val TAG = "IdleVideoHost"

private const val CROSSFADE_MS = 500
private const val CROSSFADE_TRIGGER_MS = 1_500L
private const val PRELOAD_LEAD_MS = 6_000L
private const val READY_WAIT_TIMEOUT_MS = 3_000L
private const val CONTAINER_FADE_MS = 200
private const val FIRST_FRAME_FALLBACK_MS = 1_500L

private inline fun idleVideoLog(block: () -> Unit) {
    if (BuildConfig.DEBUG) block()
}

/**
 * Хост idle-видео: прогрев без surface (Prewarm) и показ с fade-in (Visible).
 * Монтируется только когда [phase] != [IdlePhase.Hidden].
 */
@UnstableApi
@Composable
fun IdleVideoHost(
    phase: IdlePhase,
    enabledVideoIds: List<String>,
    onPrewarmReady: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val files =
        remember(enabledVideoIds) {
            enabledVideoIds.map { id -> "$id.mp4" }.ifEmpty { emptyList() }
        }
    if (files.isEmpty()) return

    val playerA = remember(context) { buildCustomerBackgroundExoPlayer(context, repeatMode = Player.REPEAT_MODE_OFF) }
    val playerB = remember(context) { buildCustomerBackgroundExoPlayer(context, repeatMode = Player.REPEAT_MODE_OFF) }
    val alphaA = remember { Animatable(0f) }
    val alphaB = remember { Animatable(0f) }
    val containerAlpha = remember { Animatable(0f) }
    var activeIsA by remember { mutableStateOf(true) }
    var lifecyclePaused by remember { mutableStateOf(false) }
    var crossfading by remember { mutableStateOf(false) }
    val counter = remember { intArrayOf(0) }
    var stallWatchState by remember { mutableStateOf(IdleVideoStallWatchdog.WatchState()) }
    var prewarmSignaled by remember { mutableStateOf(false) }

    val dismissRequestedAt = remember { longArrayOf(0L) }

    fun dismiss() {
        dismissRequestedAt[0] = System.currentTimeMillis()
        playerA.pause()
        playerB.pause()
        playerA.playWhenReady = false
        playerB.playWhenReady = false
        onDismiss()
    }

    DisposableEffect(playerA, playerB) {
        onDispose {
            if (BuildConfig.DEBUG && dismissRequestedAt[0] != 0L) {
                Timber.d("IdleMetrics dismiss_ms=${System.currentTimeMillis() - dismissRequestedAt[0]}")
            }
            playerA.pause()
            playerB.pause()
            playerA.release()
            playerB.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        lifecyclePaused = true
                        playerA.pause()
                        playerB.pause()
                    }
                    Lifecycle.Event.ON_START -> {
                        lifecyclePaused = false
                        if (phase != IdlePhase.Visible) return@LifecycleEventObserver
                        val active = if (activeIsA) playerA else playerB
                        if (
                            active.playbackState == Player.STATE_READY ||
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
        // playWhenReady снимаем до prepare: фабрика ставит его в true, иначе плеер
        // начинает декодировать сразу и держит нагрузку до своей очереди.
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri(uri(idx)))
        player.prepare()
    }

    fun nextAssetIndex(): Int = (counter[0] + 1) % files.size

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

    /**
     * Ждём именно отрисовку кадра, а не READY: READY означает лишь буфер,
     * а показывать оверлей до первого кадра — это чёрная вспышка.
     */
    suspend fun awaitFirstFrame(player: ExoPlayer): Boolean {
        return withTimeoutOrNull(FIRST_FRAME_FALLBACK_MS) {
            suspendCancellableCoroutine { cont ->
                val listener =
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
            }
        } ?: false
    }

    suspend fun preloadNextWithLead(active: ExoPlayer, next: ExoPlayer, idx: Int) {
        val dur = active.duration
        if (dur == C.TIME_UNSET || dur <= 0L) {
            preload(next, idx)
            return
        }
        val lead =
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = dur,
                positionMs = active.currentPosition,
                triggerBeforeEndMs = CROSSFADE_TRIGGER_MS,
                preloadLeadMs = PRELOAD_LEAD_MS,
            ) ?: 0L
        if (lead > 0L) delay(lead.coerceAtMost(60_000L))
        preload(next, idx)
    }

    suspend fun waitForCrossfadeWindow(active: ExoPlayer, next: ExoPlayer) {
        if (active.playbackState == Player.STATE_ENDED) {
            withTimeoutOrNull(READY_WAIT_TIMEOUT_MS) { awaitPlayerState(next, Player.STATE_READY) }
            return
        }
        while (true) {
            when (active.playbackState) {
                Player.STATE_ENDED -> {
                    withTimeoutOrNull(READY_WAIT_TIMEOUT_MS) { awaitPlayerState(next, Player.STATE_READY) }
                    return
                }
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    val dur = active.duration
                    val pos = active.currentPosition
                    if (dur != C.TIME_UNSET && dur > 0) {
                        if (
                            IdleVideoCrossfadeTiming.shouldCrossfadeNow(
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
                        if (delayMs != null) {
                            delay(delayMs.coerceAtMost(60_000L))
                            return
                        }
                    }
                    awaitPlayerState(active, Player.STATE_ENDED)
                }
                else -> awaitPlayerState(active, Player.STATE_READY)
            }
        }
    }

    LaunchedEffect(phase, files) {
        if (phase != IdlePhase.Prewarm || prewarmSignaled) return@LaunchedEffect
        val prewarmStart = System.currentTimeMillis()
        preload(playerA, 0)
        awaitPlayerState(playerA, Player.STATE_READY)
        if (BuildConfig.DEBUG) {
            Timber.d("IdleMetrics prewarm_ready_ms=${System.currentTimeMillis() - prewarmStart}")
        }
        prewarmSignaled = true
        onPrewarmReady()
    }

    LaunchedEffect(phase) {
        if (phase != IdlePhase.Visible) return@LaunchedEffect
        val visibleStart = System.currentTimeMillis()
        containerAlpha.snapTo(0f)
        alphaA.snapTo(1f)
        alphaB.snapTo(0f)

        // Запускаем воспроизведение до ожидания кадра: часть декодеров на паузе
        // первый кадр не отдаёт, и ожидание упиралось бы в fallback.
        playerA.playWhenReady = true
        playerA.play()
        val firstFrameRendered = awaitFirstFrame(playerA)
        if (BuildConfig.DEBUG) {
            val clipDur = playerA.duration
            Timber.d(
                "IdleMetrics clip_duration_ms=${if (clipDur == C.TIME_UNSET || clipDur <= 0L) -1 else clipDur}",
            )
            Timber.d("IdleMetrics first_frame_ms=${System.currentTimeMillis() - visibleStart}")
        }
        if (
            !firstFrameRendered &&
            !playerA.isPlaying &&
            playerA.playbackState != Player.STATE_READY &&
            playerA.playbackState != Player.STATE_BUFFERING
        ) {
            Timber.tag(TAG).w("Idle video failed to render first frame — dismiss")
            dismiss()
            return@LaunchedEffect
        }
        containerAlpha.animateTo(1f, tween(CONTAINER_FADE_MS, easing = LinearEasing))

        while (isActive) {
            val isA = activeIsA
            val active = if (isA) playerA else playerB
            val next = if (isA) playerB else playerA
            val fromAlpha = if (isA) alphaA else alphaB
            val toAlpha = if (isA) alphaB else alphaA
            preloadNextWithLead(active, next, nextAssetIndex())
            waitForCrossfadeWindow(active, next)
            val windowAt = System.currentTimeMillis()
            val nextReadyAtWindow = next.playbackState == Player.STATE_READY
            if (next.playbackState != Player.STATE_READY) {
                withTimeoutOrNull(READY_WAIT_TIMEOUT_MS) { awaitPlayerState(next, Player.STATE_READY) }
            }
            if (BuildConfig.DEBUG) {
                Timber.d(
                    "IdleMetrics crossfade clip=%d next_ready=%b ready_wait_ms=%d",
                    counter[0],
                    nextReadyAtWindow,
                    System.currentTimeMillis() - windowAt,
                )
            }
            if (next.playbackState != Player.STATE_READY) {
                // Повторить текущий ролик безопаснее, чем кроссфейдить в неготовый
                // плеер: иначе на экране окажется чёрный кадр.
                Timber.tag(TAG).w("next idle player not ready — repeating current clip")
                active.seekTo(0)
                active.playWhenReady = true
                active.play()
                continue
            }
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
        }
    }

    LaunchedEffect(phase, playerA, playerB) {
        if (phase != IdlePhase.Visible) return@LaunchedEffect
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

    if (phase == IdlePhase.Visible) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = containerAlpha.value }
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
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { dismiss() },
                        ),
            )
        }
    }
}
