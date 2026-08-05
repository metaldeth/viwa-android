package com.viwa.android.ui.screens.customer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import com.viwa.android.R
import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.ui.components.QRCodeView

private val PaymentOptionEntranceSpring =
    spring<Float>(
        dampingRatio = 0.88f,
        stiffness = 360f,
    )

/** Figma Card `1549:292`: фиксированная высота тела модалки (выбор способа и СБП). */
private val PaymentModalBodyHeight = 496.dp

/** Внешний размер белой подложки QR. */
private val SbpQrSlotSize = 200.dp

/** Увеличенный QR основного combined-сценария оплаты напитка. */
private val CombinedSbpQrSlotSize = 260.dp

/** Равные отступы подложка → Canvas QR (тихая зона). */
private val SbpQrQuietPadding = 6.dp

/** Центральная марка СБП в QR (меньше доли сетки — проще считывание при том же ECC). */
private val SbpQrCenterLogoSize = 40.dp

/** Меню остаётся различимым под блокирующим стеклом combined-оплаты. */
private const val CombinedPaymentScrimAlpha = 0.78f

private data class PaymentSheetColors(
    val panelBg: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val cancelBorder: Color,
    val cancelLabel: Color,
)

@Composable
private fun rememberPaymentSheetColors(): PaymentSheetColors {
    val scheme = MaterialTheme.colorScheme
    val isLightUi = scheme.surface.luminance() > 0.5f
    return remember(scheme) {
        PaymentSheetColors(
            panelBg =
                if (isLightUi) {
                    ViwaCustomerUiTokens.PaymentModalSurface
                } else {
                    scheme.surface
                },
            primaryText =
                if (isLightUi) {
                    ViwaCustomerUiTokens.TypoMainPrimaryLight
                } else {
                    ViwaCustomerUiTokens.TypoMainPrimaryDark
                },
            secondaryText =
                if (isLightUi) {
                    ViwaCustomerUiTokens.TypoMainPrimaryLight.copy(alpha = 0.75f)
                } else {
                    scheme.onSurfaceVariant
                },
            mutedText =
                if (isLightUi) {
                    ViwaCustomerUiTokens.TypoMainPrimaryLight.copy(alpha = 0.65f)
                } else {
                    scheme.onSurfaceVariant.copy(alpha = 0.85f)
                },
            cancelBorder =
                if (isLightUi) {
                    ViwaCustomerUiTokens.PaymentCancelBorderAndText
                } else {
                    scheme.outline
                },
            cancelLabel =
                if (isLightUi) {
                    ViwaCustomerUiTokens.PaymentCancelBorderAndText
                } else {
                    scheme.onSurfaceVariant
                },
        )
    }
}

/** «Глубина» шага: корень — выбор способа, далее вложенные экраны (аналог смены step в `PaymentModal.tsx`). */
private fun PaymentSheetStep.contentDepth(): Int =
    when (this) {
        PaymentSheetStep.MethodChoice -> 0
        PaymentSheetStep.Combined -> 1
        else -> 1
    }

private fun AnimatedContentTransitionScope<PaymentSheetStep>.paymentStepContentTransform(): ContentTransform {
    val sameDepth = targetState.contentDepth() == initialState.contentDepth()
    val forward = targetState.contentDepth() > initialState.contentDepth()
    return if (sameDepth) {
        fadeIn(tween(220)) togetherWith fadeOut(tween(200))
    } else if (forward) {
        (fadeIn(tween(240)) +
            slideInVertically(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                initialOffsetY = { h -> (h * 0.12f).roundToInt() },
            )).togetherWith(
            fadeOut(tween(200)) +
                slideOutVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    targetOffsetY = { h -> (-h * 0.08f).roundToInt() },
                ),
        )
    } else {
        (fadeIn(tween(240)) +
            slideInVertically(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                initialOffsetY = { h -> (-h * 0.12f).roundToInt() },
            )).togetherWith(
            fadeOut(tween(200)) +
                slideOutVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    targetOffsetY = { h -> (h * 0.08f).roundToInt() },
                ),
        )
    }
}

