package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 手工拼的最小 MP4 box 字节，覆盖 spec §4.3 的扫描规则（与 iOS `MP4MetadataScannerTests` 同一组用例）。 */
class Mp4MetadataScannerTest {
    // region box builders

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun be32(v: Long): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun be64(v: Long): ByteArray = cat(be32(v ushr 32), be32(v and 0xFFFFFFFFL))

    /** `©` 是 0xA9（Latin-1），不能按 UTF-8 编。 */
    private fun fourCC(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private fun zeros(n: Int) = ByteArray(n)

    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray =
        cat(be32(8L + payload.size), fourCC(type), payload)

    /** size == 1 → 16 字节头，后跟 64 位 largesize。 */
    private fun largeBox(type: String, payload: ByteArray = ByteArray(0)): ByteArray =
        cat(be32(1), fourCC(type), be64(16L + payload.size), payload)

    /** size == 0 → 一直到所在范围末尾。 */
    private fun toEndBox(type: String, payload: ByteArray = ByteArray(0)): ByteArray =
        cat(be32(0), fourCC(type), payload)

    /** ISO `meta` 是 FullBox：4 字节 version/flags 之后才是子 box。 */
    private fun metaBox(vararg children: ByteArray): ByteArray = box("meta", cat(zeros(4), *children))

    private fun container(type: String, vararg children: ByteArray): ByteArray = box(type, cat(*children))

    private fun uuidBox(usertype: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray =
        box("uuid", cat(usertype, payload))

    private val xmpUuid = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    private val ftyp = box("ftyp", cat(fourCC("isom"), be32(0x200), fourCC("isom")))
    private val cleanTrak = container(
        "trak",
        box("tkhd", zeros(84)),
        container(
            "mdia",
            box("mdhd", zeros(24)),
            container("minf", container("stbl", box("stsd", zeros(8)))),
        ),
    )

    private fun scan(bytes: ByteArray) = Mp4MetadataScanner.findPrivacyBoxes(bytes)

    // endregion

    @Test
    fun `clean file has no privacy boxes`() {
        val file = cat(ftyp, container("moov", box("mvhd", zeros(100)), cleanTrak), box("mdat", zeros(32)))
        assertThat(scan(file)).isEmpty()
    }

    @Test
    fun `empty input is clean`() {
        assertThat(scan(ByteArray(0))).isEmpty()
    }

    @Test
    fun `finds QuickTime location in moov udta`() {
        val file = cat(
            ftyp,
            container("moov", box("mvhd", zeros(100)), container("udta", box("©xyz", "+39.9042+116.4074/".toByteArray()))),
        )
        assertThat(scan(file)).containsExactly("©xyz")
    }

    @Test
    fun `finds loci deep in track udta`() {
        val trak = container("trak", box("tkhd", zeros(84)), container("udta", box("loci", zeros(20))))
        assertThat(scan(cat(ftyp, container("moov", trak)))).containsExactly("loci")
    }

    @Test
    fun `finds boxes under stbl`() {
        val trak = container("trak", container("mdia", container("minf", container("stbl", box("loci", zeros(4))))))
        assertThat(scan(container("moov", trak))).containsExactly("loci")
    }

    @Test
    fun `meta is a FullBox and non-empty ilst is reported`() {
        val ilst = box("ilst", box("©day", zeros(16)))
        val meta = metaBox(box("hdlr", zeros(25)), ilst)
        assertThat(scan(cat(ftyp, container("moov", container("udta", meta))))).containsExactly("ilst")
    }

    @Test
    fun `empty ilst is not reported`() {
        val meta = metaBox(box("hdlr", zeros(25)), box("ilst"))
        assertThat(scan(container("moov", container("udta", meta)))).isEmpty()
    }

    @Test
    fun `location inside meta is found after skipping version flags`() {
        val meta = metaBox(box("hdlr", zeros(25)), box("loci", zeros(8)))
        assertThat(scan(container("moov", meta))).containsExactly("loci")
    }

    /** QuickTime 风格 `meta`（keys/ilst）没有 version/flags，紧跟 `hdlr`——识别出来就不跳 4 字节。 */
    @Test
    fun `QuickTime style meta without version flags is also walked`() {
        val meta = box("meta", cat(box("hdlr", zeros(25)), box("keys", zeros(8)), box("ilst", box("data", zeros(4)))))
        assertThat(scan(container("moov", meta))).containsExactly("ilst")
    }

    @Test
    fun `finds XMP uuid at top level and ignores other uuids`() {
        val other = xmpUuid.copyOf().also { it[15] = (it[15].toInt() xor 0xFF).toByte() }
        val file = cat(ftyp, uuidBox(xmpUuid, "<x:xmpmeta/>".toByteArray()), uuidBox(other, zeros(4)))
        assertThat(scan(file)).containsExactly("XMP_")
    }

    @Test
    fun `finds XMP uuid inside udta`() {
        assertThat(scan(container("moov", container("udta", uuidBox(xmpUuid, zeros(4)))))).containsExactly("XMP_")
    }

    @Test
    fun `large size boxes are walked`() {
        // 64 位 size 的 mdat 在前，后面的 moov 仍要被扫到；moov 本身也用 64 位 size
        val file = cat(
            ftyp,
            largeBox("mdat", zeros(40)),
            largeBox("moov", container("udta", box("©xyz", zeros(8)))),
        )
        assertThat(scan(file)).containsExactly("©xyz")
    }

    @Test
    fun `size zero runs to end`() {
        val file = cat(ftyp, toEndBox("moov", container("udta", box("loci", zeros(8)))))
        assertThat(scan(file)).containsExactly("loci")
    }

    @Test
    fun `privacy type inside mdat payload is not misread`() {
        // mdat 不是容器：里面恰好出现 "loci" 字节也不算
        assertThat(scan(cat(ftyp, box("mdat", box("loci", zeros(8)))))).isEmpty()
    }

    @Test
    fun `reports all hits in encounter order`() {
        val udta = container(
            "udta",
            box("©xyz", zeros(8)),
            box("loci", zeros(8)),
            metaBox(box("hdlr", zeros(25)), box("ilst", box("data", zeros(4)))),
        )
        val file = cat(ftyp, container("moov", udta), uuidBox(xmpUuid))
        assertThat(scan(file)).containsExactly("©xyz", "loci", "ilst", "XMP_").inOrder()
    }

    @Test
    fun `truncated box is clamped and still scanned`() {
        val full = container("moov", container("udta", box("©xyz", zeros(8))))
        val truncated = full.copyOf(full.size - 4) // moov/udta/©xyz 声明长度都超出实际
        assertThat(scan(truncated)).containsExactly("©xyz") // 超长声明截到末尾，不算坏头
        assertThat(scan(byteArrayOf(0, 0, 0, 1, 0x6D))).isEmpty() // 不足 8 字节的残余忽略
    }

    @Test
    fun `trailing zero terminator in udta is ignored`() {
        val payload = cat(box("free", zeros(4)), zeros(4)) // QuickTime udta 的 4 字节 0 结尾
        assertThat(scan(container("moov", box("udta", payload)))).isEmpty()
    }

    @Test
    fun `literal XMP_ box in udta is reported`() {
        val file = container("moov", container("udta", box("XMP_", "<x:xmpmeta/>".toByteArray())))
        assertThat(scan(file)).containsExactly("XMP_")
    }

    @Test
    fun `small size header is malformed`() {
        val bad = cat(be32(4), fourCC("free")) // size 非 0/1 却 < 8
        val file = container("moov", box("udta", cat(bad, box("©xyz", zeros(8)))))
        assertThat(scan(file)).containsExactly("malformed")
    }

    @Test
    fun `large size below header is malformed`() {
        val bad = cat(be32(1), fourCC("moov"), be64(12)) // largesize < 16
        assertThat(scan(cat(ftyp, bad, zeros(8)))).containsExactly("malformed")
    }

    @Test
    fun `large size header that does not fit is malformed`() {
        val bad = cat(be32(1), fourCC("moov"), zeros(4)) // 只剩 12 字节，放不下 16 字节头
        assertThat(scan(bad)).containsExactly("malformed")
    }

    @Test
    fun `nesting beyond depth limit is malformed`() {
        var nested = box("free")
        repeat(17) { nested = container("udta", nested) } // 17 层容器：第 17 层的内容在深度 17
        assertThat(scan(nested)).containsExactly("malformed")
        var ok = box("free")
        repeat(16) { ok = container("udta", ok) }
        assertThat(scan(ok)).isEmpty()
    }

    @Test
    fun `largesize beyond Long range is clamped not crashed`() {
        // largesize 最高位为 1（有符号 Long 是负数）→ 当作超长声明夹到末尾，不能被当成「小于头」
        val file = cat(be32(1), fourCC("moov"), be64(-1L), container("udta", box("loci", zeros(4))))
        assertThat(scan(file)).containsExactly("loci")
    }

    @Test
    fun `QuickTime text atoms for time device and software each hit by their own name`() {
        for (atom in listOf("©day", "©mak", "©mod", "©swr", "©too")) {
            // QuickTime 文本 atom：2 字节长度 + 2 字节语言码 + 文本
            val text = cat(byteArrayOf(0, 4, 0x15, 0xC7.toByte()), "Xiao".toByteArray())
            val file = cat(
                ftyp,
                container("moov", box("mvhd", zeros(100)), cleanTrak, container("udta", box(atom, text))),
            )
            assertThat(scan(file)).containsExactly(atom)
        }
    }

    @Test
    fun `all five QuickTime text atoms are reported in order`() {
        val file = container(
            "moov",
            container(
                "udta",
                box("©day", zeros(8)),
                box("©mak", zeros(8)),
                box("©mod", zeros(8)),
                box("©swr", zeros(8)),
                box("©too", zeros(8)),
            ),
        )
        assertThat(scan(file)).containsExactly("©day", "©mak", "©mod", "©swr", "©too").inOrder()
    }
}
