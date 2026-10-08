package app.chencang.android.ui.pairing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.intake.IntakeClassifier
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.pairing.inband.PairingTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingShareHeaderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val site = "https://site.test/"

    @Test
    fun `invite text ends with the pairing link and classifies as the invite`() {
        val wire = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
        val text = pairingShareText(context, site, wire, isResponse = false)
        assertThat(text.lines().first()).startsWith("🔒")
        assertThat(text.lines().last()).startsWith("https://site.test/p/#")
        assertThat(text).doesNotContain(wire)
        assertThat(IntakeClassifier.classify(text)).isEqualTo(IntakeKind.PairingInvite(wire))
    }

    @Test
    fun `response text also carries the pairing link`() {
        val wire = PairingTransport.headerToWire(byteArrayOf(4, 5, 6))
        val text = pairingShareText(context, site, wire, isResponse = true)
        assertThat(text.lines().first()).startsWith("🔒")
        assertThat(text.lines().last()).startsWith("https://site.test/p/#")
        assertThat(IntakeClassifier.classify(text)).isEqualTo(IntakeKind.PairingResponse(wire))
    }

    @Test
    fun `the two headers differ`() {
        val w = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
        val inv = pairingShareText(context, site, w, isResponse = false).lines().first()
        val res = pairingShareText(context, site, w, isResponse = true).lines().first()
        assertThat(inv).isNotEqualTo(res)
    }
}
