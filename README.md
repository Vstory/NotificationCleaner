# NotifyFilter · 通知净化

利用本地 AI 模型清理你的手机通知栏。模型推理、学习与规则匹配全部在设备本机完成，无云端依赖。

[![Release](https://img.shields.io/github/v/release/Vstory/NotifyFilter)](https://github.com/Vstory/NotifyFilter/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Android-13%2B-3DDC84)](https://developer.android.com/about/versions/13)

> 安装、权限与常见问题见 [Wiki](https://github.com/Vstory/NotifyFilter/wiki)。版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

### 过滤与学习

- **端上广告过滤**：字符 1~3-gram 哈希特征（2^18 桶）的逻辑回归模型，判定阈值可调（0.5–1.0，默认 0.8）
- **端上学习**：对任意通知标注「广告 / 正常」，模型在冻结基线之上全量重拟合稀疏增量（delta），取消标注可精确回滚；学习 APP + 通知渠道后，同渠道后续推送直接继承广告偏置
- **基线升级自动重拟合**：更换基线模型后自动重拟合增量，学习成果跨版本保留

### 规则与保护

- **手动规则**：按 APP + 标题/正文关键字建规则；白名单 APP 完全跳过 AI
- **内置保护**：验证码等硬放行；媒体播放、会话、常驻（进度 / 来电）通知默认不过滤

### 记录与诊断

- **通知历史**：三个分类页（可见 / 已拦截 / 历史）；未学习保留 7 天，已学习永久保留；统计明细页支持整批重新学习（只做一次重拟合）
- **监控式环形日志**：通知接收、决策、看门狗与崩溃栈等关键事件写入本地环形日志，保留近 24 小时，进程被杀与重启均不丢失
- **一键导出**：诊断日志按模块打包 ZIP（每模块覆盖完整 24 小时），另有 CSV 导出通知明细

### 监听自愈

- 前台服务 + 闹钟看门狗自动重连通知监听
- 监听失效时发出悬浮 + 锁屏可见提醒，内置「立即修复」
- **控制中心磁贴**：Shizuku / Root 直接强制重绑，未授权时一键跳转权限页

### 界面

- Miuix（HyperOS）设计语言，玻璃模糊与连续曲率圆角
- 底栏可选贴底或悬浮；悬浮样式提供 Miuix 胶囊与液态玻璃两档，支持图标与文字 / 仅图标
- 外观与主题：深浅色、动态取色与调色板、界面缩放（80%–110%）、横移返回与预测性返回手势

### 其他

- **应用内更新**：直接读取本仓库 Releases 并下载安装，sha256 校验，校验信息缺失即拒绝下载
- **LSPosed 模块**（可选）：在 system_server 内于通知入队前完成拦截，并阻止系统停止通知监听服务

## 下载

- 正式版：[GitHub Releases](https://github.com/Vstory/NotifyFilter/releases/latest)

## 构建

```bash
git clone https://github.com/Vstory/NotifyFilter.git
cd NotifyFilter
./gradlew assembleRelease
```

- Android Studio Ladybug+ / AGP 9.4.1 / Gradle 9.7.1 / Kotlin 2.4.20 / JDK 21
- `compileSdk 37`、`targetSdk 36`、`minSdk 33`（Android 13 起）
- 仅 arm64-v8a，R8 压缩

模型训练脚本见 `training/`（Python）：

```bash
pip install -r training/requirements.txt
python training/train.py --extra training/samples/real_history.csv --extra training/samples/bank_tx.csv --extra-weight 30
```

产物 `training/model_out/model.bin` 需拷入 `app/src/main/resources/model/`。

## LSPosed 模块（可选）

安装后可在 LSPosed 管理器中启用本模块（作用域：`system`）：

- 在 system_server 内、通知入队前完成拦截（早于监听器，无过滤延迟）
- 阻止系统停止通知监听服务

基于 libxposed Modern API 102（`META-INF/xposed` 注册）。

## 隐私

- 模型推理、学习与规则匹配全部在设备本地完成
- 联网权限仅用于「检查更新 / 下载 APK」
- 通知数据仅存本地 Room 数据库，不上传任何服务器
- 环形日志同样只存本机私有目录，仅在用户主动导出时才离开设备

## 许可证

[MIT](LICENSE)。本项目 fork 自 [ytdttj/NotificationCleaner](https://github.com/ytdttj/NotificationCleaner)。
