@file:OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.viwa.android.ui.screens.customer

import androidx.annotation.OptIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.floor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.R
import com.viwa.android.domain.model.customer.isUnavailable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.ui.text.font.FontFamily
import com.viwa.android.data.network.NetworkTrafficChannel
import com.viwa.android.data.network.NetworkTrafficDirection
import com.viwa.android.hardware.controller.ControllerTrafficDirection
import com.viwa.android.ui.components.ServiceMenuTrigger
import com.viwa.android.ui.components.ServicePasswordScreen
import com.viwa.android.ui.system.DialogWindowImmersiveSideEffect
import com.viwa.android.ui.theme.MontserratFamily
import kotlin.math.abs
import kotlin.math.min

private val MediaKeyHexOverride: Map<String, String> =
    mapOf(
        "strawberry-lemongrass" to "#F44336",
        "lime" to "#63E515",
        "lemon" to "#D7D717",
        "peach-mango" to "#E5AE8A",
        "coconut" to "#74543D",
    )

/** Центр бегунка для дискретного шага `ordinal` (0.2) по ширине трека. */
private fun concentrationThumbCenterX(ordinal: Int, widthPx: Float, thumbPx: Float): Float =
    thumbPx / 2f + (ordinal / 2f) * (widthPx - thumbPx)

/** Доля 0.1: центр бегунка следует за X пальца, в пределах хода бегунка. */
private fun thumbFractionFromFingerX(x: Float, widthPx: Float, thumbPx: Float): Float {
    if (widthPx <= thumbPx) return 0.5f
    val minCx = thumbPx / 2f
    val maxCx = widthPx - thumbPx / 2f
    val cx = x.coerceIn(minCx, maxCx)
    return (cx - minCx) / (maxCx - minCx)
}

