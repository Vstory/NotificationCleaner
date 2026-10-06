package io.github.vstory.hook.notifyfilter.keepalive

import android.app.Notification
import android.content.Context
import io.github.vstory.hook.notifyfilter.ai.FeatureHasher
import io.github.vstory.hook.notifyfilter.ai.SpamDelta
import io.github.vstory.hook.notifyfilter.ai.SpamModel
import io.github.vstory.hook.notifyfilter.data.ModuleConfig
import io.github.vstory.hook.notifyfilter.data.ModuleConfigCodec
import io.github.vstory.hook.notifyfilter.data.ModuleRule
import io.github.vstory.hook.notifyfilter.data.db.CompiledCondition
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI_MODULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE_MODULE
import io.github.vstory.hook.notifyfilter.data.db.RuleCompiler
import io.github.vstory.hook.notifyfilter.data.db.RuleCondition
import io.github.vstory.hook.notifyfilter.data.db.RuleConditionSet
import io.github.vstory.hook.notifyfilter.notify.FilterGuards
import io.github.vstory.hook.notifyfilter.notify.NotificationProtector

/**
 * LSPosed 模块端判定引擎（1.2.1，借鉴 ref/Notice KeywordFilter 架构）：
 * 在 system_server 内、通知入队前完成决策——命中即吞掉（skipResult），
 * 通知永远不进系统，从根上消除 NLS 进程冻结/被杀导致的过滤延迟。
 *
 * 决策语义与 CleanerListenerService.handle 逐条对齐：
 * 自身豁免 → 群摘要放行 → 内置保护类型放行 → MIUI targetPkg 解析 → 规则（BLOCK_RULE）
 * → 白名单放行 → 硬放行护栏 → 观察模式放行 → AI 打分 + '>' 抬升 + 阈值（BLOCK_AI）。
 * 任何异常一律放行（PROTECTIVE + 内层 runCatching 双保险）。
 */
internal class FilterEngine {

    /** 一次判定的结果 */
    data class Outcome(
        val block: Boolean,
        val decision: String, // BLOCK 时为 MODULE 决策常量
        val probability: Float,
        val title: String,
        val content: String,
    )

    /** 最近一次 attach 的框架接口：热重载会换新的模块实例，故存字段而不是捕获进监听闭包 */
    @Volatile private var api: io.github.libxposed.api.XposedInterface? = null

    /** 已注册过 prefs 监听：本实例被热重载复用时据此跳过重复注册（监听器不随热重载清理） */
    private var listenerRegistered = false

    @Volatile private var config = ModuleConfig()

    /** 已应用用户学习修正的内置模型；未加载为 null（此时全部放行，靠 NLS 兜底） */
    @Volatile private var model: SpamModel? = null
    @Volatile private var loadedDeltaVersion = Long.MIN_VALUE

    // P0-2：MIUI extraNotification 反射缓存（探测一次，成功缓存 Field/Method，失败永久短路）
    @Volatile private var extraField: java.lang.reflect.Field? = null
    @Volatile private var targetPkgGetter: java.lang.reflect.Method? = null
    @Volatile private var resolveProbed = false

    /** 预编译规则快照（config.rules 变化时重建） */
    @Volatile private var compiledRules: List<CompiledModuleRule> = emptyList()

    private class CompiledModuleRule(
        val packageName: String,
        val conditions: List<CompiledCondition>,
        val join: String,
    )

    /** 从 NMS 入口参数解析出的上下文 */
    class Parsed(val pkg: String, val channelId: String, val notification: Notification)

    /**
     * 入队前判定。[ctx] 为 NMS Context（供打日志/回流使用），可为 null。
     * 返回 null 表示无法解析（放行）。
     */
    fun decide(pkgRaw: String, notification: Notification?): Outcome? {
        if (notification == null) return null
        val pkg = resolvePackage(pkgRaw, notification).ifBlank { return null }
        if (pkg == SELF_PKG) return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val joined = listOf(content, bigText).filter { it.isNotEmpty() }.distinct().joinToString(" ")
        if (title.isEmpty() && joined.isEmpty()) return null

        // 配置取一次快照：保护开关与后续阈值/拦截模式必须来自同一个 config，
        // 否则热更新正好落在两行之间时会出现「按新开关放行、按旧阈值打分」
        val cfg = config

        // 内置保护类型：与 NotificationProtector 相同语义，媒体/对话/常驻不过滤（开关随 config 下发）
        if (NotificationProtector.classifyType(notification, cfg.protection()) != null) return null

        // 规则（先于白名单，与 NLS 一致：白名单 APP 仍受规则约束）
        val snapshot = compiledRules
        for (rule in snapshot) {
            if (rule.packageName != pkg) continue
            if (rule.conditions.isEmpty()) continue
            val hit = if (rule.join == RuleConditionSet.OR_JOIN) {
                rule.conditions.any { it.eval(title, joined) }
            } else {
                rule.conditions.all { it.eval(title, joined) }
            }
            if (hit) return Outcome(true, DECISION_FILTERED_BY_RULE_MODULE, 0f, title, joined)
        }

        // 白名单：跳过 AI 过滤
        if (pkg in cfg.whitelist) return null

        // AI 判定（与 NLS 一致：归一化文本 = title + content 合流）
        val aiText = listOf(title, joined).filter { it.isNotEmpty() }.joinToString("\n")
        val normalized = FeatureHasher.normalize(aiText)
        if (normalized.length < 4 ||
            FilterGuards.HARD_ALLOW_WORDS_NORMALIZED.any { normalized.contains(it) }
        ) {
            return null
        }
        if (!cfg.interceptMode) return null // 观察模式：入队走 NLS 原路径记录
        val m = model ?: return null

        val channel = notification.channelId.orEmpty()
        val chKey = if (channel.isNotEmpty()) FeatureHasher.channelKey(pkg, channel) else null
        val p0 = m.scoreNormalized(normalized, chKey)
        val p = if (aiText.contains('>') || aiText.contains('＞')) {
            maxOf(p0, FilterGuards.SPAM_MARK_BOOST)
        } else {
            p0
        }
        return if (p >= cfg.threshold) {
            Outcome(true, DECISION_FILTERED_BY_AI_MODULE, p.toFloat(), title, joined)
        } else {
            null
        }
    }

