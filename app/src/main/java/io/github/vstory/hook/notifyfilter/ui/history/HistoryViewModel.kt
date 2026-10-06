package io.github.vstory.hook.notifyfilter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.ModelRepository
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MANUAL_MARKED_AD
import io.github.vstory.hook.notifyfilter.data.db.DECISION_PASSED
import io.github.vstory.hook.notifyfilter.data.db.FILTERED_DECISIONS
import io.github.vstory.hook.notifyfilter.data.db.NotificationDao
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 三态：同 key 最新版本还在不在通知栏，与「被后到通知覆盖」三者互斥 */
enum class HistoryTab { VISIBLE, DISMISSED, HISTORY }

/** 决策维度：Tab 位让给三态后下沉进高级筛选 */
enum class DecisionFilter { ALL, FILTERED, PASSED }

/**
 * 历史高级筛选（Dev 6）：决策维度 + App（多选，复用规则页 AppPicker，含系统应用）+
 * 仅看已学习 + 日期范围（含起止当天）。任一条件为空/关闭即不参与过滤。
 */
data class HistoryAdvancedFilter(
    val decision: DecisionFilter = DecisionFilter.ALL,
    val apps: List<Pair<String, String>> = emptyList(), // pkg to label；空 = 全部 App
    val learnedOnly: Boolean = false,
    val startDate: java.time.LocalDate? = null,
    val endDate: java.time.LocalDate? = null,
) {
    val isDefault: Boolean
        get() = decision == DecisionFilter.ALL && apps.isEmpty() && !learnedOnly &&
            startDate == null && endDate == null
}

