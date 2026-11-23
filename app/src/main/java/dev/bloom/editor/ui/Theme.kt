package dev.bloom.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Tokens from design/UI.md (dark). Neutral grays so the chrome never tints the photo; Pollen is the one accent. */
object Bloom {
    val Canvas = Color(0xFF0E0E0E)
    val Surface = Color(0xFF171717)
    val SurfaceContainer = Color(0xFF1F1F1F)
    val SurfaceHigh = Color(0xFF292929)
    val On = Color(0xFFEDEDED)
    val OnVariant = Color(0xFFA8A8A8)
    val Outline = Color(0xFF757575)
    val OutlineVariant = Color(0xFF333333)
    val Pollen = Color(0xFFFFC857)
    val OnPollen = Color(0xFF2A1D00)
    val PollenContainer = Color(0xFF4A3600)
    val OnPollenContainer = Color(0xFFFFE2A6)

    /** Petal: rounded rectangle with two tight corners. Primary button and selected tool only. */
    val Petal = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
}

@Composable
fun BloomTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Bloom.Pollen, onPrimary = Bloom.OnPollen,
        primaryContainer = Bloom.PollenContainer, onPrimaryContainer = Bloom.OnPollenContainer,
        background = Bloom.Canvas, surface = Bloom.Surface, onSurface = Bloom.On, onSurfaceVariant = Bloom.OnVariant,
        surfaceContainer = Bloom.SurfaceContainer, surfaceContainerHigh = Bloom.SurfaceHigh,
        outline = Bloom.Outline, outlineVariant = Bloom.OutlineVariant,
    ),
    content = content,
)

/**
 * Horizontal scroll that fades whichever edge has more content past it, so a row reads as scrollable.
 * Use in place of horizontalScroll on the row's viewport.
 */
@Composable
fun Modifier.scrollFade(state: ScrollState = rememberScrollState(), width: Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } // DstIn needs its own layer
        .drawWithContent {
            drawContent()
            val w = width.toPx()
            if (state.value > 0) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, w), size = Size(w, size.height), blendMode = BlendMode.DstIn,
            )
            if (state.value < state.maxValue) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - w, size.width),
                topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn,
            )
        }
        .horizontalScroll(state)

/** Stroke icons on a 24 grid, same paths as the mockup. */
object Icons {
    val Back = icon("M15 5l-7 7 7 7")
    val Undo = icon("M8 6L4 10l4 4M4 10h10a5 5 0 0 1 0 10h-3")
    val Redo = icon("M16 6l4 4-4 4M20 10H10a5 5 0 0 0 0 10h3")
    val Compare = icon("M12 3v18M4 6h5v12H4zM15 6h5v12h-5")
    val Layers = icon("M12 4l9 5-9 5-9-5 9-5zM3 14l9 5 9-5")
    val Adjust = icon("M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4")
    val Check = icon("M5 12l5 5 9-10")
    val Reset = icon("M19 12a7 7 0 1 1-2-5M19 4v4h-4")
    val Eye = icon("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")
    val EyeOff = icon("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM4 4l16 16")
    val Trash = icon("M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13")
    val Brush = icon("M15 4l5 5-9 9H6v-5l9-9z")
    val Up = icon("M7 14l5-5 5 5")
    val Down = icon("M7 10l5 5 5-5")
    val Image = icon("M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4")
    // Color parameters
    val Exposure = icon("M4 4h16v16H4zM4 20L20 4M6.5 8h4M8.5 6v4M13.5 16h4")
    val Contrast = icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 3v18M14 5.5l4 4M13.5 10l6 6M13.5 15.5l3 3")
    val Highlights = icon("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val Shadows = icon("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Temp = icon("M10 4a2 2 0 0 1 4 0v10a4 4 0 1 1-4 0zM12 9v7")
    val Tint = icon("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11zM9 17l6-6")
    val Saturation = icon("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11zM6 14h12")
    val Vibrance = icon("M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z")
    val Curves = icon("M4 4v16h16M4 20C10 20 9 7 20 4")
    val Mask = icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 3v18M8 4.5v15M4.5 8v8")
    val Gradient = icon("M4 4h16v16H4zM4 9h16M4 13.5h16M4 17.5h16")
    val Radial = icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")
    val Eyedropper = icon("M14 4.5l5.5 5.5M16.5 2.5a2.1 2.1 0 0 1 3 3L17 8l-1-1-9 9H4v-3l9-9-1-1 2.5-2.5z")
    val Luminance = icon("M3 19h18M3 19L21 5v14M9 19v-4.7M15 19V9.7")
    val Invert = icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 3v18M12 7h5M12 12h8M12 17h5")
    val Subject = icon("M12 4a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zM5 20c0-4 3-6.5 7-6.5s7 2.5 7 6.5")
    val Background = icon("M2 19l6.5-9 4.5 6 3-4 6 7zM17 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    val Detail = icon("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z")
    val Blur = icon("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11zM9 14a3 3 0 0 0 3 3")
    val Sharpen = icon("M12 4l8.5 15h-17zM12 10v4")
    val Depth = icon("M3 20h18M5.5 20L10 5M18.5 20L14 5M8.3 11h7.4")
    val Plus = icon("M12 5v14M5 12h14")
    val Minus = icon("M5 12h14")
}

private fun icon(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
        pathData = addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
