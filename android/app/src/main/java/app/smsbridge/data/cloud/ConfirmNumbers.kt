package app.smsbridge.data.cloud

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Number matching (the fallback when a QR code cannot be scanned). The phone picks a random
 * [newSalt] only after the browser registered its keys; the browser shows
 * number(salt, its own key fingerprint) and the phone offers number(salt, the keys the server
 * listed) among two decoys. A server that swapped keys would have had to guess the salt in
 * advance, so its keys match only by chance (1 in 90). web/lib/confirm.ts computes the same.
 */
object ConfirmNumbers {
    private val random = SecureRandom()
    private val domain = "sms-bridge confirm v1".toByteArray()

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** 10..99 */
    fun number(salt: ByteArray, fingerprint: ByteArray): Int {
        val d = MessageDigest.getInstance("SHA-256").digest(domain + salt + fingerprint)
        val n = ((d[0].toInt() and 0x7F) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        return 10 + n % 90
    }

    /** The right number plus two different decoys, in random order. */
    fun options(correct: Int): List<Int> {
        val set = linkedSetOf(correct)
        while (set.size < 3) set += 10 + random.nextInt(90)
        return set.shuffled(random)
    }
}
