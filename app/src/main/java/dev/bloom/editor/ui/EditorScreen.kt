package dev.bloom.editor.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.bloom.editor.Project
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.model.PersonStep
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.Patch
import dev.bloom.editor.model.RetouchEffect
import dev.bloom.editor.model.WarpEffect
import dev.bloom.editor.model.WarpTool
import dev.bloom.editor.ToolTouch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Effect
import dev.bloom.editor.ModelStore
import dev.bloom.editor.Segment
import dev.bloom.editor.EraseArea
import dev.bloom.editor.ai.AiRequest
import dev.bloom.editor.ai.AiResult
import dev.bloom.editor.ai.Capability
import dev.bloom.editor.ai.OnDevice
import dev.bloom.editor.ai.AiConfig
import dev.bloom.editor.ai.AiError
import dev.bloom.editor.ai.EditOps
import dev.bloom.editor.ai.Choice
import dev.bloom.editor.ai.Keys
import dev.bloom.editor.ai.open
import dev.bloom.editor.ai.ProviderType
import dev.bloom.editor.SubjectMask
import dev.bloom.editor.model.History
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the editor shows. Committed edits live in [history]; the open tool edits [draft] until ✓. */
class EditorState {
    var source by mutableStateOf<Uri?>(null) // the photo file: the project's original, or its upscaled copy
    var preview by mutableStateOf<Bitmap?>(null)
    var photoSize by mutableStateOf<Pair<Int, Int>?>(null) // the source's upright size, px; null for a raw file
    var history by mutableStateOf(History(current = Document()))
    var project by mutableStateOf<Project?>(null) // where the photo autosaves
    var opened: Document? = null // the project's document as opened; null for a new one
    var draft by mutableStateOf<Layer?>(null)
    // The open tool's own undo steps: the draft (or crop) after each gesture. ✓ then commits the session as one document step.
    var steps by mutableStateOf(History<Pair<Layer?, Crop?>>(current = null to null))
    var editingId by mutableStateOf<Long?>(null)
    var param by mutableStateOf<ColorParam?>(ColorParam.Exposure) // null = the Curves chip
    var curveChannel by mutableIntStateOf(0) // 0 RGB, 1 R, 2 G, 3 B
    var detailMore by mutableStateOf(false) // Detail's More rows shown
    var comparing by mutableStateOf(false)
    // Latched by a swipe on the rail's layers pill: a view setting, not saved or undone, unlike a layer's own visibility
    var layersHidden by mutableStateOf(false)
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
    var depth by mutableStateOf<SubjectMask?>(null) // this photo's depth map, once a lens blur needs it
    var depthStatus by mutableStateOf<String?>(null) // "Measuring depth…" or an error while there is no depth map
    var aiRetry by mutableIntStateOf(0) // Retry: reruns the depth and subject jobs that failed

    // The Crop tool edits [crop] (non-null while open) until ✓; it is a document setting, not a layer.
    var crop by mutableStateOf<Crop?>(null)

    // Liquify brush settings (kept between layers)
    var warpTool by mutableStateOf(WarpTool.Push)
    var warpSize by mutableFloatStateOf(60f) // radius in dp on screen
    var warpStrength by mutableFloatStateOf(50f)
    var warpSoftness by mutableFloatStateOf(50f)
    var warpMore by mutableStateOf(false) // Strength and Softness rows shown
    var warpReverse by mutableStateOf(false) // Twirl counterclockwise
    var aiMode by mutableStateOf(Capability.ERASE) // the AI tool's mode, if a provider still offers it
    var fillPrompt by mutableStateOf("") // what Fill paints
    var upscaleBy by mutableIntStateOf(2)
    var askText by mutableStateOf("") // Ask's request
    var askTarget by mutableStateOf<Long?>(null) // the layer Ask refines (from its bubble); null = any change
    var asked by mutableStateOf<EditOps.Parsed?>(null) // Ask's reply, shown as a checklist and previewed
    var askOff by mutableStateOf(emptySet<Int>()) // the reply's ops the user unticked
    var running by mutableStateOf<String?>(null) // an AI job running: what it is doing ("Downloading…", "Erasing…", "Queued, 3 ahead")
    var runCancel by mutableStateOf<(() -> Unit)?>(null) // a remote job running: stops it
    var ai by mutableStateOf(AiConfig()) // AI providers and choices (Settings)

    // A brush's size and softness shown mid-photo while their sliders move: radius dp, softness 0..100, and a tick to time it out
    var ring by mutableStateOf<Pair<Float, Float>?>(null)
    var ringTick by mutableIntStateOf(0)
    fun showRing(sizeDp: Float, softness: Float) { ring = sizeDp to softness; ringTick++ }
    var cropRatio by mutableStateOf<Float?>(null) // locked output width / height; null = free
    var cropMore by mutableStateOf(false) // Crop's perspective rows shown

    /** A Lens blur or a depth range step exists (applied or in the draft), so the depth map is needed. */
    val needsDepth: Boolean
        get() = ((askResult() ?: doc).layers + listOfNotNull(draft)).any { l ->
            l.effect.let { it is DetailEffect && !it.sharpen && it.detail.lens } || l.mask.steps.any { it is LuminanceStep && it.depth }
        }

    /** Pin sets of every Subject or Background step, and [PersonStep.KEY] for Person steps (applied layers and the draft): the masks needed. */
    val neededSubjects: Set<List<Float>>
        get() = ((askResult() ?: doc).layers + listOfNotNull(draft)).flatMap { l ->
            l.mask.steps.mapNotNull { if (it is SubjectStep) it.pins else if (it is PersonStep) PersonStep.KEY else null }
        }.toSet()
    private var nextId = 1L

    val doc: Document get() = history.current

    /** What the preview renders: committed layers (none while [layersHidden]), live opacity drag, then the draft. */
    fun rendered(): List<Layer> {
        if (comparing) return emptyList()
        var d = askResult() ?: doc
        opacityPreview?.let { (id, o) ->
            d = d.copy(layers = d.layers.map { if (it.id == id) it.copy(opacity = o) else it }, groups = d.groups.map { if (it.id == id) it.copy(opacity = o) else it })
        }
        val dr = draft
        if (dr != null && editingId != null) d = d.replace(dr)
        val ls = d.rendered().filter { !layersHidden || it.id == dr?.id }
        return if (dr != null && editingId == null) ls + dr else ls
    }

    fun newId() = nextId++

    /** The document with Ask's ticked changes, while its checklist shows. */
    fun askResult(): Document? = asked?.let { a -> EditOps.apply(doc, a.ops.filterIndexed { i, _ -> i !in askOff }, nextId) }

    /** Applies Ask's ticked changes as one document step and closes the tool. */
    fun applyAsk() {
        val d = askResult() ?: return
        nextId += asked!!.ops.size
        edit { d }
        close()
    }

