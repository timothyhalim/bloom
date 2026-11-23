package dev.bloom.editor.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.sqrt

/** Color layer parameters, in shader uniform order. */
enum class ColorParam { Exposure, Contrast, Highlights, Shadows, Temp, Tint, Saturation, Vibrance }

/** Layer blend modes, in shader order. */
enum class Blend(val label: String) { Normal("Normal"), Multiply("Multiply"), Screen("Screen"), Overlay("Overlay"), SoftLight("Soft light") }

/**
 * One brush stroke. [points] are dab centres as x,y pairs in upright image coordinates (0..1, top-left origin).
 * [radius] is a fraction of the image height; [softness] and [flow] are 0..1.
 */
@Serializable
data class Stroke(val points: List<Float>, val radius: Float, val softness: Float, val flow: Float) {
    /** Adds dabs from the last one toward (x, y), a quarter radius apart; this stroke itself when none fit. */
    fun lineTo(x: Float, y: Float, aspect: Float) =
        points.dabsTo(x, y, aspect, radius * 0.25f).let { if (it === points) this else copy(points = it) }
}

/**
 * These x,y dab centres plus dabs from the last one toward (x, y), [step] apart (a fraction of the image height;
 * [aspect] is image width / height). The same list when no dab fits.
 */
fun List<Float>.dabsTo(x: Float, y: Float, aspect: Float, step: Float): List<Float> {
    if (isEmpty()) return listOf(x, y)
    val lx = this[size - 2]
    val ly = this[size - 1]
    val d = hypot((x - lx) * aspect, y - ly)
    val n = (d / step).toInt()
    if (n == 0) return this
    val out = ArrayList<Float>(size + n * 2).apply { addAll(this@dabsTo) }
    for (i in 1..n) {
        val t = i * step / d
        out += lx + (x - lx) * t
        out += ly + (y - ly) * t
    }
    return out
}

/**
 * One mask step. Add: mask = max(mask, step). Remove: mask = mask × (1 − step).
 * [feather] (0..1) blurs the step's edge before it is combined; 1 = a radius of 3% of the image height.
 */
@Serializable
sealed interface MaskStep {
    val add: Boolean
    val visible: Boolean
    val feather: Float
}

