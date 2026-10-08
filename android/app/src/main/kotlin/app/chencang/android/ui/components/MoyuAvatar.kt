package app.chencang.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.chencang.design.moyuColors
import app.chencang.shared.profile.AvatarSpec

/**
 * 印章头像：圆 + 色板底色 + 一个字符。字符与色板下标由调用方算好传入
 * （`contactAvatar` / `myAvatar`），联系人头像与「我」的头像同一个组件。
 */
@Composable
fun MoyuAvatar(spec: AvatarSpec, size: Dp) {
    val c = moyuColors
    // 下标 0–7 ↔ avatar-1…avatar-8：跨端约定，只追加、不重排。
    val palette = listOf(
        c.avatar1, c.avatar2, c.avatar3, c.avatar4,
        c.avatar5, c.avatar6, c.avatar7, c.avatar8,
    )
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(palette[Math.floorMod(spec.paletteIndex, palette.size)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = spec.glyph,
            color = c.avatarGlyph,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}
