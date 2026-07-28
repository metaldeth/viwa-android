package com.viwa.android.ui.screens.customer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * ExoPlayer для фонового/прomo-видео на customer-экранах.
 * Hardware-декодер, wake lock и crop-scaling снижают нагрузку на UI/GPU.
 */
@UnstableApi
internal fun buildCustomerBackgroundExoPlayer(
    context: Context,
    repeatMode: Int = Player.REPEAT_MODE_ONE,
): ExoPlayer =
    ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
        )
        .build()
        .apply {
            this.repeatMode = repeatMode
            playWhenReady = true
            volume = 0f
            setWakeMode(C.WAKE_MODE_LOCAL)
            setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        }
