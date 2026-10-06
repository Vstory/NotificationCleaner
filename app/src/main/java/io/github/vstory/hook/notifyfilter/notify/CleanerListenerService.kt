package io.github.vstory.hook.notifyfilter.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.vstory.hook.notifyfilter.R
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.ai.SpamModel
import io.github.vstory.hook.notifyfilter.ai.takeCodepoints
import io.github.vstory.hook.notifyfilter.data.ModelRepository
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_PASSED
import io.github.vstory.hook.notifyfilter.data.db.DECISION_WHITELIST
import io.github.vstory.hook.notifyfilter.data.db.NotificationDao
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import io.github.vstory.hook.notifyfilter.data.db.REASON_GONE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 通知监听服务（系统入口，Plan.md §5.6）：
 * 收到通知 → 硬放行护栏 → 规则匹配 → AI 打分 → 阈值决策 → 清除/放行，全部入库留档。
 */
class CleanerListenerService : NotificationListenerService() {

    companion object {
        val SELF_PACKAGE = io.github.vstory.hook.notifyfilter.BuildConfig.APPLICATION_ID

        @Volatile
        private var activeInstance: CleanerListenerService? = null

        /**
         * 监听真实连接状态（1.1.11 修复）：由 onListenerConnected/onListenerDisconnected 维护。
         * 此前看门狗用 activeInstance != null 判断——绑定断开但实例未销毁时会误判"已连接"，
         * 导致看门狗从不去重绑，只能靠进入 APP 的强制 rebind 自愈。
         */
        @Volatile
        private var listenerConnected = false

        /** 学习/拦截时监听未连接 → 记入待取消队列；onListenerConnected 时补撤（1.1.8） */
        private val pendingCancels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** 按通知 key 清除系统通知栏中的通知（供「学习为广告」联动使用） */
        fun cancelByKey(key: String) {
            val svc = activeInstance
            if (svc == null) {
                pendingCancels.add(key)
                return
            }
            val ok = runCatching { svc.cancelNotification(key) }.isSuccess
            if (!ok) pendingCancels.add(key)
        }

        /** 监听服务当前是否已连接（已连接才能收到通知回调/清除通知） */
        fun isListenerConnected(): Boolean = listenerConnected

        /** 权限已授予但绑定断开时请求系统重绑（供保活看门狗/入口检查调用，1.1.8） */
        fun requestRebindIfEnabled(context: Context) {
            if (!isListenerEnabled(context)) return
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, CleanerListenerService::class.java),
                )
            }
        }

        /** 硬放行词表与强广告抬升常量迁移至 [FilterGuards]（1.2.1：NLS 与模块端共用） */

        private val EXPIRE_MS = TimeUnit.DAYS.toMillis(7)
        private val DEDUP_WINDOW_MS = TimeUnit.SECONDS.toMillis(60)

        // ---- 决策热路径缓存（1.0.7：消除每条通知的 DataStore/PackageManager 往返，降低拦截延迟） ----
        @Volatile
        var cachedThreshold: Float = 0.8f
            private set

        @Volatile
        var cachedIntercept: Boolean = true
            private set

        private val appNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** 入库互斥：并发 onNotificationPosted 处理时防止查重-插入竞态双插 */
        private val insertMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var appScope: CoroutineScope? = null

        /**
         * 1.2.0（ImprovePlan P1-5）：通知处理独立限流调度器——风暴/补扫时不再打满
         * Dispatchers.Default（缓解对其它监听 APP 的 CPU 挤压）
         * 1.2.1：拆分为实时 / 补扫双通道——backfill 只占 1 通道慢消化，
         * 重连补扫不再排队阻塞实时通知（重连后延迟的直接修复）
         */
        private var realtimeDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null
        private var backfillDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

        fun initScope(context: Context) {
            if (appScope == null) {
                appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                realtimeDispatcher = Dispatchers.Default.limitedParallelism(2)
                backfillDispatcher = Dispatchers.Default.limitedParallelism(1)
                // 设置项持续同步到内存，决策路径零 IO
                appScope!!.launch {
                    ServiceLocator.settings.threshold.collect { cachedThreshold = it }
                }
                appScope!!.launch {
                    ServiceLocator.settings.interceptMode.collect { cachedIntercept = it }
                }
                // 1.3.2（P0-5）：应用名缓存预热——distinctApps 一次查询回填，
                // 决策路径对已见过的包名不再做 PackageManager IPC
                appScope!!.launch(Dispatchers.IO) {
                    runCatching {
                        ServiceLocator.db.notificationDao().distinctApps()
                            .groupBy { it.packageName }
                            .forEach { (pkg, refs) ->
                                // 同包多行时优先取已解析的 appName（appName != pkg 的行）
                                val best = refs.firstOrNull { it.appName != it.packageName }?.appName
                                    ?: refs.first().appName
                                appNameCache.putIfAbsent(pkg, best)
                            }
                    }
                }
            }
            ServiceLocator.ruleEngine.start(appScope!!)
        }

        /** 监听服务是否已授权并连接 */
        fun isListenerEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            if (TextUtils.isEmpty(flat)) return false
            val cn = ComponentName(context, CleanerListenerService::class.java)
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.equals(cn) == true
            }
        }

        /** 监听断线时请求系统重绑（Plan.md §7.1 看门狗的自愈路径） */
        fun requestRebind(context: Context) {
            if (!isListenerEnabled(context)) return
            runCatching {
                requestRebindCompat(context)
            }
        }

        private fun requestRebindCompat(context: Context) {
            // NotificationListenerService.requestRebind 为静态方法（API 24+）
            NotificationListenerService.requestRebind(
                ComponentName(context, CleanerListenerService::class.java),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        initScope(this)
        android.util.Log.i("NfWatch", "listener onCreate uptime=${android.os.SystemClock.elapsedRealtime()}")
        val scope = appScope ?: return
        scope.launch {
            // Dev 6：保留天数可调（默认 7 天），按 postTime 清理
            val cutoff = ServiceLocator.settings.historyRetentionCutoff(System.currentTimeMillis())
            ServiceLocator.db.notificationDao().purgeOlderThan(cutoff)
        }
        // 1.1.14：进程被拉起（NMS 重绑/开机/升级）时也续约闹钟看门狗，保证链条不断
        WatchdogReceiver.schedule(this)
        KeepAliveService.start(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        android.util.Log.i("NfWatch", "listener CONNECTED")
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
            io.github.vstory.hook.notifyfilter.diagnostics.LogModules.NLS, "监听已连接",
        )
        // 补撤：学习/拦截时监听未连接而残留的通知（1.1.8）
        if (pendingCancels.isNotEmpty()) {
            val keys = pendingCancels.toList()
            pendingCancels.removeAll(keys)
            keys.forEach { runCatching { cancelNotification(it) } }
            android.util.Log.i("NfWatch", "pendingCancels flushed: ${keys.size}")
        }
        // 1.4.0 Dev 15：监听恢复 → 撤下"监听已失效"提醒
        runCatching { ListenerAlertNotifier.cancel(this) }
        // 追溯处理：监听断线期间弹出的通知不会触发回调，重连后扫一遍通知栏补处理（1.1.8）
        // 1.2.1：补扫走独立慢速通道（backfillDispatcher），不与实时通知抢并发
        runCatching {
            val active = activeNotifications
            android.util.Log.i("NfWatch", "backfill scan: ${active?.size ?: -1} active notifications")
            // 三态校正：升级回填/进程重启期间被误标为已取消、实际仍在通知栏的行改回正显示
            val activeKeys = active?.map { it.key }?.filter { it.isNotEmpty() }.orEmpty()
            if (activeKeys.isNotEmpty()) {
                appScope?.launch(Dispatchers.IO) {
                    runCatching { ServiceLocator.db.notificationDao().markPresentVisible(activeKeys) }
                }
            }
            // 反向校正（照通知滤盒 a3/c.a(Z)）：回调停摆窗口内消失的通知收不到 removed 回调，
            // 只做正向校正会让它们永久卡在「正显示」，这里拿通知栏快照反推。
            // active == null 是「取快照失败」而非「栏里没有」，必须跳过，否则会把全表误标为已取消
            if (active != null) {
                val activeKeySet = activeKeys.toHashSet()
                appScope?.launch(Dispatchers.IO) {
                    runCatching {
                        val dao = ServiceLocator.db.notificationDao()
                        val gone = dao.listVisibleKeys().filterNot { it in activeKeySet }
                        if (gone.isNotEmpty()) {
                            val n = dao.markGoneFromSnapshot(gone, REASON_GONE)
                            io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
                                io.github.vstory.hook.notifyfilter.diagnostics.LogModules.NLS,
                                "快照反向校正：$n 条已不在通知栏 → 标记为已取消",
                            )
                        }
                    }
                }
            }
            active?.forEach { sbn -> dispatch(sbn, fromBackfill = true) }
        }
        KeepAliveService.start(this)
    }

    override fun onListenerDisconnected() {
        // 1.1.11 修复：断线必须先落标志，否则看门狗用实例存在误判"已连接"，永远不会自愈重绑
        listenerConnected = false
        android.util.Log.w("NfWatch", "listener DISCONNECTED — requesting rebind")
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
            io.github.vstory.hook.notifyfilter.diagnostics.LogModules.NLS, "✗ 监听断线 → 请求重绑",
        )
        // 1.4.0 Dev 15：断线即发提醒（悬浮 + 锁屏可见），仅在权限仍授予时提醒
        runCatching {
            if (isListenerEnabled(this)) {
                ListenerAlertNotifier.notifyDown(this, R.string.reason_listener_disconnected)
            }
        }
        // 监听断线（进程被杀后系统回收绑定）→ 自愈重绑（Plan.md §7.1）
        requestRebindCompat(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        android.util.Log.w("NfWatch", "listener onDestroy")
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
            io.github.vstory.hook.notifyfilter.diagnostics.LogModules.NLS, "监听服务销毁",
        )
        if (activeInstance === this) activeInstance = null
        appScope?.cancel()
        appScope = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        dispatch(sbn, fromBackfill = false)
    }

    /**
     * 撤销登记（2.2.0 三态）：必须 override 带 reason 的三参重载，两参版拿不到取消原因。
     * 本模块自己 cancelNotification 撤下的拦截通知也走这里，等价于"已被撤销"。
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        val key = sbn.key
        if (key.isEmpty()) return
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                ServiceLocator.db.notificationDao()
                    .markDismissed(key, System.currentTimeMillis(), reason)
            }
        }
    }

    /** 实时回调与重连补扫共用入口；fromBackfill 决定走慢速补扫通道（1.2.1） */
    private fun dispatch(sbn: StatusBarNotification, fromBackfill: Boolean) {
        if (sbn.packageName == SELF_PACKAGE) return
        // 1.3.2（P3-2）：每通知一次的日志在 release 下门控，省 logd 写入与字符串分配
        if (io.github.vstory.hook.notifyfilter.BuildConfig.DEBUG) {
            android.util.Log.i("NfWatch", "posted pkg=${sbn.packageName} connected=$listenerConnected backfill=$fromBackfill")
        }
        val notification: Notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        // 1.3.2（P1-2）：入库正文截断 500 codepoint——阻止 DB/复合索引膨胀与去重长串比较；
        // 截断后 joined 的前 500 codepoint 不变，AI 打分逐位不变（论证见 1.3.2Plan P1-2）
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .takeCodepoints(io.github.vstory.hook.notifyfilter.ai.FeatureHasher.MAX_TEXT_LEN)
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            .takeCodepoints(io.github.vstory.hook.notifyfilter.ai.FeatureHasher.MAX_TEXT_LEN)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            .takeCodepoints(io.github.vstory.hook.notifyfilter.ai.FeatureHasher.MAX_TEXT_LEN)
        val text = listOf(content, bigText).filter { it.isNotEmpty() }
            .distinct().joinToString(" ").ifEmpty { content }
        if (title.isEmpty() && text.isEmpty()) return

        // 1.4.0 Dev 12：环形日志全量留痕——此前 release 下 NfWatch logcat 门控，
        // 事件无法事后归因（排查时 logcat/内存 trace 均已滚动丢失）
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
            io.github.vstory.hook.notifyfilter.diagnostics.LogModules.PIPE,
            // Dev 12：补正文片段——只有标题无法判断"这条为什么被判广告"，
            // 决策依据正是 title + content 合流后的文本（正文截断 60 字，控制环形日志体积）
            // Dev 12 延迟排查：post=通知原始发布时间、lag=发布到我们收到的滞后
            //（lag 几十秒以上 = 系统投递积压，与本 App 处理速度无关）
            "通知 pkg=${sbn.packageName} backfill=$fromBackfill" +
                " post=${io.github.vstory.hook.notifyfilter.diagnostics.DiagTime.stamp(sbn.postTime)}" +
                " lag=${io.github.vstory.hook.notifyfilter.diagnostics.DiagTime.lagText(System.currentTimeMillis() - sbn.postTime)}" +
                " title=${title.take(24)}" +
                " content=${text.take(60)}",
        )

        // 1.1.13：灭屏瞬间 CPU 可能被挂起导致打分/入库中断，短超时部分唤醒锁保证处理完成
        // 1.2.0（ImprovePlan P1-4）：仅灭屏时加锁——亮屏时 CPU 本就唤醒，无需锁
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive == false) {
                pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "NotifyFilter:handle",
                )?.acquire(10_000)
            }
        }

        val scope = appScope ?: return
        val dispatcher = if (fromBackfill) backfillDispatcher else realtimeDispatcher
        scope.launch(dispatcher ?: Dispatchers.Default) { handle(sbn, title, text, fromBackfill) }
    }

    private suspend fun handle(
        sbn: StatusBarNotification,
        title: String,
        content: String,
        fromBackfill: Boolean,
    ) {
        val locator = ServiceLocator
        val dao: NotificationDao = locator.db.notificationDao()
        val modelRepo: ModelRepository = locator.modelRepo

        val pkg = sbn.packageName
        // 1.3.2（P0-5）：应用名解析移出决策路径——缓存命中直接用；未命中先用包名占位
        //（putIfAbsent 保证单飞），异步解析后回填缓存与历史行，不占用实时槽做 IPC
        val appName = appNameCache[pkg] ?: run {
            appNameCache.putIfAbsent(pkg, pkg)
            appScope?.launch(Dispatchers.IO) {
                val label = appLabel(pkg)
                appNameCache[pkg] = label
                // 解析失败时 label == pkg（兜底值，维持占位语义，与 appLabel KDoc 1.2.3 取舍一致）；
                // 此时跳过 DB 回填，避免空转 UPDATE（P0-5 修订4）
                if (label != pkg) {
                    runCatching { locator.db.notificationDao().updateAppName(pkg, label) }
                }
            }
            pkg
        }
        val notification = sbn.notification
        val channel = notification?.channelId.orEmpty()
        // 渠道名展示层解析：详情页展示渠道 ID（Plan.md §6.1）

        val postTime = sbn.postTime
        val joined = listOf(title, content).filter { it.isNotEmpty() }.joinToString("\n")

        // 1.2.0（ImprovePlan P1-6）：重连补扫快路径——槽位已入库且标题/内容/时间未变 → 整体跳过，
        // 消除重连 backfill 风暴的重复推理与写库（正式查重在下方 insertMutex 内，此处仅无锁预检）
        val preExisting = runCatching { dao.findByKey(sbn.key) }.getOrNull()
        if (preExisting != null &&
            preExisting.title == title &&
            preExisting.content == content &&
            preExisting.postTime == postTime
        ) {
            return
        }

        // ---- 决策（1.0.7：阈值/拦截模式走内存缓存，热路径零 IO）----
        var decision = DECISION_PASSED
        var probability = 0f

        val protectedType = NotificationProtector.classifyType(notification)
        when {
            // 内置保护类型：媒体/对话/常驻通知完全不参与过滤，仅入库留档
            protectedType != null -> decision = protectedType
            else -> {
                val whitelisted = locator.ruleEngine.isWhitelisted(pkg)
                val rule = locator.ruleEngine.match(pkg, title, content)
                when {
                    rule != null -> decision = DECISION_FILTERED_BY_RULE
                    whitelisted -> decision = DECISION_WHITELIST // 跳过 AI 过滤（规则仍生效，见上）
                    else -> {
                        val normalized = io.github.vstory.hook.notifyfilter.ai.FeatureHasher.normalize(joined)
                        // 1.3.2（P1-3）：硬放行改判已归一化文本（零额外分配，省 6 次 locale 敏感扫描）。
                        // 与模块端（FilterEngine.decide）同步修改；语义扩大（分隔符变体命中）有意为之
                        val hardAllow = normalized.length < 4 ||
                            FilterGuards.HARD_ALLOW_WORDS_NORMALIZED.any { normalized.contains(it) }
                        if (!hardAllow) {
                            // 模型不可用（assets 缺失/损坏）时跳过打分，按放行处理
                            // 1.1.11：带通道偏置打分（同 App 同渠道的学习成果直接生效）
                            // 1.2.0（ImprovePlan P1-2/P1-3）：复用已 normalize 文本 + 打分 LRU 缓存
                            val chKey = if (channel.isNotEmpty()) {
                                io.github.vstory.hook.notifyfilter.ai.FeatureHasher.channelKey(pkg, channel)
                            } else {
                                null
                            }
                            val p0 = modelRepo.scoreCached(pkg, normalized, chKey) ?: 0.0
                            // 1.1.11：">"/">>" 强广告标记（覆盖全角 ＞），命中抬到 0.95
                            val p = if (joined.contains('>') || joined.contains('＞')) {
                                maxOf(p0, FilterGuards.SPAM_MARK_BOOST)
                            } else {
                                p0
                            }
                            probability = p.toFloat()
                            if (p >= cachedThreshold && cachedIntercept) {
                                decision = DECISION_FILTERED_BY_AI
                            }
                        }
                    }
                }
            }
        }

        // 1.4.0 Dev 12：决策结果环形留痕（decision 常量可直接 grep：passed/whitelist/filtered_*）
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
            io.github.vstory.hook.notifyfilter.diagnostics.LogModules.PIPE,
            "决策 pkg=$pkg decision=$decision p=$probability title=${title.take(24)}",
        )

        if (decision == DECISION_FILTERED_BY_AI || decision == DECISION_FILTERED_BY_RULE) {
            // 清除失败（时机过早等）也记入待取消队列，重连时补撤（1.1.11 兜底）
            val ok = runCatching { cancelNotification(sbn.key) }.isSuccess
            if (!ok) {
                android.util.Log.w("NfWatch", "cancel failed, queued: $decision ${sbn.key.takeLast(12)}")
                io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
                    io.github.vstory.hook.notifyfilter.diagnostics.LogModules.PIPE,
                    "✗ 清除通知失败 decision=$decision key=${sbn.key.takeLast(12)}",
                )
                pendingCancels.add(sbn.key)
            } else {
                android.util.Log.i("NfWatch", "filtered+$decision p=$probability")
            }
        }

        // ---- 入库（1.3.2 P1-1：findByKey/update/去重/insert 合并为单事务，fsync 3→1；
        //      与 ModuleLogProvider 回流路径共用 upsertSlot；insertMutex 保留用于跨协程互斥）----
        val entity = NotificationEntity(
            packageName = pkg,
            appName = appName,
            channelId = channel,
            channelName = channel,
            title = title,
            content = content,
            postTime = postTime,
            adProbability = probability,
            decision = decision,
            expireAt = postTime + EXPIRE_MS,
            key = sbn.key,
            // 拦截类的 cancelNotification 在上方已调用，而 onNotificationRemoved 可能在本次入库
            // 之前就跑到（协程并发）→ 那时表里还没有行，撤销标记会丢。这里入库即置位。
            dismissTime = if (decision == DECISION_FILTERED_BY_AI || decision == DECISION_FILTERED_BY_RULE) {
                postTime
            } else {
                -1
            },
        )
        insertMutex.withLock {
            val outcome = dao.upsertSlot(entity, postTime - DEDUP_WINDOW_MS)
            if (outcome != null) {
                countFiltered(decision, outcome.previousDecision)
            }
        }
    }

    /** 累计拦截计数（1.1.5，常驻通知展示）：仅在新拦截时 +1，同槽位重复更新不重复计数 */
    private suspend fun countFiltered(decision: String, previous: String?) {
        val isAi = decision == DECISION_FILTERED_BY_AI
        val isRule = decision == DECISION_FILTERED_BY_RULE
        if (!isAi && !isRule) return
        if (previous == decision) return
        runCatching { ServiceLocator.settings.incrementFiltered(ai = isAi) }
    }

    /**
     * 应用名解析（失败回退包名并随 appNameCache 缓存——进程重启后自然重试）。
     * 根因备注（1.2.3 诊断日志 20260918）：MIUI Android 16 上部分包（如带 systemui
     * 主题覆盖引用的应用）加载资源时因设备侧 /data/resource-cache 的 RRO idmap 文件
     * 缺失/损坏而抛 IOException——属设备状态（主题切换后出现，重启自愈），APP 无法恢复
     * label 本身；职责 = 不崩溃 + 快速兜底 + 不重复刷屏。
     */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)
}
