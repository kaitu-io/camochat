package app.chencang.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

val LocalMoyuColors = staticCompositionLocalOf { MoyuLight }

/** 当前主题的墨玉语义色。UI 取色的唯一入口(禁止直接引用 MoyuLight/MoyuDark)。 */
val moyuColors: MoyuColorScheme
    @Composable @ReadOnlyComposable get() = LocalMoyuColors.current

/**
 * 墨玉字号阶梯(spec §3.2):display 28 semibold / title 20 semibold /
 * body 16 regular / callout 14 regular / caption 12 regular,行高 1.4。
 * 字体族不设(系统 Roboto/MiSans 自动)。其余 Typography 槽位保持 M3 默认值,
 * 避免未核对槽位漂移。
 */
private fun moyuTypography(): Typography {
    fun style(size: TextUnit, weight: FontWeight) = TextStyle(
        fontSize = size,
        fontWeight = weight,
        lineHeight = size * 1.4,
    )
    return Typography(
        displaySmall = style(Moyu.FontSize.Display, FontWeight.SemiBold), // onboarding/首页大标题
        titleLarge = style(Moyu.FontSize.Title, FontWeight.SemiBold), // 屏幕标题(TopAppBar 默认取)
        bodyLarge = style(Moyu.FontSize.Body, FontWeight.Normal), // 正文/消息(ListItem headline 默认取)
        bodyMedium = style(Moyu.FontSize.Callout, FontWeight.Normal), // 列表副文(ListItem supporting 默认取)
        labelLarge = style(Moyu.FontSize.Callout, FontWeight.Medium), // 按钮(Button 默认取)
        bodySmall = style(Moyu.FontSize.Caption, FontWeight.Normal), // 时间戳/提示
        labelSmall = style(Moyu.FontSize.Caption, FontWeight.Normal),
    )
}

/**
 * 墨玉主题:提供 [LocalMoyuColors],并把 Material3 colorScheme 映射到玉青,
 * 让 Button/TextField/Switch 等 Material 组件跟随品牌色(告别默认紫)。
 * Dynamic Color 有意关闭——伪装一致性优先(spec §7)。
 */
@Composable
fun MoyuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) MoyuDark else MoyuLight
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.accentOnPrimary,
            secondary = colors.accentPrimary,
            onSecondary = colors.accentOnPrimary,
            background = colors.surfaceBase,
            onBackground = colors.textPrimary,
            surface = colors.surfaceBase,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderHairline,
            error = colors.statusDanger,
        )
    } else {
        lightColorScheme(
            primary = colors.accentPrimary,
            onPrimary = colors.accentOnPrimary,
            secondary = colors.accentPrimary,
            onSecondary = colors.accentOnPrimary,
            background = colors.surfaceBase,
            onBackground = colors.textPrimary,
            surface = colors.surfaceBase,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderHairline,
            error = colors.statusDanger,
        )
    }
    CompositionLocalProvider(LocalMoyuColors provides colors) {
        MaterialTheme(colorScheme = material, typography = moyuTypography(), content = content)
    }
}
