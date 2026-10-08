package app.chencang.shared.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.config.ConfigTestSupport.env
import app.chencang.shared.config.ConfigTestSupport.fakeVerify
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigRepositoryTest {
    private val factorySource = "https://f.example/c/"
    private val factory = env(2, sources = """["$factorySource"]""")
    private lateinit var store: ConfigStore
    private var nowMs = 1_000_000_000L

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = ConfigStore(ctx.getSharedPreferences("chencang_config", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    }

    private fun TestScope.repo(source: ConfigSource) = ConfigRepository(
        factoryEnvelope = factory, store = store, source = source, verify = fakeVerify,
        now = { nowMs }, io = StandardTestDispatcher(testScheduler),
    )

    @Test fun startsFromFactoryWhenNoCache() = runTest {
        assertThat(repo { null }.config.value.seq).isEqualTo(2)
    }

    @Test fun cacheWithLowerSeqThanFactoryIsIgnored() = runTest {
        store.saveEnvelope(env(1))
        assertThat(repo { null }.config.value.seq).isEqualTo(2)
    }

    @Test fun cacheWithHigherSeqIsUsed() = runTest {
        store.saveEnvelope(env(4))
        assertThat(repo { null }.config.value.seq).isEqualTo(4)
    }

    @Test fun picksHighestSeqAcrossSources() = runTest {
        store.saveEnvelope(env(2, sources = """["https://x/c/","https://y/c/"]"""))
        val m = mapOf("https://x/c/" to env(2), "https://y/c/" to env(5), factorySource to env(3))
        val r = repo { u -> m[u] }
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.UPDATED)
        assertThat(r.config.value.seq).isEqualTo(5)
        assertThat(store.envelope()).isEqualTo(env(5))
    }

    @Test fun neverRollsBack() = runTest {
        val cached = env(5, sources = """["https://x/c/"]""")
        store.saveEnvelope(cached)
        val r = repo { env(4) }
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.UNCHANGED)
        assertThat(r.config.value.seq).isEqualTo(5)
        assertThat(store.envelope()).isEqualTo(cached)
    }

    @Test fun allSourcesFail_keepsCurrent_returnsFailed() = runTest {
        val r = repo { null }
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.FAILED)
        assertThat(r.config.value.seq).isEqualTo(2)
        assertThat(store.lastSuccessAt()).isEqualTo(0L)
    }

    @Test fun invalidResponsesCountAsFailure() = runTest {
        val r = repo { "garbage" }
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.FAILED)
    }

    @Test fun throttledWithin6h_unlessForce() = runTest {
        val r = repo { env(2) }
        assertThat(r.refresh()).isEqualTo(RefreshResult.UNCHANGED)
        assertThat(store.lastSuccessAt()).isEqualTo(nowMs)
        nowMs += 21_599_999
        assertThat(r.refresh()).isEqualTo(RefreshResult.THROTTLED)
        nowMs += 1
        assertThat(r.refresh()).isEqualTo(RefreshResult.UNCHANGED)
        assertThat(r.refresh()).isEqualTo(RefreshResult.THROTTLED)
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.UNCHANGED)
    }

    @Test fun slowSourceDoesNotBlockBeyond15s() = runTest {
        // fetch() is blocking, so virtual time cannot model a hang: use a real IO dispatcher and a
        // shortened overall budget (the 15 s default is asserted separately).
        store.saveEnvelope(env(2, sources = """["https://slow/c/"]"""))
        val latch = java.util.concurrent.CountDownLatch(1)
        val src = ConfigSource { u ->
            if (u == "https://slow/c/") { latch.await(30, java.util.concurrent.TimeUnit.SECONDS); null } else env(3)
        }
        val r = ConfigRepository(factory, store, src, fakeVerify, { nowMs }, kotlinx.coroutines.Dispatchers.IO, overallTimeoutMs = 300)
        val t0 = System.nanoTime()
        assertThat(r.refresh(force = true)).isEqualTo(RefreshResult.UPDATED)
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000L)
        assertThat(r.config.value.seq).isEqualTo(3)
        latch.countDown()
    }

    @Test fun defaultOverallBudgetIs15Seconds() = runTest {
        assertThat(ConfigRepository.OVERALL_TIMEOUT_MS).isEqualTo(15_000L)
        assertThat(ConfigRepository.THROTTLE_MS).isEqualTo(21_600_000L)
    }

    @Test fun concurrentRefreshNeverRollsBack() = runTest {
        store.saveEnvelope(env(3, sources = """["$factorySource"]"""))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val r = repo { if (calls.getAndIncrement() == 0) env(5) else env(4) }
        val a = async { r.refresh(force = true) }
        val b = async { r.refresh(force = true) }
        assertThat(a.await()).isEqualTo(RefreshResult.UPDATED)
        assertThat(b.await()).isEqualTo(RefreshResult.UNCHANGED)
        assertThat(r.config.value.seq).isEqualTo(5)
        assertThat(store.envelope()).isEqualTo(env(5))
    }

    @Test fun unionsCachedAndFactorySources() = runTest {
        store.saveEnvelope(env(3, sources = """["https://x/c/","$factorySource"]"""))
        val seen = mutableListOf<String>()
        val r = repo { u -> synchronized(seen) { seen += u }; null }
        r.refresh(force = true)
        assertThat(seen).containsExactly("https://x/c/", factorySource)
    }
}

/** Source whose slow URL hangs far beyond the 15 s budget, in virtual time (uses Thread-free delay via runBlocking-free polling). */
private class DelayingSource(private val scope: TestScope, private val slow: String, private val fast: String) : ConfigSource {
    override fun fetch(url: String): String? {
        if (url != slow) return fast
        // Block this (virtual-dispatcher) call: cannot delay() in a non-suspend fun, so simulate by a long real-time-free hang is
        // impossible; instead the repository must treat a fetch that never returns within timeout as null.
        Thread.sleep(200)
        return null
    }
}
