package app.chencang.android.ui.chat

import app.chencang.shared.media.MediaFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaSaverTest {
    @Test
    fun `extension and mime follow the sniffed format`() {
        assertThat(MediaSaver.imageName(MediaFormat.HEIC, 7)).isEqualTo("chencang_7.heic" to "image/heic")
        assertThat(MediaSaver.imageName(MediaFormat.WEBP, 7)).isEqualTo("chencang_7.webp" to "image/webp")
        assertThat(MediaSaver.imageName(MediaFormat.JPEG, 7)).isEqualTo("chencang_7.jpg" to "image/jpeg")
    }

    @Test
    fun `non-images are refused`() {
        assertThat(MediaSaver.imageName(MediaFormat.UNKNOWN, 7)).isNull()
        assertThat(MediaSaver.imageName(MediaFormat.MP4, 7)).isNull()
    }
}
