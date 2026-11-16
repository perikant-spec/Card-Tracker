package tracker.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tracker.core.LedgerService

/**
 * The single doorway from Android code to the engine.
 *  - every call runs on a background dispatcher;
 *  - calls are serialised with one mutex (the engine is not thread-safe);
 *  - [version] ticks after every write so screens know to reload.
 * It holds no accounting logic of its own.
 */
class LedgerRepository(
    private val serviceProvider: () -> LedgerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var seeded = false
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun ready(): LedgerService {
        val service = serviceProvider()
        if (!seeded) {
            service.seedDefaults()
            seeded = true
        }
        return service
    }

    suspend fun <T> read(block: (LedgerService) -> T): T =
        withContext(dispatcher) { mutex.withLock { block(ready()) } }

    suspend fun <T> write(block: (LedgerService) -> T): T {
        val result = withContext(dispatcher) { mutex.withLock { block(ready()) } }
        _version.update { it + 1 }
        return result
    }
}
