package com.viwa.android.ui.screens.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.ui.theme.MontserratFamily
import kotlin.math.cos
import kotlin.math.sin

/**
 * Геометрия 1:1 с `MonthlyProgressCard.tsx` (viewBox 300×250).
 * Android drawArc: 0°=3 часа, по часовой; web polar: 0°=восток, CCW, y = cy − r·sin.
 */
private val GaugeGoalGreen = Color(0xFFA6FFE0)
private val GaugeRemainingColors =
    listOf(Color(0xFF7F5AF0), Color(0xFF3B82F6), Color(0xFF00E5FF))
private val GaugeTrackColor = Color.White.copy(alpha = 0.10f)
private val GaugeTickMajorColor = Color.White.copy(alpha = 0.38f)
private val GaugeTickMinorColor = Color.White.copy(alpha = 0.16f)
private val GaugeScaleTextColor = Color(0xFFA3A3A3)
private val GaugeCenterMuted = Color(0xFFA3A3A3)
private val GaugeMetricColor = Color(0xFFF5F5F5)

private const val WebGaugeWidth = 300f
private const val WebGaugeHeight = 250f
private const val WebCenterX = 150f
private const val WebCenterY = 128f
private const val WebSweepDeg = 270f
private const val WebStartDeg = 225f
private const val WebEndDeg = 315f
private const val WebStrokeWidth = 16f
private const val WebRadius = 102f
private const val WebTickOuter = 120f // RADIUS + 18
private const val WebTickInnerMajor = 108f // RADIUS + 6
private const val WebTickInnerMinor = 112f // RADIUS + 10
private const val WebLabelRadius = 134f // TICK_OUTER + 14
private const val WebMajorEvery = 5
private const val WebTickCount = 49

/** Плотный bbox подковы (без пустых полей viewBox 250) — для max высоты в карточке. */
private const val WebVisualTop = 6f
private const val WebVisualBottom = 236f
private const val WebVisualHeight = WebVisualBottom - WebVisualTop // 230

/** Android start = −webStart (mod 360): web 225° → 135°. */
private const val AndroidArcStartDeg = 135f

private enum class SubscribePromoScenario {
    Trial,
    Active,
    LimitExhausted,
    Expired,
}

/** Бесплатный дневной литр (как `app.constVolume` на бэкенде) — знаменатель шкалы, не из maxVolume ответа. */
private const val FREE_DRINK_TOTAL_ML = 1000

/**
 * Знаменатель дуги: при активной подписке — лимит уровня с бэка; иначе — фиксированный 1 л для бесплатного напитка.
 */
private fun volumeProgressDenominatorMl(state: DrinkListUiState): Int =
    if (state.isSubscriptionActive) {
        state.subscriptionMaxVolumeMl.coerceAtLeast(1)
    } else {
        FREE_DRINK_TOTAL_ML
    }

/** Как `getScenario` в `SubscribeCard.tsx` (PurchaseMenu). */
private fun subscribePromoScenario(state: DrinkListUiState): SubscribePromoScenario {
    if (state.isSubscriptionActive) {
        return if (state.subscriptionVolumeMl <= 0) SubscribePromoScenario.LimitExhausted
        else SubscribePromoScenario.Active
    }
    return if (state.subscriptionEndDate.isNullOrBlank()) {
        SubscribePromoScenario.Trial
    } else {
        SubscribePromoScenario.Expired
    }
}

private fun formatEndDate(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val head = raw.take(10)
    val parts = head.split('-')
    return if (parts.size == 3) {
        "${parts[2]}.${parts[1]}.${parts[0]}"
    } else {
        head.replace('-', '.')
    }
}

/** мл → литры для шкалы: 18000 → «18», 15001 → «15». */
private fun formatLitersLabel(ml: Int): String {
    val tenths = kotlin.math.round(ml / 100.0).toInt() // 0.1 л
    return if (tenths % 10 == 0) {
        (tenths / 10).toString()
    } else {
        "${tenths / 10},${tenths % 10}"
    }
}

/**
 * Цвета карточки подписки по `Theme_color_gpnDefault.css` / `Theme_color_gpnDark.css` (как SubscribeCard в electron).
 */
