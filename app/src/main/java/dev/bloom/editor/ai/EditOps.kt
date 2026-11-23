package dev.bloom.editor.ai

import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LayerKind
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.MaskStep
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.ui.label
import dev.bloom.editor.ui.signed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** One change Ask makes. Only [EditOps.parse] builds them, so every value is known and in range. */
sealed interface EditOp {
    /** A new Color layer on top with [params] (slider units), limited to [mask] (no steps = everywhere). */
    data class AddColorLayer(val params: Map<ColorParam, Float>, val mask: List<MaskStep>) : EditOp

    /** A new Blur layer on top. */
    data class AddBlur(val type: BlurType, val amount: Float, val mask: List<MaskStep>) : EditOp

    /** Sets [param] of layer [layer]: a [ColorParam] name on a Color layer, or [AMOUNT] on a Blur / Sharpen layer. */
    data class SetParam(val layer: Long, val param: String, val value: Float) : EditOp

    /** Adds [step] at the end of layer [layer]'s mask. */
    data class AddMaskStep(val layer: Long, val step: MaskStep) : EditOp

    companion object {
        const val AMOUNT = "Amount"
    }
}

/**
 * Ask: the text the LLM gets (what Bloom can do, the request, a summary of the layers: never the photo), and its reply
 * turned into checked [EditOp]s.
 */
object EditOps {
    const val MAX_OPS = 10
    const val MAX_MASK_STEPS = 4

    /** What [parse] kept, and why it dropped the rest. */
    class Parsed(val ops: List<EditOp>, val notes: List<String>)

    val SYSTEM = """
        You turn a photo editing request into changes in Bloom, a photo editor that works in layers. You never see the photo.
        Reply with JSON only, no other text: {"ops":[...]}, at most $MAX_OPS ops. The ops:
        {"op":"add_color_layer","params":{"Temp":20},"mask":[...]} adds a Color layer. params: Exposure, Contrast, Highlights, Shadows, Temp (+ is warmer), Tint (+ is magenta), Saturation, Vibrance; each -100..100, Exposure 100 = +3 stops. List only the params you change.
        {"op":"add_blur","type":"gaussian","amount":30,"mask":[...]} adds a Blur layer. type: gaussian, lens (depth of field), motion, zoom. amount 0..100.
        {"op":"set_param","layer":3,"param":"Temp","value":-10} sets a value of an existing layer (by its id); Blur and Sharpen layers have "Amount" 0..100.
        {"op":"add_mask_step","layer":3,"step":{...}} limits an existing layer further.
        Mask steps: {"type":"subject"} the main subject; {"type":"background"} everything but the subject; {"type":"gradient","from":"top"} full at that edge (top, bottom, left or right), gone past the middle: "top" is the sky; {"type":"luminance","range":"shadows"} (shadows, midtones or highlights). "add":false removes that area from the steps before it. No mask = the whole photo.
        Use natural amounts: 10..30 for a bit, 40..70 for a lot.
    """.trimIndent()

