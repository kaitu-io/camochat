package app.chencang.shared.media

import app.chencang.shared.config.RelaySelector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * **全 App 唯一的联网类**（spec §2.5，裁决 R6）。只搬加密后的 `.cca` blob：
 * 申请上传地址、PUT 密文、GET 密文。文字路径永远不经过这里——
 * `NetworkIsolationGuardTest` 断言网络 API 与中转 base URL 只出现在本文件。
 *
 * 所有方法都是阻塞的，调用方在 `Dispatchers.IO` 上调；失败抛 [MediaFailureException]。
 * 不带 cookie、不走缓存、不跟随重定向。
 */
class MediaTransport(
    private val relays: RelaySelector,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    /**
     * Tries the configured relays in [RelaySelector.ordered] order. [MediaFailure.NETWORK] and
     * [MediaFailure.SERVER] move on to the next relay; any other failure is final. The relay that
     * answered is remembered. All relays failing rethrows the last failure.
     */
    private fun <T> withRelays(block: (String) -> T): T {
        var last: MediaFailureException? = null
        for (base in relays.ordered()) {
            try {
                return block(base).also { relays.markGood(base) }
            } catch (e: MediaFailureException) {
                if (e.failure != MediaFailure.NETWORK && e.failure != MediaFailure.SERVER) throw e
                last = e
            }
        }
        throw last ?: MediaFailureException(MediaFailure.NETWORK)
    }

    /** `GET <relay>/api/upload?blob_id=&byte_len=&kind=` → 预签名 PUT 地址（5 分钟有效）。 */
    fun requestUploadUrl(blobId: String, byteLen: Int, kind: Int): URL {
        requireBlobId(blobId)
        return withRelays { base -> requestUploadUrlFrom("$base/api/upload", blobId, byteLen, kind) }
    }

    private fun requestUploadUrlFrom(uploadBase: String, blobId: String, byteLen: Int, kind: Int): URL {
        val url = URL("$uploadBase?blob_id=$blobId&byte_len=$byteLen&kind=$kind")
        return call(url) { conn ->
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw MediaFailureException(signerFailure(code))
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val signed = runCatching {
                URL(Json.parseToJsonElement(body).jsonObject.getValue("url").jsonPrimitive.content)
            }.getOrNull() ?: throw MediaFailureException(MediaFailure.SERVER)
            // 终审 M2：签名服务回来的地址必须与签名服务同协议（生产 = https），否则当作一次普通的
            // 服务端失败（可重试）。不钉主机：换国内桶只动网关（产品裁决）。
            if (!isAllowedUploadUrl(signed, uploadBase)) throw MediaFailureException(MediaFailure.SERVER)
            signed
        }
    }

    /**
     * `PUT` 密文。固定长度流 → 精确 `Content-Length`、不分块；`If-None-Match: *`
     * 让同一 blob_id 不能被覆盖。200/201/204/412 都算成功（412 = 之前那次其实传上去了）。
     */
    fun upload(url: URL, body: ByteArray, onProgress: (Double) -> Unit) {
        call(url) { conn ->
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("If-None-Match", "*")
            conn.outputStream.use { out ->
                var off = 0
                while (off < body.size) {
                    val n = minOf(CHUNK, body.size - off)
                    out.write(body, off, n)
                    off += n
                    onProgress(off.toDouble() / body.size)
                }
            }
            when (val code = conn.responseCode) {
                200, 201, 204, 412 -> Unit
                else -> throw MediaFailureException(uploadFailure(code))
            }
        }
    }

    /**
     * `GET <relay>/b/<blobId>`；403/404 → [MediaFailure.GONE]（UI「已过期」）。
     * [expectedLen] 是帧里声明的 `byte_len`：`Content-Length` 与它不符立刻放弃，读到超过它
     * 也立刻放弃，流提前 EOF（实际读到的字节数不足）同样放弃，不会把一个被换掉/被截断的对象整个读进内存
     * 或当成完整文件返回。这些都算 [MediaFailure.NETWORK]（终审 F3）：200 上长度不符是传输层的事（截断、
     * TLS 中间盒、CDN 异常），可以再取；[MediaFailure.CORRUPT] 只留给解密失败。
     */
    fun download(blobId: String, expectedLen: Long, onProgress: (Double) -> Unit): ByteArray {
        requireBlobId(blobId)
        require(expectedLen in 1..MediaConstants.MAX_BLOB_VIDEO) { "expected length out of range" }
        return withRelays { base -> downloadFrom("$base/b/", blobId, expectedLen, onProgress) }
    }

    private fun downloadFrom(blobBase: String, blobId: String, expectedLen: Long, onProgress: (Double) -> Unit): ByteArray =
        call(URL(blobBase + blobId)) { conn ->
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw MediaFailureException(downloadFailure(code))
            val total = conn.contentLengthLong
            if (total >= 0 && total != expectedLen) throw MediaFailureException(MediaFailure.NETWORK)
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(expectedLen.toInt())
                val buf = ByteArray(CHUNK)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > expectedLen) throw MediaFailureException(MediaFailure.NETWORK)
                    out.write(buf, 0, n)
                    onProgress(read.toDouble() / expectedLen)
                }
                if (read != expectedLen) throw MediaFailureException(MediaFailure.NETWORK)
                onProgress(1.0)
                out.toByteArray()
            }
        }

    private inline fun <T> call(url: URL, block: (HttpURLConnection) -> T): T {
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw MediaFailureException(MediaFailure.NETWORK, e)
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        try {
            return block(conn)
        } catch (e: MediaFailureException) {
            throw e
        } catch (e: IOException) {
            throw MediaFailureException(MediaFailure.NETWORK, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun requireBlobId(blobId: String) {
        require(BLOB_ID.matches(blobId)) { "blob id must be 22 base64url chars" }
    }

    companion object {
        /**
         * 预签名地址的协议必须与签名服务 [uploadBase] 一致（生产 = https；本地测试服务器 = http）。
         * 主机不做限制：对象存储换成哪家由网关决定，客户端不钉桶域名。
         */
        internal fun isAllowedUploadUrl(url: URL, uploadBase: String): Boolean =
            url.protocol.equals(URL(uploadBase).protocol, ignoreCase = true)

        private const val CHUNK = 64 * 1024
        private val BLOB_ID = Regex("^[A-Za-z0-9_-]{22}$")

        /**
         * 签名与 PUT 同一口径（与 iOS 一致）：429 限流、413 太大；403（S3 的签名不符/过期——是
         * 服务端问题，不是「已过期」）与 408（超时）可重试；其余 4xx 是请求本身不被接受 →
         * [MediaFailure.REJECTED]（上传即放弃）；3xx（不跟随重定向）与 5xx 可重试。
         */
        private fun signerFailure(code: Int): MediaFailure = when (code) {
            429 -> MediaFailure.RATE_LIMITED
            413 -> MediaFailure.TOO_LARGE
            403, 408 -> MediaFailure.SERVER
            in 400..499 -> MediaFailure.REJECTED
            else -> MediaFailure.SERVER
        }

        private fun uploadFailure(code: Int): MediaFailure = signerFailure(code)

        private fun downloadFailure(code: Int): MediaFailure = when (code) {
            403, 404 -> MediaFailure.GONE
            429 -> MediaFailure.RATE_LIMITED
            else -> MediaFailure.SERVER
        }
    }
}
