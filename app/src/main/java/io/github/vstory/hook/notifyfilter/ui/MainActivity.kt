package io.github.vstory.hook.notifyfilter.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.notify.CleanerListenerService
import io.github.vstory.hook.notifyfilter.ui.component.blur.LocalBlurEnabled
import io.github.vstory.hook.notifyfilter.ui.component.blur.rememberBlurBackdrop
import io.github.vstory.hook.notifyfilter.ui.history.HistoryScreen
import io.github.vstory.hook.notifyfilter.ui.nav.LocalNavigator
import io.github.vstory.hook.notifyfilter.ui.nav.MainPagerState
import io.github.vstory.hook.notifyfilter.ui.nav.Navigator
import io.github.vstory.hook.notifyfilter.ui.nav.Route
import io.github.vstory.hook.notifyfilter.ui.nav.rememberMainPagerState
import io.github.vstory.hook.notifyfilter.ui.permission.OnboardingScreen
import io.github.vstory.hook.notifyfilter.ui.permission.PermissionLostDialog
import io.github.vstory.hook.notifyfilter.ui.permission.checkPermissions
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerScreen
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerSession
import io.github.vstory.hook.notifyfilter.ui.rules.RuleEditScreen
import io.github.vstory.hook.notifyfilter.ui.rules.RulesScreen
import io.github.vstory.hook.notifyfilter.ui.settings.AboutScreen
import io.github.vstory.hook.notifyfilter.ui.settings.AdvancedPermissionScreen
import io.github.vstory.hook.notifyfilter.ui.settings.AiModelScreen
import io.github.vstory.hook.notifyfilter.ui.settings.SettingsScreen
import io.github.vstory.hook.notifyfilter.ui.settings.StatsDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("主页", MiuixIcons.Home),
    Tab("规则", MiuixIcons.ListView),
    Tab("设置", MiuixIcons.Settings),
)

@Composable
fun MainScaffold() {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val mainPagerState = rememberMainPagerState(pagerState)

    // 横移返回方向取物理方向（不随布局方向镜像），RTL 下必须反过来
    val swipeDismiss = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<Route.Main>(swipeDismiss = swipeDismiss) {
                MainPage(mainPagerState = mainPagerState, navigator = navigator)
            }
            entry<Route.Advanced>(swipeDismiss = swipeDismiss) {
                AdvancedPermissionScreen(onBack = { navigator.pop() })
            }
            entry<Route.AiModel>(swipeDismiss = swipeDismiss) {
                AiModelScreen(
                    onBack = { navigator.pop() },
                    onOpenLearned = { navigator.push(Route.Stats("learned")) },
                )
            }
            entry<Route.About>(swipeDismiss = swipeDismiss) {
                val uriHandler = LocalUriHandler.current
                AboutScreen(
                    onBack = { navigator.pop() },
                    onOpenUrl = { url -> uriHandler.openUri(url) },
                )
            }
            entry<Route.Stats>(swipeDismiss = swipeDismiss) { route ->
                StatsDetailScreen(route.mode, onBack = { navigator.pop() })
            }
            entry<Route.RuleEdit>(swipeDismiss = swipeDismiss) {
                RuleEditScreen(
                    onBack = { navigator.pop() },
                    openAppPicker = { navigator.push(Route.AppPicker("选择 APP")) },
                )
            }
            entry<Route.AppPicker>(swipeDismiss = swipeDismiss) { route ->
                AppPickerScreen(
                    title = route.title,
                    multiSelect = true,
                    onBack = { navigator.pop() },
                    onConfirm = {
                        // 白名单模式：直接入库；规则模式：结果由 RuleEditScreen 回读
                        if (route.title == "白名单") {
                            ServiceLocator.appScope.launch {
                                it.forEach { (pkg, label) ->
                                    ServiceLocator.db.whitelistDao().insert(
                                        io.github.vstory.hook.notifyfilter.data.db.WhitelistEntity(packageName = pkg, appName = label),
                                    )
                                }
                            }
                        } else {
                            AppPickerSession.result = it
                        }
                        navigator.pop()
                    },
                )
            }
        }
    }
}

/**
 * 底栏 + 三个 Tab 的横向 Pager；二级页由 NavDisplay 独立渲染，不带底栏。
 *
 * Tab 页各自持有 Scaffold + TopAppBar（与 Mishka 同构），本层因此**不**对整个 Pager 施加 padding，
 * 只把底栏高度透传下去——否则内层 TopAppBar 会二次吃一遍状态栏 inset。
 */
