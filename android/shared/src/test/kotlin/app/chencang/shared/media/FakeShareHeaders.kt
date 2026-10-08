package app.chencang.shared.media

/** Locale-free share header lines for tests: `TH` and `MH:<kind>:<count>:<firstBlobId>`. */
object FakeShareHeaders : ShareHeaders {
    override fun text(): String = "TH"
    override fun media(kind: Int, count: Int, firstBlobId: String): String = "MH:$kind:$count:$firstBlobId"
}
