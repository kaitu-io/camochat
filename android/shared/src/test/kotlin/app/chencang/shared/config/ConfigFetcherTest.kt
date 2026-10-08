package app.chencang.shared.config

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ConfigFetcherTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/c/").toString()

    @Before fun up() { server = MockWebServer().also { it.start() } }
    @After fun down() { server.shutdown() }

    @Test fun fetchesBodyOn200() {
        server.enqueue(MockResponse().setBody("hello"))
        assertThat(ConfigFetcher().fetch(base)).isEqualTo("hello")
        assertThat(server.takeRequest().path).isEqualTo("/c/chencang-config.json")
    }

    @Test fun returnsNullOn404() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(ConfigFetcher().fetch(base)).isNull()
    }

    @Test fun doesNotFollowRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other").toString()))
        server.enqueue(MockResponse().setBody("x"))
        assertThat(ConfigFetcher().fetch(base)).isNull()
    }

    @Test fun returnsNullWhenBodyOver64KiB() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() })))
        assertThat(ConfigFetcher().fetch(base)).isNull()
    }

    @Test fun acceptsBodyAtExactly64KiB() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024) { 'a'.code.toByte() })))
        assertThat(ConfigFetcher().fetch(base)).hasLength(64 * 1024)
    }

    @Test fun slowDripBodyHitsTotalDeadline() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(200) { 'a'.code.toByte() })).throttleBody(10, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
        val t0 = System.nanoTime()
        assertThat(ConfigFetcher(totalTimeoutMs = 300).fetch(base)).isNull()
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_500L)
    }

    @Test fun returnsNullOnConnectionFailure() {
        val dead = base
        server.shutdown()
        assertThat(ConfigFetcher(connectTimeoutMs = 500, readTimeoutMs = 500).fetch(dead)).isNull()
    }

    @Test fun sendsNoQueryOrCustomHeaders() {
        server.enqueue(MockResponse().setBody("x"))
        ConfigFetcher().fetch(base)
        val req = server.takeRequest()
        assertThat(req.path).doesNotContain("?")
        val allowed = setOf("host", "user-agent", "accept", "connection", "accept-encoding",
            // JDK-injected by useCaches=false; not custom identifiers
            "cache-control", "pragma")
        assertThat(req.headers.names().map { it.lowercase() }.filter { it !in allowed }).isEmpty()
        assertThat(req.getHeader("Cookie")).isNull()
        assertThat(req.getHeader("Authorization")).isNull()
    }
}
