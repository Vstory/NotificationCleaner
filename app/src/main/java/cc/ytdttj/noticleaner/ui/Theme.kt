package cc.ytdttj.noticleaner.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.ytdttj.noticleaner.ServiceLocator

// 外观层（1.5.1 起为 Material 3 Expressive）：
// - MaterialExpressiveTheme + MotionScheme.expressive()，圆角回归 M3 标准尺度 4/8/12/16/28
// - 主题色：动态取色（Material You）为默认，另附固定色板预设
// - 液态玻璃皮肤已废弃：其开关已从设置页移除，AppTheme 不再下发玻璃模式

/** 深浅色模式 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun from(name: String?): DarkMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 主题色。[DYNAMIC] 取系统壁纸（Android 12+），其余为固定色板 */
enum class ThemeColor(val label: String) {
    DYNAMIC("动态取色"),
    BLUE("蓝"),
    PURPLE("紫"),
    GREEN("绿"),
    ORANGE("橙"),
    ;

    companion object {
        fun from(name: String?): ThemeColor = entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

// M3 标准圆角尺度。此前 8/12/16/24/32 偏大，卡片显软塌
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 固定色板只覆盖 primary/secondary/tertiary 三组强调色，
 * surface 等中性角色保持 M3 基线（与强调色无关，覆盖反而破坏中性层次）。
 */
private fun lightScheme(
    primary: Long,
    onPrimary: Long,
    primaryContainer: Long,
    onPrimaryContainer: Long,
    secondaryContainer: Long,
    onSecondaryContainer: Long,
    tertiaryContainer: Long,
    onTertiaryContainer: Long,
): ColorScheme = lightColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
)

private fun darkScheme(
    primary: Long,
    onPrimary: Long,
    primaryContainer: Long,
    onPrimaryContainer: Long,
    secondaryContainer: Long,
    onSecondaryContainer: Long,
    tertiaryContainer: Long,
    onTertiaryContainer: Long,
): ColorScheme = darkColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
)

private data class Preset(val light: ColorScheme, val dark: ColorScheme)

private val BluePreset = Preset(
    light = lightScheme(
        primary = 0xFF0B57D0, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFD3E3FD, onPrimaryContainer = 0xFF041E49,
        secondaryContainer = 0xFFD7E3F8, onSecondaryContainer = 0xFF101C2B,
        tertiaryContainer = 0xFFF2DAFF, onTertiaryContainer = 0xFF251431,
    ),
    dark = darkScheme(
        primary = 0xFFA8C8FF, onPrimary = 0xFF002F65,
        primaryContainer = 0xFF0842A0, onPrimaryContainer = 0xFFD3E3FD,
        secondaryContainer = 0xFF3B4858, onSecondaryContainer = 0xFFD7E3F8,
        tertiaryContainer = 0xFF523F5F, onTertiaryContainer = 0xFFF2DAFF,
    ),
)

private val PurplePreset = Preset(
    light = lightScheme(
        primary = 0xFF6B4E9B, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFEADDFF, onPrimaryContainer = 0xFF251431,
        secondaryContainer = 0xFFE8DEF8, onSecondaryContainer = 0xFF1D192B,
        tertiaryContainer = 0xFFFFD8E4, onTertiaryContainer = 0xFF31101D,
    ),
    dark = darkScheme(
        primary = 0xFFD0BCFF, onPrimary = 0xFF381E72,
        primaryContainer = 0xFF4F378B, onPrimaryContainer = 0xFFEADDFF,
        secondaryContainer = 0xFF4A4458, onSecondaryContainer = 0xFFE8DEF8,
        tertiaryContainer = 0xFF633B48, onTertiaryContainer = 0xFFFFD8E4,
    ),
)

private val GreenPreset = Preset(
    light = lightScheme(
        primary = 0xFF1E6B4F, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFA8F0CD, onPrimaryContainer = 0xFF002114,
        secondaryContainer = 0xFFCDE8DA, onSecondaryContainer = 0xFF0A1F16,
        tertiaryContainer = 0xFFCEE8F5, onTertiaryContainer = 0xFF071E28,
    ),
    dark = darkScheme(
        primary = 0xFF8CD4B1, onPrimary = 0xFF003825,
        primaryContainer = 0xFF005138, onPrimaryContainer = 0xFFA8F0CD,
        secondaryContainer = 0xFF334C40, onSecondaryContainer = 0xFFCDE8DA,
        tertiaryContainer = 0xFF334C58, onTertiaryContainer = 0xFFCEE8F5,
    ),
)

private val OrangePreset = Preset(
    light = lightScheme(
        primary = 0xFF8B5000, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFFFDCBE, onPrimaryContainer = 0xFF2D1600,
        secondaryContainer = 0xFFF2DFD1, onSecondaryContainer = 0xFF221A12,
        tertiaryContainer = 0xFFE4E2CC, onTertiaryContainer = 0xFF1C1C0E,
    ),
    dark = darkScheme(
        primary = 0xFFFFB870, onPrimary = 0xFF4A2800,
        primaryContainer = 0xFF6B3C00, onPrimaryContainer = 0xFFFFDCBE,
        secondaryContainer = 0xFF51443A, onSecondaryContainer = 0xFFF2DFD1,
        tertiaryContainer = 0xFF484834, onTertiaryContainer = 0xFFE4E2CC,
    ),
)

private fun presetFor(color: ThemeColor): Preset = when (color) {
    ThemeColor.PURPLE -> PurplePreset
    ThemeColor.GREEN -> GreenPreset
    ThemeColor.ORANGE -> OrangePreset
    else -> BluePreset
}

/** 主题色选择器用的色块；[ThemeColor.DYNAMIC] 用中性灰表示「跟随壁纸」 */
fun ThemeColor.swatch(): Color = when (this) {
    ThemeColor.DYNAMIC -> Color(0xFF9AA0A6)
    else -> presetFor(this).light.primary
}

// ---- 玻璃皮肤兼容层（待各页面迁移完成后连同 ui/glass 一并删除）----
// ui/glass 的 Nc* 组件仍在被未迁移的页面引用，且其中带玻璃分支，故保留这两个
// CompositionLocal。AppTheme 不再下发，取值即默认的「非玻璃 + 磨砂」，玻璃分支永不进入。

/** 当前是否处于液态玻璃模式（已废弃，恒 false） */
val LocalGlassMode = staticCompositionLocalOf { false }

/** 玻璃清晰度（已废弃，恒 FROSTED） */
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.FROSTED }

/** 玻璃清晰度（已废弃，仅为 ui/glass 编译保留） */
enum class GlassStyle(val label: String) {
    FROSTED("磨砂玻璃"),
    SOFT("柔光玻璃"),
    ;

    companion object {
        fun from(name: String?): GlassStyle = entries.firstOrNull { it.name == name } ?: FROSTED
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val darkName by ServiceLocator.settings.darkMode.collectAsState(initial = DarkMode.SYSTEM.name)
    val colorName by ServiceLocator.settings.themeColor.collectAsState(initial = ThemeColor.DYNAMIC.name)
    val context = LocalContext.current

    val dark = when (DarkMode.from(darkName)) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val color = ThemeColor.from(colorName)
    val scheme = when {
        color == ThemeColor.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> presetFor(color).let { if (dark) it.dark else it.light }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        content = content,
    )
}
