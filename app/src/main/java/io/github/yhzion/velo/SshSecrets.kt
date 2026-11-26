package io.github.yhzion.velo

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.yhzion.velo.core.sshHostPort
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals secrets before they reach the prefs: the Keystore's AES-GCM key in the app ([KeystoreSealer]), a fake in the JVM tests. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    /**
     * Throws when [sealed] cannot be opened: [SealKeyLost], [AEADBadTagException] or
     * [MalformedSealedValue] when it never will be (its key is gone, another key sealed it, or
     * [seal] never made it), anything else when it cannot now.
     */
    fun open(sealed: ByteArray): ByteArray
}

/** The key that sealed a value is gone: the value can never be opened again. */
class SealKeyLost : GeneralSecurityException("the key that sealed this value is gone")

/** The value is none that [Sealer.seal] made (an unknown version, too short): it can never be opened. */
class MalformedSealedValue : GeneralSecurityException("not a sealed value")

/**
 * The Android Keystore AES-GCM key [alias], made on first use. Keystore cannot hold an Ed25519
 * key on API 30, so the SSH keys and the passwords are sealed with this key instead. A sealed
 * value is a version byte, the IV's length, the IV and the ciphertext with its tag.
 */
class KeystoreSealer(private val alias: String = ALIAS) : Sealer {
    private var key: SecretKey? = null

    /** The key, if the Keystore has it. */
    @Synchronized
    private fun existingKey(): SecretKey? {
        key?.let { return it }
        val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)?.also { key = it }
    }

    /** The key, made when the Keystore has none. */
    @Synchronized
    private fun key(): SecretKey {
        val made = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
        return made.also { key = it }
    }

    override fun seal(plain: ByteArray): ByteArray {
        // The Keystore picks the IV: a key that requires randomized encryption refuses one given to it.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= 2 || sealed[0] != VERSION) throw MalformedSealedValue()
        val ivSize = sealed[1].toInt()
        // seal() always writes the whole GCM tag: a shorter value was cut, and can never be opened.
        if (ivSize !in 12..16 || sealed.size < 2 + ivSize + TAG_BYTES) throw MalformedSealedValue()
        // Opening never makes a key: without the one that sealed it, the value is lost.
        val key = existingKey() ?: throw SealKeyLost()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 2, ivSize)) }
        return cipher.doFinal(sealed, 2 + ivSize, sealed.size - 2 - ivSize)
    }

    companion object {
        /** The Keystore alias of the key that seals the SSH secrets (Global Constraints). */
        const val ALIAS = "velo-ssh-seal"
        private const val KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        /** The GCM tag's length, which ends every sealed value. */
        private const val TAG_BYTES = 16
    }
}

/** [SshSecrets]' key functions: [NativeSshKeys] in the app, a fake in the JVM tests. */
interface SshKeyTool {
    /** A new Ed25519 key, (unencrypted OpenSSH PEM, authorized_keys line ending in [comment]); null when none could be made. */
    fun generate(comment: String): Pair<String, String>?
    /** [SshNative.decryptKey]: 0 and pemOut[0] the key unencrypted; EACCES: passphrase missing or wrong; EINVAL: not a usable key. */
    fun decrypt(pem: String, passphrase: String?, pemOut: Array<String?>): Int
}

/**
 * A change of the keys ([SshSecrets.importKey], [SshSecrets.removeKey], [SshSecrets.replaceKey])
 * that the prefs file did not take (its commit failed): it was undone, so nothing changed.
 * [message] is the sentence for the user ([SshSecrets.KEYS_NOT_SAVED]).
 */
class SshKeysNotSaved : Exception(SshSecrets.KEYS_NOT_SAVED)

/** Why a key was not imported; [message] is the sentence for the user. */
class SshKeyProblem(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NeedsPassphrase, WrongPassphrase, NotAKey, Failed }
}

/**
 * A key that logins offer, as the key page shows it: its [publicLine] (an authorized_keys line)
 * and [fingerprint] (`SHA256:…`, as `ssh-keygen -lf` prints it). [id] stays the same across
 * restarts; [phone] marks the phone key, which [SshSecrets.removeKey] never removes. An
 * imported key's [comment] is the one its file carried ([openSshComment]); null when it had none
 * that could be read.
 */
data class SshKey(val id: String, val phone: Boolean, val publicLine: String, val fingerprint: String, val comment: String? = null)

