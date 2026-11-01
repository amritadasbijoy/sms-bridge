package app.smsbridge.core.model

import java.util.Locale

/**
 * Device-independent key for an SMS address, used for conversation grouping and dedup.
 *
 * Alphanumeric sender ids are compared case-insensitively. Numeric addresses are reduced to
 * their last 10 digits so "+880 1711-000000" and "01711000000" match. Missing addresses map to "".
 */
object AddressKey {
    fun of(address: String?): String {
        val trimmed = address?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed.any { it.isLetter() }) return trimmed.lowercase(Locale.ROOT)
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}
