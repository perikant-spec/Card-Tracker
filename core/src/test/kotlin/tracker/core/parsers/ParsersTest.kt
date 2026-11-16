package tracker.core.parsers

import tracker.core.CardConfig
import tracker.core.CardStatus
import tracker.core.Defaults
import tracker.core.InMemoryLedgerStore
import tracker.core.IngestOutcome
import tracker.core.LedgerService
import tracker.core.Paise
import tracker.core.ParseResult
import tracker.core.RawSms
import tracker.core.Statement
import tracker.core.TxnState
import tracker.core.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** All message texts here are synthetic: fake merchants, fake digits. */
class ParsersTest {
    private val now: Instant = Instant.parse("2026-11-15T06:30:00Z")
    private val hdfc = IssuerParsers.parser("HDFC", listOf("HDFCBK"))!!

    private fun parse(body: String) = hdfc.parse("VM-HDFCBK", body, now)

    // ------------------------------------------------------------ helpers

    @Test fun `amount formats`() {
        assertEquals(Paise(125_000), SmsText.parseAmount("1,250.00"))
        assertEquals(Paise(12_500_050), SmsText.parseAmount("1,25,000.50"))
        assertEquals(Paise(12_500_000), SmsText.parseAmount("125,000"))
        assertEquals(Paise(500_000), SmsText.parseAmount("5000"))
        assertEquals(Paise(50), SmsText.parseAmount("0.5"))
        assertEquals(Paise(5), SmsText.parseAmount("0.05"))
        assertNull(SmsText.parseAmount("12,50.00"))
        assertNull(SmsText.parseAmount("1,250.005"))
        assertNull(SmsText.parseAmount("1,250,"))
        assertNull(SmsText.parseAmount(""))
        assertNull(SmsText.parseAmount("9999999999999"))
        assertEquals(
            listOf(Paise(125_000), Paise(500_000), Paise(9_900), Paise(1_000)),
            SmsText.findAmounts("Rs.1,250.00 then Rs 5,000 then INR 99 then ₹10.00 but not 777"),
        )
    }

    @Test fun `date formats are day first`() {
        val d = LocalDate.of(2026, 11, 5)
        assertEquals(d, SmsText.parseDate("05-11-26"))
        assertEquals(d, SmsText.parseDate("5-11-2026"))
        assertEquals(d, SmsText.parseDate("05/11/26"))
        assertEquals(d, SmsText.parseDate("05-Nov-26"))
        assertEquals(d, SmsText.parseDate("05 Nov 2026"))
        assertEquals(d, SmsText.parseDate("5 November, 2026"))
        assertEquals(d, SmsText.parseDate("05NOV26"))
        assertEquals(d, SmsText.parseDate("2026-11-05"))
        assertNull(SmsText.parseDate("31-02-26"))
        assertNull(SmsText.parseDate("05-13-26"))
        assertNull(SmsText.parseDate("05-Foo-26"))
        assertNull(SmsText.parseDate("05-11-126"))
    }

    @Test fun `last4 shapes`() {
        assertEquals(listOf("1234"), SmsText.findLast4("Card x1234"))
        assertEquals(listOf("1234"), SmsText.findLast4("Card XX1234."))
        assertEquals(listOf("1234"), SmsText.findLast4("card ending 1234"))
        assertEquals(listOf("1234"), SmsText.findLast4("card ending with 1234 and again **1234"))
        assertEquals(listOf("1234", "9090"), SmsText.findLast4("Card XX1234 and A/c XX9090"))
        assertTrue(SmsText.findLast4("call 18001234567 or pay 1234").isEmpty())
    }

    // -------------------------------------------------------- precision

    @Test fun `examples from the brief parse`() {
        val spend = assertIs<ParseResult.Parsed>(parse("Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26")).draft
        assertEquals(TxnType.PURCHASE, spend.type)
        assertEquals(Paise(125_000), spend.amount)
        assertEquals("1234", spend.cardLast4)
        assertEquals("MERCHANT", spend.merchant)
        assertEquals(LocalDate.of(2026, 11, 15), spend.txnDate)
        assertFalse(spend.isAuthorisation)

        val pay = assertIs<ParseResult.Parsed>(parse("Payment of Rs 5,000 received towards your card ending 1234")).draft
        assertEquals(TxnType.PAYMENT, pay.type)
        assertEquals(Paise(500_000), pay.amount)

        val icici = IssuerParsers.parser("ICICI", listOf("ICICIB"))!!
        val thanks = assertIs<ParseResult.Parsed>(
            icici.parse("AD-ICICIB", "Thank you for using your ICICI Bank Credit Card XX1234 for INR 1,250.00 at MERCHANT", now),
        ).draft
        assertEquals(Paise(125_000), thanks.amount)
        assertEquals("MERCHANT", thanks.merchant)
    }

