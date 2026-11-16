package tracker.core.parsers

/**
 * HDFC templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real HDFC message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object HdfcTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:HDFC Bank |HDFC )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("hdfc", CARD_PHRASE) + listOf<SmsTemplate>(
    )
}
