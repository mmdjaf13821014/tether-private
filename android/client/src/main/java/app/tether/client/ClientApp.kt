package app.tether.client

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import app.tether.audio.OpusCodec
import app.tether.client.calls.CallAudio
import app.tether.client.calls.CallTracker
import app.tether.client.calls.MicPolicy
import app.tether.client.calls.MicWarning
import app.tether.client.calls.TelecomBridge
import app.tether.client.link.BleCentral
import app.tether.client.link.Identity
import app.tether.client.link.LinkEvents
import app.tether.client.link.LinkJournal
import app.tether.client.link.LinkService
import app.tether.client.model.ClientCore
import app.tether.client.model.ClientHello
import app.tether.client.model.ClientState
import app.tether.client.model.Effects
import app.tether.client.model.LinkPort
import app.tether.client.model.Problem
import app.tether.client.ui.CallActivity
import app.tether.core.AudioFrame
import app.tether.core.CallEntry
import app.tether.core.Message
import app.tether.core.PairingCode
import app.tether.core.Sms
import app.tether.core.ext.ExtensionLink
import app.tether.core.ext.ExtensionRegistry
import app.tether.client.ext.clientExtensions
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Left-to-right isolate around a number (so "+98…" keeps its plus sign in a Persian layout), and its end. */
private const val LRI = 0x2066
private const val PDI = 0x2069

