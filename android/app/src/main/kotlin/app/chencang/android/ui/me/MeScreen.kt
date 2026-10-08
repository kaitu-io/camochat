package app.chencang.android.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.chencang.android.ui.components.MoyuAvatar
import app.chencang.design.Moyu
import app.chencang.design.moyuColors
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.R
import app.chencang.shared.profile.AvatarSpec
import app.chencang.shared.profile.clampMyNameInput
import app.chencang.shared.profile.myAvatar
import app.chencang.shared.profile.normalizeMyName
import app.chencang.shared.profile.rewrittenNameInput

/**
 * 「我」tab：资料卡槽 + 设置各分组（[app.chencang.android.ui.settings.SettingsSections]）。
 * 资料卡由 [MyProfileSection] 填入。
 */
@Composable
fun MeContent(
    contentPadding: PaddingValues,
    profile: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Moyu.Space.L, vertical = Moyu.Space.L),
        verticalArrangement = Arrangement.spacedBy(Moyu.Space.L),
    ) {
        profile()
        settings()
    }
}

object MeTestTags {
    const val PROFILE = "me-profile"
    const val AVATAR = "me-avatar"
    const val NAME = "me-name"
    const val NAME_FIELD = "me-name-field"
    const val NAME_SAVE = "me-name-save"
    const val AVATAR_GLYPH = "me-avatar-glyph"
    const val AVATAR_COLOR_PREFIX = "me-avatar-color-"
    const val AVATAR_COLOR_AUTO = "me-avatar-color-auto"
    const val AVATAR_DONE = "me-avatar-done"
}

/**
 * 资料卡：左头像、右昵称 + 铅笔，下一行「目前只有你自己看得到」。头像与昵称是两个独立点击目标。
 * [loaded] 为 false（存储还没读出）时不画「未设置昵称」，免得闪一下。
 */
@Composable
fun ProfileCard(
    loaded: Boolean,
    name: String,
    avatar: AvatarSpec,
    onEditName: () -> Unit,
    onEditAvatar: () -> Unit,
) {
    val c = moyuColors
    val avatarCd = stringResource(R.string.me_avatar_cd)
    val nameCd = if (loaded) {
        stringResource(R.string.me_name_cd, name.ifEmpty { stringResource(R.string.me_name_unset_short) })
    } else {
        stringResource(R.string.me_name_cd_loading)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Moyu.Radius.Card))
            .background(c.surfaceRaised)
            .padding(Moyu.Space.L)
            .testTag(MeTestTags.PROFILE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Moyu.Space.L),
    ) {
        Box(
            modifier = Modifier
                .clickable(enabled = loaded, onClick = onEditAvatar)
                .clearAndSetSemantics {
                    testTag = MeTestTags.AVATAR
                    contentDescription = avatarCd
                    role = Role.Button
                },
        ) {
            if (loaded) MoyuAvatar(spec = avatar, size = Moyu.Size.AvatarProfile)
            else Spacer(Modifier.size(Moyu.Size.AvatarProfile))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = loaded, onClick = onEditName)
                .clearAndSetSemantics {
                    testTag = MeTestTags.NAME
                    contentDescription = nameCd
                    role = Role.Button
                },
            verticalArrangement = Arrangement.spacedBy(Moyu.Space.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Moyu.Space.S)) {
                if (loaded) {
                    if (name.isEmpty()) {
                        Text(stringResource(R.string.me_name_unset), color = c.textTertiary, fontSize = Moyu.FontSize.Title)
                    } else {
                        Text(name, color = c.textPrimary, fontSize = Moyu.FontSize.Title)
                    }
                }
                Icon(Icons.Default.Edit, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(Moyu.Size.TabIcon))
            }
            Text(stringResource(R.string.me_name_subtitle), color = c.textSecondary, fontSize = Moyu.FontSize.Callout)
        }
    }
}

/** 改名弹窗：输入过 [clampMyNameInput]；[normalizeMyName] 拒绝（含换行等）时「保存」置灰，不写存储。允许保存为空。 */
@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    // 保存时再截一次：组合中的文本可能暂时超限。
    val normalized = normalizeMyName(clampMyNameInput(field.text))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.me_name_title)) },
        text = {
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
                modifier = Modifier.fillMaxWidth().testTag(MeTestTags.NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (normalized != null) onSave(normalized) },
                enabled = normalized != null,
                modifier = Modifier.testTag(MeTestTags.NAME_SAVE),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 「我」tab 的资料卡接线：读 [app.chencang.shared.AppPrefs] 四个流，改名弹窗与头像编辑面板。 */
@Composable
fun MyProfileSection(locator: CcServiceLocator) {
    val prefs = locator.appPrefs
    val loaded by prefs.profileLoaded.collectAsStateWithLifecycle()
    val name by prefs.myName.collectAsStateWithLifecycle()
    val glyph by prefs.myAvatarGlyph.collectAsStateWithLifecycle()
    val color by prefs.myAvatarColor.collectAsStateWithLifecycle()
    // 指纹读完前不画头像，免得「自动」底色从固定种子色跳到指纹色。rememberSaveable：
    // 切 tab 重建本组件时不重读身份、不闪空头像（指纹是公开摘要，可安全存进保存态）。
    var fpLoaded by rememberSaveable { mutableStateOf(false) }
    var fpHex by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!fpLoaded) {
            fpHex = locator.myFingerprintHex()
            fpLoaded = true
        }
    }
    val defaultGlyph = stringResource(R.string.me_avatar_default_glyph)
    var editingName by remember { mutableStateOf(false) }
    var editingAvatar by remember { mutableStateOf(false) }

    ProfileCard(
        loaded = loaded && fpLoaded,
        name = name,
        avatar = myAvatar(glyph, color, name, fpHex, defaultGlyph),
        onEditName = { editingName = true },
        onEditAvatar = { editingAvatar = true },
    )
    if (editingName) {
        NameDialog(
            initial = name,
            onSave = { prefs.setMyName(it); editingName = false },
            onDismiss = { editingName = false },
        )
    }
    if (editingAvatar) {
        AvatarEditorSheet(
            initialGlyph = glyph,
            initialColor = color,
            myName = name,
            myFingerprintHex = fpHex,
            onGlyphChange = prefs::setMyAvatarGlyph,
            onColorChange = prefs::setMyAvatarColor,
            onDismiss = { editingAvatar = false },
        )
    }
}
