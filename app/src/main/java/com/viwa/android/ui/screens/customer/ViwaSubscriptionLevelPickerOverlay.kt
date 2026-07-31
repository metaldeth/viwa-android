package com.viwa.android.ui.screens.customer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.R
import com.viwa.android.services.telemetry.SubscriptionLevelItem
import com.viwa.android.ui.theme.MontserratFamily
import kotlin.math.abs

/** Аналог рекомендованного тарифа в кабинете (средний из 2–3). */
private const val RecommendedLevelIndex = 1

/** Палитра кабинета / лендинга Viwa. */
private val ScreenBlack = Color(0xFF000000)
private val VioletAccent = Color(0xFF7F5AF0)
private val CyanAccent = Color(0xFF00E5FF)
private val MintPrice = Color(0xF2A6FFE0)
private val TextPrimary = Color(0xFFF5F5F5)
private val TextMuted = Color(0x99F5F5F5)
private val BackButtonGray = Color(0xFF353535)
private val CtaUnselected = Color(0x2EFFFFFF)

/**
 * Фото-фоны тарифов (drawable-nodpi), циклически для N уровней.
 */
private data class SubscriptionCardVisual(
    @DrawableRes val photoRes: Int,
    val audienceLine: String,
)

private val CardVisuals =
    listOf(
        SubscriptionCardVisual(
            photoRes = R.drawable.viwa_tier_card_01,
            audienceLine = "Для лёгкого ритма",
        ),
        SubscriptionCardVisual(
            photoRes = R.drawable.viwa_tier_card_02,
            audienceLine = "Оптимум на каждый день",
        ),
        SubscriptionCardVisual(
            photoRes = R.drawable.viwa_tier_card_03,
            audienceLine = "Максимум напитков + вода безлимитно",
        ),
    )

/**
 * Полноэкранный выбор тарифа подписки (landscape kiosk).
 * Визуал как кабинет клиента: тёмный фон, фото-карточки, mint-цена, violet/cyan акцент → далее СБП.
 */
