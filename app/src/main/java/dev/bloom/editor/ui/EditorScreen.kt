package dev.bloom.editor.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.bloom.editor.ImageIo
import dev.bloom.editor.PhotoView
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.ModelStore
import dev.bloom.editor.Segment
import dev.bloom.editor.SubjectMask
import dev.bloom.editor.model.History
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the editor shows. Committed edits live in [history]; the open tool edits [draft] until ✓. */
class EditorState {
    var source by mutableStateOf<Uri?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var history by mutableStateOf(History())
    var draft by mutableStateOf<Layer?>(null)
    var editingId by mutableStateOf<Long?>(null)
    var param by mutableStateOf(ColorParam.Exposure)
    var curvesOpen by mutableStateOf(false) // the Curves chip is selected instead of [param]
    var curveChannel by mutableIntStateOf(0) // 0 RGB, 1 R, 2 G, 3 B
    var comparing by mutableStateOf(false)
    var opacityPreview by mutableStateOf<Pair<Long, Float>?>(null)
    var bubble by mutableStateOf<Pair<Long, Float>?>(null) // layer id, thumbnail centre y (root px)

    // Mask mode edits draft.mask; ✓ in mask mode returns to the tool, the tool's ✓ applies both.
    var maskMode by mutableStateOf(false)
    var maskAdd by mutableStateOf(true)
    var maskTool by mutableStateOf(MaskTool.Brush)
    var maskMore by mutableStateOf(false)
    var stepIndex by mutableStateOf<Int?>(null)
    var brushSize by mutableFloatStateOf(40f) // radius in dp on screen
    var softness by mutableFloatStateOf(50f)
    var flow by mutableFloatStateOf(100f)
    var addingSample by mutableStateOf(false) // the next eyedropper tap adds a sample instead of replacing
    var subjects by mutableStateOf(emptyMap<List<Float>, SubjectMask>()) // this photo's subject masks, by pins (empty = automatic)
    var subjectStatus by mutableStateOf<String?>(null) // "Downloading…", "Finding…", or an error while there is no mask
    var subjectRetry by mutableIntStateOf(0)

    /** Pin sets of every Subject or Background step (applied layers and the draft): the masks needed. */
    val neededSubjects: Set<List<Float>>
        get() = (doc.layers + listOfNotNull(draft)).flatMap { l -> l.mask.steps.filterIsInstance<SubjectStep>().map { it.pins } }.toSet()
    private var nextId = 1L

    val doc: Document get() = history.current

    /** What the preview renders: committed layers, live opacity drag, then the draft. */
    fun rendered(): List<Layer> {
        if (comparing) return emptyList()
        var ls = doc.layers
        opacityPreview?.let { (id, o) -> ls = ls.map { if (it.id == id) it.copy(opacity = o) else it } }
        draft?.let { d -> ls = if (editingId != null) ls.map { if (it.id == d.id) d else it } else ls + d }
        return ls
    }

    fun openColor(edit: Layer? = null, mask: Boolean = false) {
        draft = edit ?: Layer(nextId++)
        editingId = edit?.id
        param = ColorParam.Exposure
        curvesOpen = false
        curveChannel = 0
        bubble = null
        maskMode = mask
        maskTool = MaskTool.Brush
        stepIndex = null
    }

    fun setMask(m: Mask) {
        draft = draft?.copy(mask = m)
    }

    fun replaceStep(i: Int, step: MaskStep) {
        val m = draft?.mask ?: return
        setMask(m.copy(steps = m.steps.toMutableList().also { it[i] = step }))
    }

    fun deleteStep(i: Int) {
        val m = draft?.mask ?: return
        setMask(m.copy(steps = m.steps.filterIndexed { j, _ -> j != i }))
        stepIndex = null
    }

    fun selectStep(i: Int) {
        stepIndex = if (stepIndex == i) null else i
        val s = draft?.mask?.steps?.getOrNull(i) ?: return
        maskTool = toolFor(s)
        addingSample = false
    }

