package fi.mitsukanny.phevbridge

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Must respond within Android's 5-second window, so this only does the quick allowlist
 * check inline and hands the actual car-control work off to [PhevCarCommandService] --
 * a foreground service, not just a background thread, because `WifiNetworkFactory`
 * rejects `WifiNetworkSpecifier` requests from a process without foreground-level
 * importance (confirmed on-device: a plain background thread here gets rejected the
 * moment the app has no residual foreground importance from recent UI use).
 *
 * An allowed call is silently rejected (not answered) -- this is a "call this number
 * to trigger an action" appliance pattern, not a real call the user needs to pick up.
 */
class PhevCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""

        if (!CallAllowlist.isCallTriggerEnabled(this)) {
            respondToCall(callDetails, CallScreeningService.CallResponse.Builder().build())
            return
        }

        if (!CallAllowlist.isAllowed(this, number)) {
            BridgeLog.detail("Call from $number ignored (not on allowlist)")
            respondToCall(callDetails, CallScreeningService.CallResponse.Builder().build())
            return
        }

        BridgeLog.summary("Call received (allowlisted) -- triggering car command")
        respondToCall(
            callDetails,
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(false)
                .build()
        )

        val creds = CarCredentials.load(this)
        if (creds == null) {
            BridgeLog.summary("Failed: no saved car Wi-Fi credentials -- open the app once to save them")
            return
        }

        val mode = CallAllowlist.defaultMode(this)
        val duration = CallAllowlist.defaultDuration(this)
        val payload = Rc4CarClient.climateCommand(mode, duration)
        PhevCarCommandService.start(this, "call-trigger", payload)
    }
}
