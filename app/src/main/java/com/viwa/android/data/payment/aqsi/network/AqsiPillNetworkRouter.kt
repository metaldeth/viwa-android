package com.viwa.android.data.payment.aqsi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps general app traffic (SBP, telemetry, OkHttp) on validated Wi‑Fi when Pill USB-NCM
 * (`eth*`, 192.168.137.0/24) is connected, while JPAY/Arcus proxy sockets use the Pill network.
 */
class AqsiPillNetworkRouter private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val lock = Any()

    @Volatile
    private var wifiNetwork: Network? = null

    @Volatile
    private var pillNetwork: Network? = null

    @Volatile
    private var started = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNetworks()
            }

            override fun onLost(network: Network) {
                refreshNetworks()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                refreshNetworks()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                refreshNetworks()
            }
        }

    private val wifiHoldCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) {
                    wifiNetwork = network
                    applyProcessBinding(pillNetwork, network)
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    if (wifiNetwork == network) {
                        wifiNetwork = null
                    }
                    refreshNetworks()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (!isValidatedWifi(networkCapabilities)) return
                synchronized(lock) {
                    wifiNetwork = network
                    applyProcessBinding(pillNetwork, network)
                }
            }
        }

    init {
        start()
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            refreshNetworks()
            val wifiRequest =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            connectivityManager.requestNetwork(wifiRequest, wifiHoldCallback)
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback,
            )
            Log.i(TAG, "network callback registered + wifi hold requested")
        }
    }

    fun connectToPill(
        socket: Socket,
        host: String,
        port: Int,
        timeoutMs: Int,
    ) {
        refreshNetworks()
        pillNetwork?.bindSocket(socket)
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    }

    fun connectForInternet(
        socket: Socket,
        host: String,
        port: Int,
        timeoutMs: Int,
    ) {
        refreshNetworks()
        val wifi = wifiNetwork
        if (wifi != null) {
            wifi.bindSocket(socket)
        }
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    }

    fun hasPillNetwork(): Boolean = pillNetwork != null

    fun isProcessBoundToWifi(): Boolean =
        connectivityManager.boundNetworkForProcess?.let { bound ->
            wifiNetwork != null && bound == wifiNetwork
        } == true

    fun refreshNetworks() {
        synchronized(lock) {
            var nextWifi: Network? = wifiNetwork
            var nextPill: Network? = null

            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue

                when {
                    isPillEthernetNetwork(linkProperties, capabilities) -> nextPill = network
                    isValidatedWifi(capabilities) -> nextWifi = network
                }
            }

            wifiNetwork = nextWifi
            pillNetwork = nextPill
            applyProcessBinding(nextPill, nextWifi)
        }
    }

    private fun applyProcessBinding(
        pill: Network?,
        wifi: Network?,
    ) {
        if (pill == null) {
            val cleared = connectivityManager.bindProcessToNetwork(null)
            Log.i(TAG, "bindProcessToNetwork cleared result=$cleared")
            return
        }
        if (wifi == null) {
            Timber.tag(TAG).w("Pill network present but Wi‑Fi missing; app traffic may be broken")
            return
        }
        val bound = connectivityManager.bindProcessToNetwork(wifi)
        Log.i(
            TAG,
            "bindProcessToNetwork wifi=true pill=true result=$bound boundNetwork=${connectivityManager.boundNetworkForProcess}",
        )
    }

    companion object {
        private const val TAG = "AQSI_NET"

        fun getInstance(context: Context): AqsiPillNetworkRouter =
            Holder.ensureStarted(context)

        fun isPillEthernetNetwork(
            linkProperties: LinkProperties,
            capabilities: NetworkCapabilities,
        ): Boolean {
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return false
            val onPillSubnet =
                linkProperties.linkAddresses.any { address ->
                    address.address?.hostAddress?.startsWith(AqsiPillNetworkConstants.PILL_SUBNET_PREFIX) == true
                }
            if (!onPillSubnet) return false
            val hasDefaultRoute = linkProperties.routes.any { it.isDefaultRoute }
            return !hasDefaultRoute
        }

        fun isValidatedWifi(capabilities: NetworkCapabilities): Boolean =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        fun routesViaPillSubnet(
            host: String,
            port: Int,
        ): Boolean =
            host == AqsiPillNetworkConstants.PILL_HOST ||
                (host == AqsiPillNetworkConstants.PILL_GATEWAY_HOST &&
                    port == AqsiPillNetworkConstants.SOCKS_PORT)
    }

    private object Holder {
        @Volatile
        private var instance: AqsiPillNetworkRouter? = null

        fun ensureStarted(context: Context): AqsiPillNetworkRouter =
            instance ?: synchronized(this) {
                instance ?: AqsiPillNetworkRouter(context).also { instance = it }
            }
    }
}
