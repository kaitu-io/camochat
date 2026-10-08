package app.chencang.android.share

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingShareReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val marks = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val originalDeps = PairingShareReceiver.deps
    private val collector = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        PairingShareReceiver.deps = PairingShareReceiver.Deps {
            PairingShareReceiver.Handle(
                scope = scope,
                markInvite = { marks += "invite:$it" },
                markResponse = { marks += "response:$it" },
            )
        }
        collector.launch { PairingShareEvents.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        PairingShareReceiver.deps = originalDeps
        scope.cancel()
        collector.cancel()
    }

    private fun callbackIntent(kind: String?, id: String?, chosen: Boolean = true) =
        Intent(context, PairingShareReceiver::class.java).setAction(PairingShareReceiver.ACTION).apply {
            kind?.let { putExtra(PairingShareReceiver.EXTRA_KIND, it) }
            id?.let { putExtra(PairingShareReceiver.EXTRA_ID, it) }
            if (chosen) putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.example.chat", "com.example.chat.Share"))
        }

    private fun drain() = runBlocking { job.children.toList().forEach { it.join() } }

    @Test
    fun `a chosen-target callback marks by kind and emits the id`() {
        val r = PairingShareReceiver()
        r.onReceive(context, callbackIntent("invite", "pairing-1"))
        r.onReceive(context, callbackIntent("response", "fp-1"))
        drain()
        assertThat(marks).containsExactly("invite:pairing-1", "response:fp-1").inOrder()
        assertThat(events).containsExactly("pairing-1", "fp-1").inOrder()
    }

    @Test
    fun `without a chosen component, an unknown kind or a missing id nothing happens`() {
        val r = PairingShareReceiver()
        r.onReceive(context, callbackIntent("invite", "pairing-1", chosen = false))
        r.onReceive(context, callbackIntent("other", "pairing-1"))
        r.onReceive(context, callbackIntent("invite", null))
        r.onReceive(context, callbackIntent(null, "pairing-1"))
        drain()
        assertThat(marks).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun `a failed mark emits nothing`() {
        PairingShareReceiver.deps = PairingShareReceiver.Deps {
            PairingShareReceiver.Handle(scope, markInvite = { error("disk") }, markResponse = {})
        }
        PairingShareReceiver().onReceive(context, callbackIntent("invite", "pairing-1"))
        drain()
        assertThat(events).isEmpty()
    }

    @Test
    fun `the callback PendingIntent is explicit, mutable, per id and carries only kind and id`() {
        val a = PairingShareReceiver.pendingIntent(context, "invite", "pairing-1")
        val b = PairingShareReceiver.pendingIntent(context, "response", "fp-1")
        val sa = shadowOf(a)
        assertThat(sa.isBroadcast).isTrue()
        assertThat(sa.flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
        assertThat(sa.flags and PendingIntent.FLAG_UPDATE_CURRENT).isNotEqualTo(0)
        val saved = sa.savedIntent
        assertThat(saved.component).isEqualTo(ComponentName(context, PairingShareReceiver::class.java))
        assertThat(saved.extras!!.keySet()).containsExactly(PairingShareReceiver.EXTRA_KIND, PairingShareReceiver.EXTRA_ID)
        assertThat(saved.identifier).isEqualTo("pairing-1")
        assertThat(a).isNotEqualTo(b)
    }
}
