package io.github.vstory.hook.notifyfilter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vstory.hook.notifyfilter.R
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MANUAL_MARKED_AD
import io.github.vstory.hook.notifyfilter.data.db.DECISION_PASSED
import io.github.vstory.hook.notifyfilter.data.db.MAX_VERSIONS_PER_KEY
import io.github.vstory.hook.notifyfilter.data.db.NotificationDao
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import io.github.vstory.hook.notifyfilter.ui.component.CardItem
import io.github.vstory.hook.notifyfilter.ui.component.groupedCardItems
import io.github.vstory.hook.notifyfilter.ui.history.NotificationDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 1.3.2（P3-6）：SimpleDateFormat（非线程安全）→ java.time DateTimeFormatter（不可变）
private val detailTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatDetailTime(epochMs: Long): String =
    detailTimeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

sealed class StatsMode(val key: String) {
    data object Filtered : StatsMode("filtered")
    data object Learned : StatsMode("learned")

    companion object {
        fun of(key: String): StatsMode = if (key == "learned") Learned else Filtered
    }
}

class StatsDetailViewModel(
    private val dao: NotificationDao,
    private val modelRepo: io.github.vstory.hook.notifyfilter.data.ModelRepository,
) : ViewModel() {
    val filtered = dao.listByDecisions(
        listOf("FILTERED_BY_AI", "FILTERED_BY_AI_MODULE"),
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val filteredByRule = dao.listByDecisions(
        listOf("FILTERED_BY_RULE", "FILTERED_BY_RULE_MODULE"),
    ).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val learned = dao.listLearned().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 1.4.0 Dev 13：批量重新学习（顶部「全部学习」） ----
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun clearToast() {
        _toast.value = null
    }

    // ---- Dev 14：明细条目点击 → 弹层内单条学习/取消学习（与历史页同款交互）----
    private val _selected = MutableStateFlow<NotificationEntity?>(null)
    val selected: StateFlow<NotificationEntity?> = _selected

    /** 详情面板的版本记录，与历史页同源（见 [io.github.vstory.hook.notifyfilter.ui.history.HistoryViewModel].versions） */
    private val _versions = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val versions: StateFlow<List<NotificationEntity>> = _versions

    fun select(n: NotificationEntity?) {
        _selected.value = n
        if (n == null || n.key.isEmpty()) {
            _versions.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching { dao.listVersionsByKey(n.key, MAX_VERSIONS_PER_KEY) }
                .getOrDefault(emptyList())
            // 快速改选时早发出的查询可能后返回，只认仍在选中的那条
            if (_selected.value?.key == n.key) _versions.value = rows
        }
    }

    /** 单条学习：广告(1) / 正常(0)；同方向重复点击累积权重，随后全量重拟合 */
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
            _toast.value = "正在拟合标注…"
            io.github.vstory.hook.notifyfilter.data.LearningHelper.refit(dao, modelRepo)
            _toast.value = when {
                label == 1 && sameDirection -> "已重复学习广告（第 ${n.learnCount + 1} 次）"
                label == 1 -> "已学习为广告通知"
                else -> "已学习为正常通知"
            }
        }
    }

    /** 取消学习：从标注集移除后重新拟合，精确回滚 */
    fun unlearn(n: NotificationEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            dao.update(n.copy(learned = false, learnLabel = -1, learnCount = 0))
            io.github.vstory.hook.notifyfilter.data.LearningHelper.refit(dao, modelRepo)
            _toast.value = "已取消学习"
        }
    }

    /**
     * 按各通知**原有方向**重新学习（广告→广告加强，正常→正常加强）。
     * 2.0.1 Dev 1：**未学习过的条目直接跳过**（不再默认学为广告——
     * 已过滤页躺着大量被误拦的正常通知，默认学广告会把模型带偏）。
     */
    fun relearnAll(items: List<NotificationEntity>) {
        if (items.isEmpty() || _busy.value) return
        viewModelScope.launch(Dispatchers.Default) {
            _busy.value = true
            _toast.value = "正在重新学习…"
            val result = runCatching {
                io.github.vstory.hook.notifyfilter.data.LearningHelper.relearnAll(dao, modelRepo, items)
            }.getOrNull()
            _busy.value = false
            _toast.value = if (result == null) {
                "批量学习失败，请重试"
            } else if (result.adCount + result.normalCount == 0) {
                "没有已学习的条目可重学；未学习的请逐条打开详情选择方向"
            } else {
                buildString {
                    append("已重新学习 ${result.adCount + result.normalCount} 条：广告 ${result.adCount} / 正常 ${result.normalCount}")
                    if (result.skipped > 0) append("；已跳过未学习 ${result.skipped} 条")
                }
            }
        }
    }
}

