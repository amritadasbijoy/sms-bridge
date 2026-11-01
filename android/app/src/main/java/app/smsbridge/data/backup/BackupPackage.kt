package app.smsbridge.data.backup

import app.smsbridge.core.model.SmsDirection
import java.io.BufferedInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMS Bridge backup package: a ZIP containing, in this order,
 *
 * - `messages.xml`      every message, streamed (`<smses><sms …/></smses>` layout)
 * - `metadata.json`     where and when the backup was made, counts, date range
 * - `manifest.json`     format/schema versions, file list with SHA-256 and sizes
 * - `checksums.sha256`  `sha256sum`-compatible lines for the three files above
 */
object BackupPackage {
    const val FORMAT = "sms-bridge-backup"
    const val FORMAT_VERSION = 1
    const val MESSAGES = "messages.xml"
    const val METADATA = "metadata.json"
    const val MANIFEST = "manifest.json"
    const val CHECKSUMS = "checksums.sha256"
}

data class BackupSource(
    val backupId: String,
    val createdAt: Long,
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val deviceId: String,
    val deviceName: String,
    val androidSdk: Int,
    val timeZone: String,
    /** Exact number of records [writeRecords] will emit (snapshot taken before streaming). */
    val messageCount: Int,
)

data class WrittenBackup(val messageCount: Int, val messagesSha256: String, val messagesBytes: Long)

/**
 * Writes a backup package to [output] (closed when done). [writeRecords] is called once and
 * must pass every record to the supplied sink; it is a suspend function so it can page through
 * Room. Memory use is bounded by the page size; hashes are computed while streaming.
 */
suspend fun writeBackupPackage(
    output: OutputStream,
    source: BackupSource,
    writeRecords: suspend (sink: (BackupRecord) -> Unit) -> Unit,
): WrittenBackup {
    val stats = RecordStats()
    ZipOutputStream(output.buffered()).use { zip ->
        // messages.xml
        zip.putNextEntry(ZipEntry(BackupPackage.MESSAGES).apply { time = source.createdAt })
        val messagesDigest = MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(DigestOutputStream(NonClosingOutputStream(zip), messagesDigest))
        val writer = OutputStreamWriter(counting, Charsets.UTF_8).buffered(64 * 1024)
        val xml = SmsXmlWriter(writer)
        xml.begin(source.messageCount, source.backupId, source.createdAt)
        writeRecords { record ->
            xml.write(record)
            stats.add(record)
        }
        xml.end()
        writer.flush()
        zip.closeEntry()
        check(stats.count == source.messageCount) {
            "message count changed during export (${stats.count} != ${source.messageCount})"
        }
        val messagesHash = messagesDigest.digest().toHex()

        val metadata = JSONObject()
            .put("backupId", source.backupId)
            .put("createdAt", source.createdAt)
            .put("appVersion", source.appVersion)
            .put("sourceDeviceId", source.deviceId)
            .put("sourceDeviceName", source.deviceName)
            .put("androidSdk", source.androidSdk)
            .put("timeZone", source.timeZone)
            .put("messageCount", stats.count)
            .put("incoming", stats.incoming)
            .put("outgoing", stats.outgoing)
            .put("oldestDate", stats.oldest ?: JSONObject.NULL)
            .put("newestDate", stats.newest ?: JSONObject.NULL)
            .toString(2).toByteArray(Charsets.UTF_8)
        val metadataHash = putSmallEntry(zip, BackupPackage.METADATA, metadata, source.createdAt)

        val manifest = JSONObject()
            .put("format", BackupPackage.FORMAT)
            .put("formatVersion", BackupPackage.FORMAT_VERSION)
            .put("databaseSchemaVersion", source.databaseSchemaVersion)
            .put("backupId", source.backupId)
            .put("createdAt", source.createdAt)
            .put("messageCount", stats.count)
            .put(
                "files",
                JSONArray()
                    .put(JSONObject().put("name", BackupPackage.MESSAGES).put("sha256", messagesHash).put("bytes", counting.count))
                    .put(JSONObject().put("name", BackupPackage.METADATA).put("sha256", metadataHash).put("bytes", metadata.size)),
            )
            .toString(2).toByteArray(Charsets.UTF_8)
        val manifestHash = putSmallEntry(zip, BackupPackage.MANIFEST, manifest, source.createdAt)

        val checksums = buildString {
            append("$messagesHash  ${BackupPackage.MESSAGES}\n")
            append("$metadataHash  ${BackupPackage.METADATA}\n")
            append("$manifestHash  ${BackupPackage.MANIFEST}\n")
        }.toByteArray(Charsets.UTF_8)
        putSmallEntry(zip, BackupPackage.CHECKSUMS, checksums, source.createdAt)
        zip.finish()
        return WrittenBackup(stats.count, messagesHash, counting.count)
    }
}

