package tracker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MatchingTest {
    private fun p(id: String, day: Int, r: Long, merchant: String, ref: String? = null) =
        Txn(id, "hdfc", LocalDate.of(2026, 10, day), Paise.rupees(r), TxnType.PURCHASE, TxnState.PENDING, merchant, ref)

    private fun settled(day: Int, r: Long, merchant: String, ref: String? = null) =
        Txn("s", "hdfc", LocalDate.of(2026, 10, day), Paise.rupees(r), TxnType.PURCHASE, TxnState.POSTED, merchant, ref)

    @Test fun `reference number wins`() {
        val res = PendingMatcher.match(settled(21, 500, "AMAZON", "R1"), listOf(p("a", 20, 500, "AMAZON", "R1")))
        assertEquals("a", assertIs<MatchResult.Matched>(res).pending.id)
    }

    @Test fun `amount merchant and window match when no ref`() {
        val res = PendingMatcher.match(settled(22, 500, "Amazon Pay", null), listOf(p("a", 20, 500, "amazon pay")))
        assertIs<MatchResult.Matched>(res)
    }

    @Test fun `two same-amount same-merchant pendings are ambiguous, not collapsed`() {
        val res = PendingMatcher.match(
            settled(21, 200, "Swiggy"),
            listOf(p("a", 20, 200, "Swiggy"), p("b", 20, 200, "Swiggy")),
        )
        assertEquals(2, assertIs<MatchResult.Ambiguous>(res).candidates.size)
    }

    @Test fun `different amount does not match`() {
        assertIs<MatchResult.None>(PendingMatcher.match(settled(21, 501, "Swiggy"), listOf(p("a", 20, 500, "Swiggy"))))
    }

    @Test fun `outside window does not match`() {
        assertIs<MatchResult.None>(PendingMatcher.match(settled(29, 500, "Swiggy"), listOf(p("a", 20, 500, "Swiggy"))))
    }

    @Test fun `other card is never matched`() {
        val other = p("a", 20, 500, "Swiggy").copy(cardId = "icici")
        assertIs<MatchResult.None>(PendingMatcher.match(settled(21, 500, "Swiggy"), listOf(other)))
    }
}
