package io.github.vstory.hook.notifyfilter.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.github.vstory.hook.notifyfilter.BuildConfig
import io.github.vstory.hook.notifyfilter.ai.takeCodepoints
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI_MODULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE_MODULE
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import io.github.vstory.hook.notifyfilter.keepalive.ModuleLogger
import io.github.vstory.hook.notifyfilter.notify.CleanerListenerService
import io.github.vstory.hook.notifyfilter.notify.KeepAliveManager
import io.github.vstory.hook.notifyfilter.provider.ModuleLogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志导出（2.0.1 Dev 9：分模块 ZIP）。
 *
 * 旧版把所有模块堆在一个 .txt 里：排查某条链路要在整坨文本里 grep，而且各模块共用
 * 一份环形缓冲配额，通知风暴会把看门狗的 24 小时历史挤掉。
 *
 * 现在导出为 **ZIP**，每个模块一个 log 文件：
 * - `00-README.txt`：诊断头 + 状态快照 + 各模块 24 小时覆盖汇总（先看这个文件）
 * - `01..08`：八个模块各自的 log（[LogModules]），**每个文件都只保留导出前完整 24 小时
 *   窗口内的条目**，并在文件头给出窗口、条目数、最早/最晚时间、逐时分布与覆盖状态
 *   （最早条目明显晚于窗口起点 → 标 ⚠ 部分覆盖并说明可能原因）
 * - `10-NOTIFICATION-HISTORY.csv`：通知历史（最近 24 小时，数据库为准）
 *
 * 条目名全 ASCII（序号 + 模块 TAG），中文模块名只出现在文件内容里——理由见 [LogModules.fileName]。
 *
 * 24 小时覆盖由分模块环形日志（filesDir/logs/ring/<TAG>.log，保留 24h、崩溃/重启不丢）
 * 保证；logcat 缓冲通常不足 24 小时，只作为每个模块文件末尾的"补充段"附带。
 *
 * **`03-HOOK.log` 是唯一例外**：模块端在 system_server 内不写环形日志（[RingLog] 只在 APP
 * 进程 init，`MainHook` 不引用它）⇒ 该模块的 ring 文件从未被创建过，按普通路径生成只会得到
 * 一个恒空文件。它的内容改由历史库里 decision 带 `_MODULE` 后缀的记录（模块端拦截回流）
 * 拼成，见 [buildHookFile]。
 *
 * 输出写入外部私有目录 logs/，经 FileProvider 系统分享（mime application/zip）。
 */
object DiagExporter {

    /** 导出覆盖窗口：导出时刻往前 24 小时（例如 09-28 23:42 导出 → 09-27 23:42 ~ 09-28 23:42） */
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** 逐时分布槽位数 */
    private const val BUCKETS = 24

    /** 最早条目晚于窗口起点超过此值 → 判定"部分覆盖"（进程重启/缓冲滚动都会造成缺口） */
    private const val COVERAGE_TOLERANCE_MS = 60 * 60 * 1000L

    /** ZIP 条目名（全 ASCII，理由同 [LogModules.fileName]） */
    private const val ENTRY_README = "00-README.txt"
    private const val ENTRY_HISTORY = "10-NOTIFICATION-HISTORY.csv"

    /** 03 正文行的标题/正文截断长度（控行长，避免单条通知占满整屏） */
    private const val HIT_TITLE_MAX = 40
    private const val HIT_CONTENT_MAX = 60

    /** 模块端（system_server）拦截的 decision：后缀 `_MODULE` 才是模块端干的，无后缀是 APP 侧 NLS */
    private val MODULE_DECISIONS = setOf(DECISION_FILTERED_BY_AI_MODULE, DECISION_FILTERED_BY_RULE_MODULE)

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val shortFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val hourFmt = SimpleDateFormat("HH", Locale.US)

    /** 一条日志条目：首行（带时间戳）+ 续行（崩溃栈/诊断段等无时间戳行） */
    private class Entry(val ts: Long, val lines: MutableList<String>)

    /** @return 导出的 zip 文件；失败抛异常由调用方提示 */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val out = File(dir, "NotifyFilter-log-${stampFmt.format(Date(now))}.zip")

