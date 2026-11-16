package tracker.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tracker.app.LedgerRepository
import tracker.app.sms.BackfillProgress
import tracker.app.sms.InboxBackfill
import tracker.core.BackupCodec
import tracker.core.BackupException
import tracker.core.CardConfig
import tracker.core.CardSummary
import tracker.core.DashboardSummary
import tracker.core.Defaults
import tracker.core.LedgerService
import tracker.core.Paise
import tracker.core.ReconResult
import tracker.core.ReviewItem
import tracker.core.Statement
import tracker.core.StatementProposal
import tracker.core.Txn
import tracker.core.TxnType
import java.io.IOException
import java.time.LocalDate

// ViewModels are deliberately thin: they load values from the engine, forward user actions to it,
// and hold transient screen state. No balance, matching or reconciliation logic lives here.

/** Reloads [load] every time the ledger changes. Null until the first load finishes. */
private fun <T> LedgerRepository.observe(scope: CoroutineScope, load: (LedgerService) -> T): StateFlow<T?> =
    version.map<Long, T?> { read(load) }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

class DashboardViewModel(repo: LedgerRepository) : ViewModel() {
    val summary: StateFlow<DashboardSummary?> = repo.observe(viewModelScope) { it.dashboard(LocalDate.now()) }
}

data class CardDetail(
    val summary: CardSummary,
    val txns: List<Txn>,
    val statements: List<Statement>,
    val proposals: List<StatementProposal>,
)

class CardDetailViewModel(private val repo: LedgerRepository, private val cardId: String) : ViewModel() {
    val detail: StateFlow<CardDetail?> = repo.observe(viewModelScope) { service ->
        service.cardSummary(cardId, LocalDate.now())?.let { summary ->
            CardDetail(
                summary = summary,
                txns = service.txns(cardId),
                statements = service.statements(cardId).reversed(),
                proposals = service.proposals().filter { it.cardId == cardId },
            )
        }
    }

    fun undoAdjustment(txnId: String) { viewModelScope.launch { repo.write { it.undoAdjustment(txnId) } } }
    fun deleteStatement(date: LocalDate) { viewModelScope.launch { repo.write { it.deleteStatement(cardId, date) } } }
    fun rejectProposal(id: String) { viewModelScope.launch { repo.write { it.rejectProposal(id) } } }
}

data class StatementForm(val card: CardConfig, val proposal: StatementProposal?, val suggestedDate: LocalDate)

/** What the statement screen shows after "check" or "confirm". */
data class StatementOutcome(
    val statement: Statement,
    /** Null when there is no earlier statement: this one is the opening anchor. */
    val recon: ReconResult?,
    val confirmed: Boolean,
    val adjustmentId: String? = null,
)

class StatementViewModel(private val repo: LedgerRepository, private val cardId: String, private val proposalId: String?) : ViewModel() {
    val form = MutableStateFlow<StatementForm?>(null)
    val outcome = MutableStateFlow<StatementOutcome?>(null)
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            form.value = repo.read { service ->
                service.cards().firstOrNull { it.id == cardId }?.let { card ->
                    StatementForm(
                        card = card,
                        proposal = service.proposals().firstOrNull { it.id == proposalId },
                        suggestedDate = tracker.core.Dashboard.lastStatementDayOnOrBefore(LocalDate.now(), card.statementDay),
                    )
                }
            }
        }
    }

    fun check(statement: Statement) {
        viewModelScope.launch {
            outcome.value = StatementOutcome(statement, repo.read { it.previewReconcile(statement) }, confirmed = false)
        }
    }

    fun confirm(statement: Statement) {
        viewModelScope.launch {
            try {
                val recon = repo.write { it.confirmStatement(statement, proposalId) }
                outcome.value = StatementOutcome(statement, recon, confirmed = true)
            } catch (e: IllegalArgumentException) {
                error.value = e.message
            }
        }
    }

    /** Explicit correction for the variance of the confirmed statement. Never automatic. */
    fun addVarianceAdjustment() {
        val current = outcome.value ?: return
        val variance = current.recon?.variance ?: return
        if (!current.confirmed || variance == Paise.ZERO || current.adjustmentId != null) return
        viewModelScope.launch {
            val row = repo.write { it.addAdjustment(cardId, current.statement.statementDate, variance, "Statement variance accepted by user") }
            val after = repo.read { it.previewReconcile(current.statement) }
            outcome.value = current.copy(recon = after, adjustmentId = row.id)
        }
    }

    fun undoAdjustment() {
        val current = outcome.value ?: return
        val id = current.adjustmentId ?: return
        viewModelScope.launch {
            repo.write { it.undoAdjustment(id) }
            val after = repo.read { it.previewReconcile(current.statement) }
            outcome.value = current.copy(recon = after, adjustmentId = null)
        }
    }
}

