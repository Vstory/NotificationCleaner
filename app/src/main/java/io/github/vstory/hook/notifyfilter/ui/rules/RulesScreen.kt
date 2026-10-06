package io.github.vstory.hook.notifyfilter.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.db.ConditionEvaluator
import io.github.vstory.hook.notifyfilter.data.db.MatchMode
import io.github.vstory.hook.notifyfilter.data.db.RuleDao
import io.github.vstory.hook.notifyfilter.data.db.RuleEntity
import io.github.vstory.hook.notifyfilter.data.db.WhitelistDao
import io.github.vstory.hook.notifyfilter.data.db.WhitelistEntity
import io.github.vstory.hook.notifyfilter.ui.component.blur.BlurredBar
import io.github.vstory.hook.notifyfilter.ui.component.blur.rememberBlurBackdrop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class RulesViewModel(
    private val dao: RuleDao,
    private val whitelistDao: WhitelistDao,
    private val notificationDao: io.github.vstory.hook.notifyfilter.data.db.NotificationDao,
) : ViewModel() {
    val rules = dao.listAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val whitelist = whitelistDao.listAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 每条规则的历史过滤计数（1.4.0 Dev 2）：
     * 取 NLS 端 + 模块端回流的规则过滤记录，在 UI 层按各规则条件求值归因
     * （与引擎同一求值器 [ConditionEvaluator]，无需改动决策/入库功能代码）。
     * 一条通知只归因于规则列表中第一条命中的规则，避免多规则重叠时重复计数。
     */
    val ruleHitCounts: kotlinx.coroutines.flow.StateFlow<Map<Long, Int>> =
        kotlinx.coroutines.flow.combine(
            notificationDao.listByDecisions(
                listOf(
                    io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE,
                    io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE_MODULE,
                ),
            ),
            dao.listAll(),
        ) { notifs, rs ->
            val compiled = rs.map { Triple(it.id, it.packageName, it.conditionSet()) }
            val counts = HashMap<Long, Int>()
            for (n in notifs) {
                for ((id, pkg, set) in compiled) {
                    if (pkg == n.packageName && ConditionEvaluator.evalSet(set, n.title, n.content)) {
                        counts[id] = (counts[id] ?: 0) + 1
                        break
                    }
                }
            }
            counts
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun toggle(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch { dao.update(rule.copy(enabled = enabled)) }
    }

    fun delete(rule: RuleEntity) {
        viewModelScope.launch { dao.delete(rule.id) }
    }

    fun addWhitelist(packageName: String, appName: String) {
        viewModelScope.launch { whitelistDao.insert(WhitelistEntity(packageName = packageName, appName = appName)) }
    }

    fun removeWhitelist(packageName: String) {
        viewModelScope.launch { whitelistDao.delete(packageName) }
    }
}

@Composable
fun RulesScreen(
    onOpenRuleEdit: () -> Unit,
    onOpenAppPicker: () -> Unit,
    bottomPadding: Dp = 0.dp,
    vm: RulesViewModel = viewModel(factory = rulesVmFactory()),
) {
    val rules by vm.rules.collectAsState()
    val whitelist by vm.whitelist.collectAsState()
    val hitCounts by vm.ruleHitCounts.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0=过滤规则 1=白名单

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // contentWindowInsets 清零：顶部 inset 由本页 TopAppBar 处理，底部靠外层底栏高度透传，
    // 不再依赖 MainScaffold 的 padding，避免 inset 二次叠加
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                TopAppBar(
                    title = "规则",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (tab == 0) onOpenRuleEdit() else {
                        AppPickerSession.initial = emptyList()
                        onOpenAppPicker()
                    }
                },
                // contentWindowInsets 清零后 Scaffold 不再为 FAB 让出底栏高度，此处自行补足
                modifier = Modifier.padding(bottom = bottomPadding),
            ) {
                Icon(
                    MiuixIcons.Add,
                    contentDescription = if (tab == 0) "新建规则" else "添加白名单",
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = padding.calculateTopPadding(), bottom = bottomPadding),
        ) {
            TabRowWithContour(
                tabs = listOf("过滤规则 (${rules.size})", "白名单 (${whitelist.size})"),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
            )
            when (tab) {
                0 -> {
                    if (rules.isEmpty()) {
                        EmptyHint("暂无手动规则", "点击右下角 + 新建：选择 APP + 条件组合（8 种匹配方式）")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(rules, key = { it.id }) { rule ->
                                RuleCard(
                                    rule,
                                    hitCount = hitCounts[rule.id] ?: 0,
                                    onToggle = { vm.toggle(rule, it) },
                                    onDelete = { vm.delete(rule) },
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (whitelist.isEmpty()) {
                        EmptyHint("白名单为空", "白名单内的 APP 通知不会被 AI 过滤；点击右下角 + 添加")
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(whitelist, key = { it.packageName }) { item ->
                                WhitelistCard(item, onRemove = { vm.removeWhitelist(item.packageName) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 规则摘要：APP + 条件列表 + 该规则累计过滤条数（NLS 端 + 模块端回流记录） */
private fun ruleSummaryText(rule: RuleEntity, hitCount: Int): String {
    val cs = rule.conditionSet()
    val conditions = if (cs.conditions.isEmpty()) {
        "无条件"
    } else {
        buildString {
            append(if (cs.join == "OR") "任一条件" else "全部条件")
            append("满足时过滤：")
            cs.conditions.forEachIndexed { i, c ->
                if (i > 0) append(if (cs.join == "OR") " 或 " else " 且 ")
                append("[${if (c.field == io.github.vstory.hook.notifyfilter.data.db.MATCH_TITLE) "标题" else "内容"}·")
                append(MatchMode.label(c.mode))
                append("]")
            }
        }
    }
    return "$conditions\n已过滤 $hitCount 条通知"
}

@Composable
private fun RuleCard(rule: RuleEntity, hitCount: Int, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SwitchPreference(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            title = rule.appName,
            summary = ruleSummaryText(rule, hitCount),
            endActions = {
                IconButton(onClick = onDelete) { Icon(MiuixIcons.Delete, contentDescription = "删除") }
            },
        )
    }
}

@Composable
private fun WhitelistCard(item: WhitelistEntity, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicComponent(
            title = item.appName,
            summary = item.packageName,
            endActions = {
                IconButton(onClick = onRemove) { Icon(MiuixIcons.Delete, contentDescription = "移除") }
            },
        )
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MiuixTheme.textStyles.headline2)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
internal fun rulesVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            RulesViewModel(
                ServiceLocator.db.ruleDao(),
                ServiceLocator.db.whitelistDao(),
                ServiceLocator.db.notificationDao(),
            )
        }
    }
