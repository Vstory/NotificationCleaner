package io.github.vstory.hook.notifyfilter.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vstory.hook.notifyfilter.update.UpdateState
import io.github.vstory.hook.notifyfilter.update.UpdateViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 进入应用时的更新提示弹窗（1.1.8）：
 * - Available：发现新版本 → 立即更新 / 关闭
 * - Downloading：显示进度，可取消
 * - ReadyToInstall：安装
 * - Checking/UpToDate/Error：不显示任何提示（静默）
 */
@Composable
fun UpdatePromptDialog(vm: UpdateViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> OverlayDialog(
            title = "发现新版本 v${s.release.versionName}",
            show = true,
            onDismissRequest = { vm.reset(); onDismiss() },
        ) {
            // 2.0.1 Dev 10：更新日志（notes）可能很长，旧实现直接堆一个 Text，
            // 超长时把按钮顶出屏幕且无法滚动。限高 320dp + 纵向滚动即可两全。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    s.release.notes.ifBlank { "已发布新版本，是否立即下载更新？" },
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Spacer(Modifier.height(20.dp))
            DialogButtons(
                dismissText = "关闭",
                onDismiss = { vm.reset(); onDismiss() },
                confirmText = "立即更新",
                onConfirm = { vm.startDownload(s.release) },
            )
        }

        is UpdateState.Downloading -> OverlayDialog(
            title = "正在下载更新",
            show = true,
            onDismissRequest = { vm.cancelDownload(); vm.reset(); onDismiss() },
        ) {
            Text("v${s.release.versionName}　${s.progress}%", style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = s.progress / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.cancelDownload(); vm.reset(); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
            }
        }

        is UpdateState.ReadyToInstall -> OverlayDialog(
            title = "更新包已就绪",
            show = true,
            onDismissRequest = { vm.reset(); onDismiss() },
        ) {
            Text(
                "点击「安装」打开系统安装器升级到 v${s.release.versionName}。",
                style = MiuixTheme.textStyles.body2,
            )
            Spacer(Modifier.height(20.dp))
            DialogButtons(
                dismissText = "稍后",
                onDismiss = { vm.reset(); onDismiss() },
                confirmText = "安装",
                onConfirm = { vm.install(s.release, s.file); onDismiss() },
            )
        }

        else -> {}
    }
}

@Composable
private fun DialogButtons(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(dismissText) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) { Text(confirmText) }
    }
}
