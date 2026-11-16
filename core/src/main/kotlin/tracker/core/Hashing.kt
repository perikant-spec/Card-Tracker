package tracker.core

import java.security.MessageDigest

object Hashing {
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun normSender(sender: String) = sender.trim().uppercase()
    private fun normBody(body: String) = body.trim().replace(Regex("\\s+"), " ")

    /** Identity of one delivered message: sender + timestamp + text. Re-importing the same inbox is a no-op. */
    fun smsHash(sms: RawSms): String =
        sha256Hex("v1\n" + normSender(sms.sender) + "\n" + sms.receivedAtMs + "\n" + normBody(sms.body))

    /** Identity ignoring the timestamp; used only to flag possible duplicates for review. */
    fun bodyHash(sms: RawSms): String =
        sha256Hex("b1\n" + normSender(sms.sender) + "\n" + normBody(sms.body))
}
