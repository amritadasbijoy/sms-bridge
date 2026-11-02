package app.smsbridge.data.cloud

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkTest {
    private val device = "7cc2df38-882c-4fd5-9e12-36fdbc308132"
    private val fp = ByteArray(32) { it.toByte() }
    private val fpText = Base64.getUrlEncoder().withoutPadding().encodeToString(fp)

    @Test
    fun parsesConnectApproveAndCombinedLinks() {
        val connect = PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&c=AB12C-DE34F")!!
        assertEquals("https://sms.example.org", connect.server)
        assertEquals("AB12C-DE34F", connect.pairingCode)
        assertFalse(connect.approvesBrowser)

        val approve = PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&d=$device&f=$fpText")!!
        assertNull(approve.pairingCode)
        assertTrue(approve.approvesBrowser)
        assertArrayEquals(fp, approve.fingerprint)

        val both = PairLinks.parse("  smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&c=AB12C-DE34F&d=$device&f=$fpText\n")!!
        assertEquals("AB12C-DE34F", both.pairingCode)
        assertEquals(device, both.deviceId)
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(PairLinks.parse("https://sms.example.org"))
        assertNull(PairLinks.parse("smsbridge://pair?v=2&s=https%3A%2F%2Fx&c=AB12C-DE34F")) // unknown version
        assertNull(PairLinks.parse("smsbridge://pair?v=1&s=ftp%3A%2F%2Fx&c=AB12C-DE34F")) // bad scheme
        assertNull(PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fx")) // nothing to do
        assertNull(PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fx&d=$device&f=short")) // bad fingerprint
        assertNull(PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fx&d=not-an-id&f=$fpText"))
        assertNull(PairLinks.parse("smsbridge://transfer?v=1&h=1.2.3.4")) // the Wi-Fi transfer QR is a different thing
    }

    @Test
    fun fingerprintMatchesTheBrowsersAndOnlyTheseKeys() {
        // The same test keys as web/test/pair.test.ts; both sides must produce this value.
        val text = File("../../testdata/hpke-vectors-v1.json").readText()
        fun field(name: String) = Base64.getDecoder().decode(Regex("\"$name\": \"([^\"]+)\"").find(text)!!.groupValues[1])
        val enc = field("encPublic")
        val sign = field("signPublic")
        val expected = Base64.getUrlDecoder().decode("niUOofQkoq17tqL8R27m0TeNCJbZO6J3NG9uSdTO2lQ")
        assertArrayEquals(expected, PairLinks.fingerprint(enc, sign))
        assertTrue(PairLinks.matches(expected, enc, sign))
        assertFalse(PairLinks.matches(expected, sign, enc)) // swapped keys
        assertFalse(PairLinks.matches(expected, enc, ByteArray(32)))
    }
}
