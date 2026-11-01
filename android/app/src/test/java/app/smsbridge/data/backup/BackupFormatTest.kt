package app.smsbridge.data.backup

import app.smsbridge.core.model.SmsType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Synthetic records only. */
internal object BackupSamples {
    fun record(i: Int, body: String = "Synthetic backup message $i", type: SmsType = if (i % 3 == 0) SmsType.SENT else SmsType.INBOX) =
        BackupRecord(
            address = "+1555010${(i % 100).toString().padStart(4, '0')}",
            body = body,
            date = 1_700_000_000_000L + i * 60_000L,
            dateSent = if (type == SmsType.INBOX) 1_700_000_000_000L + i * 60_000L - 2_000 else 0,
            type = type,
            read = i % 2 == 0,
            seen = true,
            status = null,
            subscriptionId = i % 2,
            archiveId = "id-$i",
            sourceDeviceId = "device-a",
        )

    /** Bodies that break naive XML/CSV writers. */
    val tricky = listOf(
        "Line one\nLine two\r\nLine three\tTabbed",
        "Quotes \" and ' and <tags> & ampersands",
        "আপনার কোড ১২৩৪৫৬ — Bangla text",
        "Emoji 😀👍🏽 and symbols ©®",
        "Control \u0001 \u001F chars",
        "",
        "   leading and trailing spaces   ",
        "=SUM(A1:A9) looks like a formula",
    )

    fun source(count: Int) = BackupSource(
        backupId = "backup-1", createdAt = 1_780_000_000_000L, databaseSchemaVersion = 2, appVersion = "test",
        deviceId = "device-a", deviceName = "Synthetic phone", androidSdk = 34, timeZone = "UTC", messageCount = count,
    )

    fun packageBytes(records: List<BackupRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { writeBackupPackage(out, source(records.size)) { sink -> records.forEach(sink) } }
        return out.toByteArray()
    }
}

@RunWith(RobolectricTestRunner::class)
class BackupFormatTest {

    @Test
    fun packageRoundTripIsLossless() {
        val records = BackupSamples.tricky.mapIndexed { i, body -> BackupSamples.record(i, body) } +
            BackupSamples.record(100).copy(address = null, subscriptionId = null, status = 64)
        val bytes = BackupSamples.packageBytes(records)

        val info = inspectBackup { ByteArrayInputStream(bytes) }
        assertTrue(info.problems.toString(), info.isUsable)
        assertEquals(BackupKind.SMS_BRIDGE_PACKAGE, info.kind)
        assertEquals(true, info.checksumsValid)
        assertEquals(records.size, info.messageCount)
        assertEquals("Synthetic phone", info.sourceDeviceName)

        val readBack = openMessagesStream({ ByteArrayInputStream(bytes) }, info.kind).use { SmsXmlReader(it).asSequence().toList() }
        assertEquals(records, readBack)
    }

    @Test
    fun packageContainsRequiredFilesWithSha256sumFormat() {
        val bytes = BackupSamples.packageBytes(listOf(BackupSamples.record(1)))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(listOf("messages.xml", "metadata.json", "manifest.json", "checksums.sha256"), entries.keys.toList())
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals("sms-bridge-backup", manifest.getString("format"))
        assertEquals(1, manifest.getInt("formatVersion"))
        assertEquals(2, manifest.getInt("databaseSchemaVersion"))
        assertEquals(1, manifest.getInt("messageCount"))
        assertTrue(entries.getValue("checksums.sha256").lines().first().matches(Regex("[0-9a-f]{64}  messages.xml")))
    }

