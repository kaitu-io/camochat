package app.chencang.android.share

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipFile
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApkShareTest {
    private lateinit var context: Context
    private lateinit var apk: File
    private val bytes = ByteArray(4096) { (it * 7).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        apk = File.createTempFile("base", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        context.applicationInfo.sourceDir = apk.absolutePath
        context.applicationInfo.splitSourceDirs = null
        File(context.cacheDir, "share").deleteRecursively()
        // FileProvider memoizes its path roots statically; Robolectric gives each test a new data dir.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    private fun versionName() = context.packageManager.getPackageInfo(context.packageName, 0).versionName

    @Test
    fun prepareCopiesBaseApk() {
        val uri = ApkShare.prepare(context, asZip = false)
        assertThat(uri).isNotNull()
        val out = File(context.cacheDir, "share/chencang-${versionName()}.apk")
        assertThat(out.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun prepareZipContainsApk() {
        assertThat(ApkShare.prepare(context, asZip = true)).isNotNull()
        val zip = File(context.cacheDir, "share/chencang-${versionName()}.zip")
        ZipFile(zip).use { z ->
            val e = z.getEntry("chencang-${versionName()}.apk")
            assertThat(e).isNotNull()
            assertThat(z.getInputStream(e).readBytes()).isEqualTo(bytes)
        }
    }

    @Test
    fun prepareReturnsNullWithSplits() {
        context.applicationInfo.splitSourceDirs = arrayOf("/x/split.apk")
        assertThat(ApkShare.prepare(context, asZip = false)).isNull()
    }

    @Test
    fun prepareClearsOldShares() {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val stale = File(dir, "old.apk").apply { writeText("x") }
        ApkShare.prepare(context, asZip = false)
        assertThat(stale.exists()).isFalse()
    }

    @Test
    fun shareIntentCarriesMimeAndGrant() {
        val uri = ApkShare.prepare(context, asZip = true)!!
        val i = ApkShare.shareIntent(context, uri, asZip = true)
        assertThat(i.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(i.type).isEqualTo("application/zip")
        assertThat(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(ApkShare.shareIntent(context, uri, false).type).isEqualTo("application/vnd.android.package-archive")
    }
}
