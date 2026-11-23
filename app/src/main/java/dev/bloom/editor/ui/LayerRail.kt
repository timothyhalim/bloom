package dev.bloom.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Blend
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Gesture { Tap, Swipe, Scroll, Hold }

/**
 * The only layers UI. Top layer first, Photo last.
 * Tap = settings bubble, sideways swipe = hide/show, hold then drag = reorder, vertical drag = scroll.
 * [onTap] gets the layer and the thumbnail's vertical centre in root coordinates.
 */
@Composable
fun LayerRail(
    layers: List<Layer>, // top first
    pending: LayerKind?, // kind of the draft layer when it is new
    activeId: Long?,
    preview: Bitmap?,
    onTap: (Layer, Float) -> Unit,
    onToggle: (Layer) -> Unit,
    onMove: (Layer, Int) -> Unit, // new top-first index
    onPhotoTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var photoY by remember { mutableFloatStateOf(0f) }
    var collapsed by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { 48.dp.toPx() }

    if (collapsed) {
        Box(
            modifier.padding(top = 8.dp).size(20.dp, 48.dp)
                .background(Bloom.SurfaceContainer, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .clickable { collapsed = false }.semantics { contentDescription = "Show layers" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Back, null, Modifier.size(14.dp), tint = Bloom.On) }
        return
    }
    Column(modifier.padding(top = 8.dp, end = 4.dp).width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.height(48.dp).clickable { collapsed = true }.semantics { contentDescription = "Hide layers" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(28.dp).background(Bloom.SurfaceContainer, CircleShape).border(1.dp, Bloom.Outline, CircleShape).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Layers, null, Modifier.size(14.dp), tint = Bloom.On)
                Text("${layers.size}", fontSize = 11.sp, color = Bloom.On)
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(scroll)) {
                if (pending != null) Thumb(pending = true, desc = "New ${pending.label} layer, not applied yet") { Icon(pending.icon, null, Modifier.size(18.dp), tint = Bloom.On) }
                layers.forEachIndexed { i, l -> RailItem(l, i, layers.size, l.id == activeId, rowPx, onTap, onToggle, onMove) }
                Thumb(
                    Modifier.onGloballyPositioned { photoY = it.positionInRoot().y + it.size.height / 2f }
                        .clickable(role = Role.Button) { onPhotoTap(photoY) },
                    active = activeId == PHOTO_ID, desc = "Photo layer",
                ) {
                    preview?.let { Image(remember(it) { it.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
            if (scroll.value > 0) Arrow(Icons.Up, "Scroll layers up", Modifier.align(Alignment.TopCenter)) {
                scope.launch { scroll.animateScrollBy(-rowPx) }
            }
            if (scroll.value < scroll.maxValue) Arrow(Icons.Down, "Scroll layers down", Modifier.align(Alignment.BottomCenter)) {
                scope.launch { scroll.animateScrollBy(rowPx) }
            }
        }
    }
}

@Composable
private fun RailItem(
    l: Layer, index: Int, count: Int, active: Boolean, rowPx: Float,
    onTap: (Layer, Float) -> Unit, onToggle: (Layer) -> Unit, onMove: (Layer, Int) -> Unit,
) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    var centerY by remember { mutableFloatStateOf(0f) }
    // The gesture loop is keyed on the id only, so a toggle mid-swipe doesn't restart it; read fresh values through these.
    val layer by rememberUpdatedState(l)
    val idx by rememberUpdatedState(index)
    val n by rememberUpdatedState(count)
    val name = "${l.kind.label} layer"
    Thumb(
        active = active, hidden = !l.visible, masked = l.mask.active,
        desc = name + (if (l.mask.active) ", has mask" else "") + if (l.visible) "" else ", hidden",
        modifier = Modifier
            .onGloballyPositioned { centerY = it.positionInRoot().y + it.size.height / 2f }
            .graphicsLayer {
                translationX = dx
                translationY = dy
                if (lifted) { scaleX = 1.15f; scaleY = 1.15f; shadowElevation = 12f }
            }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(if (l.visible) "Hide layer" else "Show layer") { onToggle(l); true })
            }
            .pointerInput(l.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var total = Offset.Zero
                    // The first touch-slop of movement decides: sideways = show/hide, vertical = scroll; none before the timeout = hold.
                    val g = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var r: Gesture? = null
                        while (r == null) {
                            val c = awaitPointerEvent().changes.first { it.id == down.id }
                            total += c.positionChange()
                            r = when {
                                !c.pressed -> Gesture.Tap
                                total.getDistance() <= viewConfiguration.touchSlop -> null
                                abs(total.x) > abs(total.y) -> Gesture.Swipe
                                else -> Gesture.Scroll
                            }
                        }
                        r
                    } ?: Gesture.Hold
                    val l = layer
                    when (g) {
                        Gesture.Tap -> onTap(l, centerY)
                        Gesture.Scroll -> Unit // left unconsumed so the rail's scroll picks it up
                        Gesture.Swipe -> {
                            var done = false
                            while (true) {
                                val c = awaitPointerEvent().changes.first { it.id == down.id }
                                if (!c.pressed) break
                                c.consume()
                                total += c.positionChange()
                                dx = (total.x * 0.3f).coerceIn(-10.dp.toPx(), 10.dp.toPx()) // follows with resistance
                                if (!done && abs(total.x) > 24.dp.toPx()) { done = true; onToggle(l) }
                            }
                            dx = 0f
                        }
                        Gesture.Hold -> {
                            lifted = true
                            while (true) {
                                val c = awaitPointerEvent().changes.first { it.id == down.id }
                                if (!c.pressed) break
                                c.consume()
                                dy += c.positionChange().y
                            }
                            val to = (idx + (dy / rowPx).roundToInt()).coerceIn(0, n - 1)
                            lifted = false
                            dy = 0f
                                            if (to != idx) onMove(l, to)
                        }
                    }
                }
            },
    ) { Icon(l.kind.icon, null, Modifier.size(18.dp), tint = Bloom.On) }
}

