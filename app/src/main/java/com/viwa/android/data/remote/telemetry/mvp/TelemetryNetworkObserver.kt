package com.viwa.android.data.remote.telemetry.mvp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
 * Observes default network validation state and debounces reconnect triggers.
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

    @Volatile
    var isValidatedAvailable: Boolean = false
        private set

    var onValidatedAvailable: (() -> Unit)? = null
    var onValidatedLost: (() -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        refreshValidatedState(logInitial = true)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshValidatedState()
                }

                override fun onLost(network: Network) {
                    refreshValidatedState()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    refreshValidatedState()
                }
            }
        networkCallback = callback
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.onFailure {
            Timber.w(it, "TelemetryNetworkObserver: registerDefaultNetworkCallback failed")
            started = false
            networkCallback = null
        }
    }

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                .onFailure { Timber.w(it, "TelemetryNetworkObserver: unregister failed") }
        }
        networkCallback = null
        started = false
    }

    /** Test seam — drive validated transitions without real ConnectivityManager callbacks. */
    internal fun applyValidatedStateForTests(validated: Boolean) {
        handleValidatedTransition(validated)
    }

    private fun refreshValidatedState(logInitial: Boolean = false) {
        val validated = readDefaultNetworkValidated()
        handleValidatedTransition(validated, logInitial = logInitial)
    }

    private fun readDefaultNetworkValidated(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isValidatedInternet(caps)
    }

    private fun handleValidatedTransition(
        validated: Boolean,
        logInitial: Boolean = false,
    ) {
        if (validated == isValidatedAvailable && !logInitial) return
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
                Timber.i("TelemetryNetworkObserver: validated network lost — ${describeNetwork()}")
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
                Timber.i(
                    "TelemetryNetworkObserver: validated network available — reconnect trigger ${describeNetwork()}",
                )
                onValidatedAvailable?.invoke()
            }
    }

    private fun describeNetwork(): String {
        val caps =
            connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
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
                    (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                        ?.connectionInfo
                        ?.rssi
                }.getOrNull()
            } else {
                null
            }
        return "transport=$transport rssi=$rssi"
    }

    companion object {
        const val DEBOUNCE_MS = 500L

        fun isValidatedInternet(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