    @Test
    fun tamperedMessagesAreDetected() {
        val bytes = BackupSamples.packageBytes(listOf(BackupSamples.record(1, "Original synthetic")))
        // Rebuild the zip with an edited messages.xml but the original manifest and checksums.
        val tampered = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            ZipOutputStream(tampered).use { output ->
                while (true) {
                    val e = input.nextEntry ?: break
                    var content = input.readBytes()
                    if (e.name == "messages.xml") content = content.toString(Charsets.UTF_8).replace("Original", "Altered").toByteArray()
                    output.putNextEntry(ZipEntry(e.name))
                    output.write(content)
                    output.closeEntry()
                }
            }
        }
        val info = inspectBackup { ByteArrayInputStream(tampered.toByteArray()) }
        assertEquals(false, info.checksumsValid)
        assertFalse(info.isUsable)
    }

    @Test
    fun interruptedPackageIsRejected() {
        // Simulates a backup killed after messages.xml: no manifest, no checksums.
        val partial = ByteArrayOutputStream()
        ZipOutputStream(partial).use { zip ->
            zip.putNextEntry(ZipEntry("messages.xml"))
            val w = StringWriter()
            SmsXmlWriter(w).apply { begin(1, "x", 0); write(BackupSamples.record(1)); end() }
            zip.write(w.toString().toByteArray())
            zip.closeEntry()
        }
        val info = inspectBackup { ByteArrayInputStream(partial.toByteArray()) }
        assertFalse(info.isUsable)
        assertTrue(info.problems.any { it.contains("manifest") })

        val truncated = BackupSamples.packageBytes(listOf(BackupSamples.record(1))).let { it.copyOf(it.size / 2) }
        assertFalse(inspectBackup { ByteArrayInputStream(truncated) }.isUsable)
    }

    @Test
    fun readsXmlFromOtherBackupTools() {
        // Synthetic file in the common <smses> layout, with "null" placeholders, an MMS and a broken row.
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="4" backup_set="abc" backup_date="1700000000000" type="full">
              <sms protocol="0" address="+15550100" date="1700000000000" type="1" subject="null" body="Hello&#10;there" toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="1699999990000" sub_id="-1" readable_date="x" contact_name="(Unknown)" />
              <sms protocol="0" address="null" date="1700000060000" type="2" body="Sent synthetic" read="1" status="-1" date_sent="0" />
              <mms date="1700000120000" msg_box="1"><parts><part seq="0" ct="text/plain" text="ignored" /></parts></mms>
              <sms address="+15550100" type="1" body="no date, invalid" />
            </smses>
        """.trimIndent()
        val info = inspectBackup { ByteArrayInputStream(xml.toByteArray()) }
        assertEquals(BackupKind.PLAIN_XML, info.kind)
        assertNull(info.checksumsValid)
        assertEquals(2, info.messageCount)
        assertEquals(1, info.incoming)
        assertEquals(1, info.outgoing)
        assertEquals(1, info.skippedInvalid)
        assertEquals(1, info.skippedUnsupported) // one <mms>, not its nested <parts>/<part>
        assertTrue(info.isUsable)

        val records = SmsXmlReader(ByteArrayInputStream(xml.toByteArray())).asSequence().toList()
        assertEquals("Hello\nthere", records[0].body)
        assertNull(records[0].subscriptionId)
        assertNull(records[1].address)
        assertEquals(SmsType.SENT, records[1].type)
    }

    @Test
    fun rejectsFilesThatAreNotSmsBackups() {
        val info = inspectBackup { ByteArrayInputStream("<html><body>nope</body></html>".toByteArray()) }
        assertFalse(info.isUsable)
        assertFalse(inspectBackup { ByteArrayInputStream("not xml at all".toByteArray()) }.isUsable)
    }

    @Test
    fun csvQuotesAndBlocksFormulaInjection() {
        assertEquals("plain", SmsCsvWriter.cell("plain"))
        assertEquals("\"a,b\"", SmsCsvWriter.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SmsCsvWriter.cell("say \"hi\""))
        assertEquals("\"line1\nline2\"", SmsCsvWriter.cell("line1\nline2"))
        assertEquals("'=SUM(A1)", SmsCsvWriter.cell("=SUM(A1)"))
        assertEquals("'+15550100", SmsCsvWriter.cell("+15550100"))

        val out = StringWriter()
        SmsCsvWriter(out).apply { begin(); BackupSamples.tricky.forEachIndexed { i, b -> write(BackupSamples.record(i, b)) } }
        val text = out.toString()
        assertTrue(text.startsWith("\uFEFFid,address,"))
        assertTrue(text.contains("আপনার কোড"))
    }

    @Test
    fun jsonExportIsValidJson() {
        val out = StringWriter()
        SmsJsonWriter(out).apply {
            begin(BackupSamples.tricky.size, 0, "device-a")
            BackupSamples.tricky.forEachIndexed { i, b -> write(BackupSamples.record(i, b)) }
            end()
        }
        val json = JSONObject(out.toString())
        assertEquals("sms-bridge-json", json.getString("format"))
        val messages = json.getJSONArray("messages")
        assertEquals(BackupSamples.tricky.size, messages.length())
        assertEquals(BackupSamples.tricky[0], messages.getJSONObject(0).getString("body"))
    }

    @Test
    fun exportFailsLoudlyIfCountChangesDuringStreaming() {
        val out = ByteArrayOutputStream()
        val result = runCatching {
            runBlocking { writeBackupPackage(out, BackupSamples.source(5)) { sink -> sink(BackupSamples.record(1)) } }
        }
        assertTrue(result.isFailure)
    }
}
