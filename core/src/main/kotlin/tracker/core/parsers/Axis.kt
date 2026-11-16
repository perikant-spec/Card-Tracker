package tracker.core.parsers

/**
 * Axis templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real Axis message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object AxisTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:Axis Bank |Axis )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("axis", CARD_PHRASE) + listOf<SmsTemplate>(
        // UNVERIFIED TEMPLATE: written without real fixtures
        // e.g. "Spent INR 499 Axis Bank Card no. XX1234 15-11-26 14:22:10 IST MERCHANT Avl Lmt INR 50000"
        SmsTemplate(
            "axis.spend.block_amount_first", DraftKind.SPEND,
            "Spent {AMT} (?:on )?(?:your )?{CP} {CARD} {DATE}{TIME} (?<merchant>.+?) Avl (?:Lmt|Limit)\\b.*", CARD_PHRASE,
        ),
        // UNVERIFIED TEMPLATE: written without real fixtures
        // e.g. "Spent Card no. XX1234 INR 499 15-11-26 14:22:10 MERCHANT Avl Lmt INR 50000"
        SmsTemplate(
            "axis.spend.block_card_first", DraftKind.SPEND,
            "Spent (?:on )?(?:your )?{CP} {CARD} {AMT} {DATE}{TIME} (?<merchant>.+?) Avl (?:Lmt|Limit)\\b.*", CARD_PHRASE,
        ),
    )
}
