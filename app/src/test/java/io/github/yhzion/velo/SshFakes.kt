package io.github.yhzion.velo

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Not encryption: enough that nothing sealed reads as what went in, and that open undoes seal.
 * A sealed value is a version byte, the byte of the [key] that sealed it, and the text turned
 * around. Like [KeystoreSealer], it opens only what its own key sealed: another version is
 * [MalformedSealedValue], another key's value fails its tag ([AEADBadTagException]).
 */
internal class FakeSealer(private val key: Byte = 7) : Sealer {
    var opens = 0
    override fun seal(plain: ByteArray) = byteArrayOf(VERSION, key) + plain.reversedArray().map { (it.toInt() xor 0x5A).toByte() }
    override fun open(sealed: ByteArray): ByteArray {
        opens++
        if (sealed.size < 2 || sealed[0] != VERSION) throw MalformedSealedValue()
        if (sealed[1] != key) throw AEADBadTagException()
        return sealed.drop(2).map { (it.toInt() xor 0x5A).toByte() }.reversed().toByteArray()
    }

    companion object {
        const val VERSION: Byte = 1
    }
}

/**
 * An unencrypted OpenSSH private key as `ssh.MarshalPrivateKey` writes it: only its public half
 * is real. With a [comment] its private part is laid out as OpenSSH lays out an Ed25519 key's
 * (two check numbers, the type, the public and private halves, the comment, padding to 8), so
 * the comment can be read; the halves are made up. [cipher] other than "none" marks it encrypted.
 */
internal fun openSshPem(type: String, keyData: ByteArray, comment: String? = null, cipher: String = "none"): Pair<String, String> {
    fun DataOutputStream.string(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    val blob = ByteArrayOutputStream().also { DataOutputStream(it).apply { string(type.toByteArray()); string(keyData) } }.toByteArray()
    val private = if (comment == null) ByteArray(64) { 0x33 } else ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply {
            writeInt(0x1234); writeInt(0x1234)
            string(type.toByteArray()); string(keyData); string(ByteArray(64) { 0x44 })
            string(comment.toByteArray(Charsets.UTF_8))
        }
        var pad = 1
        while (out.size() % 8 != 0) out.write(pad++)
    }.toByteArray()
    val body = ByteArrayOutputStream().also { out ->
        out.write("openssh-key-v1\u0000".toByteArray())
        DataOutputStream(out).apply {
            string(cipher.toByteArray()); string((if (cipher == "none") "none" else "bcrypt").toByteArray()); string(ByteArray(0))
            writeInt(1); string(blob); string(private)
        }
    }.toByteArray()
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
        Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n") + "\n-----END OPENSSH PRIVATE KEY-----\n"
    return pem to "$type ${Base64.getEncoder().encodeToString(blob)}"
}

/** The [n]th test key, (unencrypted OpenSSH PEM, public line): what [keyFile] (n) opens to. */
internal fun testKey(n: Int) = openSshPem("ssh-ed25519", ByteArray(32) { n.toByte() })

/** A key file that [FakeKeys] opens to [testKey] (n). */
internal fun keyFile(n: Int) = "-----BEGIN OPENSSH PRIVATE KEY-----\nKEY $n\n-----END OPENSSH PRIVATE KEY-----\n"

/** What SshNative's key functions do, without the native library. */
internal class FakeKeys : SshKeyTool {
    var generated = 0
    val imported = openSshPem("ssh-ed25519", ByteArray(32) { it.toByte() })
    override fun generate(comment: String): Pair<String, String> {
        generated++
        return "-----BEGIN OPENSSH PRIVATE KEY-----\nphone-key-$generated\n-----END OPENSSH PRIVATE KEY-----\n" to
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPhone$generated $comment"
    }
    override fun decrypt(pem: String, passphrase: String?, pemOut: Array<String?>): Int = when {
        !pem.contains("PRIVATE KEY") -> 22 // EINVAL
        pem.contains("ENCRYPTED") && passphrase != "open sesame" -> 13 // EACCES
        else -> {
            // An unencrypted key opens to itself; a [keyFile] to its [testKey].
            pemOut[0] = when {
                openSshPublicLine(pem) != null -> pem
                else -> Regex("KEY (\\d+)").find(pem)?.let { testKey(it.groupValues[1].toInt()).first } ?: imported.first
            }
            0
        }
    }
}