    private fun toolFor(s: MaskStep) = when (s) {
        is BrushStep -> MaskTool.Brush
        is GradientStep -> MaskTool.Gradient
        is ColorStep -> MaskTool.Color
        is LuminanceStep -> MaskTool.Luminance
        is SubjectStep -> MaskTool.Subject
    }

    /** Adds [step] at the end of the mask, selects it and switches to its tool. */
    fun addStep(step: MaskStep) {
        val m = draft?.mask ?: return
        setMask(m.copy(steps = m.steps + step))
        stepIndex = m.steps.size
        maskTool = toolFor(step)
        addingSample = false
    }

    /** Committed layers under the draft: what Color and Luminance steps and the eyedropper see. */
    fun below(): List<Layer> {
        val d = draft ?: return emptyList()
        return if (editingId != null) doc.layers.takeWhile { it.id != d.id } else doc.layers
    }

    /** Adds a gradient with the current sign (full at the top, fading out past the middle) and selects it. */
    fun addGradient() = addStep(GradientStep(false, 0.5f, 0.2f, 0.5f, 0.6f, add = maskAdd))

    val dirty: Boolean
        get() = draft?.let { d -> if (editingId != null) d != doc.layers.firstOrNull { it.id == d.id } else d.edited } ?: false

    fun apply() {
        val d = draft ?: return
        // A new layer with nothing changed would only clutter the stack.
        if (editingId != null) edit { it.replace(d) } else if (d.edited) edit { it.copy(layers = it.layers + d) }
        close()
    }

    fun close() {
        draft = null
        editingId = null
        maskMode = false
    }

    fun edit(f: (Document) -> Document) {
        history = history.push(f(doc))
    }

    fun load(uri: Uri, bmp: Bitmap) {
        source = uri
        preview = bmp
        subjects = emptyMap()
        subjectStatus = null
        history = History()
        close()
        bubble = null
    }
}

