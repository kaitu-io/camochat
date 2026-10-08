package app.chencang.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.chencang.shared.CcServiceLocator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Assembling the locator (what [CcApp.onCreate] does on every process start) must not mint an
 * identity: "has an identity" is how the app tells a new user (onboarding) from a returning one.
 * [CcApp.onCreate] has already installed the upload engine; the locator is rebuilt here against
 * an identity-less disk.
 */
@RunWith(AndroidJUnit4::class)
class LocatorBootTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val identityFile get() = File(ctx.filesDir, "identity.enc")

    @Before
    fun setUp() {
        CcServiceLocator.reset()
        identityFile.delete()
    }

    @After
    fun tearDown() {
        CcServiceLocator.reset()
        identityFile.delete()
    }

    @Test
    fun from_onFreshInstall_leavesNoIdentity() = runBlocking {
        val locator = CcServiceLocator.from(ctx)
        // Let every boot job the locator started run to completion.
        locator.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }

        assertThat(locator.identityStore.hasIdentity()).isFalse()
        assertThat(identityFile.exists()).isFalse()
    }
}
