package io.github.vstory.hook.notifyfilter.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
// 1.3.2（P3-7①）：material-icons-extended → core（History/Rule 为 extended 独有，
// 就近替换为 core 内语义相近图标，debug DEX 体积显著缩小）
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.notify.CleanerListenerService
import io.github.vstory.hook.notifyfilter.ui.history.HistoryScreen
import io.github.vstory.hook.notifyfilter.ui.permission.OnboardingScreen
import io.github.vstory.hook.notifyfilter.ui.permission.PermissionLostDialog
import io.github.vstory.hook.notifyfilter.ui.permission.checkPermissions
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerScreen
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerSession
import io.github.vstory.hook.notifyfilter.ui.rules.RuleEditScreen
import io.github.vstory.hook.notifyfilter.ui.rules.RulesScreen
import io.github.vstory.hook.notifyfilter.ui.settings.SettingsScreen
import io.github.vstory.hook.notifyfilter.ui.settings.StatsDetailScreen
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ---- 权限未授予 / 失效提醒（1.1.8）----
    private var alertVisible by mutableStateOf(false)
    private var lostListener by mutableStateOf(false)
    private var lostBattery by mutableStateOf(false)
    private var lostNotifications by mutableStateOf(false)

    /** null=读取中，false=首次使用（初始化未走完），true=已完成初始化 */
    private var onboardingDone by mutableStateOf<Boolean?>(null)

    /** 本进程是否已发起过通知权限申请（跨进程的去重状态见 settings.notifPermissionAsked） */
    private var notifPermAskedThisRun = false

    /**
     * 1.2.2：通知发送权限申请（Android 13+ 常驻保活/更新提醒通知必需）。
     *
     * ⚠️ 结果回调里**只能刷新状态，绝不能再次 launch**。此处曾是无条件再申请，
     * 构成「申请→回调→申请」自环，两种崩坏表现：
     * ① 用户拒绝：系统在回调前已复位 mHasCurrentPermissionsRequest，于是每次
     *    都算全新申请 → 授权框无限连弹；
     * ② 上次申请未答复时再申请：系统走 requestPermissions 的
     *    `mHasCurrentPermissionsRequest` 分支——**同步**回调且不复位标志 →
     *    纯同步自递归，几百层后 StackOverflowError（首次安装必现：
     *    onCreate 刚发出申请，紧随的 onResume 又发一次）。
     */
    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            refreshPermissionState()
        }

    // ---- 进入应用自动检查更新（1.1.8）：onCreate/onNewIntent 递增触发 ----
    private var entryCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
        // 入口检查：图标/保活通知进入都会走 onCreate 或 onNewIntent
        refreshPermissionState()
        maybeRequestNotifPermission()
        applyEntrySideEffects()
        // 是否走完初始化决定「权限失效」提醒能否出现：首次使用时权限本就未授予，
        // 那是待办事项而非失效，报「权限已失效」既错时机又吓人
        lifecycleScope.launch {
            onboardingDone = ServiceLocator.settings.onboardingDone.first()
            if (onboardingDone == true) maybeAlertLostPermissions()
        }
        entryCount++
    }

    override fun onResume() {
        super.onResume()
        // 只刷新：从授权框/系统设置返回后据此更新或关闭提醒框。
        // ⚠️ 不在此发起申请——上一次申请尚未答复时再申请会触发系统同步回调分支（见 notifPermLauncher）
        refreshPermissionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshPermissionState()
        maybeRequestNotifPermission()
        applyEntrySideEffects()
        maybeAlertLostPermissions()
        entryCount++
    }

    /** 读取权限状态并同步提示位；无副作用，任何时机可调（onResume 每次回到前台都调） */
    private fun refreshPermissionState() {
        val s = checkPermissions(this)
        lostListener = !s.listenerEnabled
        lostBattery = !s.batteryWhitelisted
        lostNotifications = !s.notificationsGranted
        // 全部就绪 → 撤下提醒框；仍有缺失则保持，避免从设置页返回瞬间闪断
        if (!lostListener && !lostBattery && !lostNotifications) alertVisible = false
    }

    /**
     * 提示「权限缺失」。仅在初始化已完成时弹：首次使用尚未授权属正常流程，
     * 不适用「失效」语义（PermissionLostDialog 的文案与操作都面向已配置过的用户）。
     */
    private fun maybeAlertLostPermissions() {
        if (onboardingDone != true) return
        if (lostListener || lostBattery || lostNotifications) alertVisible = true
    }

    /**
     * 自动发起一次通知发送权限申请。三道守卫缺一不可：
     * SDK < 33 无此运行时权限；已授予无需申请；已申请过（含跨进程）不再自动弹。
     */
    private fun maybeRequestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (notifPermAskedThisRun) return
        if (checkPermissions(this).notificationsGranted) return
        notifPermAskedThisRun = true
        lifecycleScope.launch {
            if (ServiceLocator.settings.notifPermissionAsked.first()) return@launch
            ServiceLocator.settings.setNotifPermissionAsked()
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 每次进入应用要做的副作用（与权限提示无关）：
     * 1.1.13 重应用「多任务隐藏」——该标志只对当前任务实例生效，任务被系统重建后
     * （如进程死亡后由保活通知拉起）标志丢失，必须重新应用；
     * 监听权限在但绑定断开时请求系统重绑（自愈）。
     */
    private fun applyEntrySideEffects() {
        lifecycleScope.launch {
            applyExcludeFromRecents(ServiceLocator.settings.excludeFromRecents.first())
        }
        if (checkPermissions(this).listenerEnabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!CleanerListenerService.isListenerConnected()) {
                    android.util.Log.i("NfWatch", "app entry → rebind requested")
                    CleanerListenerService.requestRebindIfEnabled(this@MainActivity)
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        // 权限初始化流程（1.1.8）：首次启动（含老版本升级后首次打开 1.1.8）走一遍。
        // 状态提升到 Activity 字段——onCreate 里的「失效提醒」判定也要用同一份读取结果
        when (onboardingDone) {
            null -> Box(Modifier.fillMaxSize()) // DataStore 读取中
            false -> OnboardingScreen(
                onFinish = {
                    onboardingDone = true
                    lifecycleScope.launch { ServiceLocator.settings.setOnboardingDone() }
                },
            )
            else -> {
                MainScaffold()
                // 进入应用自动检查更新（1.1.8）：有更新弹窗，无更新静默
                val updateVm: io.github.vstory.hook.notifyfilter.update.UpdateViewModel =
                    viewModel(key = "updateEntry", factory = viewModelFactory {
                        initializer { io.github.vstory.hook.notifyfilter.update.UpdateViewModel() }
                    })
                LaunchedEffect(entryCount) {
                    val s = updateVm.state.value
                    // 正在下载/待安装时不打断，避免重复检查取消进行中的下载
                    if (s !is io.github.vstory.hook.notifyfilter.update.UpdateState.Downloading &&
                        s !is io.github.vstory.hook.notifyfilter.update.UpdateState.ReadyToInstall
                    ) {
                        updateVm.checkUpdate()
                    }
                }
                io.github.vstory.hook.notifyfilter.ui.update.UpdatePromptDialog(vm = updateVm, onDismiss = {})
                if (alertVisible && (lostListener || lostBattery || lostNotifications)) {
                    PermissionLostDialog(
                        lostListener = lostListener,
                        lostBattery = lostBattery,
                        lostNotifications = lostNotifications,
                        onDismiss = { alertVisible = false },
                    )
                }
            }
        }
    }

    /**
     * 应用「多任务界面隐藏」（API 29+，直接对当前任务设置标记，不重建任务）。
     * @return 是否成功应用（旧系统返回 false）
     */
    fun applyExcludeFromRecents(exclude: Boolean): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.firstOrNull()?.setExcludeFromRecents(exclude)
            am?.appTasks != null
        }.getOrDefault(false)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("history", "历史", Icons.Filled.DateRange),
    Tab("rules", "规则", Icons.AutoMirrored.Filled.List),
    Tab("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "history"
    fun onTabClick(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { onTabClick(tab.route) },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
    ) { padding ->
        MainNavHost(navController = navController, modifier = Modifier.padding(padding))
    }
}

/** 主导航（路由定义唯一） */
@Composable
private fun MainNavHost(navController: androidx.navigation.NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = "history",
        modifier = modifier,
    ) {
        composable("history") {
            HistoryScreen(
                onOpenAppPicker = {
                    // initial 已由 HistoryScreen 写入 AppPickerSession；结果同样经 result 回读
                    navController.navigate("apppicker/筛选APP")
                },
            )
        }
        composable("rules") {
            RulesScreen(
                onOpenRuleEdit = { navController.navigate("ruleedit") },
                onOpenAppPicker = { navController.navigate("apppicker/白名单") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onOpenStats = { navController.navigate("stats/$it") },
                onOpenOpenSource = { navController.navigate("opensource") },
            )
        }
        composable("opensource") {
            io.github.vstory.hook.notifyfilter.ui.settings.OpenSourceScreen(onBack = { navController.popBackStack() })
        }
        composable("stats/{mode}") { entry ->
            val mode = entry.arguments?.getString("mode") ?: "filtered"
            StatsDetailScreen(mode, onBack = { navController.popBackStack() })
        }
        composable("ruleedit") {
            RuleEditScreen(
                onBack = { navController.popBackStack() },
                openAppPicker = { navController.navigate("apppicker/选择 APP") },
            )
        }
        composable("apppicker/{title}") { entry ->
            val title = entry.arguments?.getString("title") ?: "选择 APP"
            AppPickerScreen(
                title = title,
                multiSelect = true,
                onBack = { navController.popBackStack() },
                onConfirm = {
                    // 白名单模式：直接入库；规则模式：结果由 RuleEditScreen 回读
                    if (title == "白名单") {
                        val scope = io.github.vstory.hook.notifyfilter.ServiceLocator.appScope
                        scope.launch {
                            it.forEach { (pkg, label) ->
                                ServiceLocator.db.whitelistDao().insert(
                                    io.github.vstory.hook.notifyfilter.data.db.WhitelistEntity(packageName = pkg, appName = label),
                                )
                            }
                        }
                    } else {
                        AppPickerSession.result = it
                    }
                    navController.popBackStack()
                },
            )
        }
    }
}
