package app.smsbridge.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric provides a real org.json (plain JVM tests only get Android's stubs).
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    @Test
    fun parsesLatestJsonAndResolvesRelativeLinks() {
        val info = UpdateChecker.parse("""{"versionCode":8,"versionName":"0.6.0","url":"/download/sms-bridge-0.6.0.apk","sha256":"ab"}""", "https://sms.example.org")!!
        assertEquals(8, info.versionCode)
        assertEquals("0.6.0", info.versionName)
        assertEquals("https://sms.example.org/download/sms-bridge-0.6.0.apk", info.url)
        assertEquals("https://cdn.example.org/a.apk", UpdateChecker.parse("""{"versionCode":8,"versionName":"x","url":"https://cdn.example.org/a.apk"}""", "https://s")!!.url)
    }

    @Test
    fun rejectsBrokenOrUnsafeFiles() {
        assertNull(UpdateChecker.parse("not json", "https://s"))
        assertNull(UpdateChecker.parse("""{"versionName":"0.6.0","url":"/a.apk"}""", "https://s")) // no versionCode
        assertNull(UpdateChecker.parse("""{"versionCode":8,"versionName":"x","url":"javascript:alert(1)"}""", "https://s"))
        assertNull(UpdateChecker.parse("""{"versionCode":8,"versionName":"x","url":"file:///sdcard/a.apk"}""", "https://s"))
    }
}
