@file:OptIn(ExperimentalMaterial3Api::class)

package cc.ytdttj.noticleaner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.data.SettingsRepository
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.notify.KeepAliveStatus
import cc.ytdttj.noticleaner.notify.RootExecutor
import cc.ytdttj.noticleaner.notify.ShizukuExecutor
import cc.ytdttj.noticleaner.notify.runKeepAliveCommands
import cc.ytdttj.noticleaner.ui.DarkMode
import cc.ytdttj.noticleaner.ui.ThemeColor
import cc.ytdttj.noticleaner.ui.components.SectionHeader
import cc.ytdttj.noticleaner.ui.components.SettingRow
import cc.ytdttj.noticleaner.ui.components.SettingSliderRow
import cc.ytdttj.noticleaner.ui.components.SettingSwitchRow
import cc.ytdttj.noticleaner.ui.components.groupedListShape
import cc.ytdttj.noticleaner.ui.swatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val dao: NotificationDao,
) : ViewModel() {
    val threshold = settings.threshold.stateIn(viewModelScope, SharingStarted.Eagerly, 0.8f)
    val interceptMode = settings.interceptMode.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val excludeFromRecents = settings.excludeFromRecents.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filteredCount = dao.filteredCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val learnedCount = dao.learnedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- 外观（1.5.1）----
    val darkMode = settings.darkMode.stateIn(viewModelScope, SharingStarted.Eagerly, DarkMode.SYSTEM.name)

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { settings.setDarkMode(mode.name) }
    }

    val themeColor = settings.themeColor.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeColor.DYNAMIC.name)

    fun setThemeColor(color: ThemeColor) {
        viewModelScope.launch { settings.setThemeColor(color.name) }
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
            cc.ytdttj.noticleaner.keepalive.LspServiceDetector.state.collect {
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
    val lspServiceState = cc.ytdttj.noticleaner.keepalive.LspServiceDetector.state

    /** 保活：请求系统框架（android）作用域（system_server 保活/拦截 hook） */
    fun requestKeepAliveScope() {
        cc.ytdttj.noticleaner.keepalive.LspServiceDetector.requestScope(
            listOf(cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER),
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
                cc.ytdttj.noticleaner.notify.ListenerRepair.repair(executor)
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
    val darkModeName by vm.darkMode.collectAsState()
    val themeColorName by vm.themeColor.collectAsState()
    val historyRetentionDays by vm.historyRetentionDays.collectAsState()
    val manufacturerHint = remember { ServiceLocator.keepAlive.manufacturerAutoStartHint() }
    val context = LocalContext.current

    // VM 的瞬时提示（如「仅支持 Android 10 及以上系统」）此前在界面里无处显示 → 统一走系统 Toast
    val toastMsg by vm.toast.collectAsState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 多任务隐藏：切换后立即应用（API 29+ 直接设置任务标记，不重建任务）
    var prevExclude by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(excludeRecents) {
        if (prevExclude != null && prevExclude != excludeRecents) {
            (context as? cc.ytdttj.noticleaner.ui.MainActivity)?.let { act ->
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
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ================= 常规 =================
        SectionHeader("常规")
        SettingSwitchRow(
            title = "拦截模式",
            supporting = "关闭后仅标记不拦截，便于观察误杀（AI 仍打分并记录）",
            checked = intercept,
            shape = groupedListShape(0, 2),
            onCheckedChange = { vm.setInterceptMode(it) },
        )
        Spacer(Modifier.height(2.dp))
        SettingSwitchRow(
            title = "在多任务界面隐藏",
            supporting = "系统多任务界面不显示本 APP 的后台卡片，防止误滑删除（需 Android 10+）",
            checked = excludeRecents,
            shape = groupedListShape(1, 2),
            onCheckedChange = { vm.setExcludeFromRecents(it) },
        )

        // ================= 过滤 =================
        SectionHeader("过滤")
        SettingSliderRow(
            title = "过滤阈值",
            supporting = "AI 判定广告概率 ≥ 阈值时自动清除（默认 0.8）",
            value = threshold,
            valueRange = 0.5f..1.0f,
            steps = 9,
            shape = groupedListShape(0, 3),
            onValueCommit = { vm.setThreshold(it) },
        )
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "已过滤 ${filteredCount} 条通知",
            shape = groupedListShape(1, 3),
            trailingText = "查看明细",
            onClick = { onOpenStats("filtered") },
        )
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "已学习 ${learnedCount} 条通知",
            shape = groupedListShape(2, 3),
            trailingText = "查看明细",
            onClick = { onOpenStats("learned") },
        )

        // ================= 外观 =================
        SectionHeader("外观")
        DarkModeRow(
            current = DarkMode.from(darkModeName),
            shape = groupedListShape(0, 2),
            onPick = { vm.setDarkMode(it) },
        )
        Spacer(Modifier.height(2.dp))
        ThemeColorRow(
            current = ThemeColor.from(themeColorName),
            shape = groupedListShape(1, 2),
            onPick = { vm.setThemeColor(it) },
        )

        // ================= 权限与保活 =================
        var permAdvancedOpen by remember { mutableStateOf(false) }
        val permTotal = if (manufacturerHint != null) 4 else 3
        var permIndex = 0
        SectionHeader("权限与保活")
        StatusRow(
            label = "通知监听权限",
            ok = keepAlive.listenerEnabled,
            shape = groupedListShape(permIndex++, permTotal),
            onAction = { vm.openListenerSettings() },
        )
        Spacer(Modifier.height(2.dp))
        StatusRow(
            label = "电池优化白名单",
            ok = keepAlive.ignoringBattery,
            shape = groupedListShape(permIndex++, permTotal),
            onAction = { vm.requestIgnoreBattery() },
        )
        if (manufacturerHint != null) {
            Spacer(Modifier.height(2.dp))
            SettingRow(
                title = "厂商自启动设置",
                supporting = manufacturerHint,
                shape = groupedListShape(permIndex++, permTotal),
            )
        }
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "高级权限（可选）",
            supporting = "Shizuku / Root / LSPosed / 无障碍 / 强制修复",
            shape = groupedListShape(permIndex, permTotal),
            trailingText = if (permAdvancedOpen) "收起" else "展开",
            onClick = { permAdvancedOpen = !permAdvancedOpen },
        )
        if (permAdvancedOpen) {
            Spacer(Modifier.height(2.dp))
            val advTotal = 5
            AdvancedRow(
                label = "Shizuku 保活",
                desc = "免 Root 写入电池优化白名单 / 通知监听权限",
                ok = keepAlive.shizukuAvailable,
                actionLabel = if (keepAlive.shizukuAvailable) "应用" else "授权",
                shape = groupedListShape(0, advTotal),
                onAction = { if (keepAlive.shizukuAvailable) vm.applyShizuku() else vm.requestShizuku() },
            )
            Spacer(Modifier.height(2.dp))
            AdvancedRow(
                label = "Root 保活",
                desc = "以 Root 执行白名单与厂商自启动命令（最彻底）",
                ok = keepAlive.rootAvailable,
                actionLabel = "应用",
                shape = groupedListShape(1, advTotal),
                onAction = { if (keepAlive.rootAvailable) vm.applyRoot() else vm.showToast("未检测到 Root（su）") },
            )
            Spacer(Modifier.height(2.dp))
            AdvancedRow(
                label = "LSPosed 保活",
                desc = "在 LSPosed 中激活本模块并勾选「系统(android)」作用域后自动生效，重启手机完成。" +
                    "未打勾 = 未检测到激活证据，以 LSPosed 管理器为准",
                ok = keepAlive.lspDetected == true,
                actionLabel = if (lspServiceState.bound && !lspServiceState.hasScope(
                        cc.ytdttj.noticleaner.keepalive.LspServiceDetector.SCOPE_SYSTEM_SERVER,
                    )
                ) "授权" else null,
                shape = groupedListShape(2, advTotal),
                onAction = {
                    if (lspServiceState.bound) vm.requestKeepAliveScope()
                    else vm.showToast("请先在 LSPosed 中启用本模块")
                },
            )
            Spacer(Modifier.height(2.dp))
            AdvancedRow(
                label = "无障碍保活",
                desc = "开启「保活守护」无障碍服务：系统绑定的第二条生命线，不读取屏幕内容",
                ok = keepAlive.accessibilityEnabled,
                actionLabel = if (keepAlive.accessibilityEnabled) null else "去开启",
                shape = groupedListShape(3, advTotal),
                onAction = { vm.openAccessibilitySettings() },
            )
            Spacer(Modifier.height(2.dp))
            AdvancedRow(
                label = "修复通知监听",
                desc = "监听断连且无法自愈时的强制修复（需 Shizuku 已授权或 Root）",
                ok = keepAlive.listenerEnabled,
                actionLabel = "修复",
                shape = groupedListShape(4, advTotal),
                onAction = { vm.repairListener() },
            )
        }

        // ================= 数据与诊断 =================
        var historyPanelOpen by remember { mutableStateOf(false) }
        val diagScope = rememberCoroutineScope()
        var diagExporting by remember { mutableStateOf(false) }
        var diagMsg by remember { mutableStateOf<String?>(null) }
        var csvExporting by remember { mutableStateOf(false) }
        var csvMsg by remember { mutableStateOf<String?>(null) }
        SectionHeader("数据与诊断")
        SettingRow(
            title = "导出诊断日志",
            supporting = "导出 ZIP：每个模块一个 log 文件，各自覆盖导出前完整 24 小时",
            shape = groupedListShape(0, 2),
            trailing = {
                OutlinedButton(
                    enabled = !diagExporting,
                    onClick = {
                        diagExporting = true
                        diagScope.launch {
                            diagMsg = runCatching {
                                val file = cc.ytdttj.noticleaner.diagnostics.DiagExporter.export(context)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    // Dev 9：分模块 ZIP（text/plain 会让部分接收端把 zip 当文本改名/打不开）
                                    type = "application/zip"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "分享诊断日志"),
                                )
                                "已导出: ${file.name}（${file.length() / 1024}KB）"
                            }.getOrElse { "导出失败: ${it.message}" }
                            diagExporting = false
                        }
                    },
                ) { Text(if (diagExporting) "导出中…" else "导出") }
            },
        )
        diagMsg?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "历史通知管理",
            supporting = "CSV 导出与保留天数",
            shape = groupedListShape(1, 2),
            trailingText = if (historyPanelOpen) "收起" else "展开",
            onClick = { historyPanelOpen = !historyPanelOpen },
        )
        if (historyPanelOpen) {
            Spacer(Modifier.height(2.dp))
            val histTotal = 2
            SettingRow(
                title = "导出历史通知 CSV",
                supporting = "全部历史（应用/包名/通道/标题/正文/AI率/学习状态），经系统分享",
                shape = groupedListShape(0, histTotal),
                trailing = {
                    OutlinedButton(
                        enabled = !csvExporting,
                        onClick = {
                            csvExporting = true
                            diagScope.launch {
                                csvMsg = runCatching {
                                    val file = cc.ytdttj.noticleaner.diagnostics.HistoryCsvExporter.export(context)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "分享历史通知 CSV"),
                                    )
                                    "已导出 ${file.name}（${file.length() / 1024}KB）"
                                }.getOrElse { "导出失败: ${it.message}" }
                                csvExporting = false
                            }
                        },
                    ) { Text(if (csvExporting) "导出中…" else "导出") }
                },
            )
            csvMsg?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            SettingSliderRow(
                title = "历史保留天数",
                supporting = "未学习的历史通知只保留 N 天，最新的顶掉最老的；已学习的标注不受影响",
                value = historyRetentionDays.toFloat(),
                valueRange = 1f..30f,
                steps = 28,
                shape = groupedListShape(1, histTotal),
                onValueCommit = { vm.setHistoryRetentionDays(it.toInt().coerceIn(1, 30)) },
                valueText = { "${it.toInt()} 天" },
            )
        }

        // ================= 关于 =================
        val updateVm: cc.ytdttj.noticleaner.update.UpdateViewModel =
            viewModel(key = "update", factory = viewModelFactory { initializer { cc.ytdttj.noticleaner.update.UpdateViewModel() } })
        val updateState by updateVm.state.collectAsState()
        var advancedOpen by remember { mutableStateOf(false) }
        var confirmResetModel by remember { mutableStateOf(false) }
        SectionHeader("关于")
        SettingRow(
            title = "检查更新",
            supporting = "当前版本 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME}",
            shape = groupedListShape(0, 3),
            trailing = {
                OutlinedButton(
                    enabled = updateState !is cc.ytdttj.noticleaner.update.UpdateState.Checking,
                    onClick = { updateVm.checkUpdate() },
                ) { Text("检查") }
            },
        )
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "参考开源项目",
            supporting = "本项目的借鉴、参考与依赖来源",
            shape = groupedListShape(1, 3),
            trailingText = "›",
            onClick = onOpenOpenSource,
        )
        Spacer(Modifier.height(2.dp))
        SettingRow(
            title = "AI 模型",
            supporting = modelInfo,
            shape = groupedListShape(2, 3),
            trailingText = if (advancedOpen) "收起" else "展开",
            onClick = { advancedOpen = !advancedOpen },
        )
        if (advancedOpen) {
            Spacer(Modifier.height(2.dp))
            SettingRow(
                title = "重置模型",
                supporting = "清除所有学习标注，回到预训练基线；已拦截统计不受影响，不可撤销",
                shape = groupedListShape(0, 1),
                trailing = {
                    OutlinedButton(onClick = { confirmResetModel = true }) { Text("重置") }
                },
            )
        }
        Spacer(Modifier.height(24.dp))

        if (confirmResetModel) {
            AlertDialog(
                onDismissRequest = { confirmResetModel = false },
                title = { Text("确认重置模型？") },
                text = { Text("将清除所有学习标注，模型回到预训练基线。已拦截统计不受影响，此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = { vm.resetModel(); confirmResetModel = false }) { Text("确认重置") }
                },
                dismissButton = { TextButton(onClick = { confirmResetModel = false }) { Text("取消") } },
            )
        }

        when (val s = updateState) {
            is cc.ytdttj.noticleaner.update.UpdateState.Checking -> UpdateStatusDialog(
                title = "正在检查更新…", text = "正在请求更新源",
                confirm = null, onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.UpToDate -> UpdateStatusDialog(
                title = "已是最新版本", text = "当前 v${cc.ytdttj.noticleaner.BuildConfig.VERSION_NAME} 已是最新",
                confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Error -> UpdateStatusDialog(
                title = "更新失败", text = s.message, confirm = "知道了", onDismiss = { updateVm.reset() },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Available -> AlertDialog(
                onDismissRequest = { updateVm.reset() },
                title = { Text("发现新版本 v${s.release.versionName}") },
                text = { Column { Text(s.release.notes.ifBlank { "无更新说明" }, style = MaterialTheme.typography.bodyMedium) } },
                confirmButton = { TextButton(onClick = { updateVm.startDownload(s.release) }) { Text("立即更新") } },
                dismissButton = { TextButton(onClick = { updateVm.reset() }) { Text("稍后再说") } },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.Downloading -> AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载 v${s.release.versionName}") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${s.progress}%", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { updateVm.cancelDownload() }) { Text("取消") } },
            )
            is cc.ytdttj.noticleaner.update.UpdateState.ReadyToInstall -> AlertDialog(
                onDismissRequest = { updateVm.reset() },
                title = { Text("下载完成") },
                text = { Text("点击「安装」打开系统安装器升级到 v${s.release.versionName}。") },
                confirmButton = { TextButton(onClick = { updateVm.install(s.release, s.file) }) { Text("安装") } },
                dismissButton = { TextButton(onClick = { updateVm.reset() }) { Text("稍后") } },
            )
            cc.ytdttj.noticleaner.update.UpdateState.Idle -> Unit
        }

        execResult?.let { result ->
            AlertDialog(
                onDismissRequest = { vm.dismissExecResult() },
                title = { Text("保活命令执行结果") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.dismissExecResult() }) { Text("完成") } },
            )
        }
    }
}

