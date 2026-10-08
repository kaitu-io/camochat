package app.chencang.shared.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.chencang.shared.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaShareTextTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `share text is the header line, a newline, then the wire`() {
        assertThat(MediaShareText.compose("H", "🔒w")).isEqualTo("H\n🔒w")
    }

    @Test
    fun `media header carries the kind label and the landing link`() {
        val headers = ResourceShareHeaders(ctx) { "https://site.test/" }
        val many = headers.media(MediaConstants.KIND_IMAGE, 3, "abc")
        assertThat(many).contains(ctx.resources.getQuantityString(R.plurals.media_photo_count, 3, 3))
        assertThat(many).contains("https://site.test/m/abc")
        assertThat(headers.media(MediaConstants.KIND_IMAGE, 1, "abc")).contains(ctx.getString(R.string.media_kind_image))
        assertThat(headers.media(MediaConstants.KIND_VOICE, 1, "abc")).contains(ctx.getString(R.string.media_kind_voice))
        assertThat(headers.media(MediaConstants.KIND_VIDEO, 1, "abc")).contains(ctx.getString(R.string.media_kind_video))
        assertThat(headers.text()).contains("https://site.test/m/")
        assertThat(headers.text()).doesNotContain("\n")
    }
}
