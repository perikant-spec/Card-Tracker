package tracker.core

/** The six tracked cards. Card id == lower-case issuer key. last4 starts blank: the user fills it in. */
object Defaults {
    const val HDFC = "HDFC"
    const val ICICI = "ICICI"
    const val SCAPIA = "SCAPIA"
    const val KIWI = "KIWI"
    const val SBI = "SBI"
    const val AXIS = "AXIS"

    val ISSUERS: List<String> = listOf(HDFC, ICICI, SCAPIA, KIWI, SBI, AXIS)

    fun cardId(issuer: String): String = issuer.lowercase()

    fun cards(): List<CardConfig> = ISSUERS.map {
        CardConfig(id = cardId(it), issuer = it, last4 = "", statementDay = 1, dueDay = 20)
    }

    /**
     * Editable starting points only, NOT verified facts about what each bank sends from.
     * Scapia and Kiwi are deliberately empty: run `tools/extract_fixtures.py list-senders`
     * on your own export and type in what you see.
     */
    fun allowlist(): Map<String, List<String>> = linkedMapOf(
        HDFC to listOf("HDFCBK"),
        ICICI to listOf("ICICIB"),
        SCAPIA to emptyList(),
        KIWI to emptyList(),
        SBI to listOf("SBICRD"),
        AXIS to listOf("AXISBK"),
    )

    const val SETTING_SEEDED = "seeded"
    const val SETTING_STALE_PENDING_DAYS = "stale_pending_days"
    const val SETTING_BACKFILL_MONTHS = "backfill_months"
    const val DEFAULT_BACKFILL_MONTHS = 12
}

object SenderAllowlist {
    const val MIN_PATTERN_LENGTH = 3

    /** Trim, upper-case, drop blanks/too-short entries (a blank pattern must never match everything). */
    fun clean(patterns: List<String>): List<String> =
        patterns.map { it.trim().uppercase() }.filter { it.length >= MIN_PATTERN_LENGTH }.distinct()

    /** Case-insensitive substring match, e.g. pattern HDFCBK matches sender "VM-HDFCBK-S". */
    fun matches(patterns: List<String>, sender: String): Boolean {
        val s = sender.trim().uppercase()
        if (s.isEmpty()) return false
        return clean(patterns).any { s.contains(it) }
    }
}
