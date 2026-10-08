package app.chencang.shared.config

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

fun interface ConfigSource {
    fun fetch(url: String): String?
}

/**
 * Fetches `<source>chencang-config.json`. One of the few files allowed to touch the network
 * (NetworkIsolationGuardTest): plain GET, no query, no custom headers, no cookies, no redirects,
 * 64 KiB cap. Any non-200 or exception yields null.
 */
class ConfigFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
    private val totalTimeoutMs: Long = 8_000,
) : ConfigSource {
    override fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url + FILE_NAME).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (System.nanoTime() > deadline) return null
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val FILE_NAME = "chencang-config.json"
        const val MAX_BYTES = 64 * 1024
    }
}
