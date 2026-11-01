package app.smsbridge.data.backup

import android.util.JsonWriter
import android.util.Xml
import app.smsbridge.core.database.entity.SmsMessageEntity
import app.smsbridge.core.model.SmsType
import java.io.InputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser

/**
 * A message as stored in a backup file. Mirrors the attributes of the widely used
 * `<smses><sms …/></smses>` layout so files from other SMS backup tools can be imported.
 * `toString()` is redacted.
 */
data class BackupRecord(
    val address: String?,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: SmsType,
    val read: Boolean,
    val seen: Boolean,
    val status: Int?,
    val subscriptionId: Int?,
    /** SMS Bridge archive id, present only in SMS Bridge backups. */
    val archiveId: String?,
    /** Device that originally captured the message, present only in SMS Bridge backups. */
    val sourceDeviceId: String?,
) {
    override fun toString(): String = "BackupRecord(type=$type, date=$date, bodyLength=${body.length})"

    companion object {
        fun from(entity: SmsMessageEntity) = BackupRecord(
            address = entity.address,
            body = entity.body,
            date = entity.date,
            dateSent = entity.dateSent,
            type = entity.type,
            read = entity.read,
            seen = entity.seen,
            status = entity.status,
            subscriptionId = entity.subscriptionId,
            archiveId = entity.id,
            sourceDeviceId = entity.sourceDeviceId,
        )
    }
}

/**
 * Streaming writer for `messages.xml`. Hand-written instead of XmlSerializer so every string
 * survives a round trip: newlines and tabs inside attributes are written as character
 * references (parsers would otherwise normalise them to spaces), and control characters that
 * XML 1.0 forbids are written as numeric references, as other SMS backup tools do.
 */
class SmsXmlWriter(private val out: Writer) {
    private val readable = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

    fun begin(count: Int, backupId: String, createdAt: Long) {
        out.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        out.write(
            "<smses count=\"$count\" backup_set=\"${escape(backupId)}\" backup_date=\"$createdAt\" " +
                "type=\"full\" smsbridge_format=\"${BackupPackage.FORMAT_VERSION}\">\n",
        )
    }

    fun write(record: BackupRecord) {
        out.write("  <sms protocol=\"0\"")
        attr("address", record.address ?: "null")
        attr("date", record.date.toString())
        attr("type", record.type.providerValue.toString())
        attr("subject", "null")
        attr("body", record.body)
        attr("toa", "null")
        attr("sc_toa", "null")
        attr("service_center", "null")
        attr("read", if (record.read) "1" else "0")
        attr("status", (record.status ?: -1).toString())
        attr("locked", "0")
        attr("date_sent", record.dateSent.toString())
        attr("sub_id", (record.subscriptionId ?: -1).toString())
        attr("readable_date", readable.format(Instant.ofEpochMilli(record.date)))
        attr("contact_name", "(Unknown)")
        attr("seen", if (record.seen) "1" else "0")
        record.archiveId?.let { attr("sb_id", it) }
        record.sourceDeviceId?.let { attr("sb_source_device", it) }
        out.write(" />\n")
    }

    fun end() {
        out.write("</smses>\n")
    }

    private fun attr(name: String, value: String) {
        out.write(" ")
        out.write(name)
        out.write("=\"")
        out.write(escape(value))
        out.write("\"")
    }

