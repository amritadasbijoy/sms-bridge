package app.smsbridge.data.cloud

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCryptoTest {
    private val phoneA = DeviceKeys.generate()
    private val phoneB = DeviceKeys.generate()
    private val vk = CloudCrypto.newVaultKey()

    @Test
    fun hpkeWrapsOnlyForTheIntendedDeviceAndVersion() {
        val wrapped = CloudCrypto.wrapForDevice(vk, phoneB.encPublic, "device-b", 1)
        assertArrayEquals(vk, CloudCrypto.unwrapForDevice(wrapped, phoneB, "device-b", 1))
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.unwrapForDevice(wrapped, phoneA, "device-b", 1) }
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.unwrapForDevice(wrapped, phoneB, "device-c", 1) }
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.unwrapForDevice(wrapped, phoneB, "device-b", 2) }
    }

    @Test
    fun recoveryKeyFormatsParsesAndUnwraps() {
        val recovery = CloudCrypto.newRecoveryKey()
        val text = CloudCrypto.formatRecoveryKey(recovery)
        assertEquals(26, text.replace("-", "").length)
        assertTrue(text.matches(Regex("[0-9A-Z]{4}(-[0-9A-Z]{1,4}){6}")))
        assertArrayEquals(recovery, CloudCrypto.parseRecoveryKey(text.lowercase().replace("-", " ")))
        assertNull(CloudCrypto.parseRecoveryKey("too short"))

        val wrapped = CloudCrypto.wrapForRecovery(vk, recovery, 1)
        assertArrayEquals(vk, CloudCrypto.unwrapForRecovery(wrapped, recovery, 1))
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.unwrapForRecovery(wrapped, CloudCrypto.newRecoveryKey(), 1) }
    }

    @Test
    fun eventsAreBoundToTheirMetadata() {
        val payload = """{"type":"SMS_RECEIVED","body":"Synthetic secret"}""".toByteArray()
        val id = UUID.randomUUID().toString()
        val ct = CloudCrypto.encryptEvent(vk, payload, id, "device-a", 7, 1)
        assertFalse(String(ct, Charsets.ISO_8859_1).contains("Synthetic"))
        assertArrayEquals(payload, CloudCrypto.decryptEvent(vk, ct, id, "device-a", 7, 1))
        // Moving the ciphertext to another event, device or sequence fails authentication.
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.decryptEvent(vk, ct, UUID.randomUUID().toString(), "device-a", 7, 1) }
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.decryptEvent(vk, ct, id, "device-b", 7, 1) }
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.decryptEvent(vk, ct, id, "device-a", 8, 1) }
        assertThrows(GeneralSecurityException::class.java) { CloudCrypto.decryptEvent(CloudCrypto.newVaultKey(), ct, id, "device-a", 7, 1) }
        // Same plaintext twice gives different ciphertexts (random nonce).
        assertNotEquals(ct.toList(), CloudCrypto.encryptEvent(vk, payload, id, "device-a", 7, 1).toList())
    }

    @Test
    fun signaturesVerifyTheWayTheServerVerifiesThem() {
        val ct = CloudCrypto.encryptEvent(vk, "x".toByteArray(), "e1", "device-a", 1, 1)
        val message = CloudCrypto.signedMessage("e1", "device-a", 1, 1, ct)
        val signature = CloudCrypto.sign(phoneA.signPrivate, message)
        // Same construction as backend Sync.kt verifyEd25519 (JDK Ed25519, RFC 8410 SPKI prefix).
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + phoneA.signPublic
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key); verifier.update(message)
        assertTrue(verifier.verify(signature))
        verifier.initVerify(key); verifier.update(CloudCrypto.signedMessage("e1", "device-a", 2, 1, ct))
        assertFalse(verifier.verify(signature))
        assertTrue(String(message).startsWith("sbsync-v1|e1|device-a|1|1|"))
    }

    @Test
    fun safetyCodeIsSixDigitsAndKeyDependent() {
        val code = CloudCrypto.safetyCode(phoneA.encPublic, phoneA.signPublic)
        assertTrue(code.matches(Regex("\\d{3} \\d{3}")))
        assertEquals(code, CloudCrypto.safetyCode(phoneA.encPublic, phoneA.signPublic))
        assertNotEquals(code, CloudCrypto.safetyCode(phoneB.encPublic, phoneA.signPublic))
    }
}