/**
 * The SSH secrets, which live only here (Global Constraints): the phone key, the imported keys
 * and the saved passwords sealed by [seal] in [prefs], and the host keys the user trusted, per
 * `host:port` as `TYPE SHA256:…` (public, so not sealed). The public lines of the keys are kept
 * in plain prefs as well ([PUBLIC], ruling P4): they are public, the e2e mocks read the phone
 * key's to authorize the phone, and the key page lists them without opening a key. Nothing here
 * logs. Opening a sealed value is Keystore work: the app does it off the main thread at its
 * start, on a question, when a password is saved or when the keys change; never per frame.
 *
 * Logins offer the keys in order ([identityPems]): the phone key first, then the imported keys
 * in the order they were imported. An import adds a key beside the phone key; only
 * [replaceKey] replaces the phone key. The one key an earlier build kept is the phone key, even
 * if it was imported in the phone key's place.
 *
 * [prefs] is the app's ssh prefs file ([PREFS]), which stays on this device: neither a cloud
 * backup nor a device-to-device transfer takes it (`res/xml/data_extraction_rules.xml`), since
 * no other device can open what this one sealed. A sealed value is given up only when it is
 * lost for good ([SealKeyLost], [AEADBadTagException], [MalformedSealedValue], or no Base64);
 * one the Keystore cannot open now stays. A phone key lost for good is replaced, and every other
 * sealed value is then opened on its own: one that opens stays, one lost for good goes, and one
 * the Keystore cannot open now stays as it is. Nothing stays that can never be opened, and
 * nothing goes that still opens.
 *
 * A change of the keys ([importKey], [removeKey], [replaceKey]) is written before it returns. One
 * the file does not take (its commit fails) is undone and reported as [SshKeysNotSaved]: the key
 * page, the pool and the file after a crash all see the keys as they were. [keysChanged] runs
 * after the keys changed, under this object's lock, so the pool can read [identityPems] in the
 * order of the changes.
 *
 * The saved passwords have a lock of their own ([passwordLock]), which never waits on the
 * Keystore: the main thread saves and forgets them, and an import may hold this object's lock
 * for seconds. A forget wins over a save of the same login under way ([savePassword]).
 *
 * A saved password belongs to the host that accepted it (#29): the host key trusted then is kept
 * beside it ([passwordHostKey]; public, so not sealed, and the sealed value stays the password
 * alone), and [SshQuestions] answers with the password only while that key is the one trusted
 * for the login's `host:port`. Forgetting a host's key forgets the passwords of every login to
 * it ([forgetHostKey]).
 */
