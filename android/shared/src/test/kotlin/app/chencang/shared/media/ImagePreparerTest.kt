package app.chencang.shared.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import app.chencang.shared.R
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** NATIVE 图形模式：Robolectric 用真 Skia 编解码 JPEG，结果与真机一致。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePreparerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun textured(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            Color.rgb(x * 255 / w, y * 255 / h, (x xor y) and 0xFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun jpeg(b: Bitmap, q: Int = 90): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** 左上角 1/4 白、其余黑——旋转/镜像后角落落在哪里一看便知，不受压缩伪影影响。 */
    private fun cornerMarked(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < w / 2 && y < h / 2) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun isWhite(pixel: Int): Boolean = Color.red(pixel) > 128

    /** 在 SOI 之后插一个 APP1/Exif 段，TIFF 里只有一个 Orientation 条目。 */
    private fun withExifOrientation(src: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return src.copyOfRange(0, 2) + seg + src.copyOfRange(2, src.size)
    }

    private fun prepare(bytes: ByteArray): Pair<PreparedMedia, ByteArray> {
        val out = File(tmp.root, "out-${System.nanoTime()}.jpg")
        val p = ImagePreparer.prepare({ bytes.inputStream() }, out)
        return p to out.readBytes()
    }

    @Test
    fun `large photo is scaled to 1920 on the long edge and stays under budget`() {
        val (p, out) = prepare(jpeg(textured(3000, 2000)))
        assertThat(p.kind).isEqualTo(MediaConstants.KIND_IMAGE)
        assertThat(p.width).isEqualTo(1920)
        assertThat(p.height).isEqualTo(1280)
        assertThat(out.size.toLong()).isAtMost(2_097_102L)
        assertThat(MediaFormat.sniff(out)).isEqualTo(MediaFormat.WEBP)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(decoded.width).isEqualTo(1920)
    }

    @Test
    fun `small photo keeps its size`() {
        val (p, _) = prepare(jpeg(textured(640, 480)))
        assertThat(p.width).isEqualTo(640)
        assertThat(p.height).isEqualTo(480)
    }

    @Test
    fun `gps in the source exif is dropped by re-encoding`() {
        // 前置：源 JPEG 真带 GPS（用 ExifInterface 写进经纬度，再读回来确认），否则本测试是空转
        val src = File(tmp.root, "gps.jpg").apply { writeBytes(jpeg(textured(400, 300))) }
        ExifInterface(src.absolutePath).apply {
            // 39°54'15.12"N 116°24'26.64"E（框架 ExifInterface 没有 setLatLong，按 EXIF 有理数写）
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,54/1,1512/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "116/1,24/1,2664/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        val input = src.readBytes()
        val latLong = FloatArray(2)
        assertThat(ExifInterface(ByteArrayInputStream(input)).getLatLong(latLong)).isTrue()
        assertThat(latLong[0].toDouble()).isWithin(1e-3).of(39.9042)
        assertThat(latLong[1].toDouble()).isWithin(1e-3).of(116.4074)

        val (_, out) = prepare(input)
        // 输出：没有任何 EXIF/XMP chunk，也读不出经纬度
        assertThat(WebpChunks.privacyChunks(out)).isEmpty()
        val outExif = ExifInterface(ByteArrayInputStream(out))
        assertThat(outExif.getLatLong(FloatArray(2))).isFalse()
        assertThat(outExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        assertThat(outExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)).isNull()
    }

    @Test
    fun `prepare fails when encoder output carries an EXIF chunk, or is not webp`() {
        val exifWebp = "RIFF".toByteArray() + byteArrayOf(22, 0, 0, 0) + "WEBP".toByteArray() +
            "EXIF".toByteArray() + byteArrayOf(2, 0, 0, 0, 1, 2)
        val out = File(tmp.root, "bad.webp")
        val src = jpeg(textured(64, 64))
        assertThrows(IllegalStateException::class.java) {
            ImagePreparer.prepare({ src.inputStream() }, out, encode = { exifWebp })
        }
        assertThrows(IllegalStateException::class.java) {
            ImagePreparer.prepare({ src.inputStream() }, out, encode = { jpeg(it) })
        }
    }

    @Test
    fun `exif orientation 6 is applied before exif is dropped`() {
        val input = withExifOrientation(jpeg(textured(400, 200)), orientation = 6)
        val (p, out) = prepare(input)
        assertThat(p.width).isEqualTo(200)
        assertThat(p.height).isEqualTo(400)
        assertThat(WebpChunks.privacyChunks(out)).isEmpty()
    }

    @Test
    fun `exif orientation 6 rotates clockwise — top-left marker ends up top-right`() {
        val input = withExifOrientation(jpeg(cornerMarked(400, 200)), orientation = 6)
        val (p, out) = prepare(input)
        assertThat(p.width).isEqualTo(200)
        assertThat(p.height).isEqualTo(400)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(isWhite(decoded.getPixel(150, 50))).isTrue() // 新图右上角
        assertThat(isWhite(decoded.getPixel(50, 350))).isFalse() // 新图左下角不该是白的
    }

    @Test
    fun `exif orientation 8 rotates counter-clockwise — top-left marker ends up bottom-left`() {
        val input = withExifOrientation(jpeg(cornerMarked(400, 200)), orientation = 8)
        val (p, out) = prepare(input)
        assertThat(p.width).isEqualTo(200)
        assertThat(p.height).isEqualTo(400)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(isWhite(decoded.getPixel(50, 350))).isTrue() // 新图左下角
        assertThat(isWhite(decoded.getPixel(150, 50))).isFalse() // 新图右上角不该是白的——和方向 6 相反
    }

    @Test
    fun `exif orientation 2 mirrors horizontally without rotating`() {
        val input = withExifOrientation(jpeg(cornerMarked(400, 200)), orientation = 2)
        val (p, out) = prepare(input)
        assertThat(p.width).isEqualTo(400)
        assertThat(p.height).isEqualTo(200)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(isWhite(decoded.getPixel(300, 50))).isTrue() // 原左上角镜像到右上角
        assertThat(isWhite(decoded.getPixel(50, 50))).isFalse() // 原左上角不再是白的
    }

    @Test
    fun `quality steps down until the budget is met, and gives up below q45`() {
        val bmp = textured(800, 600)
        val atStart = ImagePreparer.compressUnderBudget(bmp, budget = Long.MAX_VALUE).size.toLong()
        val stepped = ImagePreparer.compressUnderBudget(bmp, budget = atStart - 1)
        assertThat(stepped.size.toLong()).isAtMost(atStart - 1)
        val e = assertThrows(MediaRejected::class.java) { ImagePreparer.compressUnderBudget(bmp, budget = 10) }
        assertThat(e.messageRes).isEqualTo(R.string.media_image_too_big)
    }

    @Test
    fun `not an image is rejected with a readable message`() {
        val e = assertThrows(MediaRejected::class.java) { prepare(ByteArray(64) { it.toByte() }) }
        assertThat(e.messageRes).isEqualTo(R.string.media_image_unreadable)
    }

    @Test
    fun `sample size halves until the long edge would drop below the target`() {
        assertThat(ImagePreparer.sampleSize(1920, 1920)).isEqualTo(1)
        assertThat(ImagePreparer.sampleSize(3000, 1920)).isEqualTo(1)
        assertThat(ImagePreparer.sampleSize(4000, 1920)).isEqualTo(2)
        assertThat(ImagePreparer.sampleSize(8000, 1920)).isEqualTo(4)
    }
}
