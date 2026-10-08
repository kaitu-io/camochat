package app.chencang.shared.media

import java.io.File
import java.io.IOException
import java.util.UUID

class StorageFullException : IOException("storage full")

/**
 * 裁决 R5 的本地文件布局：`<root>/<messageId>/<index>.cca`（密文，上传重试用）与
 * `<index>.bin`（明文）。生产 root = `<filesDir>/media`（不进备份：manifest 已
 * `allowBackup=false`）。[freeBytes] 可注入，用来测「手机存储空间不足」。
 *
 * [scratchDirs]：媒体流程在 `cacheDir` 下的临时目录（预处理半成品、录音、相机原片，见
 * [scratchDirsUnder]）。里面可能躺着被进程死亡打断留下的明文，[deleteAll]（删账户）一并清掉（终审 M3）。
 */
class MediaFiles(
    val root: File,
    private val scratchDirs: List<File> = emptyList(),
    private val freeBytes: () -> Long = {
        root.mkdirs()
        root.usableSpace
    },
) {
    fun dir(messageId: String): File {
        require(SAFE_ID.matches(messageId)) { "bad message id: $messageId" }
        return File(root, messageId)
    }

    fun cca(messageId: String, index: Int): File = File(dir(messageId), "$index.cca")

    fun bin(messageId: String, index: Int): File = File(dir(messageId), "$index.bin")

    /** 剩余空间不足 [bytes] + 余量 → [StorageFullException]。 */
    fun ensureSpace(bytes: Long) {
        if (freeBytes() < bytes + SPACE_MARGIN_BYTES) throw StorageFullException()
    }

    /** 先写 `.tmp` 再改名，崩溃时不会留下半个 `.cca`/`.bin`。 */
    fun write(target: File, bytes: ByteArray) {
        ensureSpace(bytes.size.toLong())
        val parent = requireNotNull(target.parentFile)
        parent.mkdirs()
        val tmp = File(parent, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed: $target")
        }
    }

    /** 把预处理好的文件（cache 里）挪进消息目录；同分区直接改名，否则拷贝。 */
    fun moveIn(source: File, target: File) {
        requireNotNull(target.parentFile).mkdirs()
        if (source.renameTo(target)) return
        ensureSpace(source.length())
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    /** 转发用：把某条 `.bin` 复制到暂存区，交给发送流程再 [moveIn]。 */
    fun stageCopy(source: File): File {
        ensureSpace(source.length())
        val dst = File(File(root, STAGING_DIR), "${UUID.randomUUID()}.bin")
        requireNotNull(dst.parentFile).mkdirs()
        source.copyTo(dst)
        return dst
    }

    /** @throws IOException 目录没能删干净（例如某个文件被占用）——绝不能悄悄留下明文却报成功。 */
    fun deleteMessage(messageId: String) {
        val target = dir(messageId)
        target.deleteRecursively()
        if (target.exists()) throw IOException("media dir not fully deleted: $target")
    }

    /** @throws IOException 同 [deleteMessage]：整棵 media 目录或某个临时目录没能删干净（每个都会尝试）。 */
    fun deleteAll() {
        val survivors = (listOf(root) + scratchDirs).filter { dir ->
            dir.deleteRecursively()
            dir.exists()
        }
        if (survivors.isNotEmpty()) throw IOException("media dirs not fully deleted: ${survivors.size}")
    }

    companion object {
        /** `cacheDir/prep`：图片/视频预处理输出（发送时挪进消息目录）。 */
        const val PREP_DIR = "prep"

        /** `cacheDir/voice`：录音中的 Ogg 文件。 */
        const val VOICE_DIR = "voice"

        /** `cacheDir/capture`：系统相机写回的原片（经 FileProvider）。 */
        const val CAPTURE_DIR = "capture"

        fun scratchDirsUnder(cacheDir: File): List<File> =
            listOf(PREP_DIR, VOICE_DIR, CAPTURE_DIR).map { File(cacheDir, it) }

        const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
        private const val STAGING_DIR = "staging"
        private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