    @Test fun `multi-word merchant is not truncated`() {
        val d = assertIs<ParseResult.Parsed>(parse("Rs.80 spent on HDFC Bank Card x1234 at BIG FAKE BAZAAR on 15-11-26. Avl Lmt Rs.1,000")).draft
        assertEquals("BIG FAKE BAZAAR", d.merchant)
        assertEquals(Paise(8_000), d.amount)
    }

    @Test fun `near misses are unparsed, never guessed`() {
        // word order changed, amount without currency, card digits missing, 3-digit card
        assertIs<ParseResult.Unparsed>(parse("You have spent Rs.1,250.00 somewhere with card x1234"))
        assertIs<ParseResult.Unparsed>(parse("1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26"))
        assertIs<ParseResult.Unparsed>(parse("Rs.1,250.00 spent on HDFC Bank Card at MERCHANT on 15-11-26"))
        assertIs<ParseResult.Unparsed>(parse("Rs.1,250.00 spent on HDFC Bank Card x123 at MERCHANT on 15-11-26"))
        assertIs<ParseResult.Unparsed>(parse("Rs.1,250.00 spent on HDFC Bank Debit Card x1234 at MERCHANT on 15-11-26"))
        assertIs<ParseResult.Unparsed>(parse("Refund of Rs.500.00 has been initiated to your HDFC Bank Credit Card x1234"))
        assertIs<ParseResult.Unparsed>(parse("Late payment fee of Rs.500.00 + GST has been charged to your HDFC Bank Credit Card x1234"))
    }

    @Test fun `failed or declined transactions are never recorded as spends`() {
        assertIs<ParseResult.Unparsed>(parse("Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26 was declined"))
        assertIs<ParseResult.Unparsed>(parse("Transaction of Rs.1,250.00 made on your HDFC Bank Credit Card x1234 at MERCHANT. Transaction failed."))
    }

    @Test fun `otp is dropped even when it mentions an amount and a card`() {
        assertEquals(ParseResult.Ignored("otp"), parse("123456 is your OTP for Rs.1,250.00 at MERCHANT on card ending 1234"))
        assertEquals(ParseResult.Ignored("otp"), parse("Your one time password is 4821 for HDFC Bank Credit Card x1234"))
        assertEquals(ParseResult.Ignored("otp"), parse("Use OTP 482193 to complete your transaction of INR 999.00"))
        assertEquals(ParseResult.Ignored("otp"), parse("OTP: 482193. Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26"))
        // Unknown wording that mentions an OTP is dropped rather than stored in the review queue.
        assertEquals(ParseResult.Ignored("otp"), parse("Complete the purchase with the OTP sent separately"))
    }

    @Test fun `a real alert with an otp warning in its footer is still parsed`() {
        val d = assertIs<ParseResult.Parsed>(
            parse("Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26. Not you? Call 1800000000. Never share OTP or CVV"),
        ).draft
        assertEquals(Paise(125_000), d.amount)
    }

    @Test fun `promo is ignored but a promo-looking message that moves money is not`() {
        assertIs<ParseResult.Ignored>(parse("Pre-approved offer on your HDFC Bank Credit Card. Apply now. T&C apply"))
        assertIs<ParseResult.Unparsed>(parse("Rs.999 charged as renewal on card x1234. T&C apply"))
    }

    @Test fun `bank account alerts are ignored, card-account wording is kept for review`() {
        assertEquals(ParseResult.Ignored("bank-account alert"), parse("Rs.5,000.00 debited from A/c XX9090 on 15-11-26 to credit card bill. Avl Bal Rs.100"))
        assertEquals(ParseResult.Ignored("bank-account alert"), parse("INR 40,000 credited to your account no. XX9090 on 01-11-26"))
        assertIs<ParseResult.Unparsed>(parse("Your Credit Card A/c XX1234 has been debited with Rs.500"))
    }

    // ---------------------------------------------------------- routing

    @Test fun `sender allowlist decides who gets parsed at all`() {
        val router = IssuerParsers.router(mapOf("HDFC" to listOf("HDFCBK"), "SCAPIA" to emptyList(), "NOPE" to listOf("XXXXXX")))
        val body = "Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26"
        assertIs<ParseResult.Parsed>(router.route("VM-HDFCBK-S", body, now))
        assertNull(router.route("VM-OTHERS", body, now))
        assertNull(router.route("", body, now))
    }

