package app.smsbridge.data.cloud

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmNumbersTest {
    @Test
    fun matchesTheBrowsersNumberForTheSharedVector() {
        // Same keys and salt as web/test/confirm.test.ts (computed independently: 92).
        val text = File("../../testdata/hpke-vectors-v1.json").readText()
        fun field(name: String) = Base64.getDecoder().decode(Regex("\"$name\": \"([^\"]+)\"").find(text)!!.groupValues[1])
        val fingerprint = PairLinks.fingerprint(field("encPublic"), field("signPublic"))
        assertEquals(92, ConfirmNumbers.number(ByteArray(16) { it.toByte() }, fingerprint))
    }

    @Test
    fun numbersAreTwoDigitsAndOptionsHoldTheAnswerOnce() {
        repeat(500) {
            val n = ConfirmNumbers.number(ConfirmNumbers.newSalt(), ByteArray(32) { i -> (i * it).toByte() })
            assertTrue(n in 10..99)
            val options = ConfirmNumbers.options(n)
            assertEquals(3, options.toSet().size)
            assertTrue(n in options && options.all { it in 10..99 })
        }
    }
}
