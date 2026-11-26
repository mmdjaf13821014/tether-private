package app.tether.link

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * "Share VPN": lets the iPhone and the Mac use the Android phone's VPN.
 *
 * Android forwards tethered traffic (hotspot, Bluetooth PAN) straight to the mobile / Wi-Fi network: tethering
 * never uses a VpnService, and without root the routing cannot be changed. A proxy running in Tether is an
 * ordinary app, so its own connections go through the VPN. One port serves:
 *   - HTTP proxy (absolute-form requests) and HTTPS via CONNECT,
 *   - SOCKS5 (no authentication, CONNECT),
 *   - GET /proxy.pac: "PROXY <this address>:<port>" while sharing is on, "DIRECT" while it is off,
 *     so an iPhone set to "Automatic" proxy on the hotspot follows the switch.
 * It listens only on the IPv4 addresses of tethering interfaces (hotspot, Bluetooth PAN, USB), never on the
 * mobile, Wi-Fi-client or VPN interfaces, so nothing outside the phone's own tethered devices can reach it.
 */
class ShareProxy(private val ctx: Context) {
    companion object {
        const val TAG = "Tether"
        const val PORT = 8118
        private const val IDLE_MS = 5 * 60_000
        /** Downstream interface names Android uses for tethering on this phone (see `dumpsys tethering`). */
        /** Samsung's hotspot is swlan0; plain wlanN is the Wi-Fi client and is never listened on. */
        private val TETHER_IF = Regex("(swlan|softap|ap_br_\\w+|bt-pan|rndis|ncm|usb)\\d*")
        /** At most this many proxied connections at once (each takes two threads). */
        private const val MAX_CONNECTIONS = 128
    }