    /** Opens the tool for a new layer of [effect], or for [edit]. */
    fun openTool(effect: Effect = ColorEffect(), edit: Layer? = null, mask: Boolean = false) {
        draft = edit ?: Layer(newId(), effect = effect)
        editingId = edit?.id
        param = ColorParam.Exposure
        curveChannel = 0
        bubble = null
        maskMode = mask
        maskTool = MaskTool.Brush
        stepIndex = null
        steps = History(current = draft to crop)
    }

    /** Records the draft as a step if it changed; called once every finger is up, so one gesture = one step. */
    fun commitStep() {
        steps = steps.push(draft to crop)
    }

    fun undoStep() {
        commitStep() // a change no gesture recorded yet (an async color sample) is the one to undo
        steps = steps.undo()
        restoreStep()
    }

    fun redoStep() {
        commitStep()
        steps = steps.redo()
        restoreStep()
    }

    private fun restoreStep() {
        draft = steps.current.first
        crop = steps.current.second
        stepIndex = stepIndex?.takeIf { it < (draft?.mask?.steps?.size ?: 0) }
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
        is PersonStep -> MaskTool.Person
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
        get() = asked != null || crop?.let { it != doc.crop } ?: draft?.let { d -> if (editingId != null) d != doc.layers.firstOrNull { it.id == d.id } else d.edited } ?: false

    fun apply() {
        val d = draft ?: return
        // A new layer with nothing changed would only clutter the stack.
        if (editingId != null) edit { it.replace(d) } else if (d.edited) edit { it.copy(layers = it.layers + d) }
        close()
    }

    fun openCrop() {
        crop = doc.crop
        cropRatio = null
        bubble = null
        steps = History(current = draft to crop)
    }

    fun applyCrop() {
        val c = crop ?: return
        edit { it.copy(crop = c) }
        crop = null
    }

    fun close() {
        asked = null
        askOff = emptySet()
        askTarget = null
        askText = ""
        crop = null
        draft = null
        editingId = null
        maskMode = false
        steps = History(current = draft to crop)
    }

    fun edit(f: (Document) -> Document) {
        history = history.push(f(doc))
    }

    /**
     * Opens a photo into [project], with [saved] its document when the project was opened before, or with nulls closes
     * it; either way drops the last photo's edits and AI masks.
     */
    fun load(uri: Uri?, bmp: Bitmap?, project: Project? = null, saved: Document? = null) {
        source = uri
        preview = bmp
        this.project = project
        opened = saved
        subjects = emptyMap()
        subjectStatus = null
        depth = null
        depthStatus = null
        history = History(current = saved ?: Document())
        nextId = ((saved?.layers.orEmpty().map { it.id } + saved?.groups.orEmpty().map { it.id }).maxOrNull() ?: 0L) + 1
        layersHidden = false
        close()
        bubble = null
    }
}

@Composable
fun EditorScreen(photoView: PhotoView, state: EditorState = remember { EditorState() }) {
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

    // Tool settings (brush sizes, strengths, which More boxes are open) are kept between sessions.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tools", android.content.Context.MODE_PRIVATE)
        val floats = listOf(
            Triple("brushSize", { state.brushSize }, { v: Float -> state.brushSize = v }),
            Triple("softness", { state.softness }, { v: Float -> state.softness = v }),
            Triple("flow", { state.flow }, { v: Float -> state.flow = v }),
            Triple("warpSize", { state.warpSize }, { v: Float -> state.warpSize = v }),
            Triple("warpStrength", { state.warpStrength }, { v: Float -> state.warpStrength = v }),
            Triple("warpSoftness", { state.warpSoftness }, { v: Float -> state.warpSoftness = v }),
        )
        val flags = listOf(
            Triple("warpReverse", { state.warpReverse }, { v: Boolean -> state.warpReverse = v }),
            Triple("maskMore", { state.maskMore }, { v: Boolean -> state.maskMore = v }),
            Triple("warpMore", { state.warpMore }, { v: Boolean -> state.warpMore = v }),
            Triple("detailMore", { state.detailMore }, { v: Boolean -> state.detailMore = v }),
            Triple("cropMore", { state.cropMore }, { v: Boolean -> state.cropMore = v }),
        )
        for ((k, get, set) in floats) set(prefs.getFloat(k, get()))
        for ((k, get, set) in flags) set(prefs.getBoolean(k, get()))
        prefs.getString("warpTool", null)?.let { n -> WarpTool.entries.firstOrNull { it.name == n }?.let { state.warpTool = it } }
        prefs.getString("aiMode", null)?.let { n -> Capability.entries.firstOrNull { it.name == n }?.let { state.aiMode = it } }
        snapshotFlow { floats.map { it.second() } + flags.map { it.second() } + state.warpTool.name + state.aiMode.name }.collect {
            prefs.edit().apply {
                for ((k, get) in floats) putFloat(k, get())
                for ((k, get) in flags) putBoolean(k, get())
                putString("warpTool", state.warpTool.name)
                putString("aiMode", state.aiMode.name)
            }.apply()
        }
    }

    LaunchedEffect(Unit) {
        state.ai = AiConfig.load(context)
        snapshotFlow { state.ai }.drop(1).collect { AiConfig.save(context, it) }
    }
    val phone = remember { OnDevice(context) }
    fun provider(c: Choice) = if (c.provider == OnDevice.ID) phone else open(state.ai.providers.first { it.id == c.provider }, Keys.get(context, c.provider))
    var settings by remember { mutableStateOf(false) }
    val aiModes by remember { derivedStateOf { AI_MODES.filter { state.ai.options(it, phone).isNotEmpty() } } }
    val aiMode by remember { derivedStateOf { state.aiMode.takeIf { it in aiModes } ?: Capability.ERASE } }

