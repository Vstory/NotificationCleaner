package io.github.vstory.hook.notifyfilter.data

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

data class ThemeConfig(
    val colorMode: Int = 0,
    val pureBlack: Boolean = false,
    val useMonet: Boolean = false,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    val accentColor: ThemeAccentColor = ThemeAccentColor.Default,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: TopBarBlurStyle = TopBarBlurStyle.Gaussian,
    // 悬浮胶囊是已发布形态，改默认值等于给老用户换一套底栏
    val floatingBottomBar: Boolean = true,
    val floatingBottomBarStyle: FloatingBottomBarStyle = FloatingBottomBarStyle.Miuix,
    val bottomBarMode: BottomBarMode = BottomBarMode.IconAndText,
    val densityScale: Float = DefaultDensityScale,
)

const val MinDensityScale = 0.8f
const val MaxDensityScale = 1.1f
const val DefaultDensityScale = 1f

/** colorMode → 深色判定的唯一权威实现；systemDark 由调用方传入系统深色状态 */
fun ThemeConfig.resolveIsDark(systemDark: Boolean): Boolean = when (colorMode) {
    1 -> false
    2 -> true
    else -> systemDark
}

fun normalizeDensityScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinDensityScale, MaxDensityScale) else DefaultDensityScale

enum class TopBarBlurStyle(val storageValue: String) {
    Gaussian("gaussian"),
    Progressive("progressive");

    companion object {
        fun fromStorage(value: String): TopBarBlurStyle =
            entries.firstOrNull { it.storageValue == value } ?: Gaussian
    }
}

enum class ThemeAccentColor(
    val storageValue: String,
    val seedColor: Color,
) {
    Default("default", Color(0xFF3482FF)),
    Blue("blue", Color(0xFF3482FF)),
    Purple("purple", Color(0xFF6750A4)),
    Pink("pink", Color(0xFFB0006D)),
    Red("red", Color(0xFFBA1A1A)),
    Orange("orange", Color(0xFFB65D00)),
    Yellow("yellow", Color(0xFF7D5700)),
    Green("green", Color(0xFF006D3B)),
    Teal("teal", Color(0xFF006A6A));

    companion object {
        fun fromStorage(value: String): ThemeAccentColor =
            entries.firstOrNull { it.storageValue == value } ?: Default
    }
}

val ThemePaletteStyles: List<ThemePaletteStyle> = ThemePaletteStyle.entries.toList()

fun themePaletteStyleFromStorage(value: String): ThemePaletteStyle =
    ThemePaletteStyles.firstOrNull { it.name == value } ?: ThemePaletteStyle.TonalSpot