class SshSecrets(
    private val prefs: SharedPreferences,
    private val seal: Sealer,
    private val tool: SshKeyTool = NativeSshKeys,
    private val keysChanged: () -> Unit = {},
) {
    /** The phone key opened once in this process: [PUBLIC] is its line. */
    private var identityOpened = false

    /** Guards the saved passwords' writes and [forgets]; held over no Keystore work. */
    private val passwordLock = Any()

    /**
     * How often each login's password was forgotten in this process ([forgetPassword]), under
     * [passwordLock]: a save that began before a forget of its login is dropped.
     */
    private val forgets = HashMap<String, Int>()

    /**
     * The last [identityPems] left out a key that the Keystore could not open then: the pool
     * lacks that key until [identityPems] runs again and opens it.
     */
    @Volatile
    var keysMissing = false
        private set

    /**
     * The phone key's public half, an authorized_keys line; the key is made on first use. The
     * first call of a process opens the key ([identityPem]), so a line whose key is lost (and
     * about to be replaced) is never handed out. Throws when the Keystore cannot open it now.
     */
    @Synchronized
    fun publicLine(): String {
        if (!identityOpened || !prefs.contains(IDENTITY)) identityPem()
        return checkNotNull(prefs.getString(PUBLIC, null))
    }

    /**
     * The keys logins offer, in their order: the phone key, then the imported keys in the order
     * they were imported. Only the phone key is opened, once a process ([publicLine]); the public
     * lines are plain prefs. Throws when the Keystore cannot open the phone key now.
     */
    @Synchronized
    fun keys(): List<SshKey> {
        val phone = sshKey(PHONE_ID, true, publicLine())
        return listOf(phone) + importedIds().mapNotNull { id -> prefs.getString(IMPORTED_PUBLIC + id, null)?.let { importedKey(id, it) } }
    }

    /**
     * The phone key, unencrypted OpenSSH PEM; made on first use. A key lost for good (its Keystore
     * key is gone, or its value is malformed) is replaced by a new one, and every other sealed
     * value is opened on its own ([dropLost]); one the Keystore cannot open now throws, and stays.
     */
    @Synchronized
    fun identityPem(): String {
        val sealed = prefs.getString(IDENTITY, null) ?: return makeKey()
        return when (val opened = unseal(sealed)) {
            is Unsealed.Value -> opened.text.also { identityOpened = true }
            Unsealed.Lost -> {
                dropLost()
                makeKey()
            }
            Unsealed.NotNow -> throw IllegalStateException("The phone key cannot be opened now.")
        }
    }

    /**
     * The keys for the pool ([SshNative.setIdentities]), unencrypted, in the order of [keys]. A
     * key the Keystore cannot open now is left out ([keysMissing]) and stays; an imported key
     * lost for good is forgotten.
     */
    @Synchronized
    fun identityPems(): List<String> {
        var missing = false
        val pems = ArrayList<String>()
        try {
            pems += identityPem()
        } catch (_: Exception) {
            // The Keystore cannot open it now, or no key could be made: the imported keys still go.
            missing = true
        }
        val lost = ArrayList<String>()
        for (id in importedIds()) {
            when (val opened = prefs.getString(IMPORTED + id, null)?.let(::unseal) ?: Unsealed.Lost) {
                is Unsealed.Value -> pems += opened.text
                Unsealed.Lost -> lost += id
                Unsealed.NotNow -> missing = true
            }
        }
        if (lost.isNotEmpty()) forgetImported(lost)
        keysMissing = missing
        return pems
    }

    /**
     * Add the private key [pem] (OpenSSH or PEM; Ed25519, ECDSA or RSA) after the keys kept,
     * opened once with [passphrase] and kept sealed, unencrypted. A key kept already (the same
     * public key, the phone key included) is not added again: that key is the result.
     * [SshKeyProblem] when the key cannot be used, [SshKeysNotSaved] when the file did not take
     * it (nothing is added); throws when the Keystore cannot open the phone key now (nothing is
     * added then) or cannot seal.
     *
     * Call it off the main thread: opening an OpenSSH key runs its bcrypt KDF in the native
     * library, which can take seconds, and it holds this object's lock all the while (the pool's
     * setup and [publicLine] wait for it).
     */
    @Synchronized
    fun importKey(pem: String, passphrase: String?): Result<SshKey> {
        val out = arrayOfNulls<String>(1)
        val given = passphrase?.takeIf { it.isNotEmpty() }
        val problem = when (val rc = tool.decrypt(pem, given, out)) {
            0 -> null
            EACCES -> if (given == null) SshKeyProblem(SshKeyProblem.Kind.NeedsPassphrase, NEEDS_PASSPHRASE)
                else SshKeyProblem(SshKeyProblem.Kind.WrongPassphrase, "Incorrect passphrase. Try again to import the key.")
            EINVAL -> notAKey()
            else -> SshKeyProblem(SshKeyProblem.Kind.Failed, "Velo couldn't open this key (error $rc).")
        }
        if (problem != null) return Result.failure(problem)
        val opened = out[0] ?: return Result.failure(notAKey())
        val line = openSshPublicLine(opened) ?: return Result.failure(notAKey())
        val id = sshFingerprint(line) ?: return Result.failure(notAKey())
        // The phone key opens first, once a process: one lost for good is replaced before this
        // key is compared with the keys kept ([kept]) and joins them.
        if (!identityOpened) identityPem()
        kept(line)?.let { return Result.success(it) }
        val plain = opened.toByteArray(Charsets.UTF_8)
        val sealed = try { seal.seal(plain) } finally { plain.fill(0) }
        // From the file as given: the opened key is written again without one ([SshKeyTool.decrypt]).
        val comment = openSshComment(pem)
        // An id whose public line went missing (a write cut short) keeps its place, once.
        val ids = importedIds().let { if (id in it) it else it + id }
        // Written before it returns (this runs off the main thread): no crash loses the import.
        val written = commitOrUndo(listOf(IMPORTED + id, IMPORTED_PUBLIC + id, IMPORTED_COMMENT + id, IMPORTED_IDS)) {
            putString(IMPORTED + id, Base64.getEncoder().encodeToString(sealed))
            putString(IMPORTED_PUBLIC + id, line)
            if (comment != null) putString(IMPORTED_COMMENT + id, comment) else remove(IMPORTED_COMMENT + id)
            putString(IMPORTED_IDS, ids.joinToString(ID_SEPARATOR))
        }
        if (!written) return Result.failure(SshKeysNotSaved())
        keysChanged()
        return Result.success(importedKey(id, line))
    }

    /**
     * Forget the imported key [id]; false (nothing changes) for the phone key or an id not kept.
     * A login under way keeps the keys it started with; the next one no longer offers it. Throws
     * [SshKeysNotSaved] when the file did not take the change (the key stays). Call it off the
     * main thread: [keysChanged] has the pool read the keys again, which opens them.
     */
    @Synchronized
    fun removeKey(id: String): Boolean {
        if (id !in importedIds()) return false
        if (!forgetImported(listOf(id))) throw SshKeysNotSaved()
        keysChanged()
        return true
    }

    /**
     * A new phone key in place of the one in use; hosts that know only the old one ask for a
     * password again. The imported keys stay. Written before it returns: [SshKeysNotSaved] when
     * the file did not take it (the old key stays). Call it off the main thread (Keystore work).
     */
    @Synchronized
    fun replaceKey() {
        val key = newKey()
        val written = commitOrUndo(listOf(IDENTITY, PUBLIC)) {
            putString(IDENTITY, key.sealed)
            putString(PUBLIC, key.line)
        }
        if (!written) throw SshKeysNotSaved()
        identityOpened = true
        keysChanged()
    }

    /**
     * The key the user trusted for [hostPort], `TYPE SHA256:…`; null when none. A name is one
     * host whatever its case (the pool asks in lower case, #292), so a key kept under another
     * case of [hostPort] counts too. Keys kept under two such spellings that are not the same
     * key are no choice between them: the answer is then [HOST_KEYS_DIFFER], which is no key,
     * so whatever the host shows is "another key" ([autoAnswer]) until the user forgets them.
     */
    fun hostKey(hostPort: String): String? {
        val kept = hostKeyNames(hostPort).mapNotNullTo(HashSet()) { prefs.getString(it, null) }
        return if (kept.size > 1) HOST_KEYS_DIFFER else kept.singleOrNull()
    }

    /**
     * Trust the key [typeAndFingerprint] for [hostPort]. A password kept without a host key for
     * a login to [hostPort] while no key is trusted for it goes first (#29): a version before
     * that fix left it behind when the host's key was forgotten, so the host that accepted it
     * is not known to be the one trusted now, and the next password question is the user's.
     */
    fun trustHostKey(hostPort: String, typeAndFingerprint: String) {
        if (hostKey(hostPort) == null) {
            for (login in passwordLogins(hostPort)) if (passwordHostKey(login) == null) forgetPassword(login)
        }
        prefs.edit().putString(HOST_KEY + hostPort, typeAndFingerprint).apply()
    }

    /**
     * Forget the key trusted for [hostPort], under every case of its name ([hostKey]), and with
     * it the passwords saved for every login to [hostPort], whatever its user and the case of its
     * host's name (#29): the host that accepted them is no longer one the phone knows, so the
     * host trusted next is sent nothing the user did not type for it. The passwords go first:
     * a stop in between leaves the key without them, never them without the key.
     */
    fun forgetHostKey(hostPort: String) {
        for (login in passwordLogins(hostPort)) forgetPassword(login)
        val edit = prefs.edit()
        for (name in hostKeyNames(hostPort)) edit.remove(name)
        edit.apply()
    }

    /** The logins a password is saved for whose host key is kept under [hostPort] ([sshHostPort]), in whatever case its name was written. */
    private fun passwordLogins(hostPort: String): List<String> =
        prefs.all.keys.filter { it.startsWith(PASSWORD) }.map { it.removePrefix(PASSWORD) }
            .filter { sshHostPort(it).equals(hostPort, ignoreCase = true) }

    /** The prefs names that keep a host key for [hostPort], in whatever case its name was written. */
    private fun hostKeyNames(hostPort: String): List<String> =
        prefs.all.keys.filter { it.startsWith(HOST_KEY) && it.substring(HOST_KEY.length).equals(hostPort, ignoreCase = true) }

    /**
     * The password saved for the login [target] (`ssh://user@host[:port]`); null when none, or
     * when it cannot be opened: one lost for good is forgotten, one the Keystore cannot open now stays.
     */
    fun password(target: String): String? {
        val sealed = prefs.getString(PASSWORD + target, null) ?: return null
        return when (val opened = unseal(sealed)) {
            is Unsealed.Value -> opened.text
            Unsealed.Lost -> null.also { forgetLost(target, sealed) }
            Unsealed.NotNow -> null
        }
    }

    /**
     * The host key (`TYPE SHA256:…`) of the host that accepted the password saved for [target];
     * null when no password is saved, or when a version before #29 saved it, which kept no key
     * ([bindPassword]). Plain prefs: nothing is opened.
     */
    fun passwordHostKey(target: String): String? = prefs.getString(PASSWORD_HOST_KEY + target, null)

    /** The password saved for [target] with its host key ([password], [passwordHostKey]); null when [password] is. */
    fun savedPassword(target: String): SavedPassword? = password(target)?.let { SavedPassword(it, passwordHostKey(target)) }

    /**
     * Keep [hostKey] as the key of the host that accepted the password saved for [target], which
     * a version before #29 saved without one. False, and nothing changes, when no password is
     * saved or it has a key already.
     */
    fun bindPassword(target: String, hostKey: String): Boolean = synchronized(passwordLock) {
        if (!prefs.contains(PASSWORD + target) || prefs.contains(PASSWORD_HOST_KEY + target)) return false
        prefs.edit().putString(PASSWORD_HOST_KEY + target, hostKey).apply()
        true
    }

    /**
     * Save [password] for the login [target], sealed, with [hostKey]: the key (`TYPE SHA256:…`)
     * of the host that accepted it (#29). Without one (null) it is kept as a version before #29
     * kept it, and answers nothing until it is bound ([bindPassword]). False when it was not saved: the user
     * forgot [target]'s password while it was sealed ([forgetPassword]), and the forget wins
     * (the next login asks again, as after a lost key). Throws when the Keystore cannot seal.
     * The sealing holds none of this object's locks, so a forget never waits for the Keystore;
     * the main thread waits on [passwordLock] only for a prefs write in memory.
     */
    fun savePassword(target: String, password: String, hostKey: String? = null): Boolean {
        val began = synchronized(passwordLock) { forgets[target] ?: 0 }
        val plain = password.toByteArray(Charsets.UTF_8)
        val sealed = try { seal.seal(plain) } finally { plain.fill(0) }
        synchronized(passwordLock) {
            if ((forgets[target] ?: 0) != began) return false
            val edit = prefs.edit().putString(PASSWORD + target, Base64.getEncoder().encodeToString(sealed))
            if (hostKey != null) edit.putString(PASSWORD_HOST_KEY + target, hostKey) else edit.remove(PASSWORD_HOST_KEY + target)
            edit.apply()
        }
        return true
    }

    /** Whether a password is saved for [target]; nothing is opened (no Keystore work). */
    fun hasPassword(target: String): Boolean = prefs.contains(PASSWORD + target)

    /** Forget the password saved for [target] and its host key, and drop a save of it under way ([savePassword]). */
    fun forgetPassword(target: String) {
        synchronized(passwordLock) {
            forgets[target] = (forgets[target] ?: 0) + 1
            prefs.edit().remove(PASSWORD + target).remove(PASSWORD_HOST_KEY + target).apply()
        }
    }

    /**
     * Forget [target]'s password [stored], lost for good, unless a save replaced it meanwhile.
     * It is not the user's forget, so a save under way goes on.
     */
    private fun forgetLost(target: String, stored: String) {
        synchronized(passwordLock) {
            if (prefs.getString(PASSWORD + target, null) == stored) prefs.edit().remove(PASSWORD + target).remove(PASSWORD_HOST_KEY + target).apply()
        }
    }

    /** A new phone key: its PEM, its sealed value (Base64, for [IDENTITY]) and its public line. */
    private class NewKey(val pem: String, val sealed: String, val line: String)

    private fun newKey(): NewKey {
        val (pem, line) = tool.generate(COMMENT) ?: throw IllegalStateException("The phone could not make an SSH key.")
        val plain = pem.toByteArray(Charsets.UTF_8)
        val sealed = try { seal.seal(plain) } finally { plain.fill(0) }
        return NewKey(pem, Base64.getEncoder().encodeToString(sealed), line)
    }

    /** The phone key, made on first use or in the place of one lost for good. */
    private fun makeKey(): String {
        val key = newKey()
        prefs.edit().putString(IDENTITY, key.sealed).putString(PUBLIC, key.line).apply()
        identityOpened = true
        return key.pem
    }

    /**
     * Make [change] to the values [keys] and write it before returning; true when the file took
     * it. When it did not (the commit failed, and Android keeps the change in memory all the
     * same), [keys] are set back as they were, and false.
     */
    private fun commitOrUndo(keys: Collection<String>, change: SharedPreferences.Editor.() -> Unit): Boolean {
        val before = keys.associateWith { prefs.getString(it, null) }
        val edit = prefs.edit()
        edit.change()
        if (edit.commit()) return true
        val undo = prefs.edit()
        for ((key, value) in before) if (value == null) undo.remove(key) else undo.putString(key, value)
        // The file never got the change: whether or not this write lands, it holds the old values.
        undo.commit()
        return false
    }

    /** The imported keys' ids, in the order imported. */
    private fun importedIds(): List<String> =
        prefs.getString(IMPORTED_IDS, null)?.split(ID_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    private fun sshKey(id: String, phone: Boolean, line: String) = SshKey(id, phone, line, sshFingerprint(line).orEmpty())

    /** The imported key [id] whose public line is [line], with the comment kept for it. */
    private fun importedKey(id: String, line: String) = SshKey(id, false, line, sshFingerprint(line).orEmpty(), prefs.getString(IMPORTED_COMMENT + id, null))

    /** The key kept whose public key is [line]'s (its type and Base64; a comment does not count); null when none. */
    private fun kept(line: String): SshKey? {
        val key = publicKeyOf(line)
        prefs.getString(PUBLIC, null)?.takeIf { publicKeyOf(it) == key }?.let { return sshKey(PHONE_ID, true, it) }
        for (id in importedIds()) {
            val other = prefs.getString(IMPORTED_PUBLIC + id, null) ?: continue
            if (publicKeyOf(other) == key) return importedKey(id, other)
        }
        return null
    }

    /**
     * Forget the imported keys [ids]: their sealed key, their public line, their comment and their
     * place in the order. Written before it returns (its callers run off the main thread): a removed key,
     * maybe one that leaked, does not come back after a crash. False, and nothing forgotten,
     * when the file did not take it ([commitOrUndo]).
     */
    private fun forgetImported(ids: Collection<String>): Boolean {
        val rest = importedIds() - ids.toSet()
        return commitOrUndo(ids.flatMap { listOf(IMPORTED + it, IMPORTED_PUBLIC + it, IMPORTED_COMMENT + it) } + IMPORTED_IDS) {
            for (id in ids) remove(IMPORTED + id).remove(IMPORTED_PUBLIC + id).remove(IMPORTED_COMMENT + id)
            if (rest.isEmpty()) remove(IMPORTED_IDS) else putString(IMPORTED_IDS, rest.joinToString(ID_SEPARATOR))
        }
    }

    /**
     * The phone key is lost for good: every other sealed value is opened on its own (Review Focus
     * 4 of #284). The imported keys and the saved passwords that open stay, those lost for good
     * go, and those the Keystore cannot open now stay as they are. A Keystore key that is gone
     * opens none of them, so then they all go. The trusted host keys are public, and stay.
     */
    private fun dropLost() {
        val lost = importedIds().filter { id -> (prefs.getString(IMPORTED + id, null)?.let(::unseal) ?: Unsealed.Lost) == Unsealed.Lost }
        if (lost.isNotEmpty()) forgetImported(lost)
        for ((key, value) in prefs.all) {
            if (key.startsWith(PASSWORD) && value is String && unseal(value) == Unsealed.Lost) forgetLost(key.removePrefix(PASSWORD), value)
        }
    }

    /** What opening a sealed value gave: its text, nothing ever ([Lost]), or nothing for now ([NotNow]). */
    private sealed interface Unsealed {
        class Value(val text: String) : Unsealed
        data object Lost : Unsealed
        data object NotNow : Unsealed
    }

    private fun unseal(stored: String): Unsealed {
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            // Nothing this class wrote: it will never open.
            return Unsealed.Lost
        }
        return try {
            val plain = seal.open(bytes)
            try { Unsealed.Value(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } catch (_: SealKeyLost) {
            Unsealed.Lost
        } catch (_: AEADBadTagException) {
            Unsealed.Lost
        } catch (_: MalformedSealedValue) {
            Unsealed.Lost
        } catch (_: Exception) {
            Unsealed.NotNow
        }
    }

    private fun notAKey() = SshKeyProblem(SshKeyProblem.Kind.NotAKey, NOT_A_KEY)

    companion object {
        /** The prefs file of the SSH values, kept off backups and transfers. */
        const val PREFS = "velo-ssh"
        /** The phone key's public line, in plain prefs (ruling P4). */
        const val PUBLIC = "ssh:public"
        private const val IDENTITY = "ssh:identity"
        /** The imported keys' ids in the order imported, joined by [ID_SEPARATOR]; each id is its key's fingerprint. */
        private const val IMPORTED_IDS = "ssh:imported"
        /** An imported key, sealed, by its id. */
        private const val IMPORTED = "ssh:imported:"
        /** An imported key's public line, by its id (plain: it is public). */
        private const val IMPORTED_PUBLIC = "ssh:imported-public:"
        /**
         * An imported key's comment, by its id; none when its file carried none ([openSshComment]).
         * Plain: OpenSSH writes it in the clear into the key's `.pub` file, and the key page shows it.
         */
        private const val IMPORTED_COMMENT = "ssh:imported-comment:"
        /** No fingerprint holds it (`SHA256:` and Base64). */
        private const val ID_SEPARATOR = ","
        /** The phone key's [SshKey.id]: no imported key's id (a fingerprint) can be it. */
        const val PHONE_ID = "phone"
        private const val PASSWORD = "ssh:password:"
        /**
         * The host key (`TYPE SHA256:…`) of the host that accepted a login's saved password, by
         * the login (#29). Plain, as the trusted host keys are: it is public, and the sealed value
         * stays what versions before #29 wrote. No login's [PASSWORD] name starts like it.
         */
        private const val PASSWORD_HOST_KEY = "ssh:password-hostkey:"
        private const val HOST_KEY = "ssh:hostkey:"
        /**
         * [hostKey]'s answer while two different keys are kept for one `host:port` in different
         * case. No key reads like it (a key is `TYPE SHA256:…`), so it equals none a host shows.
         */
        const val HOST_KEYS_DIFFER = "differ"
        /** The comment at the end of the phone key's public line. */
        const val COMMENT = "velo"
        /** What [importKey] says of a key that needs its passphrase ([SshKeyProblem.Kind.NeedsPassphrase]). */
        const val NEEDS_PASSPHRASE = "Enter the passphrase to import this key."
        /** What the key page says when a change of the keys did not reach the file ([SshKeysNotSaved]). */
        const val KEYS_NOT_SAVED = "Velo couldn't save the key change."
        /** What [importKey] says of a file that is no key it can use ([SshKeyProblem.Kind.NotAKey]). */
        const val NOT_A_KEY = "Velo can't use this file as a key. Import an Ed25519, ECDSA or RSA private key."
        private const val EACCES = 13
        private const val EINVAL = 22
    }
}

/** The public key of the authorized_keys [line]: its type and Base64, without a comment. */
private fun publicKeyOf(line: String): String = line.trim().split(' ', '\t').filter { it.isNotEmpty() }.take(2).joinToString(" ")

/**
 * The fingerprint of the authorized_keys [line] as `ssh-keygen -lf` prints it: `SHA256:` and
 * the unpadded Base64 of the SHA-256 of the key blob. Null when the line holds no blob.
 */
internal fun sshFingerprint(line: String): String? {
    val field = line.trim().split(' ', '\t').filter { it.isNotEmpty() }.getOrNull(1) ?: return null
    val blob = try {
        Base64.getDecoder().decode(field)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (blob.isEmpty()) return null
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
}

/**
 * Moves the `ssh:` values an earlier build kept in the app's prefs [from] into the ssh prefs
 * [to], once: they leave [from]. A value already in [to] is kept. Written before removed, so a
 * stop in between loses nothing; when [to]'s file does not take them (its commit fails), [from]
 * keeps them, and the next start moves them again.
 */
internal fun moveSshPrefs(from: SharedPreferences, to: SharedPreferences) {
    val old = from.all.filterKeys { it.startsWith("ssh:") }
    if (old.isEmpty()) return
    val add = to.edit()
    for ((key, value) in old) if (!to.contains(key) && value is String) add.putString(key, value)
    if (!add.commit()) return
    val remove = from.edit()
    for (key in old.keys) remove.remove(key)
    remove.commit()
}

/**
 * The authorized_keys line (`TYPE BASE64`) of an unencrypted OpenSSH private key, read from its
 * header, which holds the public half in the clear; null when [pem] is not one.
 */
internal fun openSshPublicLine(pem: String): String? {
    val begin = "-----BEGIN OPENSSH PRIVATE KEY-----"
    val end = "-----END OPENSSH PRIVATE KEY-----"
    val start = pem.indexOf(begin)
    val stop = pem.indexOf(end)
    if (start < 0 || stop < start) return null
    val bytes = try {
        Base64.getDecoder().decode(pem.substring(start + begin.length, stop).filterNot { it.isWhitespace() })
    } catch (_: IllegalArgumentException) {
        return null
    }
    val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) return null
    val header = ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)
    repeat(3) { header.string() ?: return null } // cipher, KDF, KDF options
    if (header.remaining() < 4 || header.int < 1) return null
    val blob = header.string() ?: return null
    val type = ByteBuffer.wrap(blob).string()?.takeIf { it.isNotEmpty() } ?: return null
    return String(type, Charsets.US_ASCII) + " " + Base64.getEncoder().encodeToString(blob)
}

/** The longest comment a key keeps ([openSshComment]), in characters: its name on the key page. */
internal const val MAX_KEY_COMMENT = 100

/**
 * The comment the OpenSSH private key [pem] carries (`ssh-keygen -C`), where it is in the clear:
 * an unencrypted key, as `ssh-keygen -N ""` writes it. Its fields are read as the pool's
 * `keyComment` reads them (Ed25519, ECDSA, RSA), past the private halves without copying them.
 * Null when there is none, the key is encrypted (the comment is in the part only the
 * passphrase opens, and the opened key is written again without it), the file is a PEM key
 * (which has no comment) or no key, and when the comment is not one line of visible text of
 * at most [MAX_KEY_COMMENT] characters: a control character could end a line a shell reads,
 * and a format character, a combining mark or an invisible filler could hide or turn round what
 * the key page shows. A variation selector right after a symbol or a punctuation mark is let
 * through (an emoji written with U+FE0F). An emoji joined by U+200D is not: that is a format character like
 * the ones that hide text, so such a key is named by its fingerprint. Spaces at its ends are dropped.
 */
internal fun openSshComment(pem: String): String? {
    val begin = "-----BEGIN OPENSSH PRIVATE KEY-----"
    val end = "-----END OPENSSH PRIVATE KEY-----"
    val start = pem.indexOf(begin)
    val stop = pem.indexOf(end)
    if (start < 0 || stop < start) return null
    val bytes = try {
        Base64.getDecoder().decode(pem.substring(start + begin.length, stop).filterNot { it.isWhitespace() })
    } catch (_: IllegalArgumentException) {
        return null
    }
    try {
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) return null
        val file = ByteBuffer.wrap(bytes, magic.size, bytes.size - magic.size)
        if (file.string()?.contentEquals("none".toByteArray(Charsets.US_ASCII)) != true) return null // the cipher
        if (!file.skipString() || !file.skipString()) return null // KDF, KDF options
        if (file.remaining() < 4 || file.int != 1 || !file.skipString()) return null // one key, its public half
        if (file.remaining() < 4) return null
        val size = file.int
        if (size < 0 || size > file.remaining()) return null
        val keyPart = ByteBuffer.wrap(bytes, file.position(), size)
        if (keyPart.remaining() < 8) return null
        keyPart.position(keyPart.position() + 8) // the two check numbers
        val type = keyPart.string()?.toString(Charsets.US_ASCII) ?: return null
        // The fields of each key type before its comment, as OpenSSH writes them.
        val fields = when {
            type == "ssh-ed25519" -> 2 // public, private
            type.startsWith("ecdsa-sha2-") -> 3 // curve, public point, private scalar
            type == "ssh-rsa" -> 6 // n, e, d, iqmp, p, q
            else -> return null
        }
        repeat(fields) { if (!keyPart.skipString()) return null }
        val raw = keyPart.string() ?: return null
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return null
        }
        return keyComment(text)
    } finally {
        bytes.fill(0)
    }
}

