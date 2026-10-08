package app.chencang.shared.config

import java.security.MessageDigest
import java.util.Base64

/** Test helpers: fake signature = SHA-256(payload) padded to 64 bytes. */
object ConfigTestSupport {
    fun fakeSig(p: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-256").digest(p)
        return h + h
    }

    val fakeVerify: (ByteArray, ByteArray) -> Boolean = { p, s -> s.contentEquals(fakeSig(p)) }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun envelope(payload: String, sig: ByteArray? = null): String {
        val p = payload.toByteArray()
        return """{"p":"${b64(p)}","s":"${b64(sig ?: fakeSig(p))}"}"""
    }

    const val SHA = "e1bf99ef41e4c63ad1eb453e1e076209e55fc22b609a20be17213158f8d79927"

    fun payload(
        seq: Long = 1,
        schema: Int = 1,
        sources: String = """["https://a.example/c/"]""",
        relays: String = """["https://r.example"]""",
        shareSite: String = "https://s.example/",
        sha: String = SHA,
        versionCode: Int = 3,
        minVersionCode: Int = 3,
        mirrors: String = """["https://m.example/x.apk"]""",
        extra: String = "",
    ) = """{"schema":$schema,"seq":$seq,"sources":$sources,"relays":$relays,"shareSite":"$shareSite"$extra,
"android":{"minVersionCode":$minVersionCode,"latest":{"versionCode":$versionCode,"versionName":"1.0","sha256":"$sha","size":10,"mirrors":$mirrors}}}"""

    fun env(seq: Long, sources: String = """["https://a.example/c/"]""") = envelope(payload(seq = seq, sources = sources))
}
