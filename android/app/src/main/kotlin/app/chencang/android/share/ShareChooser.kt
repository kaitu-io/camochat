package app.chencang.android.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.chencang.android.receive.ProcessTextActivity

/**
 * Leaves the app's own 「陈仓解密」 SEND target ([ProcessTextActivity]) out of a share sheet built with
 * `Intent.createChooser`: sharing ciphertext out to ourselves would only decrypt it back.
 */
fun Intent.excludingOwnShareTarget(context: Context): Intent = putExtra(
    Intent.EXTRA_EXCLUDE_COMPONENTS,
    arrayOf(ComponentName(context, ProcessTextActivity::class.java)),
)