    @Test fun `scapia and kiwi work only after the user supplies a sender`() {
        val body = "Rs.250.00 debited from your Kiwi Credit Card XX4444 via UPI to SYNTH TEA STALL on 15-11-2026"
        assertNull(IssuerParsers.router(Defaults.allowlist()).route("TS-SYNKWI", body, now))
        val withSender = Defaults.allowlist() + ("KIWI" to listOf("SYNKWI"))
        assertIs<ParseResult.Parsed>(IssuerParsers.router(withSender).route("TS-SYNKWI", body, now))
    }

    @Test fun `two issuers sharing a sender agree or the message goes to review`() {
        val router = IssuerParsers.router(mapOf("AXIS" to listOf("AXISBK"), "KIWI" to listOf("AXISBK")))
        val d = assertIs<ParseResult.Parsed>(router.route("AX-AXISBK", "Payment of Rs 5,000 received towards your card ending 4444", now))
        assertEquals("4444", d.draft.cardLast4)
    }

    // ------------------------------------------------- end to end with engine

    private fun ms(day: Int, hour: Int) =
        LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test fun `card payment plus the bank-account debit alert for it is counted once`() {
        val store = InMemoryLedgerStore()
        val service = LedgerService(store)
        service.seedDefaults()
        service.saveCard(CardConfig("hdfc", "HDFC", "1234", 15, 5))
        service.confirmStatement(Statement("hdfc", LocalDate.of(2026, 10, 15), Paise.rupees(10_000)))

        val debit = service.ingest(RawSms("VM-HDFCBK", "Rs.5,000.00 debited from A/c XX9090 on 12-11-26 towards HDFC Bank Credit Card x1234 payment. Avl Bal Rs.1,000.00", ms(12, 10)))
        val payment = service.ingest(RawSms("VM-HDFCBK", "Payment of Rs.5,000.00 received towards your HDFC Bank Credit Card ending 1234 on 12-11-2026", ms(12, 11)))
        assertIs<IngestOutcome.Ignored>(debit)
        assertIs<IngestOutcome.Applied>(payment)

        val rows = store.txnsForCard("hdfc")
        assertEquals(1, rows.size)
        assertEquals(TxnType.PAYMENT, rows.single().type)
        val summary = service.cardSummary("hdfc", LocalDate.of(2026, 11, 13))!!
        assertEquals(Paise.rupees(5_000), summary.balance.billed)
        assertEquals(CardStatus.RECONCILED, summary.status)
        assertTrue(store.reviewItems().isEmpty())
    }

    @Test fun `debit-card and other-card alerts from the same sender never reach the ledger`() {
        val store = InMemoryLedgerStore()
        val service = LedgerService(store)
        service.seedDefaults()
        service.saveCard(CardConfig("hdfc", "HDFC", "1234", 15, 5))
        val out = service.ingest(RawSms("VM-HDFCBK", "Rs.300.00 spent on HDFC Bank Card x7777 at MERCHANT on 12-11-26", ms(12, 10)))
        assertIs<IngestOutcome.UntrackedCard>(out)
        assertTrue(store.txns().isEmpty())
        assertTrue(store.allProcessed().isEmpty())
    }

    @Test fun `default spend templates post directly and a later reversal cancels`() {
        val store = InMemoryLedgerStore()
        val service = LedgerService(store)
        service.seedDefaults()
        service.saveCard(CardConfig("hdfc", "HDFC", "1234", 15, 5))
        service.confirmStatement(Statement("hdfc", LocalDate.of(2026, 10, 15), Paise.rupees(0)))
        service.ingest(RawSms("VM-HDFCBK", "Rs.500.00 spent on HDFC Bank Card x1234 at FAKEMART STORE on 12-11-26", ms(12, 10)))
        assertEquals(TxnState.POSTED, store.txns().single().state)
        service.ingest(RawSms("VM-HDFCBK", "Your transaction of Rs.500.00 at FAKEMART STORE on HDFC Bank Credit Card x1234 has been reversed on 13-11-26", ms(13, 10)))
        assertTrue(store.txns().all { it.state == TxnState.REVERSED })
        assertEquals(Paise.ZERO, service.cardSummary("hdfc", LocalDate.of(2026, 11, 14))!!.balance.unbilled)
    }

    @Test fun `every template compiles and carries a unique name`() {
        val names = IssuerParsers.TEMPLATES.values.flatten().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(Defaults.ISSUERS.toSet(), IssuerParsers.TEMPLATES.keys)
    }
}
