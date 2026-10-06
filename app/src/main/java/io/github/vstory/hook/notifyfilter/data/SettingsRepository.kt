package io.github.vstory.hook.notifyfilter.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 设置（Plan.md §4 / §6.3）：
 * - threshold：过滤阈值 0.5~1.0，默认 0.8
 * - interceptMode：true=达到阈值即清除；false=仅标记不拦截（观察模式）
 * - filteredAi/filteredRule：累计拦截计数（持久化，历史 7 天过期不影响）
 */
class SettingsRepository(private val context: Context) {
    private val keyThreshold = floatPreferencesKey("threshold")
    private val keyIntercept = booleanPreferencesKey("intercept_mode")
    private val keyHideRecents = booleanPreferencesKey("exclude_from_recents")
    private val keyFilteredAi = intPreferencesKey("filtered_ai_count")
    private val keyFilteredRule = intPreferencesKey("filtered_rule_count")
    private val keyOnboardingDone = booleanPreferencesKey("onboarding_done")

    // ---- 1.2.0（ImprovePlan P2-2）：拦截计数内存累积 + 500ms 批量落盘 ----
    // 拦截风暴（一次弹 N 条广告）时不再逐条全文件读改写 DataStore。
    // 1.3.2（P3-3）：常驻 ticker 改为按需启动的一次性 flush 协程——
    // incrementFiltered 时才起协程（含 500ms 防抖窗口），写完且无新增即退出；
    // 零拦截期间完全休眠，不再每秒唤醒 2 次。
    private val pendingAi = java.util.concurrent.atomic.AtomicInteger()
    private val pendingRule = java.util.concurrent.atomic.AtomicInteger()

    private val countFlushScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private var flushJob: kotlinx.coroutines.Job? = null

    init {
        // 冷启动时补一次 flush（进程被杀可能留下未落盘的增量；无增量时立即退出，零开销）
        countFlushScope.launch { flushPendingCounters() }
    }

    val threshold: Flow<Float> = context.dataStore.data.map { it[keyThreshold] ?: 0.8f }
    val interceptMode: Flow<Boolean> = context.dataStore.data.map { it[keyIntercept] ?: true }

    /** 在系统多任务界面隐藏本 APP 的后台卡片（防误滑删除，切换后重建任务生效） */
    val excludeFromRecents: Flow<Boolean> = context.dataStore.data.map { it[keyHideRecents] ?: false }

    // ---- 通知类型保护（默认全开 = 改造前行为）----
    private val keyProtectMedia = booleanPreferencesKey("protect_media")
    private val keyProtectConversation = booleanPreferencesKey("protect_conversation")
    private val keyProtectOngoing = booleanPreferencesKey("protect_ongoing")

    val protectTypes: Flow<ProtectTypes> = context.dataStore.data.map { p ->
        ProtectTypes(
            media = p[keyProtectMedia] ?: true,
            conversation = p[keyProtectConversation] ?: true,
            ongoing = p[keyProtectOngoing] ?: true,
        )
    }

    suspend fun setProtectTypes(value: ProtectTypes) {
        context.dataStore.edit {
            it[keyProtectMedia] = value.media
            it[keyProtectConversation] = value.conversation
            it[keyProtectOngoing] = value.ongoing
        }
    }

    // ---- 外观与主题（外观与主题子页；底栏形态与样式同属这里的 ThemeConfig）----
    private val keyFloatingNavBar = booleanPreferencesKey("floating_nav_bar")
    private val keyFloatingNavBarStyle = stringPreferencesKey("floating_nav_bar_style")
    private val keyBottomBarMode = stringPreferencesKey("bottom_bar_mode")
    private val keyThemeColorMode = intPreferencesKey("theme_color_mode")
    private val keyThemePureBlack = booleanPreferencesKey("theme_pure_black")
    private val keyThemeMonet = booleanPreferencesKey("theme_monet")
    private val keyThemePaletteStyle = stringPreferencesKey("theme_palette_style")
    private val keyThemeAccentColor = stringPreferencesKey("theme_accent_color")
    private val keyThemeBlur = booleanPreferencesKey("theme_blur")
    private val keyThemeBlurStyle = stringPreferencesKey("theme_blur_style")
    private val keyThemeDensityScale = floatPreferencesKey("theme_density_scale")
    private val keySwipeDismiss = booleanPreferencesKey("swipe_dismiss")
    private val keyPredictiveBack = booleanPreferencesKey("predictive_back")

    /**
     * 外观与主题的单一真相。
     * 底栏默认 true = 悬浮毛玻璃胶囊（改动前的唯一形态）；贴底档是兼容与可读性的兜底，
     * 默认值必须保持已发布形态，否则老用户升级后观感突变。
     */
    val themeConfig: Flow<ThemeConfig> = context.dataStore.data.map { p ->
        ThemeConfig(
            colorMode = p[keyThemeColorMode] ?: 0,
            pureBlack = p[keyThemePureBlack] ?: false,
            useMonet = p[keyThemeMonet] ?: false,
            paletteStyle = themePaletteStyleFromStorage(
                p[keyThemePaletteStyle] ?: ThemePaletteStyle.TonalSpot.name,
            ),
            accentColor = ThemeAccentColor.fromStorage(
                p[keyThemeAccentColor] ?: ThemeAccentColor.Default.storageValue,
            ),
            blurEnabled = p[keyThemeBlur] ?: true,
            topBarBlurStyle = TopBarBlurStyle.fromStorage(
                p[keyThemeBlurStyle] ?: TopBarBlurStyle.Gaussian.storageValue,
            ),
            floatingBottomBar = p[keyFloatingNavBar] ?: true,
            floatingBottomBarStyle = FloatingBottomBarStyle.fromStorage(
                p[keyFloatingNavBarStyle] ?: FloatingBottomBarStyle.Miuix.storageValue,
            ),
            bottomBarMode = BottomBarMode.fromStorage(
                p[keyBottomBarMode] ?: BottomBarMode.IconAndText.storageValue,
            ),
            densityScale = normalizeDensityScale(p[keyThemeDensityScale] ?: DefaultDensityScale),
        )
    }

