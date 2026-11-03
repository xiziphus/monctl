package com.xiziphus.monctl.net

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Bluetooth route's message seal; must match ble/monctl-ble.swift.
 *
 *   key  = HKDF-SHA256(token, salt "monctl-ble-v1", info "aes-gcm", 32 bytes)
 *   box  = nonce(12) || ciphertext || tag(16)        (CryptoKit's "combined")
 *   AAD  = session nonce || "Q" or "R" || counter(4, big-endian)
 */
internal object BleCrypto {
    private val random = SecureRandom()

    fun key(token: String): ByteArray =
        hkdf(token.trim().toByteArray(), "monctl-ble-v1".toByteArray(), "aes-gcm".toByteArray(), 32)

    fun aad(session: ByteArray, dir: Char, counter: Int): ByteArray =
        session + byteArrayOf(dir.code.toByte()) + be32(counter)

    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Throws javax.crypto.AEADBadTagException when the seal doesn't verify. */
    fun open(key: ByteArray, aad: ByteArray, box: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, box, 0, 12))
        c.updateAAD(aad)
        return c.doFinal(box, 12, box.size - 12)
    }

    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun readBe32(b: ByteArray, at: Int = 0): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** RFC 5869. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var done = 0
        var i = 1
        while (done < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - done)
            System.arraycopy(t, 0, out, done, n)
            done += n
            i++
        }
        return out
    }
}
