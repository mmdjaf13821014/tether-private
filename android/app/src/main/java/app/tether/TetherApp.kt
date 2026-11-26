package app.tether

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.tether.data.CallLogRepo
import app.tether.data.ContactsRepo
import app.tether.data.SimRepo
import app.tether.data.SmsRepo
import app.tether.link.Hub
import app.tether.link.Keys
import app.tether.telecom.CallRegistry

class TetherApp : Application() {
    lateinit var keys: Keys; private set
    lateinit var sms: SmsRepo; private set
    lateinit var contacts: ContactsRepo; private set
    lateinit var sims: SimRepo; private set
    lateinit var callLog: CallLogRepo; private set
    lateinit var lists: app.tether.data.Lists; private set
    lateinit var hub: Hub; private set
    val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        keys = Keys(this)
        sms = SmsRepo(this)
        contacts = ContactsRepo(this)
        sims = SimRepo(this)
        callLog = CallLogRepo(this, sims)
        lists = app.tether.data.Lists(this)
        CallRegistry.app = this
        hub = Hub(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LINK, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SETUP, getString(R.string.channel_setup), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FIND, getString(R.string.channel_find), NotificationManager.IMPORTANCE_HIGH))
        ensureLinkService()
    }

    /**
     * Whatever started this process (a call, a notification action, a debug hook, a late boot or update broadcast),
     * the link service should be running once the app is set up: Samsung sometimes holds back MY_PACKAGE_REPLACED
     * after an update, and until then nothing would start it, so the iPhone could not link and calls were not bridged.
     * An app exempt from battery optimisation (part of setup) may start it from the background; refusals are logged.
     */
    private fun ensureLinkService() {
        if (app.tether.link.LinkService.running != null) return
        val bt = if (android.os.Build.VERSION.SDK_INT >= 31) android.Manifest.permission.BLUETOOTH_ADVERTISE else android.Manifest.permission.BLUETOOTH
        if (checkSelfPermission(bt) != android.content.pm.PackageManager.PERMISSION_GRANTED) return   // not set up yet
        try { app.tether.link.LinkService.start(this) }
        catch (t: Throwable) { android.util.Log.w("Tether", "link service not started at process start: ${t.javaClass.simpleName}") }
    }

    /** Same path as the notification's RemoteInput: pair with adbd, grant ourselves what we need, start the helper. */
    fun adbPairAndBootstrap(code: String): String {
        app.tether.adb.Adb.get(this).pairWithCode(code)
        prefs.edit().putBoolean("adb_paired", true).apply()
        val grants = app.tether.adb.Adb.bootstrap(this)
        val helper = hub.helper.ensureRunning() ?: "helper running"
        hub.refresh()
        return "paired; grants: ${grants.trim().ifEmpty { "ok" }}; $helper"
    }

    fun adbShell(cmd: String): String = app.tether.adb.Adb.get(this).shell(cmd)

    companion object {
        const val CH_LINK = "link"
        const val CH_CALLS = "calls"
        const val CH_SETUP = "setup"
        const val CH_FIND = "find"
    }
}
