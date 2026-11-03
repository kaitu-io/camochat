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

    private val filter = app.chencang.shared.moderation.ContentFilter(listOf("badword"))

    @Test
    fun `incoming text hitting the filter previews as filtered`() {
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_TEXT, "a badword b"), filter = filter))
            .isEqualTo(UiText.Res(R.string.preview_filtered))
    }

    @Test
    fun `outgoing text is never filtered and clean incoming text is shown`() {
        val out = ChatMessage("m", "alice", ChatMessage.DIRECTION_OUT, "a badword b", 1L)
        assertThat(MessagePreview.of(out, filter = filter)).isEqualTo(UiText.Raw("a badword b"))
        assertThat(MessagePreview.of(msg(ChatMessage.KIND_TEXT, "fine"), filter = filter)).isEqualTo(UiText.Raw("fine"))
    }
}
