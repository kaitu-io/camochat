package app.chencang.android.ui.pairing

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QrImageDecoderTest {
    @Test
    fun `decodes a generated pairing link qr`() {
        val link = "https://x.example/p/#" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Random(7).let { r -> ByteArray(200).also { r.nextBytes(it) } })
        assertThat(QrImageDecoder.decode(qrBitmapForTest(link))).isEqualTo(link)
    }

    @Test
    fun `a blank bitmap has no code`() {
        val blank = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.WHITE)
        assertThat(QrImageDecoder.decode(blank)).isNull()
    }
}
