package dev.bloom.editor.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
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
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Group
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Blend
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Gesture { Tap, Swipe, Scroll, Hold }

/**
 * The only layers UI. Top layer first, Photo last; a group's header sits above its members.
 * Tap = settings bubble, sideways swipe = hide/show, hold then drag = reorder, vertical drag = scroll.
 * Layer and group ids share one space, so the callbacks take either: [onTap] gets the id and the thumbnail's vertical
 * centre in root coordinates, [onMove] the id and the rows dragged up (negative = down).
 * Tapping the layers pill collapses the rail; swiping it hides or shows all layers ([layersHidden]).
 */
@Composable
fun LayerRail(
    doc: Document,
    pending: LayerKind?, // kind of the draft layer when it is new
    activeId: Long?,
    preview: Bitmap,
    layersHidden: Boolean,
    onHideLayers: () -> Unit,
    onTap: (Long, Float) -> Unit,
    onActiveY: (Float) -> Unit, // the active thumbnail moved (reordered or scrolled), so its bubble can follow
    onToggle: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onPhotoTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var photoY by remember { mutableFloatStateOf(0f) }
    var collapsed by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { 48.dp.toPx() }
    val hideLayers by rememberUpdatedState(onHideLayers)

    if (collapsed) {
        Box(
            // a 20 dp tab at the edge in a 48 dp touch target
            modifier.padding(top = 8.dp).size(48.dp).clickable { collapsed = false }.semantics { contentDescription = "Show layers" },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier.size(20.dp, 48.dp).background(Bloom.SurfaceContainer, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Back, null, Modifier.size(14.dp), tint = Bloom.On) }
        }
        return
    }
    Column(modifier.padding(top = 8.dp, end = 4.dp).width(48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.height(48.dp).systemGestureExclusion()
                .semantics {
                    contentDescription = "Hide layers panel" + if (layersHidden) ". All layers hidden" else ""
                    customActions = listOf(CustomAccessibilityAction(if (layersHidden) "Show all layers" else "Hide all layers") { hideLayers(); true })
                }
                .clickable { collapsed = true }
                // after clickable, so it sees the swipe consumed and doesn't count it as a tap
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }) { c, dx ->
                        c.consume()
                        val past = abs(total) > 24.dp.toPx()
                        total += dx
                        if (!past && abs(total) > 24.dp.toPx()) hideLayers()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.heightIn(min = 28.dp).background(if (layersHidden) Bloom.PollenContainer else Bloom.SurfaceContainer, CircleShape)
                    .border(1.dp, if (layersHidden) Bloom.Pollen else Bloom.Outline, CircleShape).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (layersHidden) Icons.EyeOff else Icons.Layers, null, Modifier.size(14.dp), tint = Bloom.On)
                Text("${doc.layers.size}", fontSize = 11.sp, color = Bloom.On)
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(scroll)) {
                if (pending != null) Thumb(pending = true, desc = "New ${pending.label} layer, not applied yet") { Icon(pending.icon, null, Modifier.size(18.dp), tint = Bloom.On) }
                Column(Modifier.graphicsLayer { alpha = if (layersHidden) 0.45f else 1f }) {
                    val top = doc.layers.reversed()
                    top.forEachIndexed { i, l ->
                        val g = doc.groups.firstOrNull { it.id == l.group }
                        if (g != null && top.getOrNull(i - 1)?.group != g.id) key(g.id) {
                            RailItem(
                                g.id, Icons.Group,
                                (if (g.name.isEmpty()) "Group" else "${g.name}, group") + " of ${top.count { it.group == g.id }} layers" + (if (g.collapsed) ", collapsed" else "") + if (g.visible) "" else ", hidden",
                                if (g.visible) "Hide group" else "Show group",
                                g.id == activeId, hidden = !g.visible, masked = false, member = false, rowPx, onTap, onActiveY, onToggle, onMove,
                            )
                        }
                        if (g == null || !g.collapsed) key(l.id) {
                            RailItem(
                                l.id, l.kind.icon,
                                (if (l.name.isEmpty()) "" else "${l.name}, ") + "${l.kind.label} layer" + (if (g != null) " in ${g.title}" else "") +
                                    (if (l.mask.active) ", has mask" else "") + if (l.visible) "" else ", hidden",
                                if (l.visible) "Hide layer" else "Show layer",
                                l.id == activeId, hidden = !l.visible || g?.visible == false, masked = l.mask.active, member = g != null,
                                rowPx, onTap, onActiveY, onToggle, onMove,
                            )
                        }
                    }
                }
                Thumb(
                    Modifier.onGloballyPositioned { photoY = it.positionInRoot().y + it.size.height / 2f }
                        .clickable(role = Role.Button) { onPhotoTap(photoY) },
                    active = activeId == PHOTO_ID, desc = "Photo layer",
                ) {
                    Image(remember(preview) { preview.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

/** One layer, or a group's header, on the rail. */
@Composable
private fun RailItem(
    id: Long, icon: ImageVector, desc: String, toggleLabel: String, active: Boolean, hidden: Boolean, masked: Boolean, member: Boolean, rowPx: Float,
    onTap: (Long, Float) -> Unit, onActiveY: (Float) -> Unit, onToggle: (Long) -> Unit, onMove: (Long, Int) -> Unit,
) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    var centerY by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    Thumb(
        active = active, hidden = hidden, masked = masked, member = member, desc = desc,
        modifier = Modifier
            .onGloballyPositioned {
                centerY = it.positionInRoot().y + it.size.height / 2f
                if (active) onActiveY(centerY)
            }
            .zIndex(if (lifted) 1f else 0f)
            .systemGestureExclusion() // the rail is on the right edge: the back gesture would take its sideways swipes
            .semantics { customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(id); true }) }
            // before graphicsLayer: drags are measured unmoved and unscaled, not in the lifted thumbnail's own coordinates
            .pointerInput(id) {
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
                    when (g) {
                        Gesture.Tap -> onTap(id, centerY)
                        Gesture.Scroll -> Unit // left unconsumed so the rail's scroll picks it up
                        Gesture.Swipe -> {
                            var done = false
                            while (true) {
                                val c = awaitPointerEvent().changes.first { it.id == down.id }
                                if (!c.pressed) break
                                total += c.positionChange() // before consume(): a consumed change reads as zero
                                c.consume()
                                dx = (total.x * 0.3f).coerceIn(-10.dp.toPx(), 10.dp.toPx()) // follows with resistance
                                if (!done && abs(total.x) > 24.dp.toPx()) { done = true; onToggle(id) }
                            }
                            dx = 0f
                        }
                        Gesture.Hold -> {
                            lifted = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            while (true) {
                                val c = awaitPointerEvent().changes.first { it.id == down.id }
                                if (!c.pressed) break
                                dy += c.positionChange().y
                                c.consume()
                            }
                            val rows = -(dy / rowPx).roundToInt()
                            lifted = false
                            dy = 0f
                            if (rows != 0) onMove(id, rows)
                        }
                    }
                }
            }
            .graphicsLayer {
                translationX = dx
                translationY = dy
                if (lifted) { scaleX = 1.15f; scaleY = 1.15f; shadowElevation = 12f }
            },
    ) { Icon(icon, null, Modifier.size(18.dp), tint = Bloom.On) }
}

