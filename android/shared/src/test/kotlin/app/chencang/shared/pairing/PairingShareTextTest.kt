package app.chencang.shared.pairing

import app.chencang.shared.intake.IntakeClassifier
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.pairing.inband.PairingTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import uniffi.chencang.encodeWire

class PairingShareTextTest {
    private val site = "https://x.example/"
    private val bundleWire = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
    private val headerWire = PairingTransport.headerToWire(byteArrayOf(4, 5, 6))
    private val envelope = encodeWire(byteArrayOf(0xCB.toByte(), 1, 1, 2, 3))

    private fun invite(link: String) = "🔒 CamoChat invite\nOpen the link:\n$link"
    private fun response(link: String) = "🔒 CamoChat reply\nCopy this whole message:\n$link"

    @Test
    fun `compose is the header alone`() {
        assertThat(PairingShareText.compose("H\nlink")).isEqualTo("H\nlink")
    }

    @Test
    fun `make and wires round trip`() {
        val link = PairingLink.make(site, envelope)
        assertThat(link).isEqualTo("https://x.example/p/#ywEBAgM")
        assertThat(PairingLink.wires(link)).containsExactly(envelope)
    }

    @Test
    fun `full composed invite and response classify with the canonical wire`() {
        val inv = PairingShareText.compose(invite(PairingLink.make(site, bundleWire)))
        assertThat(IntakeClassifier.classify(inv)).isEqualTo(IntakeKind.PairingInvite(bundleWire))
        val res = PairingShareText.compose(response(PairingLink.make(site, headerWire)))
        assertThat(IntakeClassifier.classify(res)).isEqualTo(IntakeKind.PairingResponse(headerWire))
    }

    @Test
    fun `link alone and decorated links classify`() {
        val link = PairingLink.make(site, bundleWire)
        val expected = IntakeKind.PairingInvite(bundleWire)
        assertThat(IntakeClassifier.classify(link)).isEqualTo(expected)
        assertThat(IntakeClassifier.classify("「$link」")).isEqualTo(expected)
        assertThat(IntakeClassifier.classify("$link 点开就能加我")).isEqualTo(expected)
        assertThat(IntakeClassifier.classify(link.replace("/p/#", "/p/?from=x#"))).isEqualTo(expected)
        assertThat(IntakeClassifier.classify(link.replace("https", "HTTPS").replace("x.example", "X.EXAMPLE")))
            .isEqualTo(expected)
    }

    @Test
    fun `upper case path is not a pairing link`() {
        val link = PairingLink.make(site, bundleWire).replace("/p/#", "/P/#")
        assertThat(IntakeClassifier.classify(link)).isEqualTo(IntakeKind.NotOurs)
    }

    @Test
    fun `bare p link without a fragment is link only`() {
        assertThat(IntakeClassifier.classify("https://x.example/p/")).isEqualTo(IntakeKind.LinkOnly)
    }

    @Test
    fun `broken or foreign fragments fall back`() {
        val header = "🔒 CamoChat invite\nOpen the link:\n"
        assertThat(IntakeClassifier.classify(header + "https://x.example/p/#!!!")).isEqualTo(IntakeKind.Incomplete)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        val session = b64.encodeToString(byteArrayOf(0xCC.toByte(), 0xC8.toByte(), 1, 2))
        assertThat(IntakeClassifier.classify(header + "https://x.example/p/#$session")).isEqualTo(IntakeKind.Incomplete)
        val cb03 = b64.encodeToString(byteArrayOf(0xCB.toByte(), 3, 1, 2))
        assertThat(IntakeClassifier.classify(header + "https://x.example/p/#$cb03")).isEqualTo(IntakeKind.Incomplete)
        assertThat(PairingLink.wires("https://x.example/p/#ywE")).isEmpty()
    }

    @Test
    fun `a lock wire wins over a pairing link`() {
        val session = encodeWire(byteArrayOf(0xCC.toByte(), 0xC8.toByte()) + ByteArray(20))
        val text = "${PairingLink.make(site, bundleWire)}\n$session"
        assertThat(IntakeClassifier.classify(text)).isEqualTo(IntakeKind.Message(session))
    }

    @Test
    fun `last valid link wins`() {
        val text = "${PairingLink.make(site, bundleWire)} ${PairingLink.make(site, headerWire)}"
        assertThat(IntakeClassifier.classify(text)).isEqualTo(IntakeKind.PairingResponse(headerWire))
    }
}
