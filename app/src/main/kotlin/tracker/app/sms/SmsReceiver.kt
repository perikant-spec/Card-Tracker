package tracker.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tracker.app.CardTrackerApp

/**
 * Live ingestion. The message is handed to the engine, which drops it unread unless the sender
 * is on the user's allowlist. Nothing about the message is ever logged.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val parts = messages.filterNotNull().map { SmsPart(it.originatingAddress, it.messageBody, it.timestampMillis) }
        val incoming = SmsAssembler.assemble(parts)
        if (incoming.isEmpty()) return

        val graph = (context.applicationContext as CardTrackerApp).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.repository.write { service -> incoming.forEach { service.ingest(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately silent: no logging of anything derived from an SMS.
                // A message missed here is picked up by the next inbox backfill (idempotent).
            } finally {
                pending.finish()
            }
        }
    }
}
