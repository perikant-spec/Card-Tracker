package tracker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerTest {
    private val stmtDate = LocalDate.of(2026, 10, 15)
    private val stmt = Statement("hdfc", stmtDate, Paise.rupees(10_000))

    private fun txn(
        id: String, day: Int, rupees: Long, type: TxnType,
        state: TxnState = TxnState.POSTED, month: Int = 10, merchant: String? = null, ref: String? = null,
    ) = Txn(id, "hdfc", LocalDate.of(2026, month, day), Paise.rupees(rupees), type, state, merchant, ref)

    @Test fun `no activity after statement leaves billed equal to total due`() {
        val b = Ledger.balance(stmt, emptyList())
        assertEquals(Paise.rupees(10_000), b.billed)
        assertEquals(Paise.ZERO, b.unbilled)
    }

    @Test fun `purchases and fees after statement are unbilled`() {
        val b = Ledger.balance(stmt, listOf(
            txn("1", 20, 1_000, TxnType.PURCHASE),
            txn("2", 22, 118, TxnType.FEE),
        ))
        assertEquals(Paise.rupees(1_118), b.unbilled)
        assertEquals(Paise.rupees(11_118), b.outstanding)
    }

    @Test fun `payment reduces billed not unbilled`() {
        val b = Ledger.balance(stmt, listOf(
            txn("1", 20, 1_000, TxnType.PURCHASE),
            txn("2", 25, 4_000, TxnType.PAYMENT),
        ))
        assertEquals(Paise.rupees(6_000), b.billed)
        assertEquals(Paise.rupees(1_000), b.unbilled)
    }

    @Test fun `refund reduces unbilled`() {
        val b = Ledger.balance(stmt, listOf(
            txn("1", 20, 1_000, TxnType.PURCHASE),
            txn("2", 24, 400, TxnType.REFUND),
        ))
        assertEquals(Paise.rupees(600), b.unbilled)
    }

    @Test fun `reversed rows are ignored`() {
        val b = Ledger.balance(stmt, listOf(
            txn("1", 20, 1_000, TxnType.PURCHASE, TxnState.REVERSED),
            txn("2", 21, 500, TxnType.PURCHASE),
        ))
        assertEquals(Paise.rupees(500), b.unbilled)
    }

    @Test fun `pending is shown separately and excluded from headline`() {
        val b = Ledger.balance(stmt, listOf(
            txn("1", 20, 300, TxnType.PURCHASE, TxnState.PENDING),
        ))
        assertEquals(Paise.ZERO, b.unbilled)
        assertEquals(Paise.rupees(300), b.pending)
        assertEquals(Paise.rupees(10_300), b.worstCase)
    }

    @Test fun `rows on the statement date belong to that statement`() {
        val b = Ledger.balance(stmt, listOf(txn("1", 15, 999, TxnType.PURCHASE)))
        assertEquals(Paise.ZERO, b.unbilled)
    }

    @Test fun `overpayment is kept as negative billed, not clamped`() {
        val b = Ledger.balance(stmt, listOf(txn("1", 20, 12_000, TxnType.PAYMENT)))
        assertEquals(Paise.rupees(-2_000), b.billed)
    }

    @Test fun `other cards are not mixed in`() {
        val other = Txn("x", "icici", LocalDate.of(2026, 10, 20), Paise.rupees(5_000), TxnType.PURCHASE, TxnState.POSTED)
        assertEquals(Paise.ZERO, Ledger.balance(stmt, listOf(other)).unbilled)
    }

    @Test fun `explicit adjustments apply with their sign`() {
        val up = Txn("a1", "hdfc", LocalDate.of(2026, 10, 20), Paise.rupees(250), TxnType.FEE, TxnState.ADJUSTMENT)
        val down = Txn("a2", "hdfc", LocalDate.of(2026, 10, 21), Paise.rupees(-100), TxnType.FEE, TxnState.ADJUSTMENT)
        val b = Ledger.balance(stmt, listOf(up, down))
        assertEquals(Paise.rupees(250), b.unbilled)
        assertEquals(Paise.rupees(9_900), b.billed)
    }
}
