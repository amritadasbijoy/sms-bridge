package app.smsbridge.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedBackupTest {
    // The minimum the reader accepts; the app itself uses 600,000.
    private val iterations = 100_000

    private fun key(passphrase: String, header: EncryptedBackup.Header = EncryptedBackup.newHeader(iterations)) =
        EncryptedBackup.deriveKey(passphrase.toCharArray(), header)

    private fun encrypt(plain: ByteArray, key: EncryptedBackup.DerivedKey): ByteArray {
        val out = ByteArrayOutputStream()
        EncryptedBackup.encrypt(out, key).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, passphrase: String): ByteArray {
        val header = EncryptedBackup.readHeader(ByteArrayInputStream(cipher))
        return EncryptedBackup.decrypt(ByteArrayInputStream(cipher), key(passphrase, header)).readBytes()
    }

    @Test
    fun encryptedPackageRoundTripsAndValidates() {
        val records = (1..2_500).map { BackupSamples.record(it) } + BackupSamples.tricky.mapIndexed { i, b -> BackupSamples.record(10_000 + i, b) }
        val plain = BackupSamples.packageBytes(records)
        val k = key("correct horse battery")
        val cipher = encrypt(plain, k)

        assertTrue(EncryptedBackup.isEncrypted(cipher))
        assertFalse(EncryptedBackup.isEncrypted(plain))
        // Ciphertext reveals nothing obvious: the zip and XML markers are gone.
        assertFalse(String(cipher, Charsets.ISO_8859_1).contains("messages.xml"))
        assertFalse(String(cipher, Charsets.ISO_8859_1).contains("Synthetic backup message"))

        val decrypted = decrypt(cipher, "correct horse battery")
        assertArrayEquals(plain, decrypted)

        // The whole Phase 3 validation works on the decrypted stream.
        val header = EncryptedBackup.readHeader(ByteArrayInputStream(cipher))
        val k2 = key("correct horse battery", header)
        val info = inspectBackup { EncryptedBackup.decrypt(ByteArrayInputStream(cipher), k2) }
        assertTrue(info.isUsable)
        assertEquals(records.size, info.messageCount)
        assertEquals(true, info.checksumsValid)
    }

    @Test
    fun wrongPassphraseFailsInsteadOfProducingGarbage() {
        val cipher = encrypt(BackupSamples.packageBytes(listOf(BackupSamples.record(1))), key("right passphrase"))
        assertThrows(IOException::class.java) { decrypt(cipher, "wrong passphrase") }
    }

    @Test
    fun anyTamperingIsDetected() {
        val plain = BackupSamples.packageBytes((1..50).map { BackupSamples.record(it) })
        val cipher = encrypt(plain, key("tamper test passphrase"))
        val headerSize = EncryptedBackup.readHeader(ByteArrayInputStream(cipher)).encode().size

        // Flip one bit in the ciphertext body.
        val flipped = cipher.copyOf().also { it[headerSize + 40] = (it[headerSize + 40].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { decrypt(flipped, "tamper test passphrase") }

        // Cut off the end (an interrupted or truncated copy).
        assertThrows(IOException::class.java) { decrypt(cipher.copyOf(cipher.size - 10), "tamper test passphrase") }

        // Change the salt in the header: the key changes AND the header is authenticated.
        val badSalt = cipher.copyOf().also { it[headerSize - 1] = (it[headerSize - 1].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { decrypt(badSalt, "tamper test passphrase") }
    }

    @Test
    fun headerIsValidated() {
        assertThrows(InvalidBackupException::class.java) {
            EncryptedBackup.readHeader(ByteArrayInputStream("PK\u0003\u0004 not encrypted".toByteArray()))
        }
        // An absurd iteration count (denial of service) or too-weak one is refused before any work.
        val tooMany = EncryptedBackup.Header(Int.MAX_VALUE, ByteArray(16)).encode()
        assertThrows(InvalidBackupException::class.java) { EncryptedBackup.readHeader(ByteArrayInputStream(tooMany)) }
        val tooFew = EncryptedBackup.Header(1_000, ByteArray(16)).encode()
        assertThrows(InvalidBackupException::class.java) { EncryptedBackup.readHeader(ByteArrayInputStream(tooFew)) }
        assertThrows(InvalidBackupException::class.java) { EncryptedBackup.readHeader(ByteArrayInputStream(ByteArray(3))) }
    }

    @Test
    fun saltsAreRandomAndKeysDependOnBoth() {
        val a = EncryptedBackup.newHeader(iterations)
        val b = EncryptedBackup.newHeader(iterations)
        assertFalse(a.salt.contentEquals(b.salt))
        assertNotEquals(key("same passphrase!", a).key.toList(), key("same passphrase!", b).key.toList())
        assertEquals(key("same passphrase!", a).key.toList(), key("same passphrase!", a).key.toList())
    }

    @Test
    fun passphraseAndKeysAreWiped() {
        val passphrase = "wipe me please".toCharArray()
        val k = EncryptedBackup.deriveKey(passphrase, EncryptedBackup.newHeader(iterations))
        assertTrue(passphrase.all { it == '\u0000' })

        val keys = EphemeralKeys()
        val id = keys.put(k)
        assertTrue(keys.get(id) === k)
        keys.remove(id)
        assertTrue(k.key.all { it == 0.toByte() })
        assertEquals(null, keys.get(id))
    }
}
