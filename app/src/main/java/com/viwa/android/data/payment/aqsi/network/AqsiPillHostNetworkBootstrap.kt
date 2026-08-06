package com.viwa.android.data.payment.aqsi.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Pill host network setup from app code — no adb.
 *
 * [HostNetworkBootstrapMode.STARTUP_FULL]: clear stale proxy → NCM IP (incl. DHCP wait) → Wi‑Fi bind → SOCKS :1080.
 * [HostNetworkBootstrapMode.PAYMENT_FAST]: clear proxy → quick NCM snapshot → Wi‑Fi probe only (no NCM wait / SOCKS).
 */
@Singleton
class AqsiPillHostNetworkBootstrap
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val ncmConfigurator: AqsiPillNcmConfigurator,
    private val networkRouter: AqsiPillNetworkRouter,
    private val socksForwarder: AqsiPillSocksForwarder,
) {
    suspend fun runWhenPillPresent(): HostNetworkStatus = run(HostNetworkBootstrapMode.STARTUP_FULL)

    suspend fun runForPayment(): HostNetworkStatus = run(HostNetworkBootstrapMode.PAYMENT_FAST)

    private suspend fun run(mode: HostNetworkBootstrapMode): HostNetworkStatus {
        val paymentFastPath = mode == HostNetworkBootstrapMode.PAYMENT_FAST
        val proxyCleared = AqsiPillShellRunner.clearStaleHttpProxy(context)
        networkRouter.refreshNetworks()

        val ncmReady =
            when (mode) {
                HostNetworkBootstrapMode.STARTUP_FULL -> {
                    val ready = ncmConfigurator.ensureHostLinkReady(networkRouter)
                    if (ready) {
                        socksForwarder.ensureStarted()
                    } else if (ncmConfigurator.hasHostGatewayAddress()) {
                        socksForwarder.ensureStarted()
                    }
                    ready
                }
                HostNetworkBootstrapMode.PAYMENT_FAST -> {
                    val current = ncmConfigurator.hasHostGatewayAddress()
                    Timber.tag(TAG).i(
                        "payment fast-path NCM snapshot ncmReady=%s (no DHCP wait)",
                        current,
                    )
                    current
                }
            }

        val wifiReachable = probeWifiInternet()
        val status =
            HostNetworkStatus(
                httpProxyCleared = proxyCleared,
                ncmReady = ncmReady,
                wifiProcessBound = networkRouter.isProcessBoundToWifi(),
                wifiInternetProbe = wifiReachable,
                socksStarted =
                    when (mode) {
                        HostNetworkBootstrapMode.STARTUP_FULL -> ncmReady
                        HostNetworkBootstrapMode.PAYMENT_FAST -> false
                    },
                paymentFastPath = paymentFastPath,
            )
        Timber.tag(TAG).i("host network bootstrap mode=%s: %s", mode, status)
        return status
    }

    private fun probeWifiInternet(): Boolean =
        runCatching {
            Socket().use { socket ->
                networkRouter.connectForInternet(socket, "1.1.1.1", 443, 4_000)
                true
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "Wi‑Fi internet probe failed")
            false
        }

    enum class HostNetworkBootstrapMode {
        STARTUP_FULL,
        PAYMENT_FAST,
    }

    data class HostNetworkStatus(
        val httpProxyCleared: Boolean,
        val ncmReady: Boolean,
        val wifiProcessBound: Boolean,
        val wifiInternetProbe: Boolean,
        val socksStarted: Boolean,
        val paymentFastPath: Boolean = false,
    ) {
        val readyForJpay: Boolean
            get() = ncmReady && wifiProcessBound && wifiInternetProbe
    }

    companion object {
        private const val TAG = "AQSI_SETUP"
    }
}