@Composable
fun EditorScreen(photoView: PhotoView) {
    val state = remember { EditorState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf<Float?>(null) } // export progress 0..1, null when idle
    var viewport by remember { mutableStateOf<RectF?>(null) }
    var chromePx by remember { mutableIntStateOf(0) }
    var canvasTop by remember { mutableStateOf(0f) }
    var canvasBottom by remember { mutableStateOf(0f) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var toggleToasts by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    var transformTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        photoView.onViewport = { viewport = it }
        photoView.onTransform = { transformTick++ }
        launch { snapshotFlow { state.doc.deband }.collect(photoView::setDeband) }
        launch { snapshotFlow { state.subjects }.collect(photoView::setSubjects) }
        snapshotFlow { state.rendered() }.collect(photoView::setLayers)
    }
    // Subject masks are found once per photo and pin set, when a Subject or Background step needs one.
    LaunchedEffect(state.preview, state.neededSubjects, state.subjectRetry) {
        val photo = state.preview ?: return@LaunchedEffect
        for (pins in state.neededSubjects - state.subjects.keys) {
            state.subjectStatus = if (pins.isEmpty()) "Finding the subject…" else "Selecting…"
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val get = { name: String -> ModelStore.get(context, name) { state.subjectStatus = "Downloading AI model… ${(it * 100).toInt()}%" } }
                    val t = System.nanoTime()
                    val m = if (pins.isEmpty()) Segment.subject(get("subject"), photo)
                    else Segment.pinned(get("sam_encoder"), get("sam_decoder"), photo, pins)
                    android.util.Log.d("Bloom", "subject (${pins.size / 3} pins): ${(System.nanoTime() - t) / 1_000_000} ms")
                    m ?: error("the model couldn't run on this phone")
                }
            }
            r.exceptionOrNull()?.let { android.util.Log.e("Bloom", "subject failed", it) }
            if (state.preview !== photo) return@LaunchedEffect
            r.onSuccess { state.subjects = state.subjects + (pins to it) }
            state.subjectStatus = r.exceptionOrNull()?.let { "Couldn't find the subject: ${it.message}" }
            if (r.isFailure) return@LaunchedEffect
        }
    }
    // Mask overlay and painting only in mask mode.
    val shownPreview = state.preview
    // Loupe while painting or picking a color; brush ring only for the brush.
    LaunchedEffect(state.maskMode, state.maskTool, state.brushSize) {
        photoView.loupe = state.maskMode && (state.maskTool == MaskTool.Brush || state.maskTool == MaskTool.Color)
        photoView.brushRadiusPx = if (state.maskMode && state.maskTool == MaskTool.Brush) state.brushSize * density.density else 0f
    }
    LaunchedEffect(state.draft?.id, state.maskMode, shownPreview) {
        photoView.setOverlay(state.draft?.id?.takeIf { state.maskMode })
        photoView.onTool = if (state.maskMode && shownPreview != null) {
            MaskTouch(state, photoView, shownPreview.width.toFloat() / shownPreview.height, density.density) { u, v, done ->
                photoView.sample(state.below(), u, v, done)
            }::on
        } else null
    }

    fun open(uri: android.net.Uri?) {
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ImageIo.loadPreview(context.contentResolver, uri) } }
                .onSuccess { state.load(uri, it); photoView.setImage(it) }
                .onFailure { snackbar.showSnackbar("Can't open image: ${it.message ?: it}") }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::open)
    // Files the photo picker doesn't list, e.g. CR3 raws (Android has no MIME type for them, so they show as octet-stream)
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::open)
    fun export(dst: android.net.Uri?, png: Boolean) {
        val src = state.source
        if (dst == null || src == null) return
        val layers = state.doc.layers
        val deband = state.doc.deband
        scope.launch {
            exporting = 0f
            val subjects = state.subjects
            val r = runCatching {
                withContext(Dispatchers.IO) { ImageIo.export(context.contentResolver, src, dst, context.cacheDir, layers, deband, png, subjects) { exporting = it } }
            }
            exporting = null
            r.exceptionOrNull()?.let { android.util.Log.e("Bloom", "export failed", it) }
            snackbar.showSnackbar(r.fold({ "Saved" }, { "Export failed: ${it.message ?: it}" }))
        }
    }
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { export(it, false) }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { export(it, true) }
    val openPicker = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    fun toggle(l: Layer) {
        state.edit { it.replace(l.copy(visible = !l.visible)) }
        if (toggleToasts++ < 3) scope.launch {
            if (snackbar.showSnackbar("Color layer ${if (l.visible) "hidden" else "shown"}", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                state.history = state.history.undo()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bloom.Canvas)) {
        TopBar(
            state, exporting,
            onBack = {
                if (state.doc.layers.isEmpty()) state.source = null
                else confirm = "Close photo? Your edits will be lost." to { state.source = null; state.history = History(); state.bubble = null }
            },
            onExport = { png ->
                val name = "bloom_${System.currentTimeMillis()}"
                if (png) savePng.launch("$name.png") else saveJpeg.launch("$name.jpg")
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { canvasTop = it.positionInRoot().y; canvasBottom = canvasTop + it.size.height }) {
            AndroidView({ photoView }, Modifier.fillMaxSize())
            val selGradient = state.stepIndex?.let { state.draft?.mask?.steps?.getOrNull(it) } as? GradientStep
            if (state.maskMode && selGradient != null && selGradient.visible) GradientHandles(selGradient, photoView, transformTick)
            val selSubject = state.stepIndex?.let { state.draft?.mask?.steps?.getOrNull(it) } as? SubjectStep
            if (state.maskMode && selSubject != null) Pins(selSubject, photoView, transformTick)
            val preview = state.preview
            if (state.source == null || preview == null) {
                // opaque, so a closed photo doesn't show through
                Box(Modifier.fillMaxSize().background(Bloom.Canvas)) { EmptyState(openPicker, { pickFile.launch(arrayOf("image/*", "application/octet-stream")) }, Modifier.align(Alignment.Center)) }
            } else {
                val top = state.doc.layers.reversed()
                LayerRail(
                    layers = top,
                    pendingNew = state.draft != null && state.editingId == null,
                    activeId = state.bubble?.first ?: state.editingId,
                    preview = preview,
                    onTap = { l, y -> state.bubble = if (state.bubble?.first == l.id) null else l.id to y },
                    onToggle = ::toggle,
                    onMove = { l, to -> state.edit { it.move(l.id, top.size - 1 - to) } },
                    onPhotoTap = { y -> state.bubble = if (state.bubble?.first == PHOTO_ID) null else PHOTO_ID to y },
                    modifier = Modifier.align(Alignment.TopEnd).padding(bottom = with(density) { chromePx.toDp() } + 8.dp),
                )
                viewport?.let { v ->
                    Navigator(
                        preview, v, photoView::centerOn,
                        Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = with(density) { chromePx.toDp() } + 8.dp),
                    )
                }
                Chrome(
                    state,
                    // The chrome floats over the photo. Only the bottom bar, which never changes height, reserves space,
                    // so rows appearing above it never move the photo.
                    onBarTop = { top -> photoView.setBottomInset((canvasBottom - top).roundToInt()) },
                    onDiscard = { if (state.dirty) confirm = "Discard changes?" to state::close else state.close() },
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                        chromePx = it.height
                    },
                )
                state.bubble?.takeIf { it.first == PHOTO_ID }?.let { (_, y) ->
                    Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) { state.bubble = null })
                    PhotoBubble(
                        state.doc.deband, deep = preview?.config == Bitmap.Config.RGBA_F16,
                        onDeband = { on -> state.edit { it.copy(deband = on) } },
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 52.dp)
                            .offset { IntOffset(0, (y - canvasTop - 40.dp.toPx()).roundToInt().coerceAtLeast(0)) },
                    )
                }
                state.bubble?.let { (id, y) ->
                    val l = state.doc.layers.firstOrNull { it.id == id } ?: return@let
                    val shown = state.opacityPreview?.takeIf { it.first == id }?.let { l.copy(opacity = it.second) } ?: l
                    // tap outside closes the bubble and does nothing else
                    Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) { state.bubble = null })
                    LayerBubble(
                        shown,
                        onVisible = { toggle(l) },
                        onOpacity = { state.opacityPreview = id to it },
                        onOpacityDone = {
                            state.opacityPreview?.let { (_, o) -> state.edit { d -> d.replace(l.copy(opacity = o)) } }
                            state.opacityPreview = null
                        },
                        onEdit = { state.openColor(l) },
                        onEditMask = { state.openColor(l, mask = true) },
                        onBlend = { b -> state.edit { d -> d.replace(l.copy(blend = b)) } },
                        onDelete = { state.edit { it.remove(id) }; state.bubble = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 52.dp)
                            .offset { IntOffset(0, (y - canvasTop - 40.dp.toPx()).roundToInt().coerceAtLeast(0)) },
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { chromePx.toDp() }))
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(text) },
            confirmButton = { TextButton({ confirm = null; action() }) { Text("Discard") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep editing") } },
            containerColor = Bloom.SurfaceHigh,
        )
    }
}

