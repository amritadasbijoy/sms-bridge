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
    fun parsesSignInLinksAndRejectsBrokenOnes() {
        val login = PairLinks.parseLogin("smsbridge://login?v=1&s=https%3A%2F%2Fsms.example.org&r=$device&f=$fpText")!!
        assertEquals("https://sms.example.org", login.server)
        assertEquals(device, login.requestId)
        assertArrayEquals(fp, login.fingerprint)
        assertNull(PairLinks.parseLogin("smsbridge://login?v=1&s=https%3A%2F%2Fx&r=$device")) // no fingerprint
        assertNull(PairLinks.parseLogin("smsbridge://login?v=1&s=https%3A%2F%2Fx&r=nope&f=$fpText"))
        assertNull(PairLinks.parseLogin("smsbridge://pair?v=1&s=https%3A%2F%2Fx&c=AB12C-DE34F")) // a pairing link is not a sign-in
        assertNull(PairLinks.parse("smsbridge://login?v=1&s=https%3A%2F%2Fx&r=$device&f=$fpText"))
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

    @Test
    fun parsesAddAnotherPhoneLinksAndComputesTheBrowsersProof() {
        val link = PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&c=AB12C-DE34F&k=AAECAwQFBgcICQoLDA0ODw")!!
        assertEquals("AB12C-DE34F", link.pairingCode)
        assertArrayEquals(ByteArray(16) { it.toByte() }, link.linkSecret)
        assertFalse(link.approvesBrowser)
        assertNull(PairLinks.parse("smsbridge://pair?v=1&s=https%3A%2F%2Fx&c=C&k=short")!!.linkSecret) // bad secret is ignored

        // Same keys and secret as web/test/link.test.ts: both sides must produce this value.
        val text = File("../../testdata/hpke-vectors-v1.json").readText()
        fun field(name: String) = Base64.getDecoder().decode(Regex("\"$name\": \"([^\"]+)\"").find(text)!!.groupValues[1])
        val proof = PairLinks.linkProof(ByteArray(16) { it.toByte() }, field("encPublic"), field("signPublic"))
        assertEquals("KFP4zjvvbRTPxhOZ1ATCcz/e8xyaJyTKUMMzzcVjnSk=", Base64.getEncoder().encodeToString(proof))
    }
}
