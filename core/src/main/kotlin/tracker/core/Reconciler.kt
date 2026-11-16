package tracker.core

data class ReconResult(
    val expected: Paise,
    val actual: Paise,
    /** actual - expected. Positive: bank says more is owed than the ledger explains. */
    val variance: Paise,
) {
    val status: CardStatus get() = if (variance == Paise.ZERO) CardStatus.RECONCILED else CardStatus.VARIANCE
}

object Reconciler {

    /**
     * Compares the bank's closing figure for a cycle with what the ledger explains:
     *   expected = previous total due
     *            - payments/refunds in (previous statement date, this statement date]
     *            + purchases/fees in the same window
     *            + explicit user adjustments in the same window (signed)
     * Never mutates the ledger. Interest, fees with no SMS, or missed messages show up
     * as variance; the user resolves them by finding the row or adding an ADJUSTMENT.
     */
    fun reconcile(previous: Statement?, current: Statement, txns: List<Txn>): ReconResult {
        val from = previous?.statementDate
        val window = txns.filter {
            it.cardId == current.cardId &&
                (it.state == TxnState.POSTED || it.state == TxnState.ADJUSTMENT) &&
                it.date <= current.statementDate &&
                (from == null || it.date > from)
        }
        val net = window.fold(Paise.ZERO) { acc, t ->
            if (t.state == TxnState.ADJUSTMENT) {
                acc + t.amount
            } else when (t.type) {
                TxnType.PURCHASE, TxnType.FEE -> acc + t.amount
                TxnType.PAYMENT, TxnType.REFUND -> acc - t.amount
            }
        }
        val expected = (previous?.totalDue ?: Paise.ZERO) + net
        return ReconResult(expected, current.totalDue, current.totalDue - expected)
    }
}
