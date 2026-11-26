package io.github.yhzion.velo.ui

import io.github.yhzion.velo.AutoAnswer
import io.github.yhzion.velo.FakeKeys
import io.github.yhzion.velo.FakePrefs
import io.github.yhzion.velo.FakeSealer
import io.github.yhzion.velo.SshPool
import io.github.yhzion.velo.SshQuestion
import io.github.yhzion.velo.SshQuestions
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.autoAnswer
import io.github.yhzion.velo.core.sshHostPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

private const val url = "ssh://alice@studio.example:2222"
private const val hostPort = "studio.example:2222"
private const val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
private const val socket = "/home/alice/.local/state/rex/server.sock"
private const val now = 1_800_000_000L

/** What the panel says of a time: the test reads it back without a locale. */
private fun at(seconds: Long) = "T$seconds"

/** The pool's queue, as far as these tests need it: what was asked, and what was answered. */
private class Pool : SshPool {
    val waiting = LinkedHashMap<Long, SshQuestion>()
    val answers = mutableListOf<Pair<Long, String>>()
    private var last = 0L

    fun askHostKey(): SshQuestion =
        SshQuestion(++last, "hostkey", url, hostPort, keyType = "ssh-ed25519", fingerprint = fingerprint).also { waiting[it.id] = it }

    override fun questions(): String = Json.encodeToString(waiting.values.toList())
    override fun answer(id: Long, answer: String, ok: Boolean): Boolean {
        if (waiting.remove(id) == null) return false
        answers += id to answer
        return true
    }
    override fun installKey(target: String, publicLine: String, timeoutMs: Int) = 0
}

/** host-endpoints.json as the pool writes it (`json.MarshalIndent`), for two logins. */
private val endpoints = """
    {
      "version": 1,
      "ssh_sockets": {
        "ssh://alice@mac.example": "/Users/alice/.local/state/rex/server.sock",
        "ssh://alice@studio.example:2222": "$socket"
      },
      "refused": {
        "ssh://alice@mac.example": ${now - 90_000},
        "ssh://alice@studio.example:2222": ${now - 600}
      }
    }
""".trimIndent()

private fun rows(hostKey: String?, remembered: String?, password: Boolean) =
    sshDetailRows(hostPort, hostKey, remembered, password, now, ::at).associateBy { it.detail }

class SshDetailsTest {

    // Global Constraints: after Forget, the next login shows the fingerprint and waits for the
    // user's Trust, exactly as for a new host (as OpenSSH after `ssh-keygen -R`).
    @Test
    fun afterForgetHostKeyTheNextLoginAsksTheUserToTrustTheKeyAgain() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val pool = Pool()
        val questions = SshQuestions(pool, secrets)
        // The pool names the key by the host:port the remote's URL gives.
        assertEquals(hostPort, sshHostPort(url))

        // A new host: the user is asked, and trusts it.
        val first = pool.askHostKey()
        questions.poll()
        assertEquals(first, questions.pending.value)
        questions.answer(first.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey(hostPort))
        // The next login is answered from the key kept, without the user.
        val known = pool.askHostKey()
        questions.poll()
        assertNull(questions.pending.value)
        assertEquals(known.id to "trust", pool.answers.last())

        // SSH details → Host key → Forget.
        forgetTrustedHostKey(secrets, url)

