package dev.bloom.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bloom.editor.model.ColorEffect
import dev.bloom.editor.model.ColorParam
import dev.bloom.editor.model.Crop
import dev.bloom.editor.model.Document
import dev.bloom.editor.model.Layer
import dev.bloom.editor.model.Patch
import dev.bloom.editor.model.RetouchEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectSaveTest {
    @Test
    fun savedProjectReopensWithPatchPixelsAndThumbnail() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "project_test").apply { deleteRecursively(); mkdirs() }
        val px = Bitmap.createBitmap(IntArray(16 * 8) { Color.rgb(it, 255 - it, 7) }, 16, 8, Bitmap.Config.ARGB_8888)
        val patch = Patch(0.1f, 0.2f, 0.3f, 0.4f, px)
        val color = Layer(1, effect = ColorEffect().with(ColorParam.Exposure, 30f))
        val doc = Document(listOf(Layer(2, effect = RetouchEffect(patch)), color), crop = Crop(left = 0.5f))
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        Project(dir).apply { name = "test.jpg" }.save(doc, photo, emptyMap(), null)

        val (p, back) = Project.open(dir)
        assertEquals("test.jpg", p.name)
        assertEquals(color, back.layers[1])
        assertEquals(doc.crop, back.crop)
        val bp = (back.layers[0].effect as RetouchEffect).patch!!
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), listOf(bp.left, bp.top, bp.right, bp.bottom))
        assertTrue("patch pixels are lossless", bp.pixels.sameAs(px))
        // a half-width crop of a 2:1 photo is square
        val thumb = BitmapFactory.decodeFile(File(dir, "thumb.jpg").path)
        assertEquals(Project.THUMB, thumb.width)
        assertEquals(Project.THUMB, thumb.height)

        // a new patch replaces the old file; the old one goes once doc.json no longer needs it
        val px2 = px.copy(Bitmap.Config.ARGB_8888, false)
        p.save(back.replace(back.layers[0].copy(effect = RetouchEffect(Patch(0f, 0f, 1f, 1f, px2)))), photo, emptyMap(), null)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("patch_") })
        dir.deleteRecursively()
    }
}
