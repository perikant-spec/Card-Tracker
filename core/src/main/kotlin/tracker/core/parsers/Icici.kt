package tracker.core.parsers

/**
 * ICICI templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real ICICI message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object IciciTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:ICICI Bank |ICICI )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("icici", CARD_PHRASE) + listOf<SmsTemplate>(
    )
}