/** 设置页统计明细（Plan.md §6.3）：已过滤/已学习通知全文列表 */
@Composable
fun StatsDetailScreen(
    modeKey: String,
    onBack: () -> Unit = {},
    vm: StatsDetailViewModel = viewModel(factory = statsVmFactory()),
) {
    val mode = StatsMode.of(modeKey)
    val filtered by vm.filtered.collectAsState()
    val filteredByRule by vm.filteredByRule.collectAsState()
    val learned by vm.learned.collectAsState()

    val items: List<NotificationEntity> = when (mode) {
        StatsMode.Filtered -> (filtered + filteredByRule).sortedByDescending { it.postTime }
        StatsMode.Learned -> learned
    }

    // 1.4.0 Dev 13：「全部学习」——按各自原方向重新学习（广告加强/正常加强）
    val toast by vm.toast.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }
    // 2.0.1 Dev 1：「全部学习」只作用于**已学习**条目，未学习项跳过不再代填“广告”
    val normalCount = items.count { it.learned && it.learnLabel == 0 }
    val adCount = items.count { it.learned && it.learnLabel == 1 }
    val learnedCount = normalCount + adCount
    val unlearnedCount = items.size - learnedCount

    if (confirmAll) {
        OverlayDialog(
            show = true,
            title = "重新学习 $learnedCount 条已学习通知？",
            onDismissRequest = { confirmAll = false },
        ) {
            Text(
                "⚠️ 只会重新学习已有标注的条目，未学习的 $unlearnedCount 条将被跳过\n\n" +
                    "· 已学习为广告 $adCount 条 → 再学一次广告（广告权重加强）\n" +
                    "· 已学习为正常 $normalCount 条 → 再学一次正常（广告权重下调）\n\n" +
                    "⚠️ 未学习过的通知不会被自动标为广告；\n若想把某条误拦通知改成正常，请逐条点开详情后手动选择。\n\n" +
                    "学习后模型会立即重新拟合。",
                style = MiuixTheme.textStyles.footnote1,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { confirmAll = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        confirmAll = false
                        vm.relearnAll(items)
                    },
                    enabled = learnedCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("开始学习", style = MiuixTheme.textStyles.button) }
            }
        }
    }

    val selected by vm.selected.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SmallTopAppBar(
                title = if (mode is StatsMode.Filtered) "已过滤的通知" else "已学习的通知",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") }
                },
                actions = {
                    // 2.0.1 Dev 1：只在列表里确有已学习条目时才显示（未学习项不再代填方向）
                    if (learnedCount > 0) {
                        TextButton(
                            text = if (busy) "学习中…" else "全部学习",
                            onClick = { confirmAll = true },
                            enabled = !busy,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无记录", style = MiuixTheme.textStyles.headline2)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                // Dev 14：条目可点击 → 打开与历史页同款的详情弹层（可重新学习/取消学习）
                groupedCardItems(
                    keyPrefix = "stats",
                    outerBottomPadding = 12.dp,
                    items = items.map { n ->
                        CardItem(n.id.toString()) { StatsRow(n) { vm.select(n) } }
                    },
                )
            }
        }
    }

    // Dev 14：点击条目 → 与历史页一致的详情弹层（重新学习 / 取消学习 / 跳转通道）
    selected?.let { n ->
        val context = LocalContext.current
        val versions by vm.versions.collectAsState()
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.detail_title),
            onDismissRequest = { vm.select(null) },
        ) {
            NotificationDetail(
                n = n,
                versions = versions,
                onJumpChannel = {
                    io.github.vstory.hook.notifyfilter.notify.KeepAliveManager.openChannelSettings(
                        context, n.packageName, n.channelId,
                    )
                },
                onLearn = { label -> vm.learn(n, label) },
                onUnlearn = { vm.unlearn(n) },
            )
        }
    }
}

/** 明细行内容；卡片底与圆角由 groupedCardItems 的 CardSegment 提供 */
@Composable
private fun StatsRow(n: NotificationEntity, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Column {
            Row {
                Text(n.appName, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(formatDetailTime(n.postTime), style = MiuixTheme.textStyles.footnote2)
                Spacer(Modifier.weight(1f))
                val reason = when (n.decision) {
                    "FILTERED_BY_AI", "FILTERED_BY_AI_MODULE" -> "AI ${(n.adProbability * 100).toInt()}%"
                    "FILTERED_BY_RULE", "FILTERED_BY_RULE_MODULE" -> "规则"
                    DECISION_MANUAL_MARKED_AD -> "手动学习"
                    else -> ""
                }
                Text(reason, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
            }
            if (n.title.isNotEmpty()) {
                Text(n.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text(n.content, style = MiuixTheme.textStyles.footnote1, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun statsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            StatsDetailViewModel(ServiceLocator.db.notificationDao(), ServiceLocator.modelRepo)
        }
    }
