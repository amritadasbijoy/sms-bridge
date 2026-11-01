package app.smsbridge.data.backup

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted backup file (`.sbbackup`): a plain backup package (zip) wrapped in authenticated,
 * streaming encryption.
 *
 * ```
 * header  = "SBENC" | version u8 (1) | kdf u8 (1 = PBKDF2-HMAC-SHA256) | iterations u32 BE | saltLen u8 | salt
 * body    = Tink AesGcmHkdfStreaming(ikm = PBKDF2(passphrase, salt, iterations, 256 bit),
 *           HKDF-SHA256, AES-256-GCM, 1 MiB segments), associated data = header
 * ```
 *
 * Tink derives a fresh key and nonce prefix per file and authenticates every segment, so any
 * change to the header, any segment, the segment order, or a truncation is detected on read.
 * The passphrase is never stored; only the user can decrypt the file.
 */
object EncryptedBackup {
    private val MAGIC = "SBENC".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_LENGTH = 10
    const val FILE_EXTENSION = "sbbackup"

    /** Bounds for iterations read from a file: below is too weak, above is a denial-of-service. */
    private val ITERATION_RANGE = 100_000..10_000_000
    private const val SALT_BYTES = 16
    private const val SEGMENT_BYTES = 1 shl 20

    data class Header(val iterations: Int, val salt: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(MAGIC.size + 3 + 4 + salt.size).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(KDF_PBKDF2_SHA256.toByte())
            putInt(iterations)
            put(salt.size.toByte())
            put(salt)
        }.array()

        override fun equals(other: Any?) = other is Header && other.iterations == iterations && other.salt.contentEquals(salt)
        override fun hashCode() = 31 * iterations + salt.contentHashCode()
    }

    /** A key derived from a passphrase, together with the header that records how. */
    class DerivedKey(val header: Header, val key: ByteArray) {
        fun wipe() = key.fill(0)
    }

    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    fun newHeader(iterations: Int = DEFAULT_ITERATIONS): Header =
        Header(iterations, ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes))

    /** Slow on purpose (about a second on a phone); call off the main thread. Clears [passphrase]. */
    fun deriveKey(passphrase: CharArray, header: Header): DerivedKey {
        val spec = PBEKeySpec(passphrase, header.salt, header.iterations, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return DerivedKey(header, key)
        } finally {
            spec.clearPassword()
            passphrase.fill('\u0000')
        }
    }

    /** Writes the header, then returns a stream that encrypts everything written to it into [out]. */
    fun encrypt(out: OutputStream, key: DerivedKey): OutputStream {
        val header = key.header.encode()
        out.write(header)
        return streaming(key.key).newEncryptingStream(out, header)
    }

    /** Reads and validates the header only. */
    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        try {
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            if (!magic.contentEquals(MAGIC)) throw InvalidBackupException("Not an encrypted SMS Bridge backup.")
            val version = data.readUnsignedByte()
            if (version != VERSION) throw InvalidBackupException("This encrypted backup was made by a newer version of SMS Bridge.")
            if (data.readUnsignedByte() != KDF_PBKDF2_SHA256) throw InvalidBackupException("Unsupported key derivation.")
            val iterations = data.readInt()
            if (iterations !in ITERATION_RANGE) throw InvalidBackupException("The backup header is invalid.")
            val saltLength = data.readUnsignedByte()
            if (saltLength < SALT_BYTES) throw InvalidBackupException("The backup header is invalid.")
            return Header(iterations, ByteArray(saltLength).also(data::readFully))
        } catch (e: EOFException) {
            throw InvalidBackupException("The file is too short to be an encrypted backup.")
        }
    }

    /**
     * Reads the header from [input] (which must be positioned at the start of the file) and returns
     * a stream of the decrypted package. A wrong key or any tampering surfaces as an [IOException]
     * while reading, never as wrong plaintext.
     */
    fun decrypt(input: InputStream, key: DerivedKey): InputStream {
        val header = readHeader(input)
        if (header != key.header) throw InvalidBackupException("The key does not belong to this backup.")
        return streaming(key.key).newDecryptingStream(input, header.encode())
    }

    private fun streaming(ikm: ByteArray) = AesGcmHkdfStreaming(ikm, "HmacSha256", 32, SEGMENT_BYTES, 0)
}

class InvalidBackupException(message: String) : IOException(message)

/**
 * Derived backup keys, held only in memory and only until the job that needs them has run.
 * WorkManager persists job input to disk, so jobs receive an opaque id, never key material.
 * If the process dies, the key is gone and the user is asked for the passphrase again.
 */
@Singleton
class EphemeralKeys @Inject constructor() {
    private val keys = ConcurrentHashMap<String, EncryptedBackup.DerivedKey>()

    fun put(key: EncryptedBackup.DerivedKey): String = UUID.randomUUID().toString().also { keys[it] = key }

    fun get(id: String): EncryptedBackup.DerivedKey? = keys[id]

    fun remove(id: String) {
        keys.remove(id)?.wipe()
    }
}
