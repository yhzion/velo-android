package io.github.yhzion.velo

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val alice = "ssh://alice@studio.example:2222"
private const val root = "ssh://root@studio.example:2222"
private const val hostPort = "studio.example:2222"
private const val keyA = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
private const val keyB = "SHA256:otherkeyotherkeyotherkeyotherkeyotherkeyoth"

/** The pool's queue, as far as these tests need it: what waits, and what was answered. */
private class Logins : SshPool {
    val waiting = LinkedHashMap<Long, SshQuestion>()
    val answers = mutableListOf<Pair<Long, String>>()
    private var last = 0L

    /** A new connection's first question: the host shows the key [fingerprint]. */
    fun hostKey(login: String = alice, fingerprint: String = keyA): SshQuestion =
        SshQuestion(++last, "hostkey", login, hostPort, keyType = "ssh-ed25519", fingerprint = fingerprint).also { waiting[it.id] = it }

    fun password(login: String = alice, retry: Boolean = false, afterStall: Boolean = false): SshQuestion =
        SshQuestion(++last, "password", login, "Enter the SSH password.", retry = retry, afterStall = afterStall).also { waiting[it.id] = it }

    override fun questions(): String = Json.encodeToString(waiting.values.toList())
    override fun answer(id: Long, answer: String, ok: Boolean): Boolean {
        if (waiting.remove(id) == null) return false
        answers += id to answer
        return true
    }
    override fun installKey(target: String, publicLine: String, timeoutMs: Int) = 0
}

/**
 * #29: a saved password belongs to the host key that accepted it. Each test is a login as the
 * pool asks it: the host key question first, then the password question.
 */
class SshPasswordHostKeyTest {
    private val prefs = FakePrefs()
    private val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
    private val pool = Logins()
    private val questions = SshQuestions(pool, secrets)

    /** A first login of [login] to a host that shows [fingerprint]: the user trusts the key if asked, types [password] and saves it. */
    private fun logInAndSave(login: String, password: String, fingerprint: String = keyA) {
        val key = pool.hostKey(login, fingerprint)
        questions.poll()
        if (questions.pending.value == key) questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        val asked = pool.password(login)
        questions.poll()
        assertEquals(asked, questions.pending.value)
        questions.answer(asked.id, password, ok = true, savePassword = true, installKey = false)
        questions.connected(login)
        assertEquals(password, secrets.password(login))
    }

    /** Every password in the answers sent to the pool: none of them may reach a host that did not accept it. */
    private fun sent() = pool.answers.map { it.second }.filter { it != SSH_TRUST && it != SSH_CHANGED }

    @Test
    fun aSavedPasswordAnswersTheHostWhoseKeyAcceptedIt() {
        logInAndSave(alice, "hunter2")
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$alice"])

        val key = pool.hostKey()
        val password = pool.password()
        questions.poll()
        assertNull(questions.pending.value)
        assertEquals(listOf(key.id to SSH_TRUST, password.id to "hunter2"), pool.answers.takeLast(2))
        // The key is public and kept beside the sealed password, which still reads as nothing.
        assertFalse(prefs.all.values.any { "$it".contains("hunter2") })
    }

    @Test
    fun afterForgetAndTrustOfAnotherKeyTheUserIsAskedAndNothingIsSent() {
        logInAndSave(alice, "hunter2")
        // SSH details → Host key → Forget, and the next login meets a host with another key.
        secrets.forgetHostKey(hostPort)
        val before = pool.answers.size
        val key = pool.hostKey(fingerprint = keyB)
        questions.poll()
        assertEquals(key, questions.pending.value)
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        assertEquals("ssh-ed25519 $keyB", secrets.hostKey(hostPort))

        val password = pool.password()
        questions.poll()
        // The password is the user's to type: the one the other host accepted went nowhere.
        assertEquals(password, questions.pending.value)
        assertEquals(listOf(key.id to SSH_TRUST), pool.answers.drop(before))
        assertFalse(secrets.hasPassword(alice))

        // What the user types is saved for the new key once it logs in, and answers that host.
        questions.answer(password.id, "s3cret", ok = true, savePassword = true, installKey = false)
        questions.connected(alice)
        assertEquals("s3cret", secrets.password(alice))
        assertEquals("ssh-ed25519 $keyB", prefs.all["ssh:password-hostkey:$alice"])
        pool.hostKey(fingerprint = keyB)
        val next = pool.password()
        questions.poll()
        assertEquals(next.id to "s3cret", pool.answers.last())
        assertFalse("hunter2" in sent().drop(1))
    }

    @Test
    fun forgettingAHostKeyForgetsThePasswordsOfEveryLoginToThatHostAndPort() {
        val otherPort = "ssh://alice@studio.example"
        val otherHost = "ssh://alice@mac.example:2222"
        val otherCase = "ssh://bob@Studio.Example:2222"
        for (hostPort in listOf(hostPort, "studio.example:22", "mac.example:2222")) secrets.trustHostKey(hostPort, "ssh-ed25519 $keyA")
        for (login in listOf(alice, root, otherCase, otherPort, otherHost)) secrets.savePassword(login, "hunter2", "ssh-ed25519 $keyA")
        // One an older version saved, without a key, goes with the host's key as well.
        secrets.savePassword("ssh://old@studio.example:2222", "hunter2")

        secrets.forgetHostKey(hostPort)

        // Every user, and every case of the host's name.
        for (login in listOf(alice, root, otherCase, "ssh://old@studio.example:2222")) {
            assertFalse(login, secrets.hasPassword(login))
            assertNull(login, secrets.password(login))
        }
        // Another port and another host keep theirs.
        assertEquals("hunter2", secrets.password(otherPort))
        assertEquals("hunter2", secrets.password(otherHost))
        assertEquals(setOf(otherPort, otherHost), prefs.all.keys.filter { it.startsWith("ssh:password-hostkey:") }.map { it.removePrefix("ssh:password-hostkey:") }.toSet())
    }

