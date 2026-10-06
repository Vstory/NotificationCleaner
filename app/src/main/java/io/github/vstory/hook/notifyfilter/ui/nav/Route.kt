package io.github.vstory.hook.notifyfilter.ui.nav

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 全应用路由（miuix-nav 的 NavKey）。
 *
 * ⚠️ 基类这行 @Serializable 不能删：多态序列化器由基类注册，缺了它 rememberNavBackStack
 * 恢复 back stack 时运行期抛 SerializationException（编译期无任何提示）。
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Advanced : Route

    @Serializable
    data object AiModel : Route

    @Serializable
    data object About : Route

    @Serializable
    data object RuleEdit : Route

    @Serializable
    data class Stats(val mode: String) : Route

    @Serializable
    data class AppPicker(val title: String) : Route
}
