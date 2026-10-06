package io.github.vstory.hook.notifyfilter.ui.history

import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL
import android.service.notification.NotificationListenerService.REASON_CANCEL
import android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED
import android.service.notification.NotificationListenerService.REASON_CLICK
import android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL
import android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL
import android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED
import android.service.notification.NotificationListenerService.REASON_SNOOZED
import android.service.notification.NotificationListenerService.REASON_TIMEOUT
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.vstory.hook.notifyfilter.R
import io.github.vstory.hook.notifyfilter.ServiceLocator
import io.github.vstory.hook.notifyfilter.data.db.DECISION_CONVERSATION
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_AI_MODULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_FILTERED_BY_RULE_MODULE
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MANUAL_MARKED_AD
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MEDIA
import io.github.vstory.hook.notifyfilter.data.db.DECISION_ONGOING
import io.github.vstory.hook.notifyfilter.data.db.DECISION_WHITELIST
import io.github.vstory.hook.notifyfilter.data.db.FILTERED_DECISIONS
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import io.github.vstory.hook.notifyfilter.notify.KeepAliveManager
import io.github.vstory.hook.notifyfilter.ui.component.CardItem
import io.github.vstory.hook.notifyfilter.ui.component.blur.BlurredBar
import io.github.vstory.hook.notifyfilter.ui.component.blur.rememberBlurBackdrop
import io.github.vstory.hook.notifyfilter.ui.component.groupedCardItems
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerSession
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 1.3.2（P3-6）：SimpleDateFormat（非线程安全）→ java.time DateTimeFormatter（不可变）
private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** internal：统计明细页（Dev 14 起可点开详情）复用同一时间格式 */
internal fun formatTime(epochMs: Long): String =
    timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private val hourMinuteFmt = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 列表时间：当天用相对时间（"刚刚 / N 分钟前 / N 小时前"），跨天回落绝对格式——
 * 通知列表里"多久之前"比精确时刻更可读。now 只在重组时求值，不自行走时。
 */
internal fun formatRelativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - epochMs
    if (diff < 0) return formatTime(epochMs)
    if (diff < 60_000L) return "刚刚"
    if (diff < 3_600_000L) return "${diff / 60_000L} 分钟前"
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "${diff / 3_600_000L} 小时前"
        day == today.minusDays(1) -> "昨天 ${hourMinuteFmt.format(Instant.ofEpochMilli(epochMs).atZone(zone))}"
        else -> formatTime(epochMs)
    }
}

/** 日期分组头格式（Dev 6）："9月25日" */
private val dayFmt = DateTimeFormatter.ofPattern("M月d日")

