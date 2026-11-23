package dev.bloom.editor.model

import kotlin.math.hypot

/** Color layer parameters, in shader uniform order. */
enum class ColorParam { Exposure, Contrast, Highlights, Shadows, Temp, Tint, Saturation, Vibrance }

/** Layer blend modes, in shader order. */
enum class Blend(val label: String) { Normal("Normal"), Multiply("Multiply"), Screen("Screen"), Overlay("Overlay"), SoftLight("Soft light") }

/**
 * One brush stroke. [points] are dab centres as x,y pairs in upright image coordinates (0..1, top-left origin).
 * [radius] is a fraction of the image height; [softness] and [flow] are 0..1.
 */
data class Stroke(val points: List<Float>, val radius: Float, val softness: Float, val flow: Float) {
    /** Adds dabs from the last one toward (x, y), a quarter radius apart. [aspect] is image width / height. */
    fun lineTo(x: Float, y: Float, aspect: Float): Stroke {
        if (points.isEmpty()) return copy(points = listOf(x, y))
        val lx = points[points.size - 2]
        val ly = points[points.size - 1]
        val d = hypot((x - lx) * aspect, y - ly)
        val step = radius * 0.25f
        val n = (d / step).toInt()
        if (n == 0) return this
        val added = ArrayList<Float>(n * 2)
        for (i in 1..n) {
            val t = i * step / d
            added += lx + (x - lx) * t
            added += ly + (y - ly) * t
        }
        return copy(points = points + added)
    }
}

/**
 * One mask step. Add: mask = max(mask, step). Remove: mask = mask × (1 − step).
 * [feather] (0..1) blurs the step's edge before it is combined; 1 = a radius of 3% of the image height.
 */
sealed interface MaskStep {
    val add: Boolean
    val visible: Boolean
    val feather: Float
}

data class BrushStep(
    val strokes: List<Stroke> = emptyList(),
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep

/** Linear: full at (x0, y0), fading to none at (x1, y1). Radial: centre (x0, y0), edge at (x1, y1). Upright image coordinates. */
data class GradientStep(
    val radial: Boolean, val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep

/**
 * Selects colors of the layers below close to any of [samples] (r, g, b triples, sRGB 0..1, at most [MAX_SAMPLES]).
 * [range] widens the match, [smooth] softens its edge; both 0..1.
 */
data class ColorStep(
    val samples: List<Float> = emptyList(), val range: Float = 0.3f, val smooth: Float = 0.5f,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep {
    companion object {
        const val MAX_SAMPLES = 4
    }
}

/** Selects by brightness of the layers below: rises from [a] to [b], full to [c], falls to none at [d] (0..1, a ≤ b ≤ c ≤ d). */
data class LuminanceStep(
    val a: Float, val b: Float, val c: Float, val d: Float,
    override val add: Boolean = true, override val visible: Boolean = true, override val feather: Float = 0f,
) : MaskStep {
    companion object {
        val Shadows = LuminanceStep(0f, 0f, 0.25f, 0.4f)
        val Midtones = LuminanceStep(0.25f, 0.4f, 0.6f, 0.75f)
        val Highlights = LuminanceStep(0.6f, 0.75f, 1f, 1f)
    }
}

fun MaskStep.with(add: Boolean = this.add, visible: Boolean = this.visible, feather: Float = this.feather): MaskStep = when (this) {
    is BrushStep -> copy(add = add, visible = visible, feather = feather)
    is GradientStep -> copy(add = add, visible = visible, feather = feather)
    is ColorStep -> copy(add = add, visible = visible, feather = feather)
    is LuminanceStep -> copy(add = add, visible = visible, feather = feather)
}

/** Ordered steps, evaluated first to last from empty, then [invert]. */
data class Mask(val steps: List<MaskStep> = emptyList(), val invert: Boolean = false) {
    /** A mask with no visible steps leaves the whole layer on. */
    val active: Boolean get() = steps.any { it.visible }

    /** Color and Luminance steps sample the layers below, so the mask changes when they do. */
    val readsBelow: Boolean get() = steps.any { it.visible && (it is ColorStep || it is LuminanceStep) }

    /** Largest feather of a visible step (0..1); export tiles need that much overlap. */
    val maxFeather: Float get() = steps.filter { it.visible }.maxOfOrNull { it.feather } ?: 0f

    /** Moves step [from] to index [to], keeping the others in order. */
    fun move(from: Int, to: Int): Mask = copy(steps = steps.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) })
}

/** One Color layer. Values are slider units (-100..100), indexed by [ColorParam.ordinal]. */
data class Layer(
    val id: Long,
    val values: List<Float> = List(ColorParam.entries.size) { 0f },
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val mask: Mask = Mask(),
    val blend: Blend = Blend.Normal,
) {
    operator fun get(p: ColorParam) = values[p.ordinal]
    fun with(p: ColorParam, v: Float) = copy(values = values.toMutableList().also { it[p.ordinal] = v })
}

/** Layers bottom to top, the order they render in. */
data class Document(val layers: List<Layer> = emptyList()) {
    fun replace(l: Layer) = copy(layers = layers.map { if (it.id == l.id) l else it })
    fun remove(id: Long) = copy(layers = layers.filterNot { it.id == id })
    fun move(id: Long, toIndex: Int): Document {
        val l = layers.first { it.id == id }
        val rest = layers.filterNot { it.id == id }
        return copy(layers = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), l) })
    }
}

/** Undo/redo over immutable snapshots; documents hold parameters only, so snapshots are cheap. */
data class History(
    val past: List<Document> = emptyList(),
    val current: Document = Document(),
    val future: List<Document> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(doc: Document) = if (doc == current) this else History((past + current).takeLast(LIMIT), doc)
    fun undo() = if (!canUndo) this else History(past.dropLast(1), past.last(), listOf(current) + future)
    fun redo() = if (!canRedo) this else History(past + current, future.first(), future.drop(1))

    companion object {
        const val LIMIT = 100
    }
}