@Composable
private fun TopBar(state: EditorState, exporting: Float?, onBack: () -> Unit, onExport: (png: Boolean) -> Unit) {
    val busy = exporting != null
    val open = state.source != null
    val idle = state.draft == null // undo/redo cover applied edits; inside a tool, Cancel covers the draft
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Back, "Close photo", open, onBack)
        BarIcon(Icons.Undo, "Undo", open && idle && state.history.canUndo) { state.history = state.history.undo() }
        BarIcon(Icons.Redo, "Redo", open && idle && state.history.canRedo) { state.history = state.history.redo() }
        Box(
            Modifier.size(48.dp).semantics { contentDescription = "Compare. Hold to see the original" }
                .then(if (open) Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = { state.comparing = true; tryAwaitRelease(); state.comparing = false })
                } else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Compare, null, tint = if (open) Bloom.On else Bloom.Outline) }
        Spacer(Modifier.weight(1f))
        var menu by remember { mutableStateOf(false) }
        Box {
            Box(
                Modifier.padding(end = 8.dp).height(40.dp).clip(Bloom.Petal)
                    .background(if (open && idle && !busy) Bloom.Pollen else Bloom.SurfaceHigh)
                    .clickable(enabled = open && idle && !busy, role = Role.Button) { menu = true }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (exporting != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator({ exporting }, Modifier.size(20.dp), color = Bloom.Pollen, strokeWidth = 2.dp, trackColor = Bloom.Outline)
                    Text("${(exporting * 100).toInt()}%", Modifier.padding(start = 8.dp), color = Bloom.On, fontWeight = FontWeight.Medium)
                }
                else Text("Export", color = if (open && idle) Bloom.OnPollen else Bloom.Outline, fontWeight = FontWeight.Medium)
            }
            DropdownMenu(menu, { menu = false }, Modifier.background(Bloom.SurfaceHigh)) {
                DropdownMenuItem({ Text("JPEG", color = Bloom.On) }, { menu = false; onExport(false) })
                DropdownMenuItem({ Text("16-bit PNG (larger, for further editing)", color = Bloom.On) }, { menu = false; onExport(true) })
            }
        }
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) { Icon(icon, desc, tint = if (enabled) Bloom.On else Bloom.Outline) }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, onOpenFile: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bloom", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Bloom.On)
        Text("Edit in layers. Everything runs on your phone.", Modifier.padding(top = 8.dp, bottom = 24.dp), color = Bloom.OnVariant)
        Row(
            Modifier.height(52.dp).clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 6.dp)).background(Bloom.Pollen)
                .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Image, null, tint = Bloom.OnPollen)
            Text("Open photo", Modifier.padding(start = 8.dp), color = Bloom.OnPollen, fontWeight = FontWeight.Medium)
        }
        Text(
            "Open file (RAW: CR3, NEF, ARW, DNG…)",
            Modifier.padding(top = 12.dp).clip(Bloom.Petal).clickable(role = Role.Button, onClick = onOpenFile).padding(12.dp),
            color = Bloom.OnVariant,
        )
    }
}

