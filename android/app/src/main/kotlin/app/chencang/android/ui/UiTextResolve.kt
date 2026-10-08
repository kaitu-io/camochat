package app.chencang.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.chencang.shared.i18n.UiText

/** Resolve a [UiText] against [context]'s current locale. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, count)
    is UiText.Raw -> value
}

/** Compose-side [resolve]; recomposes with the configuration like `stringResource`. */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, count)
    is UiText.Raw -> value
}
