package tracker.core.parsers

import tracker.core.IssuerParser
import tracker.core.Paise
import tracker.core.ParseResult
import tracker.core.ParsedDraft
import tracker.core.SenderAllowlist
import tracker.core.SmsParser
import tracker.core.StatementDraft
import tracker.core.TxnType
import java.time.Instant
import java.time.LocalDate
import java.util.regex.Matcher
import java.util.regex.Pattern

/** What a template says the message is. This is the "field mapping" half of a template. */
enum class DraftKind {
    /** Card charge. POSTED unless the template is flagged as an authorisation hold. */
    SPEND,
    PAYMENT,
    /** Credit described as a refund. */
    REFUND,
    /** Credit described as the reversal of one earlier transaction. */
    REVERSAL,
    /** Fee, GST, interest, finance charge. */
    FEE,
    /** Statement notice: total due / minimum due / due date. Never applied automatically. */
    STATEMENT,
}

/**
 * One data-driven template: a name, a kind and a regex with named groups.
 *
 * The whole (whitespace-normalised) message must match; there is no partial matching.
 * Named groups used for the field mapping:
 *   amt      transaction amount            (required unless STATEMENT)
 *   last4    card digits                   (required)
 *   merchant merchant / fee description    (optional)
 *   date     transaction date              (optional; if present it must be a real date)
 *   ref      reference number              (optional)
 *   total, min, due   statement figures    (STATEMENT: total and last4 required)
 *
 * Placeholders, expanded before compiling, keep the patterns readable:
 *   {AMT} {TOTAL} {MIN}  amount into group amt / total / min
 *   {CARD}               card digits into group last4
 *   {CP}                 the issuer's "card phrase", e.g. (?:HDFC Bank )?(?:Credit )?Card
 *   {DATE} {DUE}         date into group date / due
 *   {TIME}               optional time of day after a date
 *   {MER}                merchant up to the end of the sentence
 *   {REF}                optional "Ref no 123456"
 *   {DEAR} {ALERT}       optional greeting / "Alert:" prefix
 *   {FEEWORDS}           fee, interest and tax wordings
 *   {TAIL}               anything after a word boundary (limit info, helpline text, ...)
 *   {END}                end of message, or a sentence end followed by anything
 */
