package tracker.core.parsers

/**
 * SBI templates.
 *
 * UNVERIFIED TEMPLATE: written without real fixtures.
 * Nothing here has been checked against a real SBI message. The card phrase and every shape
 * are assumptions to be corrected from fixtures/real/ (see docs/UNVERIFIED.md).
 */
object SbiTemplates {
    // UNVERIFIED TEMPLATE: written without real fixtures
    /** How this issuer is assumed to name the card in its messages. */
    const val CARD_PHRASE = "(?:SBI Card |SBICard |SBI )?(?:Credit )?Card"

    val ALL: List<SmsTemplate> = Shapes.standard("sbi", CARD_PHRASE) + listOf<SmsTemplate>(
    )
}
