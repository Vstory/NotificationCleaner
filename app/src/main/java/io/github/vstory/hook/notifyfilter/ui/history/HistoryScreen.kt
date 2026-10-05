package io.github.vstory.hook.notifyfilter.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import io.github.vstory.hook.notifyfilter.data.db.NotificationEntity
import io.github.vstory.hook.notifyfilter.notify.KeepAliveManager
import io.github.vstory.hook.notifyfilter.ui.rules.AppPickerSession
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 1.3.2（P3-6）：SimpleDateFormat（非线程安全）→ java.time DateTimeFormatter（不可变）
private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** internal：统计明细页（Dev 14 起可点开详情）复用同一时间格式 */
internal fun formatTime(epochMs: Long): String =
    timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** 日期分组头格式（Dev 6）："9月25日" */
private val dayFmt = DateTimeFormatter.ofPattern("M月d日")

private fun dayOf(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel(factory = vmFactory()), onOpenAppPicker: () -> Unit = {}) {
    val list by vm.list.collectAsState()
    val filter by vm.filter.collectAsState()
    val search by vm.search.collectAsState()
    val selected by vm.selected.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var hadData by remember { mutableStateOf(false) }

    // 修复：打开页面/切换筛选/首次加载后定位到最新通知（列表顶部）
    LaunchedEffect(list.isEmpty(), filter) {
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

    // 1.4.0 Dev 13：外层 MainScaffold 已应用状态栏 inset，此处必须清零，
    // 否则顶部 inset 双叠加 → 筛选按钮上方一大片空白
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TabRowWithContour(
                    tabs = listOf("全部", "已过滤", "正常"),
                    selectedTabIndex = HistoryFilter.entries.indexOf(filter).coerceAtLeast(0),
                    onTabSelected = { vm.setFilter(HistoryFilter.entries[it]) },
                )
            }
            var advOpen by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = search,
                    onValueChange = { vm.search.value = it },
                    label = "搜索 App / 标题 / 内容",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AdvancedFilterButton(vm, onOpenAppPicker, advOpen) { advOpen = !advOpen }
            }
            if (advOpen) {
                AdvancedFilterPanel(vm, onOpenAppPicker)
            }
            Spacer(Modifier.height(4.dp))
            if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (search.isBlank()) "暂无通知记录" else "无匹配结果", style = MiuixTheme.textStyles.headline2)
                    if (search.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("请先在系统设置中授予通知监听权限", style = MiuixTheme.textStyles.footnote1)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    // Dev 6：日期分组头——列表从新到旧，相邻两条日期不同时插入"9月25日"分隔
                    itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                        val day = dayOf(n.postTime)
                        if (i == 0 || dayOf(list[i - 1].postTime) != day) {
                            DateHeader(day)
                        }
                        NotificationCard(n) { vm.select(n) }
                    }
                }
            }
        }

        selected?.let { n ->
            OverlayBottomSheet(show = true, onDismissRequest = { vm.select(null) }) {
                NotificationDetail(
                    n = n,
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
private fun NotificationCard(n: NotificationEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(n.packageName, 40, fallbackText = n.appName)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.appName, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(formatTime(n.postTime), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.outline)
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
                // 右下角：简单广告率（按阈值区间着色）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pct = (n.adProbability * 100).toInt()
                    Text(
                        "广告率 $pct%",
                        style = MiuixTheme.textStyles.footnote2,
                        color = when {
                            n.adProbability >= 0.8f -> MiuixTheme.colorScheme.error
                            n.adProbability >= 0.5f -> MiuixTheme.colorScheme.onTertiaryContainer
                            else -> MiuixTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/**
 * 高级筛选（Dev 6）：搜索框右侧的"筛选"按钮 + 展开面板。
 * App 选择复用规则页的 AppPickerScreen（全量应用列表，含系统应用），
 * 结果经 AppPickerSession 单例回读（与 RuleEditScreen 同款机制）。
 */
private fun advancedActiveCount(adv: HistoryAdvancedFilter): Int {
    var c = 0
    if (adv.apps.isNotEmpty()) c++
    if (adv.learnedOnly) c++
    if (adv.startDate != null) c++
    if (adv.endDate != null) c++
    return c
}

@Composable
private fun AdvancedFilterButton(
    vm: HistoryViewModel,
    onOpenAppPicker: () -> Unit,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val adv by vm.advancedFilter.collectAsState()
    // 进入历史页时回读选择器结果（从 AppPicker 返回后本组合重建，LaunchedEffect 重跑）
    LaunchedEffect(Unit) {
        AppPickerSession.result?.let { result ->
            vm.setAdvancedFilter(vm.advancedFilter.value.copy(apps = result))
            AppPickerSession.result = null
        }
    }
    val active = advancedActiveCount(adv)
    Button(
        onClick = onToggle,
        colors = if (active > 0) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) {
        Text(if (active > 0) "筛选($active)" else "筛选", style = MiuixTheme.textStyles.button)
    }
}

@Composable
private fun AdvancedFilterPanel(vm: HistoryViewModel, onOpenAppPicker: () -> Unit) {
    val adv by vm.advancedFilter.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(6.dp))
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "应用",
                summary = if (adv.apps.isEmpty()) "全部 App" else "已选 ${adv.apps.size} 个",
                // 复用规则页 AppPicker（含系统应用），结果经 AppPickerSession 回读
                onClick = {
                    AppPickerSession.initial = adv.apps
                    onOpenAppPicker()
                },
            )
            HorizontalDivider()
            SwitchPreference(
                checked = adv.learnedOnly,
                onCheckedChange = { vm.setAdvancedFilter(adv.copy(learnedOnly = it)) },
                title = "只看已学习",
            )
            HorizontalDivider()
            DateField(
                label = "开始时间",
                value = adv.startDate,
                onUpdate = { vm.setAdvancedFilter(adv.copy(startDate = it)) },
            )
            HorizontalDivider()
            DateField(
                label = "结束时间",
                value = adv.endDate,
                onUpdate = { vm.setAdvancedFilter(adv.copy(endDate = it)) },
            )
            if (advancedActiveCount(adv) > 0) {
                HorizontalDivider()
                ArrowPreference(
                    title = "清除筛选条件",
                    onClick = { vm.setAdvancedFilter(HistoryAdvancedFilter()) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
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

@Composable
private fun DateHeader(day: LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            dayFmt.format(day),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.outline,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * 通知详情弹层内容（internal：Dev 14 起统计明细页点击条目也复用它，
 * 学习/取消学习交互与历史页完全一致）。
 */
@Composable
internal fun NotificationDetail(
    n: NotificationEntity,
    onJumpChannel: () -> Unit,
    onLearn: (Int) -> Unit,
    onUnlearn: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (n.title.isNotEmpty()) {
            Text(n.title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text(n.content.ifEmpty { "（无正文）" }, style = MiuixTheme.textStyles.body2)
        Spacer(Modifier.height(12.dp))
        Text("发送时间：${formatTime(n.postTime)}", style = MiuixTheme.textStyles.footnote1)
        Text(
            // 1.2.3：label 解析失败时 appName 即包名，避免重复显示两次
            if (n.appName == n.packageName) "APP：${n.packageName}"
            else "APP：${n.appName} (${n.packageName})",
            style = MiuixTheme.textStyles.footnote1,
        )
        Text("发送通道：${n.channelId.ifEmpty { "（默认/未知）" }}", style = MiuixTheme.textStyles.footnote1)
        Text("AI 判定：广告概率 ${(n.adProbability * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1)

        Spacer(Modifier.height(16.dp))
        Button(onClick = onJumpChannel, modifier = Modifier.fillMaxWidth()) {
            Text("跳转到该通道设置", style = MiuixTheme.textStyles.button)
        }
        Spacer(Modifier.height(8.dp))
        // 1.1.11：已学习的通知也允许再次点击学习（同方向重复点击累积权重）；随时可取消学习
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val adText = when {
                n.learned && n.learnLabel == 1 -> "再学一次广告(${n.learnCount + 1})"
                else -> "广告通知"
            }
            val normalText = when {
                n.learned && n.learnLabel == 0 -> "再学一次正常(${n.learnCount + 1})"
                else -> "正常通知"
            }
            Button(onClick = { onLearn(1) }, modifier = Modifier.weight(1f)) {
                Text(adText, style = MiuixTheme.textStyles.button)
            }
            Button(onClick = { onLearn(0) }, modifier = Modifier.weight(1f)) {
                Text(normalText, style = MiuixTheme.textStyles.button)
            }
        }
        if (n.learned) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onUnlearn) { Text("取消学习", style = MiuixTheme.textStyles.button) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun vmFactory(): androidx.lifecycle.ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            HistoryViewModel(ServiceLocator.db.notificationDao(), ServiceLocator.modelRepo)
        }
    }
