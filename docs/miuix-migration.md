# miuix UI 迁移计划

把 UI 层从 Material 3 + Kyant0 Backdrop 整体换成 [miuix](https://github.com/compose-miuix-ui/miuix)（与 Mishka 同源的设计语言），业务逻辑零改动。**迁移与清理均已完成，剩验收与合回 `main`。**

- 分支：`ui-miuix`；main 固定 `1d64259` 保持稳定，不在 main 上开发
- 验证：只走 CI，不在本机编译
- 参照样板：`YuKongA/Mishka`（已在 AGP 9 + miuix 上跑的实战配置）
- 当前进度与待决策：见 [README.md](README.md)（本文件是详细计划与组件映射）

## 进度快照（2026-10-06）

阶段 0 / 1 / 2 / 3 已完成，剩验收与合回 `main`。

- **阶段 2 全部 11 个屏已迁完**，一屏一提交：`985918c` update prompt → `63f093c` permission flow → `f3c36d1` rules → `53ac141` app picker → `5b7a42d` 回退到 String 版 TextField → `611260c` rule edit → `4af03d8` history → `564fec8` stats detail → `7144f97` settings → `a995e71` main scaffold 底栏
- **阶段 3 已清理完毕**：删 `GlassLib.kt`(599 行)、`material3` / `kyant0:backdrop` / `kyant0:shapes` 三条依赖，以及 `uiTheme` / `glassStyle` 开关、`UiTheme` / `GlassStyle` 枚举、`LocalGlassMode` / `LocalGlassStyle`、`AppShapes`。`androidx.compose.material3` 与 `kyant0` 引用均已清零
- CI：`#20` 起每次 push 都出 debug 包
- ⚠️ 早先「`GlassLib.kt` 已无 UI 引用」的判断是错的：`MainActivity` 的玻璃底栏分支与三个屏的 `GlassFloatingBarClearance` 让位 padding 一直在读它

## 目标工具链

| 组件 | 迁移前 | 目标 |
|---|---|---|
| AGP | 8.13.0 | 9.4.1 |
| Kotlin | 2.2.20（独立插件） | 2.4.20（AGP 9 内置，不再单独声明） |
| KSP | 2.2.20-2.0.2 | 2.3.9 |
| Gradle | 9.5.0 | 9.7.1 |
| JDK | 17 | 21 |
| UI | material3 1.4.0 + backdrop 1.0.6 + shapes 1.2.0 | miuix 0.9.4（`-android` 变体） |

## 阶段 0 · 工具链切换（不动 UI）

- [x] 建分支 `ui-miuix`
- [x] Gradle 9.5.0 → 9.7.1
- [x] AGP 8.13.0 → 9.4.1
- [x] 删 `org.jetbrains.kotlin.android`（AGP 9 内置 Kotlin，重复声明直接失败）
- [x] Kotlin 插件 → 2.4.20、KSP → 2.3.9
- [x] JDK 17 → 21（CI env ×2 + compileOptions + compilerOptions）
- [x] `kotlinOptions{}` → 顶层 `kotlin{ compilerOptions{} }`
- [x] `compileSdk`/`compileSdkMinor` → 新 DSL `compileSdk { version = release(37) { minorApiLevel = 0 } }`
- [x] CI 出绿包（debug）

## 阶段 1 · 接入 miuix + 拆旧 UI 骨架

- [x] 确认 miuix 依赖坐标（`-android` 后缀是给 AndroidX Compose 工程的变体；不带后缀会拉进 CMP 运行时并冲突）
- [x] 加依赖：miuix-ui / miuix-preference / miuix-icons / miuix-blur / miuix-squircle
  - ⚠️ **不要加 `miuix-nav`**：底栏是 miuix-ui 的 `NavigationBar`（`basic/`），`miuix-nav` 是替代 androidx.navigation 的整套导航库
- [x] 重建 `ui/Theme.kt` —— 迁移期双主题并存：`MiuixTheme` 套 `MaterialTheme`
- [x] 单屏跑通（`OpenSourceScreen` 为样板，确认 miuix 能在 CI 出包）
- [x] 删 material3 / kyant0:backdrop / kyant0:shapes 依赖
- [x] 删 `ui/glass/GlassLib.kt`（599 行）
- [x] 删 `SettingsRepository` 的 `uiTheme` / `glassStyle` 开关及 DataStore key
- [x] 删 `ui/Theme.kt` 的 `UiTheme` / `GlassStyle` 枚举、`AppShapes`、`LocalGlassMode` / `LocalGlassStyle`，并移除外层 `MaterialTheme`

## 阶段 2 · 逐屏重写（保留 ViewModel 与状态逻辑，只换皮）

现有导航：3 个主 tab = 历史 / 规则 / 设置，另有若干二级页。

- [x] `ui/settings/OpenSourceScreen.kt` (148)
- [x] `ui/update/UpdatePrompt.kt` (86)
- [x] `ui/permission/PermissionFlow.kt` (333)
- [x] `ui/rules/RulesScreen.kt` (247) + `RuleEditScreen.kt` (254) + `AppPickerScreen.kt` (172)
- [x] `ui/history/HistoryScreen.kt` (591)
- [x] `ui/settings/StatsDetailScreen.kt` (332)
- [x] `ui/settings/SettingsScreen.kt` (848)
- [x] `ui/MainActivity.kt` 导航壳 + 底栏 —— 玻璃分支已在阶段 3 删除，只剩 miuix 底栏

### 迁移期的组件映射约定（逐屏重写时照此办理）

| material3 | miuix | 备注 |
|---|---|---|
| `Card` | `Card` | `insideMargin` 默认为 0，卡片内边距要自己加 `Column(Modifier.padding(...))` |
| `Button` | `Button` | content 仍是 lambda；主色用 `ButtonDefaults.buttonColorsPrimary()` |
| `OutlinedButton` | `Button` | 次要色用默认 `ButtonDefaults.buttonColors()` |
| `TextButton(onClick){Text(x)}` | `TextButton(text = x, onClick = …)` | ⚠️ 文字是**参数**不是 content |
| `FilterChip` / `AssistChip` | `TabRow(tabs: List<String>, selectedTabIndex, onTabSelected)` / `Button` | miuix 无 chip；互斥筛选用 TabRow。⚠️ `TabRow` **没有 `Tab` 子组件** |
| `OutlinedTextField` | `TextField` | 用 `value: String` 重载（内部 `BasicTextField` 自维护光标，行为与 M3 一致）；`label` 是 **String**，占位用 `useLabelAsPlaceholder = true` |
| `ExposedDropdownMenuBox` + `DropdownMenuItem` | `OverlayDropdownMenu(DropdownEntry(listOf(DropdownItem(...))))` | item 的 `onClick` 触发选择 |
| `Checkbox(checked, onCheckedChange)` | `Checkbox(state = ToggleableState.On/Off, onClick)` | 参数名是 `state` 不是 `checked`；回调是 `() -> Unit` |
| `AlertDialog(confirmButton/dismissButton)` | `OverlayDialog(title, show, onDismissRequest){ 内容+按钮 }` | `content` 是标题/摘要之下的**主体区**，无按钮槽位，按钮自放其中 |
| `ModalBottomSheet` | `OverlayBottomSheet(show, onDismissRequest)` | |
| `TopAppBar` | `SmallTopAppBar(title: String)` | title 是 String 不是 Composable |
| `NavigationBar` / `NavigationBarItem` | 同名 | ⚠️ `icon: ImageVector` 与 `label: String` 是**值**不是 lambda（不像 M3 传 `Text`） |
| `Badge` | `Badge { Text(…) }` | content 为 `RowScope.() -> Unit` |
| `SnackbarHost` / `SnackbarHostState` | 同名 | |
| `HorizontalDivider` | `HorizontalDivider` | |
| `ProgressIndicator` | `LinearProgressIndicator(progress: () -> Float)` | |
| `Scaffold` | `Scaffold` | 参数同名（topBar/bottomBar/floatingActionButton/snackbarHost/contentWindowInsets） |
| `MaterialTheme.typography.titleLarge` | `MiuixTheme.textStyles.title3` | 字号：title1 32 / title2 24 / title3 20 / title4 18 / headline1 17 / headline2 16 / body1 16 / body2 14 / subtitle 14B / footnote1 13 / footnote2 11 / main 17 / button 17 |
| `MaterialTheme.colorScheme.onSurfaceVariant` | `MiuixTheme.colorScheme.onSurfaceVariantSummary` | miuix **无独立 `tertiary`**，只有 `tertiaryContainer` / `onTertiaryContainer` 一对 —— 文字色必须用后者，前者是底色 |
| DatePicker / DatePickerDialog | 无对应 | 见下 |

⚠️ **miuix 无日期选择器**（0.9.4 全库只有 `NumberPicker` / `ColorPicker`，官方组件页同样只列这两个；`0.9.4` 即最新 release）。本项目日期筛选用 `OverlayDialog` + 三列 `NumberPicker`（年/月/日）自建，只在 `HistoryScreen` 一处。

⚠️ miuix `FloatingActionButton` 的 `Surface` 不传 `contentColor`，图标要显式 `tint = MiuixTheme.colorScheme.onPrimary`，否则可能与底色同色不可见。

⚠️ **`*Container` 角色是底色，不能当文字色**：深色下 `tertiaryContainer` = `#2B3B54`，与卡片底 `#242424` 的对比度只有 1.4:1，文字用它会隐形（浅色下 `#EAF2FF` 同样不可读）。文字要用配对的 `on*Container`（`onTertiaryContainer` = `#4788FF`，4.6:1）。

⚠️ **不要给 miuix 用 `ColorSchemeMode.MonetSystem`**：miuix 的层次感来自中性底色的明度差（`surface` 纯黑 → `surfaceContainer` `#242424` → `secondaryContainer` `#434343`），Monet 会把这些角色整盘染上系统主题色，卡片与背景糊成一片、`TextField`（默认填充 `secondaryContainer`）突兀跳色。用 `ColorSchemeMode.System`。

## 阶段 3 · 清理与验收

- [x] `androidx.compose.material3` 引用清零（原 19 处 = `GlassLib.kt` 15 + `Theme.kt` 4）
- [x] `kyant0` / `backdrop` 引用清零（含 `build.gradle.kts` 的两条依赖）
- [x] 删 `GlassLib.kt` + `UiTheme`/`GlassStyle`/`LocalGlassMode`/`LocalGlassStyle`/`AppShapes`，`SettingsScreen` 去掉主题切换 UI
- [x] `OpenSourceScreen` 去掉 Backdrop / Shapes 致谢条目
- [ ] dex 残留检查（无 material3 / backdrop 类）
- [ ] 真机安装 + 视觉走查
- [ ] 合回 `main`（发版流程只认 main）

## 风险

1. **R8 full mode**：AGP 9 让 `android.r8.strictFullModeForKeepRules` 默认 true，且 `-keep class A` 不再隐含保留默认构造器。本项目有 Room + 6 处 `@Serializable`，release（开 minify）需专门验一次 —— push 触发的 CI 只出 debug，此风险在阶段 0/1 不会暴露。
2. **targetSdk 默认值变化**：AGP 9 让 `targetSdk` 未设置时默认跟 `compileSdk`；本项目显式写 36，需确认不被改写。
3. **compileSdk 37 的 minor 命名**：自 API 37 起平台包落盘为 `platforms/android-37.0`，新 DSL 的 `minorApiLevel = 0` 正对应它，改名成 `android-37` 反而找不到。
