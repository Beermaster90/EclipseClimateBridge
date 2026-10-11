package fi.mitsukanny.phevbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * The one place that knows how to join the car's Wi-Fi and run an RC4 command --
 * used by both the manual test UI ([Rc4TestActivity]) and the call-triggered
 * [PhevCallScreeningService], so a call behaves exactly like tapping the button
 * manually would, not a second, separately-maintained implementation.
 *
 * Per PLAN-01: joining is scoped to a short window with a hard timeout, and (for the
 * call-triggered path) a short partial wakelock covers exactly that window so the
 * work has a real chance to finish even if the screen is off and the app has no other
 * reason to be running -- not held any longer than that.
 */
object CarController {
    private const val WAKELOCK_TIMEOUT_MS = 45_000L
    private const val NETWORK_TIMEOUT_MS = 30_000L

    fun runCommand(
        context: Context,
        creds: CarCredentials.Saved,
        payload: ByteArray,
        commandLabel: String,
        onResult: (Rc4CarClient.Result?) -> Unit,
    ) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phevbridge:$commandLabel")
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val specifier = WifiNetworkSpecifier.Builder().setSsid(creds.ssid).setWpa2Passphrase(creds.password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var finished = false
        fun finish(result: Rc4CarClient.Result?, callback: ConnectivityManager.NetworkCallback?) {
            if (finished) return
            finished = true
            if (callback != null) {
                try { connectivityManager.unregisterNetworkCallback(callback) } catch (ignored: IllegalArgumentException) {}
            }
            connectivityManager.bindProcessToNetwork(null)
            if (wakeLock.isHeld) wakeLock.release()
            onResult(result)
        }

        lateinit var callback: ConnectivityManager.NetworkCallback
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                BridgeLog.summary("$commandLabel: car Wi-Fi joined")
                thread(name = "car-$commandLabel") {
                    val result = Rc4CarClient.sendAuthenticatedCommand(network, creds.host, creds.port, payload)
                    result.log.lineSequence().filter { it.isNotBlank() }.forEach { BridgeLog.detail(it) }
                    BridgeLog.summary(
                        if (result.success) "$commandLabel: response received (check the car to confirm)"
                        else "$commandLabel: failed -- ${result.log.lines().lastOrNull { it.isNotBlank() }}"
                    )
                    finish(result, callback)
                }
            }

            override fun onUnavailable() {
                BridgeLog.summary("$commandLabel: failed -- could not join car Wi-Fi")
                finish(null, callback)
            }
        }
        BridgeLog.summary("$commandLabel: connecting to car Wi-Fi \"${creds.ssid}\"")
        connectivityManager.requestNetwork(request, callback, NETWORK_TIMEOUT_MS.toInt())
    }

}