private fun dayOf(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** 三态推导：与 DAO 的 listVisible / listDismissed / listHistory 谓词一一对应，供搜索结果分组用 */
private fun stateOf(n: NotificationEntity): HistoryTab = when {
    n.seq > 0 -> HistoryTab.HISTORY
    n.dismissTime < 0 -> HistoryTab.VISIBLE
    else -> HistoryTab.DISMISSED
}

@StringRes
private fun stateTitleRes(t: HistoryTab): Int = when (t) {
    HistoryTab.VISIBLE -> R.string.history_tab_visible
    HistoryTab.DISMISSED -> R.string.history_tab_dismissed
    HistoryTab.HISTORY -> R.string.history_tab_history
}

@Composable
fun HistoryScreen(
    vm: HistoryViewModel = viewModel(factory = vmFactory()),
    onOpenAppPicker: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val list by vm.list.collectAsState()
    val tab by vm.tab.collectAsState()
    val search by vm.search.collectAsState()
    val selected by vm.selected.collectAsState()
    val toast by vm.toast.collectAsState()
    val adv by vm.advancedFilter.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var hadData by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    val searchExpanded by vm.searchMode.collectAsState()
    val advCount = advancedActiveCount(adv)

    // 修复：打开页面/切换 tab/首次加载后定位到最新通知（列表顶部）
    LaunchedEffect(list.isEmpty(), tab) {
        if (list.isNotEmpty() && !hadData) {
            listState.scrollToItem(0)
        }
        hadData = list.isNotEmpty()
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    // 从选 App 页返回后本组合重建，LaunchedEffect 重跑并回读选择器结果
    LaunchedEffect(Unit) {
        AppPickerSession.result?.let { result ->
            vm.setAdvancedFilter(vm.advancedFilter.value.copy(apps = result))
            AppPickerSession.result = null
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // contentWindowInsets 清零：顶部 inset 由本页 TopAppBar 处理，底部靠外层底栏高度透传，
    // 两侧都不再依赖 MainScaffold 的 padding，避免 inset 二次叠加
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                // 官方 MainPage 的分层：顶栏与其动作图标常驻，搜索条占内容区首行、其余内容整体让位。
                // 此前整条替换顶栏，会把「筛选」入口一并挤掉，搜索时无法再调筛选。
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { vm.setSearchMode(!searchExpanded) }) {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = stringResource(R.string.history_search),
                                tint = if (searchExpanded || search.isNotBlank()) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                        }
                        IconButton(onClick = { filterOpen = true }) {
                            Icon(
                                imageVector = MiuixIcons.Filter,
                                contentDescription = stringResource(R.string.history_filter),
                                tint = if (advCount > 0) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = padding.calculateTopPadding()),
        ) {
            // 搜索条与 Tab 行互斥占同一行：搜索展开即接管该行（官方 MainPage 的让位语义），
            // 三态选择在搜索模式下无意义——搜索范围已是全部三态
            if (searchExpanded) {
                SearchBar(
                    inputField = {
                        InputField(
                            query = search,
                            onQueryChange = { vm.search.value = it },
                            onSearch = {},
                            expanded = true,
                            onExpandedChange = { if (!it) vm.setSearchMode(false) },
                            label = stringResource(R.string.history_search_hint),
                        )
                    },
                    expanded = true,
                    onExpandedChange = { if (!it) vm.setSearchMode(false) },
                    outsideEndAction = {
                        TextButton(
                            text = stringResource(R.string.history_cancel),
                            onClick = { vm.setSearchMode(false) },
                        )
                    },
                    content = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            } else {
                TabRowWithContour(
                    tabs = listOf(
                        stringResource(R.string.history_tab_visible),
                        stringResource(R.string.history_tab_dismissed),
                        stringResource(R.string.history_tab_history),
                    ),
                    selectedTabIndex = HistoryTab.entries.indexOf(tab),
                    onTabSelected = { vm.setTab(HistoryTab.entries[it]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }
            if (list.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val emptyTitle = when {
                        search.isNotBlank() || !adv.isDefault -> stringResource(R.string.history_no_match)
                        // 搜索模式下列表为空即库内无任何通知（词为空时没有可过滤的条件）
                        searchExpanded || tab == HistoryTab.VISIBLE ->
                            stringResource(R.string.history_empty_visible)
                        tab == HistoryTab.DISMISSED -> stringResource(R.string.history_empty_dismissed)
                        else -> stringResource(R.string.history_empty_history)
                    }
                    Text(emptyTitle, style = MiuixTheme.textStyles.headline2)
                    // 权限提示只在「正显示」空列表下有意义：另外两个 tab 空是正常态
                    if (!searchExpanded && tab == HistoryTab.VISIBLE && search.isBlank() && adv.isDefault) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.history_need_access), style = MiuixTheme.textStyles.footnote1)
                    }
                }
            } else if (searchExpanded) {
                // 搜索结果跨三态 → 按状态分组，分组标签与 Tab 行同一套（三态互斥，可直接 groupBy）
                val byState = remember(list) { list.groupBy { stateOf(it) } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp + bottomPadding),
                ) {
                    HistoryTab.entries.forEach { st ->
                        val items = byState[st] ?: return@forEach
                        item(key = "state:$st") { SmallTitle(stringResource(stateTitleRes(st))) }
                        groupedCardItems(
                            keyPrefix = "state:$st",
                            outerBottomPadding = 12.dp,
                            items = items.map { n ->
                                CardItem(n.id.toString()) { NotificationRow(n) { vm.select(n) } }
                            },
                        )
                    }
                }
            } else {
                // 列表已按时间倒序 → LinkedHashMap 保序，每个日期一组，一组拼一张连续卡
                val byDay = remember(list) { list.groupBy { dayOf(it.postTime) } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp + bottomPadding),
                ) {
                    byDay.forEach { (day, items) ->
                        item(key = "day:$day") { SmallTitle(dayFmt.format(day)) }
                        groupedCardItems(
                            keyPrefix = "day:$day",
                            outerBottomPadding = 12.dp,
                            items = items.map { n ->
                                CardItem(n.id.toString()) { NotificationRow(n) { vm.select(n) } }
                            },
                        )
                    }
                }
            }
        }

        if (filterOpen) {
            OverlayBottomSheet(
                show = true,
                title = "筛选",
                onDismissRequest = { filterOpen = false },
            ) {
                Card(Modifier.fillMaxWidth()) {
                    AdvancedFilterRows(
                        vm = vm,
                        onOpenAppPicker = {
                            // 弹层渲染在 root scaffold，不收起会盖住压栈打开的 AppPicker
                            filterOpen = false
                            onOpenAppPicker()
                        },
                    )
                }
            }
        }

        selected?.let { n ->
            val versions by vm.versions.collectAsState()
            OverlayBottomSheet(
                show = true,
                title = stringResource(R.string.detail_title),
                onDismissRequest = { vm.select(null) },
            ) {
                NotificationDetail(
                    n = n,
                    versions = versions,
                    onJumpChannel = {
                        KeepAliveManager.openChannelSettings(context, n.packageName, n.channelId)
                    },
                    onLearn = { label -> vm.learn(n, label) },
                    onUnlearn = { vm.unlearn(n) },
                )
            }
        }
    }
}

