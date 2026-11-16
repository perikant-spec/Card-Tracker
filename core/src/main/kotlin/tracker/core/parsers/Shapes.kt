package tracker.core.parsers

/**
 * Reusable message shapes shared by the issuer files.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Every pattern below comes from general knowledge of how Indian card alerts are commonly
 * worded. None has been checked against a real message from any bank. Expect to edit them
 * once `tools/extract_fixtures.py` has produced real samples (see docs/UNVERIFIED.md).
 *
 * To change one issuer only, replace the `Shapes.X` reference in that issuer's file with a
 * literal pattern string. Placeholders are documented on [SmsTemplate].
 */
object Shapes {
    private const val AFTER_MERCHANT = "(?: on {DATE}{TIME}{REF}{TAIL}|{END})"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Rs.1,250.00 spent on HDFC Bank Card x1234 at MERCHANT on 15-11-26"
    const val SPEND_AMT_SPENT_AT =
        "{DEAR}{ALERT}{AMT} (?:was |has been |is )?spent (?:on|using|via|from) (?:your )?{CP} {CARD} at {MER}$AFTER_MERCHANT"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Spent Rs.1250 On HDFC Bank Card 1234 At MERCHANT On 2026-11-15:14:22:10"
    const val SPEND_SPENT_AMT_AT =
        "{DEAR}{ALERT}Spent {AMT} (?:on|using|via|from) (?:your )?{CP} {CARD} at {MER}$AFTER_MERCHANT"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "INR 1,250.00 spent using ICICI Bank Card XX1234 on 15-Nov-26 on MERCHANT. Avl Limit: ..."
    const val SPEND_DATE_THEN_MERCHANT =
        "{DEAR}{ALERT}{AMT} (?:was |has been |is )?spent (?:on|using|via|from) (?:your )?{CP} {CARD} on {DATE}{TIME} (?:at|on) {MER}{REF}{END}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Thank you for using your ICICI Bank Credit Card XX1234 for INR 1,250.00 at MERCHANT"
    const val SPEND_THANK_YOU =
        "{DEAR}Thank you for using (?:your )?{CP} {CARD} for {AMT} at {MER}$AFTER_MERCHANT"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Transaction of Rs.1,250.00 made on your SBI Credit Card ending 1234 at MERCHANT on 15/11/26"
    const val SPEND_TXN_OF =
        "{DEAR}{ALERT}(?:A |Your )?(?:transaction|txn|purchase) of {AMT} (?:has been |was |is )?(?:made|done|processed) " +
            "(?:on|using|via|with) (?:your )?{CP} {CARD} at {MER}$AFTER_MERCHANT"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Rs.250.00 debited from your Kiwi Credit Card XX1234 via UPI to MERCHANT on 15-11-26"
    const val SPEND_DEBITED =
        "{DEAR}{ALERT}{AMT} (?:was |has been |is )?debited (?:from|on|to) (?:your )?{CP} {CARD}(?: via UPI)? " +
            "(?:to|towards|at|for) {MER}$AFTER_MERCHANT"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Payment of Rs 5,000 received towards your card ending 1234"
    const val PAYMENT_RECEIVED =
        "{DEAR}(?:Your )?payment of {AMT} (?:has been |was |is )?(?:received|credited) " +
            "(?:towards|on|for|against|to) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "We have received payment of Rs.7,500.00 towards your SBI Credit Card XX1234 on 14-11-2026"
    const val PAYMENT_WE_RECEIVED =
        "{DEAR}We have received (?:a |your )?payment of {AMT} " +
            "(?:towards|on|for|against|to) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Refund of Rs.500.00 from MERCHANT has been credited to your HDFC Bank Credit Card x1234 on 15-11-26"
    const val REFUND_FROM_MERCHANT =
        "{DEAR}(?:A )?refund of {AMT} (?:from|by|for|at) (?<merchant>.+?) (?:has been |was |is )?(?:credited|processed|posted) " +
            "(?:to|on) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Refund of Rs.500.00 has been credited to your Axis Bank Credit Card XX1234"
    const val REFUND_PLAIN =
        "{DEAR}(?:A )?refund of {AMT} (?:has been |was |is )?(?:credited|processed|posted) " +
            "(?:to|on) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Your transaction of Rs.500.00 at MERCHANT on HDFC Bank Credit Card x1234 has been reversed on 16-11-26"
    const val REVERSAL_TXN_REVERSED =
        "{DEAR}(?:Your )?(?:transaction|txn|purchase) of {AMT}(?: at (?<merchant>.+?))? on (?:your )?{CP} {CARD} " +
            "(?:has been |was |is )reversed(?: on {DATE})?{REF}{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Transaction reversal of Rs.500.00 has been credited to your SBI Credit Card ending 1234"
    const val REVERSAL_CREDITED =
        "{DEAR}(?:Transaction |Txn )?reversal of {AMT} (?:has been |was |is )?(?:credited|processed|posted) " +
            "(?:to|on) (?:your )?{CP} {CARD}(?: on {DATE})?{REF}{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Late payment fee of Rs.500.00 has been charged to your ICICI Bank Credit Card XX1234"
    // (also: finance charges, interest, GST/IGST, annual fee ... see SmsTemplate.FEE_WORDS)
    const val FEE_CHARGED =
        "{DEAR}(?<merchant>{FEEWORDS}) of {AMT} (?:has been |have been |was |were |is )?(?:charged|levied|debited|applied|billed) " +
            "(?:to|on) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "EMI of Rs.2,000.00 has been debited to your Axis Bank Credit Card XX1234"
    // An EMI instalment is a charge. EMI *conversion* notices are deliberately not templated:
    // their effect on the outstanding cannot be read off the SMS, so they go to the review queue.
    const val EMI_DEBITED =
        "{DEAR}(?<merchant>EMI) of {AMT} (?:has been |was |is )?(?:debited|charged|billed|posted) " +
            "(?:to|on) (?:your )?{CP} {CARD}(?: on {DATE})?{TAIL}"

