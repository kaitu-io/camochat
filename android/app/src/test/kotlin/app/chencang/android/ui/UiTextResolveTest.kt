package app.chencang.android.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import app.chencang.shared.i18n.UiText
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UiTextResolveTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `Res resolves through getString`() {
        assertThat(UiText.Res(R.string.pairing_add_contact).resolve(ctx))
            .isEqualTo(ctx.getString(R.string.pairing_add_contact))
    }

    @Test
    fun `Res passes its args to the format`() {
        val out = UiText.Res(R.string.card_send_to, listOf("Alice")).resolve(ctx)
        assertThat(out).isEqualTo(ctx.getString(R.string.card_send_to, "Alice"))
        assertThat(out).contains("Alice")
    }

    @Test
    fun `Raw resolves to its value`() {
        assertThat(UiText.Raw("x").resolve(ctx)).isEqualTo("x")
    }

    @Test
    fun `Plural uses getQuantityString with the count as the argument`() {
        val res = ctx.resources
        assertThat(UiText.Plural(R.plurals.media_photo_count, 1).resolve(ctx))
            .isEqualTo(res.getQuantityString(R.plurals.media_photo_count, 1, 1))
        assertThat(UiText.Plural(R.plurals.media_photo_count, 3).resolve(ctx))
            .isEqualTo(res.getQuantityString(R.plurals.media_photo_count, 3, 3))
        assertThat(UiText.Plural(R.plurals.media_photo_count, 3).resolve(ctx)).contains("3")
    }
}
