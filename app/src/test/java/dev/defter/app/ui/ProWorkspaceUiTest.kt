package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import dev.defter.app.recognition.HandwritingRecognizer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

/** Native Compose workflows. Screenshots are synthetic-data UI evidence, not device OCR evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun clickingTabsKeepsLastPageAndClosingCurrentTabChoosesRemainingNotebook() {
        val model = freshModel()
        createBook(model, "Matematik")
        val math = model.notebookId!!
        compose.runOnIdle { model.addPage(PaperTemplate.GRID) }
        awaitIdle(model)
        val mathPage = model.page!!.id
        compose.runOnIdle { model.addText("İkinci sayfada kaldım") }
        awaitIdle(model)
        createBook(model, "Fizik")
        val physics = model.notebookId!!
        compose.onNodeWithContentDescription("Sekme Matematik").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(math, model.notebookId); assertEquals(mathPage, model.page!!.id) }
        compose.onNodeWithContentDescription("Matematik sekmesini kapat").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(physics, model.notebookId); assertEquals(listOf(physics), model.openTabs) }
        compose.onNodeWithContentDescription("Fizik sekmesini kapat").performClick()
        awaitIdle(model)
        compose.onNodeWithText("Kütüphanen").assertIsDisplayed()
    }

    @Test fun referencePaneNavigatesIndependentlyAndSwapsIntoTheEditablePane() {
        val model = freshModel()
        createBook(model, "Ders kitabı")
        val sourceBook = model.notebookId!!
        val firstSourcePage = model.page!!.id
        fillLesson(model, "Fonksiyonlar", "KAYNAK NOTLARI")
        compose.runOnIdle { model.addPage(PaperTemplate.GRID) }
        awaitIdle(model)
        val secondSourcePage = model.page!!.id
        fillLesson(model, "Türev kuralları", "ÖRNEKLER VE AÇIKLAMALAR")
        compose.runOnIdle { model.selectPage(firstSourcePage) }
        awaitIdle(model)
        createBook(model, "Çalışma defteri")
        val editorBook = model.notebookId!!
        val editorPage = model.page!!.id
        fillLesson(model, "Kendi çözümüm", "MATEMATİK / DERS ÇALIŞMA")
        menu("Yan yana aç")
        compose.onNodeWithText("Ders kitabı").performClick()
        awaitIdle(model)
        compose.onNodeWithText("Referans · 1/2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Referansta sonraki sayfa").assertIsEnabled().performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals(editorPage, model.page!!.id)
            assertEquals(secondSourcePage, model.referencePage!!.id)
            val canvases = canvases(compose.activity.window.decorView)
            assertEquals(2, canvases.size)
            assertTrue(canvases.single { it.document?.id == secondSourcePage }.isReadOnly)
            assertFalse(canvases.single { it.document?.id == editorPage }.isReadOnly)
        }
        capture("pro-editor-split.png")
        compose.onNodeWithContentDescription("Referansı düzenlemeye geç").performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals(sourceBook, model.notebookId)
            assertEquals(secondSourcePage, model.page!!.id)
            assertEquals(editorBook, model.referenceNotebookId)
            assertEquals(editorPage, model.referencePage!!.id)
            assertFalse(canvases(compose.activity.window.decorView).single { it.document?.id == secondSourcePage }.isReadOnly)
        }
        compose.onNodeWithContentDescription("Referansı kapat").performClick()
        compose.runOnIdle { assertNull(model.referencePage); assertEquals(1, canvases(compose.activity.window.decorView).size) }
    }

    @Test fun handwritingDialogAppliesEditableResultUndoWorksAndSearchOpensItsPage() {
        val model = freshModel()
        val recognizer = UiRecognizer()
        compose.runOnIdle { ReflectionHelpers.setField(model, "recognizerInstance", recognizer) }
        createBook(model, "Ders")
        val bookId = model.notebookId!!
        compose.runOnIdle { model.addPage(PaperTemplate.LINED) }
        awaitIdle(model)
        val target = model.page!!.id
        val ink = StrokeElement(points = listOf(InkPoint(100f, 150f), InkPoint(150f, 130f), InkPoint(180f, 180f)))
        compose.runOnIdle { model.changePage(model.page!!.copy(elements = listOf(ink))) }
        awaitIdle(model)
        menu("El yazısı ve arama")
        compose.onNodeWithText("Dil modelini indir").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(1, recognizer.downloads) }
        compose.onNodeWithText("Tamam").performClick()
        compose.onNodeWithText("Kapat").performClick()
        menu("El yazısını metne çevir")
        awaitIdle(model)
        compose.onNodeWithText("El yazısı sonucu").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Tanınan metin")).performTextReplacement("İntegral örneği")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Uygula").performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals("İntegral örneği", (model.page!!.elements.single() as TextElement).text)
            assertEquals(1, recognizer.recognitions)
        }
        compose.onNodeWithText("Geri al").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(listOf(ink), model.page!!.elements) }
        compose.onNodeWithText("Yinele").performClick()
        awaitIdle(model)
        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        awaitIdle(model)
        compose.onNode(hasSetTextAction() and hasText("Defter veya içerik ara")).performTextInput("İntegral")
        compose.mainClock.advanceTimeBy(400)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.searchResults.any { it.pageId == target } && !model.searching }
        compose.onNodeWithText("Ders · 2. sayfa").assertIsDisplayed().performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(bookId, model.notebookId); assertEquals(target, model.page!!.id) }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-mdpi")
    fun phoneCanReachLassoAndFilteredPartialEraserControls() {
        val model = freshModel()
        createBook(model, "Matematik")
        fillLesson(model, "Türev ve değişim", "KONU ÖZETİ")
        compose.onNodeWithContentDescription("Silgi seçenekleri").performScrollTo().performClick()
        compose.onNodeWithText("Yalnız dokunulan bölüm").performClick()
        compose.onNodeWithText("Yalnız fosforlu").performClick()
        compose.onNodeWithText("Tamam").performClick()
        compose.runOnIdle { assertEquals(EraserMode.PARTIAL, model.config.eraserMode); assertEquals(EraserFilter.HIGHLIGHTER, model.config.eraserFilter) }
        compose.onNodeWithContentDescription("Kement").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Geri al").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sekme Matematik").assertIsDisplayed()
        capture("pro-editor-phone.png")
    }

    private fun menu(label: String) {
        compose.onNodeWithContentDescription("Defter menüsü").performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
    }
    private fun freshModel(): DefterViewModel {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        return model
    }
    private fun createBook(model: DefterViewModel, title: String) {
        compose.runOnIdle { model.createNotebook(title, PaperTemplate.DOTS, 0xFF6254D9.toInt()) }
        awaitIdle(model)
    }
    private fun fillLesson(model: DefterViewModel, title: String, subtitle: String) {
        compose.runOnIdle {
            model.changePage(model.page!!.copy(elements = listOf(
                TextElement(text = title, x = 90f, y = 90f, size = 46f),
                TextElement(text = subtitle, x = 92f, y = 160f, size = 22f, color = 0xFF657087.toInt()),
                StrokeElement(points = listOf(InkPoint(90f, 270f), InkPoint(1020f, 270f)), kind = InkKind.HIGHLIGHTER, width = 44f, color = 0xFFF4CD4C.toInt()),
                TextElement(text = "Değişimi anlamak için grafiği incele.", x = 92f, y = 235f, size = 32f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 160f, startY = 680f, endX = 1060f, endY = 680f, color = 0xFF657087.toInt()),
                ShapeElement(shape = ShapeKind.ARROW, startX = 160f, startY = 680f, endX = 160f, endY = 360f, color = 0xFF657087.toInt()),
                StrokeElement(points = (0..25).map { val t = it / 25f; InkPoint(200f + t * 760f, 650f - t * t * 250f) }, width = 7f, color = 0xFF6254D9.toInt()),
                TextElement(text = "KENDİNİ DENE", x = 90f, y = 850f, size = 24f, color = 0xFF6254D9.toInt()),
                TextElement(text = "1. Eğimin değiştiği noktaları işaretle.\n2. Sonucunu kendi cümlelerinle açıkla.", x = 90f, y = 910f, size = 28f)
            )))
        }
        awaitIdle(model)
    }
    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); !model.loading && !model.busy && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error); assertFalse(model.unsaved) }
    }
    private fun canvases(view: View): List<NotebookCanvas> = if (view is NotebookCanvas) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { canvases(view.getChildAt(it)) } else emptyList()
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { val decor = compose.activity.window.decorView; Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) } }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) colors += bitmap.getPixel(x, y)
        assertTrue(colors.size > 12)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private class UiRecognizer : HandwritingRecognizer {
        var downloaded = false
        var downloads = 0
        var recognitions = 0
        override suspend fun isModelDownloaded(languageTag: String) = downloaded
        override suspend fun downloadModel(languageTag: String) { downloads++; downloaded = true }
        override suspend fun recognize(strokes: List<StrokeElement>, languageTag: String, preContext: String): List<String> {
            check(downloaded); recognitions++; return listOf("İntegral notu")
        }
        override fun close() {}
    }
}
