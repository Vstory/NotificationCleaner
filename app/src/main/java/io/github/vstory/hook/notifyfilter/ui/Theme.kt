package io.github.vstory.hook.notifyfilter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

// 不要改用 MonetSystem：miuix 的层次感来自中性底色的明度差（surface 纯黑 → 容器 #242424 → #434343），
// Monet 会把 surface、secondaryContainer 等整盘染上系统主题色，卡片与背景糊成一片，
// 输入框（默认填充 secondaryContainer）还会突兀跳色。System 用 miuix 出厂固定色板，仅跟随深浅色。
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val miuixController = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
    MiuixTheme(controller = miuixController) {
        content()
    }
}
