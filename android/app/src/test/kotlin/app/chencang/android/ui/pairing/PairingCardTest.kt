package app.chencang.android.ui.pairing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.chencang.android.ui.pairing.PairingCard.Kind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingCardTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `title uses the name or falls back to the anonymous version`() {
        assertThat(PairingCard.title(context, Kind.INVITE, "小明")).contains("小明")
        assertThat(PairingCard.title(context, Kind.RESPONSE, "小明")).contains("小明")
        val anonInvite = PairingCard.title(context, Kind.INVITE, "  ")
        assertThat(anonInvite).isEqualTo(PairingCard.title(context, Kind.INVITE, ""))
        assertThat(anonInvite).isNotEqualTo(PairingCard.title(context, Kind.INVITE, "小明"))
        assertThat(PairingCard.title(context, Kind.RESPONSE, "")).isNotEqualTo(anonInvite)
    }

    @Test
    fun `card renders at 1080 by 1440`() {
        val card = PairingCard.render(context, Kind.INVITE, "小明", "https://x.example/p/#" + "A".repeat(280))
        assertThat(card.width).isEqualTo(1080)
        assertThat(card.height).isEqualTo(1440)
    }

    private val link = "https://x.example/p/#" + "A".repeat(280)

    @Test
    fun `worst-case copy stays inside the card for both kinds`() {
        val limit = 480 * PairingCard.SCALE
        val longZh = "一二三四五六七八九十一二三四五六七八九十"
        val longEn = "Bartholomew-Alexander Montgomery-Featherstonehaugh"
        for ((kind, name) in listOf(Kind.INVITE to "", Kind.INVITE to longZh, Kind.RESPONSE to longEn, Kind.RESPONSE to "")) {
            val r = PairingCard.renderMeasured(context, kind, name, link)
            assertThat(r.contentBottom).isAtMost(limit.toFloat())
        }
    }

    @Test
    fun `worst-case copy fits in English too`() {
        val config = context.resources.configuration
        val saved = config.locales
        try {
            config.setLocale(java.util.Locale.ENGLISH)
            val en = context.createConfigurationContext(config)
            val limit = 480f * PairingCard.SCALE
            assertThat(PairingCard.renderMeasured(en, Kind.INVITE, "", link).contentBottom).isAtMost(limit)
            assertThat(PairingCard.renderMeasured(en, Kind.RESPONSE, "Bartholomew-Alexander Montgomery-Featherstonehaugh", link).contentBottom).isAtMost(limit)
        } finally {
            config.setLocales(saved)
        }
    }
}
