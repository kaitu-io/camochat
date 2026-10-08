package app.chencang.shared.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [PeerName] 用到 ICU 的字素切分，所以跑在 Robolectric 下（同 MyProfileTest）。 */
@RunWith(RobolectricTestRunner::class)
class PeerNameTest {
    @Test
    fun `blank is no name`() {
        assertThat(PeerName.sanitize(null)).isNull()
        assertThat(PeerName.sanitize("")).isNull()
        assertThat(PeerName.sanitize("  ")).isNull()
        assertThat(PeerName.sanitize("\n‮‏")).isNull()
    }

    @Test
    fun `control and bidi characters are stripped`() {
        assertThat(PeerName.sanitize("老王‮")).isEqualTo("老王")
        assertThat(PeerName.sanitize("⁦老‎王⁩")).isEqualTo("老王")
        assertThat(PeerName.sanitize("老\n王\t")).isEqualTo("老王")
    }

    @Test
    fun `all format and separator characters are stripped`() {
        assertThat(PeerName.sanitize("老\u200B王\uFEFF")).isEqualTo("老王")
        assertThat(PeerName.sanitize("老\u2028王\u2029")).isEqualTo("老王")
        assertThat(PeerName.sanitize("老\u061C王")).isEqualTo("老王")
    }

    @Test
    fun `supplementary plane format characters are stripped`() {
        assertThat(PeerName.sanitize("a\uDB40\uDC01b")).isEqualTo("ab") // U+E0001
        val scotlandFlag = "\uD83C\uDFF4" + "\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertThat(PeerName.sanitize(scotlandFlag)).isEqualTo("\uD83C\uDFF4") // tags gone, plain black flag
    }

    @Test
    fun `zwj emoji sequences are preserved`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertThat(PeerName.sanitize(family)).isEqualTo(family)
    }

    @Test
    fun `over long is clamped like my own name`() {
        assertThat(PeerName.sanitize("字".repeat(100))).isEqualTo("字".repeat(8))
        assertThat(PeerName.sanitize("一二三四五六七😀")).isEqualTo("一二三四五六七")
    }

    @Test
    fun `plain name is kept`() {
        assertThat(PeerName.sanitize("老王")).isEqualTo("老王")
        assertThat(PeerName.sanitize("  Alice ")).isEqualTo("Alice")
    }
}
