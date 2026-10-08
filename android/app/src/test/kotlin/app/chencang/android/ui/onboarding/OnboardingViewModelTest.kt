package app.chencang.android.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts in NeedsIdentity when no identity exists`() = runTest {
        val vm = OnboardingViewModel(
            hasIdentity = { false },
            generateIdentity = { error("not invoked") },
            namePromptDone = { true },
            saveName = {},
            ioDispatcher = dispatcher,
        )

        vm.state.test {
            assertThat(awaitItem()).isEqualTo(OnboardingState.NeedsIdentity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `transitions to Done when identity already exists`() = runTest {
        val vm = OnboardingViewModel(
            hasIdentity = { true },
            generateIdentity = { error("not invoked") },
            namePromptDone = { true },
            saveName = {},
            ioDispatcher = dispatcher,
        )

        vm.state.test {
            // Initial value may be NeedsIdentity, then init coroutine drives to Done.
            val first = awaitItem()
            val final = if (first == OnboardingState.Done) first else awaitItem()
            assertThat(final).isEqualTo(OnboardingState.Done)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clicking generate transitions NeedsIdentity to Generating to Done`() = runTest {
        var calls = 0
        val vm = OnboardingViewModel(
            hasIdentity = { false },
            generateIdentity = { calls++ },
            namePromptDone = { true },
            saveName = {},
            ioDispatcher = dispatcher,
        )

        vm.onGenerateClicked()

        assertThat(calls).isEqualTo(1)
        vm.state.test {
            assertThat(awaitItem()).isEqualTo(OnboardingState.Done)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generation error transitions to Failed with message`() = runTest {
        val vm = OnboardingViewModel(
            hasIdentity = { false },
            generateIdentity = { throw RuntimeException("uniffi exploded") },
            namePromptDone = { true },
            saveName = {},
            ioDispatcher = dispatcher,
        )

        vm.onGenerateClicked()

        vm.state.test {
            val s = awaitItem()
            assertThat(s).isInstanceOf(OnboardingState.Failed::class.java)
            assertThat((s as OnboardingState.Failed).message).isEqualTo("uniffi exploded")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clicking generate while in flight is ignored`() = runTest {
        // Use a dispatcher we control so the generate suspend doesn't complete instantly.
        val gateDispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val vm = OnboardingViewModel(
            hasIdentity = { false },
            generateIdentity = {
                calls++
                kotlinx.coroutines.delay(1_000)
            },
            namePromptDone = { true },
            saveName = {},
            ioDispatcher = gateDispatcher,
        )

        vm.onGenerateClicked()
        vm.onGenerateClicked() // second click while in Generating
        vm.onGenerateClicked()

        testScheduler.advanceUntilIdle()
        assertThat(calls).isEqualTo(1)
    }

    private class Names(var done: Boolean = false) {
        val saved = mutableListOf<String?>()
        fun save(n: String?) { saved += n; done = true }
    }

    private fun vmWith(names: Names, hasIdentity: Boolean = false, saved: SavedStateHandle = SavedStateHandle()) =
        OnboardingViewModel(
            hasIdentity = { hasIdentity },
            generateIdentity = {},
            namePromptDone = { names.done },
            saveName = names::save,
            savedState = saved,
            ioDispatcher = dispatcher,
        )

    @Test
    fun `acts go Brand then Name after identity then Mechanism`() = runTest {
        val names = Names()
        val vm = vmWith(names)
        assertThat(vm.act.value).isEqualTo(OnboardingAct.Brand)
        vm.onGenerateClicked()
        assertThat(vm.act.value).isEqualTo(OnboardingAct.Name)
        vm.onNameSkipped()
        assertThat(vm.act.value).isEqualTo(OnboardingAct.Mechanism)
        assertThat(names.saved).containsExactly(null as String?)
        assertThat(names.done).isTrue()
    }

    @Test
    fun `continuing saves the normalised name`() = runTest {
        val names = Names()
        val vm = vmWith(names)
        vm.onGenerateClicked()
        vm.onNameEntered("  小明 ")
        assertThat(names.saved).containsExactly("小明")
        assertThat(vm.act.value).isEqualTo(OnboardingAct.Mechanism)
    }

    @Test
    fun `a rebuilt view model resumes on the Name act from saved state`() = runTest {
        val names = Names()
        val saved = SavedStateHandle()
        vmWith(names, saved = saved).onGenerateClicked()
        val rebuilt = vmWith(names, hasIdentity = true, saved = saved)
        assertThat(rebuilt.act.value).isEqualTo(OnboardingAct.Name)
    }

    @Test
    fun `an existing identity with the prompt already done goes straight to Mechanism`() = runTest {
        val vm = vmWith(Names(done = true), hasIdentity = true)
        assertThat(vm.act.value).isEqualTo(OnboardingAct.Mechanism)
    }
}