@Composable
private fun Thumb(
    modifier: Modifier = Modifier, active: Boolean = false, pending: Boolean = false, hidden: Boolean = false, masked: Boolean = false,
    desc: String = "", content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(44.dp, 48.dp).semantics { if (desc.isNotEmpty()) contentDescription = desc }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).clip(shape).background(Bloom.SurfaceContainer)
                .then(if (pending) Modifier else Modifier.border(if (active) 2.dp else 1.dp, if (active) Bloom.Pollen else Bloom.Outline, shape))
                .graphicsLayer { alpha = if (hidden) 0.45f else 1f },
            contentAlignment = Alignment.Center,
        ) { content() }
        if (hidden) Canvas(Modifier.size(28.dp)) {
            drawLine(Bloom.On, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
        }
        // half-filled dot: the layer has a mask
        if (masked) Canvas(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).size(10.dp)) {
            drawCircle(Bloom.Canvas, size.minDimension / 2 + 1.dp.toPx())
            drawArc(Bloom.On, 90f, 180f, true)
            drawCircle(Bloom.On, size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        // dashed: a draft layer that isn't part of the photo until applied
        if (pending) Canvas(Modifier.size(36.dp)) {
            val dash = 4.dp.toPx()
            drawRoundRect(
                Bloom.Pollen, cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
            )
        }
        if (active) Box(Modifier.align(Alignment.CenterStart).size(3.dp, 16.dp).background(Bloom.Pollen, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp, 32.dp).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp, 20.dp).background(Bloom.SurfaceContainer, CircleShape).border(1.dp, Bloom.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(14.dp), tint = Bloom.On) }
    }
}

/** How each kind of layer is named and drawn on the rail and in its bubble. */
val LayerKind.label get() = when (this) {
    LayerKind.Color -> "Color"
    LayerKind.Blur -> "Blur"
    LayerKind.Sharpen -> "Sharpen"
}
val LayerKind.icon get() = when (this) {
    LayerKind.Color -> Icons.Adjust
    LayerKind.Blur -> Icons.Blur
    LayerKind.Sharpen -> Icons.Sharpen
}

/** Bubble id of the Photo layer; Color layer ids start at 1. */
const val PHOTO_ID = 0L

