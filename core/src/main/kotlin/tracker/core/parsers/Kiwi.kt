package tracker.core.parsers

/**
 * Kiwi templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real Kiwi message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object KiwiTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:(?:Axis Bank|Yes Bank|AU Bank) )?(?:Kiwi )?(?:RuPay )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("kiwi", CARD_PHRASE) + listOf<SmsTemplate>(
    )
}
