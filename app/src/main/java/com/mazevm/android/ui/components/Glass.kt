package com.mazevm.android.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mazevm.android.ui.theme.maze

/**
 * Blurs whatever this modifier's content draws.
 *
 * Android only gained a blur render effect in 12; below that the call is skipped and
 * the surface falls back to translucency alone, which still separates it from the page
 * even though nothing is actually softened.
 */
fun Modifier.blurContent(radius: Dp): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(
                    radius.toPx(),
                    radius.toPx(),
                    Shader.TileMode.CLAMP,
                )
                .asComposeRenderEffect()
        }
    } else {
        Modifier
    }
)

/**
 * A floating translucent surface with a lit top edge.
 *
 * On a true-black background there is nothing to blur until content scrolls underneath,
 * so the effect is built from three things rather than blur alone: a translucent fill
 * that lets whatever passes below show through, a one-pixel highlight along the top
 * edge where a real pane would catch light, and a faint vertical gradient so the
 * surface is not flat. The blur itself is applied by the caller to the content behind,
 * because a layer cannot blur what is drawn outside it.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = maze
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.glass)
            .background(
                // Brightest at the top, fading out by a third of the way down: the way
                // light falls across a pane rather than an even wash.
                Brush.verticalGradient(
                    0f to palette.glassRim,
                    0.35f to Color.Transparent,
                )
            )
            // A hairline that fades along its length, so it reads as the edge catching
            // light rather than as a drawn outline.
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        palette.glassRim,
                        palette.glassRim.copy(alpha = palette.glassRim.alpha * 0.3f),
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
                shape = shape,
            ),
        content = content,
    )
}