/**
 *.tsx` + макет Figma (node 1549:191 / Card 1549:292): выбор способа, шаг card/sbp.
 * Тексты шага СБП — как `PaymentSBPContent.tsx` (заголовок сканирования, сумма под QR).
 * Анимация появления/скрытия — как `BaseSlideModal` (direction top).
 * Переходы между шагами (способ → карта / СБП / подписка) — `AnimatedContent`, в духе смены контента в `PaymentModal.tsx`.
 * Логика оплаты остаётся во [DrinkListViewModel].
 */
@Composable
fun CustomerPaymentSheet(
    visible: Boolean,
    step: PaymentSheetStep,
    priceRub: Int,
    terminalBanner: String,
    cardPaymentUiStatus: CardPaymentUiStatus,
    paymentError: String?,
    isProcessing: Boolean,
    combinedPaymentConfirmed: Boolean = false,
    sbpLink: SBPLink?,
    sbpStatus: SBPStatus,
    sbpRemainingSeconds: Int,
    sbpLoading: Boolean,
    receiptUrl: String?,
    receiptLoading: Boolean,
    receiptError: String?,
    receiptRemainingSeconds: Int,
    onDismiss: () -> Unit,
    onCombinedRecoveryToMenu: () -> Unit = {},
    onChooseSbp: () -> Unit,
    onChooseCard: () -> Unit,
 /** При включённом режиме разработки: налив без СБП/карты (старый fast-path). */
    onDevPourWithoutPay: (() -> Unit)? = null,
    onBackToMethods: () -> Unit,
    onRetrySbp: () -> Unit = {},
) {
    if (step == PaymentSheetStep.Combined) {
        CombinedDrinkPaymentOverlay(
            visible = visible,
            priceRub = priceRub,
            cardStatus = cardPaymentUiStatus,
            sbpLink = sbpLink,
            sbpLoading = sbpLoading,
            sbpRemainingSeconds = sbpRemainingSeconds,
            paymentError = paymentError,
            combinedPaymentConfirmed = combinedPaymentConfirmed,
            cancelEnabled = !combinedPaymentConfirmed,
            onCancel = onDismiss,
            onRecoveryToMenu = onCombinedRecoveryToMenu,
        )
        return
    }

    val transition = updateTransition(targetState = visible, label = "CustomerPaymentSheet")
    if (transition.currentState || transition.targetState) {
        BackHandler(enabled = visible, onBack = onDismiss)
        val scrimBase = ViwaCustomerUiTokens.PaymentModalScrim
        val scrimAlphaMultiplier by transition.animateFloat(
            transitionSpec = { tween(durationMillis = 220, delayMillis = 40) },
            label = "paymentScrimAlpha",
        ) { shown -> if (shown) 1f else 0f }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(scrimBase.copy(alpha = scrimBase.alpha * scrimAlphaMultiplier)),
                )
                val panelHorizontalMargin = 40.dp
                val maxPanelWidth = 560.dp
                val availableWidth = (maxWidth - panelHorizontalMargin * 2).coerceAtLeast(0.dp)
                val panelWidth: Dp = minOf(maxPanelWidth, availableWidth)
                val paymentColors = rememberPaymentSheetColors()

                transition.AnimatedVisibility(
                    visible = { it },
                    enter =
                        fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) +
                            slideInVertically(
                                animationSpec = tween(durationMillis = 300, delayMillis = 40),
                                initialOffsetY = { fullHeight -> -fullHeight },
                            ),
                    exit =
                        fadeOut(animationSpec = tween(durationMillis = 180)) +
                            slideOutVertically(
                                animationSpec = tween(durationMillis = 260),
                                targetOffsetY = { fullHeight -> -fullHeight },
                            ),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Surface(
                        modifier =
                            Modifier
                                .padding(horizontal = panelHorizontalMargin)
                                .width(panelWidth)
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
                        color = paymentColors.panelBg,
                        shadowElevation = 4.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AnimatedContent(
                                targetState = step,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = { paymentStepContentTransform() },
                                label = "paymentSheetStep",
                            ) { currentStep ->
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = PaymentModalBodyHeight),
                                ) {
                                    when (currentStep) {
                            PaymentSheetStep.Combined -> Unit
                            PaymentSheetStep.MethodChoice -> {
                                val sbpEntranceProgress = remember { Animatable(0f) }
                                val cardEntranceProgress = remember { Animatable(0f) }
                                LaunchedEffect(currentStep) {
                                    sbpEntranceProgress.snapTo(0f)
                                    cardEntranceProgress.snapTo(0f)
                                    sbpEntranceProgress.animateTo(1f, PaymentOptionEntranceSpring)
                                    cardEntranceProgress.animateTo(1f, PaymentOptionEntranceSpring)
                                }

                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(PaymentModalBodyHeight),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "Выберите\nспособ оплаты",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 32.sp,
                                            lineHeight = 38.sp,
                                            color = paymentColors.primaryText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 28.dp),
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        ) {
                                            PaymentMethodTile(
                                                modifier = Modifier.weight(1f),
                                                backgroundColor = ViwaCustomerUiTokens.PaymentTileSbpBg,
                                                iconRes = R.drawable.ic_payment_tile_sbp,
                                                line1 = "Оплата",
                                                line2 = "СБП",
                                                entranceProgress = sbpEntranceProgress.value,
                                                onClick = onChooseSbp,
                                            )
                                            PaymentMethodTile(
                                                modifier = Modifier.weight(1f),
                                                backgroundColor = ViwaCustomerUiTokens.PaymentTileCardBg,
                                                iconRes = R.drawable.ic_payment_tile_card,
                                                line1 = "Оплата",
                                                line2 = "Картой",
                                                entranceProgress = cardEntranceProgress.value,
                                                onClick = onChooseCard,
                                            )
                                        }
                                    }
                                    if (onDevPourWithoutPay != null) {
                                        Spacer(Modifier.height(12.dp))
                                        TextButton(onClick = onDevPourWithoutPay) {
                                            Text(
                                                text = "Налить без оплаты (разработка)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = paymentColors.mutedText,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(28.dp))
                                    PaymentModalWideCancelButton(
                                        text = "Отмена",
                                        onClick = onDismiss,
                                        borderColor = paymentColors.cancelBorder,
                                        labelColor = paymentColors.cancelLabel,
                                    )
                                }
                            }
                            PaymentSheetStep.Card -> {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(PaymentModalBodyHeight),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = "$priceRub ₽",
                                            style = MaterialTheme.typography.displaySmall,
                                            color = paymentColors.primaryText,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        Text(
                                            text = "Следуйте инструкциям терминала",
                                            style = MaterialTheme.typography.bodyLarge,
                                            textAlign = TextAlign.Center,
                                            color = paymentColors.secondaryText,
                                        )
                                        if (terminalBanner.isNotBlank()) {
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = terminalBanner,
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        paymentError?.let { err ->
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                        Spacer(Modifier.height(20.dp))
                                        if (isProcessing) {
                                            CircularProgressIndicator(Modifier.size(40.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(28.dp))
                                    PaymentModalSecondaryButton(
                                        text = "Назад",
                                        enabled = !isProcessing,
                                        onClick = onBackToMethods,
                                        borderColor = paymentColors.cancelBorder,
                                        labelColor = paymentColors.cancelLabel,
                                    )
                                }
                            }
                            PaymentSheetStep.Sbp -> {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(PaymentModalBodyHeight),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "Отсканируйте QR-код\nдля оплаты",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 32.sp,
                                            lineHeight = 38.sp,
                                            color = paymentColors.primaryText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 14.dp),
                                        )
                                        SbpQrSlot(
                                            sbpLoading = sbpLoading,
                                            sbpLink = sbpLink,
                                            paymentError = paymentError,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = "$priceRub ₽",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 34.sp,
                                            lineHeight = 38.sp,
                                            textAlign = TextAlign.Center,
                                            color = paymentColors.primaryText,
                                        )
                                        if (sbpLink != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = "Осталось: ${formatCountdown(sbpRemainingSeconds)}",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 17.sp,
                                                lineHeight = 22.sp,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = sbpStatusText(sbpStatus),
                                                style = MaterialTheme.typography.bodySmall,
                                                lineHeight = 18.sp,
                                                textAlign = TextAlign.Center,
                                                color = paymentColors.mutedText,
                                            )
                                        }
                                        paymentError?.let { err ->
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                        if (paymentError != null && !sbpLoading && !isProcessing) {
                                            Spacer(Modifier.height(16.dp))
                                            PaymentModalWideCancelButton(
                                                text = "Повторить",
                                                onClick = onRetrySbp,
                                                borderColor = MaterialTheme.colorScheme.primary,
                                                labelColor = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(28.dp))
                                    PaymentModalWideCancelButton(
                                        text = "Отмена",
                                        onClick = onBackToMethods,
                                        enabled = !isProcessing,
                                        borderColor = paymentColors.cancelBorder,
                                        labelColor = paymentColors.cancelLabel,
                                    )
                                }
                            }
                            PaymentSheetStep.Subscription -> {
                                val subSbpProgress = remember { Animatable(0f) }
                                val subCardProgress = remember { Animatable(0f) }
                                LaunchedEffect(currentStep) {
                                    subSbpProgress.snapTo(0f)
                                    subCardProgress.snapTo(0f)
                                    subSbpProgress.animateTo(1f, PaymentOptionEntranceSpring)
                                    subCardProgress.animateTo(1f, PaymentOptionEntranceSpring)
                                }
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(PaymentModalBodyHeight),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "Покупка подписки",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 32.sp,
                                            lineHeight = 38.sp,
                                            color = paymentColors.primaryText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 14.dp),
                                        )
                                        Text(
                                            text = "$priceRub ₽",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 34.sp,
                                            lineHeight = 38.sp,
                                            textAlign = TextAlign.Center,
                                            color = paymentColors.primaryText,
                                        )
                                        Spacer(Modifier.height(20.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        ) {
                                            PaymentMethodTile(
                                                modifier = Modifier.weight(1f),
                                                backgroundColor = ViwaCustomerUiTokens.PaymentTileSbpBg,
                                                iconRes = R.drawable.ic_payment_tile_sbp,
                                                line1 = "Оплата",
                                                line2 = "СБП",
                                                entranceProgress = subSbpProgress.value,
                                                onClick = onChooseSbp,
                                            )
                                            PaymentMethodTile(
                                                modifier = Modifier.weight(1f),
                                                backgroundColor = ViwaCustomerUiTokens.PaymentTileCardBg,
                                                iconRes = R.drawable.ic_payment_tile_card,
                                                line1 = "Оплата",
                                                line2 = "Картой",
                                                entranceProgress = subCardProgress.value,
                                                onClick = onChooseCard,
                                            )
                                        }
                                        paymentError?.let { err ->
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = err,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                        if (isProcessing) {
                                            Spacer(Modifier.height(16.dp))
                                            CircularProgressIndicator(Modifier.size(40.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(28.dp))
                                    PaymentModalWideCancelButton(
                                        text = "Назад",
                                        onClick = onBackToMethods,
                                        enabled = !isProcessing,
                                        borderColor = paymentColors.cancelBorder,
                                        labelColor = paymentColors.cancelLabel,
                                    )
                                }
                            }
                            PaymentSheetStep.SubscriptionReceipt -> {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(PaymentModalBodyHeight),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "Подписка оплачена",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 32.sp,
                                            lineHeight = 38.sp,
                                            color = paymentColors.primaryText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 12.dp),
                                        )
                                        Text(
                                            text = "Отсканируйте QR,\nчтобы открыть чек",
                                            style = MaterialTheme.typography.bodyLarge,
                                            textAlign = TextAlign.Center,
                                            color = paymentColors.secondaryText,
                                            modifier = Modifier.padding(bottom = 16.dp),
                                        )
                                        ReceiptQrSlot(
                                            receiptLoading = receiptLoading,
                                            receiptUrl = receiptUrl,
                                            receiptError = receiptError,
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "Окно закроется через ${formatCountdown(receiptRemainingSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            lineHeight = 18.sp,
                                            textAlign = TextAlign.Center,
                                            color = paymentColors.mutedText,
                                        )
                                    }
                                    Spacer(Modifier.height(28.dp))
                                    PaymentModalWideCancelButton(
                                        text = "Закрыть",
                                        onClick = onDismiss,
                                        borderColor = paymentColors.cancelBorder,
                                        labelColor = paymentColors.cancelLabel,
                                    )
                                }
                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
}

