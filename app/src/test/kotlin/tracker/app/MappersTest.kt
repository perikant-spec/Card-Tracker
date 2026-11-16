package tracker.app

import tracker.app.data.AllowlistEntity
import tracker.app.data.allowlistMap
import tracker.app.data.allowlistRows
import tracker.app.data.sortCards
import tracker.app.data.toEntity
import tracker.app.data.toModel
import tracker.core.CardConfig
import tracker.core.Paise
import tracker.core.ProcessedSms
import tracker.core.ReviewItem
import tracker.core.ReviewKind
import tracker.core.ReviewStatus
import tracker.core.Statement
import tracker.core.StatementProposal
import tracker.core.Txn
import tracker.core.TxnState
import tracker.core.TxnType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Room row <-> core model mapping. Runs on the JVM: the entities are plain data classes. */
class MappersTest {
    private val day = LocalDate.of(2026, 11, 15)

    private val txn = Txn(
        id = "t1", cardId = "hdfc", date = day, amount = Paise(-12_345), type = TxnType.FEE,
        state = TxnState.ADJUSTMENT, merchant = "Café", ref = "R1", linkedTo = "t0", sourceHash = "abc", note = "why",
    )

    @Test fun `txn round trip keeps signed paise and every field`() {
        val entity = txn.toEntity()
        assertEquals(-12_345L, entity.amountPaise)
        assertEquals(day.toEpochDay(), entity.dateEpochDay)
        assertEquals("ADJUSTMENT", entity.state)
        assertEquals(txn, entity.toModel())
        val bare = Txn("t2", "sbi", day, Paise(1), TxnType.PURCHASE, TxnState.PENDING)
        assertEquals(bare, bare.toEntity().toModel())
    }

    @Test fun `card statement proposal and processed round trips`() {
        val card = CardConfig("kiwi", "KIWI", "", 31, 20)
        assertEquals(card, card.toEntity().toModel())

        val full = Statement("hdfc", day, Paise(1_000_000), Paise(50_000), day.plusDays(20))
        assertEquals(full, full.toEntity().toModel())
        val minimal = Statement("hdfc", day, Paise(-5))
        assertEquals(minimal, minimal.toEntity().toModel())

        val proposal = StatementProposal("p1", "hdfc", day, Paise(9), null, day.plusDays(1), "hash", 42)
        assertEquals(proposal, proposal.toEntity().toModel())

        val processed = ProcessedSms("h", "b", 123)
        assertEquals(processed, processed.toEntity().toModel())
    }

    @Test fun `review item round trip including candidates and the proposed row`() {
        val item = ReviewItem(
            id = "r1", kind = ReviewKind.AMBIGUOUS_MATCH, status = ReviewStatus.OPEN, createdAtMs = 7,
            sender = "VM-TEST", body = "line one\nline two", receivedAtMs = 9, smsHash = "h", cardId = "hdfc",
            txnId = null, candidateIds = listOf("a", "b"), proposed = txn, reason = "two candidates",
        )
        assertEquals(item, item.toEntity().toModel())
        val empty = ReviewItem("r2", ReviewKind.STALE_PENDING, ReviewStatus.DISMISSED, 1, txnId = "t9")
        assertEquals("", empty.toEntity().candidateIds)
        assertEquals(empty, empty.toEntity().toModel())
    }

    @Test fun `allowlist keeps order and lists issuers that have no sender yet`() {
        val rows = allowlistRows("HDFC", listOf("HDFCBK", "HDFCCC")) + AllowlistEntity("AXIS", 0, "AXISBK")
        val map = allowlistMap(rows.reversed(), listOf("HDFC", "SCAPIA", "KIWI", "AXIS"))
        assertEquals(listOf("HDFCBK", "HDFCCC"), map["HDFC"])
        assertEquals(emptyList(), map["SCAPIA"])
        assertEquals(emptyList(), map["KIWI"])
        assertEquals(listOf("AXISBK"), map["AXIS"])
    }

    @Test fun `cards are shown in the fixed issuer order`() {
        val cards = listOf("axis" to "AXIS", "zzz" to "OTHER", "hdfc" to "HDFC", "kiwi" to "KIWI").map { CardConfig(it.first, it.second, "", 1, 1) }
        assertEquals(listOf("hdfc", "kiwi", "axis", "zzz"), sortCards(cards).map { it.id })
    }
}
