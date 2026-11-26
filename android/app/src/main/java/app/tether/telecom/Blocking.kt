package app.tether.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import app.tether.TetherApp

/**
 * Rejects calls from blocked numbers before they ring anywhere (Android phone, iPhone, Mac). Telecom binds the default
 * phone app's screening service for every incoming call; numbers in Android's own block list are normally
 * stopped even earlier by the system, this also covers Tether's own list.
 */
class TetherScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val num = details.handle?.schemeSpecificPart ?: ""
        // Before Android 10 only incoming calls are screened (and the direction field does not exist).
        val incoming = android.os.Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val blocked = incoming &&
            runCatching { (application as TetherApp).lists.isBlocked(num) }.getOrDefault(false)
        if (blocked) Log.i(CallRegistry.TAG, "screening: blocked caller rejected")
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(blocked).setRejectCall(blocked).setSkipNotification(blocked)
            .setSkipCallLog(false).build())
    }
}
