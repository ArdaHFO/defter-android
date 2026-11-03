package dev.defter.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.ImageElement
import dev.defter.app.model.PaperTemplate
import dev.defter.app.model.TextElement
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

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProSelectionStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun existingTextDraftSurvivesRecreationAndAppliesToOriginalElementWithUndo() {
        val model = openNotebook()
        compose.runOnIdle { model.addText("Özgün metin") }
        awaitIdle(model)
        val original = model.page!!.elements.single() as TextElement
        compose.runOnIdle { canvas().selectElements(setOf(original.id)) }
        compose.onNodeWithContentDescription("Seçili metni düzenle").performClick()
        compose.onNode(hasSetTextAction() and hasText("Metin")).performTextReplacement("Döndürülmüş düzenleme")
        compose.activityRule.scenario.recreate()
        awaitIdle(model())
        compose.onNodeWithText("Metni düzenle").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Metin")).assertTextContains("Döndürülmüş düzenleme")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        awaitIdle(model())
        compose.runOnIdle {
            assertSame(model, model())
            val saved = model.page!!.elements.single() as TextElement
            assertEquals(original.id, saved.id)
            assertEquals("Döndürülmüş düzenleme", saved.text)
        }
        compose.onNodeWithText("Geri al").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(original, model.page!!.elements.single()) }
    }

    @Test fun cropDraftSurvivesRecreationAndAppliesToImportedImageWithUndo() {
        val model = openNotebook()
        val input = File(model.getApplication<Application>().cacheDir, "selection-rotation-input.png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF397AC6.toInt()) }
        try { input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        compose.runOnIdle { model.importImage(Uri.fromFile(input)) }
        awaitIdle(model)
        val original = model.page!!.elements.single() as ImageElement
        compose.runOnIdle { canvas().selectElements(setOf(original.id)) }
        compose.onNodeWithContentDescription("Seçili görseli kırp").performClick()
        compose.onNodeWithContentDescription("Sol kırpma sınırı").performSemanticsAction(SemanticsActions.SetProgress) { it(.25f) }
        compose.onNodeWithContentDescription("Sağ kırpma sınırı").performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        compose.activityRule.scenario.recreate()
        awaitIdle(model())
        compose.onNodeWithText("Görseli kırp").assertIsDisplayed()
        compose.onNodeWithText("Sol · %25").assertIsDisplayed()
        compose.onNodeWithText("Sağ · %75").assertIsDisplayed()
        compose.onNodeWithText("Uygula").assertIsEnabled().performClick()
        awaitIdle(model())
        compose.runOnIdle {
            assertSame(model, model())
            val saved = model.page!!.elements.single() as ImageElement
            assertEquals(original.id, saved.id)
            assertEquals(.25f, saved.cropLeft, 0f)
            assertEquals(.75f, saved.cropRight, 0f)
            assertEquals(original.width / 2f, saved.width, .001f)
            assertEquals(original.height, saved.height, .001f)
        }
        compose.onNodeWithText("Geri al").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(original, model.page!!.elements.single()) }
    }

    private fun openNotebook(): DefterViewModel {
        val model = model()
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        compose.runOnIdle { model.createNotebook("Döndürme testi", PaperTemplate.DOTS, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        return model
    }

    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun canvas(): NotebookCanvas = findCanvas(compose.activity.window.decorView) ?: error("Editor canvas not found")
    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.loading && !model.busy && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error); assertFalse(model.unsaved) }
    }
}