    /** The reply's shape, for servers that take a response_format JSON schema. */
    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("ops") {
                put("type", "array")
                put("maxItems", MAX_OPS)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("op") { putJsonArray("enum") { for (o in listOf("add_color_layer", "add_blur", "set_param", "add_mask_step")) add(JsonPrimitive(o)) } }
                    }
                    putJsonArray("required") { add(JsonPrimitive("op")) }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("ops")) }
    }

    /** The user message: the request and the layers bottom to top; with [target] only that layer, and only changes to it allowed. */
    fun message(request: String, doc: Document, target: Long? = null): String {
        val layers = doc.layers.filter { target == null || it.id == target }
        val only = if (target == null) "" else "\nChange only layer $target: use only set_param and add_mask_step with \"layer\":$target."
        return "Request: ${request.trim()}$only\nLayers, bottom to top: ${summary(layers)}"
    }

    /** [layers] as the LLM sees them: id, kind, name, changed values, mask steps. */
    fun summary(layers: List<Layer>): String = buildJsonArray {
        for (l in layers) add(buildJsonObject {
            put("id", l.id)
            put("kind", l.kind.name)
            if (l.name.isNotEmpty()) put("name", l.name)
            if (!l.visible) put("hidden", true)
            when (val e = l.effect) {
                is ColorEffect -> putJsonObject("params") { for (p in ColorParam.entries) if (e[p] != 0f) put(p.name, e[p].roundToInt()) }
                is DetailEffect -> { if (!e.sharpen) put("type", e.detail.type.name.lowercase()); put(EditOp.AMOUNT, e.detail.amount.roundToInt()) }
                else -> Unit
            }
            if (l.mask.steps.isNotEmpty()) putJsonArray("mask") { for (s in l.mask.steps) add(JsonPrimitive(describe(s))) }
        })
    }.toString()

    /**
     * The ops in an LLM [reply]: JSON alone, in a code fence or in prose (the first JSON object or array is read).
     * Each op is checked against [doc]: values clamped to their range, unknown ops, params, layers and mask steps
     * dropped with a note, at most [MAX_OPS]. With [target], only set_param / add_mask_step on that layer are kept.
     */
    fun parse(reply: String, doc: Document, target: Long? = null): Parsed {
        val root = firstJson(reply.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""))
            ?: throw AiError("The model didn't answer with changes")
        val list = root as? JsonArray ?: (root as? JsonObject)?.get("ops") as? JsonArray ?: throw AiError("The model didn't answer with changes")
        val ops = mutableListOf<EditOp>()
        val notes = mutableListOf<String>()
        for (e in list) {
            if (ops.size == MAX_OPS) { notes += "Only the first $MAX_OPS changes are used"; break }
            try {
                ops += op(e, doc, target, notes)
            } catch (x: AiError) {
                notes += x.message!!
            }
        }
        return Parsed(ops, notes)
    }

    private fun skip(why: String): Nothing = throw AiError(why)

    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.trim()

    private fun num(e: JsonElement?, range: ClosedFloatingPointRange<Float>, what: String): Float {
        val v = (e as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: skip("Skipped $what without a number")
        return v.coerceIn(range).roundToInt().toFloat()
    }

    private fun op(e: JsonElement, doc: Document, target: Long?, notes: MutableList<String>): EditOp {
        val o = e as? JsonObject ?: skip("Skipped a change that isn't an object")
        val name = str(o["op"]).orEmpty()
        val adds = name == "add_color_layer" || name == "add_blur"
        if (target != null && adds) skip("Skipped a new layer: only this layer changes here")
        return when (name) {
            "add_color_layer" -> {
                val params = (o["params"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                    val p = ColorParam.entries.firstOrNull { it.name.equals(k.trim(), true) }
                    if (p == null) { notes += "Skipped an unknown setting: ${k.take(30)}"; return@mapNotNull null }
                    p to num(v, -100f..100f, p.name)
                }.toMap().filterValues { it != 0f }
                if (params.isEmpty()) skip("Skipped a Color layer that changes nothing")
                EditOp.AddColorLayer(params, mask(o["mask"]))
            }
            "add_blur" -> {
                val type = BlurType.entries.firstOrNull { it.name.equals(str(o["type"]) ?: "gaussian", true) } ?: skip("Skipped an unknown blur: ${str(o["type"])?.take(30)}")
                val amount = num(o["amount"], 0f..100f, "a blur")
                if (amount == 0f) skip("Skipped a blur of 0")
                EditOp.AddBlur(type, amount, mask(o["mask"]))
            }
            "set_param" -> {
                val l = layer(o, doc, target)
                val p = str(o["param"]).orEmpty()
                when (l.effect) {
                    is ColorEffect -> {
                        val cp = ColorParam.entries.firstOrNull { it.name.equals(p, true) } ?: skip("Skipped an unknown setting: ${p.take(30)}")
                        EditOp.SetParam(l.id, cp.name, num(o["value"], -100f..100f, cp.name))
                    }
                    is DetailEffect -> {
                        if (!p.equals(EditOp.AMOUNT, true)) skip("Skipped an unknown setting: ${p.take(30)}")
                        EditOp.SetParam(l.id, EditOp.AMOUNT, num(o["value"], 0f..100f, EditOp.AMOUNT))
                    }
                    else -> skip("Skipped a setting: ${l.kind.label} layers have none")
                }
            }
            "add_mask_step" -> {
                val l = layer(o, doc, target)
                val s = step(o["step"])
                if (!s.add && l.mask.steps.none { it.visible }) skip("Skipped removing from a mask with nothing in it")
                EditOp.AddMaskStep(l.id, s)
            }
            else -> skip("Skipped an unknown change: ${name.take(30).ifEmpty { "no op" }}")
        }
    }

    private fun layer(o: JsonObject, doc: Document, target: Long?): Layer {
        val id = (o["layer"] as? JsonPrimitive)?.longOrNull ?: skip("Skipped a change without a layer")
        if (target != null && id != target) skip("Skipped a change to another layer")
        return doc.layers.firstOrNull { it.id == id } ?: skip("Skipped a change to a layer that doesn't exist")
    }

    private fun mask(e: JsonElement?): List<MaskStep> {
        val steps = (e as? JsonArray).orEmpty().take(MAX_MASK_STEPS).map(::step)
        // from nothing, removing leaves nothing: the layer would do nothing at all
        if (steps.firstOrNull()?.add == false) skip("Skipped a layer whose mask starts by removing")
        return steps
    }

    private fun step(e: JsonElement?): MaskStep {
        val o = e as? JsonObject ?: skip("Skipped a mask step that isn't an object")
        val add = (o["add"] as? JsonPrimitive)?.booleanOrNull ?: true
        val type = str(o["type"])?.lowercase()
        return when (type) {
            "subject" -> SubjectStep(add = add)
            "background" -> SubjectStep(background = true, add = add)
            "gradient" -> when (str(o["from"])?.lowercase()) {
                // as the mask bar's gradient: full a fifth in from the edge, gone past the middle
                "top" -> GradientStep(false, 0.5f, 0.2f, 0.5f, 0.6f, add = add)
                "bottom" -> GradientStep(false, 0.5f, 0.8f, 0.5f, 0.4f, add = add)
                "left" -> GradientStep(false, 0.2f, 0.5f, 0.6f, 0.5f, add = add)
                "right" -> GradientStep(false, 0.8f, 0.5f, 0.4f, 0.5f, add = add)
                else -> skip("Skipped a gradient without an edge")
            }
            "luminance" -> when (str(o["range"])?.lowercase()) {
                "shadows" -> LuminanceStep.Shadows.copy(add = add)
                "midtones" -> LuminanceStep.Midtones.copy(add = add)
                "highlights" -> LuminanceStep.Highlights.copy(add = add)
                else -> skip("Skipped a luminance range without shadows, midtones or highlights")
            }
            else -> skip("Skipped an unknown mask: ${type?.take(30) ?: "no type"}")
        }
    }

    /** The first JSON object or array in [text] that parses, or null. */
    fun firstJson(text: String): JsonElement? {
        var from = 0
        while (true) {
            val start = text.indexOfAny(charArrayOf('{', '['), from).takeIf { it >= 0 } ?: return null
            val end = closing(text, start)
            if (end > 0) runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()?.let { return it }
            from = start + 1
        }
    }

    /** Where the bracket at [start] closes, skipping strings; -1 if it doesn't. */
    private fun closing(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '\\') i++ else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** [doc] with [ops] applied; new layers go on top with ids from [firstId] up, so a preview and its Apply agree. */
    fun apply(doc: Document, ops: List<EditOp>, firstId: Long): Document {
        var id = firstId
        return ops.fold(doc) { d, op ->
            when (op) {
                is EditOp.AddColorLayer -> d.copy(layers = d.layers + Layer(id++, effect = op.params.entries.fold(ColorEffect()) { e, (p, v) -> e.with(p, v) }, mask = Mask(op.mask)))
                is EditOp.AddBlur -> d.copy(layers = d.layers + Layer(id++, effect = DetailEffect(detail = Detail(type = op.type, amount = op.amount)), mask = Mask(op.mask)))
                is EditOp.SetParam -> d.layers.firstOrNull { it.id == op.layer }?.let { l ->
                    d.replace(l.copy(effect = when (val e = l.effect) {
                        is ColorEffect -> e.with(ColorParam.valueOf(op.param), op.value)
                        is DetailEffect -> e.copy(detail = e.detail.copy(amount = op.value))
                        else -> e
                    }))
                } ?: d
                is EditOp.AddMaskStep -> d.layers.firstOrNull { it.id == op.layer }?.let { l -> d.replace(l.copy(mask = l.mask.copy(steps = l.mask.steps + op.step))) } ?: d
            }
        }
    }

    /** A checklist line: "Color layer · Temp +12 · mask: Subject", "Sky · Temp → −5". */
    fun label(op: EditOp, doc: Document): String {
        val title = { id: Long -> doc.layers.firstOrNull { it.id == id }?.let { it.name.ifEmpty { it.kind.label } } ?: "Layer" }
        val masked = { m: List<MaskStep> -> if (m.isEmpty()) "" else " · mask: " + m.joinToString(", ") { (if (it.add) "" else "not ") + name(it) } }
        return when (op) {
            is EditOp.AddColorLayer -> "${LayerKind.Color.label} layer · " + op.params.entries.joinToString(", ") { (p, v) -> "${p.name} ${signed(v)}" } + masked(op.mask)
            is EditOp.AddBlur -> "${LayerKind.Blur.label} layer · ${op.type.name} ${op.amount.roundToInt()}" + masked(op.mask)
            is EditOp.SetParam -> "${title(op.layer)} · ${op.param} → ${if (op.param == EditOp.AMOUNT) "${op.value.roundToInt()}" else signed(op.value)}"
            is EditOp.AddMaskStep -> "${title(op.layer)} · mask: ${if (op.step.add) "+" else "−"} ${name(op.step)}"
        }
    }

    private fun name(s: MaskStep): String = when (s) {
        is SubjectStep -> if (s.background) "Background" else "Subject"
        is GradientStep -> "Gradient"
        is LuminanceStep -> when (s.copy(add = true)) {
            LuminanceStep.Shadows -> "Shadows"
            LuminanceStep.Midtones -> "Midtones"
            LuminanceStep.Highlights -> "Highlights"
            else -> if (s.depth) "Depth range" else "Luminance range"
        }
        else -> s::class.simpleName.orEmpty().removeSuffix("Step")
    }

    /** How the LLM reads an existing mask step. */
    private fun describe(s: MaskStep): String = (if (s.add) "" else "remove ") + when (s) {
        is SubjectStep -> if (s.background) "background" else if (s.pins.isEmpty()) "subject" else "picked object"
        is GradientStep -> if (s.radial) "radial gradient" else "gradient"
        is LuminanceStep -> if (s.depth) "depth range" else "luminance range"
        is dev.bloom.editor.model.BrushStep -> "brush"
        is dev.bloom.editor.model.ColorStep -> "color range"
    }
}
