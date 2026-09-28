package cc.ytdttj.noticleaner.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.ai.takeCodepoints
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import kotlinx.coroutines.runBlocking

/**
 * 模块拦截记录回流入口（1.2.1）：
 * LSPosed 模块在 system_server 内拦截的通知经此写入历史库——
 * 入队前拦截意味着 NLS 收不到这些通知，回流保证历史完整 + 可继续学习标注。
 * exported=true（system_server 访问需要），调用方仅接受 SYSTEM_UID 与自身（对齐 ref/Notice）；
 * 例外：HOOK_LOG 决策额外接受白名单进程（SystemUI / xmsf，Dev 10 修复，见 [HOOK_LOG_DECISION]）。
 */
class ModuleLogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val uid = Binder.getCallingUid()
        values ?: return null
        val ctx = context ?: return null
        // Dev 10（P2-36 升级修复）：HOOK_LOG 通道单独判定——SystemUI(uid 101xx) 与
        // xmsf(普通 uid) 都不是 SYSTEM_UID(1000)，旧校验把它们**静默拒绝**了，
        // Dev 8 宣称的"Hook 事件回流"实际零产出（用户导出日志里 [Hook: 行 = 0 条实证）。
        // HOOK_LOG 不入通知历史库、只写环形日志（有折叠 + 每模块配额），
        // 因此可以按包名白名单放宽；其余决策维持原有严格校验。
        val decision = values.getAsString(COL_DECISION).orEmpty()
        val hookChannel = decision == HOOK_LOG_DECISION
        if (uid != Process.SYSTEM_UID && uid != Process.myUid() &&
            !(hookChannel && isHookLogUidAllowed(ctx, uid))
        ) {
            return null
        }

        // Dev 5：LSPosed 模块心跳（decision=LSP_ALIVE）——不入历史库，
        // 写独立偏好文件供 KeepAliveManager.isLspActive() 作"模块真实在跑"的铁证
        if (decision == LSP_ALIVE_DECISION) {
            runCatching {
                ctx.getSharedPreferences(LSP_HEARTBEAT_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_alive", values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis())
                    .putString("last_process", values.getAsString(COL_TITLE).orEmpty())
                    .apply()
            }
            return null
        }

        // Dev 8：Hook 端日志回流（decision=HOOK_LOG）——SystemUI/xmsf/system_server
        // 内的关键事件（岛校验/认证/拦截）写环形日志，随诊断导出覆盖 24 小时
        if (hookChannel) {
            runCatching {
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.HOOK,
                    // 进程名（SystemUI / xmsf / system_server）保留在正文里——模块标签已由
                    // HOOK 段承担，正文只补"来自哪个进程"，排查时按进程 grep 仍然可行
                    "[${values.getAsString(COL_PACKAGE).orEmpty()}] " +
                        values.getAsString(COL_TITLE).orEmpty() +
                        (values.getAsString(COL_CONTENT)?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""),
                )
            }
            return null
        }

        val entity = NotificationEntity(
            packageName = values.getAsString(COL_PACKAGE).orEmpty(),
            appName = appName(ctx, values.getAsString(COL_PACKAGE).orEmpty()),
            channelId = values.getAsString(COL_CHANNEL).orEmpty(),
            channelName = values.getAsString(COL_CHANNEL).orEmpty(),
            // 1.3.2（P1-2）：与 NLS 入库路径同语义截断 500 codepoint（跨路径去重需两端一致）
            title = values.getAsString(COL_TITLE).orEmpty()
                .takeCodepoints(cc.ytdttj.noticleaner.ai.FeatureHasher.MAX_TEXT_LEN),
            content = values.getAsString(COL_CONTENT).orEmpty()
                .takeCodepoints(cc.ytdttj.noticleaner.ai.FeatureHasher.MAX_TEXT_LEN),
            postTime = values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis(),
            adProbability = values.getAsFloat(COL_PROBABILITY) ?: 0f,
            decision = values.getAsString(COL_DECISION).orEmpty(),
            expireAt = (values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis()) + EXPIRE_MS,
            key = values.getAsString(COL_KEY).orEmpty(),
        )
        if (entity.packageName.isBlank() || entity.decision.isBlank()) return null

        runBlocking {
            val dao = ServiceLocator.db.notificationDao()
            // 1.3.2（P1-1）：与 NLS 路径共用单事务槽位写入（原三趟独立事务 + 无锁 → 单事务）
            val outcome = dao.upsertSlot(entity, entity.postTime - DEDUP_MS)
            if (outcome?.inserted == true) {
                // 模块拦截也计入常驻通知统计（与 NLS 路径一致；仅新插入计数）
                when (entity.decision) {
                    "FILTERED_BY_AI_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = true)
                    "FILTERED_BY_RULE_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = false)
                }
            }
        }
        return uri.buildUpon().appendPath(entity.key).build()
    }

    /**
     * HOOK_LOG 通道的 uid 白名单判定（带缓存）。
     *
     * `getPackagesForUid` 是一次 IPC，而 Hook 事件可能高频（每次上岛/认证都打点），
     * 故按 uid 缓存结果（含 false——否则任何第三方应用都能用高频调用反复触发 IPC）。
     * uid 只在应用重装/多用户切换时变化；缓存上限 [HOOK_UID_CACHE_MAX] 条，超出即整体清空。
     */
    private fun isHookLogUidAllowed(ctx: Context, uid: Int): Boolean {
        hookUidCache[uid]?.let { return it }
        val ok = runCatching {
            val pkgs = ctx.packageManager.getPackagesForUid(uid)
            pkgs?.any { it in HOOK_LOG_ALLOWED_PACKAGES } == true
        }.getOrDefault(false)
        if (hookUidCache.size > HOOK_UID_CACHE_MAX) hookUidCache.clear()
        hookUidCache[uid] = ok
        return ok
    }

    private fun appName(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.cc.ytdttj.noticleaner.modulelog"

    companion object {
        const val AUTHORITY = "cc.ytdttj.noticleaner.modulelog"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/log")

        /** Dev 5：模块心跳决策标记（system_server 内 hook 成功后回写，不入库） */
        const val LSP_ALIVE_DECISION = "LSP_ALIVE"
        const val LSP_HEARTBEAT_PREFS = "lsp_heartbeat"

        /** Dev 8：Hook 端日志回流标记（SystemUI/xmsf 进程关键事件 → RingLog，不入库） */
        const val HOOK_LOG_DECISION = "HOOK_LOG"

        /**
         * Dev 10：HOOK_LOG 通道额外放行的进程包名。
         * 这些进程内的 hook（IslandUnlockFocusHook / XmsfUnlockAuthHook）以**被注入进程的 uid**
         * 提交（SystemUI 101xx、xmsf 普通 uid），不是 SYSTEM_UID(1000)——只放行这两个已知
         * 系统/厂商进程，且仅限 HOOK_LOG 决策（不入历史库、只进有配额的环形日志）。
         */
        private val HOOK_LOG_ALLOWED_PACKAGES = setOf("com.android.systemui", "com.xiaomi.xmsf")

        /** uid → 是否允许走 HOOK_LOG 通道（见 [isHookLogUidAllowed]） */
        private val hookUidCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        private const val HOOK_UID_CACHE_MAX = 64

        const val COL_PACKAGE = "package"
        const val COL_CHANNEL = "channel"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_POST_TIME = "post_time"
        const val COL_PROBABILITY = "probability"
        const val COL_DECISION = "decision"
        const val COL_KEY = "key"

        private const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000
        private const val DEDUP_MS = 60_000L
    }
}