private fun putSmallEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, time: Long): String {
    zip.putNextEntry(ZipEntry(name).apply { this.time = time })
    zip.write(bytes)
    zip.closeEntry()
    return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

// ---- Validation ---------------------------------------------------------------------------

enum class BackupKind { SMS_BRIDGE_PACKAGE, PLAIN_XML }

/** Result of reading a backup end to end without changing anything. */
data class BackupInfo(
    val kind: BackupKind,
    val messageCount: Int,
    val incoming: Int,
    val outgoing: Int,
    val oldestDate: Long?,
    val newestDate: Long?,
    val createdAt: Long?,
    val sourceDeviceName: String?,
    /** True/false for packages; null for plain XML (no checksums to verify). */
    val checksumsValid: Boolean?,
    val skippedInvalid: Int,
    val skippedUnsupported: Int,
    val problems: List<String>,
) {
    /** Safe to restore: readable, at least one message, and no integrity problem. */
    val isUsable: Boolean get() = problems.isEmpty() && messageCount > 0
}

/**
 * Validates a backup. [open] is called once and must return a fresh stream of the file.
 * Packages: every entry is hashed and compared with `checksums.sha256` and `manifest.json`;
 * the message count is checked against the manifest. Plain XML: parsed and counted.
 */
fun inspectBackup(open: () -> InputStream): BackupInfo {
    val head = open().use { input -> ByteArray(4).also { input.read(it) } }
    val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    return if (isZip) inspectPackage(open) else inspectXml(open)
}

private fun inspectXml(open: () -> InputStream): BackupInfo = open().use { input ->
    val stats = RecordStats()
    val problems = mutableListOf<String>()
    val reader = try {
        SmsXmlReader(BufferedInputStream(input)).also { r -> r.forEach(stats::add) }
    } catch (e: Exception) {
        problems += "Not a readable SMS backup (${e.javaClass.simpleName})."
        null
    }
    if (reader != null && reader.header == null) problems += "No <smses> element: this is not an SMS backup file."
    stats.toInfo(BackupKind.PLAIN_XML, reader, checksumsValid = null, createdAt = reader?.header?.backupDate, deviceName = null, problems)
}

private fun inspectPackage(open: () -> InputStream): BackupInfo = open().use { raw ->
    val stats = RecordStats()
    val problems = mutableListOf<String>()
    val hashes = mutableMapOf<String, String>()
    var reader: SmsXmlReader? = null
    var manifest: JSONObject? = null
    var metadata: JSONObject? = null
    var checksums: String? = null

    try {
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val digest = MessageDigest.getInstance("SHA-256")
                val hashing = DigestInputStream(NonClosingInputStream(zip), digest)
                when (entry.name) {
                    BackupPackage.MESSAGES -> {
                        reader = SmsXmlReader(hashing).also { r -> r.forEach(stats::add) }
                        hashing.drain()
                    }
                    BackupPackage.MANIFEST -> manifest = JSONObject(hashing.readBytes().toString(Charsets.UTF_8))
                    BackupPackage.METADATA -> metadata = JSONObject(hashing.readBytes().toString(Charsets.UTF_8))
                    BackupPackage.CHECKSUMS -> checksums = hashing.readBytes().toString(Charsets.UTF_8)
                    else -> hashing.drain()
                }
                hashes[entry.name] = digest.digest().toHex()
            }
        }
    } catch (e: Exception) {
        problems += "The backup file is damaged or incomplete (${e.javaClass.simpleName})."
    }

    if (reader == null) problems += "messages.xml is missing."
    val manifestJson = manifest
    if (manifestJson == null) {
        problems += "manifest.json is missing: the backup may have been interrupted."
    } else {
        if (manifestJson.optString("format") != BackupPackage.FORMAT) problems += "Unknown backup format."
        if (manifestJson.optInt("formatVersion", -1) > BackupPackage.FORMAT_VERSION) {
            problems += "This backup was made by a newer version of SMS Bridge."
        }
        val declared = manifestJson.optInt("messageCount", -1)
        if (reader != null && declared != stats.count) problems += "Message count does not match the manifest ($declared declared, ${stats.count} found)."
        manifestJson.optJSONArray("files")?.let { files ->
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                if (hashes[file.optString("name")] != file.optString("sha256")) problems += "Checksum mismatch for ${file.optString("name")}."
            }
        }
    }
    val expected = checksums?.lineSequence()
        ?.mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } }
        ?.associate { (hash, name) -> name.removePrefix("*") to hash }
        .orEmpty()
    if (checksums == null) problems += "checksums.sha256 is missing."
    val checksumsValid = expected.isNotEmpty() && expected.all { (name, hash) -> hashes[name] == hash }
    if (checksums != null && !checksumsValid) problems += "checksums.sha256 does not match the file contents."

    stats.toInfo(
        BackupKind.SMS_BRIDGE_PACKAGE,
        reader,
        checksumsValid = checksumsValid && problems.none { it.startsWith("Checksum") },
        createdAt = manifestJson?.optLong("createdAt")?.takeIf { it > 0 },
        deviceName = metadata?.optString("sourceDeviceName")?.takeIf { it.isNotEmpty() },
        problems = problems.distinct(),
    )
}

