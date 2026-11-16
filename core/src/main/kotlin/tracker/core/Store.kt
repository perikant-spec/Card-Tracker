package tracker.core

import java.time.LocalDate

/** One incoming message exactly as the platform delivered it. Never persisted as a whole. */
data class RawSms(val sender: String, val body: String, val receivedAtMs: Long)

/** A statement notice seen in an SMS. It changes nothing until the user confirms it. */
data class StatementProposal(
    val id: String,
    val cardId: String,
    val statementDate: LocalDate,
    val totalDue: Paise,
    val minDue: Paise? = null,
    val dueDate: LocalDate? = null,
    val sourceHash: String? = null,
    val createdAtMs: Long = 0,
)

enum class ReviewKind {
    /** Allowlisted sender, no template matched exactly. */
    UNPARSED,
    /** More than one ledger row could be the counterpart of this message. */
    AMBIGUOUS_MATCH,
    /** A credit arrived for a purchase that already has a reversal/refund recorded. */
    AMBIGUOUS_REFUND,
    /** Same sender and text as a message already processed a short time earlier/later. */
    POSSIBLE_DUPLICATE,
    /** An authorisation that never settled within the configured number of days. */
    STALE_PENDING,
}

enum class ReviewStatus { OPEN, RESOLVED, DISMISSED }

/**
 * Something the engine refused to decide on its own.
 * [body] is kept only while the item is OPEN and is wiped when it is resolved or dismissed.
 * [proposed] is the row the engine would have written had there been no doubt.
 */
data class ReviewItem(
    val id: String,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val createdAtMs: Long,
    val sender: String? = null,
    val body: String? = null,
    val receivedAtMs: Long? = null,
    val smsHash: String? = null,
    val cardId: String? = null,
    val txnId: String? = null,
    val candidateIds: List<String> = emptyList(),
    val proposed: Txn? = null,
    val reason: String? = null,
)

/** Marker that an SMS was already turned into a ledger row / proposal / review item. */
data class ProcessedSms(val hash: String, val bodyHash: String, val receivedAtMs: Long)

/** Everything the app stores, in one value. */
data class LedgerSnapshot(
    val cards: List<CardConfig> = emptyList(),
    val txns: List<Txn> = emptyList(),
    val statements: List<Statement> = emptyList(),
    val proposals: List<StatementProposal> = emptyList(),
    val reviewItems: List<ReviewItem> = emptyList(),
    val processed: List<ProcessedSms> = emptyList(),
    val allowlist: Map<String, List<String>> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
)

/**
 * Storage port. Room is one adapter, [InMemoryLedgerStore] is another.
 * All calls are blocking; callers serialise access (the engine is not thread-safe by itself).
 */
interface LedgerStore {
    fun <T> transaction(block: () -> T): T

    fun cards(): List<CardConfig>
    fun upsertCard(card: CardConfig)

    fun txns(): List<Txn>
    fun txnsForCard(cardId: String): List<Txn>
    fun txn(id: String): Txn?
    fun upsertTxn(txn: Txn)
    fun deleteTxn(id: String)

    /** Confirmed statements only, any order. */
    fun statements(): List<Statement>
    fun statementsForCard(cardId: String): List<Statement>
    fun upsertStatement(statement: Statement)
    fun deleteStatement(cardId: String, statementDate: LocalDate)

    fun proposals(): List<StatementProposal>
    fun upsertProposal(proposal: StatementProposal)
    fun deleteProposal(id: String)

    fun reviewItems(): List<ReviewItem>
    fun reviewItem(id: String): ReviewItem?
    fun upsertReviewItem(item: ReviewItem)

    fun processed(hash: String): ProcessedSms?
    fun processedByBodyHash(bodyHash: String): List<ProcessedSms>
    fun allProcessed(): List<ProcessedSms>
    fun markProcessed(processed: ProcessedSms)

    /** Issuer -> sender-ID substrings the user allows. */
    fun allowlist(): Map<String, List<String>>
    fun setAllowlist(issuer: String, patterns: List<String>)

    fun settings(): Map<String, String>
    fun putSetting(key: String, value: String)

    /** Wipes everything. Used only by restore-from-backup. */
    fun clearAll()
}

class InMemoryLedgerStore : LedgerStore {
    private val cards = LinkedHashMap<String, CardConfig>()
    private val txns = LinkedHashMap<String, Txn>()
    private val statements = LinkedHashMap<Pair<String, LocalDate>, Statement>()
    private val proposals = LinkedHashMap<String, StatementProposal>()
    private val reviews = LinkedHashMap<String, ReviewItem>()
    private val processed = LinkedHashMap<String, ProcessedSms>()
    private val allowlist = LinkedHashMap<String, List<String>>()
    private val settings = LinkedHashMap<String, String>()

    override fun <T> transaction(block: () -> T): T = block()

    override fun cards(): List<CardConfig> = cards.values.toList()
    override fun upsertCard(card: CardConfig) { cards[card.id] = card }

    override fun txns(): List<Txn> = txns.values.toList()
    override fun txnsForCard(cardId: String): List<Txn> = txns.values.filter { it.cardId == cardId }
    override fun txn(id: String): Txn? = txns[id]
    override fun upsertTxn(txn: Txn) { txns[txn.id] = txn }
    override fun deleteTxn(id: String) { txns.remove(id) }

    override fun statements(): List<Statement> = statements.values.toList()
    override fun statementsForCard(cardId: String): List<Statement> = statements.values.filter { it.cardId == cardId }
    override fun upsertStatement(statement: Statement) { statements[statement.cardId to statement.statementDate] = statement }
    override fun deleteStatement(cardId: String, statementDate: LocalDate) { statements.remove(cardId to statementDate) }

    override fun proposals(): List<StatementProposal> = proposals.values.toList()
    override fun upsertProposal(proposal: StatementProposal) { proposals[proposal.id] = proposal }
    override fun deleteProposal(id: String) { proposals.remove(id) }

    override fun reviewItems(): List<ReviewItem> = reviews.values.toList()
    override fun reviewItem(id: String): ReviewItem? = reviews[id]
    override fun upsertReviewItem(item: ReviewItem) { reviews[item.id] = item }

    override fun processed(hash: String): ProcessedSms? = processed[hash]
    override fun processedByBodyHash(bodyHash: String): List<ProcessedSms> = processed.values.filter { it.bodyHash == bodyHash }
    override fun allProcessed(): List<ProcessedSms> = processed.values.toList()
    override fun markProcessed(processed: ProcessedSms) { this.processed[processed.hash] = processed }

    override fun allowlist(): Map<String, List<String>> = LinkedHashMap(allowlist)
    override fun setAllowlist(issuer: String, patterns: List<String>) { allowlist[issuer] = patterns.toList() }

    override fun settings(): Map<String, String> = LinkedHashMap(settings)
    override fun putSetting(key: String, value: String) { settings[key] = value }

    override fun clearAll() {
        cards.clear(); txns.clear(); statements.clear(); proposals.clear()
        reviews.clear(); processed.clear(); allowlist.clear(); settings.clear()
    }
}
