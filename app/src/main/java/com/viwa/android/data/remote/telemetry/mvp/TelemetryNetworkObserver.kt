package com.viwa.android.data.remote.telemetry.mvp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.viwa.android.di.AppIoScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Observes default network validation and Wi‑Fi radio state (no root, no Wi‑Fi toggle).
 * On validated availability → [onValidatedAvailable] after [DEBOUNCE_MS].
 * On loss → [onValidatedLost] immediately (socket teardown deferred to WS watchdog).
 */
@Singleton
class TelemetryNetworkObserver
@Inject
constructor(
    @ApplicationContext context: Context,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var started = false
    private var debounceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null
    private var lastLoggedLine: String? = null

    @Volatile
    var isValidatedAvailable: Boolean = false
        private set

    var onValidatedAvailable: (() -> Unit)? = null
    var onValidatedLost: (() -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshValidatedState(callback = "onAvailable")
                }

                override fun onLost(network: Network) {
                    refreshValidatedState(callback = "onLost")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refreshValidatedState(callback = "onCapabilitiesChanged")
                }
            }
        networkCallback = callback
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.onFailure {
            Timber.w(it, "TelemetryNetworkObserver: registerDefaultNetworkCallback failed")
            started = false
            networkCallback = null
            return
        }
        registerWifiStateReceiver()
        logSnapshot("start")
        refreshValidatedState(logInitial = true, callback = "start")
    }

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure { Timber.w(it, "TelemetryNetworkObserver: unregister failed") }
        }
        networkCallback = null
        wifiStateReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
                .onFailure { Timber.w(it, "TelemetryNetworkObserver: unregister wifi receiver failed") }
        }
        wifiStateReceiver = null
        started = false
        lastLoggedLine = null
    }

    /** Compact one-line snapshot for WS close / reconnect logs. */
    fun snapshotLine(): String = captureSnapshot().toLogLine()

    /** Test seam — drive validated transitions without real ConnectivityManager callbacks. */
    internal fun applyValidatedStateForTests(validated: Boolean) {
        handleValidatedTransition(validated)
    }

    private fun registerWifiStateReceiver() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    val previous =
                        intent.getIntExtra(
                            WifiManager.EXTRA_PREVIOUS_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN,
                        )
                    val snapshot = captureSnapshot()
                    Timber.i(
                        "TelemetryNetworkObserver: wifi.radio %s→%s — %s",
                        wifiStateLabel(previous),
                        wifiStateLabel(state),
                        snapshot.toLogLine(),
                    )
                    lastLoggedLine = snapshot.identityLine()
                    refreshValidatedState(callback = "wifi.radio")
                }
            }
        wifiStateReceiver = receiver
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // System broadcast: exported is required to receive WIFI_STATE_CHANGED.
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        }.onFailure {
            Timber.w(it, "TelemetryNetworkObserver: register wifi receiver failed")
            wifiStateReceiver = null
        }
    }

    private fun refreshValidatedState(
        logInitial: Boolean = false,
        callback: String = "refresh",
    ) {
        val validated = readDefaultNetworkValidated()
        handleValidatedTransition(validated, logInitial = logInitial, callback = callback)
    }

    private fun readDefaultNetworkValidated(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isValidatedInternet(caps)
    }

    private fun handleValidatedTransition(
        validated: Boolean,
        logInitial: Boolean = false,
        callback: String = "refresh",
    ) {
        if (validated == isValidatedAvailable && !logInitial) {
            logSnapshotIfChanged(callback)
            return
        }
        val previous = isValidatedAvailable
        isValidatedAvailable = validated
        if (validated) {
            if (!previous || logInitial) {
                scheduleDebouncedAvailable()
            }
        } else {
            debounceJob?.cancel()
            debounceJob = null
            if (previous) {
                val snapshot = captureSnapshot()
                Timber.i("TelemetryNetworkObserver: validated network lost — ${snapshot.toLogLine()}")
                lastLoggedLine = snapshot.identityLine()
                onValidatedLost?.invoke()
            }
        }
    }

    private fun scheduleDebouncedAvailable() {
        debounceJob?.cancel()
        debounceJob =
            appScope.launch {
                delay(DEBOUNCE_MS)
                if (!isValidatedAvailable) return@launch
                val snapshot = captureSnapshot()
                Timber.i(
                    "TelemetryNetworkObserver: validated network available — reconnect trigger ${snapshot.toLogLine()}",
                )
                lastLoggedLine = snapshot.identityLine()
                onValidatedAvailable?.invoke()
            }
    }

    private fun logSnapshot(kind: String) {
        val snapshot = captureSnapshot()
        Timber.i("TelemetryNetworkObserver: $kind — ${snapshot.toLogLine()}")
        lastLoggedLine = snapshot.identityLine()
    }

    private fun logSnapshotIfChanged(callback: String) {
        val snapshot = captureSnapshot()
        val identity = snapshot.identityLine()
        if (identity == lastLoggedLine) return
        Timber.i("TelemetryNetworkObserver: link $callback — ${snapshot.toLogLine()}")
        lastLoggedLine = identity
    }

    private fun captureSnapshot(): TelemetryNetworkSnapshot {
        val network = connectivityManager.activeNetwork
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val wifiManager =
            runCatching {
                appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            }.getOrNull()
        val transport =
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "OTHER"
            }
        val rssi =
            if (transport == "WIFI") {
                runCatching {
                    @Suppress("DEPRECATION")
                    wifiManager?.connectionInfo?.rssi
                }.getOrNull()
            } else {
                null
            }
        val ssid =
            if (transport == "WIFI") {
                sanitizeWifiSsid(
                    runCatching {
                        @Suppress("DEPRECATION")
                        wifiManager?.connectionInfo?.ssid
                    }.getOrNull(),
                )
            } else {
                "none"
            }
        val wifiState =
            runCatching { wifiManager?.wifiState }
                .getOrNull()
                ?.let { wifiStateLabel(it) }
                ?: "unavailable"
        return TelemetryNetworkSnapshot(
            validated = caps?.let { isValidatedInternet(it) } == true,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            transport = transport,
            wifiEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull(),
            wifiState = wifiState,
            rssi = rssi,
            ssid = ssid,
        )
    }

    companion object {
        const val DEBOUNCE_MS = 500L

        fun isValidatedInternet(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
