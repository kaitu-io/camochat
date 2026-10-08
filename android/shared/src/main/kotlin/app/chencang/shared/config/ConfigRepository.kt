package app.chencang.shared.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RefreshResult { UPDATED, UNCHANGED, FAILED, THROTTLED }

/**
 * Holds the current [AppConfig]: the factory (asset) config or a newer cached one, never rolled
 * back (a candidate must have a strictly higher `seq` to replace the current one).
 */
class ConfigRepository(
    factoryEnvelope: String,
    private val store: ConfigStore,
    private val source: ConfigSource,
    private val verify: (ByteArray, ByteArray) -> Boolean,
    private val now: () -> Long,
    private val io: CoroutineDispatcher,
    private val overallTimeoutMs: Long = OVERALL_TIMEOUT_MS,
) {
    private val factory: AppConfig = requireNotNull(SignedConfigCodec.decode(factoryEnvelope, verify)) {
        "factory config failed verification"
    }
    private val _config = MutableStateFlow(
        store.envelope()?.let { SignedConfigCodec.decode(it, verify) }?.takeIf { it.seq >= factory.seq } ?: factory,
    )
    val config: StateFlow<AppConfig> = _config

    // Serializes refreshes: a second call waits, then re-evaluates throttle and seq against live state.
    private val refreshLock = Mutex()

    suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock { refreshLocked(force) }

    private suspend fun refreshLocked(force: Boolean): RefreshResult {
        if (!force && now() - store.lastSuccessAt() < THROTTLE_MS) return RefreshResult.THROTTLED
        val current = _config.value
        val urls = (current.sources + factory.sources).distinct()

        // Detached scope: a blocking fetch cannot be cancelled, so the timeout must not wait for it.
        val scope = CoroutineScope(SupervisorJob() + io)
        val jobs = urls.map { u -> scope.async { runCatching { source.fetch(u) }.getOrNull() } }
        val bodies = try {
            withTimeoutOrNull(overallTimeoutMs) { jobs.forEach { it.await() } }
            withContext(io) {
                jobs.filter { it.isCompleted && !it.isCancelled }.mapNotNull { it.getCompleted() }
            }
        } finally {
            scope.cancel()
        }

        val best = bodies.mapNotNull { body ->
            SignedConfigCodec.decode(body, verify)?.let { it to body }
        }.maxByOrNull { it.first.seq } ?: return RefreshResult.FAILED

        // Compare against the live value at commit time (anti-rollback invariant).
        return if (best.first.seq > _config.value.seq) {
            store.saveEnvelope(best.second)
            _config.value = best.first
            store.markSuccess(now())
            RefreshResult.UPDATED
        } else {
            store.markSuccess(now())
            RefreshResult.UNCHANGED
        }
    }

    companion object {
        const val OVERALL_TIMEOUT_MS = 15_000L
        const val THROTTLE_MS = 21_600_000L
    }
}