    private const val DUES =
        "Total (?:Amt|Amount) Due(?: is)?:? ?{TOTAL}[.,;]? (?:and )?Min(?:imum|\\.)? (?:Amt|Amount) Due(?: is)?:? ?{MIN}[.,;]? ?" +
            "(?:Payment )?due (?:date|by|on)(?: is)?:? ?{DUE}{TAIL}"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "Statement for your SBI Credit Card ending 1234 is generated. Total Amt Due Rs.10,000.00, Min Amt Due Rs.500.00, due date 05-12-2026"
    const val STATEMENT_FOR_CARD =
        "{DEAR}(?:Your )?(?:e-?statement|statement) (?:for|of) (?:your )?{CP} {CARD} (?:is|has been) (?:generated|ready|sent)[.,:;]? ?$DUES"

    // UNVERIFIED TEMPLATE: written without real fixtures
    // e.g. "HDFC Bank Credit Card x1234 Statement: Total Amt Due Rs.10,000.00, Min Amt Due Rs.500.00, due date 05-12-2026"
    const val STATEMENT_CARD_FIRST =
        "{DEAR}(?:Your )?{CP} {CARD} statement(?: (?:is|has been) (?:generated|ready|sent))?[.,:;]? ?$DUES"

    /** The shapes every issuer starts with. Issuer files add their own on top. */
    fun standard(prefix: String, cardPhrase: String): List<SmsTemplate> = listOf(
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.amt_spent_at", DraftKind.SPEND, SPEND_AMT_SPENT_AT, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.spent_amt_at", DraftKind.SPEND, SPEND_SPENT_AMT_AT, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.date_then_merchant", DraftKind.SPEND, SPEND_DATE_THEN_MERCHANT, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.thank_you", DraftKind.SPEND, SPEND_THANK_YOU, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.txn_of", DraftKind.SPEND, SPEND_TXN_OF, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.spend.debited", DraftKind.SPEND, SPEND_DEBITED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.payment.received", DraftKind.PAYMENT, PAYMENT_RECEIVED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.payment.we_received", DraftKind.PAYMENT, PAYMENT_WE_RECEIVED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.refund.from_merchant", DraftKind.REFUND, REFUND_FROM_MERCHANT, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.refund.plain", DraftKind.REFUND, REFUND_PLAIN, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.reversal.txn_reversed", DraftKind.REVERSAL, REVERSAL_TXN_REVERSED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.reversal.credited", DraftKind.REVERSAL, REVERSAL_CREDITED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.fee.charged", DraftKind.FEE, FEE_CHARGED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.emi.debited", DraftKind.SPEND, EMI_DEBITED, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.statement.for_card", DraftKind.STATEMENT, STATEMENT_FOR_CARD, cardPhrase),
        // UNVERIFIED TEMPLATE: written without real fixtures
        SmsTemplate("$prefix.statement.card_first", DraftKind.STATEMENT, STATEMENT_CARD_FIRST, cardPhrase),
    )
}
