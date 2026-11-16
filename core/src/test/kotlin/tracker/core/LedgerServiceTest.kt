package tracker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerServiceTest {

    // ---------------------------------------------------------- gatekeeping

    @Test fun `non-allowlisted sender is not parsed and nothing is stored`() {
        val f = Fixture()
        assertIs<IngestOutcome.NotAllowlisted>(f.sms("SPEND|1234|500|Shop", 20, sender = "VM-OTHER"))
        assertTrue(f.rows().isEmpty())
        assertTrue(f.store.allProcessed().isEmpty())
        assertTrue(f.store.reviewItems().isEmpty())
    }

    @Test fun `otp is dropped and never stored, not even its hash`() {
        val f = Fixture()
        assertIs<IngestOutcome.Ignored>(f.sms("OTP", 20))
        assertTrue(f.store.allProcessed().isEmpty())
        assertTrue(f.store.reviewItems().isEmpty())
    }

    @Test fun `card digits that are not configured are skipped without storing`() {
        val f = Fixture()
        assertIs<IngestOutcome.UntrackedCard>(f.sms("SPEND|5555|500|Shop", 20))
        assertTrue(f.store.allProcessed().isEmpty())
    }

    @Test fun `unparsed known-sender message goes to the review queue with its text`() {
        val f = Fixture()
        val out = assertIs<IngestOutcome.Queued>(f.sms("something new", 20))
        assertEquals(ReviewKind.UNPARSED, out.kind)
        assertEquals("something new", f.open().single().body)
        assertTrue(f.rows().isEmpty())
    }

    // --------------------------------------------------------------- dedupe

    @Test fun `same sms imported twice counts once`() {
        val f = Fixture()
        f.anchor()
        assertIs<IngestOutcome.Applied>(f.sms("SPEND|1234|500|Shop", 20))
        assertIs<IngestOutcome.Duplicate>(f.sms("SPEND|1234|500|Shop", 20))
        assertEquals(1, f.rows().size)
        assertEquals(Paise.rupees(500), f.balance().unbilled)
    }

    @Test fun `re-importing a whole inbox is idempotent`() {
        val f = Fixture()
        f.anchor()
        val inbox = listOf(
            Triple("SPEND|1234|500|Shop", 20, 9), Triple("PAY|1234|4000", 21, 9), Triple("mystery text", 22, 9),
            Triple("STMT|1234|9000|450|2026-12-05", 16, 9), Triple("FEE|1234|118|GST", 23, 9),
        )
        inbox.forEach { (b, d, h) -> f.sms(b, d, h) }
        val before = f.service.snapshot()
        inbox.forEach { (b, d, h) -> assertIs<IngestOutcome.Duplicate>(f.sms(b, d, h)) }
        assertEquals(before, f.service.snapshot())
    }

    @Test fun `two genuine purchases of the same amount are both counted`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|200|Metro", 20, 9)
        f.sms("SPEND|1234|200|Metro", 21, 9)
        assertEquals(2, f.rows().size)
        assertEquals(Paise.rupees(400), f.balance().unbilled)
    }

    @Test fun `identical text minutes apart is queued as a possible duplicate, never guessed`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|200|Metro", 20, 9, 0)
        val out = assertIs<IngestOutcome.Queued>(f.sms("SPEND|1234|200|Metro", 20, 9, 30))
        assertEquals(ReviewKind.POSSIBLE_DUPLICATE, out.kind)
        assertEquals(Paise.rupees(200), f.balance().unbilled)
        // The user says it really was a second purchase.
        f.service.applyReviewAsNew(out.reviewId)
        assertEquals(Paise.rupees(400), f.balance().unbilled)
        assertTrue(f.open().isEmpty())
    }

    // ------------------------------------------------- authorisation/settle

    @Test fun `authorisation is pending and excluded from the headline`() {
        val f = Fixture()
        f.anchor()
        val out = assertIs<IngestOutcome.Applied>(f.sms("AUTH|1234|300|Cafe", 20))
        assertEquals(TxnState.PENDING, out.state)
        assertEquals(Paise.ZERO, f.balance().unbilled)
        assertEquals(Paise.rupees(300), f.balance().pending)
    }

    @Test fun `settlement promotes the matching pending row instead of adding a second one`() {
        val f = Fixture()
        f.anchor()
        f.sms("AUTH|1234|300|Cafe", 20)
        val out = assertIs<IngestOutcome.Applied>(f.sms("SPEND|1234|300|Cafe", 22))
        assertEquals(TxnState.POSTED, out.state)
        assertEquals(1, f.rows().size)
        assertEquals(LocalDate.of(2026, 11, 22), f.rows().single().date)
        assertEquals(Paise.rupees(300), f.balance().unbilled)
        assertEquals(Paise.ZERO, f.balance().pending)
    }

    @Test fun `settlement with no pending row is posted directly`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|300|Cafe", 22)
        assertEquals(TxnState.POSTED, f.rows().single().state)
    }

    @Test fun `two candidate pendings are ambiguous and the user links one`() {
        val f = Fixture()
        f.anchor()
        f.sms("AUTH|1234|200|Swiggy", 20, 9)
        f.sms("AUTH|1234|200|Swiggy", 21, 9)
        val out = assertIs<IngestOutcome.Queued>(f.sms("SPEND|1234|200|Swiggy", 22))
        assertEquals(ReviewKind.AMBIGUOUS_MATCH, out.kind)
        assertEquals(Paise.ZERO, f.balance().unbilled)
        val item = f.open().single()
        assertEquals(2, item.candidateIds.size)
        f.service.linkReview(item.id, item.candidateIds.first())
        assertEquals(Paise.rupees(200), f.balance().unbilled)
        assertEquals(Paise.rupees(200), f.balance().pending)
        assertNull(f.store.reviewItem(item.id)!!.body)
    }

    @Test fun `stale pending is queued once and stays pending until the user decides`() {
        val f = Fixture()
        f.anchor()
        f.sms("AUTH|1234|300|Hotel", 1)
        assertTrue(f.service.expireStalePendings(LocalDate.of(2026, 11, 10)).isEmpty())
        val created = f.service.expireStalePendings(LocalDate.of(2026, 11, 12))
        assertEquals(ReviewKind.STALE_PENDING, created.single().kind)
        assertEquals(TxnState.PENDING, f.rows().single().state)
        assertTrue(f.service.expireStalePendings(LocalDate.of(2026, 11, 13)).isEmpty())
        f.service.resolveStale(created.single().id, post = false)
        assertEquals(TxnState.REVERSED, f.rows().single().state)
        assertTrue(f.service.expireStalePendings(LocalDate.of(2026, 11, 20)).isEmpty())
    }

    @Test fun `stale pending days come from settings when present`() {
        val f = Fixture()
        f.sms("AUTH|1234|300|Hotel", 1)
        f.service.putSetting(Defaults.SETTING_STALE_PENDING_DAYS, "3")
        assertEquals(1, f.service.expireStalePendings(LocalDate.of(2026, 11, 5)).size)
    }

    @Test fun `stale pending can be posted by the user`() {
        val f = Fixture()
        f.anchor()
        f.sms("AUTH|1234|300|Hotel", 1)
        val item = f.service.expireStalePendings(LocalDate.of(2026, 11, 20)).single()
        f.service.resolveStale(item.id, post = true)
        assertEquals(Paise.rupees(300), f.balance(LocalDate.of(2026, 11, 20)).unbilled)
    }

    // ------------------------------------------------------------- payments

    @Test fun `payment reduces billed and fees are charges`() {
        val f = Fixture()
        f.anchor(10_000)
        f.sms("PAY|1234|4000", 20)
        f.sms("FEE|1234|118|GST", 21)
        assertEquals(Paise.rupees(6_000), f.balance().billed)
        assertEquals(Paise.rupees(118), f.balance().unbilled)
        assertEquals(TxnType.FEE, f.rows().first { it.merchant == "GST" }.type)
    }

    @Test fun `overpayment leaves a negative billed figure`() {
        val f = Fixture()
        f.anchor(10_000)
        f.sms("PAY|1234|12000", 20)
        assertEquals(Paise.rupees(-2_000), f.balance().billed)
        assertEquals(Paise.rupees(-2_000), f.service.dashboard(LocalDate.of(2026, 11, 14)).totalOutstanding)
    }

    // ----------------------------------------------------- refunds/reversals

    @Test fun `full reversal in the same cycle marks the original reversed`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|500|Shop", 20)
        f.sms("REVERSAL|1234|500|Shop", 21)
        assertTrue(f.rows().all { it.state == TxnState.REVERSED })
        assertEquals(f.rows().first { it.type == TxnType.PURCHASE }.id, f.rows().first { it.type == TxnType.REFUND }.linkedTo)
        assertEquals(Paise.ZERO, f.balance().unbilled)
    }

    @Test fun `reversal of a purchase that was already billed is a credit in the new cycle`() {
        val f = Fixture()
        f.anchor(10_000, LocalDate.of(2026, 11, 15))
        f.sms("SPEND|1234|500|Shop", 10)      // before the statement date: inside the 10,000
        f.sms("REVERSAL|1234|500|Shop", 18)   // after it
        val purchase = f.rows().first { it.type == TxnType.PURCHASE }
        val credit = f.rows().first { it.type == TxnType.REFUND }
        assertEquals(TxnState.POSTED, purchase.state)
        assertEquals(TxnState.POSTED, credit.state)
        assertEquals(Paise.rupees(-500), f.balance(LocalDate.of(2026, 11, 20)).unbilled)
    }

    @Test fun `confirming a statement between purchase and reversal re-posts the pair`() {
        val f = Fixture()
        f.anchor(8_000, LocalDate.of(2026, 10, 15))
        f.sms("SPEND|1234|500|Shop", 10)
        f.sms("REVERSAL|1234|500|Shop", 18)
        assertTrue(f.rows().all { it.state == TxnState.REVERSED })
        // The bank's November statement (dated the 15th) included the purchase.
        f.service.confirmStatement(Statement("hdfc", LocalDate.of(2026, 11, 15), Paise.rupees(8_500)))
        assertTrue(f.rows().all { it.state == TxnState.POSTED })
        assertEquals(Paise.rupees(-500), f.balance(LocalDate.of(2026, 11, 20)).unbilled)
        assertEquals(CardStatus.RECONCILED, f.service.cardSummary("hdfc", LocalDate.of(2026, 11, 20))!!.status)
    }

    @Test fun `refund then reversal for the same purchase is not credited twice`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|500|Shop", 20)
        assertIs<IngestOutcome.Applied>(f.sms("REFUND|1234|500|Shop", 21))
        val out = assertIs<IngestOutcome.Queued>(f.sms("REVERSAL|1234|500|Shop", 22))
        assertEquals(ReviewKind.AMBIGUOUS_REFUND, out.kind)
        assertEquals(Paise.ZERO, f.balance().unbilled)
    }

    @Test fun `reversal then refund for the same purchase is not credited twice`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|500|Shop", 20)
        assertIs<IngestOutcome.Applied>(f.sms("REVERSAL|1234|500|Shop", 21))
        val out = assertIs<IngestOutcome.Queued>(f.sms("REFUND|1234|500|Shop", 22))
        assertEquals(ReviewKind.AMBIGUOUS_REFUND, out.kind)
        assertEquals(Paise.ZERO, f.balance().unbilled)
        // If the user insists it is a separate credit, it is applied as an explicit choice.
        f.service.applyReviewAsNew(out.reviewId)
        assertEquals(Paise.rupees(-500), f.balance().unbilled)
    }

    @Test fun `partial refund is a standalone credit and the purchase stays posted`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|1000|Shop", 20)
        f.sms("REFUND|1234|400|Shop", 22)
        assertEquals(TxnState.POSTED, f.rows().first { it.type == TxnType.PURCHASE }.state)
        assertNull(f.rows().first { it.type == TxnType.REFUND }.linkedTo)
        assertEquals(Paise.rupees(600), f.balance().unbilled)
    }

    @Test fun `full refund is a linked credit and the purchase stays posted`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|1000|Shop", 20)
        f.sms("REFUND|1234|1000|Shop", 22)
        val purchase = f.rows().first { it.type == TxnType.PURCHASE }
        assertEquals(TxnState.POSTED, purchase.state)
        assertEquals(purchase.id, f.rows().first { it.type == TxnType.REFUND }.linkedTo)
        assertEquals(Paise.ZERO, f.balance().unbilled)
    }

    @Test fun `reversal that could cancel two purchases is ambiguous`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|200|Metro", 18)
        f.sms("SPEND|1234|200|Metro", 19)
        val out = assertIs<IngestOutcome.Queued>(f.sms("REVERSAL|1234|200|Metro", 21))
        assertEquals(ReviewKind.AMBIGUOUS_MATCH, out.kind)
        assertEquals(Paise.rupees(400), f.balance().unbilled)
        f.service.linkReview(out.reviewId, f.open().single().candidateIds.last())
        assertEquals(Paise.rupees(200), f.balance().unbilled)
    }

    @Test fun `reversal with no known purchase is still a credit`() {
        val f = Fixture()
        f.anchor()
        f.sms("REVERSAL|1234|500|Shop", 21)
        assertEquals(Paise.rupees(-500), f.balance().unbilled)
    }

    @Test fun `reversal of a pending authorisation just cancels it`() {
        val f = Fixture()
        f.anchor()
        f.sms("AUTH|1234|500|Shop", 20)
        f.sms("REVERSAL|1234|500|Shop", 21)
        assertEquals(TxnState.REVERSED, f.rows().single().state)
        assertEquals(Paise.ZERO, f.balance().unbilled)
        assertEquals(Paise.ZERO, f.balance().pending)
    }

    @Test fun `reference number picks the right purchase among same-amount ones`() {
        val f = Fixture()
        f.anchor()
        f.sms("SPEND|1234|200|Metro|REF001", 18)
        f.sms("SPEND|1234|200|Metro|REF002", 19)
        assertIs<IngestOutcome.Applied>(f.sms("REVERSAL|1234|200|Metro|REF002", 21))
        assertEquals(TxnState.POSTED, f.rows().first { it.ref == "REF001" }.state)
        assertEquals(TxnState.REVERSED, f.rows().first { it.ref == "REF002" && it.type == TxnType.PURCHASE }.state)
    }

    // ----------------------------------------------------------- statements

    @Test fun `statement notice is only a proposal and changes no balance until confirmed`() {
        val f = Fixture()
        f.anchor(10_000)
        f.sms("SPEND|1234|500|Shop", 10)
        val before = f.service.dashboard(LocalDate.of(2026, 11, 16))
        val out = assertIs<IngestOutcome.Proposed>(f.sms("STMT|1234|9000|450|2026-12-05", 16))
        val after = f.service.dashboard(LocalDate.of(2026, 11, 16))
        assertEquals(before.totalBilled, after.totalBilled)
        assertEquals(before.totalUnbilled, after.totalUnbilled)
        assertEquals(1, f.store.statementsForCard("hdfc").size)
        // A newer notice is waiting, so the card is flagged.
        assertEquals(CardStatus.STALE, after.cards.first { it.card.id == "hdfc" }.status)

        val p = f.service.proposals().single()
        assertEquals(out.proposalId, p.id)
        assertEquals(LocalDate.of(2026, 11, 15), p.statementDate)
        assertEquals(LocalDate.of(2026, 12, 5), p.dueDate)

        f.service.confirmStatement(Statement(p.cardId, p.statementDate, p.totalDue, p.minDue, p.dueDate), p.id)
        assertTrue(f.service.proposals().isEmpty())
        assertEquals(Paise.rupees(9_000), f.balance(LocalDate.of(2026, 11, 16)).billed)
        assertEquals(Paise.ZERO, f.balance(LocalDate.of(2026, 11, 16)).unbilled)
    }

    @Test fun `rejecting a proposal leaves everything as it was`() {
        val f = Fixture()
        f.anchor(10_000)
        f.sms("STMT|1234|9000|450|2026-12-05", 16)
        f.service.rejectProposal(f.service.proposals().single().id)
        assertTrue(f.service.proposals().isEmpty())
        assertEquals(Paise.rupees(10_000), f.balance().billed)
    }

    @Test fun `preview shows variance before confirming and never writes`() {
        val f = Fixture()
        f.anchor(8_000, LocalDate.of(2026, 10, 15))
        f.sms("PAY|1234|8000", 20, month = 10)
        f.sms("SPEND|1234|6000|Shop", 25, month = 10)
        val candidate = Statement("hdfc", LocalDate.of(2026, 11, 15), Paise.rupees(6_350))
        val recon = assertNotNull(f.service.previewReconcile(candidate))
        assertEquals(Paise.rupees(6_000), recon.expected)
        assertEquals(Paise.rupees(350), recon.variance)
        assertEquals(1, f.store.statementsForCard("hdfc").size)
        assertNull(f.service.previewReconcile(Statement("icici", LocalDate.of(2026, 11, 1), Paise.rupees(1))))
    }

    @Test fun `variance is only cleared by an explicit adjustment which can be undone`() {
        val f = Fixture()
        val today = LocalDate.of(2026, 11, 20)
        f.anchor(8_000, LocalDate.of(2026, 10, 15))
        f.sms("PAY|1234|8000", 20, month = 10)
        f.sms("SPEND|1234|6000|Shop", 25, month = 10)
        val stmtDate = LocalDate.of(2026, 11, 15)
        val recon = f.service.confirmStatement(Statement("hdfc", stmtDate, Paise.rupees(6_350)))
        assertEquals(Paise.rupees(350), recon!!.variance)
        val rowsBefore = f.rows()
        var summary = f.service.cardSummary("hdfc", today)!!
        assertEquals(CardStatus.VARIANCE, summary.status)
        assertEquals(Paise.rupees(350), summary.variance)
        assertEquals(rowsBefore, f.rows())

        val adj = f.service.addAdjustment("hdfc", stmtDate, Paise.rupees(350), "interest not in SMS")
        assertEquals(TxnState.ADJUSTMENT, adj.state)
        summary = f.service.cardSummary("hdfc", today)!!
        assertEquals(CardStatus.RECONCILED, summary.status)
        assertEquals(Paise.rupees(6_350), summary.balance.billed)

        assertTrue(f.service.undoAdjustment(adj.id))
        assertEquals(CardStatus.VARIANCE, f.service.cardSummary("hdfc", today)!!.status)
    }

    @Test fun `undo refuses to delete anything that is not an adjustment`() {
        val f = Fixture()
        f.sms("SPEND|1234|500|Shop", 20)
        assertFalse(f.service.undoAdjustment(f.rows().single().id))
        assertEquals(1, f.rows().size)
        assertFailsWith<IllegalArgumentException> { f.service.addAdjustment("hdfc", LocalDate.of(2026, 11, 1), Paise.ZERO, null) }
    }

    @Test fun `a mistyped statement can be deleted`() {
        val f = Fixture()
        f.anchor(10_000)
        f.service.deleteStatement("hdfc", LocalDate.of(2026, 10, 15))
        assertEquals(CardStatus.NO_ANCHOR, f.service.cardSummary("hdfc", LocalDate.of(2026, 11, 1))!!.status)
    }

    // --------------------------------------------------------- review queue

    @Test fun `manual classification writes a row and wipes the stored text`() {
        val f = Fixture()
        f.anchor()
        val out = assertIs<IngestOutcome.Queued>(f.sms("unknown wording 750", 20))
        val row = f.service.classifyManually(out.reviewId, "hdfc", TxnType.PURCHASE, Paise.rupees(750), LocalDate.of(2026, 11, 20), " Shop ")
        assertEquals("Shop", row.merchant)
        assertEquals(Paise.rupees(750), f.balance().unbilled)
        val closed = f.store.reviewItem(out.reviewId)!!
        assertEquals(ReviewStatus.RESOLVED, closed.status)
        assertNull(closed.body)
        assertFailsWith<IllegalArgumentException> { f.service.dismissReview(out.reviewId) }
    }

    @Test fun `dismiss wipes the stored text and keeps the ledger unchanged`() {
        val f = Fixture()
        val out = assertIs<IngestOutcome.Queued>(f.sms("unknown wording", 20))
        f.service.dismissReview(out.reviewId)
        assertNull(f.store.reviewItem(out.reviewId)!!.body)
        assertTrue(f.rows().isEmpty())
        assertTrue(f.open().isEmpty())
    }

    @Test fun `printed date far from the received date is not trusted`() {
        val f = Fixture()
        val out = assertIs<IngestOutcome.Queued>(f.sms("SPEND|1234|500|Shop||2026-01-02", 20))
        assertEquals(ReviewKind.UNPARSED, out.kind)
        assertTrue(f.rows().isEmpty())
    }

    @Test fun `printed date close to the received date is used`() {
        val f = Fixture()
        f.sms("SPEND|1234|500|Shop||2026-11-19", 20)
        assertEquals(LocalDate.of(2026, 11, 19), f.rows().single().date)
    }

    // ------------------------------------------------------------ dashboard

    @Test fun `dashboard totals and worst status across cards`() {
        val f = Fixture()
        val today = LocalDate.of(2026, 11, 10)
        var d = f.service.dashboard(today)
        assertEquals(CardStatus.NO_ANCHOR, d.worstStatus)

        f.anchor(10_000)
        f.service.confirmStatement(Statement("icici", LocalDate.of(2026, 11, 1), Paise.rupees(2_000)))
        f.sms("SPEND|1234|500|Shop", 5)
        f.sms("AUTH|1234|70|Cafe", 6)
        f.sms("PAY|1234|1000", 7)
        f.sms("mystery", 8)
        d = f.service.dashboard(today)
        assertEquals(Paise.rupees(11_000), d.totalBilled)
        assertEquals(Paise.rupees(500), d.totalUnbilled)
        assertEquals(Paise.rupees(70), d.totalPending)
        assertEquals(Paise.rupees(11_500), d.totalOutstanding)
        assertEquals(CardStatus.RECONCILED, d.worstStatus)
        assertEquals(1, d.openReviewCount)

        // HDFC's next statement is long overdue by late December.
        d = f.service.dashboard(LocalDate.of(2026, 12, 28))
        assertEquals(CardStatus.STALE, d.worstStatus)
        assertEquals(CardStatus.STALE, d.cards.first { it.card.id == "hdfc" }.status)
    }

    @Test fun `card without a statement reports the net of what was seen, flagged no-anchor`() {
        val f = Fixture()
        f.sms("SPEND|1234|500|Shop", 5)
        f.sms("PAY|1234|200", 7)
        val s = f.service.cardSummary("hdfc", LocalDate.of(2026, 11, 10))!!
        assertEquals(CardStatus.NO_ANCHOR, s.status)
        assertEquals(Paise.ZERO, s.balance.billed)
        assertEquals(Paise.rupees(300), s.balance.unbilled)
        assertNull(s.dueDate)
    }

    @Test fun `due date falls back to the configured due day`() {
        val f = Fixture()
        f.anchor(10_000)
        assertEquals(LocalDate.of(2026, 11, 5), f.service.cardSummary("hdfc", LocalDate.of(2026, 10, 20))!!.dueDate)
    }

    // ---------------------------------------------------------------- setup

    @Test fun `seeding is idempotent and leaves scapia and kiwi senders empty`() {
        val store = InMemoryLedgerStore()
        val service = LedgerService(store, parserFactory = { StubParser })
        service.seedDefaults()
        service.saveCard(store.cards().first { it.id == "hdfc" }.copy(last4 = "1234"))
        service.setAllowlist("SCAPIA", listOf(" scapia ", "", "ab"))
        service.seedDefaults()
        assertEquals(6, store.cards().size)
        assertEquals("1234", store.cards().first { it.id == "hdfc" }.last4)
        assertEquals(listOf("SCAPIA"), store.allowlist()["SCAPIA"])
        assertEquals(emptyList(), Defaults.allowlist()["KIWI"])
        assertEquals(emptyList(), Defaults.allowlist()["SCAPIA"])
    }

    @Test fun `card config is validated`() {
        val f = Fixture()
        assertFailsWith<IllegalArgumentException> { f.service.saveCard(f.card.copy(last4 = "12a4")) }
        assertFailsWith<IllegalArgumentException> { f.service.saveCard(f.card.copy(statementDay = 32)) }
    }

    @Test fun `blank or too-short allowlist entries never match`() {
        assertFalse(SenderAllowlist.matches(listOf("", "  ", "AB"), "VM-ABCDEF"))
        assertTrue(SenderAllowlist.matches(listOf("hdfcbk"), "VM-HDFCBK-S"))
        assertFalse(SenderAllowlist.matches(listOf("HDFCBK"), ""))
    }
}