@Composable
private fun Thumb(
    modifier: Modifier = Modifier, active: Boolean = false, pending: Boolean = false, hidden: Boolean = false, masked: Boolean = false,
    member: Boolean = false, desc: String = "", content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(48.dp).semantics { if (desc.isNotEmpty()) contentDescription = desc }, contentAlignment = Alignment.Center) {
        // a line down the left joins a group's members under its header
        if (member) Box(Modifier.align(Alignment.CenterStart).padding(start = 1.dp).size(2.dp, 48.dp).background(Bloom.Outline))
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
        modifier.size(48.dp, 32.dp).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp, 20.dp).background(Bloom.SurfaceContainer, CircleShape).border(1.dp, Bloom.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(14.dp), tint = Bloom.On) }
    }
}

/** How each kind of layer is named and drawn on the rail and in its bubble. */
val LayerKind.label get() = name
val Layer.title get() = name.ifEmpty { kind.label }
val Group.title get() = name.ifEmpty { "Group" }
val LayerKind.icon get() = when (this) {
    LayerKind.Color -> Icons.Adjust
    LayerKind.Blur -> Icons.Blur
    LayerKind.Sharpen -> Icons.Sharpen
    LayerKind.Liquify -> Icons.Liquify
    LayerKind.Retouch -> Icons.Eraser
}

/** Bubble id of the Photo layer; layer ids start at 1. */
const val PHOTO_ID = 0L

/** A settings bubble's frame, with its tail pointing at the rail. */
@Composable
private fun Bubble(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        // a hit target of its own, so a touch on its background or labels doesn't fall through to the tap-outside layer
        Column(Modifier.width(236.dp).pointerInput(Unit) {}.background(Bloom.SurfaceHigh, shape).border(1.dp, Bloom.Outline, shape).padding(bottom = 8.dp), content = content)
        Box(Modifier.align(Alignment.TopEnd).padding(top = 34.dp).offset(x = 6.dp).size(12.dp).rotate(45f).background(Bloom.SurfaceHigh).border(1.dp, Bloom.Outline))
    }
}

