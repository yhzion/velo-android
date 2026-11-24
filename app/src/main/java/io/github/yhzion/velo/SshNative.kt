package io.github.yhzion.velo

/**
 * JNI bridge to the app's SSH pool (package rexssh in libtailscale.so, reached through
 * libvelocore.so, `native/src/jni.zig`). One pool per process. The pool keeps no secret of its
 * own: it asks every question through [questions] and [answer], and holds the keys only in
 * memory ([setIdentities]). An Int result is 0 (success), an errno value, -1 (a library
 * without the pool) or, from [setIdentities] only, [SSH_KEY_LEFT_OUT] + N (1000+N: the key at
 * place N was left out, the others set).
 */
object SshNative {
    init {
        System.loadLibrary("velocore")
    }

    /** Make the pool, which remembers server sockets in [stateDir]; 0, or EEXIST for another directory. */
    @JvmStatic external fun init(stateDir: String): Int
    /** The questions waiting for an answer, a JSON array oldest first; null before [init]. */
    @JvmStatic external fun questions(): String?
    /** Answer question [id] ([ok] false declines it, with ""); false when it no longer waits. */
    @JvmStatic external fun answer(id: Long, answer: String, ok: Boolean): Boolean
    /**
     * The keys logins offer, in order: the phone key first, then imported keys as imported; a
     * login offers the first five. Each is unencrypted OpenSSH or PEM; an empty array clears them.
     * [SSH_KEY_LEFT_OUT] + N: a key that does not parse was left out, N the place of the first
     * (from 1), and the others are set ([identitiesLog]). EINVAL: a null element (the keys stay
     * as they were); ENOMEM: no memory for a key's copy; EPERM: no [init].
     */
    @JvmStatic external fun setIdentities(pems: Array<String>): Int
    /** A new Ed25519 key: [pem (unencrypted OpenSSH), public line ending in [comment]], or null. */
    @JvmStatic external fun generateKey(comment: String): Array<String>?
    /** Open an imported key: 0 and pemOut[0] unencrypted; EACCES: passphrase missing or wrong; EINVAL: not a usable key. */
    @JvmStatic external fun decryptKey(pem: String, passphrase: String?, pemOut: Array<String?>): Int
    /** Append [publicLine] to ~/.ssh/authorized_keys on [locator]'s host over the pool's connection. */
    @JvmStatic external fun installKey(locator: String, publicLine: String, timeoutMs: Int): Int
    /**
     * What the pool remembers for [locator], as JSON: `{"socket": its server socket or "",
     * "refused_until": until when the host's refusal of the forward holds, in unix seconds, 0 for
     * none}`; null before [init], for a malformed locator, without the pool (no native library)
     * and when there was no memory for the answer.
     */
    @JvmStatic external fun remembered(locator: String): String?
    /**
     * Forget [locator]'s server socket and refusal; its connection stays, and the next stream
     * probes again. 0; EINVAL: a malformed locator (or one holding U+0000); ENOMEM: no memory for
     * its copy; EPERM: no [init]; -1: no pool.
     */
    @JvmStatic external fun forgetSocket(locator: String): Int
    /** Drop [locator]'s connection, a login under way and its questions, and its remembered socket. */
    @JvmStatic external fun forget(locator: String): Int
}

/** [SshNative.setIdentities]' result for a key it left out: this plus the key's place, from 1. */
internal const val SSH_KEY_LEFT_OUT = 1000

/**
 * What [SshNative.setIdentities]' result [rc] says for the log; null for 0. A key left out is
 * named by its place only: nothing of the key itself reaches the log.
 */
internal fun identitiesLog(rc: Int): String? = when {
    rc == 0 -> null
    rc > SSH_KEY_LEFT_OUT -> "set identities: key ${rc - SSH_KEY_LEFT_OUT} left out, it does not parse"
    else -> "set identities: $rc"
}

/** [SshQuestions]' pool: the native one. */
object NativeSshPool : SshPool {
    override fun questions(): String? = SshNative.questions()
    override fun answer(id: Long, answer: String, ok: Boolean): Boolean = SshNative.answer(id, answer, ok)
    override fun installKey(target: String, publicLine: String, timeoutMs: Int): Int = SshNative.installKey(target, publicLine, timeoutMs)
}

/** [SshSecrets]' key functions: the native ones. */
object NativeSshKeys : SshKeyTool {
    override fun generate(comment: String): Pair<String, String>? =
        SshNative.generateKey(comment)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    override fun decrypt(pem: String, passphrase: String?, pemOut: Array<String?>): Int = SshNative.decryptKey(pem, passphrase, pemOut)
}
