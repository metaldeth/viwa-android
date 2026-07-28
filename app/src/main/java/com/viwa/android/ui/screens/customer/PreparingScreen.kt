@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.viwa.android.ui.screens.customer

import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.ui.theme.LocalCustomerPrimaryButtonColor
import androidx.annotation.OptIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.ui.components.QRCodeView
import com.viwa.android.ui.theme.MontserratFamily
import kotlinx.coroutines.delay
import timber.log.Timber

private val SuccessGreen = Color(0xFF03BC2E)
private val ProgressTrack = Color(0xFF3A3A3C)
private const val CIRCLE_SIZE_DP = 260

/** Размер самого QR на экране чека NanoKassa. */
private val ReceiptQrCodeSize = 224.dp

/**
 * Тихая зона белой подложки: горизонтально больше, чтобы подложка была заметно шире кода
 * (на тёмном фоне иначе модули сливаются с подложкой экрана).
 */
private val ReceiptQrPadH = 16.dp
private val ReceiptQrPadV = 12.dp

/** Габариты белого слота под QR/лоадер (фиксированы, чтобы не прыгала вёрстка). */
private val ReceiptQrSlotTotalWidth = ReceiptQrCodeSize + ReceiptQrPadH * 2
private val ReceiptQrSlotTotalHeight = ReceiptQrCodeSize + ReceiptQrPadV * 2
private const val STROKE_WIDTH_DP = 20

/** Вертикальный отступ между заголовком и кругом (как space-5xl в electron ~40px). */
private val PreparingTitleToCircleGap = 28.dp
/** Вертикальный отступ между кругом и footer-кнопкой. */
private val PreparingCircleToFooterGap = 20.dp

private const val AUTO_CLOSE_RECEIPT_QR_MS = 15_000L
private const val AUTO_CLOSE_CHECKMARK_MS = 5_000L

/** 5 тапов по правому верхнему углу — аварийный выход (сброс сессии готовки и возврат в меню). */
private const val EMERGENCY_EXIT_TAP_COUNT = 5
private const val EMERGENCY_EXIT_TAP_RESET_MS = 2_500L

/**
 * Экран готовки: прогресс по [estSeconds], затем «готово» по [CustomerPreparingPhase.DrinkReady].
 * Фон — видео по вкусу [mediaKey] (.
 * Вёрстка повторяет : PreparingProgressView + PreparingSuccessView.
 */
