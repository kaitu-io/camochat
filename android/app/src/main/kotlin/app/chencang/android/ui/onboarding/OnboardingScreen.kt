package app.chencang.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.ui.me.NamePrompt
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R

object OnboardingTestTags {
    const val CREATE_BUTTON = "onboarding-create-button"
    const val GENERATING_SPINNER = "onboarding-generating-spinner"
    const val ERROR_TEXT = "onboarding-error"
    const val MECHANISM_DONE_BUTTON = "onboarding-mechanism-done"
}

/**
 * 引导三幕:
 *  - 幕 1 品牌 [BrandAct] — 即时建身份,无表单;Generating/Failed 态都在这一幕内渲染。
 *  - 幕 2 问名字 [NamePrompt] — 继续(存名字)或跳过,都记为问过。
 *  - 幕 3 机制 [MechanismAct] — 发消息/复制/粘贴三行图示,「我明白了」才调 [onDone]。
 *
 * 幕由 [OnboardingViewModel.act] 决定并存进 SavedStateHandle,旋转/重建后留在原幕。
 * 真正离开 onboarding 由 [onDone] 这个显式事件回调驱动(CcNavGraph 里只有幕 3 的
 * 「我明白了」会调用它);MainActivity 只在 `onCreate` 读一次 `hasIdentity()` 定起点,
 * 不会因身份建好而反应式跳过后面的幕。
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val act by viewModel.act.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = act,
        transitionSpec = {
            fadeIn(tween(Moyu.Motion.Standard, delayMillis = Moyu.Motion.Quick / 2)) togetherWith
                fadeOut(tween(Moyu.Motion.Quick))
        },
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        label = "onboarding-act",
    ) { targetAct ->
        when (targetAct) {
            OnboardingAct.Brand -> BrandAct(state = state, onGenerateClicked = viewModel::onGenerateClicked)
            OnboardingAct.Name -> NamePrompt(
                onContinue = viewModel::onNameEntered,
                onSkip = viewModel::onNameSkipped,
                modifier = Modifier.fillMaxSize().padding(Moyu.Space.Xl),
            )
            OnboardingAct.Mechanism -> MechanismAct(onDone = onDone)
        }
    }
}

@Composable
private fun BrandAct(state: OnboardingState, onGenerateClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Moyu.Space.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Verified,
            contentDescription = null,
            tint = moyuColors.accentPrimary,
            // Moyu has no icon-glyph-size token (Space/FontSize don't cover
            // this); 72.dp literal per the plan brief, same class of literal
            // as SealBreathIcon(64.dp) in PairingWizardScreen's WorkingAct.
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(Moyu.Space.Xl))
        Text(
            stringResource(R.string.app_name),
            fontSize = Moyu.FontSize.Display,
            fontWeight = FontWeight.SemiBold,
            color = moyuColors.textPrimary,
        )
        Spacer(Modifier.height(Moyu.Space.S))
        Text(
            stringResource(R.string.onboarding_tagline),
            fontSize = Moyu.FontSize.Callout,
            color = moyuColors.textSecondary,
        )
        Spacer(Modifier.height(Moyu.Space.Xxl))

        if (state is OnboardingState.Failed) {
            Text(
                text = stringResource(R.string.onboarding_error, state.message),
                modifier = Modifier.testTag(OnboardingTestTags.ERROR_TEXT),
                color = moyuColors.statusDanger,
                fontSize = Moyu.FontSize.Caption,
            )
            Spacer(Modifier.height(Moyu.Space.L))
        }

        if (state == OnboardingState.Generating) {
            CircularProgressIndicator(modifier = Modifier.testTag(OnboardingTestTags.GENERATING_SPINNER))
            Spacer(Modifier.height(Moyu.Space.M))
            Text(stringResource(R.string.onboarding_generating), fontSize = Moyu.FontSize.Callout, color = moyuColors.textSecondary)
        } else {
            Button(
                modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.CREATE_BUTTON),
                onClick = onGenerateClicked,
            ) {
                Text(stringResource(if (state is OnboardingState.Failed) R.string.common_retry else R.string.onboarding_create_keys))
            }
        }
    }
}

@Composable
private fun MechanismAct(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Moyu.Space.Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.onboarding_how_title),
            fontSize = Moyu.FontSize.Title,
            fontWeight = FontWeight.SemiBold,
            color = moyuColors.textPrimary,
        )
        Spacer(Modifier.height(Moyu.Space.Xxl))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Moyu.Space.L),
        ) {
            MechanismStep(Icons.AutoMirrored.Filled.Send, stringResource(R.string.onboarding_step_encrypt))
            MechanismStep(Icons.Default.ContentCopy, stringResource(R.string.onboarding_step_send))
            MechanismStep(Icons.Default.ContentPaste, stringResource(R.string.onboarding_step_decrypt))
            Text(
                stringResource(R.string.onboarding_network_note),
                fontSize = Moyu.FontSize.Callout,
                color = moyuColors.textSecondary,
            )
        }

        Spacer(Modifier.height(Moyu.Space.Xxl))
        Button(
            modifier = Modifier.fillMaxWidth().testTag(OnboardingTestTags.MECHANISM_DONE_BUTTON),
            onClick = onDone,
        ) { Text(stringResource(R.string.onboarding_got_it)) }
    }
}

@Composable
private fun MechanismStep(icon: ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = moyuColors.accentPrimary,
            modifier = Modifier.size(Moyu.Space.Xl),
        )
        Spacer(Modifier.width(Moyu.Space.M))
        Text(text, fontSize = Moyu.FontSize.Body, color = moyuColors.textPrimary)
    }
}