/** Wires the link, the synced state, calls, call audio and notifications together. */
class ClientApp : Application(), LinkEvents {
    lateinit var identity: Identity; private set
    lateinit var journal: LinkJournal; private set
    lateinit var link: BleCentral; private set
    lateinit var state: ClientState; private set
    lateinit var calls: CallTracker; private set
    lateinit var extensions: ExtensionRegistry; private set
    lateinit var audio: CallAudio; private set
    lateinit var telecom: TelecomBridge; private set
    lateinit var notifier: Notifier; private set
    lateinit var core: ClientCore; private set
    val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "store").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val changePosted = AtomicBoolean(false)
    private val dataFile by lazy { File(filesDir, "tether-data.json") }
    @Volatile private var pendingSave: String? = null
    @Volatile var linkState = BleCentral.State.UNPAIRED; private set
    @Volatile var transport = "none"; private set
    var appVisible = false
        set(v) { field = v; state.appActive = v; if (v) link.appActive() }

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
        journal = LinkJournal(filesDir)
        notifier = Notifier(this)
        link = BleCentral(this, identity, this)
        val port = object : LinkPort {
            override val isConnected get() = link.isConnected
            override fun send(t: String, b: JSONObject, expectReply: Boolean) = link.send(t, b, expectReply)
        }
        state = ClientState(port, effects)
        runCatching { if (dataFile.exists()) state.load(JSONObject(dataFile.readText())) }.onFailure { journal.add("saved data not readable: ${it.message}") }
        audio = CallAudio(send = { link.sendAudio(it) }, log = { journal.add(it) }, hasMicPermission = { hasMicPermission() }, onMic = { changed() })
        telecom = TelecomBridge(this)
        calls = CallTracker(port, telecom, nameOf = { state.name(it) }, schedule = { ms, b -> main.postDelayed(b, ms) })
        val codecs = if (runCatching { OpusCodec.available() }.getOrDefault(false)) listOf("opus", "pcmu") else listOf("pcmu")
        // Extensions (protocol §7, docs/EXTENDING.md): register yours here. Handlers run on the main thread.
        extensions = ExtensionRegistry(log = { journal.add("ext: $it") }).apply {
            for (e in clientExtensions()) runCatching { register(e) }.onFailure { journal.add("ext: not registered: ${it.message}") }
            start()
        }
        val extLink = object : ExtensionLink {
            override val peerName get() = identity.peerName ?: "Android phone"
            override val peerKind = "phone"
            override val transport get() = this@ClientApp.transport
            override val inCall get() = calls.hasCalls
            override fun send(m: Message) = link.sendMessage(m)
        }
        core = ClientCore(state, calls, audio, ClientHello("android", deviceName(), codecs), extensions, extLink)
        telecom.register()
        notifier.channels()
        // JIT-compile the Opus encoder/decoder now: cold, the first seconds of a call cost several times more per frame.
        Thread({ runCatching { OpusCodec().apply { repeat(200) { i -> encode(ShortArray(320) { ((it * 37 + i) % 2000 - 1000).toShort() }).forEach { f -> decode(f) } } } } }, "opus-warmup")
            .apply { priority = Thread.MIN_PRIORITY }.start()
        if (identity.isPaired && hasLinkPermissions()) LinkService.start(this)
    }

    fun deviceName(): String = runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun hasLinkPermissions(): Boolean = linkPermissions().all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    fun hasMicPermission(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** What is wrong with this phone's microphone for the call screen and the main screen (null: nothing). */
    val micWarning: MicWarning? get() = MicPolicy.warning(hasMicPermission(), audio.recorderFailed, audio.captureSilent)

    fun linkPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    // ------------------------------------------------------------------ UI plumbing

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Coalesced: many changes in one burst redraw once. */
    fun changed() {
        if (changePosted.compareAndSet(false, true)) main.post { changePosted.set(false); listeners.forEach { runCatching { it() } } }
    }

    fun problem(kind: String, detail: String? = null) { state.problem = Problem(kind, detail); changed() }

    fun pair(code: PairingCode) {
        state.wipe()
        LinkService.start(this)
        link.pair(code)
    }

    fun unpair() {
        link.unpair()
        state.wipe()
        notifier.clearAll()
    }

    fun openCallScreen() {
        runCatching { startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Keeps the link service in the foreground; during a call also as a phone-call / microphone service. */
    fun service(callMode: Boolean) = LinkService.callMode(this, callMode)

    /** Dials from this phone through Telecom (onCreateOutgoingConnection asks the Android phone). */
    fun dial(number: String, sub: Int?) {
        val n = app.tether.client.model.dialable(number)
        if (n.isEmpty()) return
        if (!link.isConnected) { problem(Problem.NOT_CONNECTED); return }
        service(callMode = true)
        if (!telecom.placeCall(n, sub)) problem(Problem.CALL_FAILED)
    }

    private val countryIso: String by lazy {
        val tm = getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotEmpty() } ?: tm?.networkCountryIso?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().country).uppercase()
    }

    /** A number as people read it (PhoneNumberUtils with this phone's country), left-to-right even in Persian. */
    fun format(number: String): String {
        if (number.isEmpty()) return getString(R.string.unknown)
        val f = runCatching { PhoneNumberUtils.formatNumber(number, countryIso) }.getOrNull() ?: number
        return StringBuilder().appendCodePoint(LRI).append(f).appendCodePoint(PDI).toString()
    }

    fun displayName(number: String) = state.name(number) ?: format(number)

    // ------------------------------------------------------------------ effects of the synced state

    private val effects = object : Effects {
        override fun showSms(m: Sms, from: String?, sim: String?) = notifier.showSms(m, from ?: format(m.addr), sim)
        override fun clearSms(thread: Long) = notifier.clearSms(thread)
        override fun clearSmsIds(ids: List<Long>) = notifier.clearSmsIds(ids)
        override fun showMissedCall(c: CallEntry, from: String?, sim: String?) = notifier.showMissedCall(c, from ?: format(c.num), sim)
        override fun schedule(ms: Long, block: () -> Unit) { main.postDelayed(block, ms) }
        override fun changed() = this@ClientApp.changed()
        override fun save(json: String) {
            // Debounced: a sync of thousands of texts writes once.
            val first = pendingSave == null
            pendingSave = json
            if (first) io.execute {
                Thread.sleep(1000)
                val s = pendingSave ?: return@execute
                pendingSave = null
                runCatching { val tmp = File(dataFile.path + ".tmp"); tmp.writeText(s); tmp.renameTo(dataFile) }
            }
        }
    }

    // ------------------------------------------------------------------ link events (link thread → main)

    override fun onState(s: BleCentral.State) { linkState = s; changed() }
    override fun onUp(transport: String) { this.transport = transport; main.post { core.linkUp(); changed() } }
    override fun onDown() { transport = "none"; main.post { core.linkDown(); changed() } }
    override fun onMessage(m: Message) { main.post { core.message(m) } }
    override fun onAudio(f: AudioFrame) = core.audio(f)
    override fun onProblem(kind: String) { main.post { problem(kind) } }
    override fun log(line: String) = journal.add(line)
}