/** Settings bubble for the Photo layer: Smooth banding, on by default; sources deeper than 8 bits don't need it. */
@Composable
fun PhotoBubble(deband: Boolean, deep: Boolean, onDeband: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Bubble(modifier) {
        Text("Photo", Modifier.heightIn(min = 48.dp).padding(start = 16.dp, top = 14.dp), color = Bloom.On)
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
}

/** A bubble's first row: the name (tap to rename; empty = [default]) and the visibility toggle. */
@Composable
private fun TitleRow(name: String, default: String, visible: Boolean, what: String, onRename: (String) -> Unit, onVisible: () -> Unit, extra: @Composable () -> Unit = {}) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editing) {
            var text by remember { mutableStateOf(TextFieldValue(name, TextRange(name.length))) }
            val focus = remember { FocusRequester() }
            BasicTextField(
                text, { text = it }, Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "$what name" },
                singleLine = true, textStyle = TextStyle(color = Bloom.On, fontSize = 16.sp), cursorBrush = SolidColor(Bloom.Pollen),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false; onRename(text.text.trim()) }),
                decorationBox = { field -> Box { if (text.text.isEmpty()) Text(default, color = Bloom.Outline); field() } },
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                name.ifEmpty { default }, Modifier.weight(1f).clickable(onClickLabel = "Rename") { editing = true }.padding(vertical = 12.dp),
                color = Bloom.On, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        extra()
        IconButton(onClick = onVisible) {
            Icon(if (visible) Icons.Eye else Icons.EyeOff, if (visible) "Hide $what" else "Show $what", tint = Bloom.On)
        }
    }
}

@Composable
private fun OpacityRow(opacity: Float, onOpacity: (Float) -> Unit, onOpacityDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity", Modifier.widthIn(min = 56.dp), fontSize = 12.sp, color = Bloom.OnVariant)
        Slider(
            value = opacity * 100f, onValueChange = { onOpacity(it.roundToInt() / 100f) }, onValueChangeFinished = onOpacityDone,
            valueRange = 0f..100f, modifier = Modifier.weight(1f).sliderLabel("Opacity", "${(opacity * 100).roundToInt()}%"), colors = sliderColors(),
        )
        Text("${(opacity * 100).roundToInt()}%", Modifier.widthIn(min = 40.dp).padding(start = 4.dp), fontSize = 13.sp, color = Bloom.On)
    }
}

/**
 * Settings bubble for one layer, pointing at its thumbnail on the rail. [onStep] moves it one step up or down (enabled
 * by [canUp] / [canDown]); [onGroup] groups it with the layer below, or ungroups its group when [grouped].
 */
@Composable
fun LayerBubble(
    layer: Layer,
    onRename: (String) -> Unit,
    onVisible: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityDone: () -> Unit,
    onEdit: () -> Unit,
    onEditMask: () -> Unit,
    onBlend: (Blend) -> Unit,
    onDelete: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    onStep: (up: Boolean) -> Unit,
    grouped: Boolean,
    canGroup: Boolean,
    onGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var blendMenu by remember { mutableStateOf(false) }
    Bubble(modifier) {
        TitleRow(layer.name, layer.kind.label, layer.visible, "layer", onRename, onVisible)
        OpacityRow(layer.opacity, onOpacity, onOpacityDone)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Blend", Modifier.widthIn(min = 56.dp), fontSize = 12.sp, color = Bloom.OnVariant)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            BubbleAction(Icons.MoveUp, "Move up", Modifier.weight(1f), { onStep(true) }, canUp)
            BubbleAction(Icons.MoveDown, "Move down", Modifier.weight(1f), { onStep(false) }, canDown)
            if (grouped) BubbleAction(Icons.Ungroup, "Ungroup", Modifier.weight(1f), onGroup)
            else BubbleAction(Icons.Group, "Group below", Modifier.weight(1f), onGroup, canGroup)
        }
    }
}

/** Settings bubble for a group, pointing at its header on the rail. */
@Composable
fun GroupBubble(
    group: Group,
    onRename: (String) -> Unit,
    onVisible: () -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityDone: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    onStep: (up: Boolean) -> Unit,
    onCollapse: () -> Unit,
    onUngroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Bubble(modifier) {
        TitleRow(group.name, "Group", group.visible, "group", onRename, onVisible) {
            IconButton(onClick = onCollapse) {
                Icon(if (group.collapsed) Icons.Down else Icons.Up, if (group.collapsed) "Expand group" else "Collapse group", tint = Bloom.On)
            }
        }
        OpacityRow(group.opacity, onOpacity, onOpacityDone)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            BubbleAction(Icons.MoveUp, "Move up", Modifier.weight(1f), { onStep(true) }, canUp)
            BubbleAction(Icons.MoveDown, "Move down", Modifier.weight(1f), { onStep(false) }, canDown)
            BubbleAction(Icons.Ungroup, "Ungroup", Modifier.weight(1f), onUngroup)
        }
    }
}

@Composable
private fun BubbleAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    val tint = if (enabled) Bloom.On else Bloom.Outline
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, fontSize = 11.sp, color = tint, textAlign = TextAlign.Center)
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Bloom.On, activeTrackColor = Bloom.Pollen, inactiveTrackColor = Bloom.Outline,
    activeTickColor = Bloom.Pollen, inactiveTickColor = Bloom.Outline,
)

/** A slider's name and value as read out ("Exposure", "+1.0 EV") instead of the bare percentage. */
fun Modifier.sliderLabel(name: String, value: String) = semantics { contentDescription = name; stateDescription = value }

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
