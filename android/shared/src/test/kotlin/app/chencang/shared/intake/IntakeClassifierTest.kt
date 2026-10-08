package app.chencang.shared.intake

import app.chencang.shared.pairing.inband.PairingTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import uniffi.chencang.encodeWire

class IntakeClassifierTest {
    private val sessionWire = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte(), 1, 2))

    @Test
    fun `session wire is a message`() {
        assertThat(IntakeClassifier.classify(sessionWire)).isEqualTo(IntakeKind.Message(sessionWire))
    }

    @Test
    fun `pairing code under a header line and a nickname line is a pairing invite`() {
        val wire = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
        val raw = "他发来的：\n🔒 陈仓邀请 · 只能加一个人\n我在用「陈仓」和你加密聊天，两步加我：\n① 没装的话先下载：https://site.test/p/\n② 装好后长按复制这整条消息，打开陈仓点「粘贴」\n$wire\n"
        assertThat(IntakeClassifier.classify(raw)).isEqualTo(IntakeKind.PairingInvite(wire))
    }

    @Test
    fun `response wire is a pairing response`() {
        val wire = PairingTransport.headerToWire(byteArrayOf(4, 5, 6))
        assertThat(IntakeClassifier.classify(wire)).isEqualTo(IntakeKind.PairingResponse(wire))
    }

    @Test
    fun `header line alone is incomplete`() {
        assertThat(IntakeClassifier.classify("🔒 陈仓加密消息 · 用陈仓解密 https://site.test/m/"))
            .isEqualTo(IntakeKind.Incomplete)
    }

    @Test
    fun `blank text is incomplete`() {
        assertThat(IntakeClassifier.classify("   ")).isEqualTo(IntakeKind.Incomplete)
        assertThat(IntakeClassifier.classify("")).isEqualTo(IntakeKind.Incomplete)
    }

    @Test
    fun `text without a lock is not ours`() {
        assertThat(IntakeClassifier.classify("你好")).isEqualTo(IntakeKind.NotOurs)
    }

    @Test
    fun `decodable wire with unknown magic is not ours`() {
        assertThat(IntakeClassifier.classify(encodeWire(byteArrayOf(0x01, 0x02)))).isEqualTo(IntakeKind.NotOurs)
    }

    @Test
    fun `a wire inside corner brackets or quotes is still a message`() {
        assertThat(IntakeClassifier.classify("「$sessionWire」")).isEqualTo(IntakeKind.Message(sessionWire))
        assertThat(IntakeClassifier.classify("“$sessionWire”")).isEqualTo(IntakeKind.Message(sessionWire))
        assertThat(IntakeClassifier.classify("($sessionWire）\n")).isEqualTo(IntakeKind.Message(sessionWire))
        assertThat(IntakeClassifier.classify("他说：\"$sessionWire\" 』》'’")).isEqualTo(IntakeKind.Message(sessionWire))
    }

    @Test
    fun `unknown magic on the last line keeps scanning earlier lines`() {
        val invite = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
        val unknown = encodeWire(byteArrayOf(0x01, 0x02))
        assertThat(IntakeClassifier.classify("$invite\n$unknown")).isEqualTo(IntakeKind.PairingInvite(invite))
    }

    @Test
    fun `lock in the middle of a line is still found`() {
        assertThat(IntakeClassifier.classify("他发来的：$sessionWire")).isEqualTo(IntakeKind.Message(sessionWire))
    }

    @Test
    fun `recognized is true only for the three wire kinds`() {
        assertThat(IntakeKind.Message("w").recognized).isTrue()
        assertThat(IntakeKind.PairingInvite("w").recognized).isTrue()
        assertThat(IntakeKind.PairingResponse("w").recognized).isTrue()
        assertThat(IntakeKind.Incomplete.recognized).isFalse()
        assertThat(IntakeKind.NotOurs.recognized).isFalse()
        assertThat(IntakeKind.LinkOnly.recognized).isFalse()
    }

    @Test
    fun `a bare share link alone is link only`() {
        assertThat(IntakeClassifier.classify("https://d3nnqcgewrb4f0.cloudfront.net/m/")).isEqualTo(IntakeKind.LinkOnly)
        assertThat(IntakeClassifier.classify("  https://x.example/p/ \n")).isEqualTo(IntakeKind.LinkOnly)
        assertThat(IntakeClassifier.classify("https://x.example/m/」")).isEqualTo(IntakeKind.LinkOnly)
        assertThat(IntakeClassifier.classify("「https://x.example/m/」")).isEqualTo(IntakeKind.LinkOnly)
        assertThat(IntakeClassifier.classify("HTTPS://x.example/p/")).isEqualTo(IntakeKind.LinkOnly)
    }

    @Test
    fun `anything other than a bare share link is not link only`() {
        assertThat(IntakeClassifier.classify("https://x.example/m/abc")).isEqualTo(IntakeKind.NotOurs)
        assertThat(IntakeClassifier.classify("https://x.example/")).isEqualTo(IntakeKind.NotOurs)
        assertThat(IntakeClassifier.classify("http://x.example/m/")).isEqualTo(IntakeKind.NotOurs)
        assertThat(IntakeClassifier.classify("看这个 https://x.example/m/")).isEqualTo(IntakeKind.NotOurs)
        assertThat(IntakeClassifier.classify("你好")).isEqualTo(IntakeKind.NotOurs)
    }

    @Test
    fun `a real message that also carries the link is still a message`() {
        val raw = "🔒 陈仓加密消息 · 用陈仓解密 https://x.example/m/\n$sessionWire"
        assertThat(IntakeClassifier.classify(raw)).isEqualTo(IntakeKind.Message(sessionWire))
    }
}
