package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.localization.AppLanguage
import dev.defter.app.localization.tr
import dev.defter.app.model.PaperTemplate
import dev.defter.app.model.TextElement
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HistoryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun labeledControlsUndoRedoAndSaveTheResultOnTablet() = exerciseHistory()

    @Test
    @Config(application = dev.defter.app.localization.TurkishTestApplication::class, qualifiers = "w320dp-h720dp-port-mdpi")
    fun labeledControlsRemainVisibleAndWorkOnSmallPhone() = exerciseHistory()

    @Test
    @Config(application = dev.defter.app.localization.TurkishTestApplication::class, qualifiers = "tr-rTR-w393dp-h851dp-port-mdpi")
    fun enlargedSystemTextMovesHistoryBelowTheNotebookTitle() {
        // Robolectric 4.14.1 forwards setFontScale's system configuration directly to
        // Activity.resources.updateConfiguration, replacing per-context locale overrides.
        // Match this Turkish layout fixture's system language; locale independence has
        // separate tests. Keep the font-scale, visibility and history checks below strict.
        compose.runOnIdle { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        try {
            val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
            awaitIdle(model)
            compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
            awaitIdle(model)
            compose.runOnIdle { model.createNotebook("Büyük yazı", PaperTemplate.DOTS, 0xFF7162C9.toInt()) }
            awaitIdle(model)
            // Dump before looking up translated nodes so locale drift and actual clipping
            // remain distinguishable if a configuration change breaks this scenario.
            compose.runOnIdle {
                println("Large-text locale selected=${AppLanguage.selectedTag} preference=${AppLanguage.tag(compose.activity)} " +
                    "translatedStatus=${tr("Cihazına kaydedildi")} " +
                    "activityLocales=${compose.activity.resources.configuration.locales} " +
                    "applicationLocales=${compose.activity.application.resources.configuration.locales}")
            }
            println(compose.onRoot(useUnmergedTree = true).printToString())
            captureLargeTextHeader()
            compose.runOnIdle { assertEquals(2f, compose.activity.resources.configuration.fontScale, 0f) }
            val title = compose.onNodeWithText("Büyük yazı").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val statusNode = compose.onNodeWithText("Cihazına kaydedildi")
            val status = statusNode.fetchSemanticsNode().boundsInRoot
            println("Large-text header title=$title status=$status root=${compose.onRoot().fetchSemanticsNode().boundsInRoot} " +
                "window=${compose.activity.window.decorView.width}x${compose.activity.window.decorView.height} " +
                "configuration=${compose.activity.resources.configuration}")
            statusNode.assertIsDisplayed()
            for (label in listOf("Geri al", "Yinele")) {
                val bounds = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Large text needs a separate history row", bounds.top >= title.bottom && bounds.top >= status.bottom)
                assertTrue(bounds.left >= 0f && bounds.right <= compose.activity.window.decorView.width)
            }
            compose.runOnIdle { model.addText("Büyük yazıyla not") }
            awaitIdle(model)
            compose.onNodeWithText("Geri al").assertIsEnabled().performClick()
            awaitIdle(model)
            compose.runOnIdle { assertTrue(model.page!!.elements.isEmpty()) }
            compose.onNodeWithText("Yinele").assertIsEnabled().performClick()
            awaitIdle(model)
            compose.runOnIdle { assertEquals(1, model.page!!.elements.size) }
        } finally {
            compose.runOnIdle { RuntimeEnvironment.setFontScale(1f) }
        }
    }

    private fun exerciseHistory() {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        compose.runOnIdle { model.createNotebook("İşlem geçmişi", PaperTemplate.LINED, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        val bookId = model.notebookId!!
        val undo = compose.onNodeWithText("Geri al")
        val redo = compose.onNodeWithText("Yinele")
        undo.assertIsDisplayed().assertIsNotEnabled()
        redo.assertIsDisplayed().assertIsNotEnabled()
        for (button in listOf(undo, redo)) {
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("History controls need a full touch target", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("History controls must fit the viewport", bounds.left >= 0f && bounds.right <= compose.activity.window.decorView.width)
        }
        // Reach the text action through the header menu even on narrow screens.
        compose.onNodeWithContentDescription("Defter menüsü").performClick()
        compose.onNodeWithText("Metin ekle").performClick()
        compose.onNode(hasSetTextAction() and hasText("Notun")).performTextInput("Geri alınacak not")
        compose.onNodeWithText("Kaydet").performClick()
        awaitIdle(model)
        undo.assertIsEnabled().performClick()
        awaitIdle(model)
        compose.runOnIdle { assertTrue(model.page!!.elements.isEmpty()) }
        undo.assertIsNotEnabled()
        redo.assertIsEnabled().performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals("Geri alınacak not", (model.page!!.elements.single() as TextElement).text) }
        redo.assertIsNotEnabled()

        // A new edit after undo starts a new history branch.
        undo.performClick()
        awaitIdle(model)
        compose.runOnIdle { model.addText("Yeni düzenleme") }
        awaitIdle(model)
        redo.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        awaitIdle(model)
        compose.runOnIdle { model.openNotebook(bookId) }
        awaitIdle(model)
        compose.runOnIdle { assertEquals("Yeni düzenleme", (model.page!!.elements.single() as TextElement).text) }
        undo.assertIsNotEnabled()
        redo.assertIsNotEnabled()
    }

    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.loading && !model.busy && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error); assertFalse(model.unsaved) }
    }

    private fun captureLargeTextHeader() {
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try {
            val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
            File(directory, "history-large-text-phone.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
