package tracker.app

import tracker.app.ui.SetupChecklist
import tracker.core.CardBalance
import tracker.core.CardConfig
import tracker.core.CardStatus
import tracker.core.CardSummary
import tracker.core.DashboardSummary
import tracker.core.Paise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupChecklistTest {
    private fun card(id: String, last4: String, status: CardStatus) = CardSummary(
        card = CardConfig(id = id, issuer = id.uppercase(), last4 = last4, statementDay = 1, dueDay = 20),
        balance = CardBalance(id, Paise.ZERO, Paise.ZERO, Paise.ZERO),
        status = status,
        variance = null,
        latestStatement = null,
        dueDate = null,
        openProposals = 0,
    )

    private fun summary(vararg cards: CardSummary) = DashboardSummary(
        cards = cards.toList(),
        totalBilled = Paise.ZERO,
        totalUnbilled = Paise.ZERO,
        totalPending = Paise.ZERO,
        totalOutstanding = Paise.ZERO,
        worstStatus = CardStatus.NO_ANCHOR,
        openReviewCount = 0,
    )

    @Test fun `fresh install asks for SMS access and last four digits first`() {
        val steps = SetupChecklist.steps(summary(card("hdfc", "", CardStatus.NO_ANCHOR), card("axis", "", CardStatus.NO_ANCHOR)), smsGranted = false)
        // The statement step only applies once a card has its digits.
        assertEquals(2, steps.size)
        assertTrue(steps[0].contains("SMS"))
        assertTrue(steps[1].contains("2 card"))
    }

    @Test fun `cards with digits but no statement need an anchor`() {
        val steps = SetupChecklist.steps(
            summary(card("hdfc", "1234", CardStatus.NO_ANCHOR), card("axis", "5678", CardStatus.RECONCILED)),
            smsGranted = true,
        )
        assertEquals(1, steps.size)
        assertTrue(steps[0].contains("1 card"))
    }

    @Test fun `fully set up shows nothing`() {
        val steps = SetupChecklist.steps(summary(card("hdfc", "1234", CardStatus.RECONCILED)), smsGranted = true)
        assertTrue(steps.isEmpty())
    }
}
