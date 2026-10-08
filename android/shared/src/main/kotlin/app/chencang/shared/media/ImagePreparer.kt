package app.chencang.shared.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * spec §3.3 / 裁决 R8：长边压到 1920（先 inSampleSize 粗降再精确缩放，防 OOM），
 * 先把 EXIF 方向转进像素，再用 `Bitmap.compress` 重新编码——输出天然不带 EXIF/GPS。
 * 输出 WebP 有损（API 30+ WEBP_LOSSY，API 29 WEBP），质量 80 起每步 −5，降到 45 仍超预算就拦下；
 * 编码后再用 [WebpChunks] 校验没有 EXIF/XMP 块，有就让发送失败。
 */
object ImagePreparer {
    private const val QUALITY_START = 80
    private const val QUALITY_FLOOR = 45
    private const val QUALITY_STEP = 5

    /** [open] 每次调用都要返回一个从头开始的新流（要读三遍：尺寸、方向、像素）。 */
    fun prepare(
        open: () -> InputStream,
        outFile: File,
        encode: (Bitmap) -> ByteArray = { compressUnderBudget(it) },
    ): PreparedMedia {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw MediaRejected(MediaLimits.IMAGE_UNREADABLE)

        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), MediaConstants.IMAGE_MAX_EDGE)
        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw MediaRejected(MediaLimits.IMAGE_UNREADABLE)

        val upright = applyOrientation(scaleToMaxEdge(decoded, MediaConstants.IMAGE_MAX_EDGE), orientation)
        val webp = encode(upright)
        check(WebpChunks.privacyChunks(webp).isEmpty()) { "re-encoded WebP must be clean WebP without EXIF/XMP" }
        requireNotNull(outFile.parentFile).mkdirs()
        outFile.writeBytes(webp)
        return PreparedMedia(MediaConstants.KIND_IMAGE, outFile, 0, upright.width, upright.height)
    }

    fun compressUnderBudget(
        bitmap: Bitmap,
        budget: Long = MediaConstants.plainBudget(MediaConstants.KIND_IMAGE),
    ): ByteArray {
        for (q in QUALITY_START downTo QUALITY_FLOOR step QUALITY_STEP) {
            val out = ByteArrayOutputStream()
            bitmap.compress(webpFormat(), q, out)
            if (out.size() <= budget) return out.toByteArray()
        }
        throw MediaRejected(MediaLimits.IMAGE_TOO_BIG)
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    internal fun sampleSize(longEdge: Int, target: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= target) s *= 2
        return s
    }

    private fun scaleToMaxEdge(b: Bitmap, max: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= max) return b
        val f = max.toFloat() / long
        return Bitmap.createScaledBitmap(
            b,
            (b.width * f).roundToInt().coerceAtLeast(1),
            (b.height * f).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** 全部 8 个 EXIF 方向值（含镜像 2/4/5/7），标准矩阵表——不止旋转，还要处理翻转。 */
    private fun applyOrientation(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                m.setRotate(180f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
