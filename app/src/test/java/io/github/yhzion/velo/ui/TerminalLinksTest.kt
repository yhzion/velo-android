package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinksTest {
    @Test
    fun readsTheCoresLink() {
        assertEquals(
            TerminalLink("file", "src/b.kt", line = 7, col = 2),
            TerminalLink.parse("""{"kind":"file","target":"src/b.kt","line":7,"col":2}"""),
        )
        assertEquals(TerminalLink("url", "https://x.com"), TerminalLink.parse("""{"kind":"url","target":"https://x.com","extra":1}"""))
    }

    @Test
    fun noTextOrBadTextIsNoLink() {
        assertNull(TerminalLink.parse(null))
        assertNull(TerminalLink.parse(""))
        assertNull(TerminalLink.parse("{"))
    }

    @Test
    fun webAndMailLinksOpenAtOnce() {
        assertEquals(LinkTap.Open("https://x.com/a"), linkTap(TerminalLink("url", "https://x.com/a")))
        assertEquals(LinkTap.Open("HTTP://X.COM"), linkTap(TerminalLink("url", "HTTP://X.COM")))
        assertEquals(LinkTap.Open("mailto:a@b.com"), linkTap(TerminalLink("url", "mailto:a@b.com")))
    }

    @Test
    fun linksThatLaunchAnotherAppAskFirst() {
        assertEquals(LinkTap.Ask("ssh://me@host", "ssh"), linkTap(TerminalLink("url", "ssh://me@host")))
        assertEquals(LinkTap.Ask("vscode://file/a.kt", "vscode"), linkTap(TerminalLink("url", "vscode://file/a.kt")))
        assertEquals(LinkTap.Ask("tel:+821012345678", "tel"), linkTap(TerminalLink("url", "tel:+821012345678")))
    }

    @Test
    fun aLocalhostLinkOpensThroughAForward() {
        assertEquals(LinkTap.Forward(loopbackLink("http://localhost:5173/")!!), linkTap(TerminalLink("localhost", "http://localhost:5173/")))
        assertNull(linkTap(TerminalLink("url", "no-scheme")))
    }

    @Test
    fun aFileLinkOpensTheRemoteFile() {
        assertEquals(LinkTap.File("src/b.kt"), linkTap(TerminalLink("file", "src/b.kt", line = 7)))
        assertEquals(LinkTap.File("file:///home/me/a.png"), linkTap(TerminalLink("file", "file:///home/me/a.png")))
    }

    @Test
    fun theQuestionNamesTheLinkAndCutsALongOne() {
        val ask = LinkTap.Ask("ssh://me@host", "ssh")
        assertEquals("Open ssh link?", linkQuestionTitle(ask))
        assertEquals("ssh://me@host\n\nA program in the terminal printed this link. It opens another app on this phone.", linkQuestionText(ask))
        val long = "x-app://" + "a".repeat(500)
        val text = linkQuestionText(LinkTap.Ask(long, "x-app"))
        assertEquals(long.take(LINK_QUESTION_CHARS) + "…", text.substringBefore("\n"))
    }

    @Test
    fun theTapsRightAfterAnOpenedLinkDoNotOpenItAgain() {
        val gate = LinkTapGate(windowMs = 300)
        assertTrue(gate.take(1000))
        // The second tap of a double tap, and a third right after it.
        assertFalse(gate.take(1200))
        assertFalse(gate.take(1400))
        assertTrue(gate.take(1800))
    }
}
