package app.smsbridge.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone must derive the same login secret as the website (web/lib/password.ts). */
class LoginSecretTest {
    @Test
    fun matchesTheWebsite() {
        // Expected value from Python: pbkdf2_hmac("sha256", NFKC(password), "sms-bridge login v1|amrita", 600000, 32).
        assertEquals("quJJRJ5uc0RzmC4fcLNlkfZk3Ml0HNCBfyZugfdMX0M=", loginSecret("  Amrita ", "Correct horse ✓ 42"))
    }
}
