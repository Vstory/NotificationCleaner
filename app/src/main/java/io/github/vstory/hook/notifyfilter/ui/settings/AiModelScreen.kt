package io.github.vstory.hook.notifyfilter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AI 模型子页：查看基线/学习样本，重置为预训练基线。
 * 重置是破坏性动作，从设置页入口行整行可点（ArrowPreference 语义是进下一页）挪到此处并二次确认。
 */
@Composable
fun AiModelScreen(
    onBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = settingsVmFactory()),
) {
    val learnedSamples by vm.learnedSamples.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "AI 模型",
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

            SmallTitle("模型")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = "基线版本",
                    summary = "NSPM v2，随应用发版更新",
                )
                BasicComponent(
                    title = "已学习样本",
                    summary = "$learnedSamples 条",
                )
            }

            SmallTitle("维护")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = "重置为预训练基线",
                    summary = "清除全部学习标注，已拦截统计不受影响",
                    endActions = {
                        Text(
                            "重置",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    enabled = learnedSamples > 0,
                    onClick = { confirmReset = true },
                )
            }
        }

        if (confirmReset) {
            OverlayDialog(
                show = true,
                title = "确认重置模型？",
                onDismissRequest = { confirmReset = false },
            ) {
                Text("将清除所有学习标注，模型回到预训练基线。已拦截统计不受影响，此操作不可撤销。")
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { confirmReset = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { vm.resetModel(); confirmReset = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("确认重置", style = MiuixTheme.textStyles.button) }
                }
            }
        }
    }
}