private data class SubscribePromoPalette(
    val cardBg: Color,
    val title: Color,
    val subtitle: Color,
    val volumeTrack: Color,
    val secondaryButtonBg: Color,
    val secondaryButtonText: Color,
    val exitIcon: Color,
)

@Composable
private fun subscribePromoPalette(): SubscribePromoPalette {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (isLight) {
        SubscribePromoPalette(
 // --bg-main-default: #ffffff
            cardBg = Color(0xFFFFFFFF),
 // typo на светлой карточке
            title = Color(0xFF231F20),
            subtitle = Color(0xFF43474E),
 // трек круга: на белом фоне видимое кольцо (--bg-main-primary #f0f0f0; в electron трек = --bg-main-secondary = #fff — почти невидим)
            volumeTrack = Color(0xFFF0F0F0),
 // --control-secondary-bg-bg / typo
            secondaryButtonBg = Color(0xFFD9DADE),
            secondaryButtonText = Color(0xFF383838),
 // --control-clear-typo-typo
            exitIcon = Color(0xFF343434),
        )
    } else {
        SubscribePromoPalette(
 // --bg-main-default: #2c2d2e
            cardBg = Color(0xFF2C2D2E),
            title = Color(0xFFE0E0E0),
            subtitle = Color(0xFFB8B8B8),
 // --bg-main-secondary: #1d1d1d
            volumeTrack = Color(0xFF1D1D1D),
            secondaryButtonBg = Color(0xFF353535),
            secondaryButtonText = Color(0xFFE0E0E0),
            exitIcon = Color(0xFFE0E0E0),
        )
    }
}

/**
 * Карточка подписки в слоте промо (495×154 epx), как `SubscribeCard` вместо `PromoCard`.
 * Форма: только верхние углы скруглены (border-radius: 24 24 0 0 по макету).
 */
