package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.ForwardInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun aForwardOfTheSameAddressIsTheLinksForward() {
        val forwards = listOf(ForwardInfo("block:f", "localhost", 5173, 41000, 0), ForwardInfo("block:g", "::1", 3000, 3000, 0))
        assertTrue(hasForward(forwards, loopbackLink("http://localhost:5173/a")!!))
        assertTrue(hasForward(forwards, loopbackLink("http://[::1]:3000/")!!))
        // Another port, and another name for the loopback: the core makes a forward of its own for each.
        assertFalse(hasForward(forwards, loopbackLink("http://localhost:5174/")!!))
        assertFalse(hasForward(forwards, loopbackLink("http://127.0.0.1:5173/")!!))
        assertFalse(hasForward(emptyList(), loopbackLink("http://localhost:5173/")!!))
    }

    @Test
    fun theForwardQuestionNamesTheRemoteAndThePortAndShowsTheLink() {
        val link = loopbackLink("http://localhost:5173/app?x=1")!!
        assertEquals("http://localhost:5173/app?x=1", link.url)
        assertEquals("Forward port 5173?", forwardQuestionTitle(link))
        assertEquals(
            "http://localhost:5173/app?x=1\n\nA program in the terminal printed this link. To open it, Velo forwards port 5173 of workstation to this phone. " +
                "While the forward is open, apps on this phone can reach that port of workstation. It stays open until you stop it in Settings → Remotes.",
            forwardQuestionText(link, "workstation"),
        )
        val long = loopbackLink("http://localhost:5173/" + "a".repeat(500))!!
        assertEquals(long.url.take(LINK_QUESTION_CHARS) + "…", forwardQuestionText(long, "workstation").substringBefore("\n"))
    }

    @Test
    fun aTapOnALoopbackLinkAsksBeforeItMakesANewForward() {
        val link = loopbackLink("http://localhost:5173/")!!
        var forwarded = 0
        val question = forwardTap(link, hidden = false, forwards = emptyList(), remote = "workstation") { forwarded++ }!!
        assertEquals("Forward port 5173?", question.title)
        assertEquals(forwardQuestionText(link, "workstation"), question.text)
        assertEquals("Forward", question.confirm)
        // Declined, the question goes and nothing was forwarded.
        assertEquals(0, forwarded)
        question.open()
        assertEquals(1, forwarded)
    }

    @Test
    fun aTapOnALinkWhoseForwardExistsOpensItWithoutAQuestion() {
        val link = loopbackLink("http://localhost:5173/")!!
        var opened = 0
        assertNull(forwardTap(link, hidden = false, forwards = listOf(ForwardInfo("block:f", "localhost", 5173, 5173, 0)), remote = "workstation") { opened++ })
        assertEquals(1, opened)
    }

    @Test
    fun aHiddenLoopbackLinkGetsOneQuestionThatShowsItsTarget() {
        val link = loopbackLink("http://localhost:5173/admin")!!
        var opened = 0
        // No forward yet: the forward question shows the link.
        val forward = forwardTap(link, hidden = true, forwards = emptyList(), remote = "workstation") { opened++ }!!
        assertEquals("Forward port 5173?", forward.title)
        assertTrue(forward.text.startsWith("http://localhost:5173/admin\n"))
        // Its forward exists: the question of a hidden web link.
        val shown = forwardTap(link, hidden = true, forwards = listOf(ForwardInfo("block:f", "localhost", 5173, 5173, 0)), remote = "workstation") { opened++ }!!
        assertEquals("Open http link?", shown.title)
        assertEquals(hiddenLinkText(LinkTap.Open("http://localhost:5173/admin")), shown.text)
        assertEquals("Open", shown.confirm)
        assertEquals(0, opened)
        shown.open()
        assertEquals(1, opened)
    }
}