    var transformTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        photoView.onViewport = { viewport = it }
        photoView.onTransform = { transformTick++ }
        launch { snapshotFlow { state.doc.deband }.collect(photoView::setDeband) }
        launch { snapshotFlow { state.subjects }.collect(photoView::setSubjects) }
        launch { snapshotFlow { state.depth }.collect(photoView::setDepth) }
        // the Crop tool shows the whole frame with the crop drawn over it; otherwise only the crop is shown
        launch {
            snapshotFlow { Triple(state.preview, state.crop ?: state.doc.crop, state.crop != null) }.collect { (_, c, full) -> photoView.setCrop(c, full) }
        }
        snapshotFlow { state.rendered() }.collect(photoView::setLayers)
    }
    // Derived, so a stroke or slider changing the draft doesn't recompose this screen or restart these effects.
    val needsDepth by remember { derivedStateOf { state.needsDepth } }
    val neededSubjects by remember { derivedStateOf { state.neededSubjects } }
    // The depth map is measured once per photo, the first time a lens blur needs it.
    LaunchedEffect(state.preview, needsDepth, state.aiRetry) {
        val photo = state.preview ?: return@LaunchedEffect
        if (!needsDepth || state.depth != null) { state.depthStatus = null; return@LaunchedEffect }
        state.depthStatus = "Measuring depth…"
        val r = runCatching {
            withContext(Dispatchers.IO) {
                val model = ModelStore.get(context, "depth") { state.depthStatus = "Downloading AI model… ${(it * 100).toInt()}%" }
                val t = System.nanoTime()
                (Segment.depth(model, photo) ?: error("the model couldn't run on this phone")).also {
                    android.util.Log.d("Bloom", "depth: ${(System.nanoTime() - t) / 1_000_000} ms")
                }
            }
        }
        r.exceptionOrNull()?.let { if (it is CancellationException) throw it; android.util.Log.e("Bloom", "depth failed", it) }
        if (state.preview !== photo) return@LaunchedEffect
        state.depth = r.getOrNull()
        state.depthStatus = r.exceptionOrNull()?.let { "Couldn't measure depth: ${reason(it)}" }
    }
    // Subject masks are found once per photo and pin set, when a Subject or Background step needs one.
    LaunchedEffect(state.preview, neededSubjects, state.aiRetry) {
        val photo = state.preview ?: return@LaunchedEffect
        val missing = neededSubjects - state.subjects.keys
        if (missing.isEmpty()) state.subjectStatus = null // a job cancelled mid-run leaves its "Finding…"
        for (pins in missing) {
            val person = pins == PersonStep.KEY
            state.subjectStatus = if (person) "Finding people…" else if (pins.isEmpty()) "Finding the subject…" else "Selecting…"
            val r = runCatching {
                val t = System.nanoTime()
                // ponytail: only the phone offers SEGMENT; a remote one would need the consent sheet, as this runs without a tap
                val m = if (person) phone.run(Capability.BG_REPLACE, OnDevice.MATTING, AiRequest(photo)) { state.subjectStatus = it } as AiResult.Mask
                else state.ai.choice(Capability.SEGMENT, phone)!!.let { c -> provider(c).run(Capability.SEGMENT, c.model, AiRequest(photo, pins = pins)) { state.subjectStatus = it } as AiResult.Mask }
                android.util.Log.d("Bloom", "${if (person) "person" else "subject (${pins.size / 3} pins)"}: ${(System.nanoTime() - t) / 1_000_000} ms")
                m.mask
            }
            r.exceptionOrNull()?.let { if (it is CancellationException) throw it; android.util.Log.e("Bloom", "subject failed", it) }
            if (state.preview !== photo) return@LaunchedEffect
            r.onSuccess { state.subjects = state.subjects + (pins to it) }
            state.subjectStatus = r.exceptionOrNull()?.let { "Couldn't find the subject: ${reason(it)}" }
            if (r.isFailure) return@LaunchedEffect
        }
    }
    // Lens focus and Zoom centre: one finger sets the point
    val lensOpen by remember { derivedStateOf { state.draft?.effect.let { it is DetailEffect && !it.sharpen && it.detail.hasPoint } } }
    val liquifyOpen by remember { derivedStateOf { state.draft?.kind == LayerKind.Liquify && !state.maskMode } }
    // AI Erase: one finger paints the layer's mask directly, with the mask's red tint showing
    val eraseOpen by remember { derivedStateOf { state.draft?.kind == LayerKind.Retouch && !state.maskMode && aiMode in PAINTED } }
    // Loupe while painting or picking a color; brush ring for the mask brush and Liquify.
    LaunchedEffect(state.maskMode, state.maskTool, state.brushSize, liquifyOpen, eraseOpen, state.warpSize, state.softness, state.warpSoftness) {
        photoView.loupe = (state.maskMode || eraseOpen) && (state.maskTool == MaskTool.Brush || state.maskTool == MaskTool.Color)
        photoView.brushRadiusPx = when {
            liquifyOpen -> state.warpSize * density.density
            (state.maskMode || eraseOpen) && state.maskTool == MaskTool.Brush -> state.brushSize * density.density
            else -> 0f
        }
        photoView.brushSoftness = (if (liquifyOpen) state.warpSoftness else state.softness) / 100f
    }
    // the preview ring fades out shortly after the slider stops
    LaunchedEffect(state.ringTick) { if (state.ring != null) { kotlinx.coroutines.delay(700); state.ring = null } }
    val cropOpen by remember { derivedStateOf { state.crop != null } }
    LaunchedEffect(cropOpen) { photoView.margin = if (cropOpen) 32 * density.density else 0f }
    val draftId by remember { derivedStateOf { state.draft?.id } }
    LaunchedEffect(draftId, state.maskMode, state.preview, lensOpen, cropOpen, liquifyOpen, eraseOpen) {
        photoView.setOverlay(draftId?.takeIf { state.maskMode || eraseOpen })
        val aspect = state.preview?.let { it.width.toFloat() / it.height }
        photoView.onTool = when {
            aspect == null -> null
            cropOpen -> CropTouch(state, photoView, aspect, density.density)::on
            liquifyOpen -> LiquifyTouch(state, photoView, aspect, density.density)::on
            state.maskMode || eraseOpen -> MaskTouch(state, photoView, aspect, density.density) { u, v, done ->
                photoView.sample(state.below(), u, v, done)
            }::on
            lensOpen -> { t, u, v ->
                // Lens focus or Zoom centre: tap or drag on the photo to move it
                val d = state.draft
                val e = d?.effect
                if (d != null && e is DetailEffect && (t == ToolTouch.Down || t == ToolTouch.Move)) {
                    state.draft = d.copy(effect = e.copy(detail = e.detail.copy(focusX = u.coerceIn(0f, 1f), focusY = v.coerceIn(0f, 1f))))
                }
            }
            else -> null
        }
    }

    // A photo opens from its project's copy: the picker's permission to the original doesn't outlive the app.
    fun open(uri: android.net.Uri?) {
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val p = Project.create(context.contentResolver, context.filesDir, uri)
                    runCatching { p to ImageIo.loadPreview(context.contentResolver, Uri.fromFile(p.original)) }.onFailure { p.delete() }.getOrThrow()
                }
            }
                .onSuccess { (p, bmp) -> state.load(Uri.fromFile(p.original), bmp, p); photoView.setImage(bmp) }
                .onFailure { snackbar.showSnackbar("Can't open image: ${it.message ?: it}") }
        }
    }

    fun openProject(dir: File) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val (p, doc) = Project.open(dir)
                    Triple(p, doc, ImageIo.loadPreview(context.contentResolver, Uri.fromFile(p.photo(doc))))
                }
            }
                .onSuccess { (p, doc, bmp) -> state.load(Uri.fromFile(p.photo(doc)), bmp, p, doc); photoView.setImage(bmp) }
                .onFailure { android.util.Log.e("Bloom", "open project failed", it); snackbar.showSnackbar("Can't open project: ${it.message ?: it}") }
        }
    }

    var recents by remember { mutableStateOf(emptyList<Project.Recent>()) }
    var recentsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.source == null, recentsTick) {
        if (state.source == null) recents = withContext(Dispatchers.IO) { Project.list(context.filesDir) }
    }

    suspend fun save(p: Project, doc: Document, photo: Bitmap, subjects: Map<List<Float>, SubjectMask>, depth: SubjectMask?) {
        runCatching { withContext(Dispatchers.IO) { p.save(doc, photo, subjects, depth) } }.onFailure {
            if (it is CancellationException) throw it
            android.util.Log.e("Bloom", "save failed", it)
            snackbar.showSnackbar("Couldn't save the project: ${it.message ?: it}")
        }
    }

    // Autosave: the committed document (not an open tool's draft), 2 s after it last changed, and when the app leaves the screen.
    suspend fun autosave() {
        val p = state.project ?: return
        save(p, state.doc, state.preview ?: return, state.subjects, state.depth)
    }
    LaunchedEffect(state.project) { snapshotFlow { state.doc }.drop(1).collectLatest { delay(2000); autosave() } }
    LaunchedEffect(state.source) { state.photoSize = state.source?.let { withContext(Dispatchers.IO) { ImageIo.size(context.contentResolver, it) } } }
    // Upscale swaps the photo for a bigger copy, undo and redo swap it back: layers, masks and crop are in 0..1 of the
    // photo, so they stay put; subject and depth masks are kept too
    LaunchedEffect(state.doc.source) {
        val p = state.project ?: return@LaunchedEffect
        val uri = Uri.fromFile(p.photo(state.doc))
        if (uri == state.source) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { ImageIo.loadPreview(context.contentResolver, uri) } }
            .onSuccess { state.source = uri; state.preview = it; photoView.setImage(it) }
            .onFailure { if (it is CancellationException) throw it; snackbar.showSnackbar("Can't open the photo: ${reason(it)}") }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { autosave() } }

    // Back to Home. Keep saves the project (one with no edits is removed); Discard removes a new project, or takes an
    // opened one back to how it was when opened.
    fun closeProject(keep: Boolean) {
        val p = state.project
        val doc = if (keep) state.doc else state.opened
        val photo = state.preview
        val subjects = state.subjects
        val depth = state.depth
        state.load(null, null)
        if (p != null && photo != null) scope.launch {
            if (doc?.edited == true) save(p, doc, photo, subjects, depth) else withContext(Dispatchers.IO) { p.delete() }
            recentsTick++
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::open)
    // Files the photo picker doesn't list, e.g. CR3 raws (Android has no MIME type for them, so they show as octet-stream)
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::open)
    // A photo crop goes to a provider off the phone only after the consent sheet
    var consent by remember { mutableStateOf<Send?>(null) }
    fun send(cap: Capability, what: String, go: (Choice) -> Unit) {
        val c = state.ai.choice(cap, phone) ?: return
        if (state.ai.needsConsent(c.provider)) consent = Send(cap, c, what, go) else go(c)
    }

    // Runs an AI job with [c], the AI tool locked with its status showing; [failed] names it in an error ("erase").
    // Cancel shows for remote jobs and [stoppable] ones: the phone's models can't stop mid-run, but Upscale stops between tiles.
    fun runJob(c: Choice, status: String, failed: String, stoppable: Boolean = false, work: suspend () -> Unit) {
        val remote = c.provider != OnDevice.ID
        state.running = if (remote) "Sending to ${state.ai.name(c.provider)}…" else status
        val job = scope.launch {
            val e = runCatching { work() }.exceptionOrNull()
            state.running = null
            state.runCancel = null
            if (e == null || e is CancellationException) return@launch
            android.util.Log.e("Bloom", "$failed failed", e)
            snackbar.showSnackbar("Couldn't $failed: ${reason(e)}")
        }
        if ((remote || stoppable) && job.isActive) state.runCancel = { job.cancel() }
    }

    // AI Erase and Fill: fill the layer's mask from the photo (LaMa on the phone, or the chosen provider; Fill with the
    // prompt), then keep it as a Retouch layer above the photo.
    fun runFill(cap: Capability, c: Choice, area: EraseArea, prompt: String, id: Long, photo: Bitmap) = runJob(c, "Erasing…", cap.label.lowercase()) {
        val t = System.nanoTime()
        val filled = provider(c).run(cap, c.model, AiRequest(area.crop, area.fill, prompt = prompt)) { state.running = it } as AiResult.Image
        android.util.Log.d("Bloom", "${cap.label} (${c.provider}): ${(System.nanoTime() - t) / 1_000_000} ms")
        val (box, px) = withContext(Dispatchers.Default) { area.paste(filled.bitmap) }
        if (state.draft?.id != id) return@runJob
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val done = state.draft!!.copy(effect = RetouchEffect(Patch(box[0] / w, box[1] / h, box[2] / w, box[3] / h, px)))
        // a new Retouch layer goes right above the photo, so every other layer still applies to the filled area
        if (state.editingId != null) state.edit { it.replace(done) } else state.edit { it.copy(layers = listOf(done) + it.layers) }
        state.close()
    }
    fun fill(cap: Capability) {
        val d = state.draft ?: return
        val photo = state.preview ?: return
        val below = state.rendered()
        val prompt = state.fillPrompt.trim()
        val verb = cap.label.lowercase()
        state.running = "Preparing…"
        photoView.maskOf(below, d.id) { hole ->
            scope.launch {
                val r = runCatching {
                    hole ?: error("paint over the area first")
                    // the mask is soft at its edge; the model needs a clear hole: anything at least half painted
                    val bits = ByteArray(hole.size) { if ((hole[it].toInt() and 0xFF) >= 128) 1 else 0 }
                    withContext(Dispatchers.Default) { EraseArea.of(photo, bits) } ?: error("nothing to $verb")
                }
                state.running = null
                val area = r.getOrElse { snackbar.showSnackbar("Couldn't $verb: ${reason(it)}"); return@launch }
                val what = "a ${area.crop.width} × ${area.crop.height} px crop of the photo around the painted area, and its mask" + if (prompt.isEmpty()) "" else ", with your prompt"
                send(cap, what) { c -> runFill(cap, c, area, prompt, d.id, photo) }
            }
        }
    }

    // Upscale: the whole photo goes to the provider; the result replaces it as the project's photo (one undo step)
    fun upscale() {
        val p = state.project ?: return
        val src = state.source ?: return
        val (w, h) = state.photoSize ?: return
        val by = state.upscaleBy
        send(Capability.UPSCALE, "the whole photo, $w × $h px") { c ->
            runJob(c, "Upscaling…", "upscale", stoppable = true) {
                val photo = withContext(Dispatchers.IO) { ImageIo.loadPreview(context.contentResolver, src, Int.MAX_VALUE) }
                val out = (provider(c).run(Capability.UPSCALE, c.model, AiRequest(photo, scale = by)) { state.running = it } as AiResult.Image).bitmap
                photo.recycle()
                checkUpscaled(w, h, out.width, out.height)
                state.running = "Saving…"
                val name = withContext(Dispatchers.IO) {
                    // a model with another factor (a 4× ComfyUI workflow asked for 2×) is resampled to what was asked
                    val sized = if (out.width == w * by && out.height == h * by) out else Bitmap.createScaledBitmap(out, w * by, h * by, true).also { out.recycle() }
                    p.addUpscaled(sized).also {
                        sized.recycle()
                        ImageIo.copyExif(context.contentResolver, Uri.fromFile(p.original), File(p.dir, it), upright = true)
                    }
                }
                state.edit { it.copy(source = name) }
                state.close()
            }
        }
    }

    // Ask: only the request and a summary of the layers go to the provider (no photo, so no consent sheet); the reply
    // becomes a checklist previewed on the photo
    fun ask() {
        val c = state.ai.choice(Capability.EDIT_COMMAND, phone) ?: return
        val text = state.askText.trim()
        val doc = state.doc
        val target = state.askTarget
        runJob(c, "Asking…", "ask") {
            val reply = provider(c).run(Capability.EDIT_COMMAND, c.model, AiRequest(prompt = EditOps.message(text, doc, target))) { state.running = it } as AiResult.Text
            val parsed = EditOps.parse(reply.text, doc, target)
            if (parsed.ops.isEmpty()) throw AiError(parsed.notes.firstOrNull() ?: "the model suggested no changes")
            state.askOff = emptySet()
            state.asked = parsed
        }
    }

    var exportScale by remember { mutableFloatStateOf(1f) }
    fun export(dst: android.net.Uri?, format: ImageIo.Format) {
        val src = state.source
        if (dst == null || src == null) return
        val layers = state.doc.rendered()
        val crop = state.doc.crop
        val scale = exportScale
        val deband = state.doc.deband
        scope.launch {
            exporting = 0f
            val subjects = state.subjects
            val depth = state.depth
            val r = runCatching {
                withContext(Dispatchers.IO) { ImageIo.export(context.contentResolver, src, dst, context.cacheDir, layers, deband, format, subjects, depth, crop, scale) { exporting = it } }
            }
            exporting = null
            r.exceptionOrNull()?.let { android.util.Log.e("Bloom", "export failed", it) }
            snackbar.showSnackbar(
                r.fold({ "Saved" }, { if (it is OutOfMemoryError) "Not enough memory to export. Pick a smaller Size in the Export menu" else "Export failed: ${it.message ?: it}" }),
            )
        }
    }
    val savers = ImageIo.Format.entries.associateWith { f -> rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { export(it, f) } }
    val openPicker = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    fun toggle(l: Layer) {
        state.edit { it.replace(l.copy(visible = !l.visible)) }
        if (toggleToasts++ < 3) scope.launch {
            if (snackbar.showSnackbar("${l.title} layer ${if (l.visible) "hidden" else "shown"}", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                // only this layer's visibility: other edits may have come since
                state.doc.layers.firstOrNull { it.id == l.id }?.let { cur -> state.edit { it.replace(cur.copy(visible = l.visible)) } }
            }
        }
    }

    var keepAsk by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Project.Recent?>(null) }
    // Closing asks only when this visit changed something; an open tool's changes are discarded first (asking).
    val closePhoto = {
        val ask = {
            state.close()
            if (state.history.canUndo && state.doc.edited) keepAsk = true else closeProject(keep = true)
        }
        if (state.dirty) confirm = "Discard changes?" to ask else ask()
    }
    val leaveTool = { if (state.dirty) confirm = "Discard changes?" to state::close else state.close() }
    // System back: mask mode returns to its tool, a tool closes (asking first if changed), then the photo closes (asking first).
    BackHandler(state.source != null) {
        when {
            confirm != null -> confirm = null
            state.bubble != null -> state.bubble = null
            state.draft != null && state.maskMode -> state.maskMode = false
            state.draft != null || state.crop != null -> leaveTool()
            else -> closePhoto()
        }
    }

    // the selected mask step, only when it has handles on the photo
    val sel by remember { derivedStateOf { state.stepIndex?.let { state.draft?.mask?.steps?.getOrNull(it) }?.takeIf { state.maskMode && (it is GradientStep || it is SubjectStep) } } }
    val focus by remember { derivedStateOf { (state.draft?.effect as? DetailEffect)?.detail?.takeIf { lensOpen && !state.maskMode }?.let { it.focusX to it.focusY } } }
    val pending by remember { derivedStateOf { state.draft?.takeIf { state.editingId == null }?.kind } }
    val noRipple = remember { MutableInteractionSource() }

    Column(
        Modifier.fillMaxSize().background(Bloom.Canvas).pointerInput(Unit) {
            // One gesture = one tool undo step. Posted: the photo view handles the touch after this pass.
            awaitPointerEventScope {
                while (true) if (awaitPointerEvent(PointerEventPass.Final).changes.none { it.pressed }) photoView.post(state::commitStep)
            }
        },
    ) {
        TopBar(
            state, exporting, exportScale, { exportScale = it },
            onBack = closePhoto,
            onExport = { f -> savers.getValue(f).launch("bloom_${System.currentTimeMillis()}.${f.ext}") },
        )
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { canvasTop = it.positionInRoot().y; canvasBottom = canvasTop + it.size.height }) {
            AndroidView({ photoView }, Modifier.fillMaxSize().testTag("photo"))
            when (val s = sel) {
                is GradientStep -> if (s.visible) GradientHandles(s, photoView, transformTick)
                is SubjectStep -> Pins(s, photoView, transformTick)
                else -> Unit
            }
            focus?.let { (x, y) -> FocusMark(x, y, photoView, transformTick) }
            if (cropOpen) CropOverlay(state, photoView, transformTick)
            state.ring?.let { (size, soft) -> BrushRing(size, soft / 100f, photoView) }
            val preview = state.preview
            if (state.source == null || preview == null) {
                // opaque, so a closed photo doesn't show through
                Box(Modifier.fillMaxSize().background(Bloom.Canvas)) {
                    EmptyState(
                        recents, openPicker, { pickFile.launch(arrayOf("image/*", "application/octet-stream")) },
                        onRecent = { openProject(it.dir) }, onDelete = { deleting = it }, modifier = Modifier.align(Alignment.Center),
                    )
                    IconButton({ settings = true }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Settings, "Settings", tint = Bloom.On) }
                }
            } else {
                viewport?.let { v ->
                    val shownCrop = state.crop ?: state.doc.crop
                    val full = state.crop != null
                    val thumb = remember(preview, shownCrop, full) { if (shownCrop.isIdentity) preview else cropThumb(preview, shownCrop, full) }
                    Navigator(
                        thumb, v, photoView::centerOn,
                        Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = with(density) { chromePx.toDp() } + 8.dp),
                    )
                }
                Chrome(
                    state, aiModes, aiMode, phone,
                    // The chrome floats over the photo. Only the bottom bar, which never changes height, reserves space,
                    // so rows appearing above it never move the photo.
                    onBarTop = { top -> photoView.setBottomInset((canvasBottom - top).roundToInt()) },
                    onDiscard = leaveTool,
                    onRun = {
                        when (aiMode) {
                            Capability.ERASE, Capability.GEN_FILL -> fill(aiMode)
                            Capability.UPSCALE -> upscale()
                            Capability.EDIT_COMMAND -> if (state.asked == null) ask() else state.applyAsk()
                            else -> Unit
                        }
                    },
                    onSettings = { settings = true },
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                        chromePx = it.height
                    },
                )
                state.bubble?.let { (id, y) ->
                    val doc = state.doc
                    val l = doc.layers.firstOrNull { it.id == id }
                    val g = doc.groups.firstOrNull { it.id == id }
                    if (l == null && g == null && id != PHOTO_ID) return@let
                    // tap outside closes the bubble and does nothing else; screen readers close it with Back instead of a screen-sized button
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {}.clickable(noRipple, null) { state.bubble = null })
                    val at = Modifier.align(Alignment.TopEnd).padding(end = 56.dp)
                        .offset { IntOffset(0, (y - canvasTop - 40.dp.toPx()).roundToInt().coerceAtLeast(0)) }
                    val opacity = state.opacityPreview?.takeIf { it.first == id }?.second
                    if (g != null) {
                        GroupBubble(
                            opacity?.let { g.copy(opacity = it) } ?: g,
                            onRename = { name -> state.edit { it.replace(g.copy(name = name)) } },
                            onVisible = { state.edit { it.replace(g.copy(visible = !g.visible)) } },
                            onOpacity = { state.opacityPreview = id to it },
                            onOpacityDone = {
                                state.opacityPreview?.let { (_, o) -> state.edit { it.replace(g.copy(opacity = o)) } }
                                state.opacityPreview = null
                            },
                            canUp = doc.stepGroup(id, true) != doc,
                            canDown = doc.stepGroup(id, false) != doc,
                            onStep = { up -> state.edit { it.stepGroup(id, up) } },
                            onCollapse = { state.edit { it.replace(g.copy(collapsed = !g.collapsed)) } },
                            onUngroup = { state.edit { it.ungroup(id) }; state.bubble = null },
                            modifier = at,
                        )
                        return@let
                    }
                    if (l == null) {
                        PhotoBubble(
                            state.doc.deband, deep = preview.config == Bitmap.Config.RGBA_F16,
                            onDeband = { on -> state.edit { it.copy(deband = on) } },
                            modifier = at,
                        )
                        return@let
                    }
                    LayerBubble(
                        opacity?.let { l.copy(opacity = it) } ?: l,
                        onRename = { name -> state.edit { it.replace(l.copy(name = name)) } },
                        onVisible = { toggle(l) },
                        onOpacity = { state.opacityPreview = id to it },
                        onOpacityDone = {
                            state.opacityPreview?.let { (_, o) -> state.edit { d -> d.replace(l.copy(opacity = o)) } }
                            state.opacityPreview = null
                        },
                        onEdit = { state.openTool(edit = l) },
                        // Refine with Ask: the AI tool's Ask, limited to this layer
                        onAsk = if (Capability.EDIT_COMMAND in aiModes) ({
                            state.openTool(RetouchEffect())
                            state.aiMode = Capability.EDIT_COMMAND
                            state.askTarget = id
                        }) else null,
                        onEditMask = { state.openTool(edit = l, mask = true) },
                        onBlend = { b -> state.edit { d -> d.replace(l.copy(blend = b)) } },
                        onDelete = { state.edit { it.remove(id) }; state.bubble = null },
                        canUp = doc.step(id, true) != doc,
                        canDown = doc.step(id, false) != doc,
                        onStep = { up -> state.edit { it.step(id, up) } },
                        grouped = l.group != null,
                        canGroup = doc.groupWithBelow(id, 0) != doc,
                        onGroup = { l.group?.let { g -> state.edit { it.ungroup(g) } } ?: state.edit { it.groupWithBelow(id, state.newId()) } },
                        modifier = at,
                    )
                }
                // after the bubble's tap-outside layer, so the rail still scrolls, swipes and drags while a bubble is open
                LayerRail(
                    doc = state.doc,
                    pending = pending,
                    activeId = state.bubble?.first ?: state.editingId,
                    preview = preview,
                    layersHidden = state.layersHidden,
                    onHideLayers = { state.layersHidden = !state.layersHidden },
                    onTap = { id, y -> state.bubble = if (state.bubble?.first == id) null else id to y },
                    onActiveY = { y -> state.bubble = state.bubble?.let { (id, _) -> id to y } },
                    onToggle = { id ->
                        val g = state.doc.groups.firstOrNull { it.id == id }
                        if (g != null) state.edit { it.replace(g.copy(visible = !g.visible)) } else state.doc.layers.firstOrNull { it.id == id }?.let(::toggle)
                    },
                    // one step per row dragged, from the document as it is at the drop
                    onMove = { id, rows ->
                        state.bubble = null
                        val group = state.doc.groups.any { it.id == id }
                        state.edit { d -> (1..abs(rows)).fold(d) { acc, _ -> if (group) acc.stepGroup(id, rows > 0) else acc.step(id, rows > 0) } }
                    },
                    onPhotoTap = { y -> state.bubble = if (state.bubble?.first == PHOTO_ID) null else PHOTO_ID to y },
                    modifier = Modifier.align(Alignment.TopEnd).padding(bottom = with(density) { chromePx.toDp() } + 8.dp),
                )
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
    if (keepAsk) AlertDialog(
        onDismissRequest = { keepAsk = false },
        text = { Text(if (state.opened == null) "Keep this project? Discard deletes it." else "Keep your changes? Discard takes the project back to how it was when you opened it.") },
        confirmButton = { TextButton({ keepAsk = false; closeProject(keep = true) }) { Text("Keep project") } },
        dismissButton = {
            Row {
                TextButton({ keepAsk = false }) { Text("Cancel") }
                TextButton({ keepAsk = false; closeProject(keep = false) }) { Text("Discard") }
            }
        },
        containerColor = Bloom.SurfaceHigh,
    )
    consent?.let { r ->
        val p = state.ai.providers.firstOrNull { it.id == r.choice.provider }
        val host = p?.let { if (it.type == ProviderType.Fal) "fal.ai" else runCatching { java.net.URI(it.url).host }.getOrNull() ?: it.url }.orEmpty()
        ConsentSheet(
            state.ai, r.choice, host, r.what, state.ai.options(r.cap, phone) - r.choice,
            onSend = { keep ->
                consent = null
                if (keep) state.ai = state.ai.copy(consented = state.ai.consented + r.choice.provider)
                r.go(r.choice)
            },
            onOther = { o ->
                consent = null
                state.ai = state.ai.copy(choices = state.ai.choices + (r.cap to o))
                if (state.ai.needsConsent(o.provider)) consent = Send(r.cap, o, r.what, r.go) else r.go(o)
            },
            onDismiss = { consent = null },
        )
    }
    if (settings) SettingsScreen(state.ai, phone, { state.ai = it }, { settings = false })
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text("Delete ${r.name}? Its edits can't be brought back. The photo in your gallery stays.") },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch { withContext(Dispatchers.IO) { r.dir.deleteRecursively() }; recentsTick++ }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
            containerColor = Bloom.SurfaceHigh,
        )
    }
}

