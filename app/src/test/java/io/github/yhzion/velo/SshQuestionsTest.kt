package io.github.yhzion.velo

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val target = "ssh://alice@studio.example:2222"
private const val hostPort = "studio.example:2222"
private const val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"

private fun hostKeyQuestion(id: Long = 1) =
    SshQuestion(id, "hostkey", target, hostPort, keyType = "ssh-ed25519", fingerprint = fingerprint, echo = false, retry = false)

private fun passwordQuestion(id: Long = 1, retry: Boolean = false, afterStall: Boolean = false) =
    SshQuestion(id, "password", target, "Enter the SSH password for alice on studio.example.", keyType = "", fingerprint = "", echo = false, retry = retry,
        afterStall = afterStall)

/**
 * The pool's question queue, as rexssh.Queue keeps it: each question waits 120 s on [now], then the
 * login fails as declined and the question leaves the queue. [asked] counts every question asked.
 */
private class FakePool(private val now: () -> Long) : SshPool {
    val asked = mutableListOf<SshQuestion>()
    val answers = mutableListOf<Triple<Long, String, Boolean>>()
    val installs = mutableListOf<Pair<String, String>>()
    /** The logins that failed because a question went unanswered. */
    val declined = mutableListOf<String>()
    private val waiting = LinkedHashMap<Long, Pair<SshQuestion, Long>>()
    private var last = 0L

    fun ask(question: SshQuestion): SshQuestion {
        val q = question.copy(id = ++last)
        asked += q
        waiting[q.id] = q to now()
        return q
    }

    private fun expire() {
        val gone = waiting.values.filter { (_, at) -> now() - at >= 120_000 }
        for ((q, _) in gone) { waiting.remove(q.id); declined += q.target }
    }

    override fun questions(): String { expire(); return Json.encodeToString(waiting.values.map { it.first }) }
    override fun answer(id: Long, answer: String, ok: Boolean): Boolean {
        expire()
        if (waiting.remove(id) == null) return false
        answers += Triple(id, answer, ok)
        return true
    }
    override fun installKey(target: String, publicLine: String, timeoutMs: Int): Int { installs += target to publicLine; return 0 }
}

private class RecordingNotices : SshQuestions.Notices {
    val asked = mutableListOf<SshQuestion>()
    val cleared = mutableListOf<String>()
    override fun ask(question: SshQuestion) { asked += question }
    override fun clear(target: String) { cleared += target }
}

private fun prefsText(prefs: FakePrefs) = prefs.all.entries.joinToString("\n") { (k, v) -> "<string name=\"$k\">$v</string>" }

/** The main thread's queue, run by hand: [run] runs what was posted, [fire] the planned tries whose time came. */
private class FakeMain : MainQueue {
    private val posted = ArrayDeque<Runnable>()
    private val planned = LinkedHashMap<Runnable, Long>()
    override fun post(r: Runnable) { posted.addLast(r) }
    override fun postDelayed(r: Runnable, ms: Long) { planned[r] = ms }
    override fun remove(r: Runnable) {
        posted.removeAll { it === r }
        planned.remove(r)
    }
    fun run() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    /** The planned try's delay; null when none is planned. */
    val delay: Long? get() = planned.values.singleOrNull()
    fun fire() {
        val due = planned.keys.toList()
        planned.clear()
        due.forEach { it.run() }
        run()
    }
}

/** An [SshKeyGiver] whose background work runs at once, on the caller's thread. */
private fun giver(secrets: SshSecrets, main: FakeMain, set: (Array<String>) -> Int = { 0 }, clock: () -> Long = { 0L },
    changed: () -> Unit = {}, log: (String) -> Unit = {}) = SshKeyGiver(secrets, set, main, { it.run() }, clock, changed, log)

/** [SshSecrets] over [prefs] whose Keystore cannot open the [n]th test key while [busy] says so. */
private fun secretsWithBusyKey(prefs: FakePrefs, keys: FakeKeys, n: Int, busy: () -> Boolean, keysChanged: () -> Unit = {}): SshSecrets {
    val real = FakeSealer()
    val sealer = object : Sealer {
        override fun seal(plain: ByteArray) = real.seal(plain)
        override fun open(sealed: ByteArray): ByteArray {
            val plain = real.open(sealed)
            if (busy() && String(plain) == testKey(n).first) throw java.security.KeyStoreException("busy")
            return plain
        }
    }
    return SshSecrets(prefs, sealer, keys, keysChanged = keysChanged)
}

class SshQuestionsTest {

    @Test
    fun aKnownHostKeyIsTrustedAndAnotherOneIsChanged() {
        val q = hostKeyQuestion()
        assertEquals(AutoAnswer.Answer("trust"), autoAnswer(q, mapOf(hostPort to "ssh-ed25519 $fingerprint"), emptyMap()))
        assertEquals(AutoAnswer.Answer("changed"), autoAnswer(q, mapOf(hostPort to "ssh-ed25519 SHA256:otherkeyotherkeyotherkeyotherkeyotherkeyoth"), emptyMap()))
        // Another key type on the same host:port is another key.
        assertEquals(AutoAnswer.Answer("changed"), autoAnswer(q, mapOf(hostPort to "ecdsa-sha2-nistp256 $fingerprint"), emptyMap()))
        assertEquals(AutoAnswer.AskUser, autoAnswer(q, emptyMap(), emptyMap()))
        // Keys are remembered per host:port: the same host on another port is unknown.
        assertEquals(AutoAnswer.AskUser, autoAnswer(q, mapOf("studio.example:22" to "ssh-ed25519 $fingerprint"), emptyMap()))
    }

    // #292 (the coordinator's condition): keys kept for one name in two cases that differ are a
    // mismatch, not a choice. Whichever of them the host shows, the login is answered "changed":
    // it stops as host_key_changed, nothing is trusted, and the user is not asked as for a new host.
    @Test
    fun twoHostKeysKeptForOneNameAreAMismatchWhicheverTheHostShows() {
        val other = "SHA256:otherkeyotherkeyotherkeyotherkeyotherkeyoth"
        val prefs = FakePrefs()
        prefs.edit().putString("ssh:hostkey:Studio.Example:2222", "ssh-ed25519 $fingerprint")
            .putString("ssh:hostkey:$hostPort", "ssh-ed25519 $other").apply()
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
        val pool = FakePool { 0L }
        val questions = SshQuestions(pool, secrets, RecordingNotices())
        for (shown in listOf(fingerprint, other, "SHA256:athirdkeyathirdkeyathirdkeyathirdkeyathird")) {
            val q = pool.ask(hostKeyQuestion().copy(fingerprint = shown))
            questions.poll()
            assertNull(shown, questions.pending.value)
            assertEquals(shown, Triple(q.id, SSH_CHANGED, true), pool.answers.last())
        }
        assertTrue(questions.needsAnswer.value.isEmpty())
        // Both stay until the user forgets them (SSH details).
        assertEquals(2, prefs.all.keys.count { it.startsWith("ssh:hostkey:") })

        // One key kept under another case answers as the key of that host: the same is trusted, another is changed.
        val single = FakePrefs()
        single.edit().putString("ssh:hostkey:Studio.Example:2222", "ssh-ed25519 $fingerprint").apply()
        val singlePool = FakePool { 0L }
        val singleQuestions = SshQuestions(singlePool, SshSecrets(single, FakeSealer(), FakeKeys()), RecordingNotices())
        val same = singlePool.ask(hostKeyQuestion())
        singleQuestions.poll()
        assertEquals(Triple(same.id, SSH_TRUST, true), singlePool.answers.last())
        val changed = singlePool.ask(hostKeyQuestion().copy(fingerprint = other))
        singleQuestions.poll()
        assertEquals(Triple(changed.id, SSH_CHANGED, true), singlePool.answers.last())
        assertNull(singleQuestions.pending.value)
    }