@Serializable @SerialName("brush")
data class BrushStep(
    val strokes: List<Stroke> = emptyList(),
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep

/** Linear: full at (x0, y0), fading to none at (x1, y1). Radial: centre (x0, y0), edge at (x1, y1). Upright image coordinates. */
@Serializable @SerialName("gradient")
data class GradientStep(
    val radial: Boolean, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep

/**
 * Selects colors of the layers below close to any of [samples] (r, g, b triples, sRGB 0..1, at most [MAX_SAMPLES]).
 * [range] widens the match, [smooth] softens its edge; both 0..1.
 */
@Serializable @SerialName("color")
data class ColorStep(
    val samples: List<Float> = emptyList(), val range: Float = 0.3f, val smooth: Float = 0.5f,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep {
    companion object {
        const val MAX_SAMPLES = 4
    }
}

/**
 * Selects by brightness of the layers below, or with [depth] by distance (the photo's depth map, 0 = far, 1 = near):
 * rises from [a] to [b], full to [c], falls to none at [d] (0..1, a ≤ b ≤ c ≤ d).
 */
@Serializable @SerialName("luminance")
data class LuminanceStep(
    val a: Float, val b: Float, val c: Float, val d: Float, val depth: Boolean = false,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep {
    companion object {
        val Shadows = LuminanceStep(0f, 0f, 0.25f, 0.4f)
        val Midtones = LuminanceStep(0.25f, 0.4f, 0.6f, 0.75f)
        val Highlights = LuminanceStep(0.6f, 0.75f, 1f, 1f)
        val Far = LuminanceStep(0f, 0f, 0.25f, 0.4f, depth = true)
        val Middle = LuminanceStep(0.25f, 0.4f, 0.6f, 0.75f, depth = true)
        val Near = LuminanceStep(0.6f, 0.75f, 1f, 1f, depth = true)
    }
}

/**
 * The main subject found by the on-device model ([background] = everything else). With [pins] (x, y in 0..1 over the
 * upright photo, label 1 = include / 0 = exclude, as triples) it is the object the pins point at instead.
 * The mask itself is not stored here: it is computed once per photo and pin set, and cached by the editor.
 */
@Serializable @SerialName("subject")
data class SubjectStep(
    val background: Boolean = false,
    val pins: List<Float> = emptyList(),
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep

/**
 * The people in the photo, as the on-device matting model sees them (soft, with hair). Like [SubjectStep] the matte isn't
 * stored here: it is computed once per photo and cached by the editor with the subject masks, under [KEY].
 */
@Serializable @SerialName("person")
data class PersonStep(
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep {
    companion object {
        /** The person matte's key among the subject masks; pins come in triples, so no pin set equals it. */
        val KEY = listOf(-1f)
    }
}

fun MaskStep.with(add: Boolean = this.add, visible: Boolean = this.visible, feather: Float = this.feather): MaskStep = when (this) {
    is BrushStep -> copy(add = add, visible = visible, feather = feather)
    is GradientStep -> copy(add = add, visible = visible, feather = feather)
    is ColorStep -> copy(add = add, visible = visible, feather = feather)
    is LuminanceStep -> copy(add = add, visible = visible, feather = feather)
    is SubjectStep -> copy(add = add, visible = visible, feather = feather)
    is PersonStep -> copy(add = add, visible = visible, feather = feather)
}

/** Ordered steps, evaluated first to last from empty, then [invert]. */
@Serializable
data class Mask(val steps: List<MaskStep> = emptyList(), val invert: Boolean = false) {
    /** A mask with no visible steps leaves the whole layer on. */
    val active: Boolean get() = steps.any { it.visible }

    /** Color, Luminance, Subject and Person steps sample the layers below, so the mask changes when they do. */
    val readsBelow: Boolean get() = steps.any { it.visible && (it is ColorStep || it is LuminanceStep || it is SubjectStep || it is PersonStep) }

    /** Largest feather of a visible step (0..1); export tiles need that much overlap. */
    val maxFeather: Float get() = steps.filter { it.visible }.maxOfOrNull { it.feather } ?: 0f

    /** Moves step [from] to index [to], keeping the others in order. */
    fun move(from: Int, to: Int): Mask = copy(steps = steps.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) })
}

/**
 * Tone curves on the sRGB-encoded value: [rgb] first, then each channel's own. Each is control points as x,y pairs
 * in 0..1, sorted by x, at least two.
 */
@Serializable
data class Curves(
    val rgb: List<Float> = LINE,
    val r: List<Float> = LINE,
    val g: List<Float> = LINE,
    val b: List<Float> = LINE,
) {
    val active: Boolean get() = this != Curves()
    operator fun get(channel: Int) = listOf(rgb, r, g, b)[channel]
    fun with(channel: Int, points: List<Float>) = when (channel) {
        0 -> copy(rgb = points)
        1 -> copy(r = points)
        2 -> copy(g = points)
        else -> copy(b = points)
    }

    /** [n] RGBA entries, entry i is the output for input i / (n - 1); alpha is unused. */
    fun lut(n: Int = 1024): FloatArray {
        val out = FloatArray(n * 4)
        for (i in 0 until n) {
            val v = curveAt(rgb, i / (n - 1f))
            out[i * 4] = curveAt(r, v)
            out[i * 4 + 1] = curveAt(g, v)
            out[i * 4 + 2] = curveAt(b, v)
            out[i * 4 + 3] = 1f
        }
        return out
    }

    companion object {
        val LINE = listOf(0f, 0f, 1f, 1f)
        const val MAX_POINTS = 12
    }
}

/**
 * Monotone cubic (Fritsch-Carlson) through [p] (x,y pairs): no overshoot between points, flat past the ends.
 * Result is clamped to 0..1.
 */
fun curveAt(p: List<Float>, x: Float): Float {
    val n = p.size / 2
    if (x <= p[0]) return p[1].coerceIn(0f, 1f)
    if (x >= p[2 * n - 2]) return p[2 * n - 1].coerceIn(0f, 1f)
    var k = 0
    while (p[2 * k + 2] < x) k++
    val m = tangents(p)
    val h = p[2 * k + 2] - p[2 * k]
    val t = (x - p[2 * k]) / h
    val t2 = t * t
    val t3 = t2 * t
    val y = (2 * t3 - 3 * t2 + 1) * p[2 * k + 1] + (t3 - 2 * t2 + t) * h * m[k] +
        (-2 * t3 + 3 * t2) * p[2 * k + 3] + (t3 - t2) * h * m[k + 1]
    return y.coerceIn(0f, 1f)
}

private fun tangents(p: List<Float>): FloatArray {
    val n = p.size / 2
    val d = FloatArray(n - 1) { (p[2 * it + 3] - p[2 * it + 1]) / (p[2 * it + 2] - p[2 * it]) }
    val m = FloatArray(n) { i ->
        when (i) {
            0 -> d[0]
            n - 1 -> d[n - 2]
            else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2
        }
    }
    for (k in 0 until n - 1) {
        if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
        val a = m[k] / d[k]
        val b = m[k + 1] / d[k]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / sqrt(s)
            m[k] = t * a * d[k]
            m[k + 1] = t * b * d[k]
        }
    }
    return m
}

/** What a layer does, derived from its [Effect]. The shader reads the ordinal (uKind), so don't reorder. */
enum class LayerKind { Color, Blur, Sharpen, Liquify, Retouch }

/** A Retouch layer's filled pixels (AI eraser) and where they go: upright 0..1, right / bottom exclusive. */
class Patch(val left: Float, val top: Float, val right: Float, val bottom: Float, val pixels: android.graphics.Bitmap)

/**
 * Blur and Sharpen settings, 0..100 unless noted. Blur: [amount] is the radius (Gaussian: 100 = sigma 2% of the image
 * height; [lens]: 100 = a 4% disc). Lens blur follows the photo's depth: it keeps the depth at ([focusX], [focusY])
 * (upright 0..1) sharp, plus [range] around it; [bloom] makes highlights into bright discs. Sharpen: [amount] is the strength, [radius] 0 = fine to 100 = coarse, [clarity]
 * -100..100 is midtone local contrast.
 */
@Serializable
data class Detail(
    val type: BlurType = BlurType.Gaussian, val amount: Float = 0f, val radius: Float = 30f, val clarity: Float = 0f,
    val focusX: Float = 0.5f, val focusY: Float = 0.5f, val range: Float = 10f, val bloom: Float = 30f,
    val angle: Float = 0f,
) {
    val lens: Boolean get() = type == BlurType.Lens

    /** Lens and Zoom blur act around a point on the photo (focus / centre), set by tapping it. */
    val hasPoint: Boolean get() = type == BlurType.Lens || type == BlurType.Zoom
}

/**
 * Blur kinds. Motion streaks along [Detail.angle] (degrees, 0 = across); Zoom streaks toward ([Detail.focusX],
 * [Detail.focusY]); for both [Detail.amount] 100 = the longest streak.
 */
enum class BlurType { Gaussian, Lens, Motion, Zoom }

/** What a layer does and its settings. */
@Serializable
sealed interface Effect

/** Color [values] are slider units (-100..100), indexed by [ColorParam.ordinal]. */
@Serializable @SerialName("color")
data class ColorEffect(val values: List<Float> = List(ColorParam.entries.size) { 0f }, val curves: Curves = Curves()) : Effect {
    operator fun get(p: ColorParam) = values[p.ordinal]
    fun with(p: ColorParam, v: Float) = copy(values = values.toMutableList().also { it[p.ordinal] = v })
}

/** Blur, or Sharpen when [sharpen]. */
@Serializable @SerialName("detail")
data class DetailEffect(val sharpen: Boolean = false, val detail: Detail = Detail()) : Effect

/** Liquify. */
@Serializable @SerialName("warp")
data class WarpEffect(val strokes: List<WarpStroke> = emptyList()) : Effect

/** AI eraser fill; null until erased. */
@Serializable @SerialName("retouch")
data class RetouchEffect(@Contextual val patch: Patch? = null) : Effect

/** One layer. [name] is the user's name for it, empty if none. [group] is the id of the [Group] it belongs to. */
@Serializable
data class Layer(
    val id: Long,
    val name: String = "",
    val effect: Effect = ColorEffect(),
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val mask: Mask = Mask(),
    val blend: Blend = Blend.Normal,
    val group: Long? = null,
) {
    val kind: LayerKind get() = when (effect) {
        is ColorEffect -> LayerKind.Color
        is DetailEffect -> if (effect.sharpen) LayerKind.Sharpen else LayerKind.Blur
        is WarpEffect -> LayerKind.Liquify
        is RetouchEffect -> LayerKind.Retouch
    }

    /** Whether the layer changes the photo at all (mask and blend aside). */
    val edited: Boolean get() = when (effect) {
        is ColorEffect -> effect.values.any { it != 0f } || effect.curves.active
        is DetailEffect -> effect.detail.amount > 0f || effect.sharpen && effect.detail.clarity != 0f
        is WarpEffect -> effect.strokes.isNotEmpty()
        is RetouchEffect -> effect.patch != null
    }
}

/** Layers that hide, fade and move together. Its ids share the layers' id space. [collapsed] hides its members on the rail. */
@Serializable
data class Group(val id: Long, val name: String = "", val opacity: Float = 1f, val visible: Boolean = true, val collapsed: Boolean = false)

/**
 * Layers bottom to top, the order they render in. [deband] smooths 8-bit banding in the photo itself. [crop] applies after all layers.
 * [source] is the photo file in the project folder these edits apply to: empty for the original, or an upscaled copy.
 * A group's members are always next to each other; every operation here keeps it so, and drops groups left empty.
 */
@Serializable
data class Document(
    val layers: List<Layer> = emptyList(),
    val deband: Boolean = true,
    val crop: Crop = Crop(),
    val groups: List<Group> = emptyList(),
    val source: String = "",
) {
    /** Anything changed from the photo as opened. */
    val edited: Boolean get() = layers.isNotEmpty() || !deband || !crop.isIdentity || source.isNotEmpty()

    /** Layers as they render: a hidden group hides its members, and its opacity scales theirs. */
    // ponytail: group opacity scales each layer, not the group's composite; render the group to its own target if blends look off
    fun rendered(): List<Layer> = layers.map { l ->
        val g = groups.firstOrNull { it.id == l.group }
        if (g == null || g.visible && g.opacity == 1f) l else l.copy(visible = l.visible && g.visible, opacity = l.opacity * g.opacity)
    }

    fun replace(l: Layer) = copy(layers = layers.map { if (it.id == l.id) l else it })
    fun replace(g: Group) = copy(groups = groups.map { if (it.id == g.id) g else it })
    fun remove(id: Long) = copy(layers = layers.filterNot { it.id == id }).pruned()

    /** Moves layer [id] to [toIndex]: inside another group's run it joins that group, away from its own it leaves it. */
    fun move(id: Long, toIndex: Int): Document {
        val l = layers.first { it.id == id }
        val rest = layers.filterNot { it.id == id }
        val at = toIndex.coerceIn(0, rest.size)
        val below = rest.getOrNull(at - 1)?.group
        val above = rest.getOrNull(at)?.group
        val group = if (below == above) below else l.group?.takeIf { it == below || it == above }
        return copy(layers = rest.toMutableList().apply { add(at, l.copy(group = group)) }).pruned()
    }

    /**
     * One step up (toward the top) or down for layer [id]: at its group's edge it leaves the group, next to a group it
     * joins it (or skips it whole when collapsed), otherwise it swaps with its neighbour. This document when there's no step.
     */
    fun step(id: Long, up: Boolean): Document {
        val i = layers.indexOfFirst { it.id == id }
        val l = layers[i]
        val next = layers.getOrNull(if (up) i + 1 else i - 1)
        val g = next?.group
        return when {
            l.group != null && g != l.group -> replace(l.copy(group = null)).pruned()
            next == null -> this
            g == l.group -> move(id, if (up) i + 1 else i - 1)
            groups.none { it.id == g && it.collapsed } -> replace(l.copy(group = g))
            else -> move(id, if (up) layers.indexOfLast { it.group == g } else layers.indexOfFirst { it.group == g })
        }
    }

    /** Swaps group [id]'s run with the layer or group run next to it, up or down. */
    fun stepGroup(id: Long, up: Boolean): Document {
        val first = layers.indexOfFirst { it.group == id }
        val last = layers.indexOfLast { it.group == id }
        val next = layers.getOrNull(if (up) last + 1 else first - 1) ?: return this
        val other = if (next.group == null) listOf(next) else layers.filter { it.group == next.group }
        val run = layers.subList(first, last + 1)
        val rest = layers.filter { it !in run && it !in other }
        val at = if (up) first else first - other.size
        return copy(layers = rest.take(at) + (if (up) other + run else run + other) + rest.drop(at))
    }

    /**
     * Groups layer [id] with the one below it: into a new group [newId] when neither is grouped, else into the group
     * one of them is in. This document when it is the bottom layer or both are grouped.
     */
    fun groupWithBelow(id: Long, newId: Long): Document {
        val i = layers.indexOfFirst { it.id == id }
        val a = layers.getOrNull(i - 1) ?: return this
        val b = layers[i]
        if (a.group != null && b.group != null) return this
        val g = a.group ?: b.group ?: newId
        return copy(
            layers = layers.map { if (it === a || it === b) it.copy(group = g) else it },
            groups = if (groups.any { it.id == g }) groups else groups + Group(g),
        )
    }

    fun ungroup(id: Long) = copy(layers = layers.map { if (it.group == id) it.copy(group = null) else it }, groups = groups.filterNot { it.id == id })

    private fun pruned() = copy(groups = groups.filter { g -> layers.any { it.group == g.id } })
}

/**
 * Undo/redo over immutable snapshots: documents, or an open tool's draft. Snapshots share unchanged layers (and Retouch
 * patch bitmaps), so they are cheap.
 */
data class History<T>(
    val past: List<T> = emptyList(),
    val current: T,
    val future: List<T> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(x: T) = if (x == current) this else History((past + current).takeLast(LIMIT), x)
    fun undo() = if (!canUndo) this else History(past.dropLast(1), past.last(), listOf(current) + future)
    fun redo() = if (!canRedo) this else History(past + current, future.first(), future.drop(1))

    companion object {
        const val LIMIT = 100
    }
}
