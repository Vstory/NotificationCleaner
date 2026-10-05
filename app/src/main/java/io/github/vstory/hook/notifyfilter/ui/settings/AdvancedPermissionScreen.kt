package io.github.vstory.hook.notifyfilter.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 高级权限子页：Shizuku / Root / LSPosed / 无障碍 保活通道与通知监听修复。
 * 状态并入 summary，动作词置于 endActions；通道不可用时整行 enabled=false 转灰
 * （官方示例第二个 BasicComponent 的用法），而不是留一个按了没反应的按钮。
 */
@Composable
fun AdvancedPermissionScreen(
    onBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = settingsVmFactory()),
) {
    val keepAlive by vm.keepAlive.collectAsState()
    val lspServiceState by vm.lspServiceState.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scopeMissing = lspServiceState.bound &&
        !lspServiceState.hasScope(LspServiceDetector.SCOPE_SYSTEM_SERVER)

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "高级权限",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Text(
                    "任一通道可用即可，无需全部开启。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            SmallTitle("保活通道")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = "Shizuku 保活",
                    summary = if (keepAlive.shizukuAvailable) "已授权" else "未检测到 Shizuku 服务",
                    endActions = {
                        Text(
                            if (keepAlive.shizukuAvailable) "应用" else "授权",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    onClick = {
                        if (keepAlive.shizukuAvailable) vm.applyShizuku() else vm.requestShizuku()
                    },
                )
                BasicComponent(
                    title = "Root 保活",
                    summary = if (keepAlive.rootAvailable) "已检测到 Root" else "未检测到 Root",
                    endActions = {
                        Text(
                            "应用",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    enabled = keepAlive.rootAvailable,
                    onClick = { vm.applyRoot() },
                )
                BasicComponent(
                    title = "LSPosed 保活",
                    summary = if (keepAlive.lspDetected == true) "已激活" else "未激活，请在 LSPosed 中启用本模块",
                    endActions = {
                        if (scopeMissing) {
                            Text(
                                "授权",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    },
                    enabled = lspServiceState.bound,
                    onClick = { vm.requestKeepAliveScope() },
                )
                BasicComponent(
                    title = "无障碍保活",
                    summary = if (keepAlive.accessibilityEnabled) "已开启，不读取屏幕内容" else "未开启",
                    endActions = {
                        if (!keepAlive.accessibilityEnabled) {
                            Text(
                                "去开启",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    },
                    onClick = { vm.openAccessibilitySettings() },
                )
            }

            SmallTitle("故障修复")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = "修复通知监听",
                    summary = "强制重建通知监听连接",
                    endActions = {
                        Text(
                            "修复",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    onClick = { vm.repairListener() },
                )
            }
        }
    }
}
