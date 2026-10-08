package app.chencang.android.ui.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedeemPairingTest {

    @Test
    fun `CODE_RE extracts bare 12-char code from a dashed invite-copy string`() {
        val copy = "陈仓邀请码：MCRE-NDQ4-7BPR　下载：https://site.test"
        val match = CODE_RE.find(copy)?.value?.replace("-", "")
        assertThat(match).isEqualTo("MCRENDQ47BPR")
    }

    @Test
    fun `CODE_RE extracts bare 12-char code from an undashed invite-copy string`() {
        val copy = "陈仓邀请码：MCRENDQ47BPR 下载：https://site.test"
        val match = CODE_RE.find(copy)?.value?.replace("-", "")
        assertThat(match).isEqualTo("MCRENDQ47BPR")
    }

    @Test
    fun `CODE_RE returns null when no code present`() {
        val copy = "随便一句没有邀请码的话"
        assertThat(CODE_RE.find(copy)).isNull()
    }

    @Test
    fun `CODE_RE does not match a 12-char prefix of a longer alnum token`() {
        // \b anchors prevent a false positive on e.g. a 16-char URL token on the clipboard.
        assertThat(CODE_RE.find("ABCD1234EFGH5678")).isNull()
    }
}
