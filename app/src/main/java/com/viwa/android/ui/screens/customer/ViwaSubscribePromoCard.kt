package com.viwa.android.ui.screens.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viwa.android.ui.theme.LocalCustomerPrimaryButtonColor
import com.viwa.android.ui.theme.MontserratFamily

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
    val progressFraction = (state.subscriptionVolumeMl.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)

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
 // Figma: 16dp от верха/низа/слева до круговой шкалы; справа — как в SubscribeCard.module.scss
                    .padding(start = (16f * s).dp, end = (20f * s).dp, top = (16f * s).dp, bottom = (16f * s).dp),
 // space="l" в Consta = 24px между кругом и правым блоком
            horizontalArrangement = Arrangement.spacedBy((24f * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViwaSubscriptionVolumeCircle(
                s = s,
                currentVolumeMl = state.subscriptionVolumeMl,
                progressFraction = progressFraction,
                trackColor = palette.volumeTrack,
            )

 // justify="center" + space="xs" из Electron VerticalContainer (rightBlock)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy((8f * s).dp, Alignment.CenterVertically),
            ) {
                when (scenario) {
                    SubscribePromoScenario.Trial -> {
                        Text(
                            text = "Вам доступен бесплатный напиток!",
                            fontSize = (24f * s).sp,
                            lineHeight = (30f * s).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = MontserratFamily,
                            color = palette.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Стандартная вода — безлимитно",
                            fontSize = (14f * s).sp,
                            fontFamily = MontserratFamily,
                            color = palette.subtitle,
                        )
                        PromoActionRow(
                            s = s,
                            palette = palette,
                            primaryLabel = "Выгодная подписка тут",
                            onPrimaryClick = onOpenSubscriptionPurchase,
                            onDismiss = onDismiss,
                        )
                    }
                    SubscribePromoScenario.Active -> {
 // Группа заголовок + подзаголовок — space={0} из VerticalContainer в Electron
                        Column(verticalArrangement = Arrangement.spacedBy((2f * s).dp)) {
                            Text(
                                text = "Подписка активна",
                                fontSize = (24f * s).sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = MontserratFamily,
                                color = palette.title,
                            )
                            state.subscriptionEndDate?.let { end ->
                                Text(
                                    text = "Действует до ${formatEndDate(end)}",
                                    fontSize = (14f * s).sp,
                                    fontFamily = MontserratFamily,
                                    color = palette.subtitle,
                                )
                            }
                            Text(
                                text = "Вода безлимитно · остаток — для напитков",
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
                        )
                    }
                    SubscribePromoScenario.LimitExhausted -> {
                        Column(verticalArrangement = Arrangement.spacedBy((2f * s).dp)) {
                            Text(
                                text = "Подписка активна",
                                fontSize = (24f * s).sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = MontserratFamily,
                                color = palette.title,
                            )
                            Text(
                                text = "Лимит напитков исчерпан, обновится завтра",
                                fontSize = (14f * s).sp,
                                fontFamily = MontserratFamily,
                                color = palette.subtitle,
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
                        )
                    }
                    SubscribePromoScenario.Expired -> {
                        Column(verticalArrangement = Arrangement.spacedBy((2f * s).dp)) {
                            Text(
                                text = "Срок действия истёк",
                                fontSize = (24f * s).sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = MontserratFamily,
                                color = palette.title,
                            )
                            state.subscriptionEndDate?.let { end ->
                                Text(
                                    text = "Действует до ${formatEndDate(end)}",
                                    fontSize = (14f * s).sp,
                                    fontFamily = MontserratFamily,
 // --typo-status-alert при истёкшей подписке
                                    color = MaterialTheme.colorScheme.error,
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViwaSubscriptionVolumeCircle(
    s: Float,
    currentVolumeMl: Int,
    progressFraction: Float,
    trackColor: Color,
) {
    val primaryColor = LocalCustomerPrimaryButtonColor.current

    Box(
        modifier = Modifier.size((122f * s).dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size((122f * s).dp),
        ) {
            val strokeWidth = 12f * s
            drawCircle(
                color = trackColor,
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = 360f * progressFraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = currentVolumeMl.toString(),
 // $value-font-size: 28px в VolumeCircle.module.scss
                fontSize = (28f * s).sp,
                lineHeight = (28f * s).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MontserratFamily,
                color = primaryColor,
                textAlign = TextAlign.Center,
            )
 // margin-top: -4px из VolumeCircle.module.scss — плотное прилегание
            Text(
                text = "МЛ",
 // $unit-font-size: 26px в VolumeCircle.module.scss
                fontSize = (26f * s).sp,
                lineHeight = (26f * s).sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = MontserratFamily,
                color = primaryColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(y = (-4f * s).dp),
            )
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
 // space="xs" в Consta = 8px
        horizontalArrangement = Arrangement.spacedBy((8f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryActionButton(
            s = s,
            palette = palette,
            label = primaryLabel,
            onClick = onPrimaryClick,
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size((44f * s).dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                contentDescription = "Выход",
                tint = palette.exitIcon,
                modifier = Modifier.size((22f * s).dp),
            )
        }
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
