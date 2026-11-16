package tracker.core

import java.time.LocalDate

/** Money as integer paise. Never floats. */
@JvmInline
value class Paise(val value: Long) : Comparable<Paise> {
    operator fun plus(o: Paise) = Paise(value + o.value)
    operator fun minus(o: Paise) = Paise(value - o.value)
    override fun compareTo(other: Paise) = value.compareTo(other.value)
    override fun toString() = "%s%d.%02d".format(if (value < 0) "-" else "", Math.abs(value) / 100, Math.abs(value) % 100)

    companion object {
        val ZERO = Paise(0)
        fun rupees(r: Long) = Paise(r * 100)
    }
}

/** Indian digit grouping for display, e.g. 12550050 paise -> "1,25,500.50". Integer maths only. */
fun Paise.formatInr(): String {
    val abs = Math.abs(value)
    val rupees = (abs / 100).toString()
    val paise = (abs % 100).toString().padStart(2, '0')
    val grouped = if (rupees.length <= 3) rupees else {
        val head = rupees.dropLast(3)
        val tail = rupees.takeLast(3)
        val parts = ArrayList<String>()
        var rest = head
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        parts.joinToString(",") + "," + tail
    }
    return (if (value < 0) "-" else "") + grouped + "." + paise
}

enum class TxnType { PURCHASE, FEE, PAYMENT, REFUND }

/**
 * PENDING    authorisation seen, not yet settled
 * POSTED     counts toward the balance
 * REVERSED   cancelled by a reversal/refund link; ignored in maths
 * ADJUSTMENT explicit, user-confirmed correction row (never automatic)
 */
enum class TxnState { PENDING, POSTED, REVERSED, ADJUSTMENT }

data class CardConfig(
    val id: String,
    val issuer: String,
    val last4: String,
    val statementDay: Int,
    val dueDay: Int,
)

/**
 * [amount] is always positive; [type] gives the direction.
 * ADJUSTMENT rows use a signed amount (positive increases what is owed).
 */
data class Txn(
    val id: String,
    val cardId: String,
    val date: LocalDate,
    val amount: Paise,
    val type: TxnType,
    val state: TxnState,
    val merchant: String? = null,
    val ref: String? = null,
    val linkedTo: String? = null,
    /** Hash of the SMS this row came from (never the SMS text itself). Null for manual rows. */
    val sourceHash: String? = null,
    /** Free-text reason, used for ADJUSTMENT and manually classified rows. */
    val note: String? = null,
)

/** The bank's own figure for a closed cycle. The only number the bank commits to. */
data class Statement(
    val cardId: String,
    val statementDate: LocalDate,
    val totalDue: Paise,
    val minDue: Paise? = null,
    val dueDate: LocalDate? = null,
)