/** Opens `messages.xml` of a package, or the file itself for plain XML, for a second (restore) pass. */
fun openMessagesStream(open: () -> InputStream, kind: BackupKind): InputStream {
    val input = BufferedInputStream(open())
    if (kind == BackupKind.PLAIN_XML) return input
    val zip = ZipInputStream(input)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == BackupPackage.MESSAGES) return zip
    }
    zip.close()
    error("messages.xml not found")
}

// ---- helpers ------------------------------------------------------------------------------

internal class RecordStats {
    var count = 0
    var incoming = 0
    var outgoing = 0
    var oldest: Long? = null
    var newest: Long? = null

    fun add(r: BackupRecord) {
        count++
        when (r.type.direction) {
            SmsDirection.INCOMING -> incoming++
            SmsDirection.OUTGOING -> outgoing++
            SmsDirection.UNKNOWN -> Unit
        }
        oldest = minOf(oldest ?: r.date, r.date)
        newest = maxOf(newest ?: r.date, r.date)
    }

    fun toInfo(
        kind: BackupKind,
        reader: SmsXmlReader?,
        checksumsValid: Boolean?,
        createdAt: Long?,
        deviceName: String?,
        problems: List<String>,
    ) = BackupInfo(
        kind, count, incoming, outgoing, oldest, newest, createdAt, deviceName, checksumsValid,
        reader?.skippedInvalid ?: 0, reader?.skippedUnsupported ?: 0, problems,
    )
}

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

private fun InputStream.drain() {
    val buffer = ByteArray(16 * 1024)
    while (read(buffer) >= 0) Unit
}

private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
}

private class NonClosingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
    override fun close() = Unit
}

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}