    @Test
    fun aSecondLoginToTheSameHostKeepsItsOwnBinding() {
        logInAndSave(alice, "alice's")
        logInAndSave(root, "root's")
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$alice"])
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$root"])

        // Each login is answered with its own password.
        pool.hostKey(alice)
        val asAlice = pool.password(alice)
        pool.hostKey(root)
        val asRoot = pool.password(root)
        questions.poll()
        assertNull(questions.pending.value)
        assertTrue(asAlice.id to "alice's" in pool.answers)
        assertTrue(asRoot.id to "root's" in pool.answers)

        // Forgetting one login's password leaves the other's, with its key.
        questions.forgetPassword(alice)
        assertNull(prefs.all["ssh:password-hostkey:$alice"])
        assertEquals("root's", secrets.password(root))
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$root"])
    }

    // A password saved by a version before #29 has no key. The host trusted when this version
    // first uses it is the one that accepted it: the password is bound to that key, once.
    @Test
    fun aPasswordOfAnOlderVersionIsBoundToTheOneKeyTrusted() {
        secrets.trustHostKey(hostPort, "ssh-ed25519 $keyA")
        secrets.savePassword(alice, "hunter2")
        assertNull(prefs.all["ssh:password-hostkey:$alice"])

        pool.hostKey()
        val password = pool.password()
        questions.poll()
        assertEquals(password.id to "hunter2", pool.answers.last())
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$alice"])

        // From then on it is a bound password like any other.
        secrets.forgetHostKey(hostPort)
        assertFalse(secrets.hasPassword(alice))
    }

    // The older version's Forget left the password behind. No key is trusted for it now, so the
    // host that shows up next is not known to be the one that accepted it.
    @Test
    fun aPasswordOfAnOlderVersionWithNoTrustedKeyIsNotSentToTheHostTrustedNext() {
        secrets.savePassword(alice, "hunter2")

        // A password question while no key is trusted: the user's, and the password stays.
        val early = pool.password()
        questions.poll()
        assertEquals(early, questions.pending.value)
        assertTrue(secrets.hasPassword(alice))
        questions.answer(early.id, "", ok = false, savePassword = false, installKey = false)

        // The login as it really runs: the user trusts the key shown, and is asked the password.
        val key = pool.hostKey(fingerprint = keyB)
        questions.poll()
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        val password = pool.password()
        questions.poll()
        assertEquals(password, questions.pending.value)
        assertTrue(sent().toString(), sent().all { it.isEmpty() })
        assertNull(prefs.all["ssh:password-hostkey:$alice"])
    }

    @Test
    fun aPasswordOfAnOlderVersionIsNotUsedWhileTwoKeysAreKeptForTheHost() {
        prefs.edit().putString("ssh:hostkey:Studio.Example:2222", "ssh-ed25519 $keyA")
            .putString("ssh:hostkey:$hostPort", "ssh-ed25519 $keyB").apply()
        assertEquals(SshSecrets.HOST_KEYS_DIFFER, secrets.hostKey(hostPort))
        secrets.savePassword(alice, "hunter2")

        val password = pool.password()
        questions.poll()
        assertEquals(password, questions.pending.value)
        assertTrue(pool.answers.isEmpty())
        // Neither key is the host's: nothing is bound, and nothing is forgotten for it.
        assertNull(prefs.all["ssh:password-hostkey:$alice"])
        assertTrue(secrets.hasPassword(alice))
    }

    // Review Focus 4 (#245) and ruling R4 (#284) hold for a bound password as before.
    @Test
    fun retryAndAfterStallBehaveAsBefore() {
        logInAndSave(alice, "hunter2")
        val before = pool.answers.size

        // After a stall the user answers, and the saved password stays with its key.
        val stalled = pool.password(afterStall = true)
        questions.poll()
        assertEquals(stalled, questions.pending.value)
        questions.answer(stalled.id, "typo", ok = true, savePassword = false, installKey = false)
        val refusedTyped = pool.password(retry = true, afterStall = true)
        questions.poll()
        assertEquals(refusedTyped, questions.pending.value)
        assertEquals("hunter2", secrets.password(alice))
        assertEquals("ssh-ed25519 $keyA", prefs.all["ssh:password-hostkey:$alice"])
        questions.answer(refusedTyped.id, "", ok = false, savePassword = false, installKey = false)

        // The saved password answers the next login; the host refuses it, so it goes with its key.
        pool.hostKey()
        val asked = pool.password()
        questions.poll()
        assertEquals(asked.id to "hunter2", pool.answers.last())
        val refused = pool.password(retry = true)
        questions.poll()
        assertEquals(refused, questions.pending.value)
        assertFalse(secrets.hasPassword(alice))
        assertNull(prefs.all["ssh:password-hostkey:$alice"])
        assertEquals(1, pool.answers.drop(before).count { it.second == "hunter2" })
    }

    // The host key is forgotten between the user's answer and the login's end: what was typed
    // is not saved for a key the phone no longer trusts.
    @Test
    fun aPasswordTypedForAKeyForgottenMeanwhileIsNotSaved() {
        val key = pool.hostKey()
        questions.poll()
        questions.answer(key.id, "", ok = true, savePassword = false, installKey = false)
        val password = pool.password()
        questions.poll()
        questions.answer(password.id, "hunter2", ok = true, savePassword = true, installKey = false)

        secrets.forgetHostKey(hostPort)
        questions.connected(alice)

        assertFalse(secrets.hasPassword(alice))
        assertNull(prefs.all["ssh:password-hostkey:$alice"])
    }
}
