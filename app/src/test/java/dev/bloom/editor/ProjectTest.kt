package dev.bloom.editor

import dev.bloom.editor.model.Blend
import dev.bloom.editor.model.BlurType
import dev.bloom.editor.model.BrushStep
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.ColorStep
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Curves
import dev.bloom.editor.model.Detail
import dev.bloom.editor.model.DetailEffect
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.GradientStep
import dev.bloom.editor.model.Group
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.LuminanceStep
import dev.bloom.editor.model.Mask
import dev.bloom.editor.model.RetouchEffect
import dev.bloom.editor.model.Stroke
import dev.bloom.editor.model.SubjectStep
import dev.bloom.editor.model.WarpEffect
import dev.bloom.editor.model.WarpStroke
import dev.bloom.editor.model.WarpTool
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProjectTest {
    private val doc = Document(
        layers = listOf(
            Layer(
                1, "Warm", ColorEffect().with(ColorParam.Temp, 20f).with(ColorParam.Exposure, -33.3f).copy(curves = Curves(rgb = listOf(0f, 0.1f, 0.5f, 0.6f, 1f, 1f))),
                opacity = 0.7f, blend = Blend.SoftLight,
                mask = Mask(
                    listOf(
                        BrushStep(listOf(Stroke(listOf(0.1f, 0.2f, 0.3f, 0.4f), 0.05f, 0.5f, 0.8f)), feather = 0.3f),
                        GradientStep(true, 0.5f, 0.5f, 0.9f, 0.5f, add = false),
                        ColorStep(listOf(1f, 0.5f, 0f), range = 0.4f),
                        LuminanceStep.Highlights,
                        LuminanceStep.Near,
                        SubjectStep(background = true, pins = listOf(0.2f, 0.3f, 1f), visible = false),
                    ),
                    invert = true,
                ),
            ),
            Layer(2, effect = DetailEffect(detail = Detail(BlurType.Lens, amount = 40f, focusX = 0.3f)), group = 9),
            Layer(3, effect = DetailEffect(sharpen = true, detail = Detail(amount = 60f, clarity = -20f)), visible = false, group = 9),
            Layer(4, effect = WarpEffect(listOf(WarpStroke(WarpTool.Twirl, listOf(0.5f, 0.5f, 0.6f, 0.5f), 0.1f, 0.5f, reverse = true)))),
            Layer(5, effect = RetouchEffect()),
        ),
        deband = false,
        groups = listOf(Group(9, "Background", opacity = 0.6f, visible = false, collapsed = true)),
        crop = Crop(turns = 1, flip = true, angle = 3.5f, vertical = 10f, left = 0.1f, top = 0.05f, right = 0.9f, bottom = 0.8f),
    )

    @Test
    fun documentSurvivesJson() {
        val json = Project(File("unused")).json
        val text = json.encodeToString(SavedProject(Project.VERSION, "IMG_1.jpg", doc))
        val back = json.decodeFromString<SavedProject>(text)
        assertEquals(doc, back.doc)
        assertEquals("IMG_1.jpg", back.name)
        assertEquals(1, back.version)
    }

    @Test
    fun unknownFieldsFromANewerBloomAreIgnored() {
        val json = Project(File("unused")).json
        val text = json.encodeToString(SavedProject(Project.VERSION, "a", Document(listOf(Layer(7)))))
            .replace("\"opacity\"", "\"glow\":5,\"opacity\"")
        assertEquals(Document(listOf(Layer(7))), json.decodeFromString<SavedProject>(text).doc)
    }
}