@Composable
private fun TopBar(state: EditorState, exporting: Float?, scale: Float, onScale: (Float) -> Unit, onBack: () -> Unit, onExport: (ImageIo.Format) -> Unit) {
    val busy = exporting != null
    val open = state.source != null
    val idle = state.draft == null && state.crop == null // inside a tool, undo/redo step through the tool's own gestures
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Back, "Close photo", open, onBack)
        BarIcon(Icons.Undo, "Undo", open && if (idle) state.history.canUndo else state.steps.canUndo) {
            if (idle) state.history = state.history.undo() else state.undoStep()
        }
        BarIcon(Icons.Redo, "Redo", open && if (idle) state.history.canRedo else state.steps.canRedo) {
            if (idle) state.history = state.history.redo() else state.redoStep()
        }
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
                // size cycles in place; the menu stays open
                DropdownMenuItem({ Text("Size: ${(scale * 100).toInt()}%", color = Bloom.OnVariant) }, { onScale(if (scale == 1f) 0.5f else if (scale == 0.5f) 0.25f else 1f) })
                for (f in ImageIo.Format.entries) if (f.available) DropdownMenuItem(
                    {
                        Text(
                            when (f) {
                                ImageIo.Format.JPEG -> "JPEG"
                                ImageIo.Format.WEBP -> "WebP"
                                ImageIo.Format.HEIC -> "HEIC"
                                ImageIo.Format.AVIF -> "AVIF"
                                ImageIo.Format.PNG -> "16-bit PNG (larger, for further editing)"
                            },
                            color = Bloom.On,
                        )
                    },
                    { menu = false; onExport(f) },
                )
            }
        }
    }
}