    companion object {
        fun escape(value: String): String {
            if (value.none { it in "&<>\"'" || it < ' ' }) return value
            val sb = StringBuilder(value.length + 16)
            for (c in value) {
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' -> sb.append("&quot;")
                    c == '\'' -> sb.append("&apos;")
                    c < ' ' -> sb.append("&#").append(c.code).append(';')
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

/** Root `<smses>` attributes. */
data class SmsXmlHeader(val declaredCount: Int?, val backupId: String?, val backupDate: Long?, val smsBridgeFormat: Int?)

/**
 * Pull-parses `<sms>` elements one at a time, so files of any size use constant memory.
 * `<mms>` and unknown elements are skipped and counted in [skippedUnsupported].
 * Rows without a usable `date` or `type` are skipped and counted in [skippedInvalid].
 */
class SmsXmlReader(input: InputStream) : Iterator<BackupRecord> {
    private val parser: XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }
    private var next: BackupRecord? = null
    private var finished = false

    var header: SmsXmlHeader? = null
        private set
    var skippedUnsupported = 0
        private set
    var skippedInvalid = 0
        private set

    init {
        advance()
    }

    override fun hasNext(): Boolean = next != null

    override fun next(): BackupRecord {
        val current = next ?: throw NoSuchElementException()
        advance()
        return current
    }

    private fun advance() {
        next = null
        while (!finished) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> finished = true
                XmlPullParser.START_TAG -> when (parser.name) {
                    "smses" -> header = SmsXmlHeader(
                        declaredCount = attr("count")?.toIntOrNull(),
                        backupId = attr("backup_set"),
                        backupDate = attr("backup_date")?.toLongOrNull(),
                        smsBridgeFormat = attr("smsbridge_format")?.toIntOrNull(),
                    )
                    "sms" -> {
                        val record = parseSms()
                        if (record == null) skippedInvalid++ else {
                            next = record
                            return
                        }
                    }
                    // Only direct children of <smses> count (an <mms> has nested <parts>/<part>).
                    else -> if (parser.depth == 2) skippedUnsupported++
                }
            }
        }
    }

    private fun parseSms(): BackupRecord? {
        val date = attr("date")?.toLongOrNull() ?: return null
        val type = SmsType.fromProvider(attr("type")?.toIntOrNull())
        if (type == SmsType.UNKNOWN) return null
        val read = attr("read") == "1"
        return BackupRecord(
            address = attr("address")?.trim()?.takeIf { it.isNotEmpty() },
            body = attr("body").orEmpty(),
            date = date,
            dateSent = attr("date_sent")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            type = type,
            read = read,
            seen = attr("seen")?.let { it == "1" } ?: read,
            status = attr("status")?.toIntOrNull()?.takeIf { it >= 0 },
            subscriptionId = attr("sub_id")?.toIntOrNull()?.takeIf { it >= 0 },
            archiveId = attr("sb_id"),
            sourceDeviceId = attr("sb_source_device"),
        )
    }

    /** Attribute value, with the literal string "null" (used by other tools) treated as absent. */
    private fun attr(name: String): String? = parser.getAttributeValue(null, name)?.takeIf { it != "null" }
}

/** Readable JSON export (not a restore format). */
class SmsJsonWriter(out: Writer) {
    private val json = JsonWriter(out).apply { setIndent("  ") }

    fun begin(count: Int, createdAt: Long, deviceId: String) {
        json.beginObject()
        json.name("format").value("sms-bridge-json")
        json.name("formatVersion").value(BackupPackage.FORMAT_VERSION.toLong())
        json.name("exportedAt").value(Instant.ofEpochMilli(createdAt).toString())
        json.name("sourceDeviceId").value(deviceId)
        json.name("count").value(count.toLong())
        json.name("messages").beginArray()
    }

    fun write(r: BackupRecord) {
        json.beginObject()
        json.name("id").value(r.archiveId)
        json.name("address").value(r.address)
        json.name("body").value(r.body)
        json.name("date").value(r.date)
        json.name("dateIso").value(Instant.ofEpochMilli(r.date).toString())
        json.name("dateSent").value(r.dateSent)
        json.name("type").value(r.type.name)
        json.name("read").value(r.read)
        json.name("seen").value(r.seen)
        json.name("subscriptionId").value(r.subscriptionId?.toLong())
        json.name("status").value(r.status?.toLong())
        json.name("sourceDeviceId").value(r.sourceDeviceId)
        json.endObject()
    }

    fun end() {
        json.endArray()
        json.endObject()
        json.flush()
    }
}

/**
 * Spreadsheet-friendly CSV export (RFC 4180, UTF-8 with BOM so Excel shows non-Latin text).
 * Cells starting with = + - @ get a leading apostrophe to block spreadsheet formula injection;
 * this is a viewing format, not a restore format.
 */
class SmsCsvWriter(private val out: Writer) {
    fun begin() {
        out.write("\uFEFF")
        out.write("id,address,date_iso,date,date_sent,type,read,subscription_id,body\r\n")
    }

    fun write(r: BackupRecord) {
        val cells = listOf(
            r.archiveId.orEmpty(),
            r.address.orEmpty(),
            Instant.ofEpochMilli(r.date).toString(),
            r.date.toString(),
            r.dateSent.toString(),
            r.type.name,
            if (r.read) "1" else "0",
            r.subscriptionId?.toString().orEmpty(),
            r.body,
        )
        out.write(cells.joinToString(",") { cell(it) })
        out.write("\r\n")
    }

    companion object {
        fun cell(raw: String): String {
            val value = if (raw.isNotEmpty() && raw[0] in "=+-@") "'$raw" else raw
            return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
        }
    }
}
