package app.tether.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * A self-managed fake phone line for end-to-end tests of the call path (InCallService → BLE →
 * CallKit) without the cellular network. Self-managed calls are not written to the call log.
 * Driven only by adb through DebugReceiver.
 */
class TestConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val num = request.extras?.getString("num")
            ?: request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString("num") ?: "5550100"
        return FakeConnection().apply {
            setAddress(Uri.fromParts("tel", num, null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("Tether test", TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
            current = this
        }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        FakeConnection().apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
            current = this
            android.os.Handler(mainLooper).postDelayed({ setActive() }, 1500)
        }

    class FakeConnection : Connection() {
        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
            audioModeIsVoip = true
        }
        override fun onAnswer() { setActive() }
        override fun onReject() = finish(DisconnectCause.REJECTED)
        override fun onDisconnect() = finish(DisconnectCause.LOCAL)
        override fun onAbort() = finish(DisconnectCause.CANCELED)
        override fun onHold() { setOnHold() }
        override fun onUnhold() { setActive() }
        override fun onPlayDtmfTone(c: Char) { Log.i("Tether", "fake line DTMF $c") }
        fun finish(cause: Int) {
            setDisconnected(DisconnectCause(cause))
            destroy()
            if (current === this) current = null
        }
    }

    companion object {
        const val ID = "tether-test"
        @Volatile var current: FakeConnection? = null

        fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, TestConnectionService::class.java), ID)

        fun register(ctx: Context) {
            val acct = PhoneAccount.builder(handle(ctx), "Tether test line")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(acct)
        }

        fun ringIn(ctx: Context, num: String) {
            register(ctx)
            val extras = Bundle().apply {
                putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply { putString("num", num) })
            }
            ctx.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle(ctx), extras)
        }

        /** The only numbers the test line dials out to: +10000000001 … +10000000009, exactly. */
        fun dialable(num: String) = Regex("\\+1000000000[1-9]").matches(num)

        fun endCurrent() = current?.finish(DisconnectCause.REMOTE)

        /**
         * An outgoing call on the test line: placed with this self-managed account's handle, so Telecom hands it to
         * this ConnectionService only (a self-managed account never reaches a SIM). Test-range numbers only.
         */
        fun dialOut(ctx: Context, num: String) {
            require(dialable(num)) { "test numbers +1000000000x only" }
            register(ctx)
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(ctx)) }
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", num, null), extras)
        }
    }
}
