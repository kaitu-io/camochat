package app.chencang.android.ui.chat

import app.chencang.shared.media.ShareHeaders

/** Locale-free share header lines for `:app` tests: `TH` and `MH:<kind>:<count>:<firstBlobId>`. */
object FakeShareHeaders : ShareHeaders {
    override fun text(): String = "TH"
    override fun media(kind: Int, count: Int, firstBlobId: String): String = "MH:$kind:$count:$firstBlobId"
}