    @Test
    fun aSavedPasswordAnswersUntilTheHostRefusesIt() {
        val saved = mapOf(target to "hunter2")
        assertEquals(AutoAnswer.Answer("hunter2"), autoAnswer(passwordQuestion(), emptyMap(), saved))
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(retry = true), emptyMap(), saved))
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(), emptyMap(), emptyMap()))
        // Saved for another login: not this one's.
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(), emptyMap(), mapOf("ssh://root@studio.example:2222" to "hunter2")))
    }

    // Review Focus 2 and ruling R4 (#284): the last answer to this host stalled, so the pool marks
    // every password question of the next login (after_stall). Kotlin then never answers from the
    // saved password, so a refusal in that login refused the user's own answer: the saved one stays.
    @Test
    fun afterStallAsksWithoutForgetting() {
        val saved = mapOf(target to "hunter2")
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(afterStall = true), emptyMap(), saved))
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(retry = true, afterStall = true), emptyMap(), saved))
        assertEquals(AutoAnswer.AskUser, autoAnswer(passwordQuestion(retry = true), emptyMap(), saved))

        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.savePassword(target, "hunter2")
        val questions = SshQuestions(pool, secrets, RecordingNotices())

        // after_stall alone: the user is asked, and the saved password stays.
        val first = pool.ask(passwordQuestion(afterStall = true))
        questions.poll()
        assertEquals(first, questions.pending.value)
        assertTrue(pool.answers.isEmpty())
        assertEquals("hunter2", secrets.password(target))

        // retry with after_stall: the host refused what the user typed. Asked again, the saved one stays.
        questions.answer(first.id, "typo", ok = true, savePassword = true, installKey = false)
        val again = pool.ask(passwordQuestion(retry = true, afterStall = true))
        questions.poll()
        assertEquals(again, questions.pending.value)
        assertEquals("hunter2", secrets.password(target))
        assertEquals(1, pool.answers.size)
        // The refused answer is not saved when the login connects after another answer.
        questions.answer(again.id, "hunter2", ok = true, savePassword = false, installKey = false)
        questions.connected(target)
        assertEquals("hunter2", secrets.password(target))

        // After a login that finished, the pool asks without the mark: the saved password answers again.
        val later = pool.ask(passwordQuestion())
        questions.poll()
        assertEquals(Triple(later.id, "hunter2", true), pool.answers.last())
        assertNull(questions.pending.value)

        // retry without after_stall (today's rule): the host refused the saved password, so it goes.
        val refused = pool.ask(passwordQuestion(retry = true))
        questions.poll()
        assertEquals(refused, questions.pending.value)
        assertNull(secrets.password(target))
        assertFalse(secrets.hasPassword(target))
    }

    @Test
    fun theStallMarkIsReadFromThePoolsJson() {
        val marked = """[{"id":5,"kind":"password","target":"$target","text":"t","echo":false,"retry":true,"after_stall":true}]"""
        assertEquals(listOf(SshQuestion(5, "password", target, "t", retry = true, afterStall = true)), parseQuestions(marked))
        // The pool leaves it out when false.
        val plain = """[{"id":6,"kind":"prompt","target":"$target","text":"Code:","echo":true,"retry":false}]"""
        assertEquals(false, parseQuestions(plain)?.single()?.afterStall)
    }

    @Test
    fun promptsAreAlwaysTheUsersAndUnknownKindsAreDeclined() {
        val prompt = SshQuestion(1, "prompt", target, "Verification code:", "", "", echo = true, retry = false)
        assertEquals(AutoAnswer.AskUser, autoAnswer(prompt, emptyMap(), mapOf(target to "hunter2")))
        assertEquals(AutoAnswer.Decline, autoAnswer(prompt.copy(kind = "agent"), emptyMap(), emptyMap()))
    }

    @Test
    fun theQueueIsReadFromThePoolsJson() {
        val json = """[{"id":3,"kind":"hostkey","target":"$target","text":"$hostPort","keyType":"ssh-ed25519","fingerprint":"$fingerprint","echo":false,"retry":false,"later":1},""" +
            """{"id":4,"kind":"password","target":"$target","text":"t","keyType":"","fingerprint":"","echo":false,"retry":true}]"""
        assertEquals(listOf(hostKeyQuestion(3), SshQuestion(4, "password", target, "t", "", "", false, true)), parseQuestions(json))
        assertEquals(emptyList<SshQuestion>(), parseQuestions("[]"))
        // Before the pool is made, or JSON this app cannot read: nothing is known, not "no questions".
        assertNull(parseQuestions(null))
        assertNull(parseQuestions("{"))
    }

    @Test
    fun secretsAreSealedAndThePublicLineIsNot() {
        val prefs = FakePrefs()
        val sealer = FakeSealer()
        val keys = FakeKeys()
        val secrets = SshSecrets(prefs, sealer, keys)

        val pem = secrets.identityPem()
        assertTrue(pem.contains("phone-key-1"))
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone1 velo", secrets.publicLine())
        // Public, for the e2e mocks to authorize (ruling P4).
        assertEquals(secrets.publicLine(), prefs.getString("ssh:public", null))
        secrets.savePassword(target, "correct horse battery staple")
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")

        val text = prefsText(prefs)
        assertFalse(text, text.contains("correct horse"))
        assertFalse(text, text.contains("phone-key-1"))
        assertTrue(text.contains("ssh-ed25519 $fingerprint"))

        // Another process start reads them back; the phone key is not made again.
        val again = SshSecrets(prefs, sealer, keys)
        assertEquals(pem, again.identityPem())
        assertEquals(1, keys.generated)
        assertEquals("correct horse battery staple", again.password(target))
        assertNull(again.password("ssh://root@studio.example:2222"))
        assertEquals("ssh-ed25519 $fingerprint", again.hostKey(hostPort))
        assertNull(again.hostKey("studio.example:22"))

        again.forgetPassword(target)
        again.forgetHostKey(hostPort)
        assertNull(again.password(target))
        assertNull(again.hostKey(hostPort))
    }

    @Test
    fun aKeyThatNoLongerOpensIsReplacedAndAPasswordForgotten() {
        val prefs = FakePrefs()
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
        secrets.identityPem()
        secrets.savePassword(target, "hunter2")
        // The Keystore key is gone (the app's data outlived it): what it sealed cannot be opened.
        val keys = FakeKeys().also { it.generated = 1 }
        val lost = object : Sealer {
            override fun seal(plain: ByteArray) = FakeSealer().seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
        }
        val after = SshSecrets(prefs, lost, keys)
        assertNull(after.password(target))
        assertNull(prefs.getString("ssh:password:$target", null))
        assertTrue(after.identityPem().contains("phone-key-2"))
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone2 velo", after.publicLine())
    }

    @Test
    fun replacingTheKeyMakesANewOneAndTellsThePool() {
        val prefs = FakePrefs()
        val given = mutableListOf<List<String>>()
        lateinit var secrets: SshSecrets
        secrets = SshSecrets(prefs, FakeSealer(), FakeKeys(), keysChanged = { given += secrets.identityPems() })
        val first = secrets.publicLine()
        secrets.replaceKey()
        assertTrue(secrets.identityPem().contains("phone-key-2"))
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone2 velo", secrets.publicLine())
        assertTrue(first != secrets.publicLine())
        assertEquals(listOf(listOf(secrets.identityPem())), given)
    }

    @Test
    fun anImportedKeyIsOpenedOnceAndKeptSealed() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        val secrets = SshSecrets(prefs, FakeSealer(), keys)
        val encrypted = "-----BEGIN OPENSSH PRIVATE KEY-----\nENCRYPTED\n-----END OPENSSH PRIVATE KEY-----\n"

        assertEquals(SshKeyProblem.Kind.NeedsPassphrase, (secrets.importKey(encrypted, null).exceptionOrNull() as? SshKeyProblem)?.kind)
        assertEquals(SshKeyProblem.Kind.WrongPassphrase, (secrets.importKey(encrypted, "wrong").exceptionOrNull() as? SshKeyProblem)?.kind)
        assertEquals("Velo can't use this file as a key. Import an Ed25519, ECDSA or RSA private key.",
            secrets.importKey("hello", null).exceptionOrNull()?.message)

        val key = secrets.importKey(encrypted, "open sesame").getOrThrow()
        assertEquals(keys.imported.second, key.publicLine)
        assertFalse(key.phone)
        // Beside the phone key, not in its place: convenience A and B keep using the phone key.
        assertTrue(secrets.identityPem().contains("phone-key-1"))
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone1 velo", secrets.publicLine())
        assertEquals(listOf(secrets.identityPem(), keys.imported.first), secrets.identityPems())
        assertEquals(1, keys.generated)
        assertFalse(prefsText(prefs).contains(keys.imported.first.lines()[1]))
    }

    @Test
    fun thePublicLineOfAnOpenSshKeyIsReadFromItsHeader() {
        val (pem, line) = openSshPem("ecdsa-sha2-nistp256", ByteArray(40) { 9 })
        assertEquals(line, openSshPublicLine(pem))
        assertNull(openSshPublicLine("-----BEGIN OPENSSH PRIVATE KEY-----\nbm90IGEga2V5\n-----END OPENSSH PRIVATE KEY-----"))
        assertNull(openSshPublicLine("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
    }

    @Test
    fun theQuestionsAStoreCanAnswerNeverReachTheUser() {
        val clock = longArrayOf(0)
        val pool = FakePool { clock[0] }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")
        secrets.savePassword(target, "hunter2")
        val notices = RecordingNotices()
        val questions = SshQuestions(pool, secrets, notices)

        val key = pool.ask(hostKeyQuestion())
        val password = pool.ask(passwordQuestion())
        questions.poll()

        assertEquals(listOf(Triple(key.id, "trust", true), Triple(password.id, "hunter2", true)), pool.answers)
        assertNull(questions.pending.value)
        assertTrue(questions.needsAnswer.value.isEmpty())
        assertTrue(notices.asked.isEmpty())
    }

    // Review Focus 4: a saved password the host now refuses is deleted and asked again once.
    // #292: the pool marks a login's first password question with retry when the login before
    // ended with the host's refusal and no question could say so (a hang-up at sshd's
    // MaxAuthTries, a host that closed after refusing, the last try). The saved password that
    // was refused goes, and so does one given for that login: the user is asked each time.
    @Test
    fun aRefusalCarriedToTheNextLoginForgetsTheSavedPasswordThere() {
        val pool = FakePool { 0L }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.savePassword(target, "stale")
        val questions = SshQuestions(pool, secrets, RecordingNotices())
        // The saved password answers; the host hangs up on it, so no Retry comes in this login.
        val first = pool.ask(passwordQuestion())
        questions.poll()
        assertEquals(Triple(first.id, "stale", true), pool.answers.last())
        assertTrue(secrets.hasPassword(target))

        // Reconnect: the next login's first question says that the last answer was refused.
        val carried = pool.ask(passwordQuestion(retry = true))
        questions.poll()
        assertEquals(carried, questions.pending.value)
        assertFalse(secrets.hasPassword(target))
        // The user's answer, to be saved once it logs in, is refused the same way.
        questions.answer(carried.id, "also wrong", ok = true, savePassword = true, installKey = false)
        val again = pool.ask(passwordQuestion(retry = true))
        questions.poll()
        assertEquals(again, questions.pending.value)
        assertEquals(listOf("stale", "also wrong"), pool.answers.map { it.second })
        // The right one logs in and is saved.
        questions.answer(again.id, "right", ok = true, savePassword = true, installKey = false)
        questions.connected(target)
        assertEquals("right", secrets.password(target))
    }

    @Test
    fun aRefusedSavedPasswordIsForgottenAndAskedOnce() {
        val clock = longArrayOf(0)
        val pool = FakePool { clock[0] }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.savePassword(target, "old password")
        val questions = SshQuestions(pool, secrets, RecordingNotices())

        pool.ask(passwordQuestion())
        questions.poll()
        assertEquals(1, pool.answers.size)
        // The host refused it: the pool asks again in the same login.
        val again = pool.ask(passwordQuestion(retry = true))
        questions.poll()
        questions.poll()

        assertNull(secrets.password(target))
        assertEquals(again, questions.pending.value)
        assertEquals(setOf(target), questions.needsAnswer.value)
        assertEquals(1, pool.answers.size)
    }

    @Test
    fun theUsersAnswerGoesToThePoolAndTrustIsRemembered() {
        val pool = FakePool { 0 }
        val prefs = FakePrefs()
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
        val notices = RecordingNotices()
        val questions = SshQuestions(pool, secrets, notices, background = { it.run() })

        val key = pool.ask(hostKeyQuestion())
        questions.poll()
        assertEquals(key, questions.pending.value)
        assertEquals(listOf(key), notices.asked)
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals(Triple(key.id, "trust", true), pool.answers.last())
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey(hostPort))
        assertNull(questions.pending.value)

        // A password is kept only once it logged in: until then it is not saved.
        val password = pool.ask(passwordQuestion())
        questions.poll()
        questions.answer(password.id, "s3cret", ok = true, savePassword = true, installKey = true)
        assertEquals(Triple(password.id, "s3cret", true), pool.answers.last())
        assertNull(secrets.password(target))
        assertFalse(prefsText(prefs).contains("s3cret"))
        questions.connected(target)
        assertEquals("s3cret", secrets.password(target))
        // "Use this phone's key from now on": installed over the logged-in connection, once.
        assertEquals(listOf(target to secrets.publicLine()), pool.installs)
        questions.connected(target)
        assertEquals(1, pool.installs.size)
        assertTrue(questions.needsAnswer.value.isEmpty())
        assertTrue(target in notices.cleared)
    }

    @Test
    fun aDeclinedQuestionIsDeclinedInThePool() {
        val pool = FakePool { 0 }
        val questions = SshQuestions(pool, SshSecrets(FakePrefs(), FakeSealer(), FakeKeys()), RecordingNotices())
        val key = pool.ask(hostKeyQuestion())
        questions.poll()
        questions.answer(key.id, "", ok = false, savePassword = false, installKey = false)
        assertEquals(Triple(key.id, "", false), pool.answers.last())
        assertNull(questions.pending.value)
        assertTrue(questions.needsAnswer.value.isEmpty())
    }

    @Test
    fun anAnswerThatCameTooLateConnectsAgain() {
        val clock = longArrayOf(0)
        val pool = FakePool { clock[0] }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val again = mutableListOf<String>()
        val questions = SshQuestions(pool, secrets, RecordingNotices(), reconnect = { again += it })
        val key = pool.ask(hostKeyQuestion())
        questions.poll()
        clock[0] = 120_000
        // The dialog was still open when the pool gave up; the user's answer is still the user's.
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey(hostPort))
        assertEquals(listOf(target), again)
    }

    // Review Focus 2: nothing answered for 120 s leaves the remote in "needs your answer", and nothing asks again until Reconnect.
    @Test
    fun aQuestionUnansweredFor120SecondsWaitsForTheUserWithoutASecondQuestion() {
        val clock = longArrayOf(0)
        val pool = FakePool { clock[0] }
        val notices = RecordingNotices()
        val questions = SshQuestions(pool, SshSecrets(FakePrefs(), FakeSealer(), FakeKeys()), notices)
        // The remote's core dials when connected again; HostConnection's stop rule decides whether a redial may.
        var stoppedOn: String? = null
        fun dial() { pool.ask(passwordQuestion()) }
        fun redial() { if (mayReconnectAutomatically(stoppedOn)) dial() }

        dial()
        questions.poll()
        val asked = questions.pending.value
        assertNotNull(asked)
        assertEquals(setOf(target), questions.needsAnswer.value)

        clock[0] = 119_700
        questions.poll()
        assertEquals(asked, questions.pending.value)
        clock[0] = 120_000
        questions.poll()
        assertEquals(listOf(target), pool.declined)
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed("declined"))
        // A command refused while it is down says nothing about why it stopped.
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed("not_connected"))
        // The question has gone from the pool, so no dialog can answer it; the remote still waits for the user.
        assertNull(questions.pending.value)
        assertEquals(setOf(target), questions.needsAnswer.value)
        assertFalse(target in notices.cleared)

        // The tailnet changes, time passes: nothing reconnects it, nothing asks again.
        redial()
        clock[0] = 600_000
        questions.poll()
        assertEquals(1, pool.asked.size)
        assertEquals(setOf(target), questions.needsAnswer.value)

        // The user's Reconnect is what asks again.
        questions.retry(target)
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.UserReconnect)
        assertTrue(questions.needsAnswer.value.isEmpty())
        assertTrue(target in notices.cleared)
        redial()
        assertEquals(2, pool.asked.size)
    }

    @Test
    fun aStopEndsOnlyWithAConnectionOrTheUsersReconnect() {
        var stop: String? = null
        stop = stoppedAfter(stop, DialEvent.Failed("unreachable"))
        assertNull(stop)
        stop = stoppedAfter(stop, DialEvent.Failed("host_key_changed"))
        assertEquals("host_key_changed", stop)
        assertFalse(mayReconnectAutomatically(stop))
        for (code in listOf("unreachable", "not_connected", "ssh_not_ready")) assertEquals(code, "host_key_changed", stoppedAfter(stop, DialEvent.Failed(code)))
        assertEquals("declined", stoppedAfter(stop, DialEvent.Failed("declined")))
        assertNull(stoppedAfter(stop, DialEvent.UserReconnect))
        assertNull(stoppedAfter(stop, DialEvent.Connected))
        assertTrue(mayReconnectAutomatically(stoppedAfter(stop, DialEvent.Connected)))
    }

    @Test
    fun answeringTheSameQuestionTwiceSendsOneAnswer() {
        val pool = FakePool { 0 }
        val questions = SshQuestions(pool, SshSecrets(FakePrefs(), FakeSealer(), FakeKeys()), RecordingNotices())
        val password = pool.ask(passwordQuestion())
        questions.poll()
        questions.answer(password.id, "s3cret", ok = true, savePassword = false, installKey = false)
        questions.answer(password.id, "s3cret", ok = true, savePassword = false, installKey = false)
        questions.answer(password.id, "", ok = false, savePassword = false, installKey = false)
        assertEquals(listOf(Triple(password.id, "s3cret", true)), pool.answers)
    }

    @Test
    fun aTrustedKeyAnswersTheOtherLoginsToTheSameHostAndPort() {
        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val questions = SshQuestions(pool, secrets, RecordingNotices())
        val root = "ssh://root@studio.example:2222"
        val mine = pool.ask(hostKeyQuestion())
        val same = pool.ask(hostKeyQuestion().copy(target = root))
        val other = pool.ask(hostKeyQuestion().copy(target = "ssh://admin@studio.example:2222", fingerprint = "SHA256:anotherkeyanotherkeyanotherkeyanotherkeyano"))
        val elsewhere = pool.ask(hostKeyQuestion().copy(target = "ssh://alice@nas.example", text = "nas.example:22"))
        questions.poll()
        assertEquals(4, questions.waiting().size)

        questions.answer(mine.id, "", ok = true, savePassword = false, installKey = false)
        // The key it trusted answers the others for studio.example:2222; another key there is "changed".
        assertEquals(listOf(Triple(mine.id, "trust", true), Triple(same.id, "trust", true), Triple(other.id, "changed", true)), pool.answers)
        assertEquals(listOf(elsewhere), questions.waiting())
        assertEquals(elsewhere, questions.pending.value)
        assertEquals(setOf(elsewhere.target), questions.needsAnswer.value)
        // A later Trust of the other key cannot replace the one trusted: that question is gone.
        questions.answer(other.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals("ssh-ed25519 $fingerprint", secrets.hostKey(hostPort))
    }

    @Test
    fun connectedLeavesThePhoneKeyToTheBackground() {
        val pool = FakePool { 0 }
        val keys = FakeKeys()
        val sealer = FakeSealer()
        val secrets = SshSecrets(FakePrefs(), sealer, keys)
        val queued = mutableListOf<Runnable>()
        val questions = SshQuestions(pool, secrets, RecordingNotices(), background = { queued += it })
        val password = pool.ask(passwordQuestion())
        questions.poll()
        questions.answer(password.id, "s3cret", ok = true, savePassword = false, installKey = true)

        // An import holds the secrets' lock for seconds (its bcrypt KDF): connected() runs on
        // the main thread and must not wait for it.
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(secrets) { held.countDown(); release.await() } }.apply { start() }
        held.await()
        val done = java.util.concurrent.CountDownLatch(1)
        Thread { questions.connected(target); done.countDown() }.start()
        val waited = !done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        release.countDown()
        holder.join()
        assertFalse("connected() waited for the secrets' lock", waited)
        done.await()

        // Neither made nor opened there: the key is read where it is installed.
        assertEquals(0, keys.generated)
        assertEquals(0, sealer.opens)
        assertTrue(pool.installs.isEmpty())
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(listOf(target to secrets.publicLine()), pool.installs)
    }

    @Test
    fun aKeystoreThatFailsLeavesTheConnectionAlone() {
        val pool = FakePool { 0 }
        val broken = object : Sealer {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
            override fun open(sealed: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
        }
        val logged = mutableListOf<String>()
        val secrets = SshSecrets(FakePrefs(), broken, FakeKeys())
        val questions = SshQuestions(pool, secrets, RecordingNotices(), background = { it.run() }, log = { logged += it })
        val password = pool.ask(passwordQuestion())
        questions.poll()
        questions.answer(password.id, "s3cret", ok = true, savePassword = true, installKey = true)

        questions.connected(target)

        assertNull(secrets.password(target))
        assertTrue(pool.installs.isEmpty())
        assertTrue(logged.isNotEmpty())
        assertFalse(logged.toString(), logged.any { it.contains("s3cret") || it.contains("busy") })
        assertTrue(logged.toString(), logged.any { it.contains("KeyStoreException") })
    }

    @Test
    fun aKeystoreThatCannotOpenNowKeepsWhatItSealed() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).apply { identityPem(); savePassword(target, "hunter2") }
        val before = prefsText(prefs)
        val busy = object : Sealer {
            override fun seal(plain: ByteArray) = FakeSealer().seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
        }
        val now = SshSecrets(prefs, busy, keys)
        assertNull(now.password(target))
        assertTrue(runCatching { now.identityPem() }.isFailure)
        assertEquals(before, prefsText(prefs))
        assertEquals(1, keys.generated)
        // Its key gone for good (not only a tag that fails): that is lost.
        val gone = object : Sealer {
            override fun seal(plain: ByteArray) = FakeSealer().seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw SealKeyLost()
        }
        val lost = SshSecrets(prefs, gone, keys)
        assertNull(lost.password(target))
        assertNull(prefs.getString("ssh:password:$target", null))
        assertTrue(lost.identityPem().contains("phone-key-2"))
    }

    @Test
    fun theFirstPublicLineOfAProcessIsTheKeyThatOpens() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        val old = SshSecrets(prefs, FakeSealer(), keys).publicLine()
        val lost = object : Sealer {
            override fun seal(plain: ByteArray) = FakeSealer().seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
        }
        val line = SshSecrets(prefs, lost, keys).publicLine()
        assertTrue(line != old)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone2 velo", line)
        assertEquals(line, prefs.getString("ssh:public", null))
    }

    @Test
    fun sshValuesMoveOutOfTheAppsPrefsOnce() {
        val app = FakePrefs()
        val ssh = FakePrefs()
        app.edit().putFloat("fontSize", 13f).putString("ssh:identity", "sealed-key").putString("ssh:public", "ssh-ed25519 AAAA velo")
            .putString("ssh:password:$target", "sealed-pw").putString("ssh:hostkey:$hostPort", "ssh-ed25519 $fingerprint").apply()
        moveSshPrefs(app, ssh)
        assertEquals(setOf("fontSize"), app.all.keys)
        assertEquals(setOf("ssh:identity", "ssh:public", "ssh:password:$target", "ssh:hostkey:$hostPort"), ssh.all.keys)
        assertEquals("sealed-pw", ssh.getString("ssh:password:$target", null))
        // Again: nothing to move. A value already in the ssh prefs is kept.
        app.edit().putString("ssh:public", "stale").apply()
        moveSshPrefs(app, ssh)
        assertEquals("ssh-ed25519 AAAA velo", ssh.getString("ssh:public", null))
        assertEquals(setOf("fontSize"), app.all.keys)
    }

    // Fix round 1: when the ssh prefs file does not take the values (its commit fails), the app's
    // prefs keep them, and the next start moves them.
    @Test
    fun sshValuesStayInTheAppsPrefsWhenTheNewFileFails() {
        val app = FakePrefs()
        val ssh = FakePrefs()
        app.edit().putFloat("fontSize", 13f).putString("ssh:identity", "sealed-key").putString("ssh:password:$target", "sealed-pw").commit()
        ssh.commitsFail = true
        moveSshPrefs(app, ssh)
        assertEquals("sealed-key", app.getString("ssh:identity", null))
        assertEquals("sealed-pw", app.getString("ssh:password:$target", null))

        // The next start: the ssh file holds nothing, and the values move now.
        ssh.crash()
        ssh.commitsFail = false
        moveSshPrefs(app, ssh)
        ssh.crash()
        assertEquals(setOf("ssh:identity", "ssh:password:$target"), ssh.all.keys)
        assertEquals(setOf("fontSize"), app.all.keys)
    }

    @Test
    fun anSshRemoteDialsOnceThePoolRunsAndTheNodeDoesNotHoldIt() {
        assertFalse(firstDialWaits("https://workstation.example.net", nodeStarting = false, sshReady = false))
        assertTrue(firstDialWaits("https://workstation.example.net", nodeStarting = true, sshReady = true))
        assertTrue(firstDialWaits(target, nodeStarting = false, sshReady = false))
        assertFalse(firstDialWaits(target, nodeStarting = false, sshReady = true))
        // The pool is ready: an ssh remote dials, unless it also waits for the node, whose start dials it.
        assertTrue(dialsWhenSshReady(target, nodeStarting = false))
        assertFalse(dialsWhenSshReady(target, nodeStarting = true))
        assertFalse(dialsWhenSshReady("https://workstation.example.net", nodeStarting = false))
    }

    @Test
    fun aPoolSetupThatThrowsFailsWithoutTakingTheAppDown() {
        val logged = mutableListOf<String>()
        var given = 0
        // Any exception, not only a library that cannot load: the setup failed, and nothing escapes its thread.
        assertFalse(setUpSshPool({ throw IllegalStateException("x") }, { given++ }, { logged += it }))
        assertFalse(setUpSshPool({ throw SecurityException("x") }, { given++ }, { logged += it }))
        assertFalse(setUpSshPool({ throw UnsatisfiedLinkError("x") }, { given++ }, { logged += it }))
        assertFalse(setUpSshPool({ 22 }, { given++ }, { logged += it }))
        assertEquals(0, given)
        assertTrue(setUpSshPool({ 0 }, { given++ }, { logged += it }))
        assertEquals(1, given)
        // Keys that cannot be given leave the pool made: logins ask for a password.
        assertTrue(setUpSshPool({ 0 }, { throw java.security.KeyStoreException("busy") }, { logged += it }))
        assertEquals(listOf("init: IllegalStateException", "init: SecurityException", "init: UnsatisfiedLinkError", "init: 22",
            "keys: KeyStoreException"), logged)
    }

    @Test
    fun onlyTheUsersReconnectRestartsARemoteStoppedOnAFailureRetryingCannotFix() {
        for (code in listOf("declined", "host_key_changed", "password_refused", "key_refused", "not_installed", "malformed", "no_user", "ssh_unavailable", "no_common_algorithm", "login_refused")) {
            assertFalse(code, mayReconnectAutomatically(code))
        }
        for (code in listOf(null, "unreachable", "not_ssh", "closed_before_login", "ssh_not_ready", "connect_failed", "internal")) {
            assertTrue("$code", mayReconnectAutomatically(code))
        }
    }

    @Test
    fun keysTheKeystoreCouldNotOpenAreTriedAgainLessAndLessOften() {
        assertEquals(listOf(30_000L, 120_000L, 300_000L, 600_000L, 600_000L, 600_000L), (1..6).map(::keyRetryDelayMs))
    }

    // A key the Keystore cannot open now is given to the pool on the backoff, not on every dial;
    // once it opens, no try is due and the keys the pool offers changed.
    @Test
    fun aKeyThatCannotOpenNowIsTriedOnTheBackoff() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        var busy = true
        val secrets = secretsWithBusyKey(prefs, keys, 1, { busy })
        val main = FakeMain()
        var now = 0L
        val given = mutableListOf<Int>()
        var changes = 0
        val logged = mutableListOf<String>()
        val g = giver(secrets, main, set = { given += it.size; 0 }, clock = { now }, changed = { changes++ }, log = { logged += it })

        // The pool's setup, then the tries the backoff plans, each when the handler runs it.
        g.give()
        main.run()
        val delays = mutableListOf(main.delay)
        repeat(5) {
            // A dial before the due time tries nothing.
            now += main.delay!! - 1
            g.tryIfDue()
            now += 1
            main.fire()
            delays += main.delay
        }
        assertEquals(listOf(30_000L, 120_000L, 300_000L, 600_000L, 600_000L, 600_000L), delays)
        assertEquals(List(6) { 1 }, given)
        // The phone key alone, from no keys: one change, then the same keys again.
        assertEquals(1, changes)

        busy = false
        now += main.delay!!
        main.fire()
        assertNull(main.delay)
        assertEquals(2, given.last())
        assertEquals(2, changes)
        // All open: no try is due, and the same keys are no change. The next miss starts at 30 s again.
        g.give()
        main.run()
        assertNull(main.delay)
        assertEquals(2, changes)
        busy = true
        g.give()
        main.run()
        assertEquals(30_000L, main.delay)
        assertEquals(3, changes)
        assertTrue(logged.isEmpty())
    }

    // A giving the pool refused (setIdentities failed: the keys stay as they were) is no change;
    // a key left out (1000+N) leaves the others set, so it is one. The log names codes only.
    @Test
    fun onlyKeysThePoolTookCountAsAChange() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val main = FakeMain()
        var rc = 22
        var changes = 0
        val logged = mutableListOf<String>()
        val g = giver(secrets, main, set = { rc }, changed = { changes++ }, log = { logged += it })
        g.give()
        main.run()
        assertEquals(0, changes)
        rc = SSH_KEY_LEFT_OUT + 1
        g.give()
        main.run()
        assertEquals(1, changes)
        rc = 0
        g.give()
        main.run()
        assertEquals(1, changes)
        assertEquals(listOf("set identities: 22", "set identities: key 1 left out, it does not parse"), logged)
        val throwing = giver(secrets, main, set = { throw UnsatisfiedLinkError("x") }, changed = { changes++ }, log = { logged += it })
        throwing.give()
        main.run()
        assertEquals("keys: UnsatisfiedLinkError", logged.last())
        assertEquals(1, changes)
    }

    // When the keys the pool offers change, an ssh remote stopped on key_refused connects again by
    // itself, once per change: the key the host wants may be there now. Another stop stays.
    @Test
    fun aRemoteStoppedOnKeyRefusedReconnectsOnceWhenTheKeysChange() {
        val prefs = FakePrefs()
        val main = FakeMain()
        lateinit var g: SshKeyGiver
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys(), keysChanged = { g.give() })
        val other = "ssh://root@studio.example:2222"
        var stoppedOn: String? = null
        var dials = 0
        fun states() = listOf(
            SshDialState(target, "error", stoppedOn),
            SshDialState(other, "error", "password_refused"),
            SshDialState("https://workstation.example.net", "error", "key_refused"),
            SshDialState("ssh://alice@old-box", "reconnecting", null),
        )
        g = giver(secrets, main, changed = {
            for (url in reconnectsAfterKeysChange(states(), g.waiting)) {
                assertEquals(target, url)
                stoppedOn = stoppedAfter(stoppedOn, DialEvent.UserReconnect)
                dials++
                // The host refuses the keys again.
                stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed("key_refused"))
            }
        })

        // The pool's setup: nothing is stopped yet.
        g.give()
        main.run()
        assertEquals(0, dials)
        // The host refused the phone key; the same keys given again (a tried key, the page) are no change.
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed("key_refused"))
        g.give()
        g.give()
        main.run()
        assertEquals(0, dials)
        // An import: one reconnect, and the host's refusal stops it again until the next change.
        val one = secrets.importKey(keyFile(1), null).getOrThrow()
        main.run()
        assertEquals(1, dials)
        assertEquals("key_refused", stoppedOn)
        g.give()
        main.run()
        assertEquals(1, dials)
        // A remove and a replace are changes too.
        secrets.removeKey(one.id)
        main.run()
        assertEquals(2, dials)
        secrets.replaceKey()
        main.run()
        assertEquals(3, dials)
        // A remote that is not stopped on key_refused is left alone.
        stoppedOn = null
        secrets.importKey(keyFile(2), null).getOrThrow()
        main.run()
        assertEquals(3, dials)
    }

    // Fix round 1, I1: a destroyed controller closes its giver. A planned try, a post already
    // queued, a dial and a giving under way then do nothing: the pool is one per process, and
    // the next controller's keys may be in it already.
    @Test
    fun aClosedGiverGivesTheKeysNoMore() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        var busy = true
        val main = FakeMain()
        var now = 0L
        val set = mutableListOf<Int>()
        var changes = 0
        val g = giver(secretsWithBusyKey(prefs, keys, 1, { busy }), main, set = { set += it.size; 0 }, clock = { now }, changed = { changes++ })
        // The pool's setup: a try planned in 30 s, the change still in the main queue.
        g.give()
        g.close()
        main.run()
        assertNull(main.delay)
        assertEquals(0, changes)
        // The key opens now, the time passes, a dial comes: nothing more reaches the pool.
        busy = false
        now += 3_600_000
        main.fire()
        g.tryIfDue()
        g.give()
        g.reconnect(target) {}
        main.run()
        assertEquals(listOf(1), set)
        assertFalse(g.keysMissing)

        // A giving that read the keys before the close does not set them after it.
        lateinit var late: SshKeyGiver
        var closeWhileReading = false
        val real = FakeSealer()
        val hooked = object : Sealer {
            override fun seal(plain: ByteArray) = real.seal(plain)
            override fun open(sealed: ByteArray): ByteArray {
                if (closeWhileReading) {
                    closeWhileReading = false
                    late.close()
                }
                return real.open(sealed)
            }
        }
        val lateSet = mutableListOf<Int>()
        late = giver(SshSecrets(prefs, hooked, keys), FakeMain(), set = { lateSet += it.size; 0 })
        closeWhileReading = true
        late.give()
        assertTrue(lateSet.isEmpty())
        // The next controller's giver gives them as usual.
        val nextSet = mutableListOf<Int>()
        giver(SshSecrets(prefs, FakeSealer(), keys), FakeMain(), set = { nextSet += it.size; 0 }).give()
        assertEquals(listOf(2), nextSet)
    }

    // Fix round 1, I2: the due time is on elapsedRealtime. The handler's clock stops in deep
    // sleep, so the first dial after the due time tries the keys at once, and only once.
    @Test
    fun aDialAfterTheDueTimeTriesTheKeysAtOnce() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        var busy = true
        val main = FakeMain()
        var now = 0L
        val set = mutableListOf<Int>()
        val g = giver(secretsWithBusyKey(prefs, keys, 1, { busy }), main, set = { set += it.size; 0 }, clock = { now })
        g.give()
        main.run()
        assertEquals(30_000L, main.delay)
        // The phone slept past the due time; the handler has not run the planned try.
        now += 45_000
        busy = false
        g.tryIfDue()
        g.tryIfDue()
        main.run()
        assertEquals(listOf(1, 2), set)
        assertNull(main.delay)
        assertFalse(g.keysMissing)
    }

    // Fix round 1, I2: the user's Reconnect while a key is missing gives the keys first, then
    // dials, so a key the Keystore opens now goes with that login; the key change does not dial
    // that remote a second time. With every key there, Reconnect dials at once.
    @Test
    fun theUsersReconnectBringsAKeyThatOpensNow() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        var busy = true
        val main = FakeMain()
        val events = mutableListOf<String>()
        var stoppedOn: String? = null
        lateinit var g: SshKeyGiver
        g = giver(secretsWithBusyKey(prefs, keys, 1, { busy }), main, set = { events += "keys ${it.size}"; 0 }, changed = {
            for (url in reconnectsAfterKeysChange(listOf(SshDialState(target, "error", stoppedOn)), g.waiting)) events += "again $url"
        })
        g.give()
        main.run()
        // The host refused the phone key alone; the Keystore opens the other one now, long before the planned try.
        stoppedOn = stoppedAfter(stoppedOn, DialEvent.Failed("key_refused"))
        events.clear()
        busy = false
        assertTrue(g.keysMissing)

        g.reconnect(target) { events += "dial" }
        assertEquals(setOf(target), g.waiting)
        main.run()
        assertEquals(listOf("keys 2", "dial"), events)
        assertTrue(g.waiting.isEmpty())
        assertNull(main.delay)

        events.clear()
        g.reconnect(target) { events += "dial" }
        assertEquals(listOf("dial"), events)
    }

    // Final fix H1: repeated Reconnect taps while the keys are still on their way give once and
    // dial once; each give would count a try and push the retry's backoff out.
    @Test
    fun quickReconnectsOfOneRemoteGiveOnceAndDialOnce() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        val main = FakeMain()
        val background = ArrayDeque<Runnable>()
        var gives = 0
        val g = SshKeyGiver(secretsWithBusyKey(prefs, keys, 1, { true }), { gives++; 0 }, main, { background.addLast(it) }, { 0L }, {}, {})
        background.addLast(Runnable { g.give() })
        while (background.isNotEmpty()) background.removeFirst().run()
        main.run()
        assertEquals(30_000L, main.delay)
        gives = 0
        val dials = mutableListOf<String>()

        g.reconnect(target) { dials += "dial" }
        g.reconnect(target) { dials += "dial" }
        assertEquals(1, background.size)
        while (background.isNotEmpty()) background.removeFirst().run()
        main.run()
        assertEquals(1, gives)
        assertEquals(listOf("dial"), dials)
        // The second giving counted one try, not two: the next delay is the second step.
        assertEquals(keyRetryDelayMs(2), main.delay)
        assertTrue(g.waiting.isEmpty())
    }

    // #292: a change of the keys while one is missing gives the pool the keys at once, but is no
    // try of the backoff: the planned try stays where it is, and no step of the backoff is skipped.
    @Test
    fun aKeyChangeWhileAKeyIsMissingIsNoTryOfTheBackoff() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        val main = FakeMain()
        var now = 0L
        val given = mutableListOf<Int>()
        lateinit var g: SshKeyGiver
        val secrets = secretsWithBusyKey(prefs, keys, 1, { true }, keysChanged = { g.giveChanged() })
        g = giver(secrets, main, set = { given += it.size; 0 }, clock = { now })
        g.give()
        main.run()
        assertEquals(30_000L, main.delay)

        // Two imports and a remove 10 s later: the pool gets each list, the try stays planned for 30 s.
        now = 10_000
        secrets.importKey(keyFile(2), null).getOrThrow()
        val three = secrets.importKey(keyFile(3), null).getOrThrow()
        secrets.removeKey(three.id)
        main.run()
        assertEquals(listOf(1, 2, 3, 2), given)
        assertEquals(30_000L, main.delay)
        now = 29_999
        g.tryIfDue()
        assertEquals(4, given.size)
        // The planned try, when it was due: the second try of the backoff, not the fifth.
        now = 30_000
        main.fire()
        assertEquals(5, given.size)
        assertEquals(120_000L, main.delay)

        // A change that finds a key missing for the first time starts the backoff, as any giving does.
        val fresh = FakeMain()
        var busy = false
        lateinit var late: SshKeyGiver
        val lateSecrets = secretsWithBusyKey(prefs, keys, 1, { busy }, keysChanged = { late.giveChanged() })
        late = giver(lateSecrets, fresh, clock = { now })
        late.give()
        fresh.run()
        assertNull(fresh.delay)
        busy = true
        lateSecrets.importKey(keyFile(3), null).getOrThrow()
        fresh.run()
        assertEquals(30_000L, fresh.delay)
        assertTrue(late.keysMissing)
    }

    // Review of #294: a change of the keys that arrives while the planned try is already on its way
    // (its due time is taken, its giving waits for the secrets' lock) plans a try at the same step;
    // the try itself then counts as the next one. No step is skipped, and one is always planned.
    @Test
    fun aKeyChangeWhileThePlannedTryIsOnItsWayKeepsTheStep() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        val main = FakeMain()
        val background = ArrayDeque<Runnable>()
        var now = 0L
        lateinit var g: SshKeyGiver
        val secrets = secretsWithBusyKey(prefs, keys, 1, { true }, keysChanged = { g.giveChanged() })
        g = SshKeyGiver(secrets, { 0 }, main, { background.addLast(it) }, { now }, {}, {})
        g.give()
        main.run()
        assertEquals(30_000L, main.delay)

        // The planned try is due: it takes the due time and goes to the background.
        now = 30_000
        g.tryIfDue()
        assertEquals(1, background.size)
        // An import gets the lock before it.
        secrets.importKey(keyFile(2), null).getOrThrow()
        main.run()
        assertEquals(30_000L, main.delay)
        // Then the try: the second of the backoff.
        while (background.isNotEmpty()) background.removeFirst().run()
        main.run()
        assertEquals(120_000L, main.delay)
        assertTrue(g.keysMissing)
    }

    // #292: the planned try opens the missing key while a Reconnect still waits for the keys. The
    // next tap joins the one that waits; it does not dial at once and leave a second dial to come.
    @Test
    fun aTapWhileAReconnectWaitsForTheKeysJoinsItAlthoughNoKeyIsMissingAnyMore() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        var busy = true
        val main = FakeMain()
        val background = ArrayDeque<Runnable>()
        val g = SshKeyGiver(secretsWithBusyKey(prefs, keys, 1, { busy }), { 0 }, main, { background.addLast(it) }, { 0L }, {}, {})
        g.give()
        main.run()
        assertTrue(g.keysMissing)
        val dials = mutableListOf<String>()

        g.reconnect(target) { dials += "first" }
        // The planned try's giving gets the lock first and opens the key.
        busy = false
        g.give()
        main.run()
        assertFalse(g.keysMissing)
        g.reconnect(target) { dials += "second" }
        assertTrue(dials.isEmpty())
        while (background.isNotEmpty()) background.removeFirst().run()
        main.run()
        assertEquals(listOf("first"), dials)
        assertTrue(g.waiting.isEmpty())
        // Another remote does not wait for it: no key is missing, so it dials at once.
        g.reconnect("ssh://alice@other.example") { dials += "other" }
        assertEquals(listOf("first", "other"), dials)
    }

    // #292: whatever happens to the giving of a Reconnect that waits for the keys, the remote stops
    // waiting and its dial runs: a login without the keys asks for a password, and a later
    // Reconnect of that remote is not ignored.
    @Test
    fun aReconnectWhoseGivingOrDialFailsDoesNotLeaveTheRemoteWaiting() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        val main = FakeMain()
        var failure: Throwable? = null
        val logged = mutableListOf<String>()
        val g = giver(secretsWithBusyKey(prefs, keys, 1, { true }), main, set = { failure?.let { throw it }; 0 }, log = { logged += it })
        g.give()
        main.run()
        val dials = mutableListOf<String>()

        // The Keystore or the library fails: the giving logs it, and the dial runs.
        failure = IllegalStateException("keystore")
        g.reconnect(target) { dials += "after exception" }
        main.run()
        assertEquals(listOf("after exception"), dials)
        assertEquals("keys: IllegalStateException", logged.last())
        assertTrue(g.waiting.isEmpty())

        // An Error is not the giving's to catch: it goes on to its thread, and the remote is freed first.
        failure = StackOverflowError("deep")
        try {
            g.reconnect(target) { dials += "after error" }
            fail("the Error did not leave the giving")
        } catch (e: StackOverflowError) {
            assertEquals("deep", e.message)
        }
        main.run()
        assertEquals(listOf("after exception", "after error"), dials)
        assertTrue(g.waiting.isEmpty())

        // A dial that throws has left the waiting set already: the next Reconnect dials again.
        failure = null
        g.reconnect(target) { throw IllegalStateException("dial") }
        try {
            main.run()
            fail("the dial did not throw")
        } catch (e: IllegalStateException) {
            assertEquals("dial", e.message)
        }
        assertTrue(g.waiting.isEmpty())
        g.reconnect(target) { dials += "again" }
        main.run()
        assertEquals("again", dials.last())
    }

    @Test
    fun theQueueIsReadOftenOnlyWhileAnSshRemoteDialsOrAQuestionWaits() {
        val fast = SshQuestions.POLL_MS
        val slow = SshQuestions.SLOW_POLL_MS
        val https = SshDialState("https://workstation.example.net", status = "connecting", stoppedOn = null)
        val dialing = SshDialState(target, status = "connecting", stoppedOn = null)
        assertNull(questionPollMs(emptyList(), asking = false))
        assertNull(questionPollMs(listOf(https), asking = false))
        assertEquals(fast, questionPollMs(listOf(https, dialing), asking = false))
        assertEquals(fast, questionPollMs(listOf(dialing.copy(status = "idle")), asking = false))
        // Between the core's retries (it keeps "error" or "reconnecting" through the next dial): a saved answer still goes in time.
        assertEquals(slow, questionPollMs(listOf(dialing.copy(status = "error")), asking = false))
        assertEquals(slow, questionPollMs(listOf(dialing.copy(status = "reconnecting")), asking = false))
        assertEquals(fast, questionPollMs(listOf(dialing.copy(status = "error"), dialing), asking = false))
        assertNull(questionPollMs(listOf(dialing.copy(status = "connected")), asking = false))
        assertNull(questionPollMs(listOf(dialing.copy(status = "error", stoppedOn = "declined")), asking = false))
        // Waiting for the pool or the node to start: nothing dials yet.
        assertNull(questionPollMs(listOf(dialing.copy(waiting = true)), asking = false))
        // A question on screen is followed until the pool drops it.
        assertEquals(fast, questionPollMs(listOf(dialing.copy(status = "error", stoppedOn = "declined")), asking = true))
    }

    @Test
    fun aRemoteThatLostItsLinkIsReadOftenWhileItLogsInAgain() {
        val fast = SshQuestions.POLL_MS
        val slow = SshQuestions.SLOW_POLL_MS
        val window = SshQuestions.RELOGIN_POLL_MS
        assertEquals(30_000L, window)
        // Each new login asks about the host key, and a saved password may answer it: read
        // every 300 ms for the first 30 s after the link dropped, so neither waits 5 s.
        val dropped = SshDialState(target, status = "reconnecting", stoppedOn = null, reconnectingSince = 1_000L)
        assertEquals(fast, questionPollMs(listOf(dropped), asking = false, now = 1_000L))
        assertEquals(fast, questionPollMs(listOf(dropped), asking = false, now = 1_000L + window - 1))
        assertEquals(slow, questionPollMs(listOf(dropped), asking = false, now = 1_000L + window))
        assertEquals(slow, questionPollMs(listOf(dropped.copy(reconnectingSince = null)), asking = false, now = 1_000L))
        // Only while it is still reconnecting, and not when it stopped for good.
        assertEquals(slow, questionPollMs(listOf(dropped.copy(status = "error")), asking = false, now = 1_000L))
        assertNull(questionPollMs(listOf(dropped.copy(stoppedOn = "declined")), asking = false, now = 1_000L))
        // A pending question is read often whenever.
        assertEquals(fast, questionPollMs(listOf(dropped), asking = true, now = 1_000L + 10 * window))

        // The time a remote entered reconnecting: kept while it stays there, gone when it leaves.
        assertNull(reconnectingSince(null, "connected", now = 5L))
        assertEquals(5L, reconnectingSince(null, "reconnecting", now = 5L))
        assertEquals(5L, reconnectingSince(5L, "reconnecting", now = 9_000L))
        assertNull(reconnectingSince(5L, "connected", now = 9_000L))
        assertNull(reconnectingSince(5L, "error", now = 9_000L))
    }

    @Test
    fun aRemovedRemotesQuestionIsDeclinedAndItsPasswordForgotten() {
        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.savePassword(target, "hunter2")
        val notices = RecordingNotices()
        val questions = SshQuestions(pool, secrets, notices)
        val key = pool.ask(hostKeyQuestion())
        questions.poll()
        questions.removed(target)
        assertEquals(Triple(key.id, "", false), pool.answers.last())
        assertNull(secrets.password(target))
        assertNull(questions.pending.value)
        assertTrue(questions.needsAnswer.value.isEmpty())
        assertTrue(target in notices.cleared)
    }

    // Convenience A, its last step: once the phone's key has logged in, no password stays on the phone.
    @Test
    fun aLoginThatTheKeyAloneOpenedForgetsTheSavedPassword() {
        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        val logged = mutableListOf<String>()
        val questions = SshQuestions(pool, secrets, RecordingNotices(), background = { it.run() }, log = { logged += it })

        // First login: the key is not on the host yet, so it asks for the password; the user keeps "use this phone's key".
        val key = pool.ask(hostKeyQuestion())
        questions.poll()
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        val password = pool.ask(passwordQuestion())
        questions.poll()
        questions.answer(password.id, "s3cret", ok = true, savePassword = true, installKey = true)
        questions.connected(target)
        assertEquals("s3cret", secrets.password(target))
        assertEquals(1, pool.installs.size)

        // The core reconnects over the same SSH connection: no login, so nothing is learned.
        questions.connected(target)
        assertTrue(secrets.hasPassword(target))

        // The next login: the trusted key answers the host key question, and no password is asked.
        pool.ask(hostKeyQuestion())
        questions.poll()
        assertTrue(secrets.hasPassword(target))
        questions.connected(target)
        assertFalse(secrets.hasPassword(target))
        assertNull(secrets.password(target))
        assertEquals(1, pool.installs.size)
        assertTrue(logged.toString(), logged.any { it.contains(target) && it.contains("password") })
        assertFalse(logged.toString(), logged.any { it.contains("s3cret") })
    }

    @Test
    fun aLoginThatNeededThePasswordOrAPromptKeepsIt() {
        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.trustHostKey(hostPort, "ssh-ed25519 $fingerprint")
        secrets.savePassword(target, "hunter2")
        val questions = SshQuestions(pool, secrets, RecordingNotices())

        // The saved password answered: the key did not log in.
        pool.ask(hostKeyQuestion())
        pool.ask(passwordQuestion())
        questions.poll()
        questions.connected(target)
        assertEquals("hunter2", secrets.password(target))

        // The key passed, then the host asked a second factor: still not the key alone.
        pool.ask(hostKeyQuestion())
        questions.poll()
        val code = pool.ask(SshQuestion(0, "prompt", target, "Verification code:", echo = true))
        questions.poll()
        questions.answer(code.id, "123456", ok = true, savePassword = false, installKey = false)
        questions.connected(target)
        assertEquals("hunter2", secrets.password(target))

        // A login of another user of the same host is that login's own.
        val other = "ssh://root@studio.example:2222"
        pool.ask(hostKeyQuestion().copy(target = other))
        questions.poll()
        questions.connected(other)
        assertEquals("hunter2", secrets.password(target))
    }

    @Test
    fun forgettingAPasswordAlsoDropsTheOneNotYetSaved() {
        val pool = FakePool { 0 }
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.savePassword(target, "old")
        val questions = SshQuestions(pool, secrets, RecordingNotices())
        val password = pool.ask(passwordQuestion(retry = true))
        questions.poll()
        questions.answer(password.id, "new", ok = true, savePassword = true, installKey = false)

        questions.forgetPassword(target)
        questions.connected(target)

        assertFalse(secrets.hasPassword(target))
    }

    @Test
    fun anSshFailureShowsThePoolsSentenceAlone() {
        val passwordRejected = "Password rejected for alice on studio.example."
        assertEquals(passwordRejected, errorText("password_refused", passwordRejected))
        assertEquals("connect_failed: could not reach the server (ConnectionRefused)", errorText("connect_failed", "could not reach the server (ConnectionRefused)"))
        assertEquals("old-box uses only SSH algorithms that Velo doesn't accept.", errorText("no_common_algorithm", "old-box uses only SSH algorithms that Velo doesn't accept."))
        // #302: the host's first line, cleaned by the pool, on a line of its own under the sentence.
        val turnedAway = "studio.example didn't let alice log in.\nstudio.example says: System is booting up."
        assertEquals(turnedAway, errorText("login_refused", turnedAway))
        val notInstalled = "Velo couldn't find Rex on studio.example."
        val failure = connectionFailure("not_installed", notInstalled, "rex server install $target")
        assertEquals(ConnectionFailure("not_installed", notInstalled, "rex server install $target"), failure)
        assertEquals(failure, keptFailure(failure, "error"))
    }
}
