package app.tether.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

/** Share VPN proxy: what tethered clients send, and where it may never go. */
class ProxyRequestsTest {
    @Test fun pacPointsAtTheProxyOnlyWhileSharing() {
        val on = ProxyRequests.pac(true, "192.168.44.1", 8899)
        assertTrue(on.contains("PROXY 192.168.44.1:8899; SOCKS5 192.168.44.1:8899; DIRECT"))
        assertTrue(on.contains("isPlainHostName(host)"))
        assertEquals("function FindProxyForURL(url, host) { return \"DIRECT\"; }", ProxyRequests.pac(false, "192.168.44.1", 8899))
    }

    @Test fun connectTargets() {
        assertEquals("example.com" to 443, ProxyRequests.connectTarget("example.com:443"))
        assertEquals("example.com" to 8443, ProxyRequests.connectTarget("example.com:8443"))
        assertEquals("2001:db8::1" to 443, ProxyRequests.connectTarget("[2001:db8::1]:443"))
        assertEquals("example.com" to 443, ProxyRequests.connectTarget("example.com:abc"))
    }

    @Test fun plainHttpIsRewrittenToOriginFormWithoutProxyHeaders() {
        val f = ProxyRequests.forward("GET", "http://example.com:8080/a/b?q=1", "HTTP/1.1",
            listOf("Host: example.com", "Proxy-Authorization: x", "Proxy-Connection: keep-alive", "Connection: keep-alive", "Keep-Alive: 5", "Accept: */*"))!!
        assertEquals("example.com", f.host); assertEquals(8080, f.port)
        assertEquals("GET /a/b?q=1 HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nConnection: close\r\n\r\n", f.head)
        val g = ProxyRequests.forward("GET", "http://example.com", "HTTP/1.0", emptyList())!!
        assertEquals(80, g.port); assertTrue(g.head.startsWith("GET / HTTP/1.0\r\n"))
    }

    @Test fun nonProxyRequestsAreRefused() {
        for (t in listOf("/index.html", "https://example.com/", "ftp://example.com/", "http://", "::bad uri", ""))
            assertNull(t, ProxyRequests.forward("GET", t, "HTTP/1.1", emptyList()))
    }

    private fun socks(vararg b: Int) = ProxyRequests.socksRequest(ByteArrayInputStream(ByteArray(b.size) { b[it].toByte() }))

    @Test fun socksRequests() {
        assertEquals(ProxyRequests.Socks(1, "93.184.216.34", 443), socks(5, 1, 0, 1, 93, 184, 216, 34, 1, 187))
        assertEquals(ProxyRequests.Socks(1, "a.io", 80), socks(5, 1, 0, 3, 4, 'a'.code, '.'.code, 'i'.code, 'o'.code, 0, 80))
        assertEquals("2001:db8:0:0:0:0:0:1", socks(5, 1, 0, 4, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 80)!!.host)
        assertEquals(2, socks(5, 2, 0, 1, 1, 2, 3, 4, 0, 80)!!.cmd)   // BIND: parsed, refused by the proxy
    }

    @Test fun malformedSocksRequestsAreDropped() {
        assertNull(socks(4, 1, 0, 1, 1, 2, 3, 4, 0, 80))          // wrong version
        assertNull(socks(5, 1, 0, 9, 1, 2, 3, 4, 0, 80))          // unknown address type
        assertNull(socks(5, 1, 0, 1, 1, 2))                        // cut short in the address
        assertNull(socks(5, 1, 0, 1, 1, 2, 3, 4, 0))               // cut short in the port
        assertNull(socks(5, 1, 0, 3, 0, 0, 80))                    // empty name
        assertNull(socks(5, 1, 0, 3, 10, 'a'.code))                // name shorter than its length
        assertNull(socks())
    }

    @Test fun neverIntoThePhoneItself() {
        val own = setOf(InetAddress.getByName("192.168.44.1"))
        for (a in listOf("127.0.0.1", "::1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "192.168.44.1"))
            assertTrue(a, ProxyRequests.forbidden(InetAddress.getByName(a), own))
        assertFalse(ProxyRequests.forbidden(InetAddress.getByName("93.184.216.34"), own))
    }
}