@Composable
private fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled) { Icon(icon, desc, tint = if (enabled) Bloom.On else Bloom.Outline) }
}

/** Home: the first-run text, or the grid of recent projects (tap opens, long-press deletes); then the open buttons. */
@Composable
private fun EmptyState(
    recents: List<Project.Recent>, onOpen: () -> Unit, onOpenFile: () -> Unit,
    onRecent: (Project.Recent) -> Unit, onDelete: (Project.Recent) -> Unit, modifier: Modifier,
) {
    if (recents.isNotEmpty()) Column(modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bloom", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Bloom.On)
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(recents, key = { it.dir.path }) { r ->
                val thumb = remember(r) { r.thumb?.asImageBitmap() }
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Bloom.SurfaceHigh)
                        .combinedClickable(onClickLabel = "Open", onLongClickLabel = "Delete", onLongClick = { onDelete(r) }) { onRecent(r) }
                        .semantics { contentDescription = r.name },
                ) { thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            }
        }
        OpenButtons(onOpen, onOpenFile)
    } else Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Bloom", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Bloom.On)
        Text("Edit in layers. Everything runs on your phone.", Modifier.padding(top = 8.dp, bottom = 24.dp), color = Bloom.OnVariant)
        OpenButtons(onOpen, onOpenFile)
    }
}

