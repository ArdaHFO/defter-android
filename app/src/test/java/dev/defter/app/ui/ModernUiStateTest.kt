package dev.defter.app.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.data.DocumentRepository
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.DrawingTool
import dev.defter.app.model.PaperTemplate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModernUiStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
    private fun awaitIdle(model: DefterViewModel) {
        await { !model.loading && !model.busy && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error) }
    }
    private fun openNotebook(): DefterViewModel {
        val model = model()
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        compose.runOnIdle { model.createNotebook("Modern notlar", PaperTemplate.DOTS, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        return model
    }

    @Test fun penAndHighlighterKeepTheirOwnWidthsAcrossToolChanges() {
        val model = openNotebook()
        compose.runOnIdle { model.config = model.config.copy(width = 9f) }
        compose.onNodeWithContentDescription("Çizgi silgisi").performClick()
        compose.onNodeWithContentDescription("Kalem").performClick()
        compose.runOnIdle { assertEquals(9f, model.config.width, 0f) }
        compose.onNodeWithContentDescription("Fosforlu").performClick()
        compose.runOnIdle {
            assertEquals(24f, model.config.width, 0f)
            model.config = model.config.copy(width = 32f)
        }
        compose.onNodeWithContentDescription("Kalem").performClick()
        compose.runOnIdle { assertEquals(9f, model.config.width, 0f) }
        compose.onNodeWithContentDescription("Fosforlu").performClick()
        compose.runOnIdle { assertEquals(32f, model.config.width, 0f) }
    }

    @Test fun textDraftSurvivesConfigurationRecreationAndCanBeSaved() {
        val model = openNotebook()
        compose.onNodeWithContentDescription("Metin ekle").performClick()
        compose.onNode(hasSetTextAction() and hasText("Notun")).performTextInput("Döndürünce korunan not")
        compose.activityRule.scenario.recreate()
        awaitIdle(model())
        compose.onNode(hasSetTextAction() and hasText("Notun")).assertTextContains("Döndürünce korunan not")
        compose.onNodeWithText("Kaydet").performClick()
        awaitIdle(model())
        compose.runOnIdle {
            assertSame(model, model())
            assertEquals(1, model.page!!.elements.size)
        }
    }

    @Test fun colorSwatchesExposeSelectionAndFullTouchTargets() {
        val model = openNotebook()
        compose.onNodeWithContentDescription("Lacivert renk").assertIsSelected()
        val swatch = compose.onNodeWithContentDescription("Mavi renk")
        val bounds = swatch.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f && bounds.height >= 48f)
        swatch.assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Lacivert renk").assertIsNotSelected()
        compose.runOnIdle { assertEquals(0xFF397AC6.toInt(), model.config.color) }
    }

    @Test fun temporaryEraserFeedbackDoesNotChangeTheChosenToolOrWidth() {
        val model = openNotebook()
        compose.onNodeWithContentDescription("Fosforlu").performClick()
        compose.runOnIdle {
            model.config = model.config.copy(width = 31f)
            val canvas = findCanvas(compose.activity.window.decorView)!!
            canvas.onTemporaryEraserChanged?.invoke(true)
        }
        compose.onNodeWithText("Yan düğme · Silgi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Çizgi silgisi").assertIsSelected()
        compose.onNodeWithContentDescription("Fosforlu").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(DrawingTool.HIGHLIGHTER, model.config.tool)
            assertEquals(31f, model.config.width, 0f)
            findCanvas(compose.activity.window.decorView)!!.onTemporaryEraserChanged?.invoke(false)
        }
        compose.onNodeWithText("Yan düğme · Silgi").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fosforlu").assertIsSelected()
    }

    @Test fun busyEditorConsumesBackUntilPendingNoteHasBeenSaved() {
        val model = openNotebook()
        val activity = compose.activity
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blocker = scope.launch {
            DocumentRepository(activity).exportBackup(object : OutputStream() {
                override fun write(value: Int) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            })
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { model.addText("Kayıt beklenecek"); model.closeNotebook() }
            await { model.busy && model.pendingSaves > 0 }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
                assertTrue(model.busy)
            }
        } finally {
            release.countDown()
            runBlocking { withTimeout(10_000) { blocker.join() } }
            scope.cancel()
        }
        awaitIdle(model)
        compose.runOnIdle { assertNull(model.notebookId); assertFalse(model.unsaved) }
    }

    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
