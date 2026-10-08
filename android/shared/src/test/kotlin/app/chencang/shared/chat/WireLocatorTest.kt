package app.chencang.shared.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import uniffi.chencang.encodeWire

class WireLocatorTest {
    private val wire = encodeWire(byteArrayOf(0xCC.toByte(), 0x10, 0x10, 0x00, 0x41, 0x42, 0x43))
    private val caption = "🔒 陈仓加密图片 · 24小时内有效 https://site.test/m/AbCdEfGhIjKlMnOpQrSt_-"

    @Test
    fun `single wire line is returned unchanged`() {
        assertThat(WireLocator.extract(wire)).isEqualTo(wire)
        assertThat(WireLocator.extract("  $wire  ")).isEqualTo(wire)
    }

    @Test
    fun `trailing quotes and brackets after the wire are stripped like on iOS`() {
        assertThat(WireLocator.extract("「$wire」")).isEqualTo(wire)
        assertThat(WireLocator.extract("$wire\"'”’」』》)） ")).isEqualTo(wire)
    }

    @Test
    fun `R1 two-line form returns the second line`() {
        assertThat(WireLocator.extract("$caption\n$wire")).isEqualTo(wire)
    }

    @Test
    fun `text share form (header line plus wire) returns the wire`() {
        val header = "🔒 CamoChat encrypted message · decrypt with CamoChat https://site.test/m/"
        assertThat(WireLocator.extract(app.chencang.shared.media.MediaShareText.compose(header, wire))).isEqualTo(wire)
    }

    @Test
    fun `CRLF line breaks are handled`() {
        assertThat(WireLocator.extract("$caption\r\n$wire\r\n")).isEqualTo(wire)
    }

    @Test
    fun `caption after the wire is skipped by trying lines from the bottom up`() {
        assertThat(WireLocator.extract("$wire\n$caption")).isEqualTo(wire)
    }

    @Test
    fun `description line starting with lock plus wechat nickname lines still resolves the wire`() {
        val pasted = "阿明 10:32\n$caption\n$wire\n“收到请回复”"
        assertThat(WireLocator.extract(pasted)).isEqualTo(wire)
    }

    @Test
    fun `caption alone or no lock at all yields null`() {
        assertThat(WireLocator.extract(caption)).isNull()
        assertThat(WireLocator.extract("这只是普通文字\n第二行")).isNull()
        assertThat(WireLocator.extract("")).isNull()
    }

    @Test
    fun `candidates are tried last first and the first decodable wins`() {
        val tried = mutableListOf<String>()
        val got = WireLocator.extract("🔒a\n🔒b\n🔒c") { line ->
            tried += line
            if (line == "🔒b") byteArrayOf(1) else throw IllegalStateException("no")
        }
        assertThat(got).isEqualTo("🔒b")
        assertThat(tried).containsExactly("🔒c", "🔒b").inOrder()
    }

    @Test
    fun `prefix before the lock on the same line is stripped`() {
        assertThat(WireLocator.extract("他发来的：$wire")).isEqualTo(wire)
        assertThat(WireLocator.extract("阿明 10:32\n他发来的：$wire\n")).isEqualTo(wire)
    }
}
