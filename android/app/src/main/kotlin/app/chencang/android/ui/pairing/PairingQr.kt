package app.chencang.android.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 配对二维码的编码：内容是 [PairingLink] 网址（UTF-8 字节模式、纠错 M）。
 * 卡片渲染（[PairingCard]）自己画 quiet zone，这里只给原始模块矩阵。
 * 二维码内容是公钥材料，只用于屏幕展示，不得写日志。
 */
object PairingQr {
    /** 原始模块矩阵（无边距、无缩放）。 */
    fun matrix(text: String): BitMatrix = MultiFormatWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        1,
        1,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        ),
    )
}
