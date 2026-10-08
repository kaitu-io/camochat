package app.chencang.android.update

import app.chencang.shared.config.LatestApk
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ApkDownloadException(val failure: DownloadFailure, cause: Throwable? = null) :
    Exception("apk download failed: $failure", cause)

/** Resumable, mirror-failover APK download with SHA-256 verification. No identifying headers. */
open class ApkDownloader(
    private val dir: File,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun target(versionCode: Int) = File(dir, "chencang-$versionCode.apk")

    open fun download(apk: LatestApk, onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean): File =
        try {
            downloadInternal(apk, onProgress, isCancelled)
        } catch (e: ApkDownloadException) {
            throw e
        } catch (e: IOException) {
            throw ApkDownloadException(DownloadFailure.STORAGE, e)
        }

    private enum class Outcome { DONE, NEXT_MIRROR, RESTART_FROM_ZERO }

    private fun downloadInternal(
        apk: LatestApk,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw ApkDownloadException(DownloadFailure.STORAGE)
        val target = target(apk.versionCode)
        if (target.isFile && sha256(target).equals(apk.sha256, ignoreCase = true)) return target
        target.delete()
        val part = File(dir, target.name + ".part")

        // A complete .part (process died before the rename) must not be resumed with Range: size-.
        if (part.isFile && part.length() >= apk.size) {
            if (part.length() == apk.size && sha256(part).equals(apk.sha256, ignoreCase = true)) {
                if (!part.renameTo(target)) throw ApkDownloadException(DownloadFailure.STORAGE)
                return target
            }
            part.delete()
        }

        for (mirror in apk.mirrors) {
            var attempts = 0
            while (attempts++ < 2) {
                if (isCancelled()) throw ApkDownloadException(DownloadFailure.NETWORK)
                val outcome = try {
                    fetch(mirror, part, apk, onProgress, isCancelled)
                } catch (e: ApkDownloadException) {
                    throw e
                } catch (e: IOException) {
                    Outcome.NEXT_MIRROR
                }
                if (outcome == Outcome.RESTART_FROM_ZERO) continue
                if (outcome == Outcome.NEXT_MIRROR) break
                if (part.length() != apk.size || !sha256(part).equals(apk.sha256, ignoreCase = true)) {
                    part.delete()
                    throw ApkDownloadException(DownloadFailure.CHECKSUM)
                }
                if (!part.renameTo(target)) throw ApkDownloadException(DownloadFailure.STORAGE)
                return target
            }
        }
        throw ApkDownloadException(DownloadFailure.NETWORK)
    }

    private fun fetch(
        url: String,
        part: File,
        apk: LatestApk,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val u = URL(url)
        if (u.protocol != "http" && u.protocol != "https") return Outcome.NEXT_MIRROR
        val existing = if (part.isFile) part.length() else 0L
        val conn = u.openConnection() as? HttpURLConnection ?: return Outcome.NEXT_MIRROR
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            val append = when (conn.responseCode) {
                206 -> true
                200 -> false
                416 -> {
                    part.delete()
                    return Outcome.RESTART_FROM_ZERO
                }
                else -> return Outcome.NEXT_MIRROR
            }
            var done = if (append) existing else 0L
            val out = try {
                FileOutputStream(part, append)
            } catch (e: IOException) {
                throw ApkDownloadException(DownloadFailure.STORAGE, e)
            }
            out.use { o ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (isCancelled()) throw ApkDownloadException(DownloadFailure.NETWORK)
                        val n = input.read(buf)
                        if (n < 0) break
                        try {
                            o.write(buf, 0, n)
                        } catch (e: IOException) {
                            throw ApkDownloadException(DownloadFailure.STORAGE, e)
                        }
                        done += n
                        if (done > apk.size) {
                            o.close()
                            part.delete()
                            throw ApkDownloadException(DownloadFailure.CHECKSUM)
                        }
                        onProgress(done, apk.size)
                    }
                }
            }
            return Outcome.DONE
        } finally {
            conn.disconnect()
        }
    }

    /** Deletes everything in [dir] except the target of [keepVersionCode] (all of it when null). */
    fun cleanup(keepVersionCode: Int?) {
        val keep = keepVersionCode?.let { target(it).name }
        dir.listFiles()?.forEach { f ->
            if (keep == null || (f.name != keep && f.name != "$keep.part")) f.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
