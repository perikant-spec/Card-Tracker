package tracker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcilerTest {
    private val prev = Statement("hdfc", LocalDate.of(2026, 9, 15), Paise.rupees(8_000))
    private val curr = Statement("hdfc", LocalDate.of(2026, 10, 15), Paise.rupees(10_000))

    private fun t(day: Int, month: Int, r: Long, type: TxnType) =
        Txn("$month-$day-$r", "hdfc", LocalDate.of(2026, month, day), Paise.rupees(r), type, TxnState.POSTED)

    @Test fun `matching ledger reconciles to zero variance`() {
        // 8000 owed, paid 8000, spent 10000 => 10000
        val txns = listOf(
            t(20, 9, 8_000, TxnType.PAYMENT),
            t(25, 9, 6_000, TxnType.PURCHASE),
            t(5, 10, 4_000, TxnType.PURCHASE),
        )
        val r = Reconciler.reconcile(prev, curr, txns)
        assertEquals(Paise.ZERO, r.variance)
        assertEquals(CardStatus.RECONCILED, r.status)
    }

    @Test fun `missed fee shows as positive variance`() {
        val txns = listOf(
            t(20, 9, 8_000, TxnType.PAYMENT),
            t(25, 9, 6_000, TxnType.PURCHASE),
            t(5, 10, 3_500, TxnType.PURCHASE), // 500 short: bank charged something we never saw
        )
        val r = Reconciler.reconcile(prev, curr, txns)
        assertEquals(Paise.rupees(500), r.variance)
        assertEquals(CardStatus.VARIANCE, r.status)
    }

    @Test fun `rows outside the cycle window are excluded`() {
        val txns = listOf(
            t(20, 9, 8_000, TxnType.PAYMENT),
            t(25, 9, 10_000, TxnType.PURCHASE),
            t(1, 9, 99_999, TxnType.PURCHASE),   // before previous statement
            t(20, 10, 99_999, TxnType.PURCHASE), // after this statement
        )
        assertEquals(Paise.ZERO, Reconciler.reconcile(prev, curr, txns).variance)
    }

    @Test fun `explicit adjustment resolves the variance it was added for`() {
        val txns = listOf(
            t(20, 9, 8_000, TxnType.PAYMENT),
            t(25, 9, 6_000, TxnType.PURCHASE),
            t(5, 10, 3_500, TxnType.PURCHASE),
            Txn("adj", "hdfc", LocalDate.of(2026, 10, 10), Paise.rupees(500), TxnType.FEE, TxnState.ADJUSTMENT),
        )
        assertEquals(CardStatus.RECONCILED, Reconciler.reconcile(prev, curr, txns).status)
    }

    @Test fun `first statement uses zero opening`() {
        val first = Statement("hdfc", LocalDate.of(2026, 10, 15), Paise.rupees(3_000))
        val r = Reconciler.reconcile(null, first, listOf(t(5, 10, 3_000, TxnType.PURCHASE)))
        assertEquals(Paise.ZERO, r.variance)
    }

    @Test fun `reconcile never mutates the input list`() {
        val txns = listOf(t(5, 10, 100, TxnType.PURCHASE))
        val copy = txns.toList()
        Reconciler.reconcile(prev, curr, txns)
        assertEquals(copy, txns)
    }
}
