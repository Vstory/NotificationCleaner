package io.github.vstory.hook.notifyfilter.ui.component.blur

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import io.github.vstory.hook.notifyfilter.data.TopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalBlurEnabled = staticCompositionLocalOf { true }
val LocalTopBarBlurStyle = staticCompositionLocalOf { TopBarBlurStyle.Gaussian }

@Composable
fun Modifier.defaultBlurEffect(
    backdrop: LayerBackdrop,
): Modifier = this.textureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 25f,
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
        ),
    ),
)

@Composable
fun rememberBlurEnabled(): State<Boolean> =
    rememberUpdatedState(LocalBlurEnabled.current && isRuntimeShaderSupported())

/**
 * 顶栏/底栏毛玻璃所需的 backdrop；运行时不支持 RuntimeShader 时返回 null，
 * 调用方据此退化为不透明 surface 色（见 [BlurredBar]）。
 */
@Composable
fun rememberBlurBackdrop(enabled: Boolean = LocalBlurEnabled.current): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** 给顶栏/底栏铺毛玻璃背景；[blurActive] 为 false 或 backdrop 为空时不绘制，直接透出 bar 自身颜色。 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = rememberBlurEnabled().value,
    blurStyle: TopBarBlurStyle = LocalTopBarBlurStyle.current,
    content: @Composable () -> Unit,
) {
    val progressive = blurStyle == TopBarBlurStyle.Progressive
    Box(
        modifier = if (blurActive && backdrop != null && !progressive) {
            Modifier.defaultBlurEffect(backdrop)
        } else {
            Modifier
        },
    ) {
        if (blurActive && backdrop != null && progressive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 10f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.3f)),
                            ),
                        ),
                    ),
            )
        }
        content()
    }
}
