package tracker.app.data

import tracker.core.CardConfig
import tracker.core.LedgerStore
import tracker.core.ProcessedSms
import tracker.core.ReviewItem
import tracker.core.Statement
import tracker.core.StatementProposal
import tracker.core.Txn
import java.time.LocalDate

/** Room adapter for the engine's storage port. Contains no accounting logic. */
class RoomLedgerStore(private val db: LedgerDatabase) : LedgerStore {
    private val dao: LedgerDao = db.dao()

    /** Uses the Runnable overload, whose signature is the same in every Room 2.x release. */
    override fun <T> transaction(block: () -> T): T {
        var result: Any? = null
        db.runInTransaction(Runnable { result = block() })
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun cards(): List<CardConfig> = sortCards(dao.cards().map { it.toModel() })
    override fun upsertCard(card: CardConfig) = dao.upsertCard(card.toEntity())

    override fun txns(): List<Txn> = dao.txns().map { it.toModel() }
    override fun txnsForCard(cardId: String): List<Txn> = dao.txnsForCard(cardId).map { it.toModel() }
    override fun txn(id: String): Txn? = dao.txn(id)?.toModel()
    override fun upsertTxn(txn: Txn) = dao.upsertTxn(txn.toEntity())
    override fun deleteTxn(id: String) = dao.deleteTxn(id)

    override fun statements(): List<Statement> = dao.statements().map { it.toModel() }
    override fun statementsForCard(cardId: String): List<Statement> = dao.statementsForCard(cardId).map { it.toModel() }
    override fun upsertStatement(statement: Statement) = dao.upsertStatement(statement.toEntity())
    override fun deleteStatement(cardId: String, statementDate: LocalDate) =
        dao.deleteStatement(cardId, statementDate.toEpochDay())

    override fun proposals(): List<StatementProposal> = dao.proposals().map { it.toModel() }
    override fun upsertProposal(proposal: StatementProposal) = dao.upsertProposal(proposal.toEntity())
    override fun deleteProposal(id: String) = dao.deleteProposal(id)

    override fun reviewItems(): List<ReviewItem> = dao.reviewItems().map { it.toModel() }
    override fun reviewItem(id: String): ReviewItem? = dao.reviewItem(id)?.toModel()
    override fun upsertReviewItem(item: ReviewItem) = dao.upsertReviewItem(item.toEntity())

    override fun processed(hash: String): ProcessedSms? = dao.processed(hash)?.toModel()
    override fun processedByBodyHash(bodyHash: String): List<ProcessedSms> = dao.processedByBodyHash(bodyHash).map { it.toModel() }
    override fun allProcessed(): List<ProcessedSms> = dao.allProcessed().map { it.toModel() }
    override fun markProcessed(processed: ProcessedSms) = dao.markProcessed(processed.toEntity())

    override fun allowlist(): Map<String, List<String>> =
        allowlistMap(dao.allowlist(), dao.cards().map { it.issuer })

    override fun setAllowlist(issuer: String, patterns: List<String>) {
        dao.clearAllowlist(issuer)
        if (patterns.isNotEmpty()) dao.insertAllowlist(allowlistRows(issuer, patterns))
    }

    override fun settings(): Map<String, String> = dao.settings().associate { it.name to it.value }
    override fun putSetting(key: String, value: String) = dao.putSetting(SettingEntity(key, value))

    override fun clearAll() {
        dao.deleteAllCards()
        dao.deleteAllTxns()
        dao.deleteAllStatements()
        dao.deleteAllProposals()
        dao.deleteAllReviewItems()
        dao.deleteAllProcessed()
        dao.deleteAllAllowlist()
        dao.deleteAllSettings()
    }
}
