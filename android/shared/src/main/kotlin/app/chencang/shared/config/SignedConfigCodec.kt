package app.chencang.shared.config

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Envelope `{"p": base64url(payload), "s": base64url(sig)}` -> verified, validated [AppConfig]. */
object SignedConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Re = Regex("^[0-9a-f]{64}$")

    /** Any failure (bad envelope, bad signature, bad schema, failed validation) yields null. */
    fun decode(envelope: String, verify: (payload: ByteArray, sig: ByteArray) -> Boolean): AppConfig? = try {
        val obj = json.parseToJsonElement(envelope).jsonObject
        val dec = Base64.getUrlDecoder()
        val payload = dec.decode(obj.getValue("p").jsonPrimitive.content)
        val sig = dec.decode(obj.getValue("s").jsonPrimitive.content)
        if (!verify(payload, sig)) null
        else json.decodeFromString(AppConfig.serializer(), String(payload, Charsets.UTF_8)).takeIf(::valid)
    } catch (_: Exception) {
        null
    }

    private fun https(u: String) = u.startsWith("https://")

    private fun valid(c: AppConfig): Boolean {
        if (c.schema != 1) return false
        if (c.sources.isEmpty() || c.relays.isEmpty()) return false
        if (!c.sources.all { https(it) && it.endsWith("/") }) return false
        if (!c.relays.all { https(it) && !it.endsWith("/") }) return false
        if (!https(c.shareSite) || !c.shareSite.endsWith("/")) return false
        val a = c.android ?: return true
        val l = a.latest
        return sha256Re.matches(l.sha256) && l.versionCode >= a.minVersionCode &&
            l.mirrors.isNotEmpty() && l.mirrors.all(::https)
    }
}
