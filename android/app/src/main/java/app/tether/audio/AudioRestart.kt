package app.tether.audio

import org.json.JSONObject

/**
 * Whether a bridged call's audio is restarted because the helper came up on an older method (issue #15: it was
 * restarted every 1.5 s, three times in a row, with "auto" over the self-test's choice, cutting working audio; five
 * starts in one second on the reference phone, 2026-11-24).
 *
 * The helper now waits for the call's audio mode before it opens anything (helper OpenGate), so falling back means the
 * interception API refused in a call's own mode. One restart is still worth it then, and only then: on Android 13+,
 * for the call's current start (never an INFO of an earlier one), with the method choice that start used (never
 * overriding the self-test's choice of an older method), when the interception API's refusal was the "not available in
 * mode" kind, the call active, and [waitMs] after that INFO.
 */
object AudioRestart {
    const val MAX = 1

    fun wanted(sdk: Int, info: JSONObject?, choice: String, restarts: Int, msSinceInfo: Long, callActive: Boolean, waitMs: Long = 3000): Boolean {
        if (info == null || sdk < 33 || restarts >= MAX || !callActive || msSinceInfo < waitMs) return false
        if (choice != "auto" && choice != "intercept") return false
        val capture = info.optString("capture"); val inject = info.optString("inject")
        if (capture == "intercept" && inject == "intercept") return false
        val errors = info.optJSONArray("errors") ?: return false
        return (0 until errors.length()).any { val e = errors.optString(it); e.contains(":intercept ") && e.contains("not available in mode") }
    }
}
