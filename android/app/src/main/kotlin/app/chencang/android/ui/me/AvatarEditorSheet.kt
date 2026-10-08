package app.chencang.android.ui.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.R
import app.chencang.shared.profile.AvatarEditorModel

/**
 * 头像编辑面板：预览 → 「字」→ 「底色」八色块 + 「自动」→ 「完成」。选择即存：
 * 字变化（组合结束后）与选色都立刻回调 [onGlyphChange] / [onColorChange]。
 * 输入法组合中的文本原样留在输入框里，不规整、不落盘；「完成」/面板关闭时对尚未结束的组合文本规整落盘一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarEditorSheet(
    initialGlyph: String,
    initialColor: Int?,
    myName: String,
    myFingerprintHex: String?,
    onGlyphChange: (String) -> Unit,
    onColorChange: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultGlyph = stringResource(R.string.me_avatar_default_glyph)
    var model by remember {
        mutableStateOf(AvatarEditorModel(initialGlyph, initialColor, myName, myFingerprintHex, defaultGlyph))
    }
    var field by remember { mutableStateOf(TextFieldValue(initialGlyph)) }

    fun apply(text: String) {
        val next = model.inputGlyph(text, isComposing = false)
        if (next.storedGlyph != model.storedGlyph) onGlyphChange(next.storedGlyph.orEmpty())
        model = next
        val shown = next.storedGlyph.orEmpty()
        field = TextFieldValue(shown, TextRange(shown.length))
    }

    fun commitAndDismiss() {
        apply(field.text)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::commitAndDismiss) {
        AvatarEditorContent(
            model = model,
            glyphField = field,
            onGlyphField = { v ->
                if (v.composition != null) field = v else apply(v.text)
            },
            onPickColor = { i ->
                val next = model.pickColor(i)
                if (next.storedColor != model.storedColor) onColorChange(next.storedColor)
                model = next
            },
            onDone = ::commitAndDismiss,
        )
    }
}

/** 面板内容（无状态，便于测试）。可滚动：大字号 / 横屏下「完成」不会被挤出屏幕。 */
@Composable
internal fun AvatarEditorContent(
    model: AvatarEditorModel,
    glyphField: TextFieldValue,
    onGlyphField: (TextFieldValue) -> Unit,
    onPickColor: (Int?) -> Unit,
    onDone: () -> Unit,
) {
    val c = moyuColors
    val palette = listOf(c.avatar1, c.avatar2, c.avatar3, c.avatar4, c.avatar5, c.avatar6, c.avatar7, c.avatar8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Moyu.Space.L),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Moyu.Space.L),
    ) {
        Text(stringResource(R.string.me_avatar_title), color = c.textPrimary, fontSize = Moyu.FontSize.Title)
        MoyuAvatar(spec = model.preview, size = Moyu.Size.AvatarProfile)

        OutlinedTextField(
            value = glyphField,
            onValueChange = onGlyphField,
            singleLine = true,
            label = { Text(stringResource(R.string.me_avatar_glyph_label)) },
            placeholder = { Text(model.placeholderGlyph) },
            isError = model.errorRes != null,
            supportingText = { Text(stringResource(model.errorRes ?: R.string.me_avatar_glyph_hint)) },
            textStyle = LocalTextStyle.current.copy(fontSize = Moyu.FontSize.Body, textAlign = TextAlign.Center),
            shape = RoundedCornerShape(Moyu.Radius.Input),
            modifier = Modifier.width(Moyu.Size.GlyphField).testTag(MeTestTags.AVATAR_GLYPH),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(Moyu.Space.S),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            Text(stringResource(R.string.me_avatar_color), color = c.textSecondary, fontSize = Moyu.FontSize.Callout)
            // 4 + 4 两行：每格可点区域 TouchMin，视觉色块 AvatarSwatch。
            for (row in 0 until 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in row * 4 until row * 4 + 4) {
                        val swatchCd = stringResource(R.string.me_avatar_color_n_cd, i + 1)
                        val isSelected = model.storedColor == i
                        Box(
                            modifier = Modifier
                                .size(Moyu.Size.TouchMin)
                                .clickable { onPickColor(i) }
                                .clearAndSetSemantics {
                                    testTag = MeTestTags.AVATAR_COLOR_PREFIX + i
                                    contentDescription = swatchCd
                                    role = Role.RadioButton
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(Moyu.Size.AvatarSwatch)
                                    .clip(CircleShape)
                                    .background(palette[i])
                                    .selectionRing(isSelected),
                            )
                        }
                    }
                }
            }
            val autoSelected = model.storedColor == null
            val autoCd = stringResource(R.string.me_avatar_color_auto_cd)
            Box(
                modifier = Modifier
                    .heightIn(min = Moyu.Size.TouchMin)
                    .clip(RoundedCornerShape(Moyu.Radius.Input))
                    .selectionRing(autoSelected, RoundedCornerShape(Moyu.Radius.Input))
                    .clickable { onPickColor(null) }
                    .clearAndSetSemantics {
                        testTag = MeTestTags.AVATAR_COLOR_AUTO
                        contentDescription = autoCd
                        role = Role.RadioButton
                        selected = autoSelected
                    }
                    .padding(horizontal = Moyu.Space.L),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.me_avatar_auto), color = c.textPrimary, fontSize = Moyu.FontSize.Body) }
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier.heightIn(min = Moyu.Size.TouchMin).testTag(MeTestTags.AVATAR_DONE),
        ) { Text(stringResource(R.string.common_done)) }
    }
}

/** 选中项的 accentPrimary 描边环。 */
@Composable
private fun Modifier.selectionRing(selected: Boolean, shape: Shape = CircleShape): Modifier =
    if (selected) border(BorderStroke(Moyu.Size.RingSelected, moyuColors.accentPrimary), shape) else this
