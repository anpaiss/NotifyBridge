package com.anpaiss.notifybridge.phone

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and encryption of the messages between the phone and the Karoo, the same file in both apps
 * (karoo/.../PairCrypto.kt): keep them identical.
 *
 * Pairing (20/11/2026): an ECDH P-256 key exchange whose six-digit code the user compares on the two
 * screens. The Karoo commits to its public key (sends its hash) before it sees the phone's, and reveals
 * it only after: someone in between cannot try key after key until the two codes match, he has one
 * chance in a million. The code comes from the two public keys; the shared key from the ECDH secret,
 * which nobody listening can compute.
 *
 * Messages: AES-256-GCM, a random 12-byte nonce in front. A device nearby can still subscribe, but what
 * it receives cannot be read, and a message altered on the way is refused.
 */
object PairCrypto {

    const val NONCE = 12
    const val TAG_BITS = 128
    /** Bytes a sealed frame adds to the plain text. */
    const val OVERHEAD = NONCE + TAG_BITS / 8

    private val random = SecureRandom()

    class Paired(val key: ByteArray, val id: ByteArray, val code: String)

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun sha(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun commit(pub: ByteArray): ByteArray = sha("nb-commit".toByteArray(), pub)

    /** [own] is this side's key pair; the public keys are X.509-encoded, the Karoo's first. */
    fun derive(own: KeyPair, karooPub: ByteArray, phonePub: ByteArray): Paired {
        val other = if (own.public.encoded.contentEquals(karooPub)) phonePub else karooPub
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(other)), true)
            generateSecret()
        }
        val key = sha("nb-key".toByteArray(), secret, karooPub, phonePub)
        val id = sha("nb-id".toByteArray(), key).copyOf(8)
        val h = sha("nb-code".toByteArray(), karooPub, phonePub)
        val n = ((h[0].toLong() and 0xff) shl 24 or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) % 1_000_000
        val digits = "%06d".format(n)
        return Paired(key, id, digits.substring(0, 3) + " " + digits.substring(3))
    }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + c.doFinal(plain)
    }

    /** The plain text, or null when the frame was not sealed with [key] or was altered. */
    fun open(key: ByteArray, frame: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, frame, 0, NONCE))
        c.doFinal(frame, NONCE, frame.size - NONCE)
    }.getOrNull()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
