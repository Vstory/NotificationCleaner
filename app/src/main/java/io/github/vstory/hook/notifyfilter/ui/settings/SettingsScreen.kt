package io.github.vstory.hook.notifyfilter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val dao: NotificationDao,
) : ViewModel() {
    val threshold = settings.threshold.stateIn(viewModelScope, SharingStarted.Eagerly, 0.8f)
    val interceptMode = settings.interceptMode.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val excludeFromRecents = settings.excludeFromRecents.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filteredCount = dao.filteredCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val learnedCount = dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- 界面风格（1.4.0 Dev 4）：Material 3 / 液态玻璃 ----
    val uiTheme = settings.uiTheme.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.vstory.hook.notifyfilter.ui.UiTheme.MATERIAL.name)

    fun setUiTheme(mode: io.github.vstory.hook.notifyfilter.ui.UiTheme) {
        viewModelScope.launch { settings.setUiTheme(mode.name) }
    }

    // ---- 玻璃清晰度（1.4.0 Dev 5）：磨砂 / 柔光 ----
    val glassStyle = settings.glassStyle.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.vstory.hook.notifyfilter.ui.GlassStyle.FROSTED.name)

    fun setGlassStyle(style: io.github.vstory.hook.notifyfilter.ui.GlassStyle) {
        viewModelScope.launch { settings.setGlassStyle(style.name) }
    }

    private val _keepAlive = MutableStateFlow(KeepAliveStatus())
    val keepAlive: StateFlow<KeepAliveStatus> = _keepAlive

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 高级保活命令执行结果（弹窗展示） */
    private val _execResult = MutableStateFlow<String?>(null)
    val execResult: StateFlow<String?> = _execResult

    private val _execBusy = MutableStateFlow(false)
    val execBusy: StateFlow<Boolean> = _execBusy

    /**
     * 模型信息：跟随 Room 学习计数实时刷新。
     * （修复：此前在 init 一次性读取 learn_count 文件，底部导航 restoreState 保留 VM，
     *   学完通知返回设置页仍显示旧值 0。）
     */
    val modelInfo: StateFlow<String> = dao.learnedCount().map { n ->
        "已学习样本：$n 条（NSPM v2 基线 + 端上学习修正）"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "已学习样本：0 条（NSPM v2 基线 + 端上学习修正）")

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
    onOpenOpenSource: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = settingsVmFactory()),
) {
    val threshold by vm.threshold.collectAsState()
    val intercept by vm.interceptMode.collectAsState()
    val excludeRecents by vm.excludeFromRecents.collectAsState()
    val filteredCount by vm.filteredCount.collectAsState()
    val learnedCount by vm.learnedCount.collectAsState()
    val keepAlive by vm.keepAlive.collectAsState()
    val lspServiceState by vm.lspServiceState.collectAsState()
    val modelInfo by vm.modelInfo.collectAsState()
    val execResult by vm.execResult.collectAsState()
    val execBusy by vm.execBusy.collectAsState()
    var thresholdInput by remember(threshold) { mutableStateOf("%.2f".format(threshold)) }
    val uiThemeMode by vm.uiTheme.collectAsState()
    val glassStyleMode by vm.glassStyle.collectAsState()
    val manufacturerHint = remember { ServiceLocator.keepAlive.manufacturerAutoStartHint() }

    // 多任务隐藏：切换后立即应用（API 29+ 直接设置任务标记，不重建任务）
    val context = LocalContext.current
    var prevExclude by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(excludeRecents) {
        if (prevExclude != null && prevExclude != excludeRecents) {
            (context as? io.github.vstory.hook.notifyfilter.ui.MainActivity)?.let { act ->
                if (!act.applyExcludeFromRecents(excludeRecents)) {
                    vm.showToast("仅支持 Android 10 及以上系统")
                }
            }
        }
        prevExclude = excludeRecents
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Dev 15：玻璃模式下悬浮底栏位于 Scaffold 之外，会盖住滚到最底部的功能块
            // → 内容底部让位一个底栏高度（非玻璃模式有 bottomBar，无需让位）
            .padding(bottom = if (io.github.vstory.hook.notifyfilter.ui.LocalGlassMode.current) {
                io.github.vstory.hook.notifyfilter.ui.glass.GlassFloatingBarClearance
            } else {
                0.dp
            })
            .padding(16.dp),
    ) {
        // ---- 界面风格切换（1.4.0 Dev 4）：液态玻璃 / Material 3 ----
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("界面风格", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                    Text("液态玻璃或 Material 3；玻璃模式含壁纸折射、磨砂卡片与胶囊底栏", style = MiuixTheme.textStyles.footnote1)
                }
                Switch(
                    checked = uiThemeMode == io.github.vstory.hook.notifyfilter.ui.UiTheme.GLASS.name,
                    onCheckedChange = {
                        vm.setUiTheme(
                            if (it) io.github.vstory.hook.notifyfilter.ui.UiTheme.GLASS else io.github.vstory.hook.notifyfilter.ui.UiTheme.MATERIAL,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 玻璃清晰度（1.4.0 Dev 5）：仅玻璃主题下显示 ----
        if (uiThemeMode == io.github.vstory.hook.notifyfilter.ui.UiTheme.GLASS.name) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("玻璃清晰度", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("柔光玻璃近乎全透明；磨砂玻璃提供一定可读性", style = MiuixTheme.textStyles.footnote1)
                    Spacer(Modifier.height(8.dp))
                    TabRow(
                        tabs = io.github.vstory.hook.notifyfilter.ui.GlassStyle.entries.map { it.label },
                        selectedTabIndex = io.github.vstory.hook.notifyfilter.ui.GlassStyle.entries
                            .indexOfFirst { it.name == glassStyleMode }.coerceAtLeast(0),
                        onTabSelected = { vm.setGlassStyle(io.github.vstory.hook.notifyfilter.ui.GlassStyle.entries[it]) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 拦截模式 ----
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("拦截模式", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                    Text("关闭后仅标记不拦截，便于观察误杀（AI 仍打分并记录）", style = MiuixTheme.textStyles.footnote1)
                }
                Switch(checked = intercept, onCheckedChange = { vm.setInterceptMode(it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 过滤阈值 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("过滤阈值", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("AI 判定广告概率 ≥ 阈值时自动清除。范围 0.5~1.0，默认 0.8。", style = MiuixTheme.textStyles.footnote1)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = thresholdInput,
                        onValueChange = { s ->
                            // 仅编辑本地输入，点击"保存"后才生效
                            thresholdInput = s
                        },
                        label = "阈值 (0.5~1.0)",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(text = "恢复默认", onClick = {
                        thresholdInput = "0.80"
                        vm.setThreshold(0.8f)
                    })
                    Spacer(Modifier.width(8.dp))
                    val parsed = thresholdInput.toFloatOrNull()
                    val valid = parsed != null && parsed in 0.5f..1.0f && parsed != threshold
                    Button(
                        enabled = valid,
                        onClick = { parsed?.let { vm.setThreshold(it) } },
                    ) { Text("保存") }
                }
                if (thresholdInput.toFloatOrNull()?.let { it !in 0.5f..1.0f } == true) {
                    Text("请输入 0.5 ~ 1.0 之间的数值", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 统计 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("统计", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStats("filtered") }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已过滤 ${filteredCount} 条通知", Modifier.weight(1f))
                    Text("查看明细 ›", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenStats("learned") }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已学习 ${learnedCount} 条通知", Modifier.weight(1f))
                    Text("查看明细 ›", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 多任务隐藏 ----
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在多任务界面隐藏", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                    Text(
                        "系统多任务界面不显示本 APP 的后台卡片，防止误滑删除（需 Android 10+，关闭后恢复显示）",
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                Switch(checked = excludeRecents, onCheckedChange = { vm.setExcludeFromRecents(it) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 权限检查（1.3.0 beta2：原「后台保活」，高级项折叠） ----
        var permAdvancedOpen by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("权限检查", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusRow("通知监听权限", keepAlive.listenerEnabled) { vm.openListenerSettings() }
                StatusRow("电池优化白名单", keepAlive.ignoringBattery) { vm.requestIgnoreBattery() }
                manufacturerHint?.let {
                    Text(it, style = MiuixTheme.textStyles.footnote1, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { permAdvancedOpen = !permAdvancedOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "高级权限（可选）",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (permAdvancedOpen) "收起" else "展开", style = MiuixTheme.textStyles.footnote1)
                }
                if (permAdvancedOpen) {
                    Spacer(Modifier.height(4.dp))
                    AdvancedRow(
                        label = "Shizuku 保活",
                        desc = "免 Root 写入电池优化白名单 / 通知监听权限",
                        ok = keepAlive.shizukuAvailable,
                        actionLabel = if (keepAlive.shizukuAvailable) "应用" else "授权",
                        onAction = { if (keepAlive.shizukuAvailable) vm.applyShizuku() else vm.requestShizuku() },
                    )
                    AdvancedRow(
                        label = "Root 保活",
                        desc = "以 Root 执行白名单与厂商自启动命令（最彻底）",
                        ok = keepAlive.rootAvailable,
                        actionLabel = "应用",
                        onAction = { if (keepAlive.rootAvailable) vm.applyRoot() else vm.showToast("未检测到 Root（su）") },
                    )
                    AdvancedRow(
                        label = "LSPosed 保活",
                        desc = "安装 LSPosed 并激活本模块（作用域勾选「系统(android)」）后自动生效，重启手机完成。" +
                            "未打勾 = 未检测到激活证据（框架服务/模块心跳），以 LSPosed 管理器为准",
                        ok = keepAlive.lspDetected == true,
                        actionLabel = if (lspServiceState.bound && !lspServiceState.hasScope(
                                io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER,
                            )
                        ) "授权" else null,
                        onAction = {
                            if (lspServiceState.bound) vm.requestKeepAliveScope()
                            else vm.showToast("请先在 LSPosed 中启用本模块")
                        },
                    )
                    AdvancedRow(
                        label = "无障碍保活",
                        desc = "开启「保活守护」无障碍服务：系统绑定的第二条生命线，不读取屏幕内容",
                        ok = keepAlive.accessibilityEnabled,
                        actionLabel = if (keepAlive.accessibilityEnabled) null else "去开启",
                        onAction = { vm.openAccessibilitySettings() },
                    )
                    AdvancedRow(
                        label = "修复通知监听",
                        desc = "监听断连且无法自愈时的强制修复（需 Shizuku 已授权或 Root）",
                        ok = keepAlive.listenerEnabled,
                        actionLabel = "修复",
                        onAction = { vm.repairListener() },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))


        // ---- 检查更新 ----
        val updateVm: io.github.vstory.hook.notifyfilter.update.UpdateViewModel =
            viewModel(key = "update", factory = viewModelFactory { initializer { io.github.vstory.hook.notifyfilter.update.UpdateViewModel() } })
        val updateState by updateVm.state.collectAsState()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("检查更新", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                        Text(
                            "当前版本 v${io.github.vstory.hook.notifyfilter.BuildConfig.VERSION_NAME}",
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Button(
                        enabled = updateState !is io.github.vstory.hook.notifyfilter.update.UpdateState.Checking,
                        onClick = { updateVm.checkUpdate() },
                    ) { Text("检查") }
                }
            }
        }
        // ---- 导出诊断日志（1.3.2 恢复：1.3.0 设置页重排时丢失）----
        // 内容 = 版本/权限设置快照 + logcat，经系统分享
        val diagContext = LocalContext.current
        val diagScope = rememberCoroutineScope()
        var diagExporting by remember { mutableStateOf(false) }
        var diagMsg by remember { mutableStateOf<String?>(null) }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("导出诊断日志", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                        Text(
                            "导出 ZIP：每个模块一个 log 文件，各自覆盖导出前完整 24 小时",
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Button(
                        enabled = !diagExporting,
                        onClick = {
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
                    ) { Text(if (diagExporting) "导出中…" else "导出") }
                }
                diagMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MiuixTheme.textStyles.footnote1, color = Color.Gray)
                }
                // ---- 历史通知管理（Dev 6，折叠）----
                var historyPanelOpen by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { historyPanelOpen = !historyPanelOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (historyPanelOpen) "▾ 历史通知管理" else "▸ 历史通知管理",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (historyPanelOpen) {
                    Spacer(Modifier.height(8.dp))
                    // CSV 导出
                    val csvScope = rememberCoroutineScope()
                    var csvExporting by remember { mutableStateOf(false) }
                    var csvMsg by remember { mutableStateOf<String?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("导出历史通知 CSV", style = MiuixTheme.textStyles.body1)
                            Text(
                                "全部历史通知（应用/包名/通道/标题/正文/AI率/学习状态），经系统分享",
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                        Button(
                            enabled = !csvExporting,
                            onClick = {
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
                        ) { Text(if (csvExporting) "导出中…" else "导出") }
                    }
                    csvMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = Color.Gray)
                    }
                    Spacer(Modifier.height(12.dp))
                    // 保留天数（监控式循环：最新的顶掉 N 天前的）
                    val historyRetentionDays by vm.historyRetentionDays.collectAsState()
                    var retentionDraft by remember(historyRetentionDays) { mutableStateOf(historyRetentionDays) }
                    Column(Modifier.fillMaxWidth()) {
                        Text("历史保留天数：${retentionDraft} 天", style = MiuixTheme.textStyles.body1)
                        Text(
                            "未学习的历史通知只保留 N 天，最新通知不断把最老的顶掉（监控式循环保存）；已学习的标注不受影响",
                            style = MiuixTheme.textStyles.footnote1,
                        )
                        Slider(
                            value = retentionDraft.toFloat(),
                            onValueChange = { retentionDraft = it.toInt().coerceIn(1, 30) },
                            onValueChangeFinished = { vm.setHistoryRetentionDays(retentionDraft) },
                            valueRange = 1f..30f,
                            steps = 28,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // ---- 高级功能（1.3.0 beta2：默认折叠） ----
        var advancedOpen by remember { mutableStateOf(false) }
        var confirmResetModel by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "高级功能",
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (advancedOpen) "收起" else "展开", style = MiuixTheme.textStyles.footnote1)
                }
                if (advancedOpen) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // ---- AI 模型（重置需二次确认） ----
                    Text("AI 模型", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(modelInfo, style = MiuixTheme.textStyles.footnote1)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { confirmResetModel = true }) { Text("重置模型（回到预训练基线）") }
                    if (confirmResetModel) {
                        OverlayDialog(
                            show = true,
                            title = "确认重置模型？",
                            onDismissRequest = { confirmResetModel = false },
                        ) {
                            Text("将清除所有学习标注，模型回到预训练基线。已拦截统计不受影响，此操作不可撤销。")
                            Spacer(Modifier.height(20.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(
                                    text = "取消",
                                    onClick = { confirmResetModel = false },
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { vm.resetModel(); confirmResetModel = false },
                                    modifier = Modifier.weight(1f),
                                ) { Text("确认重置", style = MiuixTheme.textStyles.button) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // ---- 参考开源项目（Dev 17）：高级功能块下方，跳转开源项目列表 ----
        Card(
            Modifier.fillMaxWidth().clickable { onOpenOpenSource() },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "参考开源项目",
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "本项目的借鉴、参考与依赖来源",
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                Text("›", style = MiuixTheme.textStyles.headline2, color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
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
                    ) { Text("安装", style = MiuixTheme.textStyles.button) }
                }
            }
            io.github.vstory.hook.notifyfilter.update.UpdateState.Idle -> Unit
        }

        // ---- 高级保活执行结果弹窗 ----
        execResult?.let { result ->
            OverlayDialog(
                show = true,
                title = "保活命令执行结果",
                onDismissRequest = { vm.dismissExecResult() },
            ) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(result, style = MiuixTheme.textStyles.footnote1)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "完成",
                        onClick = { vm.dismissExecResult() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
private fun StatusRow(label: String, ok: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (ok) {
            Text("已启用", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
        } else {
            TextButton(text = "去开启", onClick = action)
        }
    }
}

@Composable
private fun AdvancedRow(label: String, desc: String, ok: Boolean, actionLabel: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (ok) {
                        true -> "可用"
                        false -> "不可用"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                )
            }
            Text(desc, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.outline)
        }
        if (actionLabel != null) {
            TextButton(text = actionLabel, onClick = onAction)
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
