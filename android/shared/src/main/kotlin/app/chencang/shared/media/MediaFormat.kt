package app.chencang.shared.media

/**
 * 按魔数嗅探媒体格式（不信扩展名/类型字段）；规则与 iOS `MediaFormat.sniff` 逐条一致，
 * 旧消息里的 JPEG / H.264 MP4 因此天然照旧可解。
 */
enum class MediaFormat(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"),
    HEIC("heic", "image/heic"),
    WEBP("webp", "image/webp"),
    MP4("mp4", "video/mp4"),
    OGG("ogg", "audio/ogg"),
    UNKNOWN("bin", "application/octet-stream"),
    ;

    val isImage: Boolean get() = this == JPEG || this == HEIC || this == WEBP

    companion object {
        private val HEIC_BRANDS = setOf("heic", "heix", "mif1", "msf1", "hevc", "hevx")

        fun sniff(b: ByteArray): MediaFormat {
            fun ascii(from: Int, to: Int) = String(b, from, to - from, Charsets.ISO_8859_1)
            if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 &&
                (b[2].toInt() and 0xFF) == 0xFF
            ) {
                return JPEG
            }
            if (b.size >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP") return WEBP
            if (b.size >= 12 && ascii(4, 8) == "ftyp") return if (ascii(8, 12) in HEIC_BRANDS) HEIC else MP4
            if (b.size >= 4 && ascii(0, 4) == "OggS") return OGG
            return UNKNOWN
        }

        /** 只读文件头，避免为嗅探把整个媒体读进内存。 */
        fun sniff(file: java.io.File): MediaFormat {
            val head = file.inputStream().use { it.readNBytes(12) }
            return sniff(head)
        }
    }
}
