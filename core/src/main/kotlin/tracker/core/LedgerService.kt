package tracker.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class EngineConfig(
    /** A PENDING authorisation older than this is sent to the review queue. Overridable in settings. */
    val stalePendingDays: Long = 10,
    /** How far back a refund/reversal looks for the purchase it cancels. */
    val refundWindowDays: Long = 60,
    /** Same sender + same text within this many minutes of a processed message => POSSIBLE_DUPLICATE. */
    val duplicateWindowMinutes: Long = 180,
    /** A date printed in an SMS further than this from the day it was received is not trusted. */
    val dateToleranceDays: Long = 7,
    /** Days after "statement date + 1 month" before a card is shown as STALE. */
    val staleStatementGraceDays: Long = 7,
)

sealed interface IngestOutcome {
    /** Sender is on no allowlist. Nothing was parsed and nothing was stored. */
    data object NotAllowlisted : IngestOutcome
    /** This exact message was already processed. */
    data object Duplicate : IngestOutcome
    /** OTP / promo / bank-account alert. Dropped, never stored. */
    data class Ignored(val reason: String) : IngestOutcome
    /** Parsed, but the card digits are not one of the configured cards. Nothing stored. */
    data object UntrackedCard : IngestOutcome
    data class Applied(val txnId: String, val state: TxnState) : IngestOutcome
    /** A statement notice was stored as a proposal. Balances are unchanged until confirmed. */
    data class Proposed(val proposalId: String) : IngestOutcome
    data class Queued(val reviewId: String, val kind: ReviewKind) : IngestOutcome
}

/** Turns one SMS into a [ParseResult]; null means "sender not allowlisted, do not read further". */
fun interface SmsParser {
    fun route(sender: String, body: String, receivedAt: Instant): ParseResult?
}

/**
 * The only place that changes the ledger. Android code calls this and nothing else.
 * Not thread-safe: callers must serialise calls (the app does so with one mutex).
 */
