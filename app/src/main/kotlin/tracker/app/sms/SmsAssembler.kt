package tracker.app.sms

import tracker.core.RawSms

/** One PDU of an incoming SMS, reduced to what the tracker needs. */
data class SmsPart(val address: String?, val body: String?, val timestampMs: Long)

/** Pure helper (unit-tested): joins the parts of multi-part messages, one [RawSms] per sender. */
object SmsAssembler {
    fun assemble(parts: List<SmsPart>): List<RawSms> {
        val bySender = LinkedHashMap<String, MutableList<SmsPart>>()
        for (part in parts) {
            val address = part.address?.trim()
            if (address.isNullOrEmpty()) continue
            bySender.getOrPut(address) { ArrayList() }.add(part)
        }
        return bySender.map { (address, list) ->
            RawSms(
                sender = address,
                body = list.joinToString(separator = "") { it.body ?: "" },
                // The service-centre timestamp of the first part. The inbox stores the same value
                // in its date_sent column, which is what the backfill uses, so both paths hash alike.
                receivedAtMs = list.first().timestampMs,
            )
        }.filter { it.body.isNotEmpty() }
    }
}