/**
 * Полноэкранный блокирующий overlay: QR СБП по центру, таймер, карточный статус у терминала (слева снизу).
 */
@Composable
private fun CombinedDrinkPaymentOverlay(
    visible: Boolean,
    priceRub: Int,
    cardStatus: CardPaymentUiStatus,
    sbpLink: SBPLink?,
    sbpLoading: Boolean,
    sbpRemainingSeconds: Int,
    paymentError: String?,
    combinedPaymentConfirmed: Boolean,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
    onRecoveryToMenu: () -> Unit,
) {
    val transition = updateTransition(targetState = visible, label = "CombinedDrinkPaymentOverlay")
    if (!transition.currentState && !transition.targetState) return

    val recoveryMode = combinedPaymentConfirmed && !paymentError.isNullOrBlank()

    BackHandler(enabled = visible && cancelEnabled && !recoveryMode, onBack = onCancel)

    val scrimBase = ViwaCustomerUiTokens.PaymentModalScrim
    val scrimAlphaMultiplier by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 220) },
        label = "combinedScrimAlpha",
    ) { shown -> if (shown) 1f else 0f }

    val paymentColors = rememberPaymentSheetColors()

    transition.AnimatedVisibility(
        visible = { it },
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { paneTitle = "Оплата напитка" },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            scrimBase.copy(
                                alpha = CombinedPaymentScrimAlpha * scrimAlphaMultiplier,
                            ),
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$priceRub ₽",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    color = paymentColors.primaryText,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (recoveryMode) {
                    Text(
                        text = paymentError.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    SbpQrSlot(
                        sbpLoading = sbpLoading,
                        sbpLink = sbpLink,
                        paymentError = if (sbpLink == null && !sbpLoading) paymentError else null,
                        size = CombinedSbpQrSlotSize,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = formatCountdown(sbpRemainingSeconds),
                        fontWeight = FontWeight.Medium,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    "Осталось ${formatCountdown(sbpRemainingSeconds)}"
                            },
                    )
                }
                if (!recoveryMode) {
                    val centerPaymentError =
                        paymentError?.takeIf { sbpLink != null && !sbpLoading }
                    centerPaymentError?.let { err ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 28.dp, bottom = 96.dp)
                        .widthIn(max = 360.dp)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = cardStatus.label
                        },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SouthWest,
                    contentDescription = null,
                    tint = paymentColors.secondaryText,
                    modifier = Modifier.size(28.dp),
                )
                AnimatedContent(
                    targetState = cardStatus,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "combinedCardStatus",
                ) { status ->
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        color = paymentColors.primaryText,
                    )
                }
            }

            if (recoveryMode) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextButton(onClick = onRecoveryToMenu) {
                        Text(
                            text = "Вернуться в меню",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = paymentColors.primaryText,
                        )
                    }
                }
            } else {
                TextButton(
                    onClick = onCancel,
                    enabled = cancelEnabled,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 20.dp, end = 28.dp),
                ) {
                    Text(
                        text = "Отмена",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = paymentColors.cancelLabel.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptQrSlot(
    receiptLoading: Boolean,
    receiptUrl: String?,
    receiptError: String?,
) {
    Surface(
        modifier = Modifier.size(SbpQrSlotSize),
        shape = RoundedCornerShape(12.dp),
        color = ViwaCustomerUiTokens.SbpQrBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(SbpQrQuietPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                receiptLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                    )
                }
                !receiptUrl.isNullOrBlank() -> {
                    QRCodeView(
                        data = receiptUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                !receiptError.isNullOrBlank() -> {
                    Text(
                        text = receiptError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                else -> {
                    Text(
                        text = "Чек формируется",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** Figma `1549:309`: 80×504, radius 20, border 1px; в тёмной теме — [borderColor]/[labelColor] из темы. */
@Composable
private fun PaymentModalWideCancelButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    borderColor: Color = ViwaCustomerUiTokens.PaymentCancelBorderAndText,
    labelColor: Color = ViwaCustomerUiTokens.PaymentCancelBorderAndText,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            color = labelColor,
        )
    }
}

@Composable
private fun SbpQrSlot(
    sbpLoading: Boolean,
    sbpLink: SBPLink?,
    paymentError: String?,
    size: Dp = SbpQrSlotSize,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = ViwaCustomerUiTokens.SbpQrBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(SbpQrQuietPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                sbpLoading || (sbpLink == null && paymentError == null) -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                    )
                }
                sbpLink != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        QRCodeView(
                            data = sbpLink.qrData,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                color = ViwaCustomerUiTokens.SbpQrBackground,
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_sbp_icon),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .padding(4.dp)
                                            .size(SbpQrCenterLogoSize),
                                )
                            }
                        }
                    }
                }
                paymentError != null -> {
                    Text(
                        text = "Не удалось получить QR",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun PaymentModalSecondaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    borderColor: Color = ViwaCustomerUiTokens.PaymentCancelBorderAndText,
    labelColor: Color = ViwaCustomerUiTokens.PaymentCancelBorderAndText,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = labelColor,
        )
    }
}

