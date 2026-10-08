package app.chencang.shared.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text a `:shared` pure-logic layer hands to the UI without needing a Context.
 * `:app` resolves it (`UiText.resolve(context)` / `UiText.asString()`); JVM tests assert ids.
 */
sealed interface UiText {
    /** A string resource, formatted with [args] in order (`%1$s`, `%2$s`, ...). */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A plurals resource; [count] picks the form and is also the single format argument. */
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText

    /** Already-final text (user content, names). Never use for UI copy. */
    data class Raw(val value: String) : UiText
}