    // ---- 配置与模型加载（attach 由 MainHook 调用；热重载复用时同一实例会再次 attach） ----

    fun attach(newApi: io.github.libxposed.api.XposedInterface, attempt: Int = 0) {
        api = newApi
        try {
            val prefs = newApi.getRemotePreferences(ModuleConfigCodec.PREFS_NAME)
            refresh(prefs)
            refreshModel(newApi)
            // 热重载复用本实例（见 MainHook.sharedEngine）：监听已注册过就只刷新 api，
            // 再 register 一次就是把监听器堆在别人的进程里，永不回收。
            if (listenerRegistered) return
            listenerRegistered = true
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
                runCatching {
                    refresh(p)
                    refreshModel(api)
                    ModuleLogger.i("module config hot-updated: rules=${config.rules.size} deltaV=${config.deltaVersion}")
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            // daemon 尚未就绪时读不到 prefs：不重试就会永久停在默认配置上
            // （热更新也依赖这次注册成功，注册不上等于整条同步链在模块端断了）
            ModuleLogger.e("module remote prefs unavailable (attempt $attempt)", t)
            if (attempt < ATTACH_RETRY_MAX) {
                val delay = ATTACH_RETRY_DELAYS_MS[attempt.coerceIn(0, ATTACH_RETRY_DELAYS_MS.lastIndex)]
                retryExecutor.execute {
                    Thread.sleep(delay)
                    attach(newApi, attempt + 1)
                }
            }
        }
    }

    private fun refresh(prefs: android.content.SharedPreferences) {
        val raw = prefs.getString(ModuleConfigCodec.KEY_CONFIG, null)
        if (raw == null) {
            // 空值会静默退化成默认配置：链路断掉时全靠这条日志留线索
            ModuleLogger.e("module config missing: APP 未推送过配置，模块端用默认值")
        }
        val next = ModuleConfigCodec.decode(raw)
        config = next
        compiledRules = next.rules.map { r ->
            val set = RuleConditionSet(
                join = r.join,
                conditions = r.conditions.map { RuleCondition(it.field, it.mode, it.values) },
            )
            FilterEngine.CompiledModuleRule(r.packageName, RuleCompiler.compile(set), set.join)
        }
    }

    /**
     * delta 版本变化才重载模型；base 经模块 APK classpath 读取（P0-3：base 只加载一次，
     * 缓存于 companion；重建在单线程 Executor 上异步执行，`model` @Volatile 原子换引用，
     * 重建期间旧模型继续可用，语义为"延迟生效"）。
     */
    private fun refreshModel(api: io.github.libxposed.api.XposedInterface?, attempt: Int = 0) {
        val version = config.deltaVersion
        if (version == loadedDeltaVersion && model != null) return
        rebuildExecutor.execute { rebuildModel(api, version, attempt) }
    }

    /** 模型重建本体（rebuildExecutor 单线程）；[version] 为触发本次重建时看到的版本号 */
    private fun rebuildModel(api: io.github.libxposed.api.XposedInterface?, version: Long, attempt: Int) {
        runCatching {
            if (version == loadedDeltaVersion && model != null) return@runCatching
            val base = loadBaseModel()
            var next = base
            // 2.0.1 Dev 11：只有**真正读到并应用了** delta 才算这个版本加载完成。
            // 旧实现无论 openRemoteFile 返回 null（文件还没被 APP 写入——见
            // ModuleConfigSync 的 TOCTOU）还是解码失败，都把版本号登记为已加载，
            // 于是模块端就此停在 base 模型上，用户"学习为正常"后仍被按广告拦截。
            var deltaApplied = false
            if (base != null && version != 0L && api != null) {
                runCatching {
                    val pfd = api.openRemoteFile(ModuleConfigCodec.DELTA_REMOTE_FILE)
                    if (pfd == null) {
                        ModuleLogger.e("module delta file unavailable (v$version)")
                    } else {
                        pfd.use {
                            val delta = SpamDelta.decode(
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(it),
                            )
                            if (!delta.isEmpty) {
                                next = base.withDelta(delta)
                                deltaApplied = true
                                ModuleLogger.i(
                                    "module delta v$version loaded: ${delta.indices.size} weights",
                                )
                            }
                        }
                    }
                }.onFailure { ModuleLogger.e("module delta load failed", it) }
            }
            model = next
            if (version == 0L || deltaApplied) {
                loadedDeltaVersion = version
            } else if (attempt < DELTA_RETRY_MAX) {
                // 未拿到 delta：退避重试（APP 侧可能还在写文件）。
                // 关键：不登记版本号，保证重试仍会触发；重试以**当前**版本号为准
                //（等待期间用户可能又学了一条）
                val delay = DELTA_RETRY_DELAYS_MS[attempt.coerceIn(0, DELTA_RETRY_DELAYS_MS.lastIndex)]
                ModuleLogger.i("module delta v$version not applied, retry in ${delay}ms")
                Thread.sleep(delay)
                rebuildModel(api, config.deltaVersion, attempt + 1)
            } else {
                ModuleLogger.e("module delta v$version gave up after $DELTA_RETRY_MAX retries")
            }
        }.onFailure { ModuleLogger.e("module model rebuild failed", it) }
    }

    /** base 模型只加载一次；加载失败置负极标记，避免每次学习事件都重试 0.5MB IO。 */
    private fun loadBaseModel(): SpamModel? {
        cachedBase?.let { return it }
        if (baseLoadFailed) return null
        val base = runCatching {
            SpamModel::class.java.classLoader
                ?.getResourceAsStream(MODEL_RESOURCE)?.use { SpamModel.load(it) }
        }.onFailure {
            ModuleLogger.e("module base model load failed", it)
        }.getOrNull()
        if (base == null) baseLoadFailed = true else cachedBase = base
        return base
    }

    /** MIUI：通知可能由系统框架代发，extraNotification.targetPkg 才是真实包名（借鉴 ref/Notice Xiaomi.kt） */
    private fun resolvePackage(pkg: String, notification: Notification): String {
        // P0-2：反射结果缓存（system_server 内 Notification 类唯一，缓存安全）。
        // 慢路径（字段/方法查找）只发生在第一条通知；此后每条仅 2 次反射调用。
        val field = extraField
        val getter = targetPkgGetter
        if (field != null && getter != null) {
            val target = runCatching {
                val extra = field.get(notification)
                if (extra != null) getter.invoke(extra) as? String else null
            }.getOrNull()
            return if (!target.isNullOrBlank()) target else pkg
        }
        if (resolveProbed) return pkg // 探测失败（非 MIUI）→ 永久短路
        return runCatching {
            val f = notification.javaClass.getField("extraNotification")
            val m = f.type.methods
                .firstOrNull { it.name == "getTargetPkg" && it.parameterCount == 0 }
            if (m == null) {
                resolveProbed = true
                return pkg
            }
            val extra = f.get(notification)
            val target = if (extra != null) m.invoke(extra) as? String else null
            // 探测成功即缓存（与 target 本次是否非空无关，extra 可能后续才有值）
            extraField = f
            targetPkgGetter = m
            if (!target.isNullOrBlank()) target else pkg
        }.getOrElse {
            resolveProbed = true
            pkg
        }
    }

    fun isWhitelisted(pkg: String): Boolean = pkg in config.whitelist

    companion object {
        private const val SELF_PKG = "io.github.vstory.hook.notifyfilter"
        private const val MODEL_RESOURCE = "model/model.bin"

        /** Dev 11：delta 未就绪时的退避重试（最多 3 次：1s / 3s / 8s） */
        private const val DELTA_RETRY_MAX = 3
        private val DELTA_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L)

        /** remote prefs 读取/注册失败时的退避重试（1s / 3s / 10s） */
        private const val ATTACH_RETRY_MAX = 3
        private val ATTACH_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 10_000L)

        // P0-3：base 模型进程级缓存（system_server 内 class/model 唯一，缓存安全）
        @Volatile private var cachedBase: SpamModel? = null
        @Volatile private var baseLoadFailed = false
    }

    /** P0-3：模型重建专用单线程（串行化重建，避免与 decide() 的并发读互相干扰） */
    private val rebuildExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NfWatch-ModelRebuild").apply { isDaemon = true }
    }

    /** attach 重试专用单线程：不与模型重建排队（重试里的 sleep 会拖住重建） */
    private val retryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "NfWatch-PrefsRetry").apply { isDaemon = true }
    }
}