/** Floating bottom chrome. The bar holds what you edit; the row above holds how (slider, Reset). */
@Composable
private fun Chrome(state: EditorState, onBarTop: (Float) -> Unit, onDiscard: () -> Unit, modifier: Modifier) {
    val float = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val d = state.draft
        val box = Modifier.fillMaxWidth().background(Bloom.Surface, float).border(1.dp, Bloom.Outline, float)
        val rowModifier = box.height(48.dp)
        if (d != null && state.maskMode) MaskRows(state, box)
        if (d != null && !state.maskMode && state.curvesOpen) CurvesRow(state, box)
        if (d != null && !state.maskMode && !state.curvesOpen) {
            val p = state.param
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.width(92.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
                Slider(
                    value = d[p], onValueChange = { state.draft = d.with(p, snap(p, it)) },
                    valueRange = -100f..100f, modifier = Modifier.weight(1f), colors = sliderColors(),
                )
                Text(shown(p, d[p]), Modifier.width(44.dp), fontSize = 14.sp, color = Bloom.On)
                IconButton({ state.draft = d.with(p, 0f) }) { Icon(Icons.Reset, "Reset ${p.name}", tint = Bloom.On) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned { onBarTop(it.positionInRoot().y) }
                .background(Bloom.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Bloom.Outline, RoundedCornerShape(20.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d == null) {
                // M1 ships Color only; each later tool joins the bar when it works.
                ToolButton("Color", Modifier.weight(1f)) { state.openColor() }
            } else if (state.maskMode) {
                MaskBar(state)
            } else {
                Box(
                    Modifier.size(54.dp, 48.dp).clickable(role = Role.Button, onClick = onDiscard)
                        .semantics { contentDescription = "Color. Back to tools" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 30.dp).background(Bloom.PollenContainer, Bloom.Petal), contentAlignment = Alignment.Center) {
                        Icon(Icons.Adjust, null, tint = Bloom.OnPollenContainer)
                    }
                }
                Row(Modifier.weight(1f).scrollFade(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ColorParam.entries.forEach { p ->
                        // Curves sits with the tone controls, before color
                        if (p == ColorParam.Temp) ParamChip(Icons.Curves, "Curves" + if (d.curves.active) ", edited" else "", d.curves.active, state.curvesOpen) { state.curvesOpen = true }
                        ParamChip(p.icon, "${p.name} ${shown(p, d[p])}" + if (p == ColorParam.Exposure) " stops" else "", d[p] != 0f, !state.curvesOpen && p == state.param) {
                            state.param = p
                            state.curvesOpen = false
                        }
                    }
                }
                MaskButton(d.mask) { state.maskMode = true }
                IconButton(state::apply) { Icon(Icons.Check, if (state.editingId != null) "Apply changes" else "Apply as Color layer", tint = Bloom.Pollen) }
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(58.dp).clickable(role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(44.dp, 30.dp), contentAlignment = Alignment.Center) { Icon(Icons.Adjust, null, tint = Bloom.OnVariant) }
        Text(label, fontSize = 11.sp, color = Bloom.OnVariant, fontWeight = FontWeight.Medium)
    }
}

private val ColorParam.icon get() = when (this) {
    ColorParam.Exposure -> Icons.Exposure
    ColorParam.Contrast -> Icons.Contrast
    ColorParam.Highlights -> Icons.Highlights
    ColorParam.Shadows -> Icons.Shadows
    ColorParam.Temp -> Icons.Temp
    ColorParam.Tint -> Icons.Tint
    ColorParam.Saturation -> Icons.Saturation
    ColorParam.Vibrance -> Icons.Vibrance
}

/** Parameter in the bar: icon only. The slider row above names the selected one; a dot marks changed values. */
@Composable
private fun ParamChip(icon: ImageVector, desc: String, changed: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp, 44.dp).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(Bloom.SurfaceHigh).drawBehind {
                drawLine(Bloom.Pollen, Offset(8.dp.toPx(), size.height - 1.dp.toPx()), Offset(size.width - 8.dp.toPx(), size.height - 1.dp.toPx()), 2.dp.toPx())
            } else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) Bloom.Pollen else Bloom.On)
        // changed values get a dot, so state isn't carried by color alone
        if (changed) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(5.dp).background(Bloom.Pollen, CircleShape))
    }
}

// Exposure reads in stops (EV), like a camera: the stored ±100 is ±3 stops, set in 0.1-stop steps.
private fun stops(v: Float) = (v * 3 / 100 * 10).roundToInt() / 10f

private fun snap(p: ColorParam, v: Float) = if (p == ColorParam.Exposure) stops(v) * 100 / 3 else v.roundToInt().toFloat()

private fun shown(p: ColorParam, v: Float): String {
    if (p != ColorParam.Exposure) return signed(v)
    val s = stops(v)
    return when {
        s > 0 -> "+%.1f".format(s)
        s < 0 -> "−%.1f".format(-s)
        else -> "0"
    }
}

private fun signed(v: Float): String {
    val i = v.roundToInt()
    return when {
        i > 0 -> "+$i"
        i < 0 -> "−${abs(i)}"
        else -> "0"
    }
}
