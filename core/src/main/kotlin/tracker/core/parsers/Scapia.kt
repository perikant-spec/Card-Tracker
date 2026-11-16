package tracker.core.parsers

/**
 * Scapia templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real Scapia message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object ScapiaTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:Federal Bank |Federal )?(?:Scapia )?(?:Federal Bank )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("scapia", CARD_PHRASE) + listOf<SmsTemplate>(
    )
}
