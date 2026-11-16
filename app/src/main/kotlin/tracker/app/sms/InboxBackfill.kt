package tracker.app.sms

import android.content.ContentResolver
import android.provider.BaseColumns
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tracker.app.LedgerRepository
import tracker.core.IngestOutcome
import tracker.core.RawSms
import tracker.core.SenderAllowlist
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

data class BackfillProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    /** Inbox rows looked at (sender and date columns only). */
    val scanned: Int = 0,
    /** Rows whose sender is on the allowlist; only these have their text read. */
    val fromAllowlisted: Int = 0,
    val processed: Int = 0,
    val applied: Int = 0,
    val proposed: Int = 0,
    val queued: Int = 0,
    val duplicates: Int = 0,
    val ignored: Int = 0,
    val untracked: Int = 0,
    val error: String? = null,
)

/** Pure tally helper (unit-tested). */
fun BackfillProgress.plus(outcome: IngestOutcome): BackfillProgress = when (outcome) {
    is IngestOutcome.Applied -> copy(processed = processed + 1, applied = applied + 1)
    is IngestOutcome.Proposed -> copy(processed = processed + 1, proposed = proposed + 1)
    is IngestOutcome.Queued -> copy(processed = processed + 1, queued = queued + 1)
    is IngestOutcome.Duplicate -> copy(processed = processed + 1, duplicates = duplicates + 1)
    is IngestOutcome.Ignored -> copy(processed = processed + 1, ignored = ignored + 1)
    is IngestOutcome.UntrackedCard -> copy(processed = processed + 1, untracked = untracked + 1)
    is IngestOutcome.NotAllowlisted -> copy(processed = processed + 1)
}

/**
 * Reads the existing inbox for a user-chosen window and feeds it to the engine.
 *
 * Privacy: the first pass asks the SMS provider only for id, sender and dates. Message text is
 * requested in a second pass and only for rows whose sender is on the allowlist.
 * Idempotent: the engine remembers a hash of every message it acted on, so running this twice
 * (or after live ingestion) changes nothing.
 */
class InboxBackfill(
    private val resolver: ContentResolver,
    private val repository: LedgerRepository,
) {
    private val busy = AtomicBoolean(false)
    private val _progress = MutableStateFlow(BackfillProgress())
    val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    private data class Candidate(val id: Long, val sender: String, val timestampMs: Long)

    suspend fun run(months: Int) {
        if (!busy.compareAndSet(false, true)) return
        _progress.value = BackfillProgress(running = true)
        try {
            withContext(Dispatchers.IO) { scan(months.coerceIn(1, 120)) }
            _progress.update { it.copy(running = false, finished = true) }
        } catch (e: SecurityException) {
            _progress.update { it.copy(running = false, error = "SMS permission has not been granted.") }
        } catch (e: IllegalStateException) {
            _progress.update { it.copy(running = false, error = "The inbox could not be read (" + e.javaClass.simpleName + ").") }
        } catch (e: IllegalArgumentException) {
            _progress.update { it.copy(running = false, error = "The inbox could not be read (" + e.javaClass.simpleName + ").") }
        } finally {
            busy.set(false)
        }
    }

    private suspend fun scan(months: Int) {
        val since = ZonedDateTime.now().minusMonths(months.toLong()).toInstant().toEpochMilli()
        val senderLists = repository.read { it.allowlist() }.values.toList()

        // Pass 1: no message text is requested.
        val candidates = ArrayList<Candidate>()
        var scanned = 0
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(BaseColumns._ID, Telephony.TextBasedSmsColumns.ADDRESS, Telephony.TextBasedSmsColumns.DATE, Telephony.TextBasedSmsColumns.DATE_SENT),
            Telephony.TextBasedSmsColumns.DATE + " >= ?",
            arrayOf(since.toString()),
            Telephony.TextBasedSmsColumns.DATE + " ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val addressCol = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)
            val dateCol = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE)
            val sentCol = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE_SENT)
            while (cursor.moveToNext()) {
                scanned++
                val address = cursor.getString(addressCol) ?: continue
                if (senderLists.none { SenderAllowlist.matches(it, address) }) continue
                // date_sent is the service-centre time, the same value the live receiver sees.
                val sent = cursor.getLong(sentCol)
                val timestamp = if (sent > 0L) sent else cursor.getLong(dateCol)
                candidates.add(Candidate(cursor.getLong(idCol), address, timestamp))
            }
        }
        _progress.update { it.copy(scanned = scanned, fromAllowlisted = candidates.size) }

        // Pass 2: text of allowlisted rows only, in small batches, oldest first.
        for (batch in candidates.chunked(BATCH)) {
            val bodies = HashMap<Long, String>()
            val placeholders = batch.joinToString(",") { "?" }
            resolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID, Telephony.TextBasedSmsColumns.BODY),
                BaseColumns._ID + " IN (" + placeholders + ")",
                batch.map { it.id.toString() }.toTypedArray(),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val bodyCol = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)
                while (cursor.moveToNext()) {
                    val body = cursor.getString(bodyCol) ?: continue
                    bodies[cursor.getLong(idCol)] = body
                }
            }
            val messages = batch.mapNotNull { c -> bodies[c.id]?.let { RawSms(c.sender, it, c.timestampMs) } }
            val outcomes = repository.write { service -> messages.map { service.ingest(it) } }
            _progress.update { current -> outcomes.fold(current) { acc, outcome -> acc.plus(outcome) } }
        }

        repository.write { it.expireStalePendings(LocalDate.now()) }
    }

    private companion object {
        const val BATCH = 40
    }
}
