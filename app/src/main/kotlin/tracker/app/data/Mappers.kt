package tracker.app.data

import tracker.core.CardConfig
import tracker.core.Defaults
import tracker.core.Paise
import tracker.core.ProcessedSms
import tracker.core.ReviewItem
import tracker.core.ReviewKind
import tracker.core.ReviewStatus
import tracker.core.SnapshotCodec
import tracker.core.Statement
import tracker.core.StatementProposal
import tracker.core.Txn
import tracker.core.TxnState
import tracker.core.TxnType
import java.time.LocalDate

// Pure functions between Room rows and the :core model. No Android types, so they are unit-tested on the JVM.

fun CardConfig.toEntity() = CardEntity(id, issuer, last4, statementDay, dueDay)

fun CardEntity.toModel() = CardConfig(id, issuer, last4, statementDay, dueDay)

/** Cards in the fixed display order (HDFC, ICICI, Scapia, Kiwi, SBI, Axis); unknown issuers last. */
fun sortCards(cards: List<CardConfig>): List<CardConfig> =
    cards.sortedWith(
        compareBy<CardConfig> { Defaults.ISSUERS.indexOf(it.issuer.uppercase()).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.id },
    )

fun Txn.toEntity() = TxnEntity(
    id = id,
    cardId = cardId,
    dateEpochDay = date.toEpochDay(),
    amountPaise = amount.value,
    type = type.name,
    state = state.name,
    merchant = merchant,
    ref = ref,
    linkedTo = linkedTo,
    sourceHash = sourceHash,
    note = note,
)

fun TxnEntity.toModel() = Txn(
    id = id,
    cardId = cardId,
    date = LocalDate.ofEpochDay(dateEpochDay),
    amount = Paise(amountPaise),
    type = TxnType.valueOf(type),
    state = TxnState.valueOf(state),
    merchant = merchant,
    ref = ref,
    linkedTo = linkedTo,
    sourceHash = sourceHash,
    note = note,
)

fun Statement.toEntity() = StatementEntity(
    cardId = cardId,
    statementEpochDay = statementDate.toEpochDay(),
    totalDuePaise = totalDue.value,
    minDuePaise = minDue?.value,
    dueEpochDay = dueDate?.toEpochDay(),
)

fun StatementEntity.toModel() = Statement(
    cardId = cardId,
    statementDate = LocalDate.ofEpochDay(statementEpochDay),
    totalDue = Paise(totalDuePaise),
    minDue = minDuePaise?.let { Paise(it) },
    dueDate = dueEpochDay?.let { LocalDate.ofEpochDay(it) },
)

fun StatementProposal.toEntity() = ProposalEntity(
    id = id,
    cardId = cardId,
    statementEpochDay = statementDate.toEpochDay(),
    totalDuePaise = totalDue.value,
    minDuePaise = minDue?.value,
    dueEpochDay = dueDate?.toEpochDay(),
    sourceHash = sourceHash,
    createdAtMs = createdAtMs,
)

fun ProposalEntity.toModel() = StatementProposal(
    id = id,
    cardId = cardId,
    statementDate = LocalDate.ofEpochDay(statementEpochDay),
    totalDue = Paise(totalDuePaise),
    minDue = minDuePaise?.let { Paise(it) },
    dueDate = dueEpochDay?.let { LocalDate.ofEpochDay(it) },
    sourceHash = sourceHash,
    createdAtMs = createdAtMs,
)

fun ReviewItem.toEntity() = ReviewEntity(
    id = id,
    kind = kind.name,
    status = status.name,
    createdAtMs = createdAtMs,
    sender = sender,
    body = body,
    receivedAtMs = receivedAtMs,
    smsHash = smsHash,
    cardId = cardId,
    txnId = txnId,
    candidateIds = candidateIds.joinToString("\n"),
    proposedJson = proposed?.let { SnapshotCodec.txnToJson(it) },
    reason = reason,
)

fun ReviewEntity.toModel() = ReviewItem(
    id = id,
    kind = ReviewKind.valueOf(kind),
    status = ReviewStatus.valueOf(status),
    createdAtMs = createdAtMs,
    sender = sender,
    body = body,
    receivedAtMs = receivedAtMs,
    smsHash = smsHash,
    cardId = cardId,
    txnId = txnId,
    candidateIds = if (candidateIds.isEmpty()) emptyList() else candidateIds.split("\n"),
    proposed = proposedJson?.let { SnapshotCodec.txnFromJson(it) },
    reason = reason,
)

fun ProcessedSms.toEntity() = ProcessedSmsEntity(hash, bodyHash, receivedAtMs)

fun ProcessedSmsEntity.toModel() = ProcessedSms(hash, bodyHash, receivedAtMs)

fun allowlistRows(issuer: String, patterns: List<String>): List<AllowlistEntity> =
    patterns.mapIndexed { index, pattern -> AllowlistEntity(issuer, index, pattern) }

/** Every known issuer appears as a key, even with no sender IDs yet (Scapia and Kiwi start empty). */
fun allowlistMap(rows: List<AllowlistEntity>, issuers: List<String>): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    for (issuer in issuers) out[issuer] = ArrayList()
    for (row in rows.sortedWith(compareBy({ it.issuer }, { it.position }))) {
        out.getOrPut(row.issuer) { ArrayList() }.add(row.pattern)
    }
    return out
}