        // 通知历史查一次、两处用（03 的模块端回流记录 + 10 的 CSV），不要重复扫表
        val rows = loadHistory(windowStart)
        val hookHits = rows.orEmpty().filter { it.decision in MODULE_DECISIONS }
        val lspStatus = lspStatusLine(context)

        // ---- 先生成各模块文件正文（00 的汇总需要各模块统计） ----
        val logcat = dumpLogcat()
        val logcatByModule = splitLogcat(logcat, windowStart)
        val bodies = LinkedHashMap<String, String>()
        val stats = LinkedHashMap<String, ModStat>()
        for (m in LogModules.ALL) {
            // HOOK 不读环形日志（模块端不写它，文件从未存在），改走回流记录
            if (m == LogModules.HOOK) {
                bodies[m] = buildHookFile(now, windowStart, hookHits, lspStatus)
                continue
            }
            val raw = runCatching { RingLog.dump(m) }.getOrDefault("")
            val entries = parseEntries(raw, windowStart)
            val extra = logcatByModule[m].orEmpty()
            bodies[m] = buildModuleFile(m, now, windowStart, entries, extra)
            stats[m] = ModStat(m, entries.size, extra.size, entries.firstOrNull()?.ts, entries.lastOrNull()?.ts)
        }

        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.putText(ENTRY_README, buildHeader(context, now, windowStart, stats, hookHits, lspStatus, logcat))
            for (m in LogModules.ALL) zos.putText(LogModules.fileName(m), bodies[m].orEmpty())
            zos.putText(ENTRY_HISTORY, historyCsv(rows))
        }
        out
    }

    // ---------------- 00：诊断头 + 覆盖汇总 ----------------

    private suspend fun buildHeader(
        context: Context,
        now: Long,
        windowStart: Long,
        stats: Map<String, ModStat>,
        hookHits: List<NotificationEntity>,
        lspStatus: String,
        logcat: String,
    ): String = buildString {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val settings = io.github.vstory.hook.notifyfilter.ServiceLocator.settings
        val threshold = settings.threshold.first()
        val intercept = settings.interceptMode.first()
        appendLine("==== NotifyFilter 诊断日志（分模块 ZIP）====")
        appendLine("导出时间: ${dayFmt.format(Date(now))}")
        appendLine("APP 版本: ${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("电池豁免: $exempt")
        appendLine("通知监听权限: ${CleanerListenerService.isListenerEnabled(context)}")
        appendLine("监听连接状态: ${CleanerListenerService.isListenerConnected()}")
        appendLine("过滤阈值: $threshold")
        appendLine("拦截模式: ${if (intercept) "拦截" else "仅标记"}")
        appendLine("LSPosed 模块: $lspStatus")
        appendLine()
        appendLine("覆盖窗口: ${dayFmt.format(Date(windowStart))} ~ ${dayFmt.format(Date(now))}（导出前完整 24 小时）")
        appendLine("说明: 01~08 每个模块文件只保留上述窗口内的条目；窗口外的历史已按 24 小时自动裁剪。")
        appendLine()
        appendLine("==== 延迟怎么看 ====")
        appendLine("02 模块（过滤决策管线）里每条通知都带 post= 与 lag=：")
        appendLine("  post = 通知的原始发布时间（sbn.postTime）")
        appendLine("  lag  = post 到「我们收到/处理」的间隔 —— 几十秒以上说明是系统投递积压")
        appendLine("        （灭屏或后台冻结时系统会压着不投递，亮屏才补投），此时 App 处理再快也没用")
        appendLine()
        appendLine("==== 文件清单与 24 小时覆盖情况 ====")
        appendLine("命名规则: <两位序号>-<模块 TAG>.<扩展名>（全 ASCII，防解压乱码）；")
        appendLine("          00 = 本文件，01–08 = 八个模块，10 = 通知历史数据表")
        appendLine("$ENTRY_README —— 本文件（状态快照 + 覆盖汇总 + 阅读指引）")
        for (m in LogModules.ALL) {
            val body = if (m == LogModules.HOOK) {
                "模块端拦截记录 ${hookHits.size} 条（数据库回流）"
            } else {
                stats[m]?.summaryLine(windowStart) ?: "（无统计）"
            }
            appendLine("${LogModules.fileName(m)} —— ${LogModules.title(m)}｜$body")
        }
        appendLine("$ENTRY_HISTORY —— 最近 24 小时通知历史（数据库记录）：时间/应用/包名/通道/标题/**正文**/决策/AI率/已学习")
        appendLine("      注：CSV 与 02 模块日志含通知标题与正文片段；03 含模块端拦截记录（标题 + 截断后的正文）；")
        appendLine("      分享前请留意其中可能带有验证码、金额等敏感内容")
        appendLine()
        appendLine("==== 模块对照表 ====")
        for (m in LogModules.ALL) {
            appendLine("${LogModules.fileName(m)}\n    ${LogModules.desc(m)}")
        }
        appendLine()
        appendLine("==== logcat 抓取范围（仅以下 tag，其余静默）====")
        appendLine(LogModules.LOGCAT_TAGS.joinToString(", "))
        appendLine("      模块端（system_server）日志不在其中：无提权读不到它的 logcat 行，见 03 文件尾部说明。")
        appendLine("logcat 原始行数: ${logcat.lineSequence().count()}")
        appendLine("注意: 系统 logcat 缓冲通常不足 24 小时，各模块文件末尾的「logcat 补充段」仅供参考；")
        appendLine("      24 小时覆盖以分模块环形日志为准（filesDir/logs/ring/<TAG>.log，崩溃/重启不丢失）。")
    }

    // ---------------- 01..09：模块文件 ----------------

    private fun buildModuleFile(
        module: String,
        now: Long,
        windowStart: Long,
        entries: List<Entry>,
        logcatLines: List<String>,
    ): String = buildString {
        appendLine("==== NotifyFilter 诊断日志 · 模块：${LogModules.title(module)} [$module] ====")
        appendLine("模块内容: ${LogModules.desc(module)}")
        appendLine("APP 版本: ${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("导出时间: ${dayFmt.format(Date(now))}")
        appendLine("覆盖窗口: ${dayFmt.format(Date(windowStart))} ~ ${dayFmt.format(Date(now))}（完整 24 小时）")
        val first = entries.firstOrNull()?.ts
        val last = entries.lastOrNull()?.ts
        appendLine("条目数: ${entries.size}（环形日志） + ${logcatLines.size}（logcat 补充）")
        appendLine(
            "时间范围: " +
                (if (first != null) shortFmt.format(Date(first)) else "（无）") +
                " ~ " + (if (last != null) shortFmt.format(Date(last)) else "（无）"),
        )
        appendLine("覆盖状态: ${coverageText(entries, windowStart)}")
        if (entries.isNotEmpty()) appendLine("逐时分布: ${hourlyDistribution(entries, windowStart)}")
        appendLine("----------------------------------------------------------------")
        if (entries.isEmpty()) {
            appendLine("（本模块在 24 小时窗口内无记录——该模块期间无事件发生，属正常）")
        } else {
            for (e in entries) {
                appendLine(e.lines.joinToString("\n"))
            }
        }
        appendLine()
        appendLine("==== 补充：logcat 快照（tag=${logcatTagDesc(module)}）====")
        appendLine("系统 logcat 缓冲通常不足 24 小时，本段仅作补充，不代表完整 24 小时覆盖。")
        appendLine("----------------------------------------------------------------")
        if (logcatLines.isEmpty()) {
            appendLine("（窗口内无 logcat 记录）")
        } else {
            logcatLines.forEach { appendLine(it) }
        }
    }

    /**
     * 03：模块端回流记录。**不读环形日志**——模块端在 system_server 内不写它（`RingLog` 只在
     * APP 进程 init，`MainHook` 不引用），该模块的 ring 文件从未被创建过，按普通路径生成只会得到
     * 一个恒空文件（排查时会被误读成"模块没激活"）。内容改由历史库中 decision 带 `_MODULE`
     * 后缀的记录拼成：模块拦下的通知经 ModuleLogProvider 回流入库，这里是它们的导出落点。
     */
    private fun buildHookFile(
        now: Long,
        windowStart: Long,
        hits: List<NotificationEntity>,
        lspStatus: String,
    ): String = buildString {
        appendLine("==== NotifyFilter 诊断日志 · 模块：${LogModules.title(LogModules.HOOK)} [${LogModules.HOOK}] ====")
        appendLine("模块内容: ${LogModules.desc(LogModules.HOOK)}")
        appendLine("APP 版本: ${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("导出时间: ${dayFmt.format(Date(now))}")
        appendLine("覆盖窗口: ${dayFmt.format(Date(windowStart))} ~ ${dayFmt.format(Date(now))}（完整 24 小时）")
        appendLine("LSPosed 模块: $lspStatus")
        val ai = hits.count { it.decision == DECISION_FILTERED_BY_AI_MODULE }
        appendLine("模块端拦截记录: ${hits.size} 条（AI $ai / 规则 ${hits.size - ai}）")
        appendLine("----------------------------------------------------------------")
        if (hits.isEmpty()) {
            appendLine("（24 小时窗口内模块端未拦截任何通知——该期间没有命中，属正常）")
            appendLine("  注意：「本模块无记录」与「模块未激活」是两件事，模块状态看上一行的 LSPosed 模块。")
        } else {
            // 旧在前：与 01/02 的时间线读法一致（listSince 是倒序，此处反转）
            for (n in hits.asReversed()) appendLine(hookHitLine(n))
        }
        appendLine()
        appendLine("==== 补充：模块端框架层日志在哪 ====")
        appendLine("本文件装的是「拦截记录回流」，不含模块端日志。模块端日志（api102 module loaded /")
        appendLine("installHooks done / decide failed 等）的权威出口是 LSPosed 管理器 → 日志，")
        appendLine("筛 tag ${ModuleLogger.TAG}。APP 侧无提权读不到 system_server 的 logcat 行，")
        appendLine("故本 ZIP 不含该日志的副本。")
    }

    /** 一条模块端拦截记录：`时间  应用(包名)  通道:x  决策  标题  正文` */
    private fun hookHitLine(n: NotificationEntity): String {
        val decision = if (n.decision == DECISION_FILTERED_BY_AI_MODULE) {
            "AI ${(n.adProbability * 100).toInt()}%"
        } else {
            "规则"
        }
        val channel = n.channelName.ifBlank { n.channelId }.ifBlank { "-" }
        return "${shortFmt.format(Date(n.postTime))}  ${n.appName}(${n.packageName})  " +
            "通道:$channel  $decision  标题:${oneLine(n.title, HIT_TITLE_MAX)}  " +
            "正文:${oneLine(n.content, HIT_CONTENT_MAX)}"
    }

    /** 单行化并截断（换行会破坏"一行一条"的排版，超长会把单条通知拉满整屏） */
    private fun oneLine(s: String, max: Int): String {
        val flat = s.replace('\n', ' ').replace('\r', ' ').trim()
        return when {
            flat.isEmpty() -> "-"
            flat.length <= max -> flat
            else -> flat.takeCodepoints(max) + "…"
        }
    }

    /**
     * LSPosed 模块激活状态（回答"模块到底在跑吗"这个导出的常见第一问）。
     * 三态判定复用 [KeepAliveManager.isLspActive] 的证据链，这里只补心跳细节供显示。
     * 检测不到时**不写"未激活"**——`isLspActive()` 的既有纪律：检测不到 ≠ 未激活。
     */
    private fun lspStatusLine(context: Context): String {
        val active = runCatching { KeepAliveManager(context).isLspActive() }.getOrNull()
        if (active != true) {
            return "未检测到（模块未启用 / LSPosed 未运行 / 本机检测不到；请在 LSPosed 管理器确认）"
        }
        val heartbeat = runCatching {
            val prefs = context.getSharedPreferences(
                ModuleLogProvider.LSP_HEARTBEAT_PREFS,
                Context.MODE_PRIVATE,
            )
            val at = prefs.getLong(ModuleLogProvider.KEY_LAST_ALIVE, 0L)
            if (at <= 0L) {
                null
            } else {
                "${dayFmt.format(Date(at))} ${prefs.getString(ModuleLogProvider.KEY_LAST_PROCESS, "").orEmpty()}"
            }
        }.getOrNull()
        return if (heartbeat != null) {
            "活跃（最后心跳 $heartbeat）"
        } else {
            "活跃（框架服务已绑定，尚无 system_server 心跳）"
        }
    }

    private fun logcatTagDesc(module: String): String {
        val tags = mutableListOf<String>()
        LogModules.logcatTagFor(module)?.let { tags += it }
        if (module == LogModules.KEEP) tags += "NfWatch(看门狗相关行)"
        return if (tags.isEmpty()) "（该模块无独立 logcat 来源）" else tags.joinToString(", ")
    }

    /** 覆盖状态判定：空 → 无记录；最早条目晚于窗口起点 1h 以上 → 部分覆盖 */
    private fun coverageText(entries: List<Entry>, windowStart: Long): String {
        if (entries.isEmpty()) return "—（24 小时窗口内本模块无事件）"
        val first = entries.first().ts
        val gap = first - windowStart
        if (gap <= COVERAGE_TOLERANCE_MS) {
            return "✓ 完整覆盖 24 小时（最早条目距窗口起点 ${fmtGap(gap)}）"
        }
        return "⚠ 部分覆盖：最早条目距窗口起点 ${fmtGap(gap)} —— 常见原因：进程重启清空内存缓冲、" +
            "环形缓冲容量滚动（本模块 24 小时内写入超 1MB）、或该模块近期才启用"
    }

    private fun fmtGap(ms: Long): String {
        val min = ms / 60_000
        return if (min < 60) "${min}分钟" else "${min / 60}小时${min % 60}分钟"
    }

    /** 逐时分布：窗口起点起的 24 个 1 小时槽，格式 `HH:mm:count` */
    private fun hourlyDistribution(entries: List<Entry>, windowStart: Long): String {
        val buckets = IntArray(BUCKETS)
        for (e in entries) {
            val idx = ((e.ts - windowStart) / 3_600_000L).toInt().coerceIn(0, BUCKETS - 1)
            buckets[idx]++
        }
        return buildString {
            for (i in 0 until BUCKETS) {
                if (i > 0) append(" | ")
                append("${hourFmt.format(Date(windowStart + i * 3_600_000L))}时:${buckets[i]}")
            }
        }
    }

    private class ModStat(
        val module: String,
        val ringCount: Int,
        val logcatCount: Int,
        val first: Long?,
        val last: Long?,
    ) {
        fun summaryLine(windowStart: Long): String {
            val range = if (first != null && last != null) {
                "${shortFmt.format(Date(first))} ~ ${shortFmt.format(Date(last))}"
            } else {
                "无记录"
            }
            val flag = if (ringCount == 0) "" else {
                if (first!! - windowStart <= COVERAGE_TOLERANCE_MS) " [完整覆盖]" else " [⚠ 部分覆盖]"
            }
            return "条目 $ringCount（+logcat $logcatCount）｜$range$flag"
        }
    }

    // ---------------- 解析与过滤 ----------------

    /**
     * 把模块原始文本切成条目并**只保留 24 小时窗口内的条目**。
     * 首行带 `MM-dd HH:mm:ss.SSS` 时间戳 = 新条目；其后的无时间戳行（崩溃栈、诊断段）
     * 作为续行并入该条目——保证多行的崩溃栈不会被拆散。
     */
    private fun parseEntries(raw: String, windowStart: Long): List<Entry> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Entry>()
        var current: Entry? = null
        var currentKept = false
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val ts = RingLog.parseLeadingTs(line)
            if (ts != null) {
                currentKept = ts >= windowStart
                if (currentKept) {
                    val e = Entry(ts, mutableListOf(line))
                    out.add(e)
                    current = e
                } else {
                    current = null
                }
            } else {
                // 续行：归属上一条目（上一条目若在窗口外则丢弃）
                if (currentKept) current?.lines?.add(line)
            }
        }
        return out
    }

    /**
     * logcat 行按 tag 归到模块，并过滤到 24 小时窗口内。
     * 行格式（`-v time`）：`09-28 23:32:07.474 I/NfWatch(23522): message`
     */
    private fun splitLogcat(logcat: String, windowStart: Long): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        val re = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\w/([^(:]+)\\(\\s*\\d+\\):\\s?(.*)$")
        for (line in logcat.lineSequence()) {
            if (line.isBlank() || line.startsWith("---------")) continue
            val m = re.find(line) ?: continue
            val ts = RingLog.parseLeadingTs(m.groupValues[1])
            if (ts == null || ts < windowStart) continue
            val tag = m.groupValues[2].trim() // 部分系统 tag 带尾部空格（如 "HWUI    "）
            val msg = m.groupValues[3]
            val module = moduleOfTag(tag, msg) ?: continue
            out.getOrPut(module) { ArrayList() }.add(line)
        }
        return out
    }

    private fun moduleOfTag(tag: String, msg: String): String? = when (tag) {
        "UpdateVM" -> LogModules.UPDATE
        "ModelRepository" -> LogModules.MODEL
        "NfWatch" -> {
            // 看门狗/保活相关行归 KEEP，其余（监听回调/补扫）归 NLS
            val low = msg.lowercase()
            if (low.contains("alarm") || low.contains("repair") || low.contains("fired") ||
                low.contains("watchdog") || low.contains("purge") || low.contains("goasync")
            ) {
                LogModules.KEEP
            } else {
                LogModules.NLS
            }
        }
        else -> null
    }

    // ---------------- 通知历史 ----------------

    /**
     * 最近 24 小时的通知历史。查询失败返回 null（与「窗口内确实无记录」的空列表区分开）——
     * 03 的模块端回流记录与 10 的 CSV 共用这一份结果，不各自扫表。
     */
    private suspend fun loadHistory(windowStart: Long): List<NotificationEntity>? = runCatching {
        io.github.vstory.hook.notifyfilter.ServiceLocator.db.notificationDao().listSince(windowStart)
    }.getOrNull()

    /** 24 小时通知历史 CSV（可直接用表格软件打开） */
    private fun historyCsv(rows: List<NotificationEntity>?): String {
        if (rows == null) return "(通知历史读取失败)\n"
        // Dev 11：UTF-8 BOM——CSV 是纯 UTF-8，Excel/WPS 在中文 Windows 上按 GBK 打开即乱码
        //（历史通知 CSV 导出 HistoryCsvExporter 一直写 BOM，Dev 9 新增的本文件漏了）
        // Dev 12：补「通知正文」+「通知通道」——只有标题时无法判断"这条为什么被判广告"
        //（判断依据是 title+content 合流后的文本，通道还会带来偏置权重）
        val header = "时间,应用,包名,通知通道,通知标题,通知正文,决策,AI率,已学习"
        if (rows.isEmpty()) return "\ufeff$header\r\n（最近 24 小时无通知记录）\r\n"
        return buildString {
            append("\ufeff")
            append(header).append("\r\n")
            for (n in rows) {
                // CRLF：与表头一致，Excel/WPS 打开不串行
                append(
                    listOf(
                        shortFmt.format(Date(n.postTime)),
                        n.appName,
                        n.packageName,
                        n.channelName.ifBlank { n.channelId }.ifBlank { "-" },
                        n.title.replace("\n", " "),
                        n.content.replace("\n", " "),
                        n.decision,
                        "${(n.adProbability * 100).toInt()}%",
                        if (n.learned) "是(${if (n.learnLabel == 1) "广告" else "正常"})" else "否",
                    ).joinToString(",") { csv(it) },
                )
                append("\r\n")
            }
        }
    }

    private fun csv(v: String): String {
        val s = v.replace("\"", "\"\"")
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"$s\"" else s
    }

    /**
     * logcat 快照：仅自身 UID 日志可见（无需权限），且只抓 [LogModules.LOGCAT_TAGS]，
     * 其余一律静默（旧写法 `TAG:*` 在部分设备上被判为非法过滤式 → 退化成全量系统噪声）。
     */
    private fun dumpLogcat(): String = try {
        val cmd = mutableListOf("logcat", "-d", "-v", "time")
        LogModules.LOGCAT_TAGS.forEach { cmd += "$it:V" }
        cmd += "*:S"
        val p = Runtime.getRuntime().exec(cmd.toTypedArray())
        val stdout = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        stdout
    } catch (t: Throwable) {
        "logcat 读取失败: $t\n"
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
