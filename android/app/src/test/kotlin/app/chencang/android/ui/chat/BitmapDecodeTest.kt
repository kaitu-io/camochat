package app.chencang.android.ui.chat

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class BitmapDecodeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(format: Bitmap.CompressFormat, name: String): File {
        val f = File(tmp.root, name)
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(format, 80, it) }
        return f
    }

    @Test
    fun `decodes jpeg and webp by content regardless of file name`() {
        val jpg = write(Bitmap.CompressFormat.JPEG, "a.bin")
        val webp = write(Bitmap.CompressFormat.WEBP_LOSSY, "b.jpg")
        assertThat(BitmapDecode.sampled(jpg.path, 1000)!!.width).isEqualTo(400)
        assertThat(BitmapDecode.sampled(webp.path, 1000)!!.width).isEqualTo(400)
    }

    @Test
    fun `downsamples large images toward the target edge`() {
        val jpg = write(Bitmap.CompressFormat.JPEG, "c.jpg")
        assertThat(BitmapDecode.sampled(jpg.path, 100)!!.width).isEqualTo(100)
    }

    @Test
    fun `unreadable file yields null`() {
        val f = File(tmp.root, "junk").apply { writeBytes(ByteArray(32) { 7 }) }
        assertThat(BitmapDecode.sampled(f.path, 100)).isNull()
    }

    @Test
    fun `sample size halves while the long edge stays at or above the target`() {
        assertThat(BitmapDecode.sampleSize(1000, 1000)).isEqualTo(1)
        assertThat(BitmapDecode.sampleSize(4000, 1000)).isEqualTo(4)
    }
}