@Composable
fun ViwaSubscribePromoCard(
    s: Float,
    state: DrinkListUiState,
    onDismiss: () -> Unit,
    onOpenSubscriptionPurchase: () -> Unit,
) {
    val palette = subscribePromoPalette()
    val scenario = subscribePromoScenario(state)
 // Только верхние углы скруглены — как в SubscribeCard.module.scss: border-top-*-radius: --control-radius-round-l (24px)
    val cardShape = RoundedCornerShape(
        topStart = (24f * s).dp,
        topEnd = (24f * s).dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    val maxVol = volumeProgressDenominatorMl(state)

    // Габарит 495×154 фиксирован. Текст прижат к шкале; кнопка выхода справа снизу.
    val cardPadStart = 2f * s
    val cardPadEnd = 6f * s
    val cardPadV = 6f * s
    val gaugeToTextGap = 14f * s
    val textColStartPad = 4f * s
    val contentH = 154f * s - 2f * cardPadV
    val maxGaugeW = 495f * s - cardPadStart - cardPadEnd - gaugeToTextGap - 160f * s
    Box(
        modifier =
            Modifier
                .width((495f * s).dp)
                .height((154f * s).dp)
                .viwaCardShadow(elevation = (4f * s).dp, shape = cardShape)
                .clip(cardShape)
                .background(palette.cardBg),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((154f * s).dp)
                    .padding(
                        start = cardPadStart.dp,
                        end = cardPadEnd.dp,
                        top = cardPadV.dp,
                        bottom = cardPadV.dp,
                    ),
            horizontalArrangement = Arrangement.spacedBy(gaugeToTextGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViwaSubscriptionVolumeGauge(
                s = s,
                remainingMl = state.subscriptionVolumeMl,
                limitMl = maxVol,
                maxHeightDp = contentH,
                maxWidthDp = maxGaugeW,
            )

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = textColStartPad.dp),
            ) {
                when (scenario) {
                    SubscribePromoScenario.Trial -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                            ) {
                                Text(
                                    text = "Вам доступен бесплатный напиток!",
                                    fontSize = (22f * s).sp,
                                    lineHeight = (28f * s).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = MontserratFamily,
                                    color = palette.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Стандартная вода — безлимитно",
                                    fontSize = (13f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                )
                            }
                            PromoActionRow(
                                s = s,
                                palette = palette,
                                primaryLabel = "Выгодная подписка тут",
                                onPrimaryClick = onOpenSubscriptionPurchase,
                                onDismiss = onDismiss,
                                remainingSeconds = state.subscriptionExitRemainingSeconds,
                                showPrimary = false,
                            )
                        }
                    }
                    SubscribePromoScenario.Active -> {
                        // Заголовок сверху (отступ ×2); описание — плотный блок из 3 строк.
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        top = (cardPadV * 2f).dp,
                                        end = (40f * s).dp,
                                        bottom = (2f * s).dp,
                                    ),
                        ) {
                            Text(
                                text = "Подписка активна",
                                fontSize = (24f * s).sp,
                                lineHeight = (28f * s).sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = MontserratFamily,
                                color = palette.title,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = (10f * s).dp),
                                verticalArrangement = Arrangement.spacedBy((2f * s).dp),
                            ) {
                                Text(
                                    text =
                                        state.subscriptionEndDate?.let { end ->
                                            "Действует до ${formatEndDate(end)}"
                                        } ?: " ",
                                    fontSize = (15f * s).sp,
                                    lineHeight = (16f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Вода безлимитно",
                                    fontSize = (15f * s).sp,
                                    lineHeight = (16f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "остаток для напитков",
                                    fontSize = (15f * s).sp,
                                    lineHeight = (16f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        SubscriptionExitControl(
                            s = s,
                            palette = palette,
                            remainingSeconds = state.subscriptionExitRemainingSeconds,
                            onDismiss = onDismiss,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                    SubscribePromoScenario.LimitExhausted -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy((3f * s).dp),
                            ) {
                                Text(
                                    text = "Подписка активна",
                                    fontSize = (22f * s).sp,
                                    lineHeight = (26f * s).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = MontserratFamily,
                                    color = palette.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Лимит напитков исчерпан, обновится завтра",
                                    fontSize = (14f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Вода — безлимитно",
                                    fontSize = (14f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                )
                            }
                            PromoActionRow(
                                s = s,
                                palette = palette,
                                primaryLabel = "Продлить",
                                onPrimaryClick = onOpenSubscriptionPurchase,
                                onDismiss = onDismiss,
                                remainingSeconds = state.subscriptionExitRemainingSeconds,
                                showPrimary = false,
                            )
                        }
                    }
                    SubscribePromoScenario.Expired -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy((4f * s).dp),
                            ) {
                                Text(
                                    text = "Срок действия истёк",
                                    fontSize = (22f * s).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = MontserratFamily,
                                    color = palette.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                state.subscriptionEndDate?.let { end ->
                                    Text(
                                        text = "Действует до ${formatEndDate(end)}",
                                        fontSize = (14f * s).sp,
                                        fontFamily = MontserratFamily,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "Стандартная вода — безлимитно",
                                    fontSize = (14f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                )
                            }
                            PromoActionRow(
                                s = s,
                                palette = palette,
                                primaryLabel = "Продлить",
                                onPrimaryClick = onOpenSubscriptionPurchase,
                                onDismiss = onDismiss,
                                remainingSeconds = state.subscriptionExitRemainingSeconds,
                                showPrimary = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Web polar (`MonthlyProgressCard.tsx`): x=cx+r·cos, y=cy−r·sin. */
private fun webPolar(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val rad = Math.toRadians(deg.toDouble())
    return Offset(
        x = cx + r * cos(rad).toFloat(),
        y = cy - r * sin(rad).toFloat(),
    )
}

/**
 * Подкова 270° (MonthlyProgressCard). Объёмы в литрах (без «мл»).
 * Вписывается в maxHeight×maxWidth карточки 495×154.
 */
@Composable
private fun ViwaSubscriptionVolumeGauge(
    s: Float,
    remainingMl: Int,
    limitMl: Int,
    maxHeightDp: Float,
    maxWidthDp: Float,
) {
    val safeLimit = limitMl.coerceAtLeast(1)
    val safeRemaining = remainingMl.coerceIn(0, safeLimit)
    val remainingRatio = safeRemaining.toFloat() / safeLimit.toFloat()
    val usedRatio = (1f - remainingRatio).coerceIn(0f, 1f)

    // Сначала по высоте; если шире лимита — жмём по ширине (карточка 495).
    val heightFromMaxH = maxHeightDp
    val widthFromMaxH = heightFromMaxH * (WebGaugeWidth / WebVisualHeight)
    val gaugeWidthDp: Float
    val gaugeHeightDp: Float
    if (widthFromMaxH <= maxWidthDp) {
        gaugeWidthDp = widthFromMaxH
        gaugeHeightDp = heightFromMaxH
    } else {
        gaugeWidthDp = maxWidthDp
        gaugeHeightDp = maxWidthDp * (WebVisualHeight / WebGaugeWidth)
    }
    val typoScale = gaugeWidthDp / 300f
    val remainingLiters = formatLitersLabel(safeRemaining)
    val limitLiters = formatLitersLabel(safeLimit)

    Box(
        modifier =
            Modifier
                .width(gaugeWidthDp.dp)
                .height(gaugeHeightDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.width(gaugeWidthDp.dp).height(gaugeHeightDp.dp)) {
                val scale = size.height / WebVisualHeight
                val cx = size.width / 2f
                val cy = (WebCenterY - WebVisualTop) * scale
                val radius = WebRadius * scale
                val strokeWidth = WebStrokeWidth * scale
                val arcTopLeft = Offset(cx - radius, cy - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

                for (i in 0 until WebTickCount) {
                    val t = i.toFloat() / (WebTickCount - 1).toFloat()
                    val deg = WebStartDeg - t * WebSweepDeg
                    val major = i % WebMajorEvery == 0
                    val outer = WebTickOuter * scale
                    val inner = (if (major) WebTickInnerMajor else WebTickInnerMinor) * scale
                    drawLine(
                        color = if (major) GaugeTickMajorColor else GaugeTickMinorColor,
                        start = webPolar(cx, cy, outer, deg),
                        end = webPolar(cx, cy, inner, deg),
                        strokeWidth = (if (major) 1.5f else 1f) * scale,
                        cap = StrokeCap.Round,
                    )
                }

                drawArc(
                    color = GaugeTrackColor,
                    startAngle = AndroidArcStartDeg,
                    sweepAngle = WebSweepDeg,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = stroke,
                )

                val usedSweep = WebSweepDeg * usedRatio
                val remainingSweep = WebSweepDeg * remainingRatio

                if (remainingSweep > 0.25f) {
                    val remStartDeg = AndroidArcStartDeg + usedSweep
                    val remEndDeg = remStartDeg + remainingSweep
                    val remStartRad = Math.toRadians(remStartDeg.toDouble())
                    val remEndRad = Math.toRadians(remEndDeg.toDouble())
                    drawArc(
                        brush =
                            Brush.linearGradient(
                                colors = GaugeRemainingColors,
                                start =
                                    Offset(
                                        cx + radius * cos(remStartRad).toFloat(),
                                        cy + radius * sin(remStartRad).toFloat(),
                                    ),
                                end =
                                    Offset(
                                        cx + radius * cos(remEndRad).toFloat(),
                                        cy + radius * sin(remEndRad).toFloat(),
                                    ),
                            ),
                        startAngle = remStartDeg,
                        sweepAngle = remainingSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = stroke,
                    )
                }

                if (usedSweep > 0.25f) {
                    drawArc(
                        color = GaugeGoalGreen,
                        startAngle = AndroidArcStartDeg,
                        sweepAngle = usedSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = stroke,
                    )
                }

                val label0 = webPolar(cx, cy, WebLabelRadius * scale, WebStartDeg)
                val labelMax = webPolar(cx, cy, WebLabelRadius * scale, WebEndDeg)
                val labelPaint0 =
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.rgb(0xA3, 0xA3, 0xA3)
                        textSize = 10f * scale
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                val labelPaintMax =
                    android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.rgb(0xA3, 0xA3, 0xA3)
                        textSize = 10f * scale
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                drawIntoCanvas { canvas ->
                    val yOff0 = (labelPaint0.descent() + labelPaint0.ascent()) / 2f
                    val yOffMax = (labelPaintMax.descent() + labelPaintMax.ascent()) / 2f
                    canvas.nativeCanvas.drawText("0", label0.x, label0.y - yOff0, labelPaint0)
                    canvas.nativeCanvas.drawText(
                        limitLiters,
                        labelMax.x + 3f * scale,
                        labelMax.y - yOffMax,
                        labelPaintMax,
                    )
                }
            }

            // Центр = только цифра; единица остаётся в подписи лимита.
            // Вертикаль: метрика в cy дуги; подписи — спутники выше/ниже (не тянут блок вверх).
            val arcCenterYFrac = (WebCenterY - WebVisualTop) / WebVisualHeight
            val toArcFromBoxCenter = gaugeHeightDp * (arcCenterYFrac - 0.5f)
            val metricSp = (72f * typoScale).coerceAtLeast(38f * s) * 0.9f
            val captionSp = (15f * typoScale).coerceAtLeast(11f * s)
            Box(modifier = Modifier.fillMaxWidth().height(gaugeHeightDp.dp)) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .offset(y = toArcFromBoxCenter.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ОСТАЛОСЬ",
                        fontSize = captionSp.sp,
                        lineHeight = captionSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = MontserratFamily,
                        color = GaugeCenterMuted,
                        textAlign = TextAlign.Center,
                        letterSpacing = (1.1f * typoScale).sp,
                        maxLines = 1,
                        modifier = Modifier.offset(y = (-(metricSp * 0.72f)).dp),
                    )
                    Text(
                        text = remainingLiters,
                        fontSize = metricSp.sp,
                        lineHeight = metricSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = MontserratFamily,
                        color = GaugeMetricColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = "ИЗ $limitLiters Л",
                        fontSize = captionSp.sp,
                        lineHeight = captionSp.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = MontserratFamily,
                        color = GaugeCenterMuted,
                        textAlign = TextAlign.Center,
                        letterSpacing = (0.7f * typoScale).sp,
                        maxLines = 1,
                        modifier = Modifier.offset(y = (metricSp * 0.68f).dp),
                    )
                }
            }
    }
}

@Composable
private fun PromoActionRow(
    s: Float,
    palette: SubscribePromoPalette,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    onDismiss: () -> Unit,
    remainingSeconds: Int,
    showPrimary: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
 // space="xs" в Consta = 8px
        horizontalArrangement =
            if (showPrimary) {
                Arrangement.spacedBy((8f * s).dp)
            } else {
                Arrangement.End
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPrimary) {
            PrimaryActionButton(
                s = s,
                palette = palette,
                label = primaryLabel,
                onClick = onPrimaryClick,
            )
        }
        SubscriptionExitControl(
            s = s,
            palette = palette,
            remainingSeconds = remainingSeconds,
            onDismiss = onDismiss,
        )
    }
}

/** Кнопка выхода из подписки: countdown слева от иконки. */
@Composable
private fun SubscriptionExitControl(
    s: Float,
    palette: SubscribePromoPalette,
    remainingSeconds: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .height((44f * s).dp)
                .clip(RoundedCornerShape((8f * s).dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = (10f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((6f * s).dp),
    ) {
        if (remainingSeconds > 0) {
            Text(
                text = "${remainingSeconds}",
                fontSize = (16f * s).sp,
                lineHeight = (18f * s).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MontserratFamily,
                color = palette.exitIcon,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
            contentDescription = "Выход",
            tint = palette.exitIcon,
            modifier = Modifier.size((22f * s).dp),
        )
    }
}

@Composable
private fun RowScope.PrimaryActionButton(
    s: Float,
    palette: SubscribePromoPalette,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .weight(1f)
                .height((48f * s).dp),
 // --control-radius-l: 12px из Theme_control_gpnDefault.css
        shape = RoundedCornerShape((12f * s).dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = palette.secondaryButtonBg,
                contentColor = palette.secondaryButtonText,
            ),
    ) {
        Text(
            text = label,
            fontSize = (15f * s).sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MontserratFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