/** Ближайший шаг по X (snap при отпускании / тапе). */
private fun nearestConcentrationOrdinal(x: Float, widthPx: Float, thumbPx: Float): Int {
    var best = 0
    var bestDist = abs(x - concentrationThumbCenterX(0, widthPx, thumbPx))
    for (i in 1..2) {
        val d = abs(x - concentrationThumbCenterX(i, widthPx, thumbPx))
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

private fun DrinkConcentration.ruStateDescription(): String =
    when (this) {
        DrinkConcentration.Weak -> "Слабая концентрация"
        DrinkConcentration.Standard -> "Стандартная концентрация"
        DrinkConcentration.Strong -> "Крепкая концентрация"
    }

@Composable
fun DrinkListScreen(
    viewModel: DrinkListViewModel,
    onOpenService: () -> Unit,
    onOpenFreeDrinkOffer: () -> Unit,
    onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    onHomeIdleBlockingChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocksIdleOverlay = state.blocksIdleVideoOverlay()
    val onHomeIdleBlockingChangedState by rememberUpdatedState(onHomeIdleBlockingChanged)
    LaunchedEffect(blocksIdleOverlay) {
        onHomeIdleBlockingChangedState(blocksIdleOverlay)
    }
 /** Одна бутылка = 0,5 л (500 мл). */
    val bottlesSavedCount =
        remember(state.accumulatedWaterMl) {
            floor(state.accumulatedWaterMl.coerceAtLeast(0.0) / 500.0).toInt()
        }
    var showPasswordDialog by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshFlags()
                }
            }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val hasSelection = state.activeContainer != null && state.selectedVolumeMl != null
    val unavailable = state.activeContainer?.isUnavailable() == true
    val priceRub =
        if (hasSelection) {
            state.activeContainer!!.product.dPrices
                .firstOrNull { it.volume == state.selectedVolumeMl }
                ?.priceRub ?: 0
        } else {
            0
        }

 /** Остаток по карте лояльности покрывает объём: платная подписка или бесплатный литр (без isActiveSubscribe). */
    val selectedVol = state.selectedVolumeMl
    val subscriptionVolumeEnough =
        state.scannedSubscriptionClientId != null &&
            hasSelection &&
            !unavailable &&
            selectedVol != null &&
            state.subscriptionVolumeMl >= selectedVol

    val primaryLabel =
        when {
            !hasSelection -> "Налить воду"
            unavailable -> "Временно недоступен"
            subscriptionVolumeEnough -> "Приготовить"
            else -> "Оплатить $priceRub ₽"
        }

    val primaryEnabled =
        if (hasSelection) {
            !unavailable && !state.isProcessingPay
        } else {
            !state.isProcessingPay
        }

    var waterPourFingerDown by remember { mutableStateOf(false) }

    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val screenBg = MaterialTheme.colorScheme.background
 /** Как `useDeselectDrinkOnClickOutside`. */
    val deselectBackdropInteraction = remember { MutableInteractionSource() }

    val subscriptionClientId = state.scannedSubscriptionClientId

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(subscriptionClientId) {
                    if (subscriptionClientId == null) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.changedToDown() }) {
                                viewModel.resetSubscriptionExitTimer()
                            }
                        }
                    }
                }
                .drawBehind {
                    drawRect(screenBg)
                    if (!isLight) {
                        val cy = size.height * 1.15f
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors = listOf(ViwaCustomerUiTokens.VignetteCenter, Color.Transparent),
                                    center = Offset(size.width / 2f, cy),
                                    radius = size.maxDimension * 0.85f,
                                ),
                            center = Offset(size.width / 2f, cy),
                            radius = size.maxDimension * 0.85f,
                        )
                    }
                },
    ) {
        val s = min(maxWidth / 1024.dp, maxHeight / 768.dp)

        fun Float.epx(): Dp = (this * s).dp

 // Слой под колонкой контента: не перехватывает тапы по карточкам/шапке/низу (они выше по z-order).
        if (hasSelection && !state.paymentSheetVisible && !showPasswordDialog) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = deselectBackdropInteraction,
                            indication = null,
                            onClick = { viewModel.clearSelection() },
                        ),
            )
        }

        Column(
            modifier =
                Modifier
                    .widthIn(max = 944f.epx())
                    .align(Alignment.TopCenter)
                    .fillMaxHeight(),
 // Electron: space="3xl" = 32px между секциями. Header без отступа сверху (top=0).
            verticalArrangement = Arrangement.spacedBy(32f.epx()),
        ) {
 // --- Зона 1: topSection — HeaderAction + пилюля «Выберите напиток» ---
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                contentAlignment = Alignment.Center,
            ) {
                HeaderActionStrip(
                    // Вода (холодная/стандарт/газ) — после выбора напитка или скана карты.
                    // Объём 300/700 — только для выбранного напитка.
                    // Premium (холод/газ): при напитке всегда; при только карте — нужна активная подписка.
                    waterEnabled =
                        state.activeContainer != null ||
                            state.scannedSubscriptionClientId != null,
                    premiumWaterEnabled =
                        state.activeContainer != null || state.isSubscriptionActive,
                    volumeEnabled = state.activeContainer != null,
                    selectedVolumeMl = state.selectedVolumeMl,
                    waterOption = state.waterOption,
                    onVolume = viewModel::setVolume,
                    onWater = viewModel::setWater,
                    s = s,
                )
                androidx.compose.animation.AnimatedVisibility(
                    // Не перекрываем верхнюю панель после скана карты — тип воды выбирается там.
                    visible =
                        state.activeContainer == null &&
                            state.scannedSubscriptionClientId == null,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(40f.epx()))
                                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f))
                                .padding(horizontal = 28f.epx(), vertical = 10f.epx()),
                    ) {
                        Text(
                            "Выберите напиток",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = (15f * s).sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

 // --- Зона 2: drinksSection — 332px по electron, сетка 3×2 ---
            Column(
                modifier = Modifier.fillMaxWidth().height(332f.epx()),
                verticalArrangement = Arrangement.spacedBy(20f.epx()),
            ) {
                val row1 = remember(state.containers) { state.containers.take(3) }
                val row2 = remember(state.containers) { state.containers.drop(3).take(3) }
                val activeContainerNumber = state.activeContainer?.containerNumber
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20f.epx(), Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row1.forEach { c ->
                        key(c.containerNumber) {
                            val onSelect = remember(c.containerNumber) {
                                { viewModel.selectContainerByNumber(c.containerNumber) }
                            }
                            ViwaDrinkCard(
                                container = c,
                                isActive = activeContainerNumber == c.containerNumber,
                                selectedVolumeMl = state.selectedVolumeMl,
                                concentration = state.concentration,
                                onConcentrationChange = viewModel::setConcentration,
                                onClick = onSelect,
                                s = s,
                            )
                        }
                    }
                }
                if (row2.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20f.epx(), Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row2.forEach { c ->
                            key(c.containerNumber) {
                                val onSelect = remember(c.containerNumber) {
                                    { viewModel.selectContainerByNumber(c.containerNumber) }
                                }
                                ViwaDrinkCard(
                                    container = c,
                                    isActive = activeContainerNumber == c.containerNumber,
                                    selectedVolumeMl = state.selectedVolumeMl,
                                    concentration = state.concentration,
                                    onConcentrationChange = viewModel::setConcentration,
                                    onClick = onSelect,
                                    s = s,
                                )
                            }
                        }
                    }
                }
            }

 // --- Зона 3: bottomSection — 274px по electron; +высота под селектор типа воды при карте ---
            val bottomSectionHeightDp =
                if (!hasSelection && state.scannedSubscriptionClientId != null) {
                    318f
                } else {
                    274f
                }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(bottomSectionHeightDp.epx()),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(40f.epx(), Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ViwaStatsPlaceholder(
                        s = s,
                        bottlesSavedCount = bottlesSavedCount,
                        telemetryWsConnected = state.telemetryWsConnected,
                        temperature0C = state.temperature0C,
                        temperature1C = state.temperature1C,
                    )
 // CenterBlock: 944-158-158-40-40 = 548px (flex:1 из electron VerticalContainer isAutoWidth)
                    Column(
                        modifier = Modifier.width(548f.epx()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val showSubscriptionPromo =
                            state.scannedSubscriptionClientId != null || state.isSubscriptionActive
                        if (showSubscriptionPromo) {
                            ViwaSubscribePromoCard(
                                s = s,
                                state = state,
                                onDismiss = { viewModel.dismissSubscriptionCard() },
                            )
                        } else {
                            ViwaPromoCard(s = s, onClick = onOpenFreeDrinkOffer)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Тип воды после скана карты выбирается в верхней панели (HeaderActionStrip).
                            Box(
                                modifier =
                                    Modifier.then(
                                        if (!hasSelection) {
                                            Modifier.pointerInput(Unit) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false)
                                                    waterPourFingerDown = true
                                                    viewModel.waterPourPointerDown()
                                                    try {
                                                        waitForUpOrCancellation()
                                                    } finally {
                                                        waterPourFingerDown = false
                                                        viewModel.waterPourPointerUp()
                                                    }
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ViwaPrimaryActionBar(
                                    label = primaryLabel,
                                    enabled = primaryEnabled,
                                    loading = state.isProcessingPay,
 // В покое анимаций нет: эффект включается только при удержании кнопки.
                                    pulseHint = !hasSelection && (waterPourFingerDown || state.isWaterPourActive),
                                    pulseStyle = state.primaryButtonPulseStyle,
                                    waterPourMode = !hasSelection,
                                    pressDampen = waterPourFingerDown || state.isWaterPourActive,
                                    onClick = { viewModel.primaryAction(onNavigateToPreparing) },
                                    s = s,
                                )
                                if (!hasSelection &&
                                    (state.waterPourLimitBanner || state.waterPourError != null)
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8f.epx()),
                                    ) {
                                        if (state.waterPourLimitBanner) {
                                            Text(
                                                "30 секунд исчерпано",
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = (13f * s).sp,
                                            )
                                        }
                                        state.waterPourError?.let { err ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8f.epx()),
                                            ) {
                                                Text(
                                                    err,
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = (13f * s).sp,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                TextButton(onClick = { viewModel.clearWaterPourError() }) {
                                                    Text("OK", fontSize = (12f * s).sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                    ViwaScannerHintPlaceholder(s = s)
                }

                if (hasSelection) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text("Сброс", fontSize = (12f * s).sp)
                        }
                    }
                }

                if (!state.useMockController) {
                    Text(
                        "Контроллер не в режиме мока — включите «Мок контроллера» в сервисном меню.",
                        fontSize = (10f * s).sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.flowBanner?.let { msg ->
                    Text(
                        msg,
                        fontSize = (11f * s).sp,
                        color =
                            if (state.flowBannerIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val machineUnavailable = !state.controllerAvailable || state.containers.isEmpty()
        if (machineUnavailable) {
            val subtitle =
                when {
                    !state.controllerAvailable ->
                        "Контроллер не подключён.\nОбратитесь к обслуживающему персоналу."
                    else ->
                        "Данные о напитках не загружены.\nОжидание синхронизации с сервером."
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy((20f * s).dp),
                    modifier = Modifier.padding(horizontal = (48f * s).dp),
                ) {
                    Text(
                        "Автомат временно недоступен",
                        fontSize = (32f * s).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        fontFamily = MontserratFamily,
                    )
                    Text(
                        subtitle,
                        fontSize = (16f * s).sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        fontFamily = MontserratFamily,
                    )
                }
            }
        }

        ServiceMenuTrigger(
            onActivate = { showPasswordDialog = true },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        if (showPasswordDialog) {
            ServicePasswordScreen(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .zIndex(50f),
                onDismiss = { showPasswordDialog = false },
                onSuccess = {
                    showPasswordDialog = false
                    onOpenService()
                },
            )
        }

        if (state.invalidSubscriptionCardVisible) {
            InvalidSubscriptionCardDialog(
                onDismiss = { viewModel.dismissInvalidSubscriptionCardDialog() },
            )
        }

        if (state.subscriptionDebugEnabled) {
            SubscriptionDebugFab(viewModel = viewModel, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }

        CustomerPaymentSheet(
                visible = state.paymentSheetVisible,
                step = state.paymentSheetStep,
                priceRub = priceRub,
                terminalBanner = state.paymentTerminalBanner,
                cardPaymentUiStatus = state.cardPaymentUiStatus,
                paymentError = state.paymentError,
                isProcessing = state.isProcessingPay,
                combinedPaymentConfirmed = state.combinedPaymentConfirmed,
                sbpLink = state.sbpLink,
                sbpStatus = state.sbpStatus,
                sbpRemainingSeconds = state.sbpRemainingSeconds,
                sbpLoading = state.isSbpLoading,
                onDismiss = { viewModel.dismissPaymentSheet() },
                onCombinedRecoveryToMenu = { viewModel.exitCombinedPaymentRecoveryToMenu() },
                onChooseSbp = {
                    if (state.paymentSheetStep != PaymentSheetStep.Combined) {
                        viewModel.openSbpStep(onNavigateToPreparing)
                    }
                },
                onChooseCard = {
                    if (state.paymentSheetStep != PaymentSheetStep.Combined) {
                        viewModel.startCardPayment(onNavigateToPreparing)
                    }
                },
                onDevPourWithoutPay =
                    if (state.freeMode &&
                        !state.combinedPaymentConfirmed &&
                        state.scannedSubscriptionClientId.isNullOrBlank()
                    ) {
                        { viewModel.devPourWithoutPayment(onNavigateToPreparing) }
                    } else {
                        null
                    },
                onBackToMethods = { viewModel.backToPaymentMethods() },
                onRetrySbp = { viewModel.retrySbpPayment(onNavigateToPreparing) },
            )
    }
}

// ─── Debug FAB + модалка ─────────────────────────────────────────────────────

@Composable
private fun SubscriptionDebugFab(viewModel: DrinkListViewModel, modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }

    FloatingActionButton(
        onClick = { showDialog = true },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Icon(Icons.Rounded.BugReport, contentDescription = "Debug логи")
    }

    if (showDialog) {
        SubscriptionDebugLogsDialog(viewModel = viewModel, onDismiss = { showDialog = false })
    }
}

@Composable
private fun SubscriptionDebugLogsDialog(viewModel: DrinkListViewModel, onDismiss: () -> Unit) {
    val wsEntries by viewModel.networkTrafficFlow.collectAsStateWithLifecycle()
    val ctrlEntries by viewModel.controllerTrafficFlow.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
        },
        title = { Text("Debug логи", style = MaterialTheme.typography.titleMedium) },
        text = {
            DialogWindowImmersiveSideEffect()
            Column(modifier = Modifier.fillMaxWidth()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) {
                        Text("WS / HTTP", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("Контроллер", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> {
                        val reversed = remember(wsEntries) { wsEntries.asReversed().take(50) }
                        if (reversed.isEmpty()) {
                            Text(
                                "Нет сообщений.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(reversed, key = { _, e -> e.id }) { _, e ->
                                    val dirColor = when (e.direction) {
                                        NetworkTrafficDirection.IN -> MaterialTheme.colorScheme.tertiary
                                        NetworkTrafficDirection.OUT -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.secondary
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                            .background(dirColor.copy(alpha = 0.10f))
                                            .padding(6.dp),
                                    ) {
                                        Text(
                                            "${if (e.channel == NetworkTrafficChannel.WS) "WS" else "HTTP"} · ${if (e.direction == NetworkTrafficDirection.IN) "IN" else "OUT"} · ${e.summary}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = dirColor,
                                        )
                                        Text(
                                            e.payload.take(200),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 14.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val reversed = remember(ctrlEntries) { ctrlEntries.asReversed().take(50) }
                        if (reversed.isEmpty()) {
                            Text(
                                "Нет команд.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                itemsIndexed(reversed, key = { _, e -> e.id }) { _, e ->
                                    val isTx = e.direction == ControllerTrafficDirection.TX
                                    val dirColor = if (isTx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                            .background(dirColor.copy(alpha = 0.10f))
                                            .padding(6.dp),
                                    ) {
                                        Text(
                                            "${if (isTx) "TX" else "RX"} · ${e.commandName} ${e.commandHex}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = dirColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun HeaderActionStrip(
    waterEnabled: Boolean,
    premiumWaterEnabled: Boolean,
    volumeEnabled: Boolean,
    selectedVolumeMl: Int?,
    waterOption: DrinkWaterOption,
    onVolume: (Int) -> Unit,
    onWater: (DrinkWaterOption) -> Unit,
    s: Float,
) {
 // Вода: одна группа (стандарт → холодная → газ); объём — отдельный кластер. Выбор взаимоисключающий.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((20f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderOptionCluster(modifier = Modifier.weight(1.5f), s = s) {
            HeaderOptionChip(
                modifier = Modifier.weight(1f),
                label = "Стандартная",
                selected = waterOption == DrinkWaterOption.STANDARD,
                enabled = waterEnabled,
                iconRes = R.drawable.ic_standard_water,
                onClick = { onWater(DrinkWaterOption.STANDARD) },
                s = s,
            )
            HeaderOptionVerticalDivider(s = s)
            HeaderOptionChip(
                modifier = Modifier.weight(1f),
                label = "Холодная",
                selected = waterOption == DrinkWaterOption.COLD,
                enabled = waterEnabled && premiumWaterEnabled,
                iconRes = R.drawable.ic_cold_water,
                onClick = { onWater(DrinkWaterOption.COLD) },
                s = s,
            )
            HeaderOptionVerticalDivider(s = s)
            HeaderOptionChip(
                modifier = Modifier.weight(1f),
                label = "Газированная",
                selected = waterOption == DrinkWaterOption.SPARK,
                enabled = waterEnabled && premiumWaterEnabled,
                iconRes = R.drawable.ic_sparkling_water,
                onClick = { onWater(DrinkWaterOption.SPARK) },
                s = s,
            )
        }
        HeaderOptionCluster(modifier = Modifier.weight(1f), s = s) {
            HeaderOptionChip(
                modifier = Modifier.weight(1f),
                label = "300 мл",
                selected = selectedVolumeMl == 300,
                enabled = volumeEnabled,
                iconRes = R.drawable.ic_small_cup,
                onClick = { onVolume(300) },
                s = s,
            )
            HeaderOptionVerticalDivider(s = s)
            HeaderOptionChip(
                modifier = Modifier.weight(1f),
                label = "700 мл",
                selected = selectedVolumeMl == 700,
                enabled = volumeEnabled,
                iconRes = R.drawable.ic_big_cup,
                onClick = { onVolume(700) },
                s = s,
            )
        }
    }
}

@Composable
private fun ViwaStatsPlaceholder(
    s: Float,
    bottlesSavedCount: Int,
 /** Как `useTelemetryConnection`. */
    telemetryWsConnected: Boolean,
    temperature0C: Int?,
    temperature1C: Int?,
) {
    val ghost =
        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFFB8B8B8)
        } else {
            ViwaCustomerUiTokens.GhostTypo
        }
    val wifiColor =
        if (telemetryWsConnected) {
            ghost
        } else {
            MaterialTheme.colorScheme.error
        }

    val temperatureLabel = when {
        temperature0C == null -> "—"
        temperature1C != null && temperature0C != temperature1C -> "${temperature0C}° / ${temperature1C}°"
        else -> "${temperature0C}°C"
    }

    Column(
        modifier =
            Modifier
                .width((158f * s).dp)
                .height((120f * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
 // Figma 772:399: gap-[12px] между блоком бутылок и строкой System
        verticalArrangement = Arrangement.spacedBy((12f * s).dp, Alignment.Top),
    ) {
 // Счётчик бутылок: накопленный расход воды (мл) / 500 (0,5 л на бутылку)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                bottlesSavedCount.toString(),
                color = ghost,
                fontSize = (18f * s).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Бутылок сохранено",
                color = ghost,
                fontSize = (12f * s).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
 // Температура + WiFi. Figma 772:403: gap-[20px]
        Row(
            horizontalArrangement = Arrangement.spacedBy((20f * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    temperatureLabel,
                    color = ghost,
                    fontSize = (18f * s).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Вода",
                    color = ghost,
                    fontSize = (12f * s).sp,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = wifiColor,
                    modifier = Modifier.size((24f * s).dp),
                )
                Text(
                    "Интернет",
                    color = wifiColor,
                    fontSize = (12f * s).sp,
                )
            }
        }
    }
}

@Composable
private fun ViwaScannerHintPlaceholder(s: Float) {
    val ghost =
        if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFFB8B8B8)
        } else {
            ViwaCustomerUiTokens.GhostTypo
        }
    Column(
        modifier =
            Modifier
                .width((158f * s).dp)
                .height((120f * s).dp)
                .padding(top = (4f * s).dp, bottom = (12f * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.QrCodeScanner,
            contentDescription = null,
            tint = ghost,
            modifier = Modifier.size((64f * s).dp),
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = ghost,
            modifier = Modifier.size((40f * s).dp),
        )
    }
}

/**
 * Figma `772:2629` / electron `OptionCardGroup.module.scss`:
 * верхние углы квадратные (прижаты к верху экрана), нижние — 20dp.
 * Тень: `0 4 20 #1212121A`.
 */
@Composable
private fun HeaderOptionCluster(
    modifier: Modifier = Modifier,
    s: Float,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(bottomStart = (20f * s).dp, bottomEnd = (20f * s).dp)
    // Фиксированная высота вместо IntrinsicSize.Min: weight()+Intrinsic ломает hit-area у 3+ чипов.
    // 12+48+4+22+12 = 98 — меньше режет подписи; запас под font padding.
    val clusterHeight = (104f * s).dp
    Row(
        modifier =
            modifier
                .height(clusterHeight)
                .viwaCardShadow(elevation = (4f * s).dp, shape = shape)
                .background(MaterialTheme.colorScheme.surface, shape)
                .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun RowScope.HeaderOptionVerticalDivider(s: Float) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width((2f * s).dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun RowScope.HeaderOptionChip(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    iconRes: Int,
    onClick: () -> Unit,
    s: Float,
) {
    val inactive = ViwaCustomerUiTokens.FigmaOptionInactive
    val iconTint =
        when {
            !enabled -> inactive.copy(alpha = 0.45f)
            selected -> MaterialTheme.colorScheme.primary
            else -> inactive
        }
    val labelColor =
        when {
            !enabled -> inactive.copy(alpha = 0.45f)
            selected -> MaterialTheme.colorScheme.primary
            else -> inactive
        }

 // OptionCard.tsx: scale = selectable && active ? 1 : 0.8, spring ~0.5s
    val iconTargetScale = if (enabled && selected) 1f else 0.8f
    val iconScale by animateFloatAsState(
        targetValue = iconTargetScale,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "headerOptionIconScale",
    )

    val chipInteraction = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clickable(
                    interactionSource = chipInteraction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(vertical = (10f * s).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((4f * s).dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier.size((48f * s).dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(iconScale),
                colorFilter = ColorFilter.tint(iconTint),
            )
        }
        Text(
            text = label,
            fontFamily = MontserratFamily,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            fontSize = (16f * s).sp,
            lineHeight = (20f * s).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

/**
 * Карточка напитка — DrinkCard.module.scss: 301×156px.
 * Когда карточка активна: вместо цены показывает слайдер концентрации (как DrinkCard.tsx: isActive ? renderSlider : renderPrice).
 */
@Composable
private fun ViwaDrinkCard(
    container: DrinkContainer,
    isActive: Boolean,
    selectedVolumeMl: Int?,
    concentration: DrinkConcentration,
    onConcentrationChange: (DrinkConcentration) -> Unit,
    onClick: () -> Unit,
    s: Float,
) {
    val unavailable = container.isUnavailable()
    val view = LocalView.current
    val ctx = LocalContext.current
    val mk = container.product.taste.mediaKey
    val hex = (mk?.let { MediaKeyHexOverride[it] } ?: container.product.taste.hexColor) ?: "#5C6BC0"
    val accent =
        remember(mk, hex) {
            runCatching { Color(android.graphics.Color.parseColor(hex)) }
                .getOrElse { Color(0xFF5C6BC0) }
        }
    val imageUri = remember(mk) { ViwaElectronAssets.horizontalCardImageUri(mk) }
    val initialLetter = container.product.name.take(1).uppercase()
    val priceText =
        if (selectedVolumeMl != null) {
            val p = container.product.dPrices.firstOrNull { it.volume == selectedVolumeMl }?.priceRub
            if (p != null) "$p ₽" else "—"
        } else {
            val minP = container.product.dPrices.minOfOrNull { it.priceRub } ?: 0
            "от $minP ₽"
        }

    val scheme = MaterialTheme.colorScheme
    val isLightUi = scheme.surface.luminance() > 0.5f
    val cardBg =
        when {
            isActive -> accent.copy(alpha = 0.40f)
            else -> scheme.surface
        }
 // Светлая тема: на выбранной карточке тот же typo/main/primary (#231f20), что и на остальных.
 // Тёмная тема: на цветном фоне карточки — светлый текст.
    val cardTextColor =
        when {
            isActive && !isLightUi -> ViwaCustomerUiTokens.DrinkCardOnAccentContent
            isLightUi -> ViwaCustomerUiTokens.TypoMainPrimaryLight
            else -> ViwaCustomerUiTokens.TypoMainPrimaryDark
        }
    val cardInteraction = remember { MutableInteractionSource() }
    val pressed by cardInteraction.collectIsPressedAsState()
 /** Слайдер перехватывает pointer — давим «нажатие» карточки снаружи, чтобы тот же scale, что у clickable. */
    var sliderPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (!isActive) sliderPressed = false
    }
    val cardTargetScale =
        when {
            unavailable -> 1f
            pressed || sliderPressed -> 1.06f
            isActive -> 1.03f
            else -> 1f
        }
    val cardScale by animateFloatAsState(
        targetValue = cardTargetScale,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "drinkCardScale",
    )
    val cardShape = RoundedCornerShape((20f * s).dp)

    Box(
        modifier =
            Modifier
                .width((301f * s).dp)
                .height((156f * s).dp)
                .zIndex(if (isActive) 1f else 0f)
                .scale(cardScale)
                .then(
                    if (!isActive) {
                        Modifier.viwaCardShadow(elevation = (4f * s).dp, shape = cardShape)
                    } else {
                        Modifier
                    },
                )
                .clip(cardShape)
                .clickable(
                    interactionSource = cardInteraction,
                    indication = ripple(bounded = true),
                    enabled = !unavailable,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onClick()
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(cardBg),
            verticalAlignment = Alignment.Top,
        ) {
 // --- Фото (160×156px, object-fit: cover как в DrinkCard.module.scss) ---
 // Фон только у Row — иначе при isActive полупрозрачный cardBg накладывается дважды
 // на колонку с картинкой и визуально «суммируется» с фоном карточки.
            Box(
                modifier =
                    Modifier
                        .width((160f * s).dp)
                        .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUri != null) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(ctx).data(imageUri).crossfade(400).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent(
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            else -> {
                                Box(
                                    modifier =
                                        Modifier.fillMaxSize().background(
                                            Brush.horizontalGradient(
                                                listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.12f)),
                                            ),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        initialLetter,
                                        fontSize = (28f * s).sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.9f),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.12f)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            initialLetter,
                            fontSize = (28f * s).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
 // --- Правая колонка: Figma x=152.67, y=20, padding-end≈20.67px, текст right-aligned.
 // Название вверху (каждое слово на строке), цена/слайдер внизу — SpaceBetween.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = 0.dp,
                            end = (20f * s).dp,
                            top = (20f * s).dp,
                            bottom = (20f * s).dp,
                        ),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
 // Название: каждое слово на своей строке (\n), text-right (Figma node 772:1798)
                Text(
                    text = container.product.name.replace(" ", "\n"),
                    style =
                        TextStyle(
                            fontSize = (20f * s).sp,
                            lineHeight = (26f * s).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = MontserratFamily,
                            textAlign = TextAlign.End,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle =
                                LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Proportional,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                        ),
                    color = cardTextColor,
                    overflow = TextOverflow.Visible,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isActive && !unavailable) {
                    ConcentrationSlider(
                        concentration = concentration,
                        onChange = onConcentrationChange,
                        accent = accent,
                        onPressChange = { sliderPressed = it },
                        s = s,
                    )
                } else {
                    Text(
                        text = priceText,
                        style =
                            TextStyle(
                                fontSize = (20f * s).sp,
                                lineHeight = (26f * s).sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = MontserratFamily,
                                textAlign = TextAlign.End,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle =
                                    LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Proportional,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                            ),
                        color = cardTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (unavailable) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
 // Text size="l" weight="semibold" — Theme_size: --size-text-l 18px, line-height 1.6em
                Text(
                    "Временно недоступен",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = MontserratFamily,
                    textAlign = TextAlign.Center,
                    fontSize = (18f * s).sp,
                    lineHeight = (28.8f * s).sp,
                    modifier = Modifier.padding((8f * s).dp),
                )
            }
        }
    }
}

/**
 * Слайдер концентрации внутри активной карточки (3 шага: Weak/Standard/Strong).
 * Удержание: бегунок плавно следует за пальцем по всей ширине хода; отпускание — snap к ближайшему шагу.
 */
@Composable
private fun ConcentrationSlider(
    concentration: DrinkConcentration,
    onChange: (DrinkConcentration) -> Unit,
    accent: Color,
    onPressChange: (Boolean) -> Unit,
    s: Float,
) {
    val onChangeState = rememberUpdatedState(onChange)
    val onPressChangeState = rememberUpdatedState(onPressChange)
    val concentrationState = rememberUpdatedState(concentration)
    val viewState = rememberUpdatedState(LocalView.current)
    val density = LocalDensity.current
    var isPressed by remember { mutableStateOf(false) }
 /** NaN — нет жеста; иначе доля 0.1 для позиции бегунка «за пальцем». */
    var dragThumbFraction by remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(Unit) {
        onDispose {
            onPressChangeState.value(false)
            dragThumbFraction = Float.NaN
        }
    }

    val thumbDp = (32f * s).dp
    val thumbPx = with(density) { thumbDp.toPx() }
    val trackH = (24f * s).dp
    val trackHPx = with(density) { trackH.toPx() }
    val trackStartDp = (6.67f * s).dp
    val trackStartPx = with(density) { trackStartDp.toPx() }
    val barH = (32f * s).dp
 /** Вертикальное расширение зоны жеста. */
    val verticalTouchPad = (6f * s).dp

    val targetFraction = concentration.ordinal / 2f
 // Без spring после отпускания — иначе визуальный «отскок» бегунка.
    val thumbFraction =
        if (!dragThumbFraction.isNaN()) {
            dragThumbFraction
        } else {
            targetFraction
        }

    val conc = concentration

    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "concentrationThumbScale",
    )
    val semanticsModifier =
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Концентрация напитка"
            stateDescription = conc.ruStateDescription()
            customActions =
                listOf(
                    CustomAccessibilityAction("Слабее") {
                        val cur = concentrationState.value
                        val next = cur.ordinal - 1
                        if (next >= 0) {
                            onChangeState.value(DrinkConcentration.entries[next])
                            true
                        } else {
                            false
                        }
                    },
                    CustomAccessibilityAction("Крепче") {
                        val cur = concentrationState.value
                        val next = cur.ordinal + 1
                        if (next <= DrinkConcentration.entries.lastIndex) {
                            onChangeState.value(DrinkConcentration.entries[next])
                            true
                        } else {
                            false
                        }
                    },
                )
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
 // В макете у слайдера нет увеличенного "воздуха" снизу.
 // Расширяем touch-зону только вверх, чтобы не поднимать трек визуально.
                .padding(top = verticalTouchPad)
                .then(semanticsModifier)
                .pointerInput(s, thumbPx) {
                    fun applyOrdinal(ordinal: Int, haptic: Boolean) {
                        if (ordinal == concentrationState.value.ordinal) return
                        if (haptic) {
                            viewState.value.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onChangeState.value(DrinkConcentration.entries[ordinal])
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        onPressChangeState.value(true)
                        var lastX = down.position.x
                        val wPx0 = size.width.toFloat()
                        if (wPx0 > 0f) {
                            dragThumbFraction = thumbFractionFromFingerX(down.position.x, wPx0, thumbPx)
                        }
                        try {
                            drag(down.id) { change ->
                                lastX = change.position.x
                                change.consume()
                                val wPx = size.width.toFloat()
                                if (wPx <= 0f) return@drag
                                dragThumbFraction = thumbFractionFromFingerX(change.position.x, wPx, thumbPx)
                            }
                        } finally {
                            val wPx = size.width.toFloat()
                            if (wPx > 0f) {
                                val snap = nearestConcentrationOrdinal(lastX, wPx, thumbPx)
                                applyOrdinal(snap, haptic = true)
                            }
                            dragThumbFraction = Float.NaN
                            isPressed = false
                            onPressChangeState.value(false)
                        }
                    }
                },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(barH)) {
            val wPx = with(density) { maxWidth.toPx() }
            val thumbCenterX = thumbPx / 2f + thumbFraction * (wPx - thumbPx)
            val thumbOffsetXDp = with(density) { (thumbCenterX - thumbPx / 2f).toDp() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackTop = (size.height - trackHPx) / 2f
                val trackLeft = trackStartPx
                val trackRight = size.width
                val trackWidth = trackRight - trackLeft
                val r = trackHPx / 2f
                drawRoundRect(
                    color = accent.copy(alpha = 0.25f),
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHPx),
                    cornerRadius = CornerRadius(r, r),
                )
                drawRoundRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(accent.copy(alpha = 0f), accent),
                            startX = trackLeft,
                            endX = trackRight,
                        ),
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHPx),
                    cornerRadius = CornerRadius(r, r),
                )
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = thumbOffsetXDp)
                        .size(thumbDp)
                        .scale(thumbScale)
                        .graphicsLayer {
                            shadowElevation = (3f * s).dp.toPx()
                            shape = CircleShape
                            clip = false
                        }
                        .background(accent, CircleShape),
            )
        }
    }
}
