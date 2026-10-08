package app.chencang.android.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.profile.clampMyNameInput
import app.chencang.shared.profile.normalizeMyName
import app.chencang.shared.profile.rewrittenNameInput

object NamePromptTestTags {
    const val FIELD = "name-prompt-field"
    const val CONTINUE = "name-prompt-continue"
    const val SKIP = "name-prompt-skip"
}

/**
 * 「你的名字」一问：引导第二幕与首次配对共用。输入过 [clampMyNameInput]；写成空或被
 * [normalizeMyName] 拒绝（含换行等）时「继续」置灰，「跳过」永远可点。文字用 rememberSaveable，
 * 旋转/重建后不丢。
 */
@Composable
fun NamePrompt(onContinue: (String) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val normalized = normalizeMyName(clampMyNameInput(field.text))?.ifEmpty { null }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.name_prompt_title),
            fontSize = Moyu.FontSize.Title,
            fontWeight = FontWeight.SemiBold,
            color = moyuColors.textPrimary,
        )
        Spacer(Modifier.height(Moyu.Space.S))
        Text(
            stringResource(R.string.name_prompt_hint),
            fontSize = Moyu.FontSize.Callout,
            color = moyuColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Moyu.Space.Xl))
        OutlinedTextField(
            value = field,
            // 输入法组合中（拼音）原样保留；组合结束才截断，不切坏组合态。
            onValueChange = { v ->
                val rewritten = rewrittenNameInput(v.text, isComposing = v.composition != null)
                field = if (rewritten == null) v else TextFieldValue(rewritten, TextRange(rewritten.length))
            },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.me_name_limit)) },
            shape = RoundedCornerShape(Moyu.Radius.Input),
            modifier = Modifier.fillMaxWidth().testTag(NamePromptTestTags.FIELD),
        )
        Spacer(Modifier.height(Moyu.Space.L))
        Button(
            onClick = { normalized?.let(onContinue) },
            enabled = normalized != null,
            modifier = Modifier.fillMaxWidth().testTag(NamePromptTestTags.CONTINUE),
        ) { Text(stringResource(R.string.name_prompt_continue)) }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().testTag(NamePromptTestTags.SKIP),
        ) { Text(stringResource(R.string.name_prompt_skip)) }
    }
}
