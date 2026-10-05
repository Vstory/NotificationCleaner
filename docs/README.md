# NotifyFilter 开发记录

记录 **`ui-miuix` 分支**（仓库默认分支、当前开发线）的开发状态。UI 迁移的详细计划、逐条组件映射与风险清单见 [miuix-migration.md](miuix-migration.md)。

> ⚠️ 本分支是开发线，**发版流程只认 `main`**（`build-release.yml` 硬编码 `refs/heads/main`）。发版前需把本分支合回 main。

## 当前状态（2026-10-06）

| 项 | 值 |
|---|---|
| 分支 | `ui-miuix`（仓库默认分支） |
| 版本 | `2.1.2` (72) |
| UI 栈 | miuix 0.9.4（`-android` 变体）；material3 仅剩 19 处残留 |
| 工具链 | AGP 9.4.1 · Kotlin 2.4.20 · KSP 2.3.9 · Gradle 9.7.1 · JDK 21 · compileSdk 37 |
| CI | `#20` ~ `#27` 全绿（一屏一提交，每次 push 出 debug 包） |

## 进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工具链切换（AGP 9 / Kotlin 2.4 / JDK 21 / 新 compileSdk DSL） | ✅ 完成 |
| 1 | 接入 miuix 依赖 + 拆旧 UI 骨架（`Theme.kt` 改双主题并存） | ✅ 完成 |
| 2 | 逐屏重写，11 个屏全部迁完（一屏一提交） | ✅ 完成 |
| 3 | 清理 material3 / backdrop 残留 + 验收 + 合回 main | ⬜ 未开始 |

### 阶段 2 已完成清单

`OpenSourceScreen` · `UpdatePrompt` · `PermissionFlow` · `RulesScreen` · `RuleEditScreen` · `AppPickerScreen` · `HistoryScreen` · `StatsDetailScreen` · `SettingsScreen` · `MainActivity`（导航壳 + 底栏，仅非玻璃分支）

### 阶段 3 待办

- [ ] `material3` 引用清零 —— 仅剩 19 处：`ui/glass/GlassLib.kt`(15) + `ui/Theme.kt`(4)
- [ ] 删 `ui/glass/GlassLib.kt`（599 行）—— 已无任何界面引用，但仍参与编译
- [ ] 删 `kyant0:backdrop` / `kyant0:shapes` 两条依赖
- [ ] 删 `uiTheme` / `glassStyle` 双主题开关（`SettingsRepository` 的 DataStore key、`UiTheme`/`GlassStyle` 枚举、`LocalGlassMode`/`LocalGlassStyle`、`AppShapes`）及 `SettingsScreen` 顶部的主题切换 UI
- [ ] dex 残留检查（无 material3 / backdrop 类）
- [ ] 真机安装 + 视觉走查
- [ ] 合回 `main`（发版前提）

## 待决策

**玻璃模式（液态玻璃）去留** —— `uiTheme` / `glassStyle` 开关 + `GlassLib.kt`(599 行) + 两条 kyant0 依赖是绑在一起的一坨。迁到 miuix 后，miuix 自带模糊材质，玻璃模式成为与之并存的第二套外观。删则阶段 3 一次清完；留则需继续维护两套皮肤。

## 关键约定（易踩）

- **产物名前缀三处同源**：`build-ci.yml` 的 `PREFIX`、`build-release.yml` 的 `PREFIX`、`UpdateViewModel` 的 `apkName` 必须同值（`NotifyFilter`），漏改最后一处 = 应用内更新 404。
- **miuix 无日期选择器**：`HistoryScreen` 的日期筛选是 `OverlayDialog` + 三列 `NumberPicker` 自建。
- **不要加 `miuix-nav` 依赖**：底栏是 `miuix-ui` 的 `NavigationBar`；`miuix-nav` 是替代 androidx.navigation 的整套导航库。
- **miuix 依赖必须带 `-android` 后缀**：那是发布给 AndroidX Compose 工程的变体；不带后缀会拉进 CMP 运行时并冲突。
