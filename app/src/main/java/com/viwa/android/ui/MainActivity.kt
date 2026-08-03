package com.viwa.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.viwa.android.ui.screens.customer.ViwaCustomerUiTokens
import com.viwa.android.ui.theme.GLOBAL_UI_SCALE
import com.viwa.android.ui.theme.LocalCustomerPrimaryButtonColor
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.viwa.android.hardware.scanner.ScannerManager
import com.viwa.android.platform.KiayoSystemBars
import com.viwa.android.platform.KioskCollapseTickerPolicy
import com.viwa.android.platform.ViwaKioskSystemUi
import com.viwa.android.platform.ViwaSystemUiPolicy
import com.viwa.android.ui.navigation.ViwaNavGraph
import com.viwa.android.ui.navigation.Routes
import com.viwa.android.ui.screens.service.ServiceScreenLaunch
import com.viwa.android.ui.screens.idle.IdleVideoOverlay
import com.viwa.android.ui.screens.idle.IdleVideoViewModel
import com.viwa.android.domain.technician.ServiceMenuNavigationGate
import com.viwa.android.services.telemetry.TechnicianKeyServiceMenuCoordinator
import com.viwa.android.services.telemetry.LoyaltyCardScanCoordinator
import com.viwa.android.services.telemetry.TelemetryDebugBootstrap
import com.viwa.android.services.telemetry.TelemetryRegistrationScannerCoordinator
import com.viwa.android.services.telemetry.ViwaTelemetryService
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF008CF0),
        background = Color(0xFFF3F3F4),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEEEEF0),
        onBackground = Color(0xFF1A1C1E),
        onSurface = Color(0xFF1A1C1E),
        onSurfaceVariant = Color(0xFF43474E),
        outline = Color(0xFFD0D0D4),
        outlineVariant = Color(0xFFE8E8EC),
        inverseSurface = Color(0xFF2D2D2D),
        inverseOnSurface = Color(0xFFF0F0F0),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF8E24AA),
        background = Color(0xFF1C1C1E),
        surface = Color(0xFF2C2C2E),
        surfaceVariant = Color(0xFF2C2C2E),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFF8A8A8E),
        outline = Color(0xFF3A3A3C),
        outlineVariant = Color(0xFF3A3A3C),
        inverseSurface = Color(0xFFE0E0E0),
        inverseOnSurface = Color(0xFF1C1C1E),
    )

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Глобальная подписка на сканер карты лояльности (инициализация @Singleton). */
    @Inject
    lateinit var loyaltyCardScanCoordinator: LoyaltyCardScanCoordinator

    /** Сканер должен читать всегда, пока жив kiosk activity, а не только из сервисной вкладки. */
    @Inject
    lateinit var scannerManager: ScannerManager

    /** KEY-* → technician validate / offline allowlist → service menu navigation. */
    @Inject
    lateinit var employeeKeyServiceMenuCoordinator: TechnicianKeyServiceMenuCoordinator

    @Inject
    lateinit var serviceMenuNavigationGate: ServiceMenuNavigationGate

    /** QR/REG → поля регистрации телеметрии (инициализация @Singleton). */
    @Inject
    lateinit var telemetryRegistrationScannerCoordinator: TelemetryRegistrationScannerCoordinator

    @Inject
    lateinit var nanoKassaRepository: NanoKassaRepository

    @Inject
    lateinit var telemetryService: ViwaTelemetryService

    private val themeViewModel: ThemeViewModel by viewModels()
    private val idleVideoViewModel: IdleVideoViewModel by viewModels()

    /** Выставляется в [setContent] для [onNewIntent] (открыть сервис с дашбордом). */
    private var navigateToServiceLambda: (() -> Unit)? = null

    private val kioskUiHandler = Handler(Looper.getMainLooper())
    private var kioskUiTickerRunning = false
    private var lastLegacyNavHideUptimeMs = 0L

    private val collapseTicker =
        object : Runnable {
            override fun run() {
                collapseSystemPanelsBestEffort()
                kioskUiHandler.postDelayed(this, KioskCollapseTickerPolicy.HIDDEN_TICK_MS)
            }
        }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idleVideoViewModel.resetTimer()
        applyKioskWindowPolicy(forceLegacyNavHide = false)
        requestImmediateKioskCollapse()
    }

    private val requestLocationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _: Map<String, Boolean> -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back navigation blocked in kiosk mode
                }
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
            if (needCoarse) {
                requestLocationPermissions.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        }
        setupImmersiveSystemUiListeners()
        applyKioskWindowPolicy(forceLegacyNavHide = true)
        scannerManager.startReading()
        telemetryRegistrationScannerCoordinator
        TelemetryDebugBootstrap.consumeAndRun(intent, telemetryService, lifecycleScope)
        setContent {
            val isDark by themeViewModel.isDark.collectAsStateWithLifecycle()
            val customerPrimaryLightArgb by themeViewModel.customerPrimaryLightArgb.collectAsStateWithLifecycle(
                initialValue = ViwaCustomerUiTokens.DefaultBrandPrimaryArgb,
            )
            val customerPrimaryDarkArgb by themeViewModel.customerPrimaryDarkArgb.collectAsStateWithLifecycle(
                initialValue = ViwaCustomerUiTokens.DefaultBrandPrimaryArgbDark,
            )
            val brandPrimaryArgb = if (isDark) customerPrimaryDarkArgb else customerPrimaryLightArgb
            val brandPrimaryColor = Color(brandPrimaryArgb)
            val colorScheme =
                (if (isDark) DarkScheme else LightScheme).copy(primary = brandPrimaryColor)
            val isIdleVisible by idleVideoViewModel.isVisible.collectAsStateWithLifecycle()
            val enabledVideoIds by idleVideoViewModel.enabledVideoIds.collectAsStateWithLifecycle()
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides
                    Density(
                        density = baseDensity.density * GLOBAL_UI_SCALE,
                        fontScale = baseDensity.fontScale,
                    ),
            ) {
                MaterialTheme(colorScheme = colorScheme) {
                    CompositionLocalProvider(LocalCustomerPrimaryButtonColor provides brandPrimaryColor) {
                        val navController = rememberNavController()
                        val backStackEntry by navController.currentBackStackEntryAsState()

                        LaunchedEffect(navController) {
                            navigateToServiceLambda = {
                                serviceMenuNavigationGate.navigateIfAuthorized {
                                    navController.navigate(Routes.Service) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            if (intent.getBooleanExtra("open_service_dashboard", false)) {
                                intent.removeExtra("open_service_dashboard")
                                ServiceScreenLaunch.selectDashboardOnOpen = true
                                navigateToServiceLambda?.invoke()
                            }
                            if (intent.getBooleanExtra("open_service_equipment", false)) {
                                intent.removeExtra("open_service_equipment")
                                ServiceScreenLaunch.selectEquipmentDevicesOnOpen = true
                                navigateToServiceLambda?.invoke()
                            }
                        }

                        LaunchedEffect(navController) {
                            employeeKeyServiceMenuCoordinator.openServiceMenuRequests.collect {
                                if (navController.currentDestination?.route != Routes.Home) return@collect
                                idleVideoViewModel.resetTimer()
                                serviceMenuNavigationGate.navigateIfAuthorized {
                                    navController.navigate(Routes.Service)
                                }
                            }
                        }

                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                runCatching { nanoKassaRepository.verifyIntegration() }
                            }
                        }

                        // Idle-таймер работает только на экране выбора напитков
                        LaunchedEffect(backStackEntry) {
                            val route = backStackEntry?.destination?.route
                            idleVideoViewModel.setActive(route == Routes.Home)
                        }

                        // Idle overlay отключён (IdleVideoViewModel.IDLE_OVERLAY_ENABLED=false):
                        // на плате давал белый экран после видео. NavGraph всегда смонтирован.
                        Box(modifier = Modifier.fillMaxSize()) {
                            ViwaNavGraph(
                                navController = navController,
                                onOpenService = {
                                    // Password "studio" only — KEY scan uses navigateIfAuthorized above.
                                    serviceMenuNavigationGate.navigateAfterLocalStudioPassword {
                                        navController.navigate(Routes.Service)
                                    }
                                },
                            )
                            if (isIdleVisible) {
                                IdleVideoOverlay(
                                    enabledVideoIds = enabledVideoIds,
                                    onDismiss = { idleVideoViewModel.resetTimer() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TelemetryDebugBootstrap.consumeAndRun(intent, telemetryService, lifecycleScope)
        if (intent.getBooleanExtra("open_service_dashboard", false)) {
            intent.removeExtra("open_service_dashboard")
            ServiceScreenLaunch.selectDashboardOnOpen = true
            navigateToServiceLambda?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        applyKioskWindowPolicy(forceLegacyNavHide = true)
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowPolicy(forceLegacyNavHide = true)
        startCollapseTickerIfNeeded()
    }

    override fun onPause() {
        stopCollapseTicker()
        super.onPause()
    }

    override fun onStop() {
        window.decorView.removeCallbacks(hideRunnable)
        releaseKiayoNavigationBar()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyKioskWindowPolicy(forceLegacyNavHide = true)
            requestImmediateKioskCollapse()
        }
    }

    override fun onDestroy() {
        releaseKiayoNavigationBar()
        scannerManager.stop()
        super.onDestroy()
    }

    private fun startCollapseTickerIfNeeded() {
        if (kioskUiTickerRunning) return
        kioskUiTickerRunning = true
        kioskUiHandler.post(collapseTicker)
    }

    private fun stopCollapseTicker() {
        kioskUiTickerRunning = false
        kioskUiHandler.removeCallbacks(collapseTicker)
    }

    private fun applyKioskWindowPolicy(forceLegacyNavHide: Boolean) {
        syncKiayoNavigationBarWithKioskMode()
        syncSystemUiPolicyWithKioskMode()
        ViwaKioskSystemUi.hideSystemBars(this)
        maybeReapplyLegacyNavHide(forceLegacyNavHide)
        collapseSystemPanelsBestEffort()
    }

    /** Kiayo K3568: hide OEM nav bar while Viwa is in foreground. */
    private fun syncKiayoNavigationBarWithKioskMode() {
        KiayoSystemBars.hideNavigationBar(this)
    }

    /** Restore system navigation when Viwa leaves foreground or is closed. */
    private fun releaseKiayoNavigationBar() {
        KiayoSystemBars.showNavigationBar(this)
    }

    /** `policy_control` when WRITE_SECURE_SETTINGS is granted (priv-app / pm grant). */
    private fun syncSystemUiPolicyWithKioskMode() {
        ViwaSystemUiPolicy.applyCustomerKioskPolicy(this)
    }

    private fun maybeReapplyLegacyNavHide(force: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastLegacyNavHideUptimeMs < LEGACY_NAV_HIDE_MIN_INTERVAL_MS) return
        lastLegacyNavHideUptimeMs = now
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = ViwaKioskSystemUi.legacyImmersiveFlags()
    }

    private val hideRunnable = Runnable { applyKioskWindowPolicy(forceLegacyNavHide = true) }

    private fun setupImmersiveSystemUiListeners() {
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            val imeVisible = compat.isVisible(WindowInsetsCompat.Type.ime())
            val navVisible = compat.isVisible(WindowInsetsCompat.Type.navigationBars())
            val statusVisible = compat.isVisible(WindowInsetsCompat.Type.statusBars())
            if ((navVisible || statusVisible) && !imeVisible) {
                view.removeCallbacks(hideRunnable)
                requestImmediateKioskCollapse()
                view.postDelayed(hideRunnable, LEGACY_NAV_HIDE_MIN_INTERVAL_MS)
            }
            view.onApplyWindowInsets(insets)
        }
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            val fullscreenHidden = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            val navHidden = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            if (fullscreenHidden || navHidden) {
                window.decorView.post { applyKioskWindowPolicy(forceLegacyNavHide = true) }
            }
        }
    }

    private fun requestImmediateKioskCollapse() {
        kioskUiHandler.removeCallbacks(collapseTicker)
        collapseSystemPanelsBestEffort()
        if (kioskUiTickerRunning) {
            kioskUiHandler.postDelayed(collapseTicker, KioskCollapseTickerPolicy.HIDDEN_TICK_MS)
        }
    }

    @SuppressLint("MissingPermission", "WrongConstant")
    private fun collapseSystemPanelsBestEffort() {
        runCatching {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
        runCatching {
            @Suppress("DEPRECATION")
            val statusBarService = getSystemService("statusbar")
            val clazz = Class.forName("android.app.StatusBarManager")
            val collapse = clazz.getMethod("collapsePanels")
            collapse.invoke(statusBarService)
        }
    }

    private companion object {
        private const val LEGACY_NAV_HIDE_MIN_INTERVAL_MS = 1100L
    }
}