class SmsTemplate(
    val name: String,
    val kind: DraftKind,
    pattern: String,
    cardPhrase: String,
    /** True only for wording that is explicitly a hold/pre-authorisation rather than a charge. */
    val authorisation: Boolean = false,
) {
    val expanded: String = expand(pattern, cardPhrase)
    private val compiled: Pattern = Pattern.compile(expanded, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    private val groups: Set<String> = GROUP_NAME.findAll(expanded).map { it.groupValues[1] }.toSet()

    private fun group(m: Matcher, name: String): String? = if (name in groups) m.group(name) else null

    /** Returns a draft only if every captured field is valid; otherwise null (no guessing). */
    fun match(text: String, issuer: String, receivedAt: Instant): ParsedDraft? {
        val m = compiled.matcher(text)
        if (!m.matches()) return null
        val last4 = group(m, "last4") ?: return null
        val merchant = cleanMerchant(group(m, "merchant"))
        val ref = group(m, "ref")

        var txnDate: LocalDate? = null
        val dateText = group(m, "date")
        if (dateText != null) txnDate = SmsText.parseDate(dateText) ?: return null

        if (kind == DraftKind.STATEMENT) {
            val total = SmsText.parseAmount(group(m, "total") ?: return null) ?: return null
            var min: Paise? = null
            val minText = group(m, "min")
            if (minText != null) min = SmsText.parseAmount(minText) ?: return null
            var due: LocalDate? = null
            val dueText = group(m, "due")
            if (dueText != null) due = SmsText.parseDate(dueText) ?: return null
            return ParsedDraft(
                cardLast4 = last4, type = TxnType.FEE, isAuthorisation = false, amount = total,
                merchant = null, ref = null, receivedAt = receivedAt,
                statement = StatementDraft(total, min, dueText, due),
                issuer = issuer, template = name,
            )
        }

        val amount = SmsText.parseAmount(group(m, "amt") ?: return null) ?: return null
        val type = when (kind) {
            DraftKind.SPEND -> TxnType.PURCHASE
            DraftKind.PAYMENT -> TxnType.PAYMENT
            DraftKind.REFUND, DraftKind.REVERSAL -> TxnType.REFUND
            DraftKind.FEE -> TxnType.FEE
            DraftKind.STATEMENT -> return null
        }
        return ParsedDraft(
            cardLast4 = last4, type = type,
            isAuthorisation = kind == DraftKind.SPEND && authorisation,
            amount = amount, merchant = merchant, ref = ref, receivedAt = receivedAt,
            issuer = issuer, txnDate = txnDate, isReversal = kind == DraftKind.REVERSAL, template = name,
        )
    }

    companion object {
        private val GROUP_NAME = Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>")

        const val FEE_WORDS =
            "late payment (?:fee|fees|charge|charges)|finance charges?|interest(?: charges?)?|" +
                "annual (?:fee|membership fee)|joining fee|renewal fee|over ?limit (?:fee|charges?)|" +
                "cash advance (?:fee|charges?)|fuel surcharge|forex mark-?up(?: fee)?|" +
                "processing fee|GST|IGST|CGST|SGST"

        private fun expand(pattern: String, cardPhrase: String): String = pattern
            .replace("{CP}", cardPhrase)
            .replace("{AMT}", SmsText.amount("amt"))
            .replace("{TOTAL}", SmsText.amount("total"))
            .replace("{MIN}", SmsText.amount("min"))
            .replace("{CARD}", SmsText.card("last4"))
            .replace("{DATE}", SmsText.date("date"))
            .replace("{DUE}", SmsText.date("due"))
            .replace("{TIME}", "(?:(?: at |[ :,]{1,2})[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?: ?[AP]M)?(?: IST)?)?")
            .replace("{MER}", "(?<merchant>(?:[^.;!]|[.;!](?=\\S))+?)")
            .replace(
                "{REF}",
                "(?:[.,;]? ?(?:Ref(?:erence)?(?: ?No)?|Txn ?(?:ID|No)|UPI Ref(?: ?No)?)\\.?[#:]? ?(?<ref>[A-Za-z0-9]{6,24})(?![A-Za-z0-9]))?",
            )
            .replace("{DEAR}", "(?:(?:Dear|Hi|Hello) [A-Za-z .]{1,40}?[,!:]? )?")
            .replace("{ALERT}", "(?:(?:Alert|Update)[!:]? ?)?")
            .replace("{FEEWORDS}", "(?:$FEE_WORDS)")
            .replace("{TAIL}", "(?:[\\s.,;!].*)?")
            .replace("{END}", "(?:[.;!](?:\\s.*)?)?")

        private val TRAILING = Regex("[\\s.,;:!-]+$")

        fun cleanMerchant(raw: String?): String? {
            if (raw == null) return null
            val t = raw.trim().replace(TRAILING, "")
            return if (t.isEmpty()) null else t.take(60)
        }
    }
}

/**
 * Messages that are deliberately dropped and never stored.
 * OTP detection runs BEFORE any template; the softer rules run only after no template matched,
 * so a real card alert is never lost just because its footer says "never share your OTP".
 */
object IgnoreRules {
    private const val OTP_WORD = "(?:OTP|one[- ]?time[- ]?(?:password|passcode|pin)|verification code|security code|secure code|passcode)"
    private val FLAGS = Pattern.CASE_INSENSITIVE

    /** "123456 is your OTP ...", "OTP is 123456", "OTP: 123456", "Use OTP 123456", "OTP for ... is 123456". */
    private val OTP_WITH_CODE = listOf(
        Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9]) is (?:your |the )?(?:[A-Za-z-]+ ){0,3}?$OTP_WORD\\b", FLAGS),
        Pattern.compile("\\b$OTP_WORD\\b(?: (?:is|for|code|number|no\\.?))? ?[-:]? ?(?<![0-9])[0-9]{4,8}(?![0-9,.]*[0-9])", FLAGS),
        Pattern.compile("\\b$OTP_WORD\\b[^.]{0,80}?\\bis:? ?(?<![0-9])[0-9]{4,8}(?![0-9])", FLAGS),
    )
    private val OTP_MENTION = Pattern.compile("\\b$OTP_WORD\\b", FLAGS)

    private val ACCOUNT_NUMBER = Pattern.compile("\\b(?:a/c|ac|acct|account)(?: ?no\\.?)? ?[xX*]*[0-9]{2,}", FLAGS)
    private val ACCOUNT_MOVE = Pattern.compile("\\b(?:debited|credited|withdrawn|deposited|transferred)\\b", FLAGS)
    private val CARD_ACCOUNT = Pattern.compile("\\bcard (?:a/c|ac|acct|account)\\b", FLAGS)

    private val PROMO = Pattern.compile(
        "\\b(?:T&C|T&Cs|offer valid|apply now|pre-?approved|limited period|click here|shop now|get up to|" +
            "flat [0-9]+% off|voucher|upgrade your|congratulations|eligible for)\\b",
        FLAGS,
    )
    private val MONEY_MOVEMENT = Pattern.compile(
        "\\b(?:spent|debited|charged|credited|received|refund(?:ed)?|revers(?:ed|al)|statement|levied)\\b", FLAGS,
    )
    private val REMINDER = Pattern.compile("\\b(?:reminder|overdue)\\b", FLAGS)

    fun isOtp(text: String): Boolean = OTP_WITH_CODE.any { it.matcher(text).find() }

    /** Reason to drop a message no template matched, or null to send it to the review queue. */
    fun afterTemplates(text: String): String? {
        if (OTP_MENTION.matcher(text).find()) return "otp"
        if (ACCOUNT_NUMBER.matcher(text).find() && ACCOUNT_MOVE.matcher(text).find() && !CARD_ACCOUNT.matcher(text).find()) {
            return "bank-account alert"
        }
        if (REMINDER.matcher(text).find()) return "reminder"
        if (PROMO.matcher(text).find() && !MONEY_MOVEMENT.matcher(text).find()) return "promo"
        return null
    }
}

