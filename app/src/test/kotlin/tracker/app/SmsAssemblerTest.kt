package tracker.app

import tracker.app.sms.BackfillProgress
import tracker.app.sms.SmsAssembler
import tracker.app.sms.SmsPart
import tracker.app.sms.plus
import tracker.core.IngestOutcome
import tracker.core.RawSms
import tracker.core.ReviewKind
import tracker.core.TxnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsAssemblerTest {
    @Test fun `multi-part message is joined in order with the first timestamp`() {
        val out = SmsAssembler.assemble(listOf(SmsPart("VM-TESTBK", "part one, ", 100), SmsPart("VM-TESTBK", "part two", 101)))
        assertEquals(listOf(RawSms("VM-TESTBK", "part one, part two", 100)), out)
    }

    @Test fun `different senders stay separate and blanks are dropped`() {
        val out = SmsAssembler.assemble(
            listOf(SmsPart("A-ONE", "x", 1), SmsPart(null, "lost", 2), SmsPart(" ", "lost", 3), SmsPart("B-TWO", "y", 4), SmsPart("C-THREE", null, 5)),
        )
        assertEquals(listOf(RawSms("A-ONE", "x", 1), RawSms("B-TWO", "y", 4)), out)
        assertTrue(SmsAssembler.assemble(emptyList()).isEmpty())
    }

    @Test fun `backfill tally counts every outcome once`() {
        val outcomes = listOf(
            IngestOutcome.Applied("t", TxnState.POSTED), IngestOutcome.Proposed("p"), IngestOutcome.Queued("r", ReviewKind.UNPARSED),
            IngestOutcome.Duplicate, IngestOutcome.Ignored("otp"), IngestOutcome.UntrackedCard, IngestOutcome.NotAllowlisted,
        )
        val p = outcomes.fold(BackfillProgress()) { acc, o -> acc.plus(o) }
        assertEquals(7, p.processed)
        assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(p.applied, p.proposed, p.queued, p.duplicates, p.ignored, p.untracked))
    }
}
