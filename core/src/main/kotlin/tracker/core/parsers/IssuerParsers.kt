package tracker.core.parsers

import tracker.core.Defaults
import tracker.core.IssuerParser
import tracker.core.SmsParser

/**
 * Registry: issuer key -> template list. All templates are UNVERIFIED (see each issuer file).
 * Sender IDs are never hardcoded here; they come from the user-editable allowlist.
 */
object IssuerParsers {
    val TEMPLATES: Map<String, List<SmsTemplate>> = linkedMapOf(
        Defaults.HDFC to HdfcTemplates.ALL,
        Defaults.ICICI to IciciTemplates.ALL,
        Defaults.SCAPIA to ScapiaTemplates.ALL,
        Defaults.KIWI to KiwiTemplates.ALL,
        Defaults.SBI to SbiTemplates.ALL,
        Defaults.AXIS to AxisTemplates.ALL,
    )

    fun parser(issuer: String, senderPatterns: List<String>): IssuerParser? {
        val key = issuer.trim().uppercase()
        val templates = TEMPLATES[key] ?: return null
        return TemplateIssuerParser(key, templates, senderPatterns)
    }

    /** Router over the issuers present in [allowlist] (issuer -> sender-ID substrings). */
    fun router(allowlist: Map<String, List<String>>): SmsParser =
        AllowlistRouter(allowlist.mapNotNull { (issuer, patterns) -> parser(issuer, patterns) })
}
