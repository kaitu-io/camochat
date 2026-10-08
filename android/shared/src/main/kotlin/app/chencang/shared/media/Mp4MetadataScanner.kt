package app.chencang.shared.media

/**
 * 视频输出的隐私兜底校验（spec 2026-09-30 §4.3）：扫 MP4 box 树，找出不该出现的元数据 box。
 * [VideoPreparer] 输出后调用，非空即判失败——发送报错，绝不带着位置上传。
 *
 * 规则与 iOS `MP4MetadataScanner.privacyBoxes` 逐条一致（task-6-report「Scanner spec」）：
 * - 只下钻容器 `moov/trak/mdia/minf/stbl/udta/meta`；`meta` 是 ISO FullBox，先跳 4 字节 version/flags，
 *   但 QuickTime 风格的 `meta`（payload[4..8] == "hdlr"）没有 version/flags，识别到就不跳；
 * - box 头支持 size == 1（64 位 largesize，16 字节头）与 size == 0（到所在范围末尾）；
 *   声明长度超出范围的截到范围末尾（截断文件照扫，不算坏头）；
 * - 命中：`©xyz`、`loci`、`XMP_`（QuickTime 字面 box）、QuickTime 文本 atom `©day` `©mak` `©mod` `©swr` `©too`
 *   （拍摄时间/厂商/机型/软件，各按自己的名字报）、usertype 为 XMP UUID 的 `uuid`（报成 `XMP_`）、
 *   内容非空的 `ilst`；命中的 box 不再下钻；
 * - 失败即拒：坏头（size 为 2..7、largesize < 16、size == 1 但放不下 16 字节头）或嵌套深于 16 层，
 *   报 `malformed` 并停止这一层；一层末尾不足 8 字节的残余忽略（QuickTime `udta` 常以 4 字节 0 结尾）。
 */
object Mp4MetadataScanner {
    const val MALFORMED = "malformed"

    /** XMP 的 uuid box usertype：BE7ACFCB-97A9-42E8-9C71-999491E3AFAC。 */
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta")
    private const val MAX_DEPTH = 16

    /** 按名字直接命中的 box（spec §4.3 禁位置、设备、时间、软件）；与 iOS 同一张表。 */
    private val HIT_TYPES = setOf("©xyz", "loci", "XMP_", "©day", "©mak", "©mod", "©swr", "©too")

    /** 按出现顺序返回命中的 box 名（可重复）；空列表 = 干净。 */
    fun findPrivacyBoxes(bytes: ByteArray): List<String> {
        val hits = mutableListOf<String>()
        walk(bytes, 0, bytes.size, 0, hits)
        return hits
    }

    private fun walk(b: ByteArray, start: Int, end: Int, depth: Int, hits: MutableList<String>) {
        if (depth > MAX_DEPTH) {
            hits += MALFORMED
            return
        }
        var offset = start
        while (end - offset >= 8) {
            val size32 = be32(b, offset)
            val type = fourCC(b, offset + 4)
            var header = 8
            // 用 Long 表示声明长度；largesize 超出 Long 正数范围的一律当「超长」，下面夹到范围末尾
            val size: Long = when (size32) {
                1L -> {
                    if (end - offset < 16) {
                        hits += MALFORMED
                        return
                    }
                    header = 16
                    be64(b, offset + 8).let { if (it < 0) Long.MAX_VALUE else it }
                }
                0L -> (end - offset).toLong()
                else -> size32
            }
            if (size < header) { // 坏头：这一层没法再往下走，判失败
                hits += MALFORMED
                return
            }
            val boxEnd = if (size > end - offset) end else offset + size.toInt()
            val payload = offset + header

            when (type) {
                in HIT_TYPES -> hits += type
                "ilst" -> if (boxEnd > payload) hits += "ilst"
                "uuid" -> if (boxEnd - payload >= 16 && regionEquals(b, payload, XMP_UUID)) hits += "XMP_"
                else -> if (type in CONTAINERS) {
                    var childStart = payload
                    if (type == "meta" && !(boxEnd - payload >= 8 && fourCC(b, payload + 4) == "hdlr")) {
                        childStart += 4 // ISO FullBox version/flags
                    }
                    if (childStart < boxEnd) walk(b, childStart, boxEnd, depth + 1, hits)
                }
            }
            offset = boxEnd
        }
    }

    private fun be32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun be64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    /** box 类型按 Latin-1 解（`©` = 0xA9）。 */
    private fun fourCC(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.ISO_8859_1)

    private fun regionEquals(b: ByteArray, at: Int, expected: ByteArray): Boolean =
        expected.indices.all { b[at + it] == expected[it] }
}
