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
    fun readsWhereTheLinkCameFrom() {
        // A link without the fields is one read from the text, which shows its target.
        val read = TerminalLink.parse("""{"kind":"url","target":"https://x.com"}""")!!
        assertFalse(read.set)
        assertTrue(read.shown)
        assertEquals(
            TerminalLink("url", "https://evil.example/", set = true, shown = false),
            TerminalLink.parse("""{"kind":"url","target":"https://evil.example/","line":0,"col":0,"set":true,"shown":false}"""),
        )
    }

    @Test
    fun aLinkAProgramSetUnderOtherTextAsksFirst() {
        assertEquals(LinkTap.Hidden(LinkTap.Open("https://evil.example/a")), linkTap(TerminalLink("url", "https://evil.example/a", set = true, shown = false)))
        assertEquals(LinkTap.Hidden(LinkTap.Open("mailto:a@b.com")), linkTap(TerminalLink("url", "mailto:a@b.com", set = true, shown = false)))
        assertEquals(LinkTap.Hidden(LinkTap.File("file:///home/me/a.apk")), linkTap(TerminalLink("file", "file:///home/me/a.apk", set = true, shown = false)))
        assertEquals(
            LinkTap.Hidden(LinkTap.Forward(loopbackLink("http://localhost:5173/")!!)),
            linkTap(TerminalLink("localhost", "http://localhost:5173/", set = true, shown = false)),
        )
        // A link that launches another app has its own question, which shows the target too.
        assertEquals(LinkTap.Ask("ssh://me@host", "ssh"), linkTap(TerminalLink("url", "ssh://me@host", set = true, shown = false)))
    }

    @Test
    fun aLinkThatShowsItsTargetOpensAsBefore() {
        // A program set it on its own target.
        assertEquals(LinkTap.Open("https://x.com/a"), linkTap(TerminalLink("url", "https://x.com/a", set = true, shown = true)))
        assertEquals(LinkTap.File("/home/me/a.png"), linkTap(TerminalLink("file", "/home/me/a.png", set = true, shown = true)))
        // Read from the text on the screen.
        assertEquals(LinkTap.Open("https://x.com/a"), linkTap(TerminalLink("url", "https://x.com/a", set = false, shown = true)))
        assertEquals(LinkTap.File("src/b.kt"), linkTap(TerminalLink("file", "src/b.kt", set = false, shown = true)))
    }

    @Test
    fun theQuestionAboutAHiddenLinkShowsItsTarget() {
        val web = LinkTap.Open("https://evil.example/a")
        assertEquals("Open https link?", hiddenLinkTitle(web))
        assertEquals(
            "https://evil.example/a\n\nA program in the terminal printed this link under other text. It opens in the browser.",
            hiddenLinkText(web),
        )
        val mail = LinkTap.Open("MAILTO:a@b.com")
        assertEquals("Open mailto link?", hiddenLinkTitle(mail))
        assertEquals(
            "MAILTO:a@b.com\n\nA program in the terminal printed this link under other text. It opens another app on this phone.",
            hiddenLinkText(mail),
        )
        val long = "https://evil.example/" + "a".repeat(500)
        assertEquals(long.take(LINK_QUESTION_CHARS) + "…", hiddenLinkText(LinkTap.Open(long)).substringBefore("\n"))
    }

    @Test
    fun theQuestionAboutAHiddenFileShowsItsPathOnTheRemote() {
        assertEquals(
            "/home/me/a.png\n\nA program in the terminal printed this link under other text. Velo downloads the file from workstation and opens it on this phone.",
            hiddenFileText("/home/me/a.png", "workstation"),
        )
        val long = "/" + "a".repeat(500)
        assertEquals(long.take(LINK_QUESTION_CHARS) + "…", hiddenFileText(long, "workstation").substringBefore("\n"))
    }

    @Test
    fun aHiddenFileLinkIsStillAFileLink() {
        assertEquals(LinkTap.File("a.png"), LinkTap.Hidden(LinkTap.File("a.png")).file)
        assertEquals(LinkTap.File("a.png"), LinkTap.File("a.png").file)
        assertNull(LinkTap.Hidden(LinkTap.Open("https://x.com")).file)
        assertNull(LinkTap.Open("https://x.com").file)
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
