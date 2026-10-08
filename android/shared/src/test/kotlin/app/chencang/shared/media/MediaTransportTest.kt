package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: MediaTransport
    private val blobId = "AbCdEfGhIjKlMnOpQrSt_-"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        transport = MediaTransport(
            relays = relaysOf(server),
            connectTimeoutMs = 2_000,
            readTimeoutMs = 500,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun failureOf(block: () -> Unit): MediaFailure =
        assertThrows(MediaFailureException::class.java) { block() }.failure

    @Test
    fun `requestUploadUrl sends GET with blob_id byte_len kind and returns the signed url`() {
        val signed = server.url("/b/$blobId?X-Amz-Signature=abc").toString()
        server.enqueue(MockResponse().setBody("""{"url":"$signed","expires_in":300}"""))

        val url = transport.requestUploadUrl(blobId, 1_050, MediaConstants.KIND_IMAGE)

        assertThat(url.toString()).isEqualTo(signed)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).isEqualTo("/api/upload?blob_id=$blobId&byte_len=1050&kind=2")
    }

    @Test
    fun `requestUploadUrl maps 429 413 and 400`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.RATE_LIMITED)
        server.enqueue(MockResponse().setResponseCode(413))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.TOO_LARGE)
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.REJECTED)
    }

    /**
     * 与 iOS 同口径：4xx 里只有 403（签名不符/过期）、408（超时）、429（限流）值得再试；
     * 其余 4xx 是请求本身不被接受，重试多少次都一样 → [MediaFailure.REJECTED]（上传即放弃）。
     * 3xx（不跟随重定向）与 5xx 仍是服务端的问题，可重试。
     */
    @Test
    fun `requestUploadUrl maps unnamed 4xx to REJECTED and keeps 403 408 3xx 5xx retryable`() {
        for (code in listOf(400, 401, 404, 405, 409, 410, 411, 422)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.REJECTED)
        }
        for (code in listOf(403, 408, 301, 304, 500, 502, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
        }
    }

    @Test
    fun `upload maps unnamed 4xx to REJECTED and keeps 403 408 3xx 5xx retryable`() {
        for (code in listOf(400, 401, 404, 405, 409, 410, 411, 422)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(failureOf { transport.upload(server.url("/p").toUrl(), byteArrayOf(1)) {} }).isEqualTo(MediaFailure.REJECTED)
        }
        for (code in listOf(403, 408, 301, 304, 500, 502, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThat(failureOf { transport.upload(server.url("/p").toUrl(), byteArrayOf(1)) {} }).isEqualTo(MediaFailure.SERVER)
        }
    }

    @Test
    fun `requestUploadUrl with a body that is not the expected JSON is a server failure`() {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
    }

    @Test
    fun `requestUploadUrl with a JSON-valid but non-URL url field is a server failure`() {
        server.enqueue(MockResponse().setBody("""{"url":"not a url","expires_in":300}"""))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
    }

    @Test
    fun `a signed url whose scheme differs from the signer's is refused as a retryable server failure, nothing is PUT`() {
        // 本地签名服务是 http，签回 https 即协议不符（生产里对应：签名 https、签回 http）。
        server.enqueue(MockResponse().setBody("""{"url":"https://bucket.example/b/$blobId?X-Amz-Signature=abc","expires_in":300}"""))
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
        assertThat(server.requestCount).isEqualTo(1) // 只有签名那一次 GET
    }

    @Test
    fun `production upload urls must be https, any host`() {
        fun ok(u: String) = MediaTransport.isAllowedUploadUrl(java.net.URL(u), "https://relay.test/api/upload")
        assertThat(ok("https://some-bucket.s3.example.com/b/$blobId?X-Amz-Signature=abc")).isTrue()
        assertThat(ok("https://relay.test/b/$blobId")).isTrue()
        assertThat(ok("http://some-bucket.s3.example.com/b/$blobId")).isFalse()
        assertThat(ok("ftp://some-bucket.s3.example.com/b/$blobId")).isFalse()
    }

    @Test
    fun `blob ids that could change the path are refused before any request`() {
        assertThrows(IllegalArgumentException::class.java) { transport.requestUploadUrl("../../etc/passwd_xxxxx", 10, 1) }
        assertThrows(IllegalArgumentException::class.java) { transport.download("short", 10L) {} }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `upload PUTs exact bytes with fixed length and the signed headers`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val progress = mutableListOf<Double>()

        transport.upload(server.url("/put/obj").toUrl(), body) { progress += it }

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("PUT")
        assertThat(req.getHeader("Content-Type")).isEqualTo("application/octet-stream")
        assertThat(req.getHeader("If-None-Match")).isEqualTo("*")
        assertThat(req.getHeader("Content-Length")).isEqualTo("200000")
        assertThat(req.getHeader("Transfer-Encoding")).isNull()
        assertThat(req.body.readByteArray()).isEqualTo(body)
        assertThat(progress).isNotEmpty()
        assertThat(progress).isInOrder()
        assertThat(progress.last()).isEqualTo(1.0)
    }

    @Test
    fun `upload treats 201 204 and 412 as success`() {
        for (code in listOf(201, 204, 412)) {
            server.enqueue(MockResponse().setResponseCode(code))
            transport.upload(server.url("/put/obj").toUrl(), byteArrayOf(1, 2, 3)) {}
        }
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `upload maps 429 413 and 403 (bad signature is a server problem, not expiry)`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(failureOf { transport.upload(server.url("/p").toUrl(), byteArrayOf(1)) {} }).isEqualTo(MediaFailure.RATE_LIMITED)
        server.enqueue(MockResponse().setResponseCode(413))
        assertThat(failureOf { transport.upload(server.url("/p").toUrl(), byteArrayOf(1)) {} }).isEqualTo(MediaFailure.TOO_LARGE)
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(failureOf { transport.upload(server.url("/p").toUrl(), byteArrayOf(1)) {} }).isEqualTo(MediaFailure.SERVER)
    }

    @Test
    fun `download GETs b slash blobId and reports progress`() {
        val blob = ByteArray(100_000) { 7 }
        server.enqueue(MockResponse().setBody(Buffer().write(blob)))
        val progress = mutableListOf<Double>()

        val got = transport.download(blobId, blob.size.toLong()) { progress += it }

        assertThat(got).isEqualTo(blob)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).isEqualTo("/b/$blobId")
        assertThat(req.getHeader("Accept-Encoding")).isEqualTo("identity")
        assertThat(progress.last()).isEqualTo(1.0)
    }

    @Test
    fun `download maps 403 and 404 to GONE, 500 to SERVER`() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.GONE)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.GONE)
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.SERVER)
    }

    @Test
    fun `download maps 429 to RATE_LIMITED`() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.RATE_LIMITED)
    }

    // 终审 F3：200 但长度对不上是传输问题（截断 / 中间盒 / CDN 异常），不是密文坏了 → NETWORK（可重试、
    // awaiting 期间静默再轮询）。CORRUPT 只留给解密失败。

    @Test
    fun `a 200 whose Content-Length differs from the expected blob length aborts as NETWORK`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(99))))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `a body longer than expected (no Content-Length) is cut off as NETWORK`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(300)), 64))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `a body shorter than expected (no Content-Length) ends as NETWORK, not a short return`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(60)), 64))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `a connection dropped mid-body on a 200 is NETWORK`() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(100_000)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        assertThat(failureOf { transport.download(blobId, 100_000L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `dropped connection and read timeout are NETWORK`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
        server.enqueue(MockResponse().setBody("x").setHeadersDelay(2, TimeUnit.SECONDS))
        assertThat(failureOf { transport.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `unreachable host is NETWORK`() {
        val dead = MediaTransport(relays = relaysOf("http://127.0.0.1:9"), connectTimeoutMs = 500)
        assertThat(failureOf { dead.download(blobId, 100L) {} }).isEqualTo(MediaFailure.NETWORK)
    }

    @Test
    fun `requestUploadUrl does not follow a redirect from the signer`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/api/upload-elsewhere").toString()),
        )
        assertThat(failureOf { transport.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `upload does not follow a redirect from the PUT target`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/put/elsewhere").toString()),
        )
        assertThat(failureOf { transport.upload(server.url("/put/obj").toUrl(), byteArrayOf(1, 2, 3)) {} })
            .isEqualTo(MediaFailure.SERVER)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---- relay failover ----

    private fun twoRelays(): Triple<MockWebServer, MockWebServer, MediaTransport> {
        val second = MockWebServer().apply { start() }
        val t = MediaTransport(relays = relaysOf(server, second), connectTimeoutMs = 2_000, readTimeoutMs = 500)
        return Triple(server, second, t)
    }

    @Test
    fun `failsOverToSecondRelayOn503`() {
        val (first, second, t) = twoRelays()
        try {
            first.enqueue(MockResponse().setResponseCode(503))
            val signed = second.url("/b/$blobId?X-Amz-Signature=abc").toString()
            second.enqueue(MockResponse().setBody("""{"url":"$signed","expires_in":300}"""))
            assertThat(t.requestUploadUrl(blobId, 10, 1).toString()).isEqualTo(signed)
            assertThat(first.requestCount).isEqualTo(1)
            assertThat(second.requestCount).isEqualTo(1)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `failsOverOnConnectionRefused`() {
        val dead = "http://127.0.0.1:9"
        val t = MediaTransport(relays = relaysOf(dead, origin(server)), connectTimeoutMs = 500, readTimeoutMs = 500)
        server.enqueue(MockResponse().setBody(ByteArray(4) { 1 }.let { Buffer().write(it) }))
        assertThat(t.download(blobId, 4, {}).size).isEqualTo(4)
    }

    @Test
    fun `doesNotFailOverOn404Download`() {
        val (first, second, t) = twoRelays()
        try {
            first.enqueue(MockResponse().setResponseCode(404))
            assertThat(failureOf { t.download(blobId, 4, {}) }).isEqualTo(MediaFailure.GONE)
            assertThat(second.requestCount).isEqualTo(0)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `doesNotFailOverOn429`() {
        val (first, second, t) = twoRelays()
        try {
            first.enqueue(MockResponse().setResponseCode(429))
            assertThat(failureOf { t.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.RATE_LIMITED)
            assertThat(second.requestCount).isEqualTo(0)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `throwsLastFailureWhenAllRelaysFail`() {
        val (first, second, t) = twoRelays()
        try {
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setResponseCode(502))
            assertThat(failureOf { t.requestUploadUrl(blobId, 10, 1) }).isEqualTo(MediaFailure.SERVER)
            assertThat(first.requestCount + second.requestCount).isEqualTo(2)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `remembersWorkingRelay`() {
        val (first, second, t) = twoRelays()
        try {
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4) { 1 })))
            second.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4) { 1 })))
            t.download(blobId, 4, {})
            t.download(blobId, 4, {})
            assertThat(first.requestCount).isEqualTo(1) // second download went straight to the good relay
            assertThat(second.requestCount).isEqualTo(2)
        } finally {
            second.shutdown()
        }
    }
}
