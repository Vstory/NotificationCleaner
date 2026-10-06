package io.github.vstory.hook.notifyfilter.data

import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.db.RuleDao
import io.github.vstory.hook.notifyfilter.data.db.RuleEntity
import io.github.vstory.hook.notifyfilter.data.db.WhitelistDao
import io.github.vstory.hook.notifyfilter.data.db.WhitelistEntity
import io.github.vstory.hook.notifyfilter.diagnostics.LogModules
import io.github.vstory.hook.notifyfilter.diagnostics.RingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * APP → LSPosed 模块配置同步（1.2.1；2.x 修正写入通道）：
 * - 规则/白名单/阈值/拦截模式/通知类型保护任一变化 → 500ms 防抖后编码 [ModuleConfig]，
 *   经 [XposedService.getRemotePreferences] 写入**框架 daemon 侧**存储，模块端同名 group 读取并热更新
 * - delta 版本变化或 Xposed 服务绑定完成 → 经 openRemoteFile 推送 delta 二进制，config 随版本号一并下发
 *
 * ⚠️ 不能写成 `context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)`：那是模块 APP 的私有文件，
 * 而模块端（system_server）读的 `getRemotePreferences` 取的是 daemon 的 SQLite 表，两者不同源。
 * 旧实现这么写 ⇒ 模块端一直拿到空值、用默认配置跑（见知识库 跨进程配置链路修复.md）。
 *
 * 模块未激活（service 未绑定）时跳过写入；绑定后由 [onServiceBound] 用实时快照补推。
 */
class ModuleConfigSync(
    private val ruleDao: RuleDao,
    private val whitelistDao: WhitelistDao,
) {
    /** 框架服务（App.onServiceBind 注入）；null = 模块未激活/框架不可用 */
    @Volatile
    var xposedService: XposedService? = null

    /** 串行化整轮推送：delta 文件与 config 必须来自同一快照，且顺序不可反 */
    private val pushMutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // 1.3.2（P1-5）：整个同步协程挪 IO 池 + 500ms 防抖（注释承诺但此前未实现），
        // 配置抖动合并为一次推送；写入不再落在 UI/CPU 池线程
        scope.launch(Dispatchers.IO) {
            combine(
                ruleDao.listAll(),
                whitelistDao.listAll(),
                ServiceLocator.settings.threshold,
                ServiceLocator.settings.interceptMode,
                ServiceLocator.settings.protectTypes,
            ) { rules, whitelist, threshold, intercept, protect ->
                buildConfig(rules, whitelist, threshold, intercept, protect, 0L)
            }
                // deltaVersion 不是判定参数，单独并在外层：带类型的 combine 重载最多 5 个流，
                // 再塞一个只能退化成 Array<Any> 强转
                .combine(ServiceLocator.modelRepo.deltaVersion) { config, deltaV ->
                    config.copy(deltaVersion = deltaV)
                }
                .debounce(500)
                .collect { config -> push(config) }
        }
    }

    /** Xposed 服务绑定完成（App.onServiceBind 调用）：补推一份实时快照 */
    suspend fun onServiceBound() {
        // 不复用"上一次防抖结果"：绑定可能早于首次 Room 查询返回，实时读更稳（一次查询成本可忽略）
        push(currentConfig())
    }

    /** 实时快照（Room + DataStore + delta 版本号） */
    private suspend fun currentConfig(): ModuleConfig = buildConfig(
        ruleDao.listAll().first(),
        whitelistDao.listAll().first(),
        ServiceLocator.settings.threshold.first(),
        ServiceLocator.settings.interceptMode.first(),
        ServiceLocator.settings.protectTypes.first(),
        ServiceLocator.modelRepo.deltaVersion.value,
    )

    private fun buildConfig(
        rules: List<RuleEntity>,
        whitelist: List<WhitelistEntity>,
        threshold: Float,
        interceptMode: Boolean,
        protect: ProtectTypes,
        deltaVersion: Long,
    ): ModuleConfig = ModuleConfig(
        threshold = threshold,
        interceptMode = interceptMode,
        whitelist = whitelist.map { it.packageName },
        rules = rules.map { r ->
            val set = r.conditionSet()
            ModuleRule(
                packageName = r.packageName,
                enabled = r.enabled,
                join = set.join,
                conditions = set.conditions.map {
                    ModuleCondition(it.field, it.mode, it.values)
                },
            )
        },
        protectMedia = protect.media,
        protectConversation = protect.conversation,
        protectOngoing = protect.ongoing,
        deltaVersion = deltaVersion,
    )

    /** 推送整份配置到 daemon 侧 remote prefs；未绑定则直接跳过（绑定后 [onServiceBound] 补推） */
    private suspend fun push(config: ModuleConfig) = withContext(Dispatchers.IO) {
        val service = xposedService ?: return@withContext
        pushMutex.withLock {
            // 顺序铁律：**先写 delta 文件、后写 config**——config 里的 deltaVersion 是模块端
            // "delta 内容已就绪"的信号。反序会被模块端 OnSharedPreferenceChangeListener 立刻读到，
            // 此时文件还没写完（或仍是上一版），而版本号已被登记为已加载 ⇒ 学习成果在模块端永久不生效
            if (config.deltaVersion != 0L) pushDelta(service)
            runCatching {
                // commit()：RemotePreferences 的 apply() 是后台线程异步 binder 提交，进程被杀即丢写
                val ok = service.getRemotePreferences(ModuleConfigCodec.PREFS_NAME)
                    .edit()
                    .putString(ModuleConfigCodec.KEY_CONFIG, ModuleConfigCodec.encode(config))
                    .commit()
                if (!ok) {
                    RingLog.log(LogModules.MODEL, "✗ 模块配置写入被框架拒绝（commit=false）")
                }
            }.onFailure {
                android.util.Log.w("NfWatch", "module config push failed: $it")
                RingLog.log(LogModules.MODEL, "✗ 模块配置推送失败: $it（模块端仍用上一次配置）")
            }
        }
    }

    /** 经框架 openRemoteFile 写 delta 二进制（学习修正文件 → 模块可读）。调用方须持有 [pushMutex] */
    private suspend fun pushDelta(service: XposedService) {
        runCatching {
            // Dev 10（P1-3）：经 ModelRepository 在同一锁域取快照，
            // 不再直接读文件（旧写法与 applyDelta 的写入锁不共享 → 可能推撕裂文件给模块）
            val bytes = ServiceLocator.modelRepo.deltaSnapshot()
            if (bytes == null) {
                RingLog.log(
                    LogModules.MODEL,
                    "△ delta 未推送：当前无学习修正（模型为内置基线）",
                )
                return@runCatching
            }
            service.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)?.use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                    out.write(bytes)
                }
            }
            RingLog.log(LogModules.MODEL, "delta 已推送到模块：${bytes.size}B")
        }.onFailure {
            android.util.Log.w("NfWatch", "module delta push failed: $it")
            RingLog.log(
                LogModules.MODEL,
                "✗ delta 推送失败: $it（模块端仍用旧/基线模型打分）",
            )
        }
    }
}
