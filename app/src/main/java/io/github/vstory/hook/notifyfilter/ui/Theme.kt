package io.github.vstory.hook.notifyfilter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.vstory.hook.notifyfilter.data.ThemeAccentColor
import io.github.vstory.hook.notifyfilter.data.ThemeConfig
import io.github.vstory.hook.notifyfilter.data.resolveIsDark
import io.github.vstory.hook.notifyfilter.ui.component.blur.LocalBlurEnabled
import io.github.vstory.hook.notifyfilter.ui.component.blur.LocalTopBarBlurStyle
import io.github.vstory.hook.notifyfilter.ui.theme.LocalAppDarkMode
import io.github.vstory.hook.notifyfilter.ui.theme.LocalAppMonetEnabled
import io.github.vstory.hook.notifyfilter.ui.theme.LocalPlatformDensity
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.platformDynamicColors

/**
 * 主题外壳：颜色模式 / 取色 / 界面缩放全部由 [themeConfig] 驱动。
 *
 * 未开莫奈取色时走 miuix 出厂固定色板（System/Light/Dark），仅在开启后交给
 * MonetSystem/Light/Dark + 调色板 + 强调色，默认档与改动前完全一致。
 */
@Composable
fun AppTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val colorSchemeMode = when {
        !themeConfig.useMonet && themeConfig.colorMode == 1 -> ColorSchemeMode.Light
        !themeConfig.useMonet && themeConfig.colorMode == 2 -> ColorSchemeMode.Dark
        !themeConfig.useMonet -> ColorSchemeMode.System
        themeConfig.colorMode == 1 -> ColorSchemeMode.MonetLight
        themeConfig.colorMode == 2 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val isDark = themeConfig.resolveIsDark(isSystemInDarkTheme())
    val systemSeedColor = if (themeConfig.useMonet && themeConfig.accentColor == ThemeAccentColor.Default) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !themeConfig.useMonet -> null
        themeConfig.accentColor == ThemeAccentColor.Default -> systemSeedColor
        else -> themeConfig.accentColor.seedColor
    }
    val controller = remember(themeConfig, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = themeConfig.paletteStyle,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, isDark, themeConfig.pureBlack) {
        if (themeConfig.useMonet && themeConfig.pureBlack && isDark) {
            colors.copy(
                background = Color.Black,
                surface = Color.Black,
            )
        } else {
            colors
        }
    }

    MiuixTheme(colors = themedColors) {
        val currentDensity = LocalDensity.current
        val appDensity = remember(currentDensity, themeConfig.densityScale) {
            Density(
                density = currentDensity.density * themeConfig.densityScale,
                fontScale = currentDensity.fontScale,
            )
        }
        CompositionLocalProvider(
            LocalAppDarkMode provides isDark,
            LocalAppMonetEnabled provides themeConfig.useMonet,
            LocalPlatformDensity provides currentDensity,
            LocalDensity provides appDensity,
            LocalBlurEnabled provides themeConfig.blurEnabled,
            LocalTopBarBlurStyle provides themeConfig.topBarBlurStyle,
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}
