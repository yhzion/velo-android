package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val login = "ssh://alice@studio.example:2222"
private const val otherLogin = "ssh://root@studio.example:2222"
private const val loginHostPort = "studio.example:2222"
private const val trusted = "ssh-ed25519 SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"

/** Keys ssh-keygen made, and what `ssh-keygen -lf` printed for them. */
private const val ED25519_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBV/lk4eLqtWr3MQ0SLkyWVvReKOCfIBfpERm3dWFgbu vector"
private const val ED25519_FINGERPRINT = "SHA256:fkwY1xcyMNEXQThk3YAYTk8QL/YjIHSGNY7hFO01Ecw"
private const val ECDSA_LINE = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHRcMpRvbnwleXvR1/ppyPZiOB3lhq7KkcPbeeiKTIJulqwJKUDPOSjURs7yUb+BspSsluwQSiA71qn4GAOgKoc= vector"
private const val ECDSA_FINGERPRINT = "SHA256:GhswSPCeaDn6GU/dJmDa7Okd2newSgy5cnnUIIlFP1k"

/** An OpenSSH private key whose public half is the Ed25519 vector's ([ED25519_LINE]). */
private fun vectorKey(): String {
    val blob = Base64.getDecoder().decode(ED25519_LINE.split(' ')[1])
    // The blob: the string "ssh-ed25519" (4 + 11 bytes), then the 32-byte key as a string.
    return openSshPem("ssh-ed25519", blob.copyOfRange(4 + 11 + 4, blob.size)).first
}

private fun sealedText(sealer: Sealer, text: String) = Base64.getEncoder().encodeToString(sealer.seal(text.toByteArray()))

private fun prefsText(prefs: FakePrefs) = prefs.all.entries.joinToString("\n") { (k, v) -> "<string name=\"$k\">$v</string>" }

/** The lines of [pem] between its armour: the key itself. */
private fun body(pem: String) = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }

/** [FakeSealer], except that the Keystore cannot open now the values that hold a text of [busy]. */
private class BusySealer(private val busy: Set<String>) : Sealer {
    private val real = FakeSealer()
    override fun seal(plain: ByteArray) = real.seal(plain)
    override fun open(sealed: ByteArray): ByteArray {
        val plain = real.open(sealed)
        if (String(plain) in busy) {
            plain.fill(0)
            throw KeyStoreException("busy")
        }
        return plain
    }
}

/** [SshSecrets] whose pool is told every change of the keys: [given] holds the lists it got, in turn. */
private class Recorded(prefs: FakePrefs, sealer: Sealer = FakeSealer(), val keys: FakeKeys = FakeKeys()) {
    val given = mutableListOf<List<String>>()
    val secrets: SshSecrets = SshSecrets(prefs, sealer, keys, keysChanged = { given += secrets.identityPems() })
}

class SshSecretsTest {

    @Test
    fun theKeysAreThePhoneKeyThenTheImportedKeysInTheOrderImported() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        val one = r.secrets.importKey(keyFile(1), null).getOrThrow()
        val two = r.secrets.importKey(keyFile(2), null).getOrThrow()
        val three = r.secrets.importKey(keyFile(3), null).getOrThrow()
        val phonePem = r.secrets.identityPem()
        val pems = listOf(phonePem, testKey(1).first, testKey(2).first, testKey(3).first)

        val keys = r.secrets.keys()
        assertEquals(listOf(true, false, false, false), keys.map { it.phone })
        assertEquals(listOf(r.secrets.publicLine(), testKey(1).second, testKey(2).second, testKey(3).second), keys.map { it.publicLine })
        assertEquals(keys.map { sshFingerprint(it.publicLine) }, keys.map { it.fingerprint })
        assertEquals(listOf(one, two, three), keys.drop(1))
        assertEquals(4, keys.map { it.id }.toSet().size)
        assertEquals(pems, r.secrets.identityPems())
        // Each import gave the pool every key, in order.
        assertEquals(listOf(pems.take(2), pems.take(3), pems), r.given)

