package tracker.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tracker.core.InMemoryLedgerStore
import tracker.core.LedgerService
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerRepositoryTest {
    @Test fun `first use seeds the six cards, writes tick the version, reads do not`() = runBlocking {
        val store = InMemoryLedgerStore()
        var built = 0
        val service = LedgerService(store)
        val repo = LedgerRepository({ built++; service }, Dispatchers.Unconfined)
        assertEquals(0, built) // nothing is opened until first use
        assertEquals(6, repo.read { it.cards().size })
        assertEquals(0L, repo.version.value)
        repo.write { it.putSetting("backfill_months", "6") }
        assertEquals(1L, repo.version.value)
        assertEquals("6", repo.read { it.setting("backfill_months") })
        assertEquals(1L, repo.version.value)
        assertEquals(listOf("HDFCBK"), store.allowlist()["HDFC"])
    }
}
