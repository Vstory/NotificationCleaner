# NotifyFilter 开发记录

记录 **`ui-miuix` 分支**（仓库默认分支、当前开发线）的开发状态。UI 迁移的详细计划、逐条组件映射与风险清单见 [miuix-migration.md](miuix-migration.md)。

> ⚠️ 本分支是开发线，**发版流程只认 `main`**（`build-release.yml` 硬编码 `refs/heads/main`）。发版前需把本分支合回 main。

## 当前状态（2026-10-06）

| 项 | 值 |
|---|---|
| 分支 | `ui-miuix`（仓库默认分支） |
| 版本 | `2.1.2` (72) |
| UI 栈 | miuix 0.9.4（`-android` 变体）；material3 与 backdrop 已清零 |
| 工具链 | AGP 9.4.1 · Kotlin 2.4.20 · KSP 2.3.9 · Gradle 9.7.1 · JDK 21 · compileSdk 37 |
| CI | `#20` 起每次 push 出 debug 包（一屏一提交） |

## 进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工具链切换（AGP 9 / Kotlin 2.4 / JDK 21 / 新 compileSdk DSL） | ✅ 完成 |
| 1 | 接入 miuix 依赖 + 拆旧 UI 骨架（`Theme.kt` 改双主题并存） | ✅ 完成 |
| 2 | 逐屏重写，11 个屏全部迁完（一屏一提交） | ✅ 完成 |
| 3 | 清理 material3 / backdrop / 玻璃模式残留 | ✅ 完成 |
| 3′ | 验收（dex 检查 + 真机走查）与合回 `main` | ⬜ 未开始 |

### 阶段 3 已完成清单

- [x] `androidx.compose.material3` 引用清零（原 19 处 = `GlassLib.kt` 15 + `Theme.kt` 4）
- [x] 删 `ui/glass/GlassLib.kt`（599 行）
- [x] 删 `kyant0:backdrop` / `kyant0:shapes` / `androidx.compose.material3:material3` 三条依赖
- [x] 删 `uiTheme` / `glassStyle` 双主题开关（DataStore key、`UiTheme`/`GlassStyle` 枚举、`LocalGlassMode`/`LocalGlassStyle`、`AppShapes`）及 `SettingsScreen` 的主题切换 UI
- [x] `OpenSourceScreen` 去掉 Backdrop / Shapes 致谢条目

### 阶段 3′ 待办

- [ ] dex 残留检查（无 material3 / backdrop 类）
- [ ] 真机安装 + 视觉走查
- [ ] 合回 `main`（发版前提）

## 关键约定（易踩）

- **产物名前缀三处同源**：`build-ci.yml` 的 `PREFIX`、`build-release.yml` 的 `PREFIX`、`UpdateViewModel` 的 `apkName` 必须同值（`NotifyFilter`），漏改最后一处 = 应用内更新 404。
- **miuix 无日期选择器**：`HistoryScreen` 的日期筛选是 `OverlayDialog` + 三列 `NumberPicker` 自建。
- **不要加 `miuix-nav` 依赖**：底栏是 `miuix-ui` 的 `NavigationBar`；`miuix-nav` 是替代 androidx.navigation 的整套导航库。
- **miuix 依赖必须带 `-android` 后缀**：那是发布给 AndroidX Compose 工程的变体；不带后缀会拉进 CMP 运行时并冲突。
- **不要给 miuix 用 `ColorSchemeMode.MonetSystem`**：miuix 的层次感靠中性底色的明度差（`surface` 纯黑 → `surfaceContainer` `#242424` → `secondaryContainer` `#434343`），Monet 会把这些角色整盘染上系统主题色 —— 卡片与背景糊成一片，`TextField`（默认填充 `secondaryContainer`）突兀跳色。用 `ColorSchemeMode.System`（出厂固定色板，仅跟随深浅色）。
- **miuix 的 `*Container` 是底色，不是文字色**：深色下 `tertiaryContainer` = `#2B3B54`，与卡片底 `#242424` 的对比度只有 1.4:1，当文字用基本隐形（浅色下同样不可读）。文字必须用配对的 `on*Container`（`onTertiaryContainer` = `#4788FF`，4.6:1）。
- **删文件前先全量 grep 调用点**：`GlassLib.kt` 的删除清单一直写着「已无任何界面引用」，实际 `MainActivity` 的玻璃底栏分支 + 三个屏的 `GlassFloatingBarClearance` 让位 padding 仍在读它，照单直接删会编译失败。
