package tracker.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Test-only parser so engine tests do not depend on any issuer template.
 * Body format:  KIND|last4|rupees|merchant|ref|yyyy-mm-dd      (trailing fields optional)
 *   KIND = AUTH, SPEND, PAY, REFUND, REVERSAL, FEE
 *   STMT|last4|totalRupees|minRupees|yyyy-mm-dd(due)
 *   OTP -> Ignored, anything else -> Unparsed
 */
object StubParser : SmsParser {
    override fun route(sender: String, body: String, receivedAt: Instant): ParseResult? {
        if (!sender.contains("TESTBK")) return null
        val f = body.split("|")
        fun opt(i: Int): String? = f.getOrNull(i)?.takeIf { it.isNotEmpty() }
        return when (f[0]) {
            "OTP" -> ParseResult.Ignored("otp")
            "STMT" -> {
                val total = Paise.rupees(f[2].toLong())
                ParseResult.Parsed(
                    ParsedDraft(
                        f[1], TxnType.FEE, false, total, null, null, receivedAt,
                        statement = StatementDraft(total, opt(3)?.let { Paise.rupees(it.toLong()) }, opt(4), opt(4)?.let { LocalDate.parse(it) }),
                    ),
                )
            }
            "AUTH", "SPEND", "PAY", "REFUND", "REVERSAL", "FEE" -> {
                val type = when (f[0]) {
                    "AUTH", "SPEND" -> TxnType.PURCHASE
                    "PAY" -> TxnType.PAYMENT
                    "FEE" -> TxnType.FEE
                    else -> TxnType.REFUND
                }
                ParseResult.Parsed(
                    ParsedDraft(
                        cardLast4 = f[1], type = type, isAuthorisation = f[0] == "AUTH",
                        amount = Paise.rupees(f[2].toLong()), merchant = opt(3), ref = opt(4), receivedAt = receivedAt,
                        txnDate = opt(5)?.let { LocalDate.parse(it) }, isReversal = f[0] == "REVERSAL",
                    ),
                )
            }
            else -> ParseResult.Unparsed(sender)
        }
    }
}

class Fixture(config: EngineConfig = EngineConfig()) {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    val store = InMemoryLedgerStore()
    private var nextId = 0
    val service = LedgerService(
        store = store, zone = zone, config = config,
        idGen = { "id" + (++nextId) }, nowMs = { 1_000L + nextId },
        parserFactory = { StubParser },
    )
    val card = CardConfig("hdfc", "HDFC", "1234", statementDay = 15, dueDay = 5)

    init {
        store.upsertCard(card)
        store.upsertCard(CardConfig("icici", "ICICI", "9876", statementDay = 1, dueDay = 20))
        store.setAllowlist("HDFC", listOf("TESTBK"))
    }

    /** Milliseconds for a day/hour/minute in November 2026 (or another month), India time. */
    fun at(day: Int, hour: Int = 12, minute: Int = 0, month: Int = 11): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    fun sms(body: String, day: Int, hour: Int = 12, minute: Int = 0, month: Int = 11, sender: String = "VM-TESTBK") =
        service.ingest(RawSms(sender, body, at(day, hour, minute, month)))

    fun anchor(rupees: Long = 10_000, date: LocalDate = LocalDate.of(2026, 10, 15)) {
        service.confirmStatement(Statement("hdfc", date, Paise.rupees(rupees)))
    }

    fun balance(today: LocalDate = LocalDate.of(2026, 11, 14)): CardBalance = service.cardSummary("hdfc", today)!!.balance
    fun rows(): List<Txn> = store.txnsForCard("hdfc")
    fun open(): List<ReviewItem> = service.reviewItems()
}
