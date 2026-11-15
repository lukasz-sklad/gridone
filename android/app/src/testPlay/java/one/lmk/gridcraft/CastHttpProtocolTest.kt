package one.lmk.gridcraft

import java.io.BufferedReader
import java.io.StringReader
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHttpProtocolTest {
    @Test
    fun generatedTokensAre32HexCharsAndUnique() {
        val a = CastHttpProtocol.generateToken()
        val b = CastHttpProtocol.generateToken()
        assertEquals(32, a.length)
        assertEquals(32, b.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
    }

    @Test
    fun parseRequestActionRequiresMatchingTokenAndValidSeq() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(
            CastHttpProtocol.CastHttpAction.GET_PNG,
            CastHttpProtocol.parseRequestAction("GET /cast/$token/42.png?ts=99 HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.HEAD_PNG,
            CastHttpProtocol.parseRequestAction("HEAD /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.OPTIONS,
            CastHttpProtocol.parseRequestAction("OPTIONS /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.OPTIONS,
            CastHttpProtocol.parseRequestAction("OPTIONS * HTTP/1.1", token),
        )

        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET / HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET /frame_42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET /cast/wrongtoken/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET /cast/$token/../etc/passwd.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET /cast/$token/%2e%2e/1.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.NOT_FOUND,
            CastHttpProtocol.parseRequestAction("GET /cast/$token/abc.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.BAD_REQUEST,
            CastHttpProtocol.parseRequestAction("POST /cast/$token/42.png HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.BAD_REQUEST,
            CastHttpProtocol.parseRequestAction("GET /cast/$token/42.png\r\nEvil: 1 HTTP/1.1", token),
        )
        assertEquals(
            CastHttpProtocol.CastHttpAction.BAD_REQUEST,
            CastHttpProtocol.parseRequestAction("   ", token),
        )
    }

    @Test
    fun boundsOversizedLinesAndHeaderFloodsAndSanitizesHeaders() {
        val overlongReader = BufferedReader(StringReader("GET /" + "x".repeat(5000)))
        assertEquals(
            CastHttpProtocol.LineReadResult.TooLong,
            CastHttpProtocol.readLineBounded(overlongReader, CastHttpProtocol.MAX_REQUEST_LINE_CHARS),
        )

        val flood = (1..40).joinToString("\r\n") { "X-Hdr-$it: val" } + "\r\n\r\n"
        assertFalse(CastHttpProtocol.drainHeadersBounded(BufferedReader(StringReader(flood))))

        val valid = "Host: localhost\r\nAccept: image/png\r\n\r\n"
        assertTrue(CastHttpProtocol.drainHeadersBounded(BufferedReader(StringReader(valid))))

        val headerStr = String(
            CastHttpProtocol.buildResponseHeader(
                200,
                "OK\r\nSet-Cookie: pwn=1",
                "image/png",
                123,
                etag = "1\"\r\nInjected: bad",
            ),
            Charsets.US_ASCII,
        )
        assertFalse(headerStr.contains("Set-Cookie:"))
        assertFalse(headerStr.contains("Injected:"))
        assertTrue(headerStr.contains("X-Content-Type-Options: nosniff\r\n"))
        assertTrue(headerStr.contains("X-Frame-Options: DENY\r\n"))
        assertTrue(headerStr.contains("Content-Security-Policy: default-src 'none'; img-src 'self'\r\n"))
    }

    @Test
    fun selectPreferredLanIpv4SkipsLoopbackLinkLocalAndIpv6() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val linkLocal = InetAddress.getByName("169.254.1.5")
        val ipv6 = InetAddress.getByName("fe80::1")
        val lan = InetAddress.getByName("192.168.1.13")

        assertFalse(CastHttpProtocol.isUsableLanIpv4(loopback))
        assertFalse(CastHttpProtocol.isUsableLanIpv4(linkLocal))
        assertFalse(CastHttpProtocol.isUsableLanIpv4(ipv6))
        assertTrue(CastHttpProtocol.isUsableLanIpv4(lan))

        assertEquals(
            "192.168.1.13",
            CastHttpProtocol.selectPreferredLanIpv4(listOf(loopback, linkLocal, ipv6, lan)),
        )
        assertNull(CastHttpProtocol.selectPreferredLanIpv4(listOf(loopback, linkLocal, ipv6)))

        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("192.168.1.42"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("10.0.0.5"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("172.16.0.1"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("172.31.255.9"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("172.32.0.1"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("169.254.1.5"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("127.0.0.1"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("::1"))
    }
}