@Composable
fun ViwaSubscriptionLevelPickerOverlay(
    s: Float,
    levels: List<SubscriptionLevelItem>?,
    levelsLoading: Boolean,
    tariffsError: String? = null,
    onDismiss: () -> Unit,
    onSelectLevel: (SubscriptionLevelItem) -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(ScreenBlack),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = (28f * s).dp, vertical = (22f * s).dp),
        ) {
            Text(
                text = "Выберите подписку",
                fontSize = (28f * s).sp,
                lineHeight = (34f * s).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MontserratFamily,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height((6f * s).dp))
            Text(
                text = "Объём на месяц — оплата через СБП или картой",
                fontSize = (15f * s).sp,
                lineHeight = (20f * s).sp,
                fontFamily = MontserratFamily,
                color = TextMuted,
                modifier = Modifier.padding(bottom = (18f * s).dp),
            )

            when {
                !tariffsError.isNullOrBlank() -> {
                    ErrorBlock(
                        s = s,
                        message = tariffsError,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
                levels == null || levelsLoading -> {
                    LoadingBlock(s = s, modifier = Modifier.weight(1f))
                }
                levels.isEmpty() -> {
                    EmptyBlock(s = s, onRetry = onRetry, modifier = Modifier.weight(1f))
                }
                else -> {
                    val showRecommendedBadge = levels.size >= 2
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy((16f * s).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        levels.forEachIndexed { index, level ->
                            val recommended = showRecommendedBadge && index == RecommendedLevelIndex
                            SubscriptionPhotoCard(
                                s = s,
                                level = level,
                                recommended = recommended,
                                visual = CardVisuals[index % CardVisuals.size],
                                onClick = { onSelectLevel(level) },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(0.92f),
                            )
                        }
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = (12f * s).dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .size((48f * s).dp)
                            .clip(CircleShape)
                            .background(BackButtonGray),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color.White,
                        modifier = Modifier.size((24f * s).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionPhotoCard(
    s: Float,
    level: SubscriptionLevelItem,
    recommended: Boolean,
    visual: SubscriptionCardVisual,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = (16f * s).dp
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier =
            modifier
                .then(
                    if (recommended) {
                        Modifier.border(
                            width = (2.5f * s).dp,
                            color = VioletAccent,
                            shape = shape,
                        )
                    } else {
                        Modifier.border(
                            width = (1f * s).dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = shape,
                        )
                    },
                )
                .clip(shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        if (recommended) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .border(
                            width = (1.5f * s).dp,
                            color = CyanAccent.copy(alpha = 0.45f),
                            shape = shape,
                        ),
            )
        }
        Image(
            painter = painterResource(visual.photoRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxSize(),
        )

        // Горизонтальный градиент как в кабинете: текст слева, фото справа.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0.00f to Color(0xF2000000),
                                    0.42f to Color(0xCC000000),
                                    0.72f to Color(0x55000000),
                                    1.00f to Color(0x14000000),
                                ),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = (18f * s).dp, vertical = (16f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                if (recommended) {
                    Text(
                        text = "ЛУЧШИЙ ВЫБОР",
                        fontSize = (11f * s).sp,
                        lineHeight = (14f * s).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MontserratFamily,
                        color = MintPrice,
                        letterSpacing = (1.2f * s).sp,
                    )
                    Spacer(modifier = Modifier.height((8f * s).dp))
                }
                Text(
                    text = level.name ?: "Подписка",
                    fontSize = (22f * s).sp,
                    lineHeight = (28f * s).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = MontserratFamily,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height((4f * s).dp))
                Text(
                    text = visual.audienceLine,
                    fontSize = (12f * s).sp,
                    lineHeight = (16f * s).sp,
                    fontFamily = MontserratFamily,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(
                    text = formatVolumeMonthlyLiters(level.volume),
                    fontSize = (15f * s).sp,
                    lineHeight = (20f * s).sp,
                    fontFamily = MontserratFamily,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height((4f * s).dp))
                Text(
                    text = formatPriceMonthly(level.price),
                    fontSize = (20f * s).sp,
                    lineHeight = (26f * s).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = MontserratFamily,
                    color = MintPrice,
                )
                Spacer(modifier = Modifier.height((14f * s).dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height((48f * s).dp)
                            .clip(RoundedCornerShape((12f * s).dp))
                            .background(if (recommended) VioletAccent else CtaUnselected)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClick,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ВЫБРАТЬ",
                        fontSize = (14f * s).sp,
                        lineHeight = (18f * s).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MontserratFamily,
                        letterSpacing = (1.1f * s).sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock(s: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size((40f * s).dp),
            color = VioletAccent,
            strokeWidth = (4f * s).dp,
        )
        Spacer(modifier = Modifier.height((16f * s).dp))
        Text(
            text = "Загрузка тарифов…",
            fontSize = (16f * s).sp,
            fontFamily = MontserratFamily,
            color = TextMuted,
        )
    }
}

@Composable
private fun ErrorBlock(
    s: Float,
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            fontSize = (16f * s).sp,
            fontFamily = MontserratFamily,
            textAlign = TextAlign.Center,
            color = Color(0xFFFF8A80),
            modifier = Modifier.padding(horizontal = (8f * s).dp),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height((18f * s).dp))
            RetryChip(s = s, onRetry = onRetry)
        }
    }
}

@Composable
private fun EmptyBlock(
    s: Float,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Нет доступных тарифов",
            fontSize = (18f * s).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MontserratFamily,
            textAlign = TextAlign.Center,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height((8f * s).dp))
        Text(
            text = "Попробуйте ещё раз или обратитесь к администратору",
            fontSize = (14f * s).sp,
            fontFamily = MontserratFamily,
            textAlign = TextAlign.Center,
            color = TextMuted,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height((18f * s).dp))
            RetryChip(s = s, onRetry = onRetry)
        }
    }
}

@Composable
private fun RetryChip(s: Float, onRetry: () -> Unit) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape((12f * s).dp))
                .background(VioletAccent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRetry,
                )
                .padding(horizontal = (22f * s).dp, vertical = (12f * s).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Повторить",
            fontSize = (15f * s).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MontserratFamily,
            color = Color.White,
        )
    }
}

/** `volume` с бэка — мл/мес для вкусовых напитков (12000 → «12 л напитков / мес»). */
private fun formatVolumeMonthlyLiters(volumeMl: Int?): String {
    if (volumeMl == null || volumeMl <= 0) return "Напитки / мес"
    val liters = volumeMl / 1000.0
    val whole = abs(liters % 1.0) < 1e-6
    val label = if (whole) liters.toInt().toString() else liters.toString()
    return "$label л напитков / мес · вода безлимитно"
}

private fun formatPriceRub(price: Double): String {
    val whole = abs(price % 1.0) < 1e-6
    return if (whole) "${price.toInt()} ₽" else "$price ₽"
}

private fun formatPriceMonthly(price: Double): String = "${formatPriceRub(price)} / мес"
