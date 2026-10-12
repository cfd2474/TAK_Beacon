package com.taksolutions.takbeacon.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the beacon when it is on a bandwidth-constrained satellite network
 * (T-Mobile T-Satellite and similar), following Android's guide for constrained
 * satellite networks: request INTERNET without NOT_BANDWIDTH_CONSTRAINED, and
 * treat the network as satellite when that capability is missing or the
 * transport is satellite.
 *
 * The app opts in with PROPERTY_SATELLITE_DATA_OPTIMIZED in its manifest; in
 * satellite mode only foreground services keep data, which is how the beacon runs.
 */
class NetworkWatcher(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _onSatellite = MutableStateFlow(false)
    val onSatellite: StateFlow<Boolean> = _onSatellite.asStateFlow()

    private var thread: HandlerThread? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null || Build.VERSION.SDK_INT < 31) return
        val t = HandlerThread("TAKBeaconNetwork").also { it.start() }
        thread = t
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (Build.VERSION.SDK_INT >= 36) removeCapability(NOT_BANDWIDTH_CONSTRAINED) }
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _onSatellite.value = isConstrainedSatellite(caps)
            }

            override fun onLost(network: Network) {
                _onSatellite.value = false
            }
        }
        try {
            cm.registerBestMatchingNetworkCallback(request, cb, Handler(t.looper))
            callback = cb
        } catch (e: RuntimeException) {
            // The guide asks apps to handle ConnectivityManager exceptions on Android 16+.
            Log.w(TAG, "could not watch for satellite networks: ${e.message}")
            t.quitSafely()
            thread = null
        }
    }

    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        thread?.quitSafely()
        thread = null
        _onSatellite.value = false
    }

    companion object {
        private const val TAG = "TAKBeaconNet"
        // Hard-coded per the guide, so the checks also compile and run below API 36.
        private const val NOT_BANDWIDTH_CONSTRAINED = 37 // NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED
        private const val TRANSPORT_SATELLITE = 10       // NetworkCapabilities.TRANSPORT_SATELLITE

        fun isConstrainedSatellite(caps: NetworkCapabilities): Boolean {
            // The capability only exists from Android 16; earlier, its absence means nothing.
            val constrained = Build.VERSION.SDK_INT >= 36 && !caps.hasCapability(NOT_BANDWIDTH_CONSTRAINED)
            val satellite = Build.VERSION.SDK_INT >= 35 && caps.hasTransport(TRANSPORT_SATELLITE)
            return constrained || satellite
        }
    }
}
