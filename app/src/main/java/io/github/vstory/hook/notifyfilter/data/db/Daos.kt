package io.github.vstory.hook.notifyfilter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Update
    suspend fun update(n: NotificationEntity)

    /**
     * 槽位写入（1.3.2 P1-1，2.2.0 加版本语义）：同 key 已有当前版本时，
     * 旧行 seq+1 退位成历史版本，新数据以 seq = 0 入表（照通知滤盒的 history.seq 机制）。
     * 两处写入路径（NLS handle / ModuleLogProvider）共用。
     * @return 该 key 此前已有记录（旧版已退位）→ [SlotOutcome]（携带原 decision，供拦截计数）；
     *         该 key 首次出现 → [SlotOutcome]（previousDecision=null, inserted=true）；
     *         60s 去重命中跳过 → null
     */
    @Transaction
    suspend fun upsertSlot(n: NotificationEntity, dedupSince: Long): SlotOutcome? {
        // P2-2：去重哈希在写入时统一计算（两条路径同步刷新，保证存量行哈希与内容一致）
        val hash = contentHashOf(n.title, n.content)
        val existing = findByKey(n.key)
        if (existing != null) {
            // 只改 seq：dismissTime 留给三态判定，历史版本不参与「还在不在通知栏」
            update(existing.copy(seq = existing.seq + 1))
            // 不能在这里重置 dismissTime/dismissReason：两条写入路径都已在构造时按
            // 「这条是否进过通知栏」置位（拦截类直接落 postTime），退位的新版本必须沿用，
            // 否则被拦的通知会被判成「正显示」
            insert(n.copy(contentHash = hash, seq = 0))
            trimVersions(n.key, MAX_VERSIONS_PER_KEY)
            return SlotOutcome(previousDecision = existing.decision, inserted = false)
        }
        val dup = findRecentDuplicate(n.packageName, hash, dedupSince)
        if (dup != null) return null
        insert(n.copy(contentHash = hash))
        return SlotOutcome(previousDecision = null, inserted = true)
    }

    /** 版本裁剪：同 key 只留 seq < keep 的版本，已学习的行保留（标注集不能丢） */
    @Query("DELETE FROM notifications WHERE `key` = :key AND seq >= :keep AND learned = 0")
    suspend fun trimVersions(key: String, keep: Int)

    /** 撤销登记（NLS onNotificationRemoved）：只标当前版本，已标过的不覆盖 */
    @Query(
        "UPDATE notifications SET dismissTime = :time, dismissReason = :reason " +
            "WHERE `key` = :key AND seq = 0 AND dismissTime = -1",
    )
    suspend fun markDismissed(key: String, time: Long, reason: Int)

    /** 重连校正：升级回填/进程重启后误标为已取消、实际仍在通知栏的行改回正显示 */
    @Query(
        "UPDATE notifications SET dismissTime = -1, dismissReason = 0 WHERE seq = 0 " +
            "AND dismissTime != -1 AND `key` IN (:keys)",
    )
    suspend fun markPresentVisible(keys: List<String>)

    /** 快照反向校正用：当前声称「正显示」的 key（拦截类天然排除——其 dismissTime = postTime ≥ 0） */
    @Query("SELECT `key` FROM notifications WHERE seq = 0 AND dismissTime = -1")
    suspend fun listVisibleKeys(): List<String>

    /**
     * 快照反向校正（照通知滤盒 a3/c.a(Z) 的 LD3/o case 10）：通知栏快照里已不存在、
     * 却仍标着正显示的当前版本 → 标为已取消。回调停摆期间消失的通知收不到 removed，
     * 只做正向校正会让它们永久卡在「正显示」。
     * dismissTime 取 postTime + 1 而非 now：批量补记不制造「刚刚取消」的假时间线。
     * @return 实际更新行数
     */
    @Query(
        "UPDATE notifications SET dismissTime = postTime + 1, dismissReason = :reason " +
            "WHERE seq = 0 AND dismissTime = -1 AND `key` IN (:keys)",
    )
    suspend fun markGoneFromSnapshot(keys: List<String>, reason: Int): Int

    /** 应用名回填（1.3.2 P0-5）：仅更新"appName 尚为包名"的行，避免覆盖已解析的历史行 */
    @Query("UPDATE notifications SET appName = :appName WHERE packageName = :pkg AND appName = :pkg")
    suspend fun updateAppName(pkg: String, appName: String)

    /** 列表一律只看当前版本（seq = 0）：旧版本归「历史」，不进主列表与统计口径 */
    @Query("SELECT * FROM notifications WHERE seq = 0 AND dismissTime = -1 ORDER BY postTime DESC LIMIT 500")
    fun listVisible(): Flow<List<NotificationEntity>>

    /** 按决策集合查询（1.2.1：模块端拦截的 *_MODULE 决策与 NLS 决策合并展示） */
    @Query("SELECT * FROM notifications WHERE seq = 0 AND decision IN (:decisions) ORDER BY postTime DESC LIMIT 500")
    fun listByDecisions(decisions: List<String>): Flow<List<NotificationEntity>>

    /** 已取消：同 key 最新版本且已被撤销 */
    @Query("SELECT * FROM notifications WHERE seq = 0 AND dismissTime != -1 ORDER BY postTime DESC LIMIT 500")
    fun listDismissed(): Flow<List<NotificationEntity>>

    /** 历史：被后到的同 key 通知覆盖掉的旧版本 */
    @Query("SELECT * FROM notifications WHERE seq > 0 ORDER BY postTime DESC LIMIT 500")
    fun listHistory(): Flow<List<NotificationEntity>>

    /** 三态并集（搜索模式）：可见/已取消/历史互斥且完备，无 WHERE 即全表此刻的最新视图 */
    @Query("SELECT * FROM notifications ORDER BY postTime DESC LIMIT 500")
    fun listAllStates(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE learned = 1 ORDER BY postTime DESC LIMIT 500")
    fun listLearned(): Flow<List<NotificationEntity>>

    /** 学习标注集（一次性读取，供全量重拟合） */
    @Query("SELECT * FROM notifications WHERE learned = 1")
    suspend fun listLearnedOnce(): List<NotificationEntity>

    /** 清空全部学习标注（重置模型用） */
    @Query("UPDATE notifications SET learned = 0, learnLabel = -1 WHERE learned = 1")
    suspend fun clearLearned()

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    /** 同一通知槽位的**当前**版本（seq = 0）；历史版本同 key 也在表里，必须排除 */
    @Query("SELECT * FROM notifications WHERE `key` = :key AND seq = 0 ORDER BY id DESC LIMIT 1")
    suspend fun findByKey(key: String): NotificationEntity?

    /** 60 秒内同 App + 同 contentHash（P2-2：64 位哈希替代整段文本等值）→ 重复推送，不重复入库 */
    @Query(
        "SELECT * FROM notifications WHERE packageName = :pkg AND contentHash = :contentHash " +
            "AND postTime >= :since ORDER BY id DESC LIMIT 1",
    )
    suspend fun findRecentDuplicate(pkg: String, contentHash: Long, since: Long): NotificationEntity?

    @Query(
        "SELECT COUNT(*) FROM notifications WHERE seq = 0 AND decision IN " +
            "('FILTERED_BY_AI','FILTERED_BY_AI_MODULE','FILTERED_BY_RULE','FILTERED_BY_RULE_MODULE')"
    )
    fun filteredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE learned = 1")
    fun learnedCount(): Flow<Int>

    /**
     * 清理保留期外的过期通知（Dev 6：保留天数可调）。
     * 按 postTime 判定（而非写入时固定的 expireAt），改保留天数立即对存量生效；
     * 已学习的标注（learned=1）永不清理，供模型重拟合。
     */
    @Query("DELETE FROM notifications WHERE learned = 0 AND postTime < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    /** CSV 导出（Dev 6）：全量历史通知，按时间倒序 */
    @Query("SELECT * FROM notifications ORDER BY postTime DESC")
    suspend fun exportAll(): List<NotificationEntity>

    /** 诊断导出（Dev 8）：最近 24 小时的通知历史 */
    @Query("SELECT * FROM notifications WHERE postTime >= :since ORDER BY postTime DESC")
    suspend fun listSince(since: Long): List<NotificationEntity>

    @Query("SELECT DISTINCT packageName, appName FROM notifications")
    suspend fun distinctApps(): List<AppRef>
}

data class AppRef(val packageName: String, val appName: String)

/** 每个通知槽位保留的版本数（含当前版本）；进度类通知同 key 高频更新，不设上限会撑爆表 */
const val MAX_VERSIONS_PER_KEY = 10

/** 槽位写入结果（1.3.2 P1-1，[NotificationDao.upsertSlot] 返回值，供拦截计数使用） */
data class SlotOutcome(val previousDecision: String?, val inserted: Boolean)

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun listAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun listEnabled(): List<RuleEntity>
}

@Dao
interface WhitelistDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(item: WhitelistEntity)

    @Query("DELETE FROM whitelist WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT * FROM whitelist ORDER BY createdAt DESC")
    fun listAll(): Flow<List<WhitelistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist WHERE packageName = :packageName)")
    suspend fun contains(packageName: String): Boolean
}