/** Letters that draw nothing (Hangul fillers): a comment with one is no visible text. */
private val FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

/** U+FE0E and U+FE0F: the text and the emoji form of the character before them. Marks by their type, but they stack nothing. */
internal val VARIATION_SELECTORS = setOf(0xFE0E, 0xFE0F)

/** Whether a variation selector may follow [cp]: a symbol or a punctuation mark, as the characters with an emoji form are. */
private fun takesVariation(cp: Int): Boolean =
    !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && cp !in VARIATION_SELECTORS

/** [text] as a key's comment: trimmed, one line of visible text of at most [MAX_KEY_COMMENT] characters; else null. */
private fun keyComment(text: String): String? {
    val comment = text.trim()
    if (comment.isEmpty() || comment.codePointCount(0, comment.length) > MAX_KEY_COMMENT) return null
    val hidden = setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(),
        // Marks stack over the letter before them; they can pile up past the line.
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
    )
    var before = ' '.code
    var at = 0
    while (at < comment.length) {
        val cp = comment.codePointAt(at)
        // A variation selector only says how the character before it is drawn (❤️ is U+2764
        // U+FE0F): it is let through right after a symbol or a punctuation mark, once (#292).
        // After a letter or a digit it draws nothing, and after a space it has no character.
        val visible = if (cp in VARIATION_SELECTORS) takesVariation(before) else Character.getType(cp) !in hidden && cp !in FILLERS
        if (!visible) return null
        before = cp
        at += Character.charCount(cp)
    }
    return comment
}

/** An SSH wire string: a 32-bit length and that many bytes; null when the buffer is too short. */
private fun ByteBuffer.string(): ByteArray? {
    if (remaining() < 4) return null
    val size = int
    if (size < 0 || size > remaining()) return null
    return ByteArray(size).also { get(it) }
}

/** Steps past an SSH wire string without copying it (a private half); false when the buffer is too short. */
private fun ByteBuffer.skipString(): Boolean {
    if (remaining() < 4) return false
    val size = int
    if (size < 0 || size > remaining()) return false
    position(position() + size)
    return true
}
