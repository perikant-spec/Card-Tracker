package tracker.core

import java.time.Instant

/** What a parser extracts from one SMS, before it becomes a ledger row. */
data class ParsedDraft(
    val cardLast4: String,
    val type: TxnType,
    val isAuthorisation: Boolean,
    val amount: Paise,
    val merchant: String?,
    val ref: String?,
    val receivedAt: Instant,
    val statement: StatementDraft? = null,
)

data class StatementDraft(val totalDue: Paise, val minDue: Paise?, val dueDateText: String?)

sealed interface ParseResult {
    data class Parsed(val draft: ParsedDraft) : ParseResult
    /** OTPs, promos, balance enquiries: deliberately dropped and never stored. */
    data class Ignored(val reason: String) : ParseResult
    /** Known sender but unrecognised format. Goes to the review queue, never dropped silently. */
    data class Unparsed(val sender: String) : ParseResult
}

/** One implementation per issuer, written against real fixtures. */
interface IssuerParser {
    val issuer: String
    fun handles(sender: String): Boolean
    fun parse(sender: String, body: String, receivedAt: Instant): ParseResult
}

class SmsRouter(private val parsers: List<IssuerParser>) {
    /** Returns null for senders not on any allowlist: those messages are never read further. */
    fun route(sender: String, body: String, receivedAt: Instant): ParseResult? =
        parsers.firstOrNull { it.handles(sender) }?.parse(sender, body, receivedAt)
}
