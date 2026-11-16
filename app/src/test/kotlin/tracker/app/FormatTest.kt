package tracker.app

import tracker.app.ui.Format
import tracker.app.ui.StatusUi
import tracker.core.CardBalance
import tracker.core.CardConfig
import tracker.core.CardStatus
import tracker.core.CardSummary
import tracker.core.DashboardSummary
import tracker.core.Paise
import tracker.core.Txn
import tracker.core.TxnState
import tracker.core.TxnType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI mapping logic that needs no emulator. */
class FormatTest {
    @Test fun `money is shown with indian grouping and a leading minus`() {
        assertEquals("₹1,25,000.50", Format.inr(Paise(12_500_050)))
        assertEquals("-₹2,000.00", Format.inr(Paise.rupees(-2_000)))
        assertEquals("₹0.00", Format.inr(Paise.ZERO))
    }

    @Test fun `money input is parsed exactly or rejected`() {
        assertEquals(Paise(125_050), Format.parseMoney(" 1,250.50 "))
        assertEquals(Paise(125_000), Format.parseMoney("₹ 1250"))
        assertEquals(Paise(50_000), Format.parseMoney("Rs.500"))
        assertEquals(Paise(-35_000), Format.parseMoney("-350", signed = true))
        assertNull(Format.parseMoney("-350"))
        assertNull(Format.parseMoney("12.345"))
        assertNull(Format.parseMoney("abc"))
        assertNull(Format.parseMoney(""))
    }

    @Test fun `dates round trip through the input format`() {
        val d = LocalDate.of(2026, 12, 5)
        assertEquals("05-12-2026", Format.dateInput(d))
        assertEquals(d, Format.parseDate(Format.dateInput(d)))
        assertEquals("05 Dec 2026", Format.date(d))
        assertEquals("—", Format.date(null))
        assertEquals("", Format.dateInput(null))
        assertNull(Format.parseDate("31-02-2026"))
        assertEquals(15, Format.dayOfMonth(" 15 "))
        assertNull(Format.dayOfMonth("32"))
        assertNull(Format.dayOfMonth("x"))
    }

    @Test fun `card title says when digits are missing`() {
        assertEquals("Hdfc •• 1234", Format.cardTitle(CardConfig("hdfc", "HDFC", "1234", 15, 5)))
        assertTrue(Format.cardTitle(CardConfig("kiwi", "KIWI", "", 1, 20)).contains("not set"))
    }

    private fun summary(id: String, status: CardStatus, variance: Paise? = null) = CardSummary(
        card = CardConfig(id, id.uppercase(), "1234", 1, 20),
        balance = CardBalance(id, Paise.ZERO, Paise.ZERO, Paise.ZERO),
        status = status, variance = variance, latestStatement = null, dueDate = null, openProposals = 0,
    )

    private fun dashboard(vararg cards: CardSummary, worst: CardStatus) =
        DashboardSummary(cards.toList(), Paise.ZERO, Paise.ZERO, Paise.ZERO, Paise.ZERO, worst, 0)

    @Test fun `status badge and banner reflect the worst card`() {
        assertEquals("Reconciled", StatusUi.label(CardStatus.RECONCILED, null))
        assertEquals("Variance ₹350.00", StatusUi.label(CardStatus.VARIANCE, Paise.rupees(350)))
        assertEquals("Variance -₹350.00", StatusUi.label(CardStatus.VARIANCE, Paise.rupees(-350)))
        assertEquals("Stale", StatusUi.label(CardStatus.STALE, null))
        assertEquals("No anchor", StatusUi.label(CardStatus.NO_ANCHOR, null))

        val banner = StatusUi.banner(
            dashboard(summary("hdfc", CardStatus.RECONCILED), summary("kiwi", CardStatus.NO_ANCHOR), summary("sbi", CardStatus.STALE), worst = CardStatus.NO_ANCHOR),
        )
        assertTrue(banner.contains("Kiwi") && !banner.contains("Hdfc") && !banner.contains("Sbi"))
        assertTrue(StatusUi.banner(dashboard(summary("hdfc", CardStatus.RECONCILED), worst = CardStatus.RECONCILED)).contains("reconcile"))
    }

    @Test fun `amounts are signed by their effect on what is owed`() {
        val day = LocalDate.of(2026, 11, 15)
        fun t(type: TxnType, state: TxnState, paise: Long) = Txn("x", "hdfc", day, Paise(paise), type, state)
        assertEquals("₹500.00", StatusUi.signedAmount(t(TxnType.PURCHASE, TxnState.POSTED, 50_000)))
        assertEquals("-₹500.00", StatusUi.signedAmount(t(TxnType.PAYMENT, TxnState.POSTED, 50_000)))
        assertEquals("-₹500.00", StatusUi.signedAmount(t(TxnType.REFUND, TxnState.POSTED, 50_000)))
        assertEquals("-₹123.45", StatusUi.signedAmount(t(TxnType.FEE, TxnState.ADJUSTMENT, -12_345)))
    }
}