data class ReviewRow(val item: ReviewItem, val candidates: List<Txn>, val subject: Txn?)

data class ReviewData(val rows: List<ReviewRow>, val cards: List<CardConfig>)

class ReviewViewModel(private val repo: LedgerRepository) : ViewModel() {
    val data: StateFlow<ReviewData?> = repo.observe(viewModelScope) { service ->
        ReviewData(
            rows = service.reviewItems().map { item ->
                ReviewRow(item, item.candidateIds.mapNotNull { id -> service.txn(id) }, item.txnId?.let { id -> service.txn(id) })
            },
            cards = service.cards(),
        )
    }
    val error = MutableStateFlow<String?>(null)

    private fun act(block: (LedgerService) -> Unit) {
        viewModelScope.launch {
            try {
                repo.write(block)
                error.value = null
            } catch (e: IllegalArgumentException) {
                error.value = e.message ?: "That action is not possible for this item."
            }
        }
    }

    fun dismiss(id: String) = act { it.dismissReview(id) }
    fun applyAsNew(id: String) = act { it.applyReviewAsNew(id) }
    fun link(id: String, txnId: String) = act { it.linkReview(id, txnId) }
    fun resolveStale(id: String, post: Boolean) = act { it.resolveStale(id, post) }
    fun classify(id: String, cardId: String, type: TxnType, amount: Paise, date: LocalDate, merchant: String?) =
        act { it.classifyManually(id, cardId, type, amount, date, merchant) }
}

data class SettingsData(val cards: List<CardConfig>, val allowlist: Map<String, List<String>>, val backfillMonths: Int)

class SettingsViewModel(
    private val repo: LedgerRepository,
    private val backfill: InboxBackfill,
    /** Application-level scope: a backfill keeps running if the user leaves the screen. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    val data: StateFlow<SettingsData?> = repo.observe(viewModelScope) { service ->
        SettingsData(
            cards = service.cards(),
            allowlist = service.allowlist(),
            backfillMonths = service.setting(Defaults.SETTING_BACKFILL_MONTHS)?.toIntOrNull() ?: Defaults.DEFAULT_BACKFILL_MONTHS,
        )
    }
    val progress: StateFlow<BackfillProgress> = backfill.progress
    val message = MutableStateFlow<String?>(null)

    fun saveCard(card: CardConfig) {
        viewModelScope.launch {
            try {
                repo.write { it.saveCard(card) }
                message.value = "Card saved."
            } catch (e: IllegalArgumentException) {
                message.value = e.message
            }
        }
    }

    fun saveAllowlist(issuer: String, text: String) {
        viewModelScope.launch {
            repo.write { it.setAllowlist(issuer, text.split(',', ';', '\n', ' ')) }
            message.value = "Sender list saved."
        }
    }

    fun runBackfill(months: Int) {
        appScope.launch {
            repo.write { it.putSetting(Defaults.SETTING_BACKFILL_MONTHS, months.toString()) }
            backfill.run(months)
        }
    }

    fun export(resolver: ContentResolver, uri: Uri, password: CharArray) {
        viewModelScope.launch {
            try {
                val snapshot = repo.read { it.snapshot() }
                withContext(Dispatchers.IO) {
                    val bytes = BackupCodec.encrypt(snapshot, password)
                    val stream = resolver.openOutputStream(uri) ?: throw IOException("cannot open the chosen file")
                    stream.use { it.write(bytes) }
                }
                message.value = "Encrypted backup written."
            } catch (e: BackupException) {
                message.value = e.message
            } catch (e: IOException) {
                message.value = "Could not write the backup file."
            } catch (e: SecurityException) {
                message.value = "Could not write the backup file."
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun restore(resolver: ContentResolver, uri: Uri, password: CharArray) {
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val stream = resolver.openInputStream(uri) ?: throw IOException("cannot open the chosen file")
                    val bytes = stream.use { it.readBytes() }
                    BackupCodec.decrypt(bytes, password)
                }
                repo.write { it.restore(snapshot) }
                message.value = "Backup restored. The previous ledger was replaced."
            } catch (e: BackupException) {
                message.value = e.message
            } catch (e: IOException) {
                message.value = "Could not read the backup file."
            } catch (e: SecurityException) {
                message.value = "Could not read the backup file."
            } finally {
                password.fill('\u0000')
            }
        }
    }
}
