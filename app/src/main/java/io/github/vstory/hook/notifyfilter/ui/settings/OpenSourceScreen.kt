package io.github.vstory.hook.notifyfilter.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 参考开源项目（2.0.1 Dev 17）：
 * 本项目的借鉴、参考与依赖来源——UI 风格（miuix）、模块 API（libxposed）与免 Root 通道（Shizuku）。
 */
private data class OpenSourceProject(
    val name: String,
    val author: String,
    val url: String,
    val usage: String,
)

private val PROJECTS = listOf(
    OpenSourceProject(
        "miuix", "compose-miuix-ui", "https://github.com/compose-miuix-ui/miuix",
        "Compose UI 组件风格参考",
    ),
    OpenSourceProject(
        "libxposed API", "libxposed", "https://github.com/libxposed/api",
        "LSPosed Modern API（模块端 hook 能力）",
    ),
    OpenSourceProject(
        "Shizuku", "RikkaApps", "https://github.com/RikkaApps/Shizuku",
        "免 Root shell 通道（保活修复与诊断命令）",
    ),
)

@Composable
fun OpenSourceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "参考开源项目",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
        ) {
            item {
                Text(
                    "通知滤盒的诞生离不开以下开源项目——感谢每一位作者的付出。" +
                        "点击卡片可访问对应的 GitHub 仓库。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(PROJECTS, key = { it.name }) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                        }
                    },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name,
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "↗",
                                style = MiuixTheme.textStyles.headline2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "作者：${p.author}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(p.usage, style = MiuixTheme.textStyles.footnote1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.url.removePrefix("https://"),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "以上项目排名不分先后",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
