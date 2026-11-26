// Hello, Tether: a complete encrypted session in memory between a minimal "phone" and a client, using only the
// public API of com.github.amirwmr.tether:tether-core. Run: ./gradlew run
import app.tether.audio.OpusCodec
import app.tether.core.AudioFrame
import app.tether.core.CentralHandshake
import app.tether.core.Kind
import app.tether.core.Message
import app.tether.core.P256
import app.tether.core.PairingCode
import app.tether.core.PeripheralHandshake
import app.tether.core.Proto
import app.tether.core.Record
import app.tether.core.RecordReader
import app.tether.core.SecureSession
import app.tether.core.T
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.sin

/** Turns a byte stream into records, as a real transport (L2CAP, GATT, TCP) would deliver it. */
fun Record.through(reader: RecordReader): Record = reader.run { append(encoded()); next()!! }

fun main() {
    // 1. The phone opens pairing: a long-term P-256 key and a one-time token, shown as a QR code.
    val phoneKey = P256.generate()
    val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
    // Since 1.2 the QR code holds the link form (tether://pair?c=…); typed codes are tether1:…; parse reads both.
    val qr = PairingCode(P256.encode(phoneKey.public), token, "Demo phone").link()
    println("pairing code: ${qr.take(24)}…")

    // 2. The client scans it and starts the handshake with its own long-term key.
    val code = PairingCode.parse(qr) ?: error("not a Tether pairing code")
    val clientKey = P256.generate()
    val client = CentralHandshake(clientKey, code.peripheralKey, code.token)
    val phone = PeripheralHandshake(phoneKey.private)
    val toPhone = RecordReader()
    val toClient = RecordReader()

    val ack = phone.receiveHello(client.hello().through(toPhone), isKnown = { false }, token = token)
    check(ack.kind == Kind.HELLO_ACK) { "phone rejected the client" }
    val (clientSession, clientFin) = client.receive(ack.through(toClient))
    val (phoneSession, phoneFin) = phone.receiveFinish(clientFin.through(toPhone))
    client.verifyFinish(clientSession, phoneFin.through(toClient))
    println("handshake done: paired with ${code.deviceName}; phone remembers the client's key (${phone.hello!!.staticKey.size} bytes)")

    // 3. Control messages: the phone sends its status, the client sends a text.
    fun SecureSession.send(m: Message) = seal(Proto.CH_CONTROL, m.toJson())
    fun SecureSession.receive(r: Record): Pair<Int, ByteArray> = open(r)

    val status = Message(T.STATUS, JSONObject().put("battery", 81).put("name", "Demo phone"))
    val (ch1, body1) = clientSession.receive(phoneSession.send(status).through(toClient))
    check(ch1 == Proto.CH_CONTROL)
    println("client got: ${Message.parse(body1)} ${Message.parse(body1).b}")

    val text = Message(T.SMS_SEND, JSONObject().put("to", "+10000000001").put("body", "Hello from the JVM"), id = 1)
    val (_, body2) = phoneSession.receive(clientSession.send(text).through(toPhone))
    val got = Message.parse(body2)
    println("phone got: $got to=${got.b.getString("to")} body=\"${got.b.getString("body")}\"")
    phoneSession.send(Message(T.SMS_RESULT, JSONObject().put("ok", true).put("state", "sent"), re = got.id))
        .through(toClient).let { println("client got: ${Message.parse(clientSession.receive(it).second)}") }

    // 4. Audio: one 20 ms Opus frame (16 kHz mono) on the audio channel.
    val opus = OpusCodec()
    val pcm = ShortArray(320) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
    val packet = opus.encode(pcm).single()
    val sealed = clientSession.seal(Proto.CH_AUDIO, AudioFrame(AudioFrame.OPUS, 0, packet).encoded())
    val (ch3, body3) = phoneSession.receive(sealed.through(toPhone))
    check(ch3 == Proto.CH_AUDIO)
    val frame = AudioFrame.parse(body3)!!
    val decoded = OpusCodec().decode(frame.payload)!!
    println("audio: ${pcm.size} samples -> ${packet.size} bytes Opus -> ${decoded.size} samples (seq ${frame.seq})")
    opus.release()
    println("ok")
}