@Composable
private fun OpenButtons(onOpen: () -> Unit, onOpenFile: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 6.dp)).background(Bloom.Pollen)
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

/** A job waiting on the consent sheet: [what] goes to [choice] for [cap]; [go] runs it with the choice made. */
private class Send(val cap: Capability, val choice: Choice, val what: String, val go: (Choice) -> Unit)

/** Floating bottom chrome. The bar holds what you edit; the box above holds its settings. */
@Composable
private fun Chrome(
    state: EditorState, aiModes: List<Capability>, aiMode: Capability, phone: OnDevice,
    onBarTop: (Float) -> Unit, onDiscard: () -> Unit, onRun: () -> Unit, onSettings: () -> Unit, modifier: Modifier,
) {
    val float = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val d = state.draft
        val box = Modifier.fillMaxWidth().background(Bloom.Surface, float).border(1.dp, Bloom.Outline, float)
        val c = state.crop
        when {
            c != null -> state.preview?.let { CropRows(state, c, it.width.toFloat() / it.height, box) }
            d == null -> Unit
            state.maskMode -> MaskRows(state, box)
            else -> when (val e = d.effect) {
                is ColorEffect -> ColorRows(state, d, e, box)
                is DetailEffect -> DetailRows(state, d, e, box)
                is WarpEffect -> LiquifyRows(state, box)
                is RetouchEffect -> AiRows(state, aiMode, box)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned { onBarTop(it.positionInRoot().y) }
                .background(Bloom.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Bloom.Outline, RoundedCornerShape(20.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                c != null -> CropBar(state, c, onDiscard)
                d == null -> {
                    ToolButton("Color", Icons.Adjust, Modifier.weight(1f)) { state.openTool() }
                    ToolButton("Detail", Icons.Detail, Modifier.weight(1f)) { state.openTool(DetailEffect()) }
                    ToolButton("Liquify", Icons.Liquify, Modifier.weight(1f)) { state.openTool(WarpEffect()) }
                    ToolButton("Crop", Icons.Crop, Modifier.weight(1f)) { state.openCrop() }
                    ToolButton("AI", Icons.Sparkle, Modifier.weight(1f)) { state.openTool(RetouchEffect()) }
                }
                state.maskMode -> MaskBar(state)
                else -> when (val e = d.effect) {
                    is ColorEffect -> ColorBar(state, d, e, onDiscard)
                    is DetailEffect -> DetailBar(state, d, onDiscard)
                    is WarpEffect -> LiquifyBar(state, d, onDiscard)
                    is RetouchEffect -> AiBar(
                        state, d, aiModes, aiMode, state.ai.choice(aiMode, phone), state.ai.options(aiMode, phone), onDiscard, onRun,
                        onPick = { state.ai = state.ai.copy(choices = state.ai.choices + (aiMode to it)) }, onSettings = onSettings,
                    )
                }
            }
        }
    }
}

/** The row above the Color bar: the selected parameter's slider and Reset, or the Curves graph. */
@Composable
private fun ColorRows(state: EditorState, d: Layer, c: ColorEffect, box: Modifier) {
    val p = state.param
    if (p == null) {
        CurvesRow(state, box)
        return
    }
    Row(box.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, Modifier.widthIn(min = 92.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
        Slider(
            value = c[p], onValueChange = { state.draft = d.copy(effect = c.with(p, snap(p, it))) },
            valueRange = -100f..100f, modifier = Modifier.weight(1f).sliderLabel(p.name, shown(p, c[p]) + if (p == ColorParam.Exposure) " EV" else ""), colors = sliderColors(),
        )
        Text(shown(p, c[p]), Modifier.widthIn(min = 44.dp), fontSize = 14.sp, color = Bloom.On)
        IconButton({ state.draft = d.copy(effect = c.with(p, 0f)) }) { Icon(Icons.Reset, "Reset ${p.name}", tint = Bloom.On) }
    }
}

/** The Color bar: back, the parameters and Curves, mask, apply. */
@Composable
private fun RowScope.ColorBar(state: EditorState, d: Layer, c: ColorEffect, onDiscard: () -> Unit) {
    ToolIcon(Icons.Adjust, "Color. Back to tools", onDiscard)
    Row(Modifier.weight(1f).scrollFade(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ColorParam.entries.forEach { p ->
            // Curves sits with the tone controls, before color
            if (p == ColorParam.Temp) ParamChip(Icons.Curves, "Curves" + if (c.curves.active) ", edited" else "", c.curves.active, state.param == null) { state.param = null }
            ParamChip(p.icon, "${p.name} ${shown(p, c[p])}" + if (p == ColorParam.Exposure) " stops" else "", c[p] != 0f, p == state.param) { state.param = p }
        }
    }
    MaskAndApply(state, d)
}

/** The Detail bar: back, Blur or Sharpen, mask, apply. */
@Composable
private fun RowScope.DetailBar(state: EditorState, d: Layer, onDiscard: () -> Unit) {
    ToolIcon(Icons.Detail, "Detail. Back to tools", onDiscard)
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Blur and Sharpen are separate layer kinds; an applied layer keeps its kind
        for (k in listOf(LayerKind.Blur, LayerKind.Sharpen)) {
            ParamChip(k.icon, k.label, false, d.kind == k) { if (state.editingId == null && d.kind != k) state.draft = d.copy(effect = DetailEffect(sharpen = k == LayerKind.Sharpen)) }
        }
    }
    MaskAndApply(state, d)
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    // icon only, like every other bar; the name is for screen readers
    Box(
        modifier.height(58.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(26.dp), tint = Bloom.On) }
}

/** Left slot of an open tool's bar: the tool's icon; tapping goes back to the tool list. */
@Composable
internal fun ToolIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp, 48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp, 30.dp).background(Bloom.PollenContainer, Bloom.Petal), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Bloom.OnPollenContainer)
        }
    }
}