/**
 * 历史列表 + 详情页的共享 VM（详情含通道跳转与 AI 学习，Plan.md §6.1）。
 *
 * 学习机制（1.1.0，复刻 Notice）：
 * 标注写入 Room（learned/learnLabel）→ 用【全部标注】在冻结 base 上全量重拟合
 * 稀疏 delta（SpamTuner.fit，60 epoch）→ delta 独立落盘并叠加到生效模型。
 * base 权重永不被改写；删除标注后重新拟合即精确回滚；基线模型升级后自动重拟合。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class HistoryViewModel(
    private val dao: NotificationDao,
    private val modelRepo: ModelRepository,
) : ViewModel() {

    val tab = MutableStateFlow(HistoryTab.VISIBLE)

    /**
     * 搜索模式：展开搜索即跨全部三态查询——用户在「正显示」里搜一条已被拦掉的通知，
     * 也得搜得到，否则不知道它落在哪个状态里就等于白搜。非搜索模式仍只查当前 tab，
     * 保住 1.3.2 的按需查询（日常不进搜索就不查三态并集）。
     */
    val searchMode = MutableStateFlow(false)

    /** 历史搜索（匹配 App 名称/标题/内容，忽略大小写） */
    val search = MutableStateFlow("")

    /**
     * 收起搜索并清空搜索词。清空必须显式做：miuix InputField 只在 `expanded` 由 true 变 false
     * 且仍在组合树中时自行清空，而本屏收起是把整个 SearchBar 移出组合树。
     */
    fun setSearchMode(on: Boolean) {
        searchMode.value = on
        if (!on) search.value = ""
    }

    /** 高级筛选（Dev 6）：App（多选）/ 已学习 / 日期范围 */
    val advancedFilter = MutableStateFlow(HistoryAdvancedFilter())

    /**
     * 历史列表（1.3.2 P2-5：三态下推 SQL）——tab / 搜索模式 / 搜索词一起作为 flatMapLatest 的键，
     * 每次 DB 变更只重查当前查询的行。搜索词下推 SQL 后 LIMIT 才是「结果条数上限」：
     * 此前它被三态并集的 LIMIT 500 当候选集用，窗口只有最近几分钟，18 分钟前的通知搜不到。
     * 搜索词只 debounce 一处（在内层），两处 debounce 会各取各的值、键与数据不一致。
     * 决策维度（原「已过滤」tab）与 App/已学习/日期仍在窗口内内存过滤——三态 × 决策共 9 组，
     * 不值得为 SQL 下推铺 9 个查询。
     */
    val list: StateFlow<List<NotificationEntity>> =
        combine(
            combine(tab, searchMode, search.debounce(200)) { t, sm, q -> Triple(t, sm, q) }
                .distinctUntilChanged()
                .flatMapLatest { (t, sm, q) ->
                    if (!sm) {
                        when (t) {
                            HistoryTab.VISIBLE -> dao.listVisible()
                            HistoryTab.DISMISSED -> dao.listDismissed()
                            HistoryTab.HISTORY -> dao.listHistory()
                        }
                    } else if (q.isBlank()) {
                        dao.listAllStates()
                    } else {
                        // SQL 结果当粗筛再按 Unicode 语义收一遍：LIKE 只对 ASCII 忽略大小写
                        dao.searchAllStates(likePattern(q))
                            .map { rows -> rows.filter { matches(q, it) } }
                    }
                },
            advancedFilter,
        ) { rows, adv -> if (adv.isDefault) rows else applyAdvanced(rows, adv) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `\` → `\\`、`%` → `\%`、`_` → `\_`；不转义时搜 `%` 会命中整表 */
    private fun likePattern(q: String): String =
        "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun matches(q: String, n: NotificationEntity): Boolean =
        n.appName.contains(q, true) || n.title.contains(q, true) ||
            n.content.contains(q, true) || n.packageName.contains(q, true)

    private fun applyAdvanced(
        rows: List<NotificationEntity>,
        adv: HistoryAdvancedFilter,
    ): List<NotificationEntity> {
        val zone = java.time.ZoneId.systemDefault()
        val startMs = adv.startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val endMs = adv.endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        return rows.filter { n ->
            decide(adv.decision, n) &&
                (adv.apps.isEmpty() || adv.apps.any { it.first == n.packageName }) &&
                (!adv.learnedOnly || n.learned) &&
                (startMs == null || n.postTime >= startMs) &&
                (endMs == null || n.postTime < endMs)
        }
    }

    private fun decide(f: DecisionFilter, n: NotificationEntity): Boolean = when (f) {
        DecisionFilter.ALL -> true
        DecisionFilter.FILTERED -> n.decision in FILTERED_DECISIONS
        // "正常"= 未被过滤（含白名单/媒体/会话/常驻等保护型通知）
        DecisionFilter.PASSED -> n.decision !in FILTERED_DECISIONS
    }

    fun setAdvancedFilter(f: HistoryAdvancedFilter) {
        advancedFilter.value = f
    }

    private val _selected = MutableStateFlow<NotificationEntity?>(null)
    val selected: StateFlow<NotificationEntity?> = _selected

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    fun select(n: NotificationEntity?) {
        _selected.value = n
    }

    fun setTab(t: HistoryTab) {
        tab.value = t
    }

    fun clearToast() {
        _toast.value = null
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            modelRepo.get() // 预热模型加载
            // 基线模型升级（assets 更新）后：用既有标注对新基线自动重新拟合
            val labels = dao.listLearnedOnce()
            if (labels.isNotEmpty() && modelRepo.tunedFingerprint() != modelRepo.baseFingerprint()) {
                refit()
            }
        }
    }

    /**
     * 学习标注（写入标注集后全量重拟合）。学习为广告的同时清除通知栏中的该通知。
     * 1.1.11：允许对已学习通知重复学习——同方向重复点击累积 learnCount，
     * 拟合时按重复次数加权（大幅提升权重）；换方向则重置计数。
     */
    fun learn(n: NotificationEntity, label: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val sameDirection = n.learned && n.learnLabel == label
            dao.update(
                n.copy(
                    learned = true,
                    learnLabel = label,
                    learnCount = if (sameDirection) n.learnCount + 1 else 1,
                    decision = if (label == 1) DECISION_MANUAL_MARKED_AD else DECISION_PASSED,
                ),
            )
            if (label == 1 && n.key.isNotEmpty()) {
                io.github.vstory.hook.notifyfilter.notify.CleanerListenerService.cancelByKey(n.key)
            }
            val updated = refit()
            _selected.value = updated.firstOrNull { it.id == n.id }
                ?: n.copy(learned = true, learnLabel = label, learnCount = if (sameDirection) n.learnCount + 1 else 1)
            // 2.0.1 Dev 11：学为"正常"时若该通知仍命中用户自定义规则，规则会继续拦截
            //（规则判定优先于 AI 打分）——不提示的话用户只会觉得"学了没用"
            val hitRule = if (label == 0) {
                runCatching {
                    io.github.vstory.hook.notifyfilter.ServiceLocator.ruleEngine
                        .match(n.packageName, n.title, n.content)
                }.getOrNull()
            } else {
                null
            }
            _toast.value = when {
                label == 1 && sameDirection -> "已重复学习（第 ${n.learnCount + 1} 次），权重已加强"
                label == 1 -> "已学习为广告通知并清除"
                hitRule != null ->
                    "已学习为正常通知；但该通知命中规则「${hitRule.appName}｜${hitRule.keyword.ifBlank { "条件规则" }}」，" +
                        "规则优先于学习，仍会被拦截——请到「规则」页调整或删除该规则"
                else -> "已学习为正常通知"
            }
        }
    }

    /** 取消学习：从标注集移除后重新拟合，精确回滚 */
    fun unlearn(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            dao.update(n.copy(learned = false, learnLabel = -1, learnCount = 0))
            val updated = refit()
            _selected.value = updated.firstOrNull { it.id == n.id } ?: n.copy(learned = false, learnLabel = -1, learnCount = 0)
            _toast.value = "已取消学习"
        }
    }

    /**
     * 用全部标注在冻结 base 上重新拟合稀疏 delta，叠加到生效模型，
     * 并刷新所有已学习行的概率展示。@return 重算后的已学习行。
     * 1.1.11：样本携带通道特征（同 App 同渠道偏置）与重复学习权重。
     */
    private suspend fun refit(): List<NotificationEntity> {
        val labels = dao.listLearnedOnce()
        if (labels.isNotEmpty()) {
            _toast.value = "正在拟合 ${labels.size} 条标注…" // P2-4：长拟合进度反馈，防"假死"
        }
        // 1.4.0 Dev 13：重拟合抽到 LearningHelper——统计明细页「全部学习」复用同一实现
        val updated = io.github.vstory.hook.notifyfilter.data.LearningHelper.refit(dao, modelRepo)
        // Dev 14：拟合完成提示（此前只有"正在拟合…"，用户不知道何时结束）
        if (labels.isNotEmpty()) {
            _toast.value = "拟合完成（${labels.size} 条标注）"
        }
        _selected.value = _selected.value?.let { sel -> updated.firstOrNull { it.id == sel.id } ?: sel }
        return updated
    }
}