        val again = pool.askHostKey()
        val kept = secrets.hostKey(hostPort)?.let { mapOf(hostPort to it) }.orEmpty()
        assertEquals(AutoAnswer.AskUser, autoAnswer(again, kept, emptyMap()))
        questions.poll()
        // The dialog shows the key's fingerprint, and nothing answered the pool meanwhile.
        assertEquals(again, questions.pending.value)
        assertEquals(fingerprint, hostKeyText(questions.pending.value!!).fingerprint)
        assertTrue(again.id in pool.waiting)
        assertTrue(pool.answers.none { it.first == again.id })
        // The panel says so too.
        val hostKey = rows(secrets.hostKey(hostPort), null, password = false).getValue(SshDetail.HostKey)
        assertEquals(listOf(DetailLine(NOT_KNOWN_YET)), hostKey.lines)
        // Only the user's Trust keeps the key again.
        questions.answer(again.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals(again.id to "trust", pool.answers.last())
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey(hostPort))
    }

    @Test
    fun forgettingAHostKeyLeavesTheOtherPortsAndTheOtherHostsKeys() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")
        secrets.trustHostKey("studio.example:22", "ssh-ed25519 $fingerprint")
        secrets.trustHostKey("mac.example:22", "ssh-ed25519 $fingerprint")
        forgetTrustedHostKey(secrets, url)
        assertNull(secrets.hostKey(hostPort))
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey("studio.example:22"))
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey("mac.example:22"))
        // A URL that is no ssh login forgets nothing.
        forgetTrustedHostKey(secrets, "https://studio.example")
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey("studio.example:22"))
    }

    // #292: two keys kept for one name in different case. The panel says so, shows neither as the
    // host's key, and its Forget forgets both: the next login then asks as for a new host.
    @Test
    fun twoKeysKeptForOneNameAreSaidAndForgottenTogether() {
        val prefs = FakePrefs()
        prefs.edit().putString("ssh:hostkey:Studio.Example:2222", "ssh-ed25519 $fingerprint")
            .putString("ssh:hostkey:$hostPort", "ssh-ed25519 SHA256:otherkeyotherkeyotherkeyotherkeyotherkeyoth").apply()
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
        val kept = readSshKept(url, secrets) { null }!!
        assertEquals(SshSecrets.HOST_KEYS_DIFFER, kept.hostKey)
        val row = kept.rows(now, ::at).getValue(SshDetail.HostKey)
        assertEquals(listOf(DetailLine(hostPort), DetailLine(HOST_KEYS_DIFFER_TEXT), DetailLine(HOST_KEY_FORGET_TEXT)), row.lines)
        assertEquals("This phone keeps two different keys for this host. Forget them, and the next login asks you to check the key.", HOST_KEYS_DIFFER_TEXT)
        assertTrue(row.canForget)

        forgetTrustedHostKey(secrets, url)
        assertTrue(prefs.all.keys.none { it.startsWith("ssh:hostkey:") })
        val pool = Pool()
        val questions = SshQuestions(pool, secrets)
        val again = pool.askHostKey()
        questions.poll()
        assertEquals(again, questions.pending.value)
        assertTrue(pool.answers.isEmpty())
    }

    // #292: after a Forget its row may take the focus. A row the focus never reached (the panel
    // lost the window's focus, the row was gone) must not stay a keyboard stop.
    @Test
    fun aRowTheFocusNeverReachedIsNoKeyboardStop() {
        assertEquals(SshDetail.HostKey, focusRowAfterMove(SshDetail.HostKey, focused = SshDetail.HostKey))
        assertNull(focusRowAfterMove(SshDetail.HostKey, focused = null))
        assertNull(focusRowAfterMove(SshDetail.HostKey, focused = SshDetail.Password))
    }

    // Review Focus 4: a host key never trusted is not known yet, and there is nothing to forget.
    @Test
    fun aRemoteThePhoneKnowsNothingOfIsNotKnownYetWithNoForget() {
        val rows = sshDetailRows(hostPort, null, null, false, now, ::at)
        assertEquals(listOf(SshDetail.HostKey, SshDetail.ServerSocket, SshDetail.Password), rows.map { it.detail })
        assertEquals(listOf("Host key", "Server socket", "Password"), rows.map { it.detail.label })
        assertEquals(listOf(DetailLine(NOT_KNOWN_YET)), rows[0].lines)
        assertEquals(listOf(DetailLine(NOT_KNOWN_YET)), rows[1].lines)
        assertEquals(listOf(DetailLine(PASSWORD_NOT_SAVED)), rows[2].lines)
        assertTrue(rows.none { it.canForget })
        assertEquals("Not known yet", NOT_KNOWN_YET)
        assertEquals("Not saved", PASSWORD_NOT_SAVED)
        // A login whose URL names no host:port has no host key either.
        assertEquals(listOf(DetailLine(NOT_KNOWN_YET)), sshDetailRows(null, null, null, false, now, ::at)[0].lines)
    }

    @Test
    fun whatThePhoneRemembersIsShownWithAForgetForEach() {
        val rows = rows("ssh-ed25519 $fingerprint", """{"socket":"$socket","refused_until":0}""", password = true)
        // #29: the row says what else its Forget forgets.
        assertEquals(listOf(DetailLine("$hostPort · ED25519"), DetailLine(fingerprint, code = true), DetailLine(HOST_KEY_FORGET_TEXT)), rows.getValue(SshDetail.HostKey).lines)
        assertEquals("Forget also forgets the passwords saved for this host.", HOST_KEY_FORGET_TEXT)
        assertEquals(listOf(DetailLine(socket, code = true)), rows.getValue(SshDetail.ServerSocket).lines)
        assertEquals(listOf(DetailLine(PASSWORD_SAVED)), rows.getValue(SshDetail.Password).lines)
        assertEquals("Saved on this phone, encrypted", PASSWORD_SAVED)
        assertTrue(rows.values.all { it.canForget })
        // Another key type is named as the host key dialog names it.
        assertEquals(DetailLine("$hostPort · ECDSA"), rows("ecdsa-sha2-nistp256 $fingerprint", null, false).getValue(SshDetail.HostKey).lines[0])
    }

    @Test
    fun aRefusedForwardSaysUntilWhenRexGoesThroughDialStdio() {
        val until = now + 3_600
        val refused = rows(null, """{"socket":"$socket","refused_until":$until}""", false).getValue(SshDetail.ServerSocket)
        assertEquals(listOf(DetailLine(socket, code = true), DetailLine("Velo reaches it through rex … dial-stdio until T$until")), refused.lines)
        assertTrue(refused.canForget)
        // A refusal alone is something to forget.
        val alone = rows(null, """{"socket":"","refused_until":$until}""", false).getValue(SshDetail.ServerSocket)
        assertEquals(listOf(DetailLine("Velo reaches it through rex … dial-stdio until T$until")), alone.lines)
        assertTrue(alone.canForget)
        // A refusal that ended says nothing.
        assertEquals(listOf(DetailLine(socket, code = true)), rows(null, """{"socket":"$socket","refused_until":$now}""", false).getValue(SshDetail.ServerSocket).lines)
        // Nothing remembered, no pool, or JSON this app cannot read: not known yet.
        for (json in listOf("""{"socket":"","refused_until":0}""", """{"socket":"","refused_until":${now - 1}}""", null, "{", "[]")) {
            val row = rows(null, json, false).getValue(SshDetail.ServerSocket)
            assertEquals(json, listOf(DetailLine(NOT_KNOWN_YET)), row.lines)
            assertTrue(json, !row.canForget)
        }
        // A field the pool adds later is read past.
        assertEquals(RememberedSocket(socket, 5), parseRemembered("""{"socket":"$socket","refused_until":5,"later":true}"""))
    }

    @Test
    fun eachForgetSaysWhatItForgot() {
        assertEquals("SSH details", SSH_DETAILS)
        assertEquals("Forget", FORGET)
        assertEquals(listOf("Forgot the host key and the passwords saved for this host", "Forgot the server socket", "Forgot the password"), SshDetail.entries.map { it.notice })
        // The three Forget buttons read the same on screen; a screen reader tells them apart.
        assertEquals(listOf("Forget host key and the passwords saved for this host", "Forget server socket", "Forget password"), SshDetail.entries.map { it.forgetDescription })
    }

    // A Forget the pool refused (forgetSocket's error) says so, with the code, not "Forgot …".
    @Test
    fun aForgetThatFailedSaysSo() {
        assertEquals("Forgot the server socket", forgetNotice(SshDetail.ServerSocket, 0))
        assertEquals("Velo couldn't forget the server socket (error 22).", forgetNotice(SshDetail.ServerSocket, 22))
        assertEquals("Velo couldn't forget the server socket (error -1).", forgetNotice(SshDetail.ServerSocket, -1))
        assertEquals("Forgot the host key and the passwords saved for this host", forgetNotice(SshDetail.HostKey, 0))
        assertEquals("Velo couldn't forget the host key.", forgetNotice(SshDetail.HostKey, null))
        assertEquals("Forgot the password", forgetNotice(SshDetail.Password, 0))
    }

    // #284 Screens: before the pool starts (no ssh dial yet in this process) the socket row
    // said "Not known yet" although host-endpoints.json had the socket.
    @Test
    fun beforeThePoolStartsTheSocketRowReadsHostEndpointsJson() {
        val row = rows(null, rememberedInEndpoints(endpoints, url, now), false).getValue(SshDetail.ServerSocket)
        assertEquals(listOf(DetailLine(socket, code = true), DetailLine(refusalLine("T${now - 600 + 86_400}"))), row.lines)
        assertTrue(row.canForget)
        // Only the pool forgets it (it keeps the file, which the panel only reads): until the
        // pool runs the row shows it with no Forget, which would forget nothing.
        val waiting = sshDetailRows(hostPort, null, rememberedInEndpoints(endpoints, url, now), false, now, ::at, socketForgettable = false)
            .associateBy { it.detail }.getValue(SshDetail.ServerSocket)
        assertEquals(row.lines, waiting.lines)
        assertTrue(!waiting.canForget)
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        assertEquals(SshKept(hostPort, null, null, password = false, poolRuns = false), readSshKept(url, secrets, poolRuns = false) { null })
        assertTrue(readSshKept(url, secrets) { null }!!.poolRuns)
        // A login on port 22 is kept without its port, as the pool writes it, and a refusal
        // older than a day no longer counts, as for the pool.
        for (login in listOf("ssh://alice@mac.example", "ssh://alice@mac.example:22")) {
            val mac = rows(null, rememberedInEndpoints(endpoints, login, now), false).getValue(SshDetail.ServerSocket)
            assertEquals(login, listOf(DetailLine("/Users/alice/.local/state/rex/server.sock", code = true)), mac.lines)
        }
        // A login the file has nothing for: not known yet, with no Forget.
        for (other in listOf("ssh://root@studio.example:2222", "ssh://alice@studio.example", "https://studio.example")) {
            val none = rows(null, rememberedInEndpoints(endpoints, other, now), false).getValue(SshDetail.ServerSocket)
            assertEquals(other, listOf(DetailLine(NOT_KNOWN_YET)), none.lines)
            assertTrue(other, !none.canForget)
        }
        // No file, or one the pool would not read either: not known yet.
        val unread = listOf(null, "", "{", "[]", endpoints.replace("\"version\": 1", "\"version\": 2"), """{"version":1,"ssh_sockets":null,"refused":null}""")
        for (file in unread) {
            assertEquals(file, listOf(DetailLine(NOT_KNOWN_YET)), rows(null, rememberedInEndpoints(file, url, now), false).getValue(SshDetail.ServerSocket).lines)
        }
    }

    @Test
    fun theSocketRowAsksThePoolOnceItRunsAndReadsTheFileUntilThen() {
        val fromPool = """{"socket":"/from/the/pool","refused_until":0}"""
        assertEquals(fromPool, rememberedFor(url, poolStarted = true, pool = { assertEquals(url, it); fromPool }, file = { error("not read while the pool runs") }, now = now))
        val fromFile = rememberedFor(url, poolStarted = false, pool = { error("no pool yet") }, file = { endpoints }, now = now)
        assertEquals(RememberedSocket(socket, now - 600 + 86_400), parseRemembered(fromFile))
        assertNull(rememberedFor(url, poolStarted = false, pool = { error("no pool yet") }, file = { null }, now = now))
    }

    // The panel reads the pool's file and never writes it: the pool alone keeps it.
    @Test
    fun theEndpointsFileIsOnlyRead() {
        val dir = Files.createTempDirectory("velo-ssh").toFile()
        try {
            assertNull(readEndpointsFile(dir))
            val file = File(dir, "host-endpoints.json")
            file.writeText(endpoints)
            file.setLastModified(1_700_000_000_000)
            assertEquals(endpoints, readEndpointsFile(dir))
            assertEquals(endpoints, file.readText())
            assertEquals(1_700_000_000_000, file.lastModified())
            assertEquals(listOf("host-endpoints.json"), dir.list()!!.toList())
            // A file far larger than the pool writes is not read.
            file.writeText(" ".repeat(MAX_ENDPOINTS_BYTES + 1))
            assertNull(readEndpointsFile(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // #284: a Forget that threw left every Forget button disabled until the panel closed.
    @Test
    fun aForgetThatThrowsSaysSoAndGivesTheButtonsBack() = runBlocking {
        val said = mutableListOf<String>()
        var idle = 0
        forgetAndSay(SshDetail.ServerSocket, forget = { throw UnsatisfiedLinkError("no pool") }, say = { said += it }, idle = { idle++ })
        forgetAndSay(SshDetail.Password, forget = { throw IllegalStateException("prefs") }, say = { said += it }, idle = { idle++ })
        forgetAndSay(SshDetail.ServerSocket, forget = { 22 }, say = { said += it }, idle = { idle++ })
        forgetAndSay(SshDetail.HostKey, forget = { 0 }, say = { said += it }, idle = { idle++ })
        assertEquals(listOf("Velo couldn't forget the server socket.", "Velo couldn't forget the password.",
            "Velo couldn't forget the server socket (error 22).", "Forgot the host key and the passwords saved for this host"), said)
        assertEquals(4, idle)
        // Whatever ends a Forget, the buttons come back: a step after it that fails, or the panel closing.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { forgetAndSay(SshDetail.HostKey, forget = { 0 }, say = { throw IllegalStateException("focus") }, idle = { idle++ }) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { forgetAndSay(SshDetail.HostKey, forget = { throw CancellationException("closed") }, say = { said += it }, idle = { idle++ }) }
        }
        assertEquals(6, idle)
        assertEquals(4, said.size)
    }

    @Test
    fun thePanelReadsTheRemotesOwnHostKeyAndPassword() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")
        secrets.savePassword(url, "hunter2", "ssh-ed25519 $fingerprint")
        val asked = mutableListOf<String>()
        val kept = readSshKept(url, secrets) { asked += it; """{"socket":"$socket","refused_until":0}""" }!!
        assertEquals(SshKept(hostPort, "ssh-ed25519 $fingerprint", """{"socket":"$socket","refused_until":0}""", password = true), kept)
        assertEquals(listOf(url), asked)
        // Another user on the same host:port shares the host key, not the password.
        assertEquals(SshKept(hostPort, "ssh-ed25519 $fingerprint", null, password = false), readSshKept("ssh://root@studio.example:2222", secrets) { null })
        // Forget password: the row says so.
        secrets.forgetPassword(url)
        assertEquals(listOf(DetailLine(PASSWORD_NOT_SAVED)), readSshKept(url, secrets) { null }!!.rows(now, ::at).getValue(SshDetail.Password).lines)
        // Not an ssh login: no panel.
        assertNull(readSshKept("https://studio.example", secrets) { error("not asked") })
    }

    // #29: Host key → Forget forgets the passwords saved for the host, and the panel, read again
    // after the Forget, shows none: for this login and for another user of the same host:port.
    @Test
    fun afterForgetHostKeyThePasswordRowSaysNoneIsSaved() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val other = "ssh://root@studio.example:2222"
        val elsewhere = "ssh://alice@studio.example"
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")
        secrets.trustHostKey("studio.example:22", "ssh-ed25519 $fingerprint")
        for (login in listOf(url, other, elsewhere)) secrets.savePassword(login, "hunter2", "ssh-ed25519 $fingerprint")
        val before = readSshKept(url, secrets) { null }!!.rows(now, ::at)
        assertEquals(DetailLine(HOST_KEY_FORGET_TEXT), before.getValue(SshDetail.HostKey).lines.last())
        assertEquals(listOf(DetailLine(PASSWORD_SAVED)), before.getValue(SshDetail.Password).lines)

        forgetTrustedHostKey(secrets, url)

        for (login in listOf(url, other)) {
            val after = readSshKept(login, secrets) { null }!!.rows(now, ::at)
            // Nothing is left to forget in either row, so neither says what a Forget would do.
            assertEquals(login, listOf(DetailLine(NOT_KNOWN_YET)), after.getValue(SshDetail.HostKey).lines)
            assertEquals(login, listOf(DetailLine(PASSWORD_NOT_SAVED)), after.getValue(SshDetail.Password).lines)
            assertTrue(login, !after.getValue(SshDetail.HostKey).canForget && !after.getValue(SshDetail.Password).canForget)
        }
        // The same host on another port keeps its key and its password.
        val kept = readSshKept(elsewhere, secrets) { null }!!.rows(now, ::at)
        assertEquals(listOf(DetailLine(PASSWORD_SAVED)), kept.getValue(SshDetail.Password).lines)
        assertTrue(kept.getValue(SshDetail.HostKey).canForget)
    }

    // Final fix H4: a first read that threw leaves one line, not a title with only Done.
    @Test
    fun aFirstReadThatFailedSaysSo() {
        val kept = SshKept(hostPort, null, null, false)
        assertNull(sshDetailsUnread(loaded = false, kept = null))
        assertEquals("Velo couldn't read what this phone remembers for this remote.", sshDetailsUnread(loaded = true, kept = null))
        assertNull(sshDetailsUnread(loaded = true, kept = kept))
    }
}

private fun SshKept.rows(now: Long, time: (Long) -> String) = sshDetailRows(hostPort, hostKey, remembered, password, now, time).associateBy { it.detail }