/** One Detail slider: its name, how to read and write it on [Detail], and its range. */
private class DetailParam(val name: String, val range: ClosedFloatingPointRange<Float>, val get: (Detail) -> Float, val set: (Detail, Float) -> Detail)

private val amount = DetailParam("Amount", 0f..100f, { it.amount }, { d, v -> d.copy(amount = v) })
private val sharpenParams = listOf(
    amount,
    DetailParam("Radius", 0f..100f, { it.radius }, { d, v -> d.copy(radius = v) }),
    DetailParam("Clarity", -100f..100f, { it.clarity }, { d, v -> d.copy(clarity = v) }),
)
private val BlurType.icon get() = when (this) {
    BlurType.Gaussian -> Icons.Blur
    BlurType.Lens -> Icons.Aperture
    BlurType.Motion -> Icons.Motion
    BlurType.Zoom -> Icons.Zoom
}

private val motionParams = listOf(amount, DetailParam("Angle", 0f..180f, { it.angle }, { d, v -> d.copy(angle = v) }))
private val gaussParams = listOf(amount)
private val lensParams = listOf(
    amount,
    DetailParam("Range", 0f..100f, { it.range }, { d, v -> d.copy(range = v) }),
    DetailParam("Highlights", 0f..100f, { it.bloom }, { d, v -> d.copy(bloom = v) }),
)

