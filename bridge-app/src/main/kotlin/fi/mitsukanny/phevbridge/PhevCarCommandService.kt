package fi.mitsukanny.phevbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Runs [CarController.runCommand] as a foreground service. Required, not optional:
 * `WifiNetworkFactory` rejects a `WifiNetworkSpecifier` request with "Request not from
 * foreground app or service" unless the requesting process currently has foreground-level
 * importance. A plain background thread inside a bound [PhevCallScreeningService] does NOT
 * qualify -- confirmed on-device, a real call-triggered attempt failed this way the moment
 * the app no longer had residual foreground importance from recent UI use. An active
 * foreground service does qualify, which is the whole reason this class exists.
 */
class PhevCarCommandService : Service() {
    companion object {
        private const val CHANNEL_ID = "car-command"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PAYLOAD = "payload"

        fun start(context: Context, commandLabel: String, payload: ByteArray) {
            val intent = Intent(context, PhevCarCommandService::class.java)
                .putExtra(EXTRA_LABEL, commandLabel)
                .putExtra(EXTRA_PAYLOAD, payload)
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val payload = intent?.getByteArrayExtra(EXTRA_PAYLOAD)
        startForeground(NOTIFICATION_ID, buildNotification(label ?: "command"))

        if (label == null || payload == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val creds = CarCredentials.load(this)
        if (creds == null) {
            BridgeLog.summary("$label: failed -- no saved car Wi-Fi credentials")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        CarController.runCommand(this, creds, payload, label) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(label: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Car command", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PHEV bridge")
            .setContentText("Sending $label to car…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
}
