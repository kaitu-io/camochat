package app.chencang.shared.media

import uniffi.chencang.MediaBlob
import uniffi.chencang.decryptMediaBlob
import uniffi.chencang.encryptMediaBlob

/**
 * 媒体 blob 的加解密。生产实现直接调 core（`blob_secret` 由 core 内部 CSPRNG 生成，
 * 调用方无法指定——终审 F2）；接口只为让测试能数「加密了几次」。
 */
interface MediaCrypto {
    fun encrypt(plaintext: ByteArray, kind: Int): MediaBlob
    fun decrypt(blob: ByteArray, blobSecret: ByteArray, expectedKind: Int): ByteArray
}

object UniffiMediaCrypto : MediaCrypto {
    override fun encrypt(plaintext: ByteArray, kind: Int): MediaBlob =
        encryptMediaBlob(plaintext, kind.toUByte())

    override fun decrypt(blob: ByteArray, blobSecret: ByteArray, expectedKind: Int): ByteArray =
        decryptMediaBlob(blob, blobSecret, expectedKind.toUByte())
}