/** APP 图标：密度感知像素尺寸 + 全局 LruCache（键含尺寸）；自适应图标裁剪安全区；失败时圆底 + 首字符占位 */
private val iconCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(192)

/** 1.2.3：图标渲染失败包的负缓存——MIUI RRO idmap 缓存损坏时 getApplicationIcon 抛 IOException，
 *  不缓存会导致每次重组重试失败路径（系统日志刷屏 + 列表卡顿），见诊断日志 20260918 */
private val failedIconKey = android.util.LruCache<String, Boolean>(192)

@Composable
fun AppIcon(packageName: String, size: Int = 40, fallbackText: String = packageName) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // 1.1.7：按屏幕密度生成物理像素位图，避免 40px 位图在 3x 屏上被拉伸导致模糊
    val sizePx = (size * density).toInt().coerceAtLeast(8)
    // 1.3.2（P3-1）：渲染挪 IO 线程——缓存/负缓存命中时同步返回；未命中先出占位圆底，
    // 异步 getApplicationIcon + 绘制完成后重组替换，首次出现某包名不再阻塞组合
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null, packageName, sizePx,
    ) {
        val cacheKey = "$packageName:$sizePx"
        iconCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }
        if (failedIconKey.get(packageName) == true) return@produceState // 已知失败包：直接占位
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rendered = renderAppIcon(context, packageName, sizePx)
            if (rendered != null) {
                iconCache.put(cacheKey, rendered)
            } else {
                failedIconKey.put(packageName, true)
            }
            rendered
        }
    }
    val rendered = bmp
    if (rendered != null) {
        androidx.compose.foundation.Image(
            bitmap = rendered,
            contentDescription = null,
            modifier = Modifier.width(size.dp).height(size.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(size.dp / 4)),
        )
    } else {
        // 占位：浅色圆底 + APP 名首字符（1.1.7：补背景色，避免孤零零一个字母的观感）
        Box(
            modifier = Modifier
                .width(size.dp)
                .height(size.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MiuixTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fallbackText.take(1).uppercase(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * drawable → 位图（1.1.7 重写）：
 * - 自适应图标（AdaptiveIconDrawable）：108 视口中心 72 安全区放大到目标尺寸（启动器同款），
 *   修复此前整幅视口塞进 bounds 导致图标偏小、四周带底色的问题
 * - 其余 drawable：按边界绘制
 * - 统一圆角裁剪，与占位风格一致
 */
private fun renderAppIcon(
    context: android.content.Context,
    packageName: String,
    sizePx: Int,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val d = context.packageManager.getApplicationIcon(packageName).mutate()
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    // 圆角裁剪（先于任何缩放变换，作用于整个位图）
    canvas.clipPath(
        android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                sizePx / 4f, sizePx / 4f,
                android.graphics.Path.Direction.CW,
            )
        },
    )
    if (d is android.graphics.drawable.AdaptiveIconDrawable) {
        // 安全区 [18,90] 映射到 [0,sizePx]：p' = (p-18) * (sizePx/72)
        val scale = sizePx / 72f
        canvas.scale(scale, scale)
        canvas.translate(-18f, -18f)
        d.setBounds(0, 0, 108, 108)
    } else {
        d.setBounds(0, 0, sizePx, sizePx)
    }
    d.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

@Composable
private fun NotificationRow(n: NotificationEntity, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(n.packageName, 40, fallbackText = n.appName)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.appName, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    // 原用 outline：浅色下 #D9D9D9，在卡片底上几乎不可读
                    Text(
                        formatRelativeTime(n.postTime),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    when (n.decision) {
                        DECISION_FILTERED_BY_AI, DECISION_FILTERED_BY_AI_MODULE ->
                            Badge { Text("AI过滤 ${(n.adProbability * 100).toInt()}%") }
                        DECISION_FILTERED_BY_RULE, DECISION_FILTERED_BY_RULE_MODULE -> Badge { Text("规则过滤") }
                        DECISION_MANUAL_MARKED_AD -> Badge { Text("已学习广告") }
                        DECISION_WHITELIST -> Text("白名单", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
                        DECISION_MEDIA -> Text("媒体", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.secondary)
                        DECISION_CONVERSATION -> Text("会话", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.secondary)
                        DECISION_ONGOING -> Text("常驻", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.secondary)
                    }
                    if (n.learned) {
                        Spacer(Modifier.width(4.dp))
                        Text("已学习", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onTertiaryContainer)
                    }
                }
                if (n.title.isNotEmpty()) {
                    Text(n.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.content.isNotEmpty()) {
                    Text(n.content, style = MiuixTheme.textStyles.footnote1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // 底行：左「这条为什么不在通知栏」/ 右广告率（按阈值区间着色）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stateLabel(n)?.let {
                        Text(
                            it,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val pct = (n.adProbability * 100).toInt()
                    Text(
                        "广告率 $pct%",
                        style = MiuixTheme.textStyles.footnote2,
                        color = adScoreColor(n.adProbability),
                    )
                }
            }
        }
    }
}

/**
 * 底行状态说明：这条为什么不在通知栏里。
 * 拦截类由写入侧直接把 dismissTime 落成 postTime（通知从未进通知栏，等不到 removed 回调），
 * reason 因此停在默认 0——与系统「原因未知」的 0 同值，只能借决策集合区分这两种来源。
 */
@Composable
private fun stateLabel(n: NotificationEntity): String? = when {
    n.seq > 0 -> stringResource(R.string.history_state_old_version)
    n.dismissTime < 0 -> null
    n.dismissReason == 0 -> if (n.decision in FILTERED_DECISIONS) {
        stringResource(R.string.history_state_blocked)
    } else {
        stringResource(R.string.history_state_removed)
    }
    else -> dismissReasonText(n.dismissReason)
}

/** NLS 的 reason 常量 → 展示文案 */
@Composable
private fun dismissReasonText(reason: Int): String = when (reason) {
    REASON_CANCEL -> stringResource(R.string.reason_cleared)
    REASON_CLICK -> stringResource(R.string.reason_clicked)
    REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> stringResource(R.string.reason_app_cancelled)
    // 本模块自己撤下的（如标注广告后清理通知栏）
    REASON_LISTENER_CANCEL, REASON_LISTENER_CANCEL_ALL -> stringResource(R.string.reason_self_cancelled)
    REASON_PACKAGE_BANNED -> stringResource(R.string.reason_app_disabled)
    REASON_CHANNEL_BANNED -> stringResource(R.string.reason_channel_disabled)
    REASON_SNOOZED -> stringResource(R.string.reason_snoozed)
    REASON_TIMEOUT -> stringResource(R.string.reason_timeout)
    else -> stringResource(R.string.reason_system_removed)
}

/** 生效中的筛选条件数：驱动入口行摘要与「清除筛选条件」行的显隐 */
private fun advancedActiveCount(adv: HistoryAdvancedFilter): Int {
    var c = 0
    if (adv.decision != DecisionFilter.ALL) c++
    if (adv.apps.isNotEmpty()) c++
    if (adv.learnedOnly) c++
    if (adv.startDate != null) c++
    if (adv.endDate != null) c++
    return c
}

/**
 * 筛选条件行（Dev 6）：与入口行同卡展开。
 * App 选择复用规则页 AppPickerScreen（含系统应用），结果经 AppPickerSession 回读。
 */
@Composable
private fun AdvancedFilterRows(vm: HistoryViewModel, onOpenAppPicker: () -> Unit) {
    val adv by vm.advancedFilter.collectAsState()
    // 决策维度（原「全部/已过滤/正常」tab）：Tab 位让给三态后在此收口
    RadioButtonPreference(
        title = "全部",
        selected = adv.decision == DecisionFilter.ALL,
        onClick = { vm.setAdvancedFilter(adv.copy(decision = DecisionFilter.ALL)) },
        radioButtonLocation = RadioButtonLocation.End,
    )
    RadioButtonPreference(
        title = "已过滤",
        selected = adv.decision == DecisionFilter.FILTERED,
        onClick = { vm.setAdvancedFilter(adv.copy(decision = DecisionFilter.FILTERED)) },
        radioButtonLocation = RadioButtonLocation.End,
    )
    RadioButtonPreference(
        title = "正常",
        selected = adv.decision == DecisionFilter.PASSED,
        onClick = { vm.setAdvancedFilter(adv.copy(decision = DecisionFilter.PASSED)) },
        radioButtonLocation = RadioButtonLocation.End,
    )
    ArrowPreference(
        title = "应用",
        summary = if (adv.apps.isEmpty()) "全部 App" else "已选 ${adv.apps.size} 个",
        // 复用规则页 AppPicker（含系统应用），结果经 AppPickerSession 回读
        onClick = {
            AppPickerSession.initial = adv.apps
            onOpenAppPicker()
        },
    )
    SwitchPreference(
        checked = adv.learnedOnly,
        onCheckedChange = { vm.setAdvancedFilter(adv.copy(learnedOnly = it)) },
        title = "只看已学习",
    )
    DateField(
        label = "开始时间",
        value = adv.startDate,
        onUpdate = { vm.setAdvancedFilter(adv.copy(startDate = it)) },
    )
    DateField(
        label = "结束时间",
        value = adv.endDate,
        onUpdate = { vm.setAdvancedFilter(adv.copy(endDate = it)) },
    )
    if (advancedActiveCount(adv) > 0) {
        ArrowPreference(
            title = "清除筛选条件",
            onClick = { vm.setAdvancedFilter(HistoryAdvancedFilter()) },
        )
    }
}

/**
 * 日期选择字段（Dev 6）：点击弹出 miuix 滚轮（年/月/日三列 NumberPicker）。
 * 原实现用 material3 DatePicker 日历，miuix 无日期组件（官方组件页仅 NumberPicker/ColorPicker），
 * 故改用设置页更常见的滚轮形态。
 */
@Composable
private fun DateField(label: String, value: LocalDate?, onUpdate: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    ArrowPreference(
        title = label,
        summary = value?.toString() ?: "未设置",
        onClick = { showPicker = true },
    )
    if (showPicker) {
        val init = value ?: LocalDate.now()
        var y by remember { mutableStateOf(init.year) }
        var m by remember { mutableStateOf(init.monthValue) }
        var d by remember { mutableStateOf(init.dayOfMonth) }
        OverlayDialog(
            show = true,
            title = label,
            onDismissRequest = { showPicker = false },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberPicker(
                    value = y,
                    onValueChange = { y = it },
                    range = 2020..2100,
                    label = { "${it}年" },
                    modifier = Modifier.weight(1f),
                )
                NumberPicker(
                    value = m,
                    onValueChange = { m = it },
                    range = 1..12,
                    label = { "${it}月" },
                    modifier = Modifier.weight(1f),
                )
                NumberPicker(
                    value = d,
                    onValueChange = { d = it },
                    range = 1..31,
                    label = { "${it}日" },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "清除",
                    onClick = { onUpdate(null); showPicker = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        // 滚轮日可留在 31：按当月实际天数收敛，避免 2月30日 这类非法日期
                        val day = d.coerceAtMost(YearMonth.of(y, m).lengthOfMonth())
                        onUpdate(LocalDate.of(y, m, day))
                        showPicker = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("确定", style = MiuixTheme.textStyles.button) }
            }
        }
    }
}

/**
 * 通知详情弹层内容（internal：Dev 14 起统计明细页点击条目也复用它，
 * 学习/取消学习交互与历史页完全一致）。
 *
 * 分区照 miuix 范式（`SmallTitle` + 一张 `Card`）：判定是结论、详情是事实，各占一张卡。
 * 横向留白交给弹层自身的 `insideMargin`（24dp），卡片与行都不要再叠横向 padding。
 */
@Composable
internal fun NotificationDetail(
    n: NotificationEntity,
    versions: List<NotificationEntity>,
    onJumpChannel: () -> Unit,
    onLearn: (Int) -> Unit,
    onUnlearn: () -> Unit,
) {
    val channelName by channelNameState(n.packageName, n.channelId)
    Column(
        Modifier
            .fillMaxWidth()
            // 弹层高度上限是「窗口高 − 状态栏」（BottomSheetContentLayout 的 heightIn），
            // 内容超过它必须自己滚，否则尾部被裁且滑不到
            .verticalScroll(rememberScrollState()),
    ) {
        Card(Modifier.fillMaxWidth()) { DetailBodyRow(n) }

        SmallTitle(stringResource(R.string.detail_section_decision))
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = stringResource(R.string.detail_decision_result),
                summary = decisionReason(n),
            )
            BasicComponent(
                title = stringResource(R.string.detail_ad_score),
                endActions = {
                    Text(
                        "${(n.adProbability * 100).toInt()}%",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = adScoreColor(n.adProbability),
                    )
                },
            )
            LearnButtons(n, onLearn)
        }

        SmallTitle(stringResource(R.string.detail_section_info))
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = stringResource(R.string.detail_label_source),
                summary = sourceText(n),
            )
            BasicComponent(
                title = stringResource(R.string.detail_label_channel),
                summary = channelName
                    ?: n.channelId.ifEmpty { stringResource(R.string.detail_channel_unknown) },
                endActions = {
                    Text(
                        stringResource(R.string.detail_channel_jump),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
                onClick = onJumpChannel,
            )
            BasicComponent(
                title = stringResource(R.string.detail_label_post_time),
                summary = formatTime(n.postTime),
            )
            BasicComponent(
                title = stringResource(R.string.detail_label_state),
                summary = detailStateText(n),
            )
        }

        // 只列被覆盖掉的旧版本：当前版本就是上面那张本体卡
        val history = versions.filter { it.seq > 0 }
        if (history.isNotEmpty()) {
            SmallTitle(stringResource(R.string.detail_section_versions))
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(title = stringResource(R.string.detail_versions_count, versions.size))
                BasicComponent(
                    title = stringResource(R.string.detail_versions_first),
                    summary = formatTime(versions.last().postTime),
                )
                history.forEach { VersionRow(it) }
            }
        }

        if (n.learned) {
            TextButton(
                text = stringResource(R.string.detail_unlearn),
                onClick = onUnlearn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 通知本体：图标 + 标题 + 正文。`BasicComponent` 的 summary 限不了 maxLines，正文会整段铺开，故自建。 */
@Composable
private fun DetailBodyRow(n: NotificationEntity) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(n.packageName, 40, fallbackText = n.appName)
        Column(Modifier.weight(1f)) {
            if (n.title.isNotEmpty()) {
                Text(
                    n.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                n.content.ifEmpty { stringResource(R.string.detail_no_body) },
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 历史版本行（自建：时间戳 + 标题 + 正文摘要；图标都一样，不带） */
@Composable
private fun VersionRow(v: NotificationEntity) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            formatTime(v.postTime),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
        if (v.title.isNotEmpty()) {
            Text(
                v.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (v.content.isNotEmpty()) {
            Text(
                v.content,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 学习方向按钮。1.1.11：已学习的通知也允许再次点击学习（同方向重复点击累积权重）。
 *
 * 成对互斥选项照 miuix 官方范式（`example/.../component/ArrowSection.kt`）：两枚 `TextButton`
 * + `weight(1f)` + 20dp 间隔，而不是两枚主色实心按钮 —— 后者既不符合这对按钮「无主次」的语义，
 * 也看不出已学的是哪个方向。当前方向改用主色变体，另一方向保持默认灰底（颜色按角色分层）。
 */
@Composable
private fun LearnButtons(n: NotificationEntity, onLearn: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val adText = if (n.learned && n.learnLabel == 1) {
            stringResource(R.string.detail_learn_ad_again, n.learnCount + 1)
        } else {
            stringResource(R.string.detail_learn_ad)
        }
        val normalText = if (n.learned && n.learnLabel == 0) {
            stringResource(R.string.detail_learn_normal_again, n.learnCount + 1)
        } else {
            stringResource(R.string.detail_learn_normal)
        }
        TextButton(
            text = adText,
            onClick = { onLearn(1) },
            modifier = Modifier.weight(1f),
            colors = if (n.learned && n.learnLabel == 1) {
                ButtonDefaults.textButtonColorsPrimary()
            } else {
                ButtonDefaults.textButtonColors()
            },
        )
        TextButton(
            text = normalText,
            onClick = { onLearn(0) },
            modifier = Modifier.weight(1f),
            colors = if (n.learned && n.learnLabel == 0) {
                ButtonDefaults.textButtonColorsPrimary()
            } else {
                ButtonDefaults.textButtonColors()
            },
        )
    }
}

/** 1.2.3：label 解析失败时 appName 即包名，避免重复显示两次 */
private fun sourceText(n: NotificationEntity): String =
    if (n.appName == n.packageName) n.packageName else "${n.appName} (${n.packageName})"

/** 广告率着色档位（与列表行同源：0.8 起 error、0.5 起 onTertiaryContainer、其余 actions） */
@Composable
private fun adScoreColor(p: Float): Color = when {
    p >= 0.8f -> MiuixTheme.colorScheme.error
    p >= 0.5f -> MiuixTheme.colorScheme.onTertiaryContainer
    else -> MiuixTheme.colorScheme.onSurfaceVariantActions
}

/**
 * 「为什么是这个判定」。判定链路的分支见 [CleanerListenerService.decide] 与 `FilterEngine.decide`：
 * `*_MODULE` 是 hook 在入队前阻断（通知从未进通知栏），非 `_MODULE` 是 NLS 事后撤下（已进过）。
 */
@Composable
private fun decisionReason(n: NotificationEntity): String = when (n.decision) {
    DECISION_FILTERED_BY_AI -> stringResource(R.string.detail_reason_ai_nls)
    DECISION_FILTERED_BY_AI_MODULE -> stringResource(R.string.detail_reason_ai_module)
    DECISION_FILTERED_BY_RULE -> stringResource(R.string.detail_reason_rule_nls)
    DECISION_FILTERED_BY_RULE_MODULE -> stringResource(R.string.detail_reason_rule_module)
    DECISION_MANUAL_MARKED_AD -> stringResource(R.string.detail_reason_manual)
    DECISION_WHITELIST -> stringResource(R.string.detail_reason_whitelist)
    DECISION_MEDIA -> stringResource(R.string.detail_reason_media)
    DECISION_CONVERSATION -> stringResource(R.string.detail_reason_conversation)
    DECISION_ONGOING -> stringResource(R.string.detail_reason_ongoing)
    // PASSED：有概率值说明只是没到阈值；没打分的分不出「硬放行」与「模型不可用」，合并成一句
    else -> if (n.adProbability > 0f) {
        stringResource(R.string.detail_reason_below_threshold)
    } else {
        stringResource(R.string.detail_reason_not_scored)
    }
}

/** 详情版当前状态：判定依据与 [stateLabel] 同一套，只是把「已拦截 / 已移除」写清上下文 */
@Composable
private fun detailStateText(n: NotificationEntity): String = when {
    n.seq > 0 -> stringResource(R.string.detail_state_history)
    n.dismissTime < 0 -> stringResource(R.string.detail_state_visible)
    n.dismissReason == 0 ->
        if (n.decision in FILTERED_DECISIONS) {
            stringResource(R.string.detail_state_filtered)
        } else {
            stringResource(R.string.detail_state_removed)
        }
    else -> stringResource(R.string.detail_state_removed_reason, dismissReasonText(n.dismissReason))
}

/**
 * 渠道名只能现查：写入侧把 `channelName` 填成了 `channelId`（该列已无独立语义），
 * 真实渠道名在系统的 `NotificationChannel` 里。查系统走 IO，照 [AppIcon] 的同一模式。
 */
@Composable
private fun channelNameState(packageName: String, channelId: String): State<String?> {
    val context = LocalContext.current
    return produceState<String?>(null, packageName, channelId) {
        if (channelId.isEmpty()) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.getNotificationChannel(packageName, channelId)?.name?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}

@Composable
internal fun vmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            HistoryViewModel(ServiceLocator.db.notificationDao(), ServiceLocator.modelRepo)
        }
    }
