package io.github.vstory.hook.notifyfilter.ui.rules

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.vstory.hook.notifyfilter.ui.history.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选择器会话结果（跨页面传递选中项；null = 未选择/取消） */
object AppPickerSession {
    var result: List<Pair<String, String>>? = null
    var initial: List<Pair<String, String>> = emptyList()
}

private data class AppInfo(val pkg: String, val label: String)

@Composable
fun AppPickerScreen(
    title: String,
    multiSelect: Boolean = true,
    onBack: () -> Unit,
    onConfirm: (List<Pair<String, String>>) -> Unit,
) {
    val context = LocalContext.current
    // 1.3.2（P0-4）：应用列表加载移出主线程——原 remember{} 在组合期同步做
    // 200~400 次 PackageManager IPC，打开页面冻结 0.5~1.5s；现在先出加载态，数据到位后填充
    var loaded by remember { mutableStateOf(false) }
    val allApps by produceState<List<AppInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                // 1.2.3：不再按 launcher 过滤——系统应用（服务类无界面包）也可选；
                // label 解析逐项容错（个别包 RRO 资源加载失败不影响整个列表）
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map {
                        val label = runCatching { pm.getApplicationLabel(it)?.toString() }
                            .getOrNull() ?: it.packageName
                        AppInfo(it.packageName, label)
                    }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
        loaded = true
    }
    var query by remember { mutableStateOf("") }
    var selected by remember {
        mutableStateOf(AppPickerSession.initial.associate { it.first to it.second })
    }

    val filtered = if (query.isBlank()) allApps
    else allApps.filter {
        it.label.contains(query, true) || it.pkg.contains(query, true)
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { onConfirm(selected.entries.map { it.key to it.value }) },
                        modifier = Modifier.padding(end = 12.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("确定(${selected.size})") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索 App 名称或包名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (!loaded) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "正在加载应用列表…",
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text("无匹配应用", style = MiuixTheme.textStyles.headline2) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = selected.containsKey(app.pkg)
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            CheckboxPreference(
                                title = app.label,
                                checked = checked,
                                onCheckedChange = {
                                    if (multiSelect) {
                                        selected = if (checked) {
                                            selected - app.pkg
                                        } else {
                                            selected + (app.pkg to app.label)
                                        }
                                    } else {
                                        selected = mapOf(app.pkg to app.label)
                                        onConfirm(listOf(app.pkg to app.label))
                                    }
                                },
                                summary = app.pkg,
                                startAction = { AppIcon(app.pkg, 36) },
                                checkboxLocation = CheckboxLocation.End,
                            )
                        }
                    }
                }
            }
        }
    }
}
