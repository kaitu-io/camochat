package app.chencang.android.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.AppPrefs
import app.chencang.shared.intake.PasteBarGate
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: AppPrefs

    @Before fun setUp() {
        prefs = AppPrefs(context)
    }

    private fun show(): Boolean =
        PasteBarGate.shouldShow(AppClipboard.hasText(context), AppClipboard.currentStamp(context), prefs.pasteBarConsumedStamp)

    private fun externalCopy(text: String) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("x", text))
    }

    @Test fun `external copy on fresh install shows the bar`() {
        externalCopy("hello")
        assertThat(show()).isTrue()
    }

    @Test fun `our own write does not show the bar`() {
        assertThat(AppClipboard.write(context, "l", "mine")).isTrue()
        assertThat(show()).isFalse()
    }

    @Test fun `a later external copy shows again`() {
        AppClipboard.write(context, "l", "mine")
        externalCopy("theirs")
        assertThat(show()).isTrue()
    }

    @Test fun `focus mark consumes a copy made in the share sheet`() {
        AppClipboard.markConsumedOnNextFocus()
        externalCopy("copied in sheet")
        assertThat(AppClipboard.consumePendingFocusMark(context, prefs)).isTrue()
        assertThat(show()).isFalse()
        assertThat(AppClipboard.consumePendingFocusMark(context, prefs)).isFalse()
    }

    @Test fun `stopping clears the pending mark so a later copy still shows`() {
        AppClipboard.markConsumedOnNextFocus()
        AppClipboard.clearPendingFocusMark() // ON_STOP: left for the chat app
        externalCopy("their reply")
        assertThat(AppClipboard.consumePendingFocusMark(context, prefs)).isFalse()
        assertThat(show()).isTrue()
    }

    @Test fun `no pending mark leaves a new copy showing`() {
        externalCopy("a")
        assertThat(AppClipboard.consumePendingFocusMark(context, prefs)).isFalse()
        assertThat(show()).isTrue()
    }

    @Test fun `non-positive timestamp is unknown so the bar stays hidden`() {
        assertThat(AppClipboard.normalizeStamp(0L)).isNull()
        assertThat(AppClipboard.normalizeStamp(-1L)).isNull()
        assertThat(AppClipboard.normalizeStamp(7L)).isEqualTo(7L)
        assertThat(PasteBarGate.shouldShow(true, AppClipboard.normalizeStamp(0L), null)).isFalse()
    }

    @Test fun `markConsumed with unknown stamp leaves stored value unchanged`() {
        prefs.setPasteBarConsumedStamp(42L)
        AppClipboard.markConsumed(null as Long?, prefs)
        assertThat(prefs.pasteBarConsumedStamp).isEqualTo(42L)
    }
}
