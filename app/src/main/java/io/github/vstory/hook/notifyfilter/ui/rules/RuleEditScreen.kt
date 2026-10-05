package io.github.vstory.hook.notifyfilter.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.db.MatchMode
import io.github.vstory.hook.notifyfilter.data.db.MATCH_CONTENT
import io.github.vstory.hook.notifyfilter.data.db.MATCH_TITLE
import io.github.vstory.hook.notifyfilter.data.db.RuleCondition
import io.github.vstory.hook.notifyfilter.data.db.RuleConditionSet
import io.github.vstory.hook.notifyfilter.data.db.RuleDao
import io.github.vstory.hook.notifyfilter.data.db.RuleEntity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class RuleEditViewModel(private val dao: RuleDao) : ViewModel() {
    /** 保存：为每个选中的 APP 建一条规则（同一套条件） */
    fun save(apps: List<Pair<String, String>>, set: RuleConditionSet) {
        viewModelScope.launch {
            apps.forEach { (pkg, label) ->
                dao.insert(
                    RuleEntity(
                        packageName = pkg,
                        appName = label,
                        conditions = set.toJson(),
                    ),
                )
            }
        }
    }
}

/** 规则编辑页（1.0.2）：APP 跳转选择器多选 + 条件构建器（8 种模式 + 并且/或者） */
@Composable
fun RuleEditScreen(
    onBack: () -> Unit,
    openAppPicker: () -> Unit,
    vm: RuleEditViewModel = viewModel(factory = ruleEditVmFactory()),
) {
    var selectedApps by remember { mutableStateOf(AppPickerSession.initial) }
    var join by remember { mutableStateOf("AND") }
    var conditions by remember {
        mutableStateOf(listOf(RuleCondition(MATCH_TITLE, MatchMode.ANY_TEXT, listOf(""))))
    }

    // 从选择器取回结果
    LaunchedEffect(Unit) {
        AppPickerSession.result?.let { result ->
            selectedApps = result
            AppPickerSession.result = null
        }
    }

    fun updateCondition(index: Int, c: RuleCondition) {
        conditions = conditions.toMutableList().also { it[index] = c }
    }

    val valid = selectedApps.isNotEmpty() && conditions.any { c ->
        c.mode == MatchMode.ALL_TEXT || c.values.any { it.isNotBlank() }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "新建规则",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- 选中 APP ----
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "选择 APP",
                    summary = if (selectedApps.isEmpty()) {
                        "可多选，至少选择一个"
                    } else {
                        selectedApps.joinToString("、") { it.second }
                    },
                    onClick = {
                        AppPickerSession.initial = selectedApps
                        openAppPicker()
                    },
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---- 条件关系 ----
            Text("条件满足方式", style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(8.dp))
            TabRowWithContour(
                tabs = listOf("并且", "或者"),
                selectedTabIndex = if (join == "AND") 0 else 1,
                onTabSelected = { join = if (it == 0) "AND" else "OR" },
            )
            Spacer(Modifier.height(12.dp))

            // ---- 条件列表 ----
            conditions.forEachIndexed { idx, c ->
                ConditionCard(
                    index = idx,
                    condition = c,
                    canDelete = conditions.size > 1,
                    onChange = { updateCondition(idx, it) },
                    onDelete = { conditions = conditions.filterIndexed { i, _ -> i != idx } },
                )
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = {
                    conditions = conditions + RuleCondition(MATCH_TITLE, MatchMode.ANY_TEXT, listOf(""))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加条件（${conditions.size}）")
            }
            Spacer(Modifier.height(16.dp))

            Button(
                enabled = valid,
                onClick = {
                    val cleaned = conditions.map { c ->
                        c.copy(values = c.values.map { it.trim() }.filter { it.isNotEmpty() })
                    }.filter { it.mode == MatchMode.ALL_TEXT || it.values.isNotEmpty() }
                    vm.save(selectedApps, RuleConditionSet(join, cleaned))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("保存规则") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConditionCard(
    index: Int,
    condition: RuleCondition,
    canDelete: Boolean,
    onChange: (RuleCondition) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "条件 ${index + 1}",
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (canDelete) {
                    IconButton(onClick = onDelete) { Icon(MiuixIcons.Delete, "删除条件") }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 字段
            TabRowWithContour(
                tabs = listOf("标题", "内容"),
                selectedTabIndex = if (condition.field == MATCH_TITLE) 0 else 1,
                onTabSelected = {
                    onChange(condition.copy(field = if (it == 0) MATCH_TITLE else MATCH_CONTENT))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            // 模式（下拉）
            OverlayDropdownPreference(
                items = MatchMode.all.map { it.second },
                selectedIndex = MatchMode.all.indexOfFirst { it.first == condition.mode },
                title = "匹配方式",
                onSelectedIndexChange = { idx -> onChange(condition.copy(mode = MatchMode.all[idx].first)) },
            )
            // 文本值（多行；ALL_TEXT 不需要输入）
            if (condition.mode != MatchMode.ALL_TEXT) {
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = condition.values.joinToString("\n"),
                    onValueChange = { raw ->
                        onChange(condition.copy(values = raw.split("\n")))
                    },
                    label = when (condition.mode) {
                        MatchMode.INCLUDE_EXCLUDE -> "每行一个：第 1 行 = 包含 A，其余行 = 排除 B…"
                        else -> "每行一个文本"
                    },
                    useLabelAsPlaceholder = true,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ruleEditVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            RuleEditViewModel(ServiceLocator.db.ruleDao())
        }
    }
