package io.github.vstory.hook.notifyfilter.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- 选中 APP ----
            Button(
                onClick = {
                    AppPickerSession.initial = selectedApps
                    openAppPicker()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selectedApps.isEmpty()) "选择 APP（可多选）" else "已选 ${selectedApps.size} 个 APP（点击修改）")
            }
            selectedApps.forEach { (pkg, label) ->
                Text("  · $label", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(12.dp))

            // ---- 条件关系 ----
            Text("条件满足方式", style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(8.dp))
            TabRow(
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
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("条件 ${index + 1}", style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除条件") }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 字段
            TabRow(
                tabs = listOf("标题", "内容"),
                selectedTabIndex = if (condition.field == MATCH_TITLE) 0 else 1,
                onTabSelected = {
                    onChange(condition.copy(field = if (it == 0) MATCH_TITLE else MATCH_CONTENT))
                },
            )
            Spacer(Modifier.height(8.dp))
            // 模式（下拉）
            OverlayDropdownMenu(
                entries = listOf(
                    DropdownEntry(
                        items = MatchMode.all.map { (mode, label) ->
                            DropdownItem(
                                text = label,
                                selected = condition.mode == mode,
                                onClick = { onChange(condition.copy(mode = mode)) },
                            )
                        },
                    ),
                ),
                title = "匹配方式",
                summary = MatchMode.label(condition.mode),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // 文本值（多行；ALL_TEXT 不需要输入）
            if (condition.mode != MatchMode.ALL_TEXT) {
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
                    modifier = Modifier.fillMaxWidth(),
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