/** Two drafts "agree" when everything that affects the ledger is identical. */
internal data class DraftKey(
    val last4: String,
    val type: TxnType,
    val amount: Paise,
    val authorisation: Boolean,
    val reversal: Boolean,
    val statement: StatementDraft?,
    val date: LocalDate?,
)

internal fun ParsedDraft.key() = DraftKey(cardLast4, type, amount, isAuthorisation, isReversal, statement?.copy(dueDateText = null), txnDate)

/** Issuer parser driven entirely by a list of [SmsTemplate]s and a user-editable sender list. */
class TemplateIssuerParser(
    override val issuer: String,
    private val templates: List<SmsTemplate>,
    private val senderPatterns: List<String>,
) : IssuerParser {

    override fun handles(sender: String): Boolean = SenderAllowlist.matches(senderPatterns, sender)

    override fun parse(sender: String, body: String, receivedAt: Instant): ParseResult {
        val text = SmsText.normalise(body)
        if (IgnoreRules.isOtp(text)) return ParseResult.Ignored("otp")

        val drafts = templates.mapNotNull { it.match(text, issuer, receivedAt) }
        if (drafts.isNotEmpty()) {
            // Words that mean the transaction did not happen, or (for a spend) that it is really a credit.
            if (FAILURE.matcher(text).find()) return ParseResult.Unparsed(sender)
            val first = drafts.first()
            if (first.type == TxnType.PURCHASE && CREDIT_WORDS.matcher(text).find()) return ParseResult.Unparsed(sender)
            // Two templates reading the same message differently is a doubt, not a parse.
            if (drafts.map { it.key() }.distinct().size > 1) return ParseResult.Unparsed(sender)
            return ParseResult.Parsed(first)
        }

        val reason = IgnoreRules.afterTemplates(text)
        return if (reason != null) ParseResult.Ignored(reason) else ParseResult.Unparsed(sender)
    }

    private companion object {
        val FAILURE: Pattern = Pattern.compile(
            "\\b(?:declined|failed|failure|unsuccessful|not successful|could not be|bounced|dishonou?red|rejected)\\b",
            Pattern.CASE_INSENSITIVE,
        )
        val CREDIT_WORDS: Pattern = Pattern.compile("\\b(?:reversed|reversal|refund(?:ed)?)\\b", Pattern.CASE_INSENSITIVE)
    }
}

/**
 * Tries every issuer whose user-editable sender list matches. Returns null when none does:
 * such a message is not parsed, not stored and not looked at again.
 */
class AllowlistRouter(private val parsers: List<IssuerParser>) : SmsParser {
    override fun route(sender: String, body: String, receivedAt: Instant): ParseResult? {
        val handlers = parsers.filter { it.handles(sender) }
        if (handlers.isEmpty()) return null
        val results = handlers.map { it.parse(sender, body, receivedAt) }
        val parsed = results.filterIsInstance<ParseResult.Parsed>()
        if (parsed.isNotEmpty()) {
            return if (parsed.map { it.draft.key() }.distinct().size == 1) parsed.first() else ParseResult.Unparsed(sender)
        }
        // "Unparsed" from one issuer outranks "Ignored" from another only if none parsed: keep it visible.
        return results.firstOrNull { it is ParseResult.Unparsed } ?: results.first()
    }
}
