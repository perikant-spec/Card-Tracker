package tracker.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/** Blocking DAO: the engine is synchronous and always runs on a background dispatcher. */
@Dao
interface LedgerDao {
    @Query("SELECT * FROM cards")
    fun cards(): List<CardEntity>

    @Upsert
    fun upsertCard(card: CardEntity)

    @Query("SELECT * FROM txns")
    fun txns(): List<TxnEntity>

    @Query("SELECT * FROM txns WHERE cardId = :cardId")
    fun txnsForCard(cardId: String): List<TxnEntity>

    @Query("SELECT * FROM txns WHERE id = :id")
    fun txn(id: String): TxnEntity?

    @Upsert
    fun upsertTxn(txn: TxnEntity)

    @Query("DELETE FROM txns WHERE id = :id")
    fun deleteTxn(id: String)

    @Query("SELECT * FROM statements")
    fun statements(): List<StatementEntity>

    @Query("SELECT * FROM statements WHERE cardId = :cardId")
    fun statementsForCard(cardId: String): List<StatementEntity>

    @Upsert
    fun upsertStatement(statement: StatementEntity)

    @Query("DELETE FROM statements WHERE cardId = :cardId AND statementEpochDay = :statementEpochDay")
    fun deleteStatement(cardId: String, statementEpochDay: Long)

    @Query("SELECT * FROM statement_proposals")
    fun proposals(): List<ProposalEntity>

    @Upsert
    fun upsertProposal(proposal: ProposalEntity)

    @Query("DELETE FROM statement_proposals WHERE id = :id")
    fun deleteProposal(id: String)

    @Query("SELECT * FROM review_items")
    fun reviewItems(): List<ReviewEntity>

    @Query("SELECT * FROM review_items WHERE id = :id")
    fun reviewItem(id: String): ReviewEntity?

    @Upsert
    fun upsertReviewItem(item: ReviewEntity)

    @Query("SELECT * FROM processed_sms WHERE hash = :hash")
    fun processed(hash: String): ProcessedSmsEntity?

    @Query("SELECT * FROM processed_sms WHERE bodyHash = :bodyHash")
    fun processedByBodyHash(bodyHash: String): List<ProcessedSmsEntity>

    @Query("SELECT * FROM processed_sms")
    fun allProcessed(): List<ProcessedSmsEntity>

    @Upsert
    fun markProcessed(processed: ProcessedSmsEntity)

    @Query("SELECT * FROM sender_allowlist ORDER BY issuer, position")
    fun allowlist(): List<AllowlistEntity>

    @Query("DELETE FROM sender_allowlist WHERE issuer = :issuer")
    fun clearAllowlist(issuer: String)

    @Insert
    fun insertAllowlist(rows: List<AllowlistEntity>)

    @Query("SELECT * FROM settings")
    fun settings(): List<SettingEntity>

    @Upsert
    fun putSetting(setting: SettingEntity)

    @Query("DELETE FROM cards")
    fun deleteAllCards()

    @Query("DELETE FROM txns")
    fun deleteAllTxns()

    @Query("DELETE FROM statements")
    fun deleteAllStatements()

    @Query("DELETE FROM statement_proposals")
    fun deleteAllProposals()

    @Query("DELETE FROM review_items")
    fun deleteAllReviewItems()

    @Query("DELETE FROM processed_sms")
    fun deleteAllProcessed()

    @Query("DELETE FROM sender_allowlist")
    fun deleteAllAllowlist()

    @Query("DELETE FROM settings")
    fun deleteAllSettings()
}
