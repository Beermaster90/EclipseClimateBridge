package fi.mitsukanny.phevbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Not required for [PhevCallScreeningService] itself -- Android's telecom framework invokes
 * a call-screening role holder on demand for every incoming call regardless of whether the
 * app process is currently running, and that role grant survives reboot on its own. This
 * exists purely so there's an on-device log line confirming the app came back up after a
 * reboot/update, for diagnosing reboot reliability -- not to start anything.
 */
class PhevBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeLog.attachFileLogging(context.applicationContext)
        BridgeLog.summary("Device boot completed -- PHEV bridge ready (${intent.action})")
    }
}
