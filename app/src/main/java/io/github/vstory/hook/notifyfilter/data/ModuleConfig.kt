package io.github.vstory.hook.notifyfilter.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * APP → LSPosed 模块（system_server）的过滤配置快照（1.2.1）。
 * 跨进程走 libxposed remote preferences：[PREFS_NAME] 是 group 名，两侧同名，
 * APP 侧必须经 `XposedService.getRemotePreferences(PREFS_NAME)` 写入（框架 daemon 侧存储），
 * 模块侧 `XposedInterface.getRemotePreferences` 读取并监听变更热更新。
 * ⚠️ 写模块自己的 `getSharedPreferences(PREFS_NAME)` 不参与这条链（不同存储）——
 * 详见知识库 跨进程配置链路修复.md。
 * 模型学习修正（delta）不经 prefs——经 openRemoteFile("spam_delta.bin") 传二进制，
 * 此处仅携带版本号触发模块重载。
 */
@Serializable
data class ModuleConfig(
    val threshold: Float = 0.8f,
    val interceptMode: Boolean = true,
    val deltaVersion: Long = 0L,
    val whitelist: List<String> = emptyList(),
    val rules: List<ModuleRule> = emptyList(),
    // 通知类型保护：默认 true = 既有行为。decode 用 ignoreUnknownKeys，老 config 缺这几个键时
    // 解出来仍是全保护，不会因为升级让原本被保护的通知突然进入判定
    val protectMedia: Boolean = true,
    val protectConversation: Boolean = true,
    val protectOngoing: Boolean = true,
) {
    fun protection(): ProtectTypes = ProtectTypes(
        media = protectMedia,
        conversation = protectConversation,
        ongoing = protectOngoing,
    )
}

@Serializable
data class ModuleRule(
    val packageName: String,
    val enabled: Boolean = true,
    val join: String = "AND",
    val conditions: List<ModuleCondition> = emptyList(),
)

@Serializable
data class ModuleCondition(
    val field: String, // TITLE / CONTENT
    val mode: String, // MatchMode 常量
    val values: List<String> = emptyList(),
)

object ModuleConfigCodec {

    const val PREFS_NAME = "noticleaner_config"
    const val KEY_CONFIG = "config"
    const val DELTA_REMOTE_FILE = "spam_delta.bin"
    const val ACTION_FLUSH_LOGS = "io.github.vstory.hook.notifyfilter.FLUSH_MODULE_LOGS"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: ModuleConfig): String = json.encodeToString(config)

    fun decode(raw: String?): ModuleConfig = raw?.let {
        runCatching { json.decodeFromString<ModuleConfig>(it) }.getOrNull()
    } ?: ModuleConfig()
}
