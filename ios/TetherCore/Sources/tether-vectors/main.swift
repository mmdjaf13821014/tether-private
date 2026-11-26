// Generates protocol/vectors.json from fixed inputs with CryptoKit.
// Usage: swift run tether-vectors <output path>
import CryptoKit
import Foundation
import TetherCore

func key(_ label: String) -> P256.KeyAgreement.PrivateKey {
    let scalar = Data(SHA256.hash(data: Data(label.utf8)))
    return try! P256.KeyAgreement.PrivateKey(rawRepresentation: scalar)
}

func bytes(_ label: String, _ n: Int) -> Data { Data(SHA256.hash(data: Data(label.utf8))).prefix(n) }

func handshakeVector(mode: HandshakeMode) -> [String: Any] {
    let sP = key("tether test s_P"), sC = key("tether test s_C")
    let eP = key("tether test e_P \(mode)"), eC = key("tether test e_C \(mode)")
    let nC = bytes("tether test n_C \(mode)", 16), nP = bytes("tether test n_P \(mode)", 16)
    let token: Data? = mode == .pair ? bytes("tether test token", 16) : nil

    let central = CentralHandshake(staticKey: sC, peerStatic: sP.publicKey.x963Representation,
                                   token: token, ephemeral: eC, nonce: nC)
    let peripheral = PeripheralHandshake(staticKey: sP, ephemeral: eP, nonce: nP)
    let hello = central.hello()
    let ack = try! peripheral.receiveHello(hello, paired: sC.publicKey.x963Representation, token: token)
    let (cSession, cFin) = try! central.receive(ack)
    let (pSession, pFin) = try! peripheral.receiveFinish(cFin)
    try! CentralHandshake.verifyFinish(cSession, keys: central.keys!, record: pFin)

    let ping = try! Control.encode(MsgType.ping, PingBody(ts: 1_700_000_000_000), id: 1)
    let c2p1 = try! cSession.seal(channel: Proto.channelControl, ping)
    let frame = AudioFrame(codec: .pcmu, seq: 7, payload: G711.encode([0, 1000, -1000, 32767])).encoded()
    let p2c1 = try! pSession.seal(channel: Proto.channelAudio, frame)
    _ = try! pSession.open(c2p1)
    _ = try! cSession.open(p2c1)

    let k = central.keys!
    return [
        "mode": mode == .pair ? "pair" : "resume",
        "s_P_priv": sP.rawRepresentation.hex, "S_P": sP.publicKey.x963Representation.hex,
        "s_C_priv": sC.rawRepresentation.hex, "S_C": sC.publicKey.x963Representation.hex,
        "e_P_priv": eP.rawRepresentation.hex, "E_P": eP.publicKey.x963Representation.hex,
        "e_C_priv": eC.rawRepresentation.hex, "E_C": eC.publicKey.x963Representation.hex,
        "n_C": nC.hex, "n_P": nP.hex,
        "token": token?.hex ?? "",
        "dh1": try! KeySchedule.ecdh(eC, eP.publicKey.x963Representation).hex,
        "dh2": try! KeySchedule.ecdh(eC, sP.publicKey.x963Representation).hex,
        "dh3": try! KeySchedule.ecdh(sC, eP.publicKey.x963Representation).hex,
        "hello_record": hello.encoded().hex,
        "hello_ack_record": ack.encoded().hex,
        "th": k.transcriptHash.hex,
        "k_c2p": k.c2p.hex, "k_p2c": k.p2c.hex,
        "fin_c2p_record": cFin.encoded().hex,
        "fin_p2c_record": pFin.encoded().hex,
        "c2p_ctr1_plain": (Data([Proto.channelControl]) + ping).hex,
        "c2p_ctr1_record": c2p1.encoded().hex,
        "p2c_ctr1_plain": (Data([Proto.channelAudio]) + frame).hex,
        "p2c_ctr1_record": p2c1.encoded().hex,
    ]
}

let sP = key("tether test s_P")
let pairing = PairingCode(peripheralKey: sP.publicKey.x963Representation,
                          token: bytes("tether test token", 16), deviceName: "Android phone · تست")
let pcm: [Int16] = [0, 1, -1, 100, -100, 1000, -1000, 8000, -8000, 32767, -32768]
let out: [String: Any] = [
    "version": 1,
    "handshakes": [handshakeVector(mode: .pair), handshakeVector(mode: .resume)],
    "pairing_code": ["string": pairing.string, "link": pairing.link, "S_P": pairing.peripheralKey.hex,
                     "token": pairing.token.hex, "name": pairing.deviceName],
    "g711": ["pcm": pcm.map(Int.init), "ulaw": G711.encode(pcm).hex,
             "decoded": G711.decode(G711.encode(pcm)).map(Int.init)],
    "otp": [
        ["text": "Your verification code is 482913. Do not share it.", "code": "482913"],
        ["text": "کد تایید شما: ۵۳۲۱۸", "code": "53218"],
        ["text": "Meet at 1930 near gate 4", "code": NSNull()],
        ["text": "G-7741 is your Google verification code.", "code": "7741"],
    ],
]
let json = try! JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "vectors.json"
try! json.write(to: URL(fileURLWithPath: path))
print("wrote \(path)")