class LedgerService(
    private val store: LedgerStore,
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val config: EngineConfig = EngineConfig(),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val parserFactory: (Map<String, List<String>>) -> SmsParser,
) {
    private var cachedAllowlist: Map<String, List<String>>? = null
    private var cachedParser: SmsParser? = null

    // ---------------------------------------------------------------- setup

    /** Creates the six cards and the editable default allowlist on first run. Idempotent. */
    fun seedDefaults() = store.transaction {
        if (store.settings()[Defaults.SETTING_SEEDED] == null) {
            val existing = store.cards().map { it.id }.toSet()
            for (card in Defaults.cards()) if (card.id !in existing) store.upsertCard(card)
            val current = store.allowlist()
            for ((issuer, patterns) in Defaults.allowlist()) {
                if (!current.containsKey(issuer)) store.setAllowlist(issuer, patterns)
            }
            store.putSetting(Defaults.SETTING_SEEDED, "1")
        }
    }

    fun cards(): List<CardConfig> = store.cards()

    fun saveCard(card: CardConfig) {
        require(card.last4.isEmpty() || (card.last4.length == 4 && card.last4.all { it in '0'..'9' })) { "last4 must be blank or 4 digits" }
        require(card.statementDay in 1..31) { "statement day must be 1..31" }
        require(card.dueDay in 1..31) { "due day must be 1..31" }
        store.transaction { store.upsertCard(card) }
    }

    fun allowlist(): Map<String, List<String>> = store.allowlist()

    fun setAllowlist(issuer: String, patterns: List<String>) =
        store.transaction { store.setAllowlist(issuer, SenderAllowlist.clean(patterns)) }

    fun isAllowlisted(sender: String): Boolean =
        store.allowlist().values.any { SenderAllowlist.matches(it, sender) }

    fun setting(key: String): String? = store.settings()[key]

    fun putSetting(key: String, value: String) = store.transaction { store.putSetting(key, value) }

    // --------------------------------------------------------------- ingest

    fun ingest(sms: RawSms): IngestOutcome = store.transaction { ingestInternal(sms) }

    private fun parserFor(allowlist: Map<String, List<String>>): SmsParser {
        val cached = cachedParser
        if (cached != null && cachedAllowlist == allowlist) return cached
        val fresh = parserFactory(allowlist)
        cachedAllowlist = allowlist
        cachedParser = fresh
        return fresh
    }

    private fun ingestInternal(sms: RawSms): IngestOutcome {
        val allowlist = store.allowlist()
        if (!allowlist.values.any { SenderAllowlist.matches(it, sms.sender) }) return IngestOutcome.NotAllowlisted
        val result = parserFor(allowlist).route(sms.sender, sms.body, Instant.ofEpochMilli(sms.receivedAtMs))
            ?: return IngestOutcome.NotAllowlisted

        return when (result) {
            is ParseResult.Ignored -> IngestOutcome.Ignored(result.reason)
            is ParseResult.Unparsed -> {
                val hash = Hashing.smsHash(sms)
                if (store.processed(hash) != null || nearDuplicate(sms)) {
                    IngestOutcome.Duplicate
                } else {
                    queue(ReviewKind.UNPARSED, sms, hash, reason = "No template matched this message exactly.")
                }
            }
            is ParseResult.Parsed -> applyDraft(sms, result.draft)
        }
    }

    private fun nearDuplicate(sms: RawSms): Boolean {
        val windowMs = config.duplicateWindowMinutes * 60_000L
        return store.processedByBodyHash(Hashing.bodyHash(sms)).any { Math.abs(it.receivedAtMs - sms.receivedAtMs) <= windowMs }
    }

    private fun remember(sms: RawSms, hash: String) {
        store.markProcessed(ProcessedSms(hash, Hashing.bodyHash(sms), sms.receivedAtMs))
    }

    private fun queue(
        kind: ReviewKind,
        sms: RawSms,
        hash: String,
        cardId: String? = null,
        candidates: List<Txn> = emptyList(),
        proposed: Txn? = null,
        reason: String? = null,
    ): IngestOutcome.Queued {
        val item = ReviewItem(
            id = idGen(),
            kind = kind,
            status = ReviewStatus.OPEN,
            createdAtMs = nowMs(),
            sender = sms.sender,
            body = sms.body,
            receivedAtMs = sms.receivedAtMs,
            smsHash = hash,
            cardId = cardId,
            candidateIds = candidates.map { it.id },
            proposed = proposed,
            reason = reason,
        )
        store.upsertReviewItem(item)
        remember(sms, hash)
        return IngestOutcome.Queued(item.id, kind)
    }

    /** Unique last4 wins; the issuer is consulted only when two cards share the same digits. */
    private fun resolveCard(draft: ParsedDraft): CardConfig? {
        if (draft.cardLast4.length != 4) return null
        val byDigits = store.cards().filter { it.last4 == draft.cardLast4 }
        if (byDigits.size <= 1) return byDigits.firstOrNull()
        val byIssuer = byDigits.filter { it.issuer.equals(draft.issuer, ignoreCase = true) }
        return if (byIssuer.size == 1) byIssuer.single() else null
    }

    private fun applyDraft(sms: RawSms, draft: ParsedDraft): IngestOutcome {
        val hash = Hashing.smsHash(sms)
        if (store.processed(hash) != null) return IngestOutcome.Duplicate
        val card = resolveCard(draft) ?: return IngestOutcome.UntrackedCard
        val receivedDate = Instant.ofEpochMilli(sms.receivedAtMs).atZone(zone).toLocalDate()

        val statementDraft = draft.statement
        if (statementDraft != null) return proposeStatement(sms, hash, card, statementDraft, receivedDate)

        val date = draft.txnDate ?: receivedDate
        val initialState = if (draft.type == TxnType.PURCHASE && draft.isAuthorisation) TxnState.PENDING else TxnState.POSTED
        val row = Txn(
            id = idGen(),
            cardId = card.id,
            date = date,
            amount = draft.amount,
            type = draft.type,
            state = initialState,
            merchant = draft.merchant,
            ref = draft.ref,
            sourceHash = hash,
        )

        if (draft.amount.value <= 0L) {
            return queue(ReviewKind.UNPARSED, sms, hash, card.id, reason = "Amount in the message is not positive.")
        }
        if (Math.abs(ChronoUnit.DAYS.between(date, receivedDate)) > config.dateToleranceDays) {
            return queue(
                ReviewKind.UNPARSED, sms, hash, card.id, proposed = row.copy(date = receivedDate),
                reason = "The date printed in the message is far from the day it was received.",
            )
        }
        if (nearDuplicate(sms)) {
            return queue(
                ReviewKind.POSSIBLE_DUPLICATE, sms, hash, card.id, proposed = row,
                reason = "Same sender and text as a message already processed around the same time.",
            )
        }

        return when (draft.type) {
            TxnType.PURCHASE -> if (draft.isAuthorisation) insert(sms, hash, row) else settle(sms, hash, row)
            TxnType.FEE, TxnType.PAYMENT -> insert(sms, hash, row)
            TxnType.REFUND -> credit(sms, hash, row, draft.isReversal)
        }
    }

    private fun insert(sms: RawSms, hash: String, row: Txn): IngestOutcome {
        store.upsertTxn(row)
        remember(sms, hash)
        return IngestOutcome.Applied(row.id, row.state)
    }

    private fun settle(sms: RawSms, hash: String, row: Txn): IngestOutcome {
        return when (val match = PendingMatcher.match(row, store.txnsForCard(row.cardId))) {
            is MatchResult.None -> insert(sms, hash, row)
            is MatchResult.Matched -> {
                val promoted = promote(match.pending, row)
                remember(sms, hash)
                IngestOutcome.Applied(promoted.id, promoted.state)
            }
            is MatchResult.Ambiguous -> queue(
                ReviewKind.AMBIGUOUS_MATCH, sms, hash, row.cardId, match.candidates, row,
                "More than one pending authorisation could be this purchase.",
            )
        }
    }

    private fun promote(pending: Txn, settled: Txn): Txn {
        val promoted = pending.copy(state = TxnState.POSTED, date = settled.date, ref = pending.ref ?: settled.ref)
        store.upsertTxn(promoted)
        return promoted
    }

    private fun norm(s: String?) = s?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /** Purchases/fees this credit could be cancelling in full. */
    private fun originalsFor(credit: Txn, all: List<Txn>): List<Txn> {
        val inWindow = all.filter {
            it.id != credit.id &&
                (it.type == TxnType.PURCHASE || it.type == TxnType.FEE) &&
                it.state != TxnState.ADJUSTMENT &&
                !it.date.isAfter(credit.date) &&
                ChronoUnit.DAYS.between(it.date, credit.date) <= config.refundWindowDays
        }
        if (credit.ref != null) {
            val byRef = inWindow.filter { it.ref == credit.ref && it.amount == credit.amount }
            if (byRef.isNotEmpty()) return byRef
        }
        return inWindow.filter {
            it.amount == credit.amount && (credit.merchant == null || norm(it.merchant) == norm(credit.merchant))
        }
    }

    private fun alreadyCredited(original: Txn, all: List<Txn>): Boolean =
        original.state == TxnState.REVERSED ||
            all.any { it.type == TxnType.REFUND && it.linkedTo == original.id && it.state != TxnState.ADJUSTMENT }

    private fun credit(sms: RawSms, hash: String, row: Txn, isReversal: Boolean): IngestOutcome {
        val all = store.txnsForCard(row.cardId)
        val candidates = originalsFor(row, all)
        val open = candidates.filter { !alreadyCredited(it, all) }
        val closed = candidates.filter { alreadyCredited(it, all) }

        if (open.isEmpty() && closed.isNotEmpty()) {
            return queue(
                ReviewKind.AMBIGUOUS_REFUND, sms, hash, row.cardId, closed, row,
                "The matching purchase already has a reversal or refund. This may be a second notice for the same credit.",
            )
        }
        if (open.size == 1) {
            val applied = applyCreditTo(open.single(), row, isReversal)
            remember(sms, hash)
            return IngestOutcome.Applied(applied.id, applied.state)
        }
        if (open.size > 1 && isReversal) {
            return queue(
                ReviewKind.AMBIGUOUS_MATCH, sms, hash, row.cardId, open, row,
                "More than one purchase could be the one that was reversed.",
            )
        }
        // No identifiable original (or several, for a plain refund): the credit itself is still certain.
        return insert(sms, hash, row)
    }

    private fun statementBetween(cardId: String, from: LocalDate, to: LocalDate): Boolean =
        store.statementsForCard(cardId).any { !it.statementDate.isBefore(from) && it.statementDate.isBefore(to) }

    /**
     * Links [credit] to [original] and returns the row that represents the outcome.
     *  - PENDING original: it never counted, so it is simply marked REVERSED and no credit row is added.
     *  - Reversal inside one statement cycle: both rows are stored as REVERSED (they cancel out).
     *  - Reversal across a confirmed statement date, or a refund: the original stays POSTED (the
     *    bank already billed it) and the credit is a POSTED REFUND linked to it.
     */
    private fun applyCreditTo(original: Txn, credit: Txn, isReversal: Boolean): Txn {
        if (original.state == TxnState.PENDING) {
            val reversed = original.copy(state = TxnState.REVERSED)
            store.upsertTxn(reversed)
            return reversed
        }
        val linked = credit.copy(linkedTo = original.id, merchant = credit.merchant ?: original.merchant)
        if (isReversal && !statementBetween(original.cardId, original.date, credit.date)) {
            store.upsertTxn(original.copy(state = TxnState.REVERSED))
            val pair = linked.copy(state = TxnState.REVERSED)
            store.upsertTxn(pair)
            return pair
        }
        val posted = linked.copy(state = TxnState.POSTED)
        store.upsertTxn(posted)
        return posted
    }

    /**
     * A REVERSED purchase/credit pair is only equivalent to "purchase + refund" while both sit in
     * the same cycle. If the user later confirms a statement dated between the two, the purchase
     * was billed in that statement and the credit belongs to the next cycle, so both go back to POSTED.
     */
    private fun normaliseReversalPairs(cardId: String) {
        val all = store.txnsForCard(cardId)
        val byId = all.associateBy { it.id }
        for (creditRow in all) {
            if (creditRow.type != TxnType.REFUND || creditRow.state != TxnState.REVERSED) continue
            val original = byId[creditRow.linkedTo ?: continue] ?: continue
            if (original.state != TxnState.REVERSED) continue
            if (statementBetween(cardId, original.date, creditRow.date)) {
                store.upsertTxn(original.copy(state = TxnState.POSTED))
                store.upsertTxn(creditRow.copy(state = TxnState.POSTED))
            }
        }
    }

    // ----------------------------------------------------------- statements

    private fun proposeStatement(sms: RawSms, hash: String, card: CardConfig, draft: StatementDraft, receivedDate: LocalDate): IngestOutcome {
        val statementDate = Dashboard.lastStatementDayOnOrBefore(receivedDate, card.statementDay)
        val confirmed = store.statementsForCard(card.id).any { it.statementDate == statementDate && it.totalDue == draft.totalDue }
        val proposedAlready = store.proposals().any { it.cardId == card.id && it.statementDate == statementDate && it.totalDue == draft.totalDue }
        if (confirmed || proposedAlready) {
            remember(sms, hash)
            return IngestOutcome.Duplicate
        }
        val proposal = StatementProposal(
            id = idGen(),
            cardId = card.id,
            statementDate = statementDate,
            totalDue = draft.totalDue,
            minDue = draft.minDue,
            dueDate = draft.dueDate,
            sourceHash = hash,
            createdAtMs = nowMs(),
        )
        store.upsertProposal(proposal)
        remember(sms, hash)
        return IngestOutcome.Proposed(proposal.id)
    }

    fun proposals(): List<StatementProposal> = store.proposals()

    fun statements(cardId: String): List<Statement> = store.statementsForCard(cardId).sortedBy { it.statementDate }

    /**
     * What confirming [candidate] would show: its total versus previous statement + ledger activity.
     * Null when there is no earlier statement (the candidate would be the opening anchor).
     */
    fun previewReconcile(candidate: Statement): ReconResult? {
        val previous = store.statementsForCard(candidate.cardId)
            .filter { it.statementDate.isBefore(candidate.statementDate) }
            .maxByOrNull { it.statementDate } ?: return null
        return Reconciler.reconcile(previous, candidate, store.txnsForCard(candidate.cardId))
    }

    /** The user typed or confirmed these figures. Only now do balances move. */
    fun confirmStatement(statement: Statement, proposalId: String? = null): ReconResult? = store.transaction {
        require(store.cards().any { it.id == statement.cardId }) { "unknown card" }
        store.upsertStatement(statement)
        if (proposalId != null) store.deleteProposal(proposalId)
        normaliseReversalPairs(statement.cardId)
        previewReconcile(statement)
    }

    fun rejectProposal(proposalId: String) = store.transaction { store.deleteProposal(proposalId) }

    /** Undo for a mistyped statement. */
    fun deleteStatement(cardId: String, statementDate: LocalDate) =
        store.transaction { store.deleteStatement(cardId, statementDate) }

    // ---------------------------------------------------------- adjustments

    /**
     * Explicit, user-confirmed correction. [signedAmount] > 0 increases what is owed.
     * To clear a statement variance, date it on the statement date with the variance as amount.
     */
    fun addAdjustment(cardId: String, date: LocalDate, signedAmount: Paise, note: String?): Txn = store.transaction {
        require(store.cards().any { it.id == cardId }) { "unknown card" }
        require(signedAmount.value != 0L) { "adjustment must be non-zero" }
        val row = Txn(
            id = idGen(), cardId = cardId, date = date, amount = signedAmount,
            type = TxnType.FEE, state = TxnState.ADJUSTMENT, note = note,
        )
        store.upsertTxn(row)
        row
    }

    /** Removes an ADJUSTMENT row. Refuses to touch anything else. */
    fun undoAdjustment(txnId: String): Boolean = store.transaction {
        val row = store.txn(txnId)
        if (row == null || row.state != TxnState.ADJUSTMENT) {
            false
        } else {
            store.deleteTxn(txnId)
            true
        }
    }

    // --------------------------------------------------------- review queue

    fun reviewItems(openOnly: Boolean = true): List<ReviewItem> =
        store.reviewItems().filter { !openOnly || it.status == ReviewStatus.OPEN }.sortedBy { it.createdAtMs }

    fun txn(id: String): Txn? = store.txn(id)

    fun txns(cardId: String): List<Txn> = store.txnsForCard(cardId).sortedByDescending { it.date }

    private fun openItem(reviewId: String): ReviewItem {
        val item = store.reviewItem(reviewId) ?: throw IllegalArgumentException("unknown review item")
        require(item.status == ReviewStatus.OPEN) { "review item is already closed" }
        return item
    }

    /** Closing an item wipes the SMS text it carried. */
    private fun close(item: ReviewItem, status: ReviewStatus) {
        store.upsertReviewItem(item.copy(status = status, body = null))
    }

    fun dismissReview(reviewId: String) = store.transaction { close(openItem(reviewId), ReviewStatus.DISMISSED) }

    /** The user reads the message and says what it is. */
    fun classifyManually(
        reviewId: String,
        cardId: String,
        type: TxnType,
        amount: Paise,
        date: LocalDate,
        merchant: String?,
        pending: Boolean = false,
    ): Txn = store.transaction {
        val item = openItem(reviewId)
        require(store.cards().any { it.id == cardId }) { "unknown card" }
        require(amount.value > 0L) { "amount must be positive" }
        val row = Txn(
            id = idGen(), cardId = cardId, date = date, amount = amount, type = type,
            state = if (pending && type == TxnType.PURCHASE) TxnState.PENDING else TxnState.POSTED,
            merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
            sourceHash = item.smsHash, note = "classified manually",
        )
        store.upsertTxn(row)
        close(item, ReviewStatus.RESOLVED)
        row
    }

    /** "It is a separate transaction": write the proposed row as it stands. */
    fun applyReviewAsNew(reviewId: String): Txn = store.transaction {
        val item = openItem(reviewId)
        val row = item.proposed ?: throw IllegalArgumentException("this item has no proposed row")
        store.upsertTxn(row)
        close(item, ReviewStatus.RESOLVED)
        row
    }

    /** The user picks which existing row the message belongs to. */
    fun linkReview(reviewId: String, txnId: String): Txn = store.transaction {
        val item = openItem(reviewId)
        val proposed = item.proposed ?: throw IllegalArgumentException("this item has no proposed row")
        val target = store.txn(txnId) ?: throw IllegalArgumentException("unknown transaction")
        require(target.cardId == proposed.cardId) { "transaction belongs to another card" }
        val result = when (proposed.type) {
            TxnType.PURCHASE -> {
                require(target.state == TxnState.PENDING) { "only a pending authorisation can be settled" }
                promote(target, proposed)
            }
            TxnType.REFUND -> {
                require(target.type == TxnType.PURCHASE || target.type == TxnType.FEE) { "a credit can only be linked to a charge" }
                require(target.state == TxnState.POSTED || target.state == TxnState.PENDING) { "that charge is already reversed" }
                applyCreditTo(target, proposed, isReversal = true)
            }
            else -> throw IllegalArgumentException("this kind of row cannot be linked")
        }
        close(item, ReviewStatus.RESOLVED)
        result
    }

    /** Stale authorisation: either it did go through (post) or it dropped off (drop). */
    fun resolveStale(reviewId: String, post: Boolean): Txn = store.transaction {
        val item = openItem(reviewId)
        require(item.kind == ReviewKind.STALE_PENDING) { "not a stale-pending item" }
        val row = store.txn(item.txnId ?: throw IllegalArgumentException("item has no transaction"))
            ?: throw IllegalArgumentException("transaction no longer exists")
        val updated = if (row.state == TxnState.PENDING) {
            row.copy(state = if (post) TxnState.POSTED else TxnState.REVERSED)
        } else row
        store.upsertTxn(updated)
        close(item, ReviewStatus.RESOLVED)
        updated
    }

    /**
     * Queues every PENDING authorisation older than the configured number of days, once.
     * The row itself is left PENDING until the user decides.
     */
    fun expireStalePendings(today: LocalDate): List<ReviewItem> = store.transaction {
        val days = store.settings()[Defaults.SETTING_STALE_PENDING_DAYS]?.toLongOrNull()?.takeIf { it > 0 } ?: config.stalePendingDays
        val alreadyQueued = store.reviewItems().filter { it.kind == ReviewKind.STALE_PENDING }.mapNotNull { it.txnId }.toSet()
        val created = ArrayList<ReviewItem>()
        for (row in store.txns()) {
            if (row.state != TxnState.PENDING || row.id in alreadyQueued) continue
            if (ChronoUnit.DAYS.between(row.date, today) <= days) continue
            val item = ReviewItem(
                id = idGen(), kind = ReviewKind.STALE_PENDING, status = ReviewStatus.OPEN, createdAtMs = nowMs(),
                cardId = row.cardId, txnId = row.id,
                reason = "Authorisation has not settled after $days days.",
            )
            store.upsertReviewItem(item)
            created.add(item)
        }
        created
    }

    // ------------------------------------------------------------ dashboard

    fun dashboard(today: LocalDate): DashboardSummary = Dashboard.summarise(
        cards = store.cards(),
        txns = store.txns(),
        statements = store.statements(),
        proposals = store.proposals(),
        openReviewCount = store.reviewItems().count { it.status == ReviewStatus.OPEN },
        today = today,
        graceDays = config.staleStatementGraceDays,
    )

    fun cardSummary(cardId: String, today: LocalDate): CardSummary? {
        val card = store.cards().firstOrNull { it.id == cardId } ?: return null
        return Dashboard.summariseCard(
            card, store.txnsForCard(cardId), store.statementsForCard(cardId), store.proposals(), today, config.staleStatementGraceDays,
        )
    }

    // --------------------------------------------------------------- backup

    fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        cards = store.cards(),
        txns = store.txns(),
        statements = store.statements(),
        proposals = store.proposals(),
        reviewItems = store.reviewItems(),
        processed = store.allProcessed(),
        allowlist = store.allowlist(),
        settings = store.settings(),
    )

    /** Replaces the whole ledger with [snapshot]. All-or-nothing. */
    fun restore(snapshot: LedgerSnapshot) = store.transaction {
        store.clearAll()
        snapshot.cards.forEach { store.upsertCard(it) }
        snapshot.txns.forEach { store.upsertTxn(it) }
        snapshot.statements.forEach { store.upsertStatement(it) }
        snapshot.proposals.forEach { store.upsertProposal(it) }
        snapshot.reviewItems.forEach { store.upsertReviewItem(it) }
        snapshot.processed.forEach { store.markProcessed(it) }
        snapshot.allowlist.forEach { (issuer, patterns) -> store.setAllowlist(issuer, patterns) }
        snapshot.settings.forEach { (k, v) -> store.putSetting(k, v) }
        cachedAllowlist = null
        cachedParser = null
    }
}
