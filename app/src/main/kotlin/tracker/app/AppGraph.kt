package tracker.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tracker.app.data.DbPassphrase
import tracker.app.data.LedgerDatabase
import tracker.app.data.RoomLedgerStore
import tracker.app.sms.InboxBackfill
import tracker.core.LedgerService
import java.time.ZoneId

/**
 * Hand-wired object graph (no DI framework). Constructing it is cheap: the encrypted database
 * is only opened on first use, which always happens on a background thread via [LedgerRepository].
 */
class AppGraph(context: Context) {
    private val appContext: Context = context.applicationContext

    private val service: LedgerService by lazy {
        val passphrase = DbPassphrase.getOrCreate(appContext)
        val database = LedgerDatabase.open(appContext, passphrase)
        passphrase.fill(0)
        LedgerService(store = RoomLedgerStore(database), zone = ZoneId.systemDefault())
    }

    val repository = LedgerRepository({ service })

    /** Outlives any screen: used by the SMS receiver and by a running backfill. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val backfill = InboxBackfill(appContext.contentResolver, repository)
}

class CardTrackerApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