@Composable
private fun MainPage(mainPagerState: MainPagerState, navigator: Navigator) {
    val floatingNavBar by ServiceLocator.settings.floatingNavBar.collectAsState(initial = true)
    // 贴底档是不透明底栏，用不到「每帧录一次图层」的开销：不创建也不挂 backdrop
    val bottomBarBackdrop = rememberBlurBackdrop(enabled = floatingNavBar && LocalBlurEnabled.current)
    val floatingBarColor = if (bottomBarBackdrop != null) {
        Color.Transparent
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val floatingPillRadius = 50.dp
    val floatingBarShape = RoundedCornerShape(floatingPillRadius)
    val isDark = isSystemInDarkTheme()
    val floatingHighlight = remember(isDark) {
        if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }
    val floatingBarModifier = if (bottomBarBackdrop != null) {
        Modifier.textureBlur(
            backdrop = bottomBarBackdrop,
            shape = floatingBarShape,
            blurRadius = 25f,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
                ),
            ),
            highlight = floatingHighlight,
        )
    } else {
        Modifier
    }

    Scaffold(
        bottomBar = {
            if (floatingNavBar) {
                FloatingNavigationBar(
                    modifier = floatingBarModifier,
                    color = floatingBarColor,
                    cornerRadius = floatingPillRadius,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        LabeledFloatingBarItem(
                            selected = mainPagerState.selectedPage == index,
                            onClick = { mainPagerState.animateToPage(index) },
                            icon = tab.icon,
                            label = tab.label,
                        )
                    }
                }
            } else {
                DockedNavigationBar(
                    selectedIndex = mainPagerState.selectedPage,
                    onSelect = { mainPagerState.animateToPage(it) },
                )
            }
        },
    ) { padding ->
        val bottomPadding = padding.calculateBottomPadding()
        HorizontalPager(
            modifier = if (bottomBarBackdrop != null) {
                Modifier.fillMaxSize().layerBackdrop(bottomBarBackdrop)
            } else {
                Modifier.fillMaxSize()
            },
            state = mainPagerState.pagerState,
            verticalAlignment = Alignment.Top,
            overscrollEffect = null,
        ) { page ->
            when (page) {
                0 -> HistoryScreen(
                    bottomPadding = bottomPadding,
                    onOpenAppPicker = {
                        // initial 已由 HistoryScreen 写入 AppPickerSession；结果同样经 result 回读
                        navigator.push(Route.AppPicker("筛选APP"))
                    },
                )
                1 -> RulesScreen(
                    bottomPadding = bottomPadding,
                    onOpenRuleEdit = { navigator.push(Route.RuleEdit) },
                    onOpenAppPicker = { navigator.push(Route.AppPicker("白名单")) },
                )
                else -> SettingsScreen(
                    bottomPadding = bottomPadding,
                    onOpenStats = { navigator.push(Route.Stats(it)) },
                    onOpenAbout = { navigator.push(Route.About) },
                    onOpenAdvanced = { navigator.push(Route.Advanced) },
                    onOpenAiModel = { navigator.push(Route.AiModel) },
                )
            }
        }
        LaunchedEffect(mainPagerState.pagerState.currentPage) {
            mainPagerState.syncPage()
        }
    }
}

/**
 * 自建件：库的 FloatingNavigationBarItem **只画图标**，label 仅作 contentDescription，
 * 胶囊里要「图标 + 文字」只能自己组合；不透明度档位沿用 miuix 的 Defaults。
 */
@Composable
private fun LabeledFloatingBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val base = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = base.copy(
        alpha = base.alpha * when {
            isPressed -> if (selected) {
                NavigationBarDefaults.SelectedPressedAlpha
            } else {
                NavigationBarDefaults.UnselectedPressedAlpha
            }

            selected -> 1f
            else -> NavigationBarDefaults.UnselectedAlpha
        },
    )

    Column(
        modifier = Modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 10.dp, bottom = 2.dp)
                .size(FloatingNavigationBarDefaults.IconSize),
        )
        Text(
            text = label,
            color = tint,
            fontSize = NavigationBarDefaults.LabelFontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/** 贴底普通底栏档：不透明 surface 底色 + 顶部分隔线，作为悬浮毛玻璃的兼容兜底（低端机 / 不要玻璃观感）。 */
@Composable
private fun DockedNavigationBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}