/** 深浅色三态：分段按钮直接呈现全部选项，无需点开二级界面 */
@Composable
private fun DarkModeRow(current: DarkMode, shape: Shape, onPick: (DarkMode) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("深浅色", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onPick(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DarkMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorRow(current: ThemeColor, shape: Shape, onPick: (ThemeColor) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("主题色", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "动态取色跟随系统壁纸（Android 12+），也可固定为下列色板",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeColor.entries.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color.swatch())
                            .clickable { onPick(color) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (current == color) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = color.label,
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusDialog(title: String, text: String, confirm: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirm ?: "关闭") }
        },
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean, shape: Shape, onAction: () -> Unit) {
    SettingRow(
        title = label,
        shape = shape,
        supporting = if (ok) null else "未开启，点击右侧按钮授权",
        trailing = {
            if (ok) {
                Text(
                    text = "已启用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(onClick = onAction) { Text("去开启") }
            }
        },
    )
}

@Composable
private fun AdvancedRow(
    label: String,
    desc: String,
    ok: Boolean,
    actionLabel: String?,
    shape: Shape,
    onAction: () -> Unit,
) {
    SettingRow(
        title = label,
        supporting = "$desc\n（${if (ok) "可用" else "不可用"}）",
        shape = shape,
        trailing = {
            if (actionLabel != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        },
    )
}

@Composable
internal fun settingsVmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            SettingsViewModel(ServiceLocator.settings, ServiceLocator.db.notificationDao())
        }
    }
