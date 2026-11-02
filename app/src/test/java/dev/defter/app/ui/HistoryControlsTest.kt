package dev.defter.app.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.model.PaperTemplate
import dev.defter.app.model.TextElement
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
@Config(sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HistoryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun labeledControlsUndoRedoAndSaveTheResultOnTablet() = exerciseHistory()

    @Test
    @Config(qualifiers = "w320dp-h720dp-port-mdpi")
    fun labeledControlsRemainVisibleAndWorkOnSmallPhone() = exerciseHistory()

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-mdpi")
    fun enlargedSystemTextMovesHistoryBelowTheNotebookTitle() {
        compose.runOnIdle { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        try {
            val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
            awaitIdle(model)
            compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
            awaitIdle(model)
            compose.runOnIdle { model.createNotebook("Büyük yazı", PaperTemplate.DOTS, 0xFF7162C9.toInt()) }
            awaitIdle(model)
            compose.runOnIdle { assertEquals(2f, compose.activity.resources.configuration.fontScale, 0f) }
            val title = compose.onNodeWithText("Büyük yazı").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val status = compose.onNodeWithText("Cihazına kaydedildi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
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
}