/** Settings bubble for the Photo layer: Smooth banding, on by default; sources deeper than 8 bits don't need it. */
@Composable
fun PhotoBubble(deband: Boolean, deep: Boolean, onDeband: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Column(Modifier.width(236.dp).background(Bloom.SurfaceHigh, shape).border(1.dp, Bloom.Outline, shape).padding(bottom = 8.dp)) {
            Text("Photo", Modifier.height(48.dp).padding(start = 16.dp, top = 14.dp), color = Bloom.On)
            Row(
                Modifier.fillMaxWidth().toggleable(deband && !deep, enabled = !deep, role = Role.Switch, onValueChange = onDeband)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Smooth banding", fontSize = 14.sp, color = if (deep) Bloom.Outline else Bloom.On)
                    Text(
                        if (deep) "Not needed: this photo has more than 8 bits" else "Removes steps in skies when you push edits",
                        fontSize = 11.sp, color = Bloom.OnVariant,
                    )
                }
                Switch(deband && !deep, null, enabled = !deep)
            }
        }
        BubbleTail(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun BubbleTail(modifier: Modifier) = Box(
    modifier.padding(top = 34.dp).offset(x = 6.dp).size(12.dp).rotate(45f).background(Bloom.SurfaceHigh).border(1.dp, Bloom.Outline),
)

/** Settings bubble for one layer, pointing at its thumbnail on the rail. */
@Composable
fun LayerBubble(
    layer: Layer,
    onVisible: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityDone: () -> Unit,
    onEdit: () -> Unit,
    onEditMask: () -> Unit,
    onBlend: (Blend) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var blendMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Column(Modifier.width(236.dp).background(Bloom.SurfaceHigh, shape).border(1.dp, Bloom.Outline, shape).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(layer.kind.label, Modifier.weight(1f), color = Bloom.On)
                IconButton(onClick = onVisible) {
                    Icon(if (layer.visible) Icons.Eye else Icons.EyeOff, if (layer.visible) "Hide layer" else "Show layer", tint = Bloom.On)
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", Modifier.width(56.dp), fontSize = 12.sp, color = Bloom.OnVariant)
                Slider(
                    value = layer.opacity * 100f, onValueChange = { onOpacity(it.roundToInt() / 100f) }, onValueChangeFinished = onOpacityDone,
                    valueRange = 0f..100f, modifier = Modifier.weight(1f), colors = sliderColors(),
                )
                Text("${(layer.opacity * 100).roundToInt()}%", Modifier.width(40.dp).padding(start = 4.dp), fontSize = 13.sp, color = Bloom.On)
            }
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Blend", Modifier.width(56.dp), fontSize = 12.sp, color = Bloom.OnVariant)
                Box {
                    TextButton({ blendMenu = true }) { Text(layer.blend.label + " ▾", color = Bloom.On) }
                    DropdownMenu(blendMenu, { blendMenu = false }, containerColor = Bloom.SurfaceHigh) {
                        Blend.entries.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.label, color = Bloom.On) },
                                onClick = { blendMenu = false; onBlend(b) },
                                trailingIcon = if (b == layer.blend) ({ Icon(Icons.Check, "Current", tint = Bloom.Pollen) }) else null,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                BubbleAction(Icons.Brush, "Edit", Modifier.weight(1f), onEdit)
                BubbleAction(Icons.Mask, "Edit mask", Modifier.weight(1f), onEditMask)
                BubbleAction(Icons.Trash, "Delete", Modifier.weight(1f), onDelete)
            }
        }
        BubbleTail(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun BubbleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(52.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Bloom.On)
        Text(label, fontSize = 11.sp, color = Bloom.On)
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Bloom.On, activeTrackColor = Bloom.Pollen, inactiveTrackColor = Bloom.Outline,
    activeTickColor = Bloom.Pollen, inactiveTickColor = Bloom.Outline,
)

/** Minimap shown while zoomed past the view: whole photo plus the visible rectangle. Tap or drag to move there. */
@Composable
fun Navigator(preview: Bitmap, visible: android.graphics.RectF, onCenter: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val h = (66f * preview.height / preview.width).coerceAtMost(120f)
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.size(66.dp, h.dp).clip(shape).border(1.dp, Bloom.Outline, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var c = awaitFirstDown()
                    while (true) {
                        onCenter((c.position.x / size.width).coerceIn(0f, 1f), (c.position.y / size.height).coerceIn(0f, 1f))
                        c.consume()
                        c = awaitPointerEvent().changes.firstOrNull { it.id == c.id && it.pressed } ?: break
                    }
                }
            },
    ) {
        Image(remember(preview) { preview.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val tl = Offset(visible.left * size.width, visible.top * size.height)
            val sz = androidx.compose.ui.geometry.Size(visible.width() * size.width, visible.height() * size.height)
            drawRect(androidx.compose.ui.graphics.Color.Black, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            drawRect(androidx.compose.ui.graphics.Color.White, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}