/**
 * One box above the Detail bar, like Liquify's: Amount, and More for the rest (Blur: the type, Gaussian, Lens,
 * Motion or Zoom, then its other sliders; Sharpen: Radius and Clarity).
 */
@Composable
private fun DetailRows(state: EditorState, d: Layer, e: DetailEffect, box: Modifier) {
    val det = e.detail
    val params = when {
        e.sharpen -> sharpenParams
        det.lens -> lensParams
        det.type == BlurType.Motion -> motionParams
        else -> gaussParams
    }
    val set = { p: DetailParam, v: Float -> state.draft = d.copy(effect = e.copy(detail = p.set(det, v))) }
    ToolBox(state.detailMore, { state.detailMore = !state.detailMore }, box, moreRows = {
        if (!e.sharpen) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Type", Modifier.widthIn(min = 84.dp).padding(start = 16.dp), fontSize = 12.sp, color = Bloom.OnVariant)
            for (t in BlurType.entries) ParamChip(t.icon, "${t.name} blur", false, det.type == t) { state.draft = d.copy(effect = e.copy(detail = det.copy(type = t))) }
        }
        for (p in params.drop(1)) SliderRow(p.name, p.get(det), p.range) { set(p, it) }
    }) {
        val p = params[0]
        val status = state.depthStatus?.takeIf { det.lens && !e.sharpen && state.depth == null }
        if (status != null) StatusRow(status, { state.aiRetry++ }, start = 16.dp)
        // reads as "Gaussian blur", "Lens blur"…
        else if (e.sharpen) MainSlider("Sharpen", p.name, p.get(det), p.range) { set(p, it) }
        else MainSlider(det.type.name, "Blur", p.get(det), p.range) { set(p, it) }
    }
}

/** A tool's main slider: on the left what is selected and under it what the slider sets, then the slider and its value. */
@Composable
internal fun RowScope.MainSlider(title: String, sub: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    TwoLineLabel(title, sub)
    Slider(value, { onChange(it.roundToInt().toFloat()) }, Modifier.weight(1f).sliderLabel("$title $sub", "${value.roundToInt()}"), valueRange = range, colors = sliderColors())
    Text("${value.roundToInt()}", Modifier.widthIn(min = 36.dp), fontSize = 14.sp, color = Bloom.On)
}

/** A main row's label: what is selected ("Erase"), and under it what the row sets ("Size"). */
@Composable
internal fun TwoLineLabel(title: String, sub: String) {
    Column(Modifier.widthIn(min = 84.dp).padding(start = 16.dp)) {
        // the same style as every slider label (Color, mask); the second line a step quieter
        Text(title, fontSize = 12.sp, lineHeight = 14.sp, color = Bloom.OnVariant)
        Text(sub, fontSize = 11.sp, lineHeight = 13.sp, color = Bloom.OnVariant.copy(alpha = 0.7f))
    }
}

/** Lens blur's focus point or Zoom blur's centre on the photo, at ([u], [v]): tap or drag the photo to move it. */
@Composable
private fun FocusMark(u: Float, v: Float, photoView: PhotoView, transformTick: Int) {
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Focus point. Tap the photo to focus elsewhere" }) {
        transformTick // redraw on zoom or pan
        val (x, y) = photoView.toView(u, v)
        val c = Offset(x, y)
        val r = 18.dp.toPx()
        drawCircle(Color.Black, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
        drawCircle(Color.White, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(Bloom.Pollen, 3.dp.toPx(), c)
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
internal fun ParamChip(icon: ImageVector, desc: String, changed: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
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

// runCatching catches OutOfMemoryError too; its own message is a byte count
private fun reason(e: Throwable) = when (e) {
    is OutOfMemoryError -> "not enough memory. Close other apps and try again"
    is java.net.SocketTimeoutException -> "the server took too long to answer"
    is java.net.ConnectException, is java.net.UnknownHostException -> "can't reach the server. Check the address in AI settings"
    else -> e.message
}

internal fun signed(v: Float): String {
    val i = v.roundToInt()
    return when {
        i > 0 -> "+$i"
        i < 0 -> "−${abs(i)}"
        else -> "0"
    }
}
