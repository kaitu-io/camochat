package app.chencang.android.update

import app.chencang.shared.config.LatestApk
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val body = ByteArray(1000) { (it * 7).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = File(tmp.root, "update")
    }

    @After fun tearDown() = server.shutdown()

    private fun apk(vararg paths: String, sha: String = hash, size: Long = body.size.toLong()) =
        LatestApk(5, "1.0", sha, size, paths.map { server.url(it).toString() })

    private fun ok(bytes: ByteArray = body, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(Buffer().write(bytes))

    private val never = { false }

    @Test fun downloadsAndVerifies() {
        server.enqueue(ok())
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(f.name).isEqualTo("chencang-5.apk")
        assertThat(f.readBytes()).isEqualTo(body)
        assertThat(File(dir, "chencang-5.apk.part").exists()).isFalse()
    }

    @Test fun resumesWithRangeFromPart() {
        dir.mkdirs()
        File(dir, "chencang-5.apk.part").writeBytes(body.copyOf(100))
        server.enqueue(ok(body.copyOfRange(100, 1000), 206))
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=100-")
        assertThat(f.readBytes()).isEqualTo(body)
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        dir.mkdirs()
        File(dir, "chencang-5.apk.part").writeBytes(body.copyOf(100))
        server.enqueue(ok())
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(f.readBytes()).isEqualTo(body)
    }

    @Test fun fallsBackToSecondMirrorOn500() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok())
        val f = ApkDownloader(dir).download(apk("/a", "/b"), { _, _ -> }, never)
        assertThat(f.readBytes()).isEqualTo(body)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun checksumMismatchDeletesPartAndThrowsChecksum() {
        server.enqueue(ok())
        val ex = assertThrows(ApkDownloadException::class.java) {
            ApkDownloader(dir).download(apk("/a", "/b", sha = "00".repeat(32)), { _, _ -> }, never)
        }
        assertThat(ex.failure).isEqualTo(DownloadFailure.CHECKSUM)
        assertThat(File(dir, "chencang-5.apk.part").exists()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun cancelKeepsPart() {
        server.enqueue(ok())
        var calls = 0
        val ex = assertThrows(ApkDownloadException::class.java) {
            ApkDownloader(dir).download(apk("/a"), { _, _ -> }, { ++calls > 2 })
        }
        assertThat(ex.failure).isEqualTo(DownloadFailure.NETWORK)
        assertThat(File(dir, "chencang-5.apk.part").exists()).isTrue()
    }

    @Test fun returnsExistingVerifiedFileWithoutRequest() {
        dir.mkdirs()
        File(dir, "chencang-5.apk").writeBytes(body)
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(f.readBytes()).isEqualTo(body)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun completePartIsVerifiedWithoutRequest() {
        dir.mkdirs()
        File(dir, "chencang-5.apk.part").writeBytes(body)
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(f.readBytes()).isEqualTo(body)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun completeButCorruptPartRestartsFromZero() {
        dir.mkdirs()
        File(dir, "chencang-5.apk.part").writeBytes(ByteArray(1000) { 1 })
        server.enqueue(ok())
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(server.takeRequest().getHeader("Range")).isNull()
        assertThat(f.readBytes()).isEqualTo(body)
    }

    @Test fun http416DeletesPartAndRetriesFromZero() {
        dir.mkdirs()
        File(dir, "chencang-5.apk.part").writeBytes(body.copyOf(100))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(ok())
        val f = ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=100-")
        assertThat(server.takeRequest().getHeader("Range")).isNull()
        assertThat(f.readBytes()).isEqualTo(body)
    }

    @Test fun oversizedBodyIsChecksumFailure() {
        server.enqueue(ok(ByteArray(1500)))
        val ex = assertThrows(ApkDownloadException::class.java) {
            ApkDownloader(dir).download(apk("/a"), { _, _ -> }, never)
        }
        assertThat(ex.failure).isEqualTo(DownloadFailure.CHECKSUM)
        assertThat(File(dir, "chencang-5.apk.part").exists()).isFalse()
    }
}
