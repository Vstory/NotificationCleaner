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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import io.github.vstory.hook.notifyfilter.App
import io.github.vstory.hook.notifyfilter.R
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.BottomBarMode
import io.github.vstory.hook.notifyfilter.data.FloatingBottomBarStyle
import io.github.vstory.hook.notifyfilter.data.ThemeConfig
import io.github.vstory.hook.notifyfilter.notify.CleanerListenerService
import io.github.vstory.hook.notifyfilter.ui.component.blur.BlurredBar
import io.github.vstory.hook.notifyfilter.ui.component.blur.rememberBlurBackdrop
import io.github.vstory.hook.notifyfilter.ui.component.liquid.IosLiquidGlassNavigationBar
import io.github.vstory.hook.notifyfilter.ui.history.HistoryScreen
import io.github.vstory.hook.notifyfilter.ui.theme.LocalAppDarkMode
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
import io.github.vstory.hook.notifyfilter.ui.settings.ThemeSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
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
        // ⚠️ 必须显式关掉这层遮罩。enableEdgeToEdge 的默认 navigationBarStyle 是
        //    SystemBarStyle.auto（nightMode = MODE_NIGHT_AUTO），而 EdgeToEdgeApi35 里是
        //    `isNavigationBarContrastEnforced = (nightMode == MODE_NIGHT_AUTO)`
        //    ⇒ API 35+ 上它反而把遮罩打开：浅色下导航栏区域泛白，悬浮底栏下方多出一条色带。
        //    全屏内容自己让位到导航栏之上，不需要系统这层保护色。
        window.isNavigationBarContrastEnforced = false
        setContent {
            val themeConfig by ServiceLocator.settings.themeConfig.collectAsState(initial = ThemeConfig())
            AppTheme(themeConfig) {
                AppRoot(
                    themeConfig = themeConfig,
                    onThemeConfigChange = { next ->
                        ServiceLocator.appScope.launch { ServiceLocator.settings.setThemeConfig(next) }
                    },
                )
            }
        }
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
    private fun AppRoot(
        themeConfig: ThemeConfig,
        onThemeConfigChange: (ThemeConfig) -> Unit,
    ) {
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
                val updateVm: io.github.vstory.hook.notifyfilter.update.UpdateViewModel =
                    viewModel(key = "updateEntry", factory = viewModelFactory {
                        initializer { io.github.vstory.hook.notifyfilter.update.UpdateViewModel() }
                    })
                MainScaffold(
                    themeConfig = themeConfig,
                    onThemeConfigChange = onThemeConfigChange,
                    onPredictiveBackChange = if (android.os.Build.VERSION.SDK_INT >= 34) {
                        { enabled ->
                            // 同步落盘：recreate 后新实例立刻读回，异步写会让开关回弹
                            kotlinx.coroutines.runBlocking {
                                ServiceLocator.settings.setPredictiveBack(enabled)
                            }
                            App.setEnableOnBackInvokedCallback(applicationInfo, enabled)
                            recreate()
                        }
                    } else {
                        null
                    },
                    // 这两个弹窗是跨页全局的，只能由外壳的宿主 Scaffold 渲染
                    // （调用点必须在 Scaffold 作用域内的原因见 MainScaffold 的 globalOverlays）
                    globalOverlays = {
                        io.github.vstory.hook.notifyfilter.ui.update.UpdatePromptDialog(vm = updateVm, onDismiss = {})
                        if (alertVisible && (lostListener || lostBattery || lostNotifications)) {
                            PermissionLostDialog(
                                lostListener = lostListener,
                                lostBattery = lostBattery,
                                lostNotifications = lostNotifications,
                                onDismiss = { alertVisible = false },
                            )
                        }
                    },
                )
                // 进入应用自动检查更新（1.1.8）：有更新弹窗，无更新静默
                LaunchedEffect(entryCount) {
                    val s = updateVm.state.value
                    // 正在下载/待安装时不打断，避免重复检查取消进行中的下载
                    if (s !is io.github.vstory.hook.notifyfilter.update.UpdateState.Downloading &&
                        s !is io.github.vstory.hook.notifyfilter.update.UpdateState.ReadyToInstall
                    ) {
                        updateVm.checkUpdate()
                    }
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

@Composable
private fun navigationTabs(): List<Tab> = listOf(
    Tab(stringResource(R.string.nav_home), MiuixIcons.Home),
    Tab(stringResource(R.string.nav_rules), MiuixIcons.ListView),
    Tab(stringResource(R.string.nav_settings), MiuixIcons.Settings),
)

@Composable
fun MainScaffold(
    themeConfig: ThemeConfig,
    onThemeConfigChange: (ThemeConfig) -> Unit,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    globalOverlays: @Composable () -> Unit = {},
) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember { Navigator(backStack) }
    val tabs = navigationTabs()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val mainPagerState = rememberMainPagerState(pagerState)

    // 横移返回方向取物理方向（不随布局方向镜像），RTL 下必须反过来
    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val storedSwipeDismiss by ServiceLocator.settings.swipeDismiss.collectAsState(initial = true)
    var swipeDismissEnabled by remember(storedSwipeDismiss) { mutableStateOf(storedSwipeDismiss) }
    val swipeDismiss = if (swipeDismissEnabled) swipeBackDirection else null

    val storedPredictiveBack by ServiceLocator.settings.predictiveBack.collectAsState(initial = true)
    var predictiveBackEnabled by remember(storedPredictiveBack) { mutableStateOf(storedPredictiveBack) }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        // Overlay* 组件把弹窗注册给「最近的 Scaffold」的 popup 宿主（LocalDialogStates）；在
        // Scaffold 之外调用只会写进 staticCompositionLocalOf 的默认状态表，而那张表没有任何
        // 宿主读它 —— 弹窗静默不出现（无异常、无日志）。页面 Scaffold 都藏在 NavDisplay 的
        // entry 里，跨页弹窗（更新提示、权限失效提醒）够不到，因此在外层补一个只作宿主的
        // Scaffold：它不设顶/底栏，content 也不消费它的 padding，尺寸与内边距全交给 entry。
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.pop() },
                effects = NavDisplayEffects(
                    cornerClipRadius = rememberNavSystemCornerRadius(),
                ),
            ) {
                entry<Route.Main>(swipeDismiss = swipeDismiss) {
                    MainPage(
                        mainPagerState = mainPagerState,
                        navigator = navigator,
                        tabs = tabs,
                        themeConfig = themeConfig,
                    )
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
                entry<Route.ThemeSettings>(swipeDismiss = swipeDismiss) {
                    ThemeSettingsScreen(
                        themeConfig = themeConfig,
                        onThemeConfigChange = onThemeConfigChange,
                        predictiveBackEnabled = predictiveBackEnabled,
                        onPredictiveBackChange = onPredictiveBackChange?.let { apply ->
                            { enabled ->
                                predictiveBackEnabled = enabled
                                apply(enabled)
                            }
                        },
                        swipeDismissEnabled = swipeDismissEnabled,
                        onSwipeDismissChange = { enabled ->
                            swipeDismissEnabled = enabled
                            ServiceLocator.appScope.launch { ServiceLocator.settings.setSwipeDismiss(enabled) }
                        },
                        onBack = { navigator.pop() },
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
            globalOverlays()
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
private fun MainPage(
    mainPagerState: MainPagerState,
    navigator: Navigator,
    tabs: List<Tab>,
    themeConfig: ThemeConfig,
) {
    val floatingNavBar = themeConfig.floatingBottomBar
    val floatingBarStyle = themeConfig.floatingBottomBarStyle
    val bottomBarMode = themeConfig.bottomBarMode

    val bottomBarBackdrop = rememberBlurBackdrop(themeConfig.blurEnabled)
    val bottomBarBlurActive = bottomBarBackdrop != null
    val barColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val floatingBarColor = if (bottomBarBlurActive) {
        Color.Transparent
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    val floatingPillRadius = 50.dp
    val floatingBarShape = RoundedCornerShape(floatingPillRadius)
    val isDark = LocalAppDarkMode.current
    val floatingHighlight = remember(isDark) {
        if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    }
    val floatingBarModifier = if (bottomBarBlurActive) {
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

    val bottomBarDisplayMode = when (bottomBarMode) {
        BottomBarMode.IconAndText -> NavigationBarDisplayMode.IconAndText
        BottomBarMode.IconOnly -> NavigationBarDisplayMode.IconOnly
    }
    val showBottomBarLabels = bottomBarMode == BottomBarMode.IconAndText
    val navigationItems = tabs.map { NavigationItem(label = it.label, icon = it.icon) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (floatingNavBar) {
                if (floatingBarStyle == FloatingBottomBarStyle.IosLike) {
                    IosLiquidGlassNavigationBar(
                        items = navigationItems,
                        selectedIndex = mainPagerState.selectedPage,
                        onItemClick = { mainPagerState.animateToPage(it) },
                        backdrop = bottomBarBackdrop,
                        isBlurActive = bottomBarBlurActive,
                        isDark = isDark,
                        showLabels = showBottomBarLabels,
                    )
                } else {
                    FloatingNavigationBar(
                        modifier = floatingBarModifier,
                        color = floatingBarColor,
                        cornerRadius = floatingPillRadius,
                    ) {
                        navigationItems.forEachIndexed { index, item ->
                            MiuixFloatingNavigationBarItem(
                                item = item,
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                showLabel = showBottomBarLabels,
                            )
                        }
                    }
                }
            } else {
                BlurredBar(backdrop = bottomBarBackdrop, blurActive = bottomBarBlurActive) {
                    NavigationBar(color = barColor, mode = bottomBarDisplayMode) {
                        navigationItems.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                icon = item.icon,
                                label = item.label,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val bottomPadding = padding.calculateBottomPadding()
        HorizontalPager(
            modifier = if (bottomBarBlurActive) {
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
                    onOpenTheme = { navigator.push(Route.ThemeSettings) },
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
 * 胶囊里要「图标 + 文字」只能自己组合。
 */
@Composable
private fun MiuixFloatingNavigationBarItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = item.icon,
            contentDescription = if (showLabel) null else item.label,
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = item.label,
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