@Composable
fun PreparingScreen(
    productName: String,
    estSeconds: Int,
    mediaKey: String?,
    onBackToMenu: () -> Unit,
    viewModel: PreparingViewModel = hiltViewModel(),
) {
    val phase by viewModel.customerPhase.collectAsStateWithLifecycle()
    val showReady = phase is CustomerPreparingPhase.DrinkReady
    val receiptAfterReady by viewModel.receiptAfterReady.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(estSeconds, showReady) {
        if (!showReady) {
            val ms = (estSeconds * 1000).coerceIn(800, 120_000)
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = ms, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }

    val backToDrinks: () -> Unit = {
        viewModel.resetSession()
        onBackToMenu()
    }
    val backToDrinksLatest by rememberUpdatedState(backToDrinks)

    LaunchedEffect(phase) {
        if (phase !is CustomerPreparingPhase.AwaitingDrinkReady) return@LaunchedEffect
        val delayMs = viewModel.getPreparingAutoExitDelayMs()
        if (delayMs <= 0L) return@LaunchedEffect
        delay(delayMs)
        if (viewModel.customerPhase.value is CustomerPreparingPhase.AwaitingDrinkReady) {
            Timber.tag("PreparingScreen").i("Автовыход с экрана готовки по таймауту")
            backToDrinksLatest()
        }
    }

    var emergencyTapCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(emergencyTapCount) {
        if (emergencyTapCount == 0) return@LaunchedEffect
        delay(EMERGENCY_EXIT_TAP_RESET_MS)
        emergencyTapCount = 0
    }

    LaunchedEffect(showReady, receiptAfterReady) {
        if (!showReady) return@LaunchedEffect
        when (receiptAfterReady) {
            is ReceiptAfterReadyState.ReceiptQr -> {
                delay(AUTO_CLOSE_RECEIPT_QR_MS)
                backToDrinks()
            }
            ReceiptAfterReadyState.SuccessCheckmark -> {
                delay(AUTO_CLOSE_CHECKMARK_MS)
                backToDrinks()
            }
            else -> Unit
        }
    }

    val accent = LocalCustomerPrimaryButtonColor.current

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ViwaCustomerUiTokens.ScreenBg),
    ) {
 // Фоновое видео по вкусу — как PreparingVideoLayer
        PreparingVideoBackground(mediaKey = mediaKey)

        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .widthIn(min = 360.dp, max = 500.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(ViwaCustomerUiTokens.PanelOverlayBg)
                    .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
 // Заголовок — без фиксированной высоты, обтекает контент
            when {
                !showReady -> PreparingProgressTitles(accent = accent)
                else ->
                    when (val r = receiptAfterReady) {
                        is ReceiptAfterReadyState.LoadingReceipt,
                        ReceiptAfterReadyState.Idle,
                        -> PreparingReceiptFiscalHeader(loading = true)
                        is ReceiptAfterReadyState.ReceiptQr ->
                            PreparingReceiptFiscalHeader(loading = false)
                        is ReceiptAfterReadyState.ReceiptError ->
                            PreparingReceiptErrorHeader()
                        ReceiptAfterReadyState.SuccessCheckmark ->
                            PreparingSuccessTitles()
                    }
            }

            Spacer(Modifier.height(PreparingTitleToCircleGap))

 // Центральный блок — без фиксированной высоты
            when {
                !showReady ->
                    PreparingProgressCircle(
                        progress = { progress.value },
                        percent = (progress.value * 100).toInt().coerceIn(0, 100),
                        progressColor = accent,
                        trackColor = ProgressTrack,
                    )
                else ->
                    when (val r = receiptAfterReady) {
                        is ReceiptAfterReadyState.LoadingReceipt,
                        ReceiptAfterReadyState.Idle,
                        -> ReceiptQrFixedSlot {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = accent,
                                strokeWidth = 4.dp,
                            )
                        }
                        is ReceiptAfterReadyState.ReceiptQr ->
                            ReceiptQrFixedSlot {
                                QRCodeView(
                                    data = r.checkPageUrl,
                                    modifier = Modifier.size(ReceiptQrCodeSize),
                                )
                            }
                        is ReceiptAfterReadyState.ReceiptError ->
                            PreparingReceiptErrorMiddle(
                                message = r.message,
                                onRetry = { viewModel.retryFiscalReceipt() },
                                accent = accent,
                            )
                        ReceiptAfterReadyState.SuccessCheckmark ->
                            SuccessCheckmarkCircle()
                    }
            }

            if (showReady) {
                Spacer(Modifier.height(PreparingCircleToFooterGap))
                Text(
                    text = "К меню напитков",
                    modifier =
                        Modifier
                            .clickable(onClick = backToDrinks)
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                    style = TextStyle(
                        fontFamily = MontserratFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = SuccessGreen,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }

 // Невидимая зона: 5 быстрых тапов — выход из готовки (обслуживание / зависание).
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 8.dp)
                    .size(width = 140.dp, height = 96.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        emergencyTapCount += 1
                        if (emergencyTapCount >= EMERGENCY_EXIT_TAP_COUNT) {
                            emergencyTapCount = 0
                            Timber.tag("PreparingScreen").i("Аварийный выход: %d тапов", EMERGENCY_EXIT_TAP_COUNT)
                            backToDrinks()
                        }
                    },
        )
    }
}

/** Две строки: «ГОТОВИМ» / «ВАШ НАПИТОК». */
@Composable
private fun PreparingProgressTitles(accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "ГОТОВИМ",
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                color = accent,
            ),
        )
        Text(
            text = "ВАШ НАПИТОК",
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                color = Color.White,
            ),
        )
    }
}

@Composable
private fun PreparingReceiptFiscalHeader(loading: Boolean) {
    val accent = LocalCustomerPrimaryButtonColor.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Ваш напиток готов",
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                color = accent,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text =
                if (loading) {
                    "Получаем чек…"
                } else {
                    "Вот ваш чек — отсканируйте QR"
                },
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun PreparingReceiptErrorHeader() {
    Text(
        text = "Ваш напиток готов",
        style = TextStyle(
            fontFamily = MontserratFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 22.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
private fun PreparingSuccessTitles() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "ВАШ НАПИТОК",
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                color = Color.White,
            ),
        )
        Text(
            text = "ГОТОВ",
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                color = SuccessGreen,
            ),
        )
    }
}

