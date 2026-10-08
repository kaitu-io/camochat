package app.chencang.shared.pairing

import java.util.Base64
import uniffi.chencang.decodeWire
import uniffi.chencang.encodeWire

/**
 * 配对链接：`{shareSite}p/#{base64url(信封字节)}`。信封字节即 🔒 wire 解码后的 `0xCB, type, CBOR…`。
 * 二维码、复制的文字、页面链接是同一个网址；`#` 后面的部分不会发给服务器。
 */
object PairingLink {
    private val LINK = Regex("""(?i:https)://[^\s/?#]+/p/[^\s#]*#([A-Za-z0-9_-]+)""")
    private const val PAIRING_MAGIC: Byte = 0xCB.toByte()

    /** [site] 恒以 `/` 结尾（配置校验保证）。wire 解不出来时片段为空（识别端按「只复制了链接」处理），不抛异常。 */
    fun make(site: String, wire: String): String =
        site + "p/#" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(runCatching { decodeWire(wire) }.getOrDefault(ByteArray(0)))

    /** 文本里所有合法配对链接，按出现顺序，转成标准 🔒 wire；片段坏了或不是配对信封的跳过。 */
    fun wires(text: String): List<String> = LINK.findAll(text).mapNotNull { m ->
        val bytes = runCatching { Base64.getUrlDecoder().decode(m.groupValues[1]) }.getOrNull()
            ?: return@mapNotNull null
        if (bytes.size < 3 || bytes[0] != PAIRING_MAGIC || (bytes[1] != 1.toByte() && bytes[1] != 2.toByte())) {
            return@mapNotNull null
        }
        encodeWire(bytes)
    }.toList()
}
