package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Real native Compose captures used for visual review, not physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModernPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabletLibraryAndEditorRemainFunctionalAndRender() {
        val model = model()
        await(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        await(model)
        for (folder in listOf("Dersler", "Kişisel")) {
            compose.runOnIdle { model.createFolder(folder) }
            await(model)
        }
        for ((title, cover) in listOf("Matematik" to 0xff6261d8.toInt(), "Fizik" to 0xff397d79.toInt(),
            "Lineer cebir" to 0xffb27855.toInt(), "Fikir defteri" to 0xff5d7da6.toInt())) {
            compose.runOnIdle { model.createNotebook(title, PaperTemplate.DOTS, cover) }
            await(model)
            compose.runOnIdle { model.closeNotebook() }
            await(model)
        }
        compose.runOnIdle { assertEquals(4, model.library.notebooks.size); assertEquals(2, model.library.folders.size) }
        compose.onNodeWithText("Kütüphanen").assertIsDisplayed()
        capture("modern-library-tablet.png")
        val book = model.library.notebooks.first { it.title == "Matematik" }
        compose.runOnIdle { model.openNotebook(book.id) }
        await(model)
        fillLesson(model)
        compose.onNodeWithContentDescription("Kalem").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yeni sayfa").assertIsDisplayed()
        compose.onNodeWithText("Geri al").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Yinele").assertIsDisplayed().assertIsNotEnabled()
        capture("modern-editor-tablet.png")
        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        await(model)
        assertNull(model.notebookId)
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-mdpi")
    fun compactPhoneKeepsEditorControlsAndScrollableToolsReachable() {
        val model = model()
        await(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        await(model)
        compose.runOnIdle { model.createNotebook("Matematik", PaperTemplate.DOTS, 0xff6261d8.toInt()) }
        await(model)
        fillLesson(model)
        compose.onNodeWithContentDescription("Kalem").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yeni sayfa").assertIsDisplayed()
        compose.onNodeWithText("Geri al").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Yinele").assertIsDisplayed().assertIsNotEnabled()
        capture("modern-editor-phone.png")
        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        await(model)
        compose.onNodeWithText("Kütüphanen").assertIsDisplayed()
        capture("modern-library-phone.png")
    }

    private fun fillLesson(model: DefterViewModel) {
        compose.runOnIdle {
            val current = model.page!!
            model.changePage(current.copy(elements = listOf(
                TextElement(text = "Türev ve değişim", x = 92f, y = 80f, size = 46f, color = 0xff25324b.toInt()),
                TextElement(text = "MATEMATİK  /  ÇALIŞMA NOTLARI", x = 94f, y = 150f, size = 20f, color = 0xff7b8598.toInt()),
                StrokeElement(points = listOf(InkPoint(94f, 250f), InkPoint(840f, 250f)), width = 44f,
                    color = 0xffffd66b.toInt(), kind = InkKind.HIGHLIGHTER),
                TextElement(text = "Türev, bir fonksiyonun anlık değişim hızıdır.", x = 94f, y = 220f, size = 30f, color = 0xff25324b.toInt()),
                ShapeElement(shape = ShapeKind.ARROW, startX = 180f, startY = 620f, endX = 1010f, endY = 620f,
                    color = 0xff7b8598.toInt(), width = 3f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 180f, startY = 620f, endX = 180f, endY = 330f,
                    color = 0xff7b8598.toInt(), width = 3f),
                StrokeElement(points = (0..32).map {
                    val x = 210f + it * 22f
                    val t = it / 32f
                    InkPoint(x, 590f - t * t * 230f, .8f)
                }, width = 7f, color = 0xff6261d8.toInt()),
                ShapeElement(shape = ShapeKind.LINE, startX = 500f, startY = 595f, endX = 960f, endY = 365f,
                    color = 0xffcd7759.toInt(), width = 3.5f),
                TextElement(text = "f(x)", x = 920f, y = 325f, size = 27f, color = 0xff6261d8.toInt()),
                TextElement(text = "Teğetin eğimi = türev", x = 510f, y = 655f, size = 28f, color = 0xffcd7759.toInt()),
                TextElement(text = "KENDİNİ DENE", x = 94f, y = 780f, size = 22f, color = 0xff6261d8.toInt()),
                TextElement(text = "1. Grafikte artışın hızlandığı bölgeyi işaretle.\n2. İki farklı noktadaki eğimi karşılaştır.",
                    x = 94f, y = 825f, size = 27f, color = 0xff45516a.toInt())
            )))
        }
        await(model)
        compose.runOnIdle { assertFalse(model.unsaved); assertNull(model.error) }
    }

    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]

    private fun await(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.loading && !model.busy && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 60)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 60)) colors += bitmap.getPixel(x, y)
        }
        assertTrue("Capture must contain real rendered UI", colors.size > 12)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
