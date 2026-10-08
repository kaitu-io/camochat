package app.chencang.android.share

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import app.chencang.android.receive.ProcessTextActivity
import app.chencang.android.ui.chat.ConversationViewModel
import app.chencang.android.ui.chat.launchShareSheet
import app.chencang.android.ui.pairing.launchPairingShare
import app.chencang.shared.pairing.inband.PairingTransport
import kotlinx.coroutines.runBlocking
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Both share sheets leave the app's own 「陈仓解密」 SEND target out (it would just decrypt it back). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareChooserTest {
    // An Activity context, as in production (the composables launch from their Activity).
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val self = ComponentName(activity as Context, ProcessTextActivity::class.java)

    @org.junit.Before
    fun clearFileProviderCache() {
        // FileProvider memoizes its path roots statically; Robolectric gives each test a new data dir.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    @Suppress("DEPRECATION")
    private fun excluded(chooser: Intent): List<ComponentName> {
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        return chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
            ?.map { it as ComponentName }
            .orEmpty()
    }

    @Test
    fun `conversation share sheet excludes the app's own share target`() {
        launchShareSheet(activity, ConversationViewModel.ShareRequest(messageId = "m1", peer = "p1", text = "🔒wire"))
        val chooser = shadowOf(activity).nextStartedActivity
        assertThat(excluded(chooser)).containsExactly(self)
    }

    @Test
    fun `pairing share sheet excludes the app's own share target`() {
        val wire = PairingTransport.bundleToWire(byteArrayOf(1, 2, 3))
        runBlocking {
            launchPairingShare(activity, "https://site.test/", wire, false, "小明", PairingShareReceiver.KIND_INVITE, "pairing-1")
        }
        val chooser = shadowOf(activity).nextStartedActivity
        assertThat(excluded(chooser)).containsExactly(self)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.type).isEqualTo("image/png")
    }
}