    suspend fun setThemeConfig(config: ThemeConfig) {
        context.dataStore.edit {
            it[keyThemeColorMode] = config.colorMode
            it[keyThemePureBlack] = config.pureBlack
            it[keyThemeMonet] = config.useMonet
            it[keyThemePaletteStyle] = config.paletteStyle.name
            it[keyThemeAccentColor] = config.accentColor.storageValue
            it[keyThemeBlur] = config.blurEnabled
            it[keyThemeBlurStyle] = config.topBarBlurStyle.storageValue
            it[keyFloatingNavBar] = config.floatingBottomBar
            it[keyFloatingNavBarStyle] = config.floatingBottomBarStyle.storageValue
            it[keyBottomBarMode] = config.bottomBarMode.storageValue
            it[keyThemeDensityScale] = normalizeDensityScale(config.densityScale)
        }
    }

    /** 横移返回手势，默认启用 */
    val swipeDismiss: Flow<Boolean> = context.dataStore.data.map { it[keySwipeDismiss] ?: true }

    suspend fun setSwipeDismiss(value: Boolean) {
        context.dataStore.edit { it[keySwipeDismiss] = value }
    }

    /**
     * 预测性返回手势；默认 true = 保持 targetSdk 33+ 的出厂行为（manifest 未声明
     * enableOnBackInvokedCallback 时系统默认开启）。关掉才需要改写 ApplicationInfo 并重建 Activity。
     */
    val predictiveBack: Flow<Boolean> = context.dataStore.data.map { it[keyPredictiveBack] ?: true }

    suspend fun setPredictiveBack(value: Boolean) {
        context.dataStore.edit { it[keyPredictiveBack] = value }
    }

    /** 累计拦截数（常驻通知展示）：AI 拦截 / 用户规则拦截 */
    val filteredAiCount: Flow<Int> = context.dataStore.data.map { it[keyFilteredAi] ?: 0 }
    val filteredRuleCount: Flow<Int> = context.dataStore.data.map { it[keyFilteredRule] ?: 0 }

    /** 权限初始化流程已完成（1.1.8 首次引入；默认 false → 老版本升级后也会走一遍初始化） */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[keyOnboardingDone] ?: false }

    // ---- 通知发送权限（1.2.2）：系统授权框只自动弹一次 ----
    private val keyNotifPermAsked = booleanPreferencesKey("notif_perm_asked")

    /**
     * 是否已自动发起过 POST_NOTIFICATIONS 申请。
     * 拒绝后系统框不会自动再弹（Android 13+ 每次调用都会重新弹出），
     * 否则用户每次冷启动都被问一遍；后续引导改走「权限未授予」弹窗跳应用通知设置。
     */
    val notifPermissionAsked: Flow<Boolean> = context.dataStore.data.map { it[keyNotifPermAsked] ?: false }

    suspend fun setNotifPermissionAsked() {
        context.dataStore.edit { it[keyNotifPermAsked] = true }
    }

    // ---- 历史通知保留天数（Dev 6）：监控式滚动，最新的顶掉 N 天前的 ----
    private val keyHistoryRetentionDays = intPreferencesKey("history_retention_days")

    /** 历史通知保留天数（1-90，默认 7）；已学习的标注不受清理影响 */
    val historyRetentionDays: Flow<Int> = context.dataStore.data.map { it[keyHistoryRetentionDays] ?: 7 }

    suspend fun setHistoryRetentionDays(value: Int) {
        context.dataStore.edit { it[keyHistoryRetentionDays] = value.coerceIn(1, 90) }
    }

    /** 清理截止时间戳：now - 保留天数（purgeOlderThan 的 cutoff） */
    suspend fun historyRetentionCutoff(now: Long): Long =
        now - historyRetentionDays.first().toLong() * 86_400_000L

    suspend fun setThreshold(value: Float) {
        val clamped = value.coerceIn(0.5f, 1.0f)
        context.dataStore.edit { it[keyThreshold] = clamped }
    }

    suspend fun setInterceptMode(value: Boolean) {
        context.dataStore.edit { it[keyIntercept] = value }
    }

    suspend fun setExcludeFromRecents(value: Boolean) {
        context.dataStore.edit { it[keyHideRecents] = value }
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[keyOnboardingDone] = true }
    }

    /** 累计拦截计数：先入内存累积器，有待写数据时按需启动一次性 flush 协程批量落盘（P2-2 防抖 / P3-3 按需） */
    fun incrementFiltered(ai: Boolean) {
        (if (ai) pendingAi else pendingRule).incrementAndGet()
        synchronized(this) {
            if (flushJob?.isActive != true) {
                flushJob = countFlushScope.launch { flushPendingCounters() }
            }
        }
    }

    /** 一次性 flush：防抖 500ms → 落盘 → 若落盘期间又有新增则继续，无新增即退出（协程结束，零唤醒） */
    private suspend fun flushPendingCounters() {
        while (true) {
            kotlinx.coroutines.delay(500)
            val ai = pendingAi.getAndSet(0)
            val rule = pendingRule.getAndSet(0)
            if (ai == 0 && rule == 0) return
            runCatching {
                context.dataStore.edit {
                    if (ai > 0) it[keyFilteredAi] = (it[keyFilteredAi] ?: 0) + ai
                    if (rule > 0) it[keyFilteredRule] = (it[keyFilteredRule] ?: 0) + rule
                }
            }
        }
    }
}
