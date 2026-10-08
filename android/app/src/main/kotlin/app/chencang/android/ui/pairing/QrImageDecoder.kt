package app.chencang.android.ui.pairing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/** 从相册里选的图片中识别二维码（扫一扫的「从相册选」）。 */
object QrImageDecoder {
    /** 解码前把最长边缩到这个像素以内，避免大图吃内存。 */
    const val MAX_SIDE = 2048

    /** 读取并缩小 [uri] 指向的图片再识别；读不出图或没有二维码返回 null。IO 阻塞，须在后台线程。 */
    fun decode(context: Context, uri: Uri): String? {
        val bitmap = runCatching { load(context, uri) }.getOrNull() ?: return null
        return try {
            decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** 先 HybridBinarizer，失败再 GlobalHistogramBinarizer；都没有返回 null。 */
    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        return attempt(HybridBinarizer(source)) ?: attempt(GlobalHistogramBinarizer(source))
    }

    private fun attempt(binarizer: com.google.zxing.Binarizer): String? {
        val reader = MultiFormatReader()
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        return try {
            reader.decode(BinaryBitmap(binarizer), hints).text
        } catch (_: com.google.zxing.ReaderException) {
            null
        }
    }

    private fun load(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
