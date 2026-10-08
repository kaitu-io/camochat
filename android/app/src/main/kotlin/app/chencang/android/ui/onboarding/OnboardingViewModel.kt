package app.chencang.android.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.chencang.shared.profile.clampMyNameInput
import app.chencang.shared.profile.normalizeMyName

/**
 * Generic ViewModel that owns the [OnboardingState] StateFlow and delegates identity
 * generation through a `suspend () -> Unit` lambda. Production wiring (see CcApp) supplies
 * a lambda that calls `IdentityStore.generateAndSave()`; unit tests supply fakes.
 *
 * This avoids any uniffi imports in the ViewModel — keeps it JVM-testable.
 */
class OnboardingViewModel(
    private val hasIdentity: suspend () -> Boolean,
    private val generateIdentity: suspend () -> Unit,
    private val namePromptDone: () -> Boolean,
    private val saveName: (String?) -> Unit,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.NeedsIdentity)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _act = MutableStateFlow(
        savedState.get<String>(SAVED_ACT)?.let { runCatching { OnboardingAct.valueOf(it) }.getOrNull() } ?: OnboardingAct.Brand,
    )

    /** Which act is on screen; survives recreation via [savedState]. */
    val act: StateFlow<OnboardingAct> = _act.asStateFlow()

    init {
        if (_act.value != OnboardingAct.Brand) _state.value = OnboardingState.Done
        viewModelScope.launch {
            if (withContext(ioDispatcher) { hasIdentity() }) {
                _state.value = OnboardingState.Done
                if (_act.value == OnboardingAct.Brand) goAfterIdentity()
            }
        }
    }

    private fun setAct(act: OnboardingAct) {
        _act.value = act
        savedState[SAVED_ACT] = act.name
    }

    /** Identity exists: ask for a name unless that was already answered. */
    private fun goAfterIdentity() = setAct(if (namePromptDone()) OnboardingAct.Mechanism else OnboardingAct.Name)

    /** 「继续」: [raw] is normalised; one that normalises to nothing counts as skip. */
    fun onNameEntered(raw: String) = answerName(normalizeMyName(clampMyNameInput(raw))?.ifEmpty { null })

    fun onNameSkipped() = answerName(null)

    private fun answerName(name: String?) {
        if (_act.value != OnboardingAct.Name) return
        saveName(name)
        setAct(OnboardingAct.Mechanism)
    }

    fun onGenerateClicked() {
        if (_state.value is OnboardingState.Generating) return
        _state.value = OnboardingState.Generating
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { generateIdentity() }
                _state.value = OnboardingState.Done
                goAfterIdentity()
            } catch (t: Throwable) {
                _state.value = OnboardingState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val SAVED_ACT = "onboarding_act"
    }
}
