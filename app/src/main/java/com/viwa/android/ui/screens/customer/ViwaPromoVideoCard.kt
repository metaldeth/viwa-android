package com.viwa.android.ui.screens.customer

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.viwa.android.ui.theme.MontserratFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.random.Random
import timber.log.Timber

private const val TAG_PROMO = "ViwaPromoVideo"

/**
 * Видео и текст как `PromoCard.tsx` + `PromoCard.module.scss` (оверлей, типографика).
 * Ролики из assets/viwa_electron/video.
 * Тап —.
 */
@UnstableApi
@Composable
fun ViwaPromoVideoCard(
    s: Float = 1f,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val files = ViwaElectronAssets.PROMO_VIDEO_FILES

    val videoIndex = remember(files.size) { Random.nextInt(files.size) }

    val exoPlayer = remember(context) { buildCustomerBackgroundExoPlayer(context) }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val obs =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                    Lifecycle.Event.ON_START -> {
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    LaunchedEffect(videoIndex, files) {
        val name = files[videoIndex]
        val uri = android.net.Uri.parse("${ViwaElectronAssets.ASSET_URI_PREFIX}/video/$name")
        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.play()
        }.onFailure { e ->
            Timber.tag(TAG_PROMO).e(e, "load failed: %s", name)
        }
    }

    val cardShape = RoundedCornerShape(topStart = (20f * s).dp, topEnd = (20f * s).dp)
    Box(
        modifier =
            Modifier
                .width((495f * s).dp)
                .height((154f * s).dp)
                .viwaCardShadow(elevation = (4f * s).dp, shape = cardShape)
                .background(Color.Black, cardShape)
                .clip(cardShape)
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = 0.35f))
                }
                .clickable { onClick() },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
        )
        Column(
            modifier =
                Modifier
                    .matchParentSize()
                    .padding(horizontal = (20f * s).dp, vertical = (16f * s).dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Попробуй вкусную\nи полезную воду\nза 0 ₽",
                fontSize = (30f * s).sp,
                lineHeight = (34f * s).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MontserratFamily,
                color = Color(0xFFE0E0E0),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
