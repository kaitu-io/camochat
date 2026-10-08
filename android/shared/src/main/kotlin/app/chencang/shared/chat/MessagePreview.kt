package app.chencang.shared.chat

import app.chencang.shared.R
import app.chencang.shared.i18n.UiText

/**
 * One-line text for a message where only a summary fits (conversation list, placeholder bubble,
 * seal card). Media and placeholder rows store `""` as body, so their text is derived from `kind`
 * here and follows the current system language.
 */
object MessagePreview {
    /** [itemCount] = number of media items (only images can carry more than one). */
    fun of(message: ChatMessage, itemCount: Int = 1): UiText =
        if (message.kind == ChatMessage.KIND_TEXT) UiText.Raw(message.body) else ofKind(message.kind, itemCount)

    /** Preview for a non-text [kind] (`ChatMessage.KIND_*`); unknown kinds read as the update-the-app placeholder. */
    fun ofKind(kind: String, itemCount: Int = 1): UiText = when (kind) {
        ChatMessage.KIND_VOICE -> UiText.Res(R.string.media_preview_voice)
        ChatMessage.KIND_VIDEO -> UiText.Res(R.string.media_preview_video)
        ChatMessage.KIND_IMAGE ->
            if (itemCount > 1) UiText.Plural(R.plurals.media_preview_images, itemCount) else UiText.Res(R.string.media_preview_image)
        else -> UiText.Res(R.string.thread_unsupported_message)
    }
}
