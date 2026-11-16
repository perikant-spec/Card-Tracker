package tracker.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Storage rows. Money is always a Long of paise; dates are epoch days; enums are stored by name.
// The mapping to and from the :core model lives in Mappers.kt and is unit-tested on the JVM.

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val issuer: String,
    val last4: String,
    val statementDay: Int,
    val dueDay: Int,
)

@Entity(tableName = "txns", indices = [Index("cardId"), Index("sourceHash")])
data class TxnEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val dateEpochDay: Long,
    val amountPaise: Long,
    val type: String,
    val state: String,
    val merchant: String?,
    val ref: String?,
    val linkedTo: String?,
    val sourceHash: String?,
    val note: String?,
)

@Entity(tableName = "statements", primaryKeys = ["cardId", "statementEpochDay"])
data class StatementEntity(
    val cardId: String,
    val statementEpochDay: Long,
    val totalDuePaise: Long,
    val minDuePaise: Long?,
    val dueEpochDay: Long?,
)

@Entity(tableName = "statement_proposals", indices = [Index("cardId")])
data class ProposalEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val statementEpochDay: Long,
    val totalDuePaise: Long,
    val minDuePaise: Long?,
    val dueEpochDay: Long?,
    val sourceHash: String?,
    val createdAtMs: Long,
)

@Entity(tableName = "review_items", indices = [Index("status")])
data class ReviewEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val status: String,
    val createdAtMs: Long,
    val sender: String?,
    /** SMS text; present only while the item is open (the engine wipes it on close). */
    val body: String?,
    val receivedAtMs: Long?,
    val smsHash: String?,
    val cardId: String?,
    val txnId: String?,
    /** Transaction ids separated by '\n'. */
    val candidateIds: String,
    /** The row the engine would have written, as JSON (see SnapshotCodec.txnToJson). */
    val proposedJson: String?,
    val reason: String?,
)

@Entity(tableName = "sender_allowlist", primaryKeys = ["issuer", "position"])
data class AllowlistEntity(
    val issuer: String,
    val position: Int,
    val pattern: String,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val name: String,
    val value: String,
)

@Entity(tableName = "processed_sms", indices = [Index("bodyHash")])
data class ProcessedSmsEntity(
    @PrimaryKey val hash: String,
    val bodyHash: String,
    val receivedAtMs: Long,
)
