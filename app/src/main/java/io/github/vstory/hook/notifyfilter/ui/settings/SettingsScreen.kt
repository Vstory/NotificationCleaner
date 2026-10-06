package io.github.vstory.hook.notifyfilter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.SettingsRepository
import io.github.vstory.hook.notifyfilter.data.db.NotificationDao
import io.github.vstory.hook.notifyfilter.notify.KeepAliveStatus
import io.github.vstory.hook.notifyfilter.notify.RootExecutor
import io.github.vstory.hook.notifyfilter.notify.ShizukuExecutor
import io.github.vstory.hook.notifyfilter.notify.runKeepAliveCommands
import io.github.vstory.hook.notifyfilter.ui.component.CardItem
import io.github.vstory.hook.notifyfilter.ui.component.blur.BlurredBar
import io.github.vstory.hook.notifyfilter.ui.component.blur.rememberBlurBackdrop
import io.github.vstory.hook.notifyfilter.ui.component.groupedCardItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val dao: NotificationDao,
) : ViewModel() {
    val threshold = settings.threshold.stateIn(viewModelScope, SharingStarted.Eagerly, 0.8f)
    val interceptMode = settings.interceptMode.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val excludeFromRecents = settings.excludeFromRecents.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filteredCount = dao.filteredCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val learnedCount = dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _keepAlive = MutableStateFlow(KeepAliveStatus())
    val keepAlive: StateFlow<KeepAliveStatus> = _keepAlive

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 高级保活命令执行结果（弹窗展示，由「保活通道与修复」子页渲染） */
    private val _execResult = MutableStateFlow<String?>(null)
    val execResult: StateFlow<String?> = _execResult

    private val _execBusy = MutableStateFlow(false)
    val execBusy: StateFlow<Boolean> = _execBusy

    /**
     * 已学习样本数。
     * ⚠️ 必须走 Room flow 实时刷新：此前在 init 一次性读 learn_count 文件，底部导航
     * restoreState 会保留 VM，学完通知返回设置页仍显示旧值 0。
     */
    val learnedSamples: StateFlow<Int> =
        dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refreshKeepAlive()
        // Dev 7：LSPosed 框架服务绑定/作用域变化（含授权框批准后）自动刷新保活状态
        viewModelScope.launch {
            io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.state.collect {
                refreshKeepAlive()
            }
        }
    }

    fun refreshKeepAlive() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val shizukuOk = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            _keepAlive.value = ServiceLocator.keepAlive.status(shizukuOk)
        }
    }

    fun setThreshold(v: Float) {
        viewModelScope.launch { settings.setThreshold(v) }
    }

    fun setInterceptMode(v: Boolean) {
        viewModelScope.launch { settings.setInterceptMode(v) }
    }

    fun setExcludeFromRecents(v: Boolean) {
        viewModelScope.launch { settings.setExcludeFromRecents(v) }
    }

    // ---- LSPosed 框架服务状态（Dev 7：libxposed service 绑定 + 作用域）----
    val lspServiceState = io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.state

    /** 保活：请求系统框架（android）作用域（system_server 保活/拦截 hook） */
    fun requestKeepAliveScope() {
        io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.requestScope(
            listOf(io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER),
        ) { result ->
            _toast.value = result.fold(
                onSuccess = { "系统框架作用域已授权：请重启手机使保活 hook 生效" },
                onFailure = { "授权失败：${it.message}" },
            )
        }
    }

    // ---- 历史通知（Dev 6：保留天数可调）----
    val historyRetentionDays = settings.historyRetentionDays.stateIn(viewModelScope, SharingStarted.Eagerly, 7)

    fun setHistoryRetentionDays(v: Int) {
        viewModelScope.launch { settings.setHistoryRetentionDays(v) }
    }

    fun requestIgnoreBattery() {
        ServiceLocator.keepAlive.requestIgnoreBatteryOptimization()
    }

    fun openListenerSettings() {
        ServiceLocator.keepAlive.openListenerSettings()
    }

    /** 1.2.1：手动修复通知监听（摘除→写回强制重绑；Shizuku 优先，Root 兜底） */
    fun repairListener() {
        if (_execBusy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _execBusy.value = true
            val executor = when {
                runCatching {
                    rikka.shizuku.Shizuku.pingBinder() &&
                        rikka.shizuku.Shizuku.checkSelfPermission() ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false) -> ShizukuExecutor
                ServiceLocator.keepAlive.isRootAvailable() -> RootExecutor
                else -> {
                    _execBusy.value = false
                    _toast.value = "需要 Shizuku（已授权）或 Root 才能强制修复；普通用户可直接开关一次通知监听权限"
                    return@launch
                }
            }
            _execResult.value = runCatching {
                io.github.vstory.hook.notifyfilter.notify.ListenerRepair.repair(executor)
            }.getOrElse { "修复失败: $it" }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    /** 1.2.1：跳转无障碍设置（保活守护层） */
    fun openAccessibilitySettings() {
        runCatching {
            ServiceLocator.appContext.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun resetModel() {
        viewModelScope.launch {
            ServiceLocator.db.notificationDao().clearLearned()
            ServiceLocator.modelRepo.resetToBaseline()
            _toast.value = "已清除学习标注，模型重置为预训练基线"
        }
    }

    // ---- Shizuku / Root 高级保活（仅用户主动点击时授权/执行） ----

    /** 请求 Shizuku 授权；授权结果刷新状态 */
    fun requestShizuku() {
        runCatching {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                _toast.value = "Shizuku 未运行：请先安装并启动 Shizuku（adb 或 Root 激活）"
                return
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, grantResult ->
                refreshKeepAlive()
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) applyShizuku()
            }
            rikka.shizuku.Shizuku.requestPermission(0)
        }.onFailure { _toast.value = "Shizuku 不可用：$it" }
    }

    fun applyShizuku() {
        if (_execBusy.value) return
        viewModelScope.launch {
            _execBusy.value = true
            _execResult.value = runKeepAliveCommands(ServiceLocator.appContext, ShizukuExecutor) { }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    fun applyRoot() {
        if (_execBusy.value) return
        viewModelScope.launch {
            _execBusy.value = true
            _execResult.value = runKeepAliveCommands(ServiceLocator.appContext, RootExecutor) { }
            _execBusy.value = false
            refreshKeepAlive()
        }
    }

    fun dismissExecResult() {
        _execResult.value = null
    }

    fun showToast(msg: String) {
        _toast.value = msg
    }

    fun clearToast() {
        _toast.value = null
    }
}

@Composable
fun SettingsScreen(
    onOpenStats: (String) -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    onOpenAiModel: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
    vm: SettingsViewModel = viewModel(factory = settingsVmFactory()),
) {
    val threshold by vm.threshold.collectAsState()
    val intercept by vm.interceptMode.collectAsState()
    val excludeRecents by vm.excludeFromRecents.collectAsState()
    val filteredCount by vm.filteredCount.collectAsState()
    val learnedCount by vm.learnedCount.collectAsState()
    val keepAlive by vm.keepAlive.collectAsState()
    val historyRetentionDays by vm.historyRetentionDays.collectAsState()
    var thresholdDraft by remember(threshold) { mutableStateOf(threshold) }
    var retentionDraft by remember(historyRetentionDays) { mutableStateOf(historyRetentionDays) }
    val manufacturerHint = remember { ServiceLocator.keepAlive.manufacturerAutoStartHint() }

    // 保活可用通道数：4 条通道任一条可用即说明保活链路有保障。
    // 摘要必须放这里，否则用户要逐层点进子页才知道保活到底有没有生效。
    // lspDetected 为 null 表示「无法检测」（检测不到 ≠ 未激活），按不可用计。
    val availableChannels = listOfNotNull(
        "Shizuku".takeIf { keepAlive.shizukuAvailable },
        "Root".takeIf { keepAlive.rootAvailable },
        "LSPosed".takeIf { keepAlive.lspDetected == true },
        "无障碍".takeIf { keepAlive.accessibilityEnabled },
    )

    // 多任务隐藏：切换后立即应用（API 29+ 直接设置任务标记，不重建任务）
    val context = LocalContext.current
    var prevExclude by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(excludeRecents) {
        if (prevExclude != null && prevExclude != excludeRecents) {
            (context as? io.github.vstory.hook.notifyfilter.ui.MainActivity)?.let { act ->
                act.applyExcludeFromRecents(excludeRecents)
            }
        }
        prevExclude = excludeRecents
    }

    val diagContext = LocalContext.current
    val diagScope = rememberCoroutineScope()
    var diagExporting by remember { mutableStateOf(false) }
    var diagMsg by remember { mutableStateOf<String?>(null) }
    val csvScope = rememberCoroutineScope()
    var csvExporting by remember { mutableStateOf(false) }
    var csvMsg by remember { mutableStateOf<String?>(null) }

    val updateVm: io.github.vstory.hook.notifyfilter.update.UpdateViewModel =
        viewModel(key = "update", factory = viewModelFactory { initializer { io.github.vstory.hook.notifyfilter.update.UpdateViewModel() } })
    val updateState by updateVm.state.collectAsState()

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                TopAppBar(
                    title = "设置",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            item { SmallTitle("过滤") }
            groupedCardItems(
                keyPrefix = "settings_filter",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("intercept") {
                        SwitchPreference(
                            checked = intercept,
                            onCheckedChange = { vm.setInterceptMode(it) },
                            title = "拦截模式",
                            summary = "关闭后仅标记不拦截，便于观察误杀",
                        )
                    },
                    CardItem("threshold") {
                        SliderPreference(
                            value = thresholdDraft,
                            onValueChange = { thresholdDraft = it },
                            title = "过滤阈值",
                            valueText = "%.2f".format(thresholdDraft),
                            valueRange = 0.5f..1.0f,
                            steps = 9,
                            onValueChangeFinished = { vm.setThreshold(thresholdDraft) },
                        )
                    },
                ),
            )

            item { SmallTitle("模型与数据") }
            groupedCardItems(
                keyPrefix = "settings_model_data",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("aiModel") {
                        ArrowPreference(
                            title = "AI 模型",
                            summary = "基线 NSPM v2",
                            onClick = onOpenAiModel,
                        )
                    },
                    CardItem("filtered") {
                        ArrowPreference(
                            title = "已过滤通知",
                            summary = "$filteredCount 条",
                            onClick = { onOpenStats("filtered") },
                        )
                    },
                    CardItem("learned") {
                        ArrowPreference(
                            title = "已学习通知",
                            summary = "$learnedCount 条",
                            onClick = { onOpenStats("learned") },
                        )
                    },
                    CardItem("retention") {
                        SliderPreference(
                            value = retentionDraft.toFloat(),
                            onValueChange = { retentionDraft = it.toInt().coerceIn(1, 30) },
                            title = "历史保留天数",
                            summary = "仅对未学习通知生效，已学习的不受影响",
                            valueText = "$retentionDraft 天",
                            valueRange = 1f..30f,
                            steps = 28,
                            onValueChangeFinished = { vm.setHistoryRetentionDays(retentionDraft) },
                        )
                    },
                    CardItem("diagLog") {
                        ArrowPreference(
                            title = "导出诊断日志",
                            summary = when {
                                diagExporting -> "导出中…"
                                diagMsg != null -> diagMsg
                                else -> "导出各模块最近 24 小时日志（ZIP）"
                            },
                            enabled = !diagExporting,
                            onClick = {
                                diagMsg = null
                                diagExporting = true
                                diagScope.launch {
                                    val msg = runCatching {
                                        val file = io.github.vstory.hook.notifyfilter.diagnostics.DiagExporter.export(diagContext)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            diagContext,
                                            "${diagContext.packageName}.fileprovider",
                                            file,
                                        )
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            // Dev 9：分模块 ZIP（text/plain 会让部分接收端把 zip 当文本改名/打不开）
                                            type = "application/zip"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        diagContext.startActivity(
                                            android.content.Intent.createChooser(send, "分享诊断日志"),
                                        )
                                        "已导出: ${file.name}（${file.length() / 1024}KB）"
                                    }.getOrElse { "导出失败: ${it.message}" }
                                    diagExporting = false
                                    diagMsg = msg
                                }
                            },
                        )
                    },
                    CardItem("csvExport") {
                        ArrowPreference(
                            title = "导出历史通知 CSV",
                            summary = when {
                                csvExporting -> "导出中…"
                                csvMsg != null -> csvMsg
                                else -> "导出全部历史通知记录（CSV）"
                            },
                            enabled = !csvExporting,
                            onClick = {
                                csvMsg = null
                                csvExporting = true
                                csvScope.launch {
                                    val msg = runCatching {
                                        val file = io.github.vstory.hook.notifyfilter.diagnostics.HistoryCsvExporter.export(diagContext)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            diagContext,
                                            "${diagContext.packageName}.fileprovider",
                                            file,
                                        )
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        diagContext.startActivity(
                                            android.content.Intent.createChooser(send, "分享历史通知 CSV"),
                                        )
                                        "已导出 ${file.name}（${file.length() / 1024}KB）"
                                    }.getOrElse { "导出失败: ${it.message}" }
                                    csvExporting = false
                                    csvMsg = msg
                                }
                            },
                        )
                    },
                ),
            )

            item { SmallTitle("保活") }
            groupedCardItems(
                keyPrefix = "settings_keepalive",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("listener") {
                        ArrowPreference(
                            title = "通知监听权限",
                            summary = if (keepAlive.listenerEnabled) "已授权" else "未授权，点击前往授权",
                            onClick = { vm.openListenerSettings() },
                        )
                    },
                    CardItem("battery") {
                        ArrowPreference(
                            title = "电池优化白名单",
                            summary = if (keepAlive.ignoringBattery) "已加入白名单" else "未加入，点击前往设置",
                            onClick = { vm.requestIgnoreBattery() },
                        )
                    },
                    CardItem("advanced") {
                        ArrowPreference(
                            title = "保活通道与修复",
                            summary = if (availableChannels.isEmpty()) {
                                "未启用，点击配置保活通道"
                            } else {
                                "${availableChannels.size} 条通道可用"
                            },
                            onClick = onOpenAdvanced,
                        )
                    },
                    CardItem("hideRecents") {
                        SwitchPreference(
                            checked = excludeRecents,
                            onCheckedChange = { vm.setExcludeFromRecents(it) },
                            title = "在多任务界面隐藏",
                            summary = "从最近任务列表隐藏本应用卡片",
                        )
                    },
                ),
            )

            // 厂商自启动提示：保持在「保活」组末尾（Mishka ExternalControl 的纯文本提示卡位置）
            manufacturerHint?.let { hint ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = hint,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            item { SmallTitle("关于") }
            groupedCardItems(
                keyPrefix = "settings_about",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("update") {
                        val checking = updateState is io.github.vstory.hook.notifyfilter.update.UpdateState.Checking
                        ArrowPreference(
                            title = "检查更新",
                            summary = if (checking) {
                                "正在请求更新源…"
                            } else {
                                "当前版本 v${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME}"
                            },
                            enabled = !checking,
                            onClick = { updateVm.checkUpdate() },
                        )
                    },
                    CardItem("about") {
                        ArrowPreference(
                            title = "关于",
                            summary = "通知净化 v${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME}",
                            onClick = { onOpenAbout() },
                        )
                    },
                ),
            )
        }

        when (val s = updateState) {
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Checking -> UpdateStatusDialog(
                title = "正在检查更新…", text = "正在请求更新源",
                confirm = null, onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.UpToDate -> UpdateStatusDialog(
                title = "已是最新版本", text = "当前 v${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME} 已是最新",
                confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Error -> UpdateStatusDialog(
                title = "更新失败", text = s.message, confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Available -> OverlayDialog(
                show = true,
                title = "发现新版本 v${s.release.versionName}",
                onDismissRequest = { updateVm.reset() },
            ) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(s.release.notes.ifBlank { "无更新说明" }, style = MiuixTheme.textStyles.body2)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "稍后再说",
                        onClick = { updateVm.reset() },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { updateVm.startDownload(s.release) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("立即更新", style = MiuixTheme.textStyles.button) }
                }
            }
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Downloading -> OverlayDialog(
                show = true,
                title = "正在下载 v${s.release.versionName}",
                onDismissRequest = {},
            ) {
                LinearProgressIndicator(
                    progress = s.progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("${s.progress}%", style = MiuixTheme.textStyles.footnote1)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { updateVm.cancelDownload() },
                        modifier = Modifier.weight(1f),
                    ) { Text("取消", style = MiuixTheme.textStyles.button) }
                }
            }
            is io.github.vstory.hook.notifyfilter.update.UpdateState.ReadyToInstall -> OverlayDialog(
                show = true,
                title = "下载完成",
                onDismissRequest = { updateVm.reset() },
            ) {
                Text("点击「安装」打开系统安装器升级到 v${s.release.versionName}。")
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "稍后",
                        onClick = { updateVm.reset() },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { updateVm.install(s.release, s.file) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("安装", style = MiuixTheme.textStyles.button) }
                }
            }
            io.github.vstory.hook.notifyfilter.update.UpdateState.Idle -> Unit
        }
    }
}

@Composable
private fun UpdateStatusDialog(title: String, text: String, confirm: String?, onDismiss: () -> Unit) {
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Text(text)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(
                text = confirm ?: "关闭",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun settingsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            SettingsViewModel(ServiceLocator.settings, ServiceLocator.db.notificationDao())
        }
    }
