package io.github.vstory.hook.notifyfilter.data

import io.github.vstory.hook.notifyfilter.data.db.DECISION_CONVERSATION
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MEDIA
import io.github.vstory.hook.notifyfilter.data.db.DECISION_ONGOING

/**
 * 通知类型保护（默认全开）：开启 = 该类型不参与过滤，与通知滤盒「过滤范围 → 通知类型」同义。
 *
 * 判定链上有两个消费者、两个进程：NLS 端读 DataStore（[SettingsRepository.protectTypes]），
 * 模块端在 system_server 内只能读 [ModuleConfig]（见 [ModuleConfigSync]）。
 * 两边必须守同一份值，否则会出现「APP 关了保护、模块端仍按保护放行」。
 *
 * 开关只影响后续判定；已入库行的 decision 不变（详情面板照旧显示当时的判定结果）。
 */
data class ProtectTypes(
    val media: Boolean = true,
    val conversation: Boolean = true,
    val ongoing: Boolean = true,
) {
    fun isProtected(decision: String): Boolean = when (decision) {
        DECISION_MEDIA -> media
        DECISION_CONVERSATION -> conversation
        DECISION_ONGOING -> ongoing
        else -> false
    }
}