        // Another process start: the same keys, in the same order, under the same ids.
        val again = SshSecrets(prefs, FakeSealer(), r.keys)
        assertEquals(keys, again.keys())
        assertEquals(pems, again.identityPems())
        assertEquals(1, r.keys.generated)
        // Sealed like the phone key: no private key in the prefs.
        val text = prefsText(prefs)
        for (n in 1..3) for (line in body(testKey(n).first)) assertFalse(line, text.contains(line))
        assertFalse(text.contains("phone-key-1"))
    }

    @Test
    fun aKeyAlreadyKeptIsNotAddedTwice() {
        val r = Recorded(FakePrefs())
        val first = r.secrets.importKey(keyFile(1), null).getOrThrow()
        r.given.clear()

        // The same key from the same file, or as its unencrypted PEM: the key kept.
        assertEquals(first, r.secrets.importKey(keyFile(1), null).getOrThrow())
        assertEquals(first, r.secrets.importKey(testKey(1).first, null).getOrThrow())
        assertEquals(2, r.secrets.keys().size)
        assertEquals(2, r.secrets.identityPems().size)
        assertTrue(r.given.isEmpty())
    }

    @Test
    fun removingAnImportedKeyForgetsItAndTellsThePool() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        val one = r.secrets.importKey(keyFile(1), null).getOrThrow()
        val two = r.secrets.importKey(keyFile(2), null).getOrThrow()
        val three = r.secrets.importKey(keyFile(3), null).getOrThrow()
        val phone = r.secrets.keys().first()
        r.given.clear()

        assertTrue(r.secrets.removeKey(two.id))
        assertEquals(listOf(phone, one, three), r.secrets.keys())
        val pems = listOf(r.secrets.identityPem(), testKey(1).first, testKey(3).first)
        assertEquals(pems, r.secrets.identityPems())
        assertEquals(listOf(pems), r.given)
        // Nothing of it stays: neither its sealed key nor its public line.
        val text = prefsText(prefs)
        assertFalse(text.contains(testKey(2).second.substringAfter(' ')))
        assertEquals(listOf(phone, one, three), SshSecrets(prefs, FakeSealer(), r.keys).keys())

        // The phone key, a key removed already, an unknown id: nothing changes, and the pool is not told.
        assertFalse(r.secrets.removeKey(phone.id))
        assertFalse(r.secrets.removeKey(two.id))
        assertFalse(r.secrets.removeKey("no such key"))
        assertEquals(listOf(phone, one, three), r.secrets.keys())
        assertEquals(text, prefsText(prefs))
        assertEquals(1, r.given.size)

        // Imported again, it comes last.
        val back = r.secrets.importKey(keyFile(2), null).getOrThrow()
        assertEquals(listOf(phone, one, three, back), r.secrets.keys())
    }

    // An import, a removal and the forgetting of a lost key reach the file before they return
    // (commit, not apply): a removed key, maybe one that leaked, never comes back after a crash.
    @Test
    fun keyChangesAreWrittenBeforeTheyReturn() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        r.secrets.publicLine() // the phone key
        prefs.flush()
        val one = r.secrets.importKey(keyFile(1), null).getOrThrow()
        val two = r.secrets.importKey(keyFile(2), null).getOrThrow()
        prefs.crash()
        val phone = SshSecrets(prefs, FakeSealer(), r.keys).keys().first()
        assertEquals(listOf(phone, one, two), SshSecrets(prefs, FakeSealer(), r.keys).keys())

        assertTrue(r.secrets.removeKey(one.id))
        prefs.crash()
        assertEquals(listOf(phone, two), SshSecrets(prefs, FakeSealer(), r.keys).keys())
        assertFalse(prefsText(prefs).contains(testKey(1).second.substringAfter(' ')))

        // A key lost for good is forgotten for good too.
        prefs.edit().putString("ssh:imported:${two.id}", "not Base64!").commit()
        assertEquals(listOf(r.secrets.identityPem()), r.secrets.identityPems())
        prefs.crash()
        assertEquals(listOf(phone), SshSecrets(prefs, FakeSealer(), r.keys).keys())
    }

    // The pool leaves out a key that does not parse and keeps the others; the log names its
    // place, nothing of the key.
    @Test
    fun aKeyLeftOutIsLoggedByItsPlace() {
        assertNull(identitiesLog(0))
        assertEquals("set identities: key 2 left out, it does not parse", identitiesLog(SSH_KEY_LEFT_OUT + 2))
        assertEquals("set identities: 22", identitiesLog(22))
        assertEquals("set identities: -1", identitiesLog(-1))
    }

    // A key whose public line went missing (a write cut short) is imported again under its id,
    // once: the order lists it once, and the key page shows it once.
    @Test
    fun anIdWithoutItsPublicLineIsNotListedTwice() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        val one = r.secrets.importKey(keyFile(1), null).getOrThrow()
        prefs.edit().remove("ssh:imported-public:${one.id}").commit()

        assertEquals(one, r.secrets.importKey(keyFile(1), null).getOrThrow())
        assertEquals(one.id, prefs.getString("ssh:imported", null))
        assertEquals(listOf(true, false), r.secrets.keys().map { it.phone })
        assertEquals(2, r.secrets.identityPems().size)
    }

    // #245 Review Focus 5: the Keystore key is gone (a restore, or data that outlived it). Each
    // value is opened on its own (#284 Review Focus 4), and none opens: all of them go.
    @Test
    fun aLostKeystoreKeyReplacesThePhoneKeyAndDropsEverythingItSealed() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).apply {
            importKey(keyFile(1), null).getOrThrow()
            importKey(keyFile(2), null).getOrThrow()
            trustHostKey(loginHostPort, trusted)
            savePassword(login, "hunter2", trusted)
        }
        val gone = object : Sealer {
            override fun seal(plain: ByteArray) = FakeSealer().seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw SealKeyLost()
        }

        val after = SshSecrets(prefs, gone, keys)
        val pems = after.identityPems()
        assertEquals(1, pems.size)
        assertTrue(pems.single().contains("phone-key-2"))
        assertEquals(listOf(true), after.keys().map { it.phone })
        assertFalse(after.keysMissing)
        // Nothing sealed by the lost key stays; the trusted host key is public and stays.
        assertEquals(setOf("ssh:identity", "ssh:public", "ssh:hostkey:$loginHostPort"), prefs.all.keys)
        assertEquals(trusted, after.hostKey(loginHostPort))
    }

    @Test
    fun afterANewKeystoreKeyEverythingKeptOpens() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(key = 7), keys).apply {
            importKey(keyFile(1), null).getOrThrow()
            savePassword(login, "hunter2")
        }
        // Another Keystore key: what the old one sealed fails its tag. The key page reads first.
        val sealer = FakeSealer(key = 8)
        val after = SshSecrets(prefs, sealer, keys)
        assertEquals(listOf(true), after.keys().map { it.phone })
        assertFalse(after.hasPassword(login))
        val sealed = prefs.all.filterKeys { it == "ssh:identity" || it.startsWith("ssh:imported") || it.startsWith("ssh:password:") }
        assertEquals(setOf("ssh:identity"), sealed.keys)
        assertTrue(String(sealer.open(Base64.getDecoder().decode(sealed.getValue("ssh:identity") as String))).contains("phone-key-2"))
        // A key imported now is kept beside the new phone key.
        after.importKey(keyFile(3), null).getOrThrow()
        assertEquals(listOf(after.identityPem(), testKey(3).first), SshSecrets(prefs, sealer, keys).identityPems())
    }

    @Test
    fun aMalformedSealedValueIsLostAndHealsItself() {
        val keys = FakeKeys()

        // A phone key that is not Base64: a new phone key. Since #284 the imported key and the
        // password still open, and stay (before, they went with the old phone key).
        val prefs = FakePrefs()
        SshSecrets(prefs, FakeSealer(), keys).apply {
            importKey(keyFile(1), null).getOrThrow()
            savePassword(login, "hunter2")
        }
        prefs.edit().putString("ssh:identity", "not base64 at all!").apply()
        val healed = SshSecrets(prefs, FakeSealer(), keys)
        assertTrue(healed.identityPem().contains("phone-key-2"))
        assertEquals(listOf(true, false), healed.keys().map { it.phone })
        assertEquals(listOf(healed.identityPem(), testKey(1).first), healed.identityPems())
        assertFalse(healed.keysMissing)
        assertEquals("hunter2", healed.password(login))

        // An imported key of an unknown version: it alone goes.
        val other = FakePrefs()
        val secrets = SshSecrets(other, FakeSealer(), keys)
        val one = secrets.importKey(keyFile(1), null).getOrThrow()
        val two = secrets.importKey(keyFile(2), null).getOrThrow()
        val phone = secrets.keys().first()
        val bad = other.all.entries.single { (k, v) -> k.startsWith("ssh:imported") && v == sealedText(FakeSealer(), testKey(1).first) }.key
        other.edit().putString(bad, Base64.getEncoder().encodeToString(byteArrayOf(9, 7, 1, 2, 3))).apply()
        val again = SshSecrets(other, FakeSealer(), keys)
        assertEquals(listOf(again.identityPem(), testKey(2).first), again.identityPems())
        assertFalse(again.keysMissing)
        assertEquals(listOf(phone, two), again.keys())
        assertFalse(other.contains(bad))
        assertFalse(prefsText(other).contains(one.id))

        // A saved password too short to be a sealed value: forgotten.
        other.edit().putString("ssh:password:$login", "").apply()
        assertNull(again.password(login))
        assertFalse(again.hasPassword(login))
    }

    // Review Focus 4 (#284): the phone key's value is corrupt. It is replaced, and every other
    // sealed value is opened on its own: what opens stays, what never will goes, and what the
    // Keystore cannot open now stays as it is.
    @Test
    fun aCorruptPhoneKeyGoesAloneAndWhatStillOpensStays() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        val (one, two, three, four) = SshSecrets(prefs, FakeSealer(), keys).run {
            val imported = (1..4).map { importKey(keyFile(it), null).getOrThrow() }
            trustHostKey(loginHostPort, trusted)
            savePassword(login, "hunter2", trusted)
            savePassword(otherLogin, "not now", trusted)
            savePassword("ssh://alice@old-box", "malformed")
            imported
        }
        prefs.edit()
            .putString("ssh:identity", Base64.getEncoder().encodeToString(byteArrayOf(9, 7, 1, 2, 3)))
            .putString("ssh:imported:${four.id}", "not Base64!")
            .putString("ssh:password:ssh://alice@old-box", "")
            .commit()
        val threeSealed = prefs.getString("ssh:imported:${three.id}", null)
        val notNowSealed = prefs.getString("ssh:password:$otherLogin", null)

        val secrets = SshSecrets(prefs, BusySealer(setOf(testKey(3).first, "not now")), keys)
        val pems = secrets.identityPems()

        assertTrue(pems.first().contains("phone-key-2"))
        assertEquals(listOf(testKey(1).first, testKey(2).first), pems.drop(1))
        assertTrue(secrets.keysMissing)
        // The two that open stay, and so does the one the Keystore cannot open now, in its place.
        assertEquals(listOf(one, two, three), secrets.keys().drop(1))
        assertEquals(threeSealed, prefs.getString("ssh:imported:${three.id}", null))
        assertFalse(prefsText(prefs).contains(four.id))
        assertEquals("hunter2", secrets.password(login))
        assertEquals(notNowSealed, prefs.getString("ssh:password:$otherLogin", null))
        assertFalse(secrets.hasPassword("ssh://alice@old-box"))
        assertEquals(trusted, secrets.hostKey(loginHostPort))
        assertEquals(2, keys.generated)

        // Once the Keystore opens it, the key is offered, after the others, and the password answers.
        val later = SshSecrets(prefs, FakeSealer(), keys)
        assertEquals(listOf(later.identityPem(), testKey(1).first, testKey(2).first, testKey(3).first), later.identityPems())
        assertFalse(later.keysMissing)
        assertEquals("not now", later.password(otherLogin))
        assertEquals(2, keys.generated)
    }

    // Review Focus 5 (#284): Forget password is tapped while a login saves the same password.
    // The forget wins, and it does not wait for the Keystore that seals the save.
    @Test
    fun aForgetWhileTheSamePasswordIsSavedWins() {
        val sealing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealer = object : Sealer {
            val real = FakeSealer()
            @Volatile var hold = false
            override fun seal(plain: ByteArray): ByteArray {
                if (hold) {
                    sealing.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return real.seal(plain)
            }
            override fun open(sealed: ByteArray) = real.open(sealed)
        }
        val secrets = SshSecrets(FakePrefs(), sealer, FakeKeys())
        assertTrue(secrets.savePassword(login, "old"))
        assertTrue(secrets.savePassword(otherLogin, "root's"))

        sealer.hold = true
        var saved: Boolean? = null
        val save = thread { saved = secrets.savePassword(login, "new") }
        assertTrue(sealing.await(5, TimeUnit.SECONDS))
        // The Keystore holds the save; the forget goes through meanwhile.
        val forget = thread { secrets.forgetPassword(login) }
        forget.join(5_000)
        assertFalse(forget.isAlive)
        release.countDown()
        save.join(5_000)

        assertEquals(false, saved)
        assertFalse(secrets.hasPassword(login))
        assertNull(secrets.password(login))
        // Another login's password is not touched, and a save that begins after the forget is kept.
        assertEquals("root's", secrets.password(otherLogin))
        sealer.hold = false
        assertTrue(secrets.savePassword(login, "newer"))
        assertEquals("newer", secrets.password(login))
    }

    // A forget of another login while a save seals leaves that save alone.
    @Test
    fun aForgetOfAnotherLoginDoesNotDropASave() {
        lateinit var secrets: SshSecrets
        var during: () -> Unit = {}
        val sealer = object : Sealer {
            val real = FakeSealer()
            override fun seal(plain: ByteArray): ByteArray {
                val sealed = real.seal(plain)
                val run = during
                during = {}
                run()
                return sealed
            }
            override fun open(sealed: ByteArray) = real.open(sealed)
        }
        secrets = SshSecrets(FakePrefs(), sealer, FakeKeys())
        secrets.savePassword(otherLogin, "root's")
        during = { secrets.forgetPassword(otherLogin) }
        assertTrue(secrets.savePassword(login, "hunter2"))
        assertEquals("hunter2", secrets.password(login))
        assertFalse(secrets.hasPassword(otherLogin))
    }

    // A saved password found lost for good is forgotten, but not a new one saved after it was read.
    @Test
    fun forgettingALostPasswordLeavesANewerOne() {
        val prefs = FakePrefs()
        lateinit var secrets: SshSecrets
        var during: () -> Unit = {}
        val sealer = object : Sealer {
            val real = FakeSealer()
            override fun seal(plain: ByteArray) = real.seal(plain)
            override fun open(sealed: ByteArray): ByteArray {
                val run = during
                during = {}
                run()
                return real.open(sealed)
            }
        }
        secrets = SshSecrets(prefs, sealer, FakeKeys())
        prefs.edit().putString("ssh:password:$login", Base64.getEncoder().encodeToString(byteArrayOf(9, 7, 1, 2, 3))).commit()
        // While the lost value is opened, the login saves the password the user just typed.
        during = { assertTrue(secrets.savePassword(login, "typed")) }
        assertNull(secrets.password(login))
        assertEquals("typed", secrets.password(login))
    }

    // Import, Remove and Replace say when their change could not be written (commit failed), and
    // then nothing changed: neither the page nor the pool nor the file after a crash sees it.
    @Test
    fun aKeyChangeThatCannotBeWrittenSaysSoAndChangesNothing() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        val one = r.secrets.importKey(keyFile(1), null).getOrThrow()
        r.secrets.savePassword(login, "hunter2")
        val phone = r.secrets.keys().first()
        val phonePem = r.secrets.identityPem()
        prefs.flush()
        r.given.clear()
        val before = prefs.all
        prefs.commitsFail = true

        val imported = r.secrets.importKey(keyFile(2), null)
        assertTrue(imported.exceptionOrNull().toString(), imported.exceptionOrNull() is SshKeysNotSaved)
        assertEquals("Velo couldn't save the key change.", imported.exceptionOrNull()?.message)
        assertEquals(listOf(phone, one), r.secrets.keys())
        assertEquals(before, prefs.all)

        assertEquals("Velo couldn't save the key change.", assertThrows(SshKeysNotSaved::class.java) { r.secrets.removeKey(one.id) }.message)
        assertEquals(listOf(phone, one), r.secrets.keys())
        assertEquals(before, prefs.all)

        assertThrows(SshKeysNotSaved::class.java) { r.secrets.replaceKey() }
        assertEquals(listOf(phone, one), r.secrets.keys())
        assertEquals(phonePem, r.secrets.identityPem())
        assertEquals(before, prefs.all)

        // The pool was told nothing, and the file holds what the page shows.
        assertTrue(r.given.isEmpty())
        prefs.crash()
        assertEquals(listOf(phone, one), SshSecrets(prefs, FakeSealer(), r.keys).keys())

        // Once the file can be written, each change goes through.
        prefs.commitsFail = false
        val two = r.secrets.importKey(keyFile(2), null).getOrThrow()
        assertTrue(r.secrets.removeKey(one.id))
        r.secrets.replaceKey()
        assertEquals(listOf(true, false), r.secrets.keys().map { it.phone })
        assertEquals(two, r.secrets.keys().last())
        assertFalse(r.secrets.publicLine() == phone.publicLine)
        assertEquals(3, r.given.size)
        prefs.crash()
        assertEquals(r.secrets.keys(), SshSecrets(prefs, FakeSealer(), r.keys).keys())
        assertEquals("hunter2", r.secrets.password(login))
    }

    // #284 Screens: an imported key is named by the comment its file carries (`ssh-keygen -C`).
    // The comment is kept beside the key's public line, which stays the key's own (type and Base64).
    @Test
    fun anImportedKeyKeepsTheCommentItsFileCarries() {
        val prefs = FakePrefs()
        val r = Recorded(prefs)
        val (pem, line) = openSshPem("ssh-ed25519", ByteArray(32) { 9 }, comment = "alice@mac")
        val named = r.secrets.importKey(pem, null).getOrThrow()
        assertEquals("alice@mac", named.comment)
        assertEquals(line, named.publicLine)
        val plain = r.secrets.importKey(keyFile(1), null).getOrThrow()
        assertNull(plain.comment)
        assertEquals(listOf(null, "alice@mac", null), r.secrets.keys().map { it.comment })
        // Imported again: the key kept, with its comment.
        assertEquals(named, r.secrets.importKey(pem, null).getOrThrow())
        // Another process start reads it again.
        assertEquals(r.secrets.keys(), SshSecrets(prefs, FakeSealer(), r.keys).keys())

        // A change the file does not take leaves no comment behind.
        prefs.flush()
        val before = prefs.all
        prefs.commitsFail = true
        val (other, _) = openSshPem("ssh-ed25519", ByteArray(32) { 10 }, comment = "bob@mac")
        assertTrue(r.secrets.importKey(other, null).exceptionOrNull() is SshKeysNotSaved)
        assertEquals(before, prefs.all)
        prefs.commitsFail = false

        // Removed, its comment goes with it.
        assertTrue(r.secrets.removeKey(named.id))
        assertFalse(prefsText(prefs).contains("alice@mac"))
        assertEquals(listOf(null, null), r.secrets.keys().map { it.comment })
    }

    @Test
    fun aKeysCommentIsReadOnlyWhenItIsInTheClearAndOneLineOfText() {
        fun pem(comment: String?, cipher: String = "none") = openSshPem("ssh-ed25519", ByteArray(32) { 5 }, comment, cipher).first
        assertEquals("alice@mac", openSshComment(pem("alice@mac")))
        assertEquals("사용자@노트북", openSshComment(pem("사용자@노트북")))
        assertEquals("work key", openSshComment(pem("  work key ")))
        assertEquals("x".repeat(MAX_KEY_COMMENT), openSshComment(pem("x".repeat(MAX_KEY_COMMENT))))
        // No comment, or only spaces.
        assertNull(openSshComment(pem("")))
        assertNull(openSshComment(pem("   ")))
        // One that would end a line a shell reads, or hide or turn round what the page shows.
        for (bad in listOf("a\nb", "a\tb", "a\rb", "x\u0000y", "a\u2028b", "key\u202Emp3.exe", "a\u200Bb", "x".repeat(MAX_KEY_COMMENT + 1),
            // Combining marks pile up over the letter before them; the fillers are letters that draw nothing.
            "e\u0301\u0301\u0301", "a\u20DDb", "a\u115Fb", "a\u1160b", "a\u3164b", "a\uFFA0b", "\u3164")) {
            assertNull(bad, openSshComment(pem(bad)))
        }
        // #292: a variation selector only says how the character before it is drawn (U+FE0F makes
        // ❤, ⚠ and 🖥 emoji), so it is let through right after a visible character.
        for (emoji in listOf("work \u2764\uFE0F", "\u26A0\uFE0F prod", "mac \uD83D\uDDA5\uFE0F", "note \u2764\uFE0E")) {
            assertEquals(emoji, openSshComment(pem(emoji)))
        }
        // Not where it has no character to belong to, and not twice. An emoji joined by U+200D (a
        // format character, as the ones that hide or turn text round) names no key either.
        for (bad in listOf("\uFE0Fwork", "work \uFE0F\u2764", "\u2764\uFE0F\uFE0F", "\u2764\uFE0E\uFE0F",
            // After a letter or a digit it draws nothing, and after any kind of space it has no character.
            "phone\uFE0Fs key", "a\uFE0E", "7\uFE0F", "\uD55C\uFE0F", "a\u00A0\uFE0Fb", "a\u2003\uFE0Fb", "a\u3000\uFE0Fb",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 home")) {
            assertNull(bad, openSshComment(pem(bad)))
        }
        // An encrypted key keeps its comment in the part only its passphrase opens.
        assertNull(openSshComment(pem("alice@mac", cipher = "aes256-ctr")))
        // A key file whose private part is cut short, a PEM key, no key.
        assertNull(openSshComment(testKey(1).first))
        assertNull(openSshComment("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n"))
        assertNull(openSshComment("-----BEGIN OPENSSH PRIVATE KEY-----\n%%%\n-----END OPENSSH PRIVATE KEY-----\n"))
        assertNull(openSshComment("not a key"))
    }

    // The comment is read where ssh-keygen writes it, for each key type it makes. Skipped
    // where ssh-keygen is not installed; the keys live in a temporary folder only.
    @Test
    fun theCommentIsReadFromKeysSshKeygenMade() {
        val keygen = listOf("/usr/bin/ssh-keygen", "/bin/ssh-keygen", "/usr/local/bin/ssh-keygen").firstOrNull { File(it).canExecute() }
        assumeTrue("ssh-keygen is not installed", keygen != null)
        val dir = Files.createTempDirectory("velo-keygen").toFile()
        try {
            fun make(name: String, vararg args: String): String {
                val file = File(dir, name)
                val run = ProcessBuilder(listOf(keygen!!, "-q", *args, "-f", file.path)).redirectErrorStream(true).start()
                val said = run.inputStream.readBytes().decodeToString()
                assertEquals(said, 0, run.waitFor())
                return file.readText()
            }
            assertEquals("rex ed25519", openSshComment(make("ed25519", "-t", "ed25519", "-N", "", "-C", "rex ed25519")))
            assertEquals("alice@ecdsa", openSshComment(make("ecdsa", "-t", "ecdsa", "-N", "", "-C", "alice@ecdsa")))
            assertEquals("alice@ecdsa384", openSshComment(make("ecdsa384", "-t", "ecdsa", "-b", "384", "-N", "", "-C", "alice@ecdsa384")))
            assertEquals("alice@rsa", openSshComment(make("rsa", "-t", "rsa", "-b", "2048", "-N", "", "-C", "alice@rsa")))
            assertNull(openSshComment(make("bare", "-t", "ed25519", "-N", "", "-C", "")))
            assertNull(openSshComment(make("locked", "-t", "ed25519", "-N", "open sesame", "-C", "rex locked")))
            assertNull(openSshComment(make("pem", "-t", "ecdsa", "-m", "PEM", "-N", "", "-C", "rex pem")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theKeystoreSealerCallsAValueItCannotReadMalformed() {
        // Each is refused before the Keystore is asked anything.
        val sealer = KeystoreSealer("velo-ssh-seal-test")
        for (bad in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(2, 12) + ByteArray(30), byteArrayOf(1, 40) + ByteArray(30),
            byteArrayOf(1, -12) + ByteArray(30), byteArrayOf(1, 12) + ByteArray(12),
            // Cut after the IV with less than the 16-byte GCM tag that seal() always writes.
            byteArrayOf(1, 12) + ByteArray(12) + ByteArray(1), byteArrayOf(1, 12) + ByteArray(12) + ByteArray(15),
            byteArrayOf(1, 16) + ByteArray(16) + ByteArray(15))) {
            assertThrows(bad.contentToString(), MalformedSealedValue::class.java) { sealer.open(bad) }
        }
    }

    @Test
    fun theOneKeyAnEarlierBuildKeptStaysThePhoneKey() {
        // An earlier build kept one key: made on the phone, or imported in its place.
        val pem = vectorKey()
        val prefs = FakePrefs()
        val sealer = FakeSealer()
        prefs.edit().putString("ssh:identity", sealedText(sealer, pem)).putString("ssh:public", ED25519_LINE).apply()
        val r = Recorded(prefs, sealer)

        val phone = r.secrets.keys().single()
        assertTrue(phone.phone)
        assertEquals(ED25519_LINE, phone.publicLine)
        assertEquals(ED25519_FINGERPRINT, phone.fingerprint)
        assertEquals(listOf(pem), r.secrets.identityPems())
        assertEquals(0, r.keys.generated)

        // Imported again (its key counts, not its comment): it is the phone key already.
        assertEquals(phone, r.secrets.importKey(pem, null).getOrThrow())
        assertEquals(listOf(phone), r.secrets.keys())
        assertTrue(r.given.isEmpty())

        // "Replace key…" replaces it, and it is not kept as an imported key.
        r.secrets.replaceKey()
        assertEquals(listOf(true), r.secrets.keys().map { it.phone })
        assertTrue(r.secrets.identityPem().contains("phone-key-1"))
        assertEquals(listOf(listOf(r.secrets.identityPem())), r.given)
    }

    @Test
    fun aFingerprintIsWhatSshKeygenPrints() {
        assertEquals(ED25519_FINGERPRINT, sshFingerprint(ED25519_LINE))
        assertEquals(ECDSA_FINGERPRINT, sshFingerprint(ECDSA_LINE))
        assertEquals(ECDSA_FINGERPRINT, sshFingerprint(ECDSA_LINE.substringBeforeLast(' ')))
        assertNull(sshFingerprint("ssh-ed25519"))
        assertNull(sshFingerprint("ssh-ed25519 %%%%"))
    }

    @Test
    fun aKeyTheKeystoreCannotOpenNowIsOfferedOnceItOpens() {
        val prefs = FakePrefs()
        val keys = FakeKeys()
        SshSecrets(prefs, FakeSealer(), keys).importKey(keyFile(1), null).getOrThrow()
        val before = prefsText(prefs)
        var busy = true
        val real = FakeSealer()
        val sealer = object : Sealer {
            override fun seal(plain: ByteArray) = real.seal(plain)
            override fun open(sealed: ByteArray): ByteArray = if (busy) throw KeyStoreException("busy") else real.open(sealed)
        }
        val secrets = SshSecrets(prefs, sealer, keys)

        // Nothing opens: nothing offered, nothing given up.
        assertEquals(emptyList<String>(), secrets.identityPems())
        assertTrue(secrets.keysMissing)
        assertEquals(before, prefsText(prefs))
        // No key is added while the phone key cannot be opened: a loss found later would take it too.
        assertTrue(runCatching { secrets.importKey(keyFile(2), null).getOrThrow() }.isFailure)
        assertEquals(before, prefsText(prefs))

        busy = false
        assertEquals(listOf(secrets.identityPem(), testKey(1).first), secrets.identityPems())
        assertFalse(secrets.keysMissing)
        assertEquals(1, keys.generated)
    }

    // #292: a host's name is one host whatever its case, and the pool now asks in lower case. A
    // key kept under another case of the name still counts. Two that differ are not a choice
    // between them: the answer is no key at all ([SshSecrets.HOST_KEYS_DIFFER]).
    @Test
    fun aHostKeyKeptUnderAnotherCaseOfTheNameStillCounts() {
        val prefs = FakePrefs()
        val secrets = SshSecrets(prefs, FakeSealer(), FakeKeys())
        val one = "ssh-ed25519 SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        val other = "ssh-ed25519 SHA256:otherkeyotherkeyotherkeyotherkeyotherkeyoth"
        prefs.edit().putString("ssh:hostkey:Studio.Example:2222", one).apply()
        assertEquals(one, secrets.hostKey("studio.example:2222"))
        assertEquals(one, secrets.hostKey("STUDIO.example:2222"))
        // Another port, and a name that only starts the same, are other hosts.
        assertNull(secrets.hostKey("studio.example:22"))
        assertNull(secrets.hostKey("studio.example:22222"))
        assertNull(secrets.hostKey("udio.example:2222"))

        // The same key under both spellings is one key.
        secrets.trustHostKey("studio.example:2222", one)
        assertEquals(one, secrets.hostKey("studio.example:2222"))
        // Two keys: neither is picked, whichever spelling asks.
        secrets.trustHostKey("studio.example:2222", other)
        for (asked in listOf("studio.example:2222", "Studio.Example:2222", "STUDIO.EXAMPLE:2222")) {
            assertEquals(asked, SshSecrets.HOST_KEYS_DIFFER, secrets.hostKey(asked))
        }
        assertNotEquals(one, SshSecrets.HOST_KEYS_DIFFER)
        // Nothing was dropped to get there.
        assertEquals(one, prefs.all["ssh:hostkey:Studio.Example:2222"])
        assertEquals(other, prefs.all["ssh:hostkey:studio.example:2222"])

        // Forget forgets every spelling, and only this host:port.
        secrets.trustHostKey("studio.example:22", one)
        secrets.forgetHostKey("studio.example:2222")
        assertNull(secrets.hostKey("studio.example:2222"))
        assertEquals(setOf("ssh:hostkey:studio.example:22"), prefs.all.keys.filter { it.startsWith("ssh:hostkey:") }.toSet())
    }
}
