package tracker.app.ui

import tracker.core.CardConfig
import tracker.core.CardStatus
import tracker.core.DashboardSummary
import tracker.core.Paise
import tracker.core.ReviewKind
import tracker.core.Txn
import tracker.core.TxnState
import tracker.core.TxnType
import tracker.core.formatInr
import tracker.core.parsers.SmsText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure display/parse helpers (unit-tested). No arithmetic on money happens here. */
object Format {
    private val SHOW: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

    /** "₹1,25,000.50", "-₹2,000.00". */
    fun inr(amount: Paise): String =
        if (amount.value < 0) "-₹" + Paise(-amount.value).formatInr() else "₹" + amount.formatInr()

    fun date(date: LocalDate?): String = date?.format(SHOW) ?: "—"

    /** Text for a date field, in the day-first form the parser reads back. */
    fun dateInput(date: LocalDate?): String = date?.format(INPUT) ?: ""

    /** dd-mm-yyyy, dd/mm/yy, dd-Mon-yyyy ... Null if not a real date. */
    fun parseDate(text: String): LocalDate? = SmsText.parseDate(text.trim())

    /**
     * "1,250.50", "₹ 1250", "Rs.500" -> paise. A leading minus is accepted only when [signed].
     * Null for anything else; never rounds and never uses floating point.
     */
    fun parseMoney(text: String, signed: Boolean = false): Paise? {
        var t = text.trim()
        var negative = false
        if (t.startsWith("-")) {
            if (!signed) return null
            negative = true
            t = t.substring(1).trim()
        }
        t = t.removePrefix("₹").removePrefix("Rs.").removePrefix("Rs").removePrefix("INR").trim()
        val parsed = SmsText.parseAmount(t) ?: return null
        return if (negative) Paise(-parsed.value) else parsed
    }

    fun cardTitle(card: CardConfig): String {
        val name = card.issuer.lowercase().replaceFirstChar { it.uppercase() }
        return if (card.last4.isEmpty()) "$name (card digits not set)" else "$name •• ${card.last4}"
    }

    fun dayOfMonth(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..31 }
}

object StatusUi {
    fun label(status: CardStatus, variance: Paise?): String = when (status) {
        CardStatus.RECONCILED -> "Reconciled"
        CardStatus.VARIANCE -> if (variance != null) "Variance " + Format.inr(variance) else "Variance"
        CardStatus.STALE -> "Stale"
        CardStatus.NO_ANCHOR -> "No anchor"
    }

    /** The confidence banner: how far the big number can be trusted, driven by the worst card. */
    fun banner(summary: DashboardSummary): String {
        val affected = summary.cards.filter { it.status == summary.worstStatus }.joinToString(", ") {
            it.card.issuer.lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        return when (summary.worstStatus) {
            CardStatus.RECONCILED -> "All cards reconcile with their latest statement."
            CardStatus.VARIANCE -> "Statement and ledger disagree for: $affected. The total may be off by the variance shown."
            CardStatus.STALE -> "Statement is out of date for: $affected. Enter or confirm the latest statement."
            CardStatus.NO_ANCHOR -> "No statement entered for: $affected. Their figures are only the net of messages seen so far."
        }
    }

    fun stateLabel(txn: Txn): String = when (txn.state) {
        TxnState.PENDING -> "Pending"
        TxnState.POSTED -> "Posted"
        TxnState.REVERSED -> "Reversed"
        TxnState.ADJUSTMENT -> "Adjustment"
    }

    fun typeLabel(type: TxnType): String = when (type) {
        TxnType.PURCHASE -> "Purchase"
        TxnType.FEE -> "Fee"
        TxnType.PAYMENT -> "Payment"
        TxnType.REFUND -> "Refund"
    }

    /** Signed the way it affects what is owed: charges positive, payments/refunds negative. */
    fun signedAmount(txn: Txn): String = when {
        txn.state == TxnState.ADJUSTMENT -> Format.inr(txn.amount)
        txn.type == TxnType.PAYMENT || txn.type == TxnType.REFUND -> Format.inr(Paise(-txn.amount.value))
        else -> Format.inr(txn.amount)
    }

    fun kindLabel(kind: ReviewKind): String = when (kind) {
        ReviewKind.UNPARSED -> "Unrecognised message"
        ReviewKind.AMBIGUOUS_MATCH -> "Ambiguous match"
        ReviewKind.AMBIGUOUS_REFUND -> "Possible double credit"
        ReviewKind.POSSIBLE_DUPLICATE -> "Possible duplicate"
        ReviewKind.STALE_PENDING -> "Stale authorisation"
    }
}
