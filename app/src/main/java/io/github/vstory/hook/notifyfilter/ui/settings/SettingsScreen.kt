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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vstory.hook.notifyfilter.R
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.ProtectTypes
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
    val protectTypes = settings.protectTypes.stateIn(viewModelScope, SharingStarted.Eagerly, ProtectTypes())
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

    /** 通知类型保护（整组写回：三个开关共用一次 DataStore edit，避免部分落盘） */
    fun setProtectTypes(v: ProtectTypes) {
        viewModelScope.launch { settings.setProtectTypes(v) }
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
    onOpenTheme: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
    vm: SettingsViewModel = viewModel(factory = settingsVmFactory()),
) {
    val threshold by vm.threshold.collectAsState()
    val intercept by vm.interceptMode.collectAsState()
    val excludeRecents by vm.excludeFromRecents.collectAsState()
    val protect by vm.protectTypes.collectAsState()
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
    val shareDiagTitle = stringResource(R.string.settings_share_diag)
    val shareCsvTitle = stringResource(R.string.settings_share_csv)
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
                    title = stringResource(R.string.settings_title),
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
            item { SmallTitle(stringResource(R.string.settings_group_general)) }
            groupedCardItems(
                keyPrefix = "settings_general",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("theme") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_theme_title),
                            summary = stringResource(R.string.settings_theme_summary),
                            onClick = onOpenTheme,
                        )
                    },
                ),
            )

            item { SmallTitle(stringResource(R.string.settings_group_filter)) }
            groupedCardItems(
                keyPrefix = "settings_filter",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("intercept") {
                        SwitchPreference(
                            checked = intercept,
                            onCheckedChange = { vm.setInterceptMode(it) },
                            title = stringResource(R.string.settings_intercept),
                            summary = stringResource(R.string.settings_intercept_summary),
                        )
                    },
                    CardItem("threshold") {
                        SliderPreference(
                            value = thresholdDraft,
                            onValueChange = { thresholdDraft = it },
                            title = stringResource(R.string.settings_threshold),
                            valueText = "%.2f".format(thresholdDraft),
                            valueRange = 0.5f..1.0f,
                            steps = 9,
                            onValueChangeFinished = { vm.setThreshold(thresholdDraft) },
                        )
                    },
                ),
            )

            // 判定类型的开关：详情面板会显示「媒体/对话/常驻通知 — 不参与过滤」，
            // 这里就是它的控制入口（与通知滤盒「过滤范围 → 通知类型」同义）
            item { SmallTitle(stringResource(R.string.settings_group_protect)) }
            groupedCardItems(
                keyPrefix = "settings_protect",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("protectMedia") {
                        SwitchPreference(
                            checked = protect.media,
                            onCheckedChange = { vm.setProtectTypes(protect.copy(media = it)) },
                            title = stringResource(R.string.settings_protect_media),
                            summary = stringResource(R.string.settings_protect_media_summary),
                        )
                    },
                    CardItem("protectConversation") {
                        SwitchPreference(
                            checked = protect.conversation,
                            onCheckedChange = { vm.setProtectTypes(protect.copy(conversation = it)) },
                            title = stringResource(R.string.settings_protect_conversation),
                            summary = stringResource(R.string.settings_protect_conversation_summary),
                        )
                    },
                    CardItem("protectOngoing") {
                        SwitchPreference(
                            checked = protect.ongoing,
                            onCheckedChange = { vm.setProtectTypes(protect.copy(ongoing = it)) },
                            title = stringResource(R.string.settings_protect_ongoing),
                            summary = stringResource(R.string.settings_protect_ongoing_summary),
                        )
                    },
                ),
            )

            item { SmallTitle(stringResource(R.string.settings_group_model)) }
            groupedCardItems(
                keyPrefix = "settings_model_data",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("aiModel") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_ai_model),
                            summary = stringResource(R.string.settings_ai_model_summary),
                            onClick = onOpenAiModel,
                        )
                    },
                    CardItem("filtered") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_filtered_notifications),
                            summary = stringResource(R.string.settings_count_items, filteredCount),
                            onClick = { onOpenStats("filtered") },
                        )
                    },
                    CardItem("learned") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_learned_notifications),
                            summary = stringResource(R.string.settings_count_items, learnedCount),
                            onClick = { onOpenStats("learned") },
                        )
                    },
                    CardItem("retention") {
                        SliderPreference(
                            value = retentionDraft.toFloat(),
                            onValueChange = { retentionDraft = it.toInt().coerceIn(1, 30) },
                            title = stringResource(R.string.settings_retention),
                            summary = stringResource(R.string.settings_retention_summary),
                            valueText = stringResource(R.string.settings_retention_days, retentionDraft),
                            valueRange = 1f..30f,
                            steps = 28,
                            onValueChangeFinished = { vm.setHistoryRetentionDays(retentionDraft) },
                        )
                    },
                    CardItem("diagLog") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_export_diag),
                            summary = when {
                                diagExporting -> stringResource(R.string.settings_exporting)
                                diagMsg != null -> diagMsg
                                else -> stringResource(R.string.settings_export_diag_summary)
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
                                            android.content.Intent.createChooser(
                                                send,
                                                shareDiagTitle,
                                            ),
                                        )
                                        diagContext.getString(
                                            R.string.settings_exported,
                                            file.name,
                                            file.length() / 1024,
                                        )
                                    }.getOrElse { diagContext.getString(R.string.settings_export_failed, it.message ?: "") }
                                    diagExporting = false
                                    diagMsg = msg
                                }
                            },
                        )
                    },
                    CardItem("csvExport") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_export_csv),
                            summary = when {
                                csvExporting -> stringResource(R.string.settings_exporting)
                                csvMsg != null -> csvMsg
                                else -> stringResource(R.string.settings_export_csv_summary)
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
                                            android.content.Intent.createChooser(send, shareCsvTitle),
                                        )
                                        diagContext.getString(
                                            R.string.settings_exported_csv,
                                            file.name,
                                            file.length() / 1024,
                                        )
                                    }.getOrElse { diagContext.getString(R.string.settings_export_failed, it.message ?: "") }
                                    csvExporting = false
                                    csvMsg = msg
                                }
                            },
                        )
                    },
                ),
            )

            item { SmallTitle(stringResource(R.string.settings_group_keepalive)) }
            groupedCardItems(
                keyPrefix = "settings_keepalive",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("listener") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_listener_permission),
                            summary = if (keepAlive.listenerEnabled) {
                                stringResource(R.string.settings_granted)
                            } else {
                                stringResource(R.string.settings_not_granted)
                            },
                            onClick = { vm.openListenerSettings() },
                        )
                    },
                    CardItem("battery") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_battery_whitelist),
                            summary = if (keepAlive.ignoringBattery) {
                                stringResource(R.string.settings_whitelisted)
                            } else {
                                stringResource(R.string.settings_not_whitelisted)
                            },
                            onClick = { vm.requestIgnoreBattery() },
                        )
                    },
                    CardItem("advanced") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_keepalive_channels),
                            summary = if (availableChannels.isEmpty()) {
                                stringResource(R.string.settings_keepalive_channels_none)
                            } else {
                                stringResource(R.string.settings_keepalive_channels_count, availableChannels.size)
                            },
                            onClick = onOpenAdvanced,
                        )
                    },
                    CardItem("hideRecents") {
                        SwitchPreference(
                            checked = excludeRecents,
                            onCheckedChange = { vm.setExcludeFromRecents(it) },
                            title = stringResource(R.string.settings_hide_recents),
                            summary = stringResource(R.string.settings_hide_recents_summary),
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

            item { SmallTitle(stringResource(R.string.settings_group_about)) }
            groupedCardItems(
                keyPrefix = "settings_about",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("update") {
                        val checking = updateState is io.github.vstory.hook.notifyfilter.update.UpdateState.Checking
                        ArrowPreference(
                            title = stringResource(R.string.settings_check_update),
                            summary = if (checking) {
                                stringResource(R.string.settings_checking_update)
                            } else {
                                stringResource(
                                    R.string.settings_current_version,
                                    io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME,
                                )
                            },
                            enabled = !checking,
                            onClick = { updateVm.checkUpdate() },
                        )
                    },
                    CardItem("about") {
                        ArrowPreference(
                            title = stringResource(R.string.settings_about),
                            summary = "NotifyFilter v${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME} " +
                                "(${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_CODE})",
                            onClick = { onOpenAbout() },
                        )
                    },
                ),
            )
        }

        when (val s = updateState) {
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Checking -> UpdateStatusDialog(
                title = stringResource(R.string.settings_update_checking_title),
                text = stringResource(R.string.settings_update_requesting),
                confirm = null, onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.UpToDate -> UpdateStatusDialog(
                title = stringResource(R.string.settings_update_up_to_date_title),
                text = stringResource(
                    R.string.settings_update_up_to_date_text,
                    io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME,
                ),
                confirm = stringResource(R.string.settings_update_got_it), onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Error -> UpdateStatusDialog(
                title = stringResource(R.string.settings_update_failed),
                text = s.message,
                confirm = stringResource(R.string.settings_update_got_it),
                onDismiss = { updateVm.reset() },
            )
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Available -> OverlayDialog(
                show = true,
                title = stringResource(R.string.settings_update_available, s.release.versionName),
                onDismissRequest = { updateVm.reset() },
            ) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = s.release.notes.ifBlank {
                            stringResource(R.string.settings_update_no_notes)
                        },
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.settings_update_later),
                        onClick = { updateVm.reset() },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { updateVm.startDownload(s.release) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(stringResource(R.string.settings_update_now), style = MiuixTheme.textStyles.button) }
                }
            }
            is io.github.vstory.hook.notifyfilter.update.UpdateState.Downloading -> OverlayDialog(
                show = true,
                title = stringResource(R.string.settings_update_downloading, s.release.versionName),
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
                    ) { Text(stringResource(R.string.common_cancel), style = MiuixTheme.textStyles.button) }
                }
            }
            is io.github.vstory.hook.notifyfilter.update.UpdateState.ReadyToInstall -> OverlayDialog(
                show = true,
                title = stringResource(R.string.settings_update_done),
                onDismissRequest = { updateVm.reset() },
            ) {
                Text(stringResource(R.string.settings_update_done_text, s.release.versionName))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = stringResource(R.string.settings_update_later),
                        onClick = { updateVm.reset() },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { updateVm.install(s.release, s.file) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(stringResource(R.string.settings_update_install), style = MiuixTheme.textStyles.button) }
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
                text = confirm ?: stringResource(R.string.common_close),
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
