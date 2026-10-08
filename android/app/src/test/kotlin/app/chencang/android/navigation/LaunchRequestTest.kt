package app.chencang.android.navigation

import android.app.Application
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LaunchRequestTest {

    @Test fun `extras round trip and are consumed`() {
        val requests = listOf(
            LaunchRequest.OpenThread("alice:bob"),
            LaunchRequest.OpenWizard("🔒w:x\ny"),
            LaunchRequest.OpenWizard(null),
            LaunchRequest.AddContact,
            LaunchRequest.None,
        )
        requests.forEach { request ->
            val intent = Intent()
            request.toExtras(intent)
            assertThat(launchRequestFrom(intent)).isEqualTo(request)
            assertThat(launchRequestFrom(intent)).isEqualTo(LaunchRequest.None)
        }
    }

    @Test fun `plain intent is no request`() {
        assertThat(launchRequestFrom(Intent())).isEqualTo(LaunchRequest.None)
    }

    @Test fun `start destination follows identity`() {
        assertThat(startDestination(hasIdentity = true)).isEqualTo(Routes.MAIN)
    }

    @Test fun `launch without identity runs onboarding then opens the wizard with the wire`() {
        assertThat(startDestination(hasIdentity = false)).isEqualTo(Routes.ONBOARDING)
        assertThat(afterOnboarding(LaunchRequest.OpenWizard("🔒w")))
            .containsExactly(NavStep.ToMain, NavStep.ToWizard("redeemer", "🔒w"))
            .inOrder()
    }

    @Test fun `plan per request`() {
        assertThat(plan(LaunchRequest.OpenThread("alice"))).containsExactly(NavStep.ToThread("alice"))
        assertThat(plan(LaunchRequest.OpenWizard(null))).containsExactly(NavStep.ToWizard("redeemer", null))
        assertThat(plan(LaunchRequest.AddContact)).containsExactly(NavStep.ToWizard("initiator", null))
        assertThat(plan(LaunchRequest.None)).isEmpty()
        assertThat(afterOnboarding(LaunchRequest.None)).containsExactly(NavStep.ToMain)
    }

    @Test fun `saveable encoding round trips`() {
        listOf(
            LaunchRequest.OpenThread("a:b"),
            LaunchRequest.OpenWizard("🔒w:1"),
            LaunchRequest.OpenWizard(null),
            LaunchRequest.AddContact,
            LaunchRequest.None,
        ).forEach { assertThat(LaunchRequest.decode(it.encode())).isEqualTo(it) }
    }
}
