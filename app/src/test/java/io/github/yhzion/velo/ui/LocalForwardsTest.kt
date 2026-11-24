package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.ForwardInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalForwardsTest {
    @Test
    fun aLoopbackLinkNamesTheRemotesHostAndPort() {
        val vite = loopbackLink("http://localhost:5173/")!!
        assertEquals("localhost", vite.remoteHost)
        assertEquals(5173, vite.port)
        val python = loopbackLink("http://127.0.0.1:8000/a/b?x=1#top")!!
        assertEquals("127.0.0.1", python.remoteHost)
        assertEquals(8000, python.port)
        assertEquals("::1", loopbackLink("http://[::1]:3000/")!!.remoteHost)
        assertEquals("0.0.0.0", loopbackLink("http://0.0.0.0:8080")!!.remoteHost)
        assertEquals("app.localhost", loopbackLink("http://app.localhost:3000/")!!.remoteHost)
    }

    @Test
    fun aLinkWithoutAPortUsesItsSchemesPort() {
        assertEquals(80, loopbackLink("http://localhost/")!!.port)
        assertEquals(443, loopbackLink("https://localhost/app")!!.port)
    }

    @Test
    fun thePhoneOpensTheLinkAtTheForwardsPortWithTheSamePathQueryAndFragment() {
        assertEquals("http://localhost:5173/", loopbackLink("http://localhost:5173/")!!.onPhone(5173))
        assertEquals("http://localhost:41000/", loopbackLink("http://localhost:5173/")!!.onPhone(41000))
        assertEquals("http://127.0.0.1:8000/a/b?x=1#top", loopbackLink("http://127.0.0.1:8000/a/b?x=1#top")!!.onPhone(8000))
        assertEquals("https://localhost:8443", loopbackLink("https://localhost:8443")!!.onPhone(8443))
        assertEquals("http://app.localhost:3000/", loopbackLink("http://app.localhost:3000/")!!.onPhone(3000))
    }

    @Test
    fun thePhoneListensOn127001Only() {
        // The phone's loopback listener: an IPv6 or any-address link is opened as localhost.
        assertEquals("http://localhost:3000/", loopbackLink("http://[::1]:3000/")!!.onPhone(3000))
        assertEquals("http://localhost:8080", loopbackLink("http://0.0.0.0:8080")!!.onPhone(8080))
        assertEquals("http://127.0.0.1:9000/", loopbackLink("http://127.0.0.2:9000/")!!.onPhone(9000))
    }

    @Test
    fun aLinkToAnotherHostOrSchemeOrABadPortIsNoLoopbackLink() {
        assertNull(loopbackLink("http://example.com:80/"))
        assertNull(loopbackLink("ftp://localhost:21/"))
        assertNull(loopbackLink("http://localhost:0/"))
        assertNull(loopbackLink("http://localhost:99999/"))
        assertNull(loopbackLink("not a url"))
    }

    @Test
    fun aForwardThatCannotBeMadeSaysWhy() {
        val link = loopbackLink("http://localhost:5173/")!!
        assertEquals("This remote's Rex server is too old to forward ports.",
            forwardFailure(ForwardAnswer.parse("""{"status":"error","code":"too_old","message":"This remote's Rex server is too old to forward ports."}"""), link))
        assertEquals("The remote is not connected.", forwardFailure(ForwardAnswer.parse("""{"status":"error","code":"NotConnected"}"""), link))
        assertEquals("Velo could not forward localhost:5173 (Timeout).", forwardFailure(ForwardAnswer.parse("""{"status":"error","code":"Timeout"}"""), link))
        assertEquals("Velo could not forward localhost:5173 (unreadable).", forwardFailure(ForwardAnswer.parse(null), link))
        assertNull(forwardFailure(ForwardAnswer.parse("""{"status":"ok","port":5173}"""), link))
        assertEquals(5173, ForwardAnswer.parse("""{"status":"ok","port":5173}""").port)
    }

    @Test
    fun aForwardIsListedByTheRemotesAddressAndThePhonesPort() {
        assertEquals("localhost:5173", ForwardInfo("block:f", "localhost", 5173, 5173, 0).label)
        assertEquals("[::1]:3000", ForwardInfo("block:f", "::1", 3000, 3000, 0).label)
        assertEquals("On this phone at 127.0.0.1:41000", forwardDetail(ForwardInfo("block:f", "localhost", 5173, 41000, 0)))
        assertEquals("On this phone at 127.0.0.1:5173 · 1 connection", forwardDetail(ForwardInfo("block:f", "localhost", 5173, 5173, 1)))
        assertEquals("On this phone at 127.0.0.1:5173 · 3 connections", forwardDetail(ForwardInfo("block:f", "localhost", 5173, 5173, 3)))
    }
}
