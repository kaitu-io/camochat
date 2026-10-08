package app.chencang.shared.chat

import app.chencang.shared.R
import app.chencang.shared.i18n.UiText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessagePreviewTest {
    private fun msg(kind: String, body: String = "") =
        ChatMessage("m", "alice", ChatMessage.DIRECTION_IN, body, 1L, kind = kind)

    @Test
    fun `text shows its own body`() {
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_TEXT, "hi there"))).isEqualTo(UiText.Raw("hi there"))
    }

    @Test
    fun `voice and video use their bracketed labels`() {
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_VOICE))).isEqualTo(UiText.Res(R.string.media_preview_voice))
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_VIDEO))).isEqualTo(UiText.Res(R.string.media_preview_video))
    }

    @Test
    fun `one image is singular, several images are counted`() {
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_IMAGE), itemCount = 1)).isEqualTo(UiText.Res(R.string.media_preview_image))
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_IMAGE))).isEqualTo(UiText.Res(R.string.media_preview_image))
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_IMAGE), itemCount = 4))
            .isEqualTo(UiText.Plural(R.plurals.media_preview_images, 4))
    }

    @Test
    fun `unsupported asks to update the app, whatever an old row stored as body`() {
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_UNSUPPORTED, "old stored text")))
            .isEqualTo(UiText.Res(R.string.thread_unsupported_message))
    }
}