private fun formatCountdown(seconds: Int): String {
    val m = seconds.coerceAtLeast(0) / 60
    val s = seconds.coerceAtLeast(0) % 60
    return "%02d:%02d".format(m, s)
}

private fun sbpStatusText(status: SBPStatus): String =
    when (status) {
        SBPStatus.Pending -> "Ожидаем подтверждения оплаты"
        SBPStatus.Success -> "Оплата подтверждена"
        SBPStatus.Cancelled -> "Оплата отменена"
        is SBPStatus.Failed -> "Ошибка: ${status.reason}"
    }

@Composable
private fun PaymentMethodTile(
    backgroundColor: Color,
    iconRes: Int,
    line1: String,
    line2: String,
    entranceProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(228.dp)
                .graphicsLayer {
                    alpha = entranceProgress
                    scaleX = 0.92f + (0.08f * entranceProgress)
                    scaleY = 0.92f + (0.08f * entranceProgress)
                    transformOrigin = TransformOrigin.Center
                }
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .background(backgroundColor),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(width = 100.dp, height = 122.dp),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 20.dp, end = 8.dp),
        ) {
            Text(
                text = line1,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                color = ViwaCustomerUiTokens.PaymentTileLabel,
            )
            Text(
                text = line2,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                color = ViwaCustomerUiTokens.PaymentTileLabel,
            )
        }
    }
}