@Composable
private fun ReceiptQrFixedSlot(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.size(ReceiptQrSlotTotalWidth, ReceiptQrSlotTotalHeight),
        shape = RoundedCornerShape(12.dp),
        color = ViwaCustomerUiTokens.SbpQrBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = ReceiptQrPadH,
                        top = ReceiptQrPadV,
                        end = ReceiptQrPadH,
                        bottom = ReceiptQrPadV,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun PreparingReceiptErrorMiddle(
    message: String,
    onRetry: () -> Unit,
    accent: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = Color(0xFFFFB4B4),
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(
                text = "Повторить",
                color = accent,
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Круговой индикатор прогресса с % внутри.
 * Canvas-реализация.
 */
@Composable
private fun PreparingProgressCircle(
    progress: () -> Float,
    percent: Int,
    progressColor: Color,
    trackColor: Color,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CIRCLE_SIZE_DP.dp)
            .drawWithCache {
                val strokePx = STROKE_WIDTH_DP.dp.toPx()
 // Inset based on max stroke so neither arc clips canvas edge
                val inset = strokePx / 2f
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val arcOffset = Offset(inset, inset)
                onDrawBehind {
 // Track (full circle)
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
 // Progress arc — same stroke width as track, no clipping
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress(),
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            },
    ) {
        Text(
            text = "$percent%",
            softWrap = false,
            style = TextStyle(
                fontFamily = MontserratFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 74.sp,
                lineHeight = 74.sp,
                color = progressColor,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * Зелёный круг (200dp) с белой галочкой.
 */
@Composable
private fun SuccessCheckmarkCircle() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(SuccessGreen),
    ) {
 // SVG checkmark path
        Box(
            modifier = Modifier
                .size(127.dp, 96.dp)
                .drawWithCache {
                    val scaleX = size.width / 127f
                    val scaleY = size.height / 96f
                    val checkColor = Color(0xFFE0E0E0)
                    onDrawBehind {
                        val path = Path().apply {
 //
                            moveTo(123.114f * scaleX, 22.333f * scaleY)
                            lineTo(56.207f * scaleX, 91.291f * scaleY)
                            cubicTo(
                                51.252f * scaleX, 96.399f * scaleY,
                                43.215f * scaleX, 96.399f * scaleY,
                                38.256f * scaleX, 91.291f * scaleY,
                            )
                            lineTo(3.717f * scaleX, 55.690f * scaleY)
                            cubicTo(
                                -1.239f * scaleX, 50.582f * scaleY,
                                -1.239f * scaleX, 42.297f * scaleY,
                                3.717f * scaleX, 37.188f * scaleY,
                            )
                            cubicTo(
                                8.675f * scaleX, 32.078f * scaleY,
                                16.711f * scaleX, 32.078f * scaleY,
                                21.667f * scaleX, 37.186f * scaleY,
                            )
                            lineTo(47.234f * scaleX, 63.539f * scaleY)
                            lineTo(105.162f * scaleX, 3.831f * scaleY)
                            cubicTo(
                                110.120f * scaleX, -1.279f * scaleY,
                                118.157f * scaleX, -1.275f * scaleY,
                                123.112f * scaleX, 3.831f * scaleY,
                            )
                            cubicTo(
                                128.068f * scaleX, 8.940f * scaleY,
                                128.068f * scaleX, 17.222f * scaleY,
                                123.114f * scaleX, 22.333f * scaleY,
                            )
                            close()
                        }
                        drawPath(path = path, color = checkColor)
                    }
                },
        )
    }
}

private const val TAG_PREPARING_VIDEO = "PreparingVideo"

/**
 * Фоновый слой: видео по вкусу [mediaKey] на весь экран (object-fit: cover).
 *.
 * Если видео не найдено — показывает только фон (заглушка не нужна, панель перекрывает).
 */
@Composable
private fun PreparingVideoBackground(mediaKey: String?) {
    val videoUri = remember(mediaKey) { ViwaElectronAssets.preparingVideoUri(mediaKey) }
    if (videoUri == null) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(context) { buildCustomerBackgroundExoPlayer(context) }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { exoPlayer.playWhenReady = true; exoPlayer.play() }
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(videoUri) {
        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.play()
        }.onFailure { e -> Timber.tag(TAG_PREPARING_VIDEO).e(e, "load failed: %s", videoUri) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
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
}
