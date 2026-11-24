package dev.bloom.editor.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
fun Modifier.scrollFade(): Modifier {
    val state = rememberScrollState()
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } // DstIn needs its own layer
        .drawWithContent {
            drawContent()
            val w = 28.dp.toPx()
            if (state.value > 0) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, w), size = Size(w, size.height), blendMode = BlendMode.DstIn,
            )
            if (state.value < state.maxValue) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - w, size.width),
                topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn,
            )
            // ‹ and › where there is more: the fade alone is easy to miss
            val t = 5.dp.toPx()
            val cy = size.height / 2
            if (state.value > 0) drawPath(Path().apply { moveTo(1.dp.toPx(), cy); lineTo(1.dp.toPx() + t, cy - t); lineTo(1.dp.toPx() + t, cy + t); close() }, Bloom.OnVariant)
            if (state.value < state.maxValue) drawPath(Path().apply { moveTo(size.width - 1.dp.toPx(), cy); lineTo(size.width - 1.dp.toPx() - t, cy - t); lineTo(size.width - 1.dp.toPx() - t, cy + t); close() }, Bloom.OnVariant)
        }
        .horizontalScroll(state)
}

/** Stroke icons on a 24 grid, same paths as the mockup. */
object Icons {
    val Back = icon("M15 5l-7 7 7 7")
    val Undo = icon("M8 6L4 10l4 4M4 10h10a5 5 0 0 1 0 10h-3")
    val Redo = icon("M16 6l4 4-4 4M20 10H10a5 5 0 0 0 0 10h3")
    val Compare = icon("M12 3v18M4 6h5v12H4zM15 6h5v12h-5")
    val Layers = icon("M12 4l9 5-9 5-9-5 9-5zM3 14l9 5 9-5")
    // Color: three overlapping circles (light mixing)
    val Adjust = icon("M12 3.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 1 0 0-9zM8 10a4.5 4.5 0 1 0 0 9 4.5 4.5 0 1 0 0-9zM16 10a4.5 4.5 0 1 0 0 9 4.5 4.5 0 1 0 0-9z")
    val Check = icon("M5 12l5 5 9-10")
    val Reset = icon("M19 12a7 7 0 1 1-2-5M19 4v4h-4")
    val Eye = icon("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")
    val EyeOff = icon("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM4 4l16 16")
    val Trash = icon("M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13")
    val Brush = icon("M15 4l5 5-9 9H6v-5l9-9z")
    val Up = icon("M7 14l5-5 5 5")
    val Down = icon("M7 10l5 5 5-5")
    val MoveUp = icon("M12 19V5M6 11l6-6 6 6")
    val MoveDown = icon("M12 5v14M6 13l6 6 6-6")
    val Group = icon("M3 6h6l2 2h10v11H3z")
    val Ungroup = icon("M3 6h6l2 2h10v11H3zM4 4l16 16")
    val Palette = icon("M12 3a9 9 0 1 0 0 18c1 0 1.5-.7 1.5-1.5 0-1.2-1-1.3-1-2.5 0-.8.7-1.5 1.5-1.5H16a5 5 0 0 0 5-5c0-4.1-4-7.5-9-7.5zM7.5 11.5h.01M9.5 7.5h.01M14.5 7.5h.01")
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
    val Person = icon("M12 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM6 20c0-3.5 2.7-6 6-6s6 2.5 6 6M9 5.5L7.5 3.5M15 5.5l1.5-2M12 5V2.5")
    val Background = icon("M2 19l6.5-9 4.5 6 3-4 6 7zM17 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    // Detail: a focus frame
    val Detail = icon("M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6z")
    val Blur = icon("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11zM9 14a3 3 0 0 0 3 3")
    val Sharpen = icon("M12 3l7 9-7 9-7-9zM12 8v8")
    val Depth = icon("M3 20h18M5.5 20L10 5M18.5 20L14 5M8.3 11h7.4")
    val Push = icon("M4 12h14M13 7l5 5-5 5M4 8v8")
    val Bloat = icon("M9 9L4 4M4 4v4M4 4h4M15 9l5-5M20 4h-4M20 4v4M15 15l5 5M20 20v-4M20 20h-4M9 15l-5 5M4 20v-4M4 20h4")
    val Pinch = icon("M4 4l5 5M9 9V5M9 9H5M20 4l-5 5M15 9V5M15 9h4M20 20l-5-5M15 15v4M15 15h4M4 20l5-5M9 15v4M9 15H5")
    val Motion = icon("M3 8h9M3 12h14M3 16h9M15 7l5 5-5 5")
    val Zoom = icon("M12 12m-2 0a2 2 0 1 0 4 0 2 2 0 1 0-4 0M4 4l4.5 4.5M20 4l-4.5 4.5M4 20l4.5-4.5M20 20l-4.5-4.5M12 2v4M12 18v4M2 12h4M18 12h4")
    val Aperture = icon("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18zM14.5 3.4L10 11.5M20.6 10H11.5M18 18.3l-4.5-7.8M9.5 20.6L14 12.5M3.4 14h9.1M6 5.7l4.5 7.8")
    // Crop's rotate 90°: a frame and the arrow turning it (Reset is a plain circular arrow)
    val Turn = icon("M4 10h10v10H4zM8 6.5A8 8 0 0 1 19.5 10M20 5.5V10.5h-5")
    val Restore = icon("M4 4h16v16H4zM4 12h16M12 4v16")
    // Twirl: two separate arrows chasing round a circle, clockwise (y down: sweep 1)
    val Twirl = icon("M5.42 9.61A7 7 0 0 1 18.58 9.61M19.61 6.79L18.58 9.61 15.98 8.11M18.58 14.39A7 7 0 0 1 5.42 14.39M4.39 17.21L5.42 14.39 8.02 15.89")
    val RotateBack = icon("M4 12a8 8 0 1 0 2.3-5.7M4 3v4h4")
    // AI: a big and a small spark
    val Sparkle = icon("M10 3l1.8 5.2L17 10l-5.2 1.8L10 17l-1.8-5.2L3 10l5.2-1.8zM18 14l.9 2.1L21 17l-2.1.9L18 20l-.9-2.1L15 17l2.1-.9z")
    val Fill = icon("M4 20h16M7 16V12l7-7 4 4-7 7zM18 2l.7 1.8L20.5 4.5l-1.8.7L18 7l-.7-1.8-1.8-.7 1.8-.7z")
    val Upscale = icon("M4 14v6h6M4 20l6-6M14 4h6v6M20 4l-6 6")
    val Ask = icon("M4 5h16v11H10l-5 4v-4H4zM8 9h8M8 12h5")
    val Eraser = icon("M15 4l5 5-9.5 9.5H6L3 15.5zM8.5 10.5l5 5M10.5 20.5H21")
    val Liquify = icon("M3 8c3-3 6 3 9 0s6-3 9 0M3 16c3-3 6 3 9 0s6-3 9 0")
    val Crop = icon("M6 2v16h16M2 6h16v16")
    val Rotate = icon("M20 12a8 8 0 1 1-2.3-5.7M20 3v4h-4")
    val Flip = icon("M12 3v18M9 7L4 17h5zM15 7l5 10h-5z")
    val Settings = icon("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM10.3 3h3.4l.5 2.4 1.8 1 2.3-.8 1.7 2.9-1.8 1.6v2l1.8 1.6-1.7 2.9-2.3-.8-1.8 1-.5 2.4h-3.4l-.5-2.4-1.8-1-2.3.8-1.7-2.9 1.8-1.6v-2L3.9 8.5l1.7-2.9 2.3.8 1.8-1z")
    val Save = icon("M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6")
    val Plus = icon("M12 5v14M5 12h14")
    val Minus = icon("M5 12h14")
}

private fun icon(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(
        pathData = addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
