package app.tether.link

import java.io.InputStream
import java.net.InetAddress

/** Request parsing of the Share VPN proxy (ShareProxy), kept free of sockets so it is unit-tested (ProxyRequestsTest). */
object ProxyRequests {
    /** The PAC file: through the proxy (HTTP, then SOCKS5, then direct) while sharing, DIRECT otherwise; local names always direct. */
    fun pac(sharing: Boolean, ip: String, port: Int): String = if (sharing) """
            function FindProxyForURL(url, host) {
              if (isPlainHostName(host) || isInNet(host, "10.0.0.0", "255.0.0.0") || isInNet(host, "172.16.0.0", "255.240.0.0") ||
                  isInNet(host, "192.168.0.0", "255.255.0.0") || isInNet(host, "127.0.0.0", "255.0.0.0") || shExpMatch(host, "*.local"))
                return "DIRECT";
              return "PROXY $ip:$port; SOCKS5 $ip:$port; DIRECT";
            }
        """.trimIndent() else "function FindProxyForURL(url, host) { return \"DIRECT\"; }"

    /** A CONNECT target `host:port` (IPv6 in brackets); port 443 when missing or not a number. */
    fun connectTarget(target: String): Pair<String, Int> =
        target.substringBeforeLast(':').trim('[', ']') to (target.substringAfterLast(':').toIntOrNull() ?: 443)

    data class Forward(val host: String, val port: Int, val head: String)

    /**
     * An absolute-form plain-HTTP request rewritten for the origin: origin-form path, hop-by-hop and proxy headers
     * dropped, one request per connection. Null when it is not an absolute http:// URL (the proxy answers 400).
     */
    fun forward(method: String, target: String, version: String, headers: List<String>): Forward? {
        val uri = runCatching { java.net.URI(target) }.getOrNull()
        if (uri?.host == null || uri.scheme?.lowercase() != "http") return null
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val sb = StringBuilder("$method $path $version\r\n")
        for (h in headers) {
            val k = h.substringBefore(':').trim().lowercase()
            if (k.startsWith("proxy-") || k == "connection" || k == "keep-alive") continue
            sb.append(h).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        return Forward(uri.host, if (uri.port > 0) uri.port else 80, sb.toString())
    }

    data class Socks(val cmd: Int, val host: String, val port: Int)

    /** The SOCKS5 request after the greeting (VER CMD RSV ATYP ADDR PORT); null when malformed or cut short. */
    fun socksRequest(inp: InputStream): Socks? {
        if (inp.read() != 5) return null
        val cmd = inp.read(); if (inp.read() < 0) return null
        val host = when (inp.read()) {
            1 -> InetAddress.getByAddress(read(inp, 4) ?: return null).hostAddress
            3 -> { val n = inp.read(); if (n <= 0) return null; read(inp, n)?.toString(Charsets.US_ASCII) }
            4 -> InetAddress.getByAddress(read(inp, 16) ?: return null).hostAddress
            else -> null
        } ?: return null
        val hi = inp.read(); val lo = inp.read()
        if (hi < 0 || lo < 0) return null
        return Socks(cmd, host, (hi shl 8) or lo)
    }

    /** Destinations the proxy never opens: the phone itself (the helper listens on loopback), link-local, any, multicast. */
    fun forbidden(addr: InetAddress, own: Set<InetAddress>) =
        addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress || addr in own

    private fun read(inp: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) return null; off += r }
        return b
    }
}
