package tracker.core

enum class CardStatus { NO_ANCHOR, RECONCILED, VARIANCE, STALE }

data class CardBalance(
    val cardId: String,
    val billed: Paise,
    val unbilled: Paise,
    /** Authorisations not yet settled. Shown separately, not in the headline. */
    val pending: Paise,
) {
    val outstanding: Paise get() = billed + unbilled
    val worstCase: Paise get() = billed + unbilled + pending
}

object Ledger {

    private fun sum(rows: List<Txn>): Paise = rows.fold(Paise.ZERO) { a, t -> a + t.amount }

    /**
     * Rows dated on or before the statement date belong to that statement.
     *
     * Billed   = statement total due - payments after the statement date
     *            + negative adjustments (credits the user confirmed).
     * Unbilled = purchases + fees after the statement date - refunds
     *            + positive adjustments (extra charges the user confirmed).
     *
     * A negative billed value means overpayment; it is kept, not clamped.
     * REVERSED rows are ignored. PENDING rows only feed [CardBalance.pending].
     */
    fun balance(statement: Statement, txns: List<Txn>): CardBalance {
        val after = txns.filter { it.cardId == statement.cardId && it.date > statement.statementDate }
        val posted = after.filter { it.state == TxnState.POSTED }
        val adjustments = after.filter { it.state == TxnState.ADJUSTMENT }

        val paid = sum(posted.filter { it.type == TxnType.PAYMENT })
        val charges = sum(posted.filter { it.type == TxnType.PURCHASE || it.type == TxnType.FEE })
        val refunds = sum(posted.filter { it.type == TxnType.REFUND })
        val adjDown = sum(adjustments.filter { it.amount.value < 0 })
        val adjUp = sum(adjustments.filter { it.amount.value >= 0 })

        val billed = statement.totalDue - paid + adjDown
        val unbilled = charges - refunds + adjUp
        val pending = sum(after.filter { it.state == TxnState.PENDING && it.type == TxnType.PURCHASE })

        return CardBalance(statement.cardId, billed, unbilled, pending)
    }

    /**
     * No confirmed statement yet, so there is no bank figure to anchor on. Everything the
     * ledger knows is reported as unbilled (charges - refunds - payments + adjustments) and
     * billed is zero. The caller must show this with a NO_ANCHOR warning: it is not a balance
     * the bank has agreed to, only the net of the messages seen so far.
     */
    fun balanceWithoutAnchor(cardId: String, txns: List<Txn>): CardBalance {
        val mine = txns.filter { it.cardId == cardId }
        val posted = mine.filter { it.state == TxnState.POSTED }
        val charges = sum(posted.filter { it.type == TxnType.PURCHASE || it.type == TxnType.FEE })
        val credits = sum(posted.filter { it.type == TxnType.REFUND || it.type == TxnType.PAYMENT })
        val adjustments = sum(mine.filter { it.state == TxnState.ADJUSTMENT })
        val pending = sum(mine.filter { it.state == TxnState.PENDING && it.type == TxnType.PURCHASE })
        return CardBalance(cardId, Paise.ZERO, charges - credits + adjustments, pending)
    }
}
