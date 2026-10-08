package app.chencang.android.ui.pairing

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM/Robolectric codec round-trip for [PairingQr]. Robolectric supplies a real
 * `android.graphics.Bitmap` so the encode path (BitMatrix → ARGB_8888) can be
 * read back pixel-by-pixel and decoded, proving the `🔒`-prefixed non-ASCII wire
 * survives QR's UTF-8 byte mode losslessly. The live camera scan is device-only.
 */
@RunWith(RobolectricTestRunner::class)
// Stock Application — the codec needs no DI; skips CcApp's eager keystore-touching init.
@Config(application = Application::class)
class PairingQrTest {

    @Test
    fun encode_then_decode_returns_exact_wire() {
        // 🔒 (U+1F512) + a Base32768-range CJK body at a realistic ~280B length.
        val wire = "🔒" + "丂丄丅丆丏".repeat(20)
        val bmp = qrBitmapForTest(wire)
        val decoded = decodeQrForTest(bmp)
        assertThat(decoded).isEqualTo(wire)
    }

    private fun decodeQrForTest(bmp: Bitmap): String {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return MultiFormatReader()
            .decode(binary, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
            .text
    }
}
