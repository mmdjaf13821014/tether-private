package app.tether.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Carrier (USSD) codes such as *123#: validation shared by the message handler and the debug hook. */
object UssdCode {
    private val re = Regex("^[*#][0-9*#]{1,30}#$")
    fun valid(code: String) = re.matches(code)
}

/** Sends one USSD request on a chosen SIM and waits (at most 30 s) for the carrier's answer. Blocks: call off the hub thread. */
class Ussd(private val ctx: Context) {
    companion object { const val TIMEOUT_S = 30L }

    /** `{ok, text?, err?}`. Never throws. */
    fun send(code: String, sub: Int?): JSONObject {
        if (!UssdCode.valid(code)) return JSONObject().put("ok", false).put("err", "Not a carrier code (like *123#)")
        return try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val t = if (sub != null && sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(sub) else tm
            val done = CountDownLatch(1)
            val out = AtomicReference<JSONObject>()
            t.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                    out.set(JSONObject().put("ok", true).put("text", response.toString())); done.countDown()
                }
                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                    val why = if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) "The network has no service for this code" else "The carrier refused the code"
                    out.set(JSONObject().put("ok", false).put("err", why)); done.countDown()
                }
            }, Handler(Looper.getMainLooper()))
            if (done.await(TIMEOUT_S, TimeUnit.SECONDS)) out.get() else JSONObject().put("ok", false).put("err", "The carrier did not answer in 30 seconds")
        } catch (e: SecurityException) {
            JSONObject().put("ok", false).put("err", "Android denied the request (phone permission)")
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("err", t.message ?: t.javaClass.simpleName)
        }
    }
}
