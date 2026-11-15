package one.lmk.gridcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHttpProtocolTest {
    @Test
    fun parsesGetAndFrameRoutesStrippingQueryString() {
        val req = CastHttpProtocol.parseRequestLine("GET /frame_42.png?ts=99 HTTP/1.1")!!
        assertEquals("GET", req.method)
        assertEquals("/frame_42.png", req.path)
        assertTrue(CastHttpProtocol.isFrameRequest(req.path))
        assertTrue(CastHttpProtocol.isFrameRequest("/frame.png"))
        assertFalse(CastHttpProtocol.isFrameRequest("/"))
        assertNull(CastHttpProtocol.parseRequestLine("   "))
    }

    @Test
    fun identifiesPrivateIpv4AddressesSafely() {
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("192.168.1.42"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("10.0.0.5"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("172.16.0.1"))
        assertTrue(CastHttpProtocol.isSiteLocalIpv4Candidate("172.31.255.9"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("172.32.0.1"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("127.0.0.1"))
        assertFalse(CastHttpProtocol.isSiteLocalIpv4Candidate("::1"))
    }
}