    /** Sharing on: proxy requests are forwarded and the PAC file points at the proxy. */
    @Volatile var sharing = false
    private val listeners = ConcurrentHashMap<String, ServerSocket>()   // ip → listener
    private val ifaceOf = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "share-proxy").apply { isDaemon = true } }
    /** Debug only (adb): also listen on 127.0.0.1, to test through `adb forward` without joining the hotspot. */
    @Volatile var loopbackForTests = false

    /** Tethering interfaces and their IPv4 addresses right now. Client interfaces of real networks are excluded. */
    fun tetherAddresses(): List<Pair<String, String>> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // Interfaces of networks this phone uses itself (mobile, Wi-Fi client, VPN): never listen there.
        // (Recent Android also registers the hotspot as a "local network" without internet: that one is allowed.)
        val upstream = try {
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { n ->
                val nc = cm.getNetworkCapabilities(n)
                nc == null || nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            }.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        } catch (t: Throwable) { setOf("wlan0") }
        val out = ArrayList<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.name in upstream || !TETHER_IF.matches(ni.name)) continue
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { out.add(ni.name to it.hostAddress!!) }
            }
        } catch (t: Throwable) { Log.w(TAG, "share: interfaces unreadable: ${t.message}") }
        return out
    }

    /** Opens / closes listeners to match the tethering interfaces (called periodically and on net changes). */
    @Synchronized fun refresh() {
        val want = tetherAddresses().toMap(LinkedHashMap()).entries.associate { (iface, ip) -> ip to iface }.toMutableMap()
        if (loopbackForTests) want["127.0.0.1"] = "lo"
        for ((ip, s) in listeners) if (ip !in want) {
            Log.i(TAG, "share: stopped on ${ifaceOf[ip] ?: ip}")
            runCatching { s.close() }; listeners.remove(ip); ifaceOf.remove(ip)
        }
        for ((ip, iface) in want) if (!listeners.containsKey(ip)) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName(ip), PORT), 64)
                listeners[ip] = s; ifaceOf[ip] = iface
                pool.execute { accept(s) }
                Log.i(TAG, "share: listening on $iface:$PORT")
            } catch (t: Throwable) { Log.w(TAG, "share: cannot listen on $iface: ${t.message}") }
        }
    }

    /** For net.state: where clients reach the proxy. */
    fun json(): JSONObject = JSONObject().put("port", PORT).put("addrs", JSONArray(listeners.keys.filter { it != "127.0.0.1" }.map { ip ->
        JSONObject().put("iface", ifaceOf[ip] ?: "").put("ip", ip).put("kind", kindOf(ifaceOf[ip] ?: "")).put("pac", "http://$ip:$PORT/proxy.pac")
    }))

    private fun kindOf(iface: String) = when {
        iface.startsWith("bt-pan") -> "bt"
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("ncm") -> "usb"
        else -> "hotspot"
    }

    private val slots = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (t: Throwable) { break }
            if (!slots.tryAcquire()) { runCatching { c.close() }; continue }
            pool.execute { try { serve(c, s.inetAddress.hostAddress ?: "") } finally { slots.release() } }
        }
    }

    // ------------------------------------------------------------------ one client connection

    private fun serve(client: Socket, localIp: String) {
        client.soTimeout = 30_000
        client.tcpNoDelay = true
        try {
            val inp = client.getInputStream()
            val first = inp.read()
            if (first < 0) return
            if (first == 5) socks(client, inp) else http(client, inp, first, localIp)
        } catch (t: Throwable) {
            // client went away / upstream unreachable: just close
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readLine(inp: InputStream, firstByte: Int = -1): String {
        val sb = StringBuilder()
        if (firstByte >= 0) sb.append(firstByte.toChar())
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun http(client: Socket, inp: InputStream, first: Int, localIp: String) {
        val request = readLine(inp, first)
        val headers = ArrayList<String>()
        while (true) { val l = readLine(inp); if (l.isEmpty()) break; headers.add(l); if (headers.size > 100) return }
        val parts = request.split(" ")
        if (parts.size < 3) return
        val (method, target) = parts[0] to parts[1]
        val out = client.getOutputStream()
        if (method == "GET" && (target == "/proxy.pac" || target == "/wpad.dat")) { pac(out, localIp); return }
        // Sharing off: the PAC file says DIRECT and nothing is proxied (a client with a cached PAC fails over
        // to DIRECT, or works again after rejoining the hotspot).
        if (!sharing && localIp != "127.0.0.1") return
        if (method == "CONNECT") {
            val (host, port) = ProxyRequests.connectTarget(target)
            val up = connect(host, port) ?: run { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); out.flush()
            pipe(client, up)
            return
        }
        // Absolute-form request: forward it as origin-form, one request per connection.
        val fwd = ProxyRequests.forward(method, target, parts[2], headers)
        if (fwd == null) {
            val body = "Tether VPN sharing proxy. Set this address as the HTTP / HTTPS / SOCKS proxy, or use /proxy.pac.\n"
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            return
        }
        val up = connect(fwd.host, fwd.port)
            ?: run { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        up.getOutputStream().write(fwd.head.toByteArray(Charsets.ISO_8859_1))
        pipe(client, up)
    }

    private fun pac(out: OutputStream, localIp: String) {
        val body = ProxyRequests.pac(sharing, localIp, PORT)
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nCache-Control: no-cache, max-age=0\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun socks(client: Socket, inp: InputStream) {
        if (!sharing && client.localAddress?.isLoopbackAddress != true) return
        val out = client.getOutputStream()
        val nMethods = inp.read(); if (nMethods <= 0) return
        repeat(nMethods) { inp.read() }
        out.write(byteArrayOf(5, 0)); out.flush()                     // no authentication
        val (cmd, host, port) = ProxyRequests.socksRequest(inp) ?: return
        if (cmd != 1) { out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return }   // CONNECT only
        val up = connect(host, port)
        if (up == null) { out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return }
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        pipe(client, up)
    }

    /** Outgoing connection from this app: it follows the phone's default network, i.e. the VPN when it is up. */
    private fun connect(host: String, port: Int): Socket? = try {
        // Never into the phone itself or the tethered side: loopback (the helper listens there), link-local,
        // any-local, and this phone's own addresses. Checked after DNS, so names cannot get round it.
        val addr = InetAddress.getByName(host)
        val own = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet() } catch (t: Throwable) { emptySet() }
        if (ProxyRequests.forbidden(addr, own)) null
        else Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(addr, port), 15_000); soTimeout = IDLE_MS }
    } catch (t: Throwable) { null }

    private fun pipe(a: Socket, b: Socket) {
        a.soTimeout = IDLE_MS   // an idle tunnel is closed after 5 minutes
        val t = Thread({ copy(b.getInputStream(), a.getOutputStream()); runCatching { a.shutdownOutput() } }, "share-pipe")
        t.isDaemon = true
        t.start()
        copy(a.getInputStream(), b.getOutputStream())
        runCatching { b.shutdownOutput() }
        t.join(120_000)
        runCatching { b.close() }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try { while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } } catch (_: Throwable) {}
    }
}
