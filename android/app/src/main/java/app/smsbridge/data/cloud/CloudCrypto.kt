package app.smsbridge.data.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts small secrets (device private keys, vault keys, the device token) before they are
 * stored on disk. The Android implementation uses a non-exportable AES-256-GCM key in Android Keystore.
 */
interface KeyProtector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(blob: ByteArray): ByteArray
}

class KeystoreKeyProtector : KeyProtector {
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun protect(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unprotect(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        }
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smsbridge-cloud-protector"
    }
}

/** Raw 32-byte key pairs. */
class DeviceKeys(val encPrivate: ByteArray, val encPublic: ByteArray, val signPrivate: ByteArray, val signPublic: ByteArray) {
    companion object {
        fun generate(): DeviceKeys {
            val enc = X25519.generatePrivateKey()
            val sign = Ed25519Sign.KeyPair.newKeyPair()
            return DeviceKeys(enc, X25519.publicFromPrivate(enc), sign.privateKey, sign.publicKey)
        }
    }
}

/**
 * The cryptography of docs/sync-design.md, built only from reviewed primitives:
 * HPKE (RFC 9180, Tink), AES-256-GCM (JCA), Ed25519 and HKDF (Tink).
 */
object CloudCrypto {
    private val random = SecureRandom()
    private const val NONCE = 12
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    init {
        HybridConfig.register()
    }

    private val hpke: HpkeParameters = HpkeParameters.builder()
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .build()

    fun newVaultKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** HPKE context binds the wrap to the recipient and key version (a blob cannot be replayed elsewhere). */
    private fun wrapContext(recipientId: String, keyVersion: Int) = "sms-bridge vault key v$keyVersion for $recipientId".toByteArray()

    fun wrapForDevice(vaultKey: ByteArray, recipientPublic: ByteArray, recipientId: String, keyVersion: Int): ByteArray {
        val publicKey = HpkePublicKey.create(hpke, Bytes.copyFrom(recipientPublic), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary()).build()
        return handle.getPrimitive(HybridEncrypt::class.java).encrypt(vaultKey, wrapContext(recipientId, keyVersion))
    }

    fun unwrapForDevice(wrapped: ByteArray, keys: DeviceKeys, deviceId: String, keyVersion: Int): ByteArray {
        val publicKey = HpkePublicKey.create(hpke, Bytes.copyFrom(keys.encPublic), null)
        val privateKey = HpkePrivateKey.create(publicKey, SecretBytes.copyFrom(keys.encPrivate, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(privateKey).withRandomId().makePrimary()).build()
        return handle.getPrimitive(HybridDecrypt::class.java).decrypt(wrapped, wrapContext(deviceId, keyVersion))
    }

    // ---- Recovery key ------------------------------------------------------------------------

    /** 128 random bits, shown as 26 Crockford base32 characters in groups of 4/5. */
    fun newRecoveryKey(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun formatRecoveryKey(key: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (b in key) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(CROCKFORD[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(CROCKFORD[(value shl (5 - bits)) and 31])
        return out.chunked(4).joinToString("-")
    }

    /** Accepts any case, spaces and dashes, and the usual O/0, I/L/1 confusions. Null if malformed. */
    fun parseRecoveryKey(text: String): ByteArray? {
        val clean = text.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')
        if (clean.length != 26 || clean.any { it !in CROCKFORD }) return null
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in clean) {
            value = (value shl 5) or CROCKFORD.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray().takeIf { it.size == 16 }
    }

    private fun recoveryKek(recoveryKey: ByteArray): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", recoveryKey, "sms-bridge recovery v1".toByteArray(), "vault key wrap".toByteArray(), 32)

    fun wrapForRecovery(vaultKey: ByteArray, recoveryKey: ByteArray, keyVersion: Int): ByteArray =
        gcmEncrypt(recoveryKek(recoveryKey), vaultKey, "sms-bridge recovery v$keyVersion".toByteArray())

    fun unwrapForRecovery(wrapped: ByteArray, recoveryKey: ByteArray, keyVersion: Int): ByteArray =
        gcmDecrypt(recoveryKek(recoveryKey), wrapped, "sms-bridge recovery v$keyVersion".toByteArray())

    // ---- Events ------------------------------------------------------------------------------

    private fun eventAad(eventId: String, deviceId: String, deviceSequence: Long, keyVersion: Int) =
        "sbsync-v1|$eventId|$deviceId|$deviceSequence|$keyVersion".toByteArray()

    fun encryptEvent(vaultKey: ByteArray, payload: ByteArray, eventId: String, deviceId: String, deviceSequence: Long, keyVersion: Int): ByteArray =
        gcmEncrypt(vaultKey, payload, eventAad(eventId, deviceId, deviceSequence, keyVersion))

    fun decryptEvent(vaultKey: ByteArray, ciphertext: ByteArray, eventId: String, deviceId: String, deviceSequence: Long, keyVersion: Int): ByteArray =
        gcmDecrypt(vaultKey, ciphertext, eventAad(eventId, deviceId, deviceSequence, keyVersion))

    /** Exactly the bytes the server verifies (backend Sync.kt `signedMessage`). */
    fun signedMessage(eventId: String, deviceId: String, deviceSequence: Long, keyVersion: Int, ciphertext: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) }
        return "sbsync-v1|$eventId|$deviceId|$deviceSequence|$keyVersion|$digest".toByteArray(Charsets.UTF_8)
    }

    fun sign(signPrivate: ByteArray, message: ByteArray): ByteArray = Ed25519Sign(signPrivate).sign(message)

    /**
     * Six digits both devices display; equal only if both see the same public keys, so a server
     * swapping keys is caught. Shown as "123 456".
     */
    fun safetyCode(encPublic: ByteArray, signPublic: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(encPublic + signPublic)
        val n = ((d[0].toInt() and 0x7F) shl 24 or (d[1].toInt() and 0xFF shl 16) or (d[2].toInt() and 0xFF shl 8) or (d[3].toInt() and 0xFF)) % 1_000_000
        return "%06d".format(n).let { "${it.take(3)} ${it.drop(3)}" }
    }

    private fun gcmEncrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
        return nonce + cipher.doFinal(plain)
    }

    private fun gcmDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < NONCE + 16) throw GeneralSecurityException("ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, NONCE))
            updateAAD(aad)
        }
        return cipher.doFinal(blob, NONCE, blob.size - NONCE)
    }
}
