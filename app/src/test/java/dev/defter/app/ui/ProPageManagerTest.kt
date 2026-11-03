package dev.defter.app.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.model.PaperTemplate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProPageManagerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pagesCanBeReorderedDeletedRestoredAndCopiedThroughDialogs() {
        val model = reset()
        compose.runOnIdle { model.createNotebook("Hedef", PaperTemplate.BLANK, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        val destination = model.notebookId!!
        compose.runOnIdle { model.closeNotebook() }
        awaitIdle(model)
        compose.runOnIdle { model.createNotebook("Kaynak", PaperTemplate.LINED, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        compose.runOnIdle { model.addPage(PaperTemplate.GRID) }
        awaitIdle(model)
        compose.runOnIdle { model.addPage(PaperTemplate.DOTS) }
        awaitIdle(model)
        val original = model.notebook!!.pageIds.toList()
        compose.onNodeWithContentDescription("Sayfaları göster").performClick()
        compose.onNodeWithContentDescription("Sayfa 3 önceye taşı").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(listOf(original[0], original[2], original[1]), model.notebook!!.pageIds) }

        compose.onNodeWithContentDescription("Sayfa 1 işlemleri").performClick()
        compose.onNodeWithText("Sayfayı sil").performClick()
        compose.onNodeWithText("Sil").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertEquals(listOf(original[0]), model.notebook!!.trashedPageIds); assertEquals(2, model.notebook!!.pageIds.size) }
        compose.onNodeWithText("Silinenler (1)").performClick()
        compose.onNodeWithContentDescription("Sayfayı geri getir").performClick()
        awaitIdle(model)
        compose.runOnIdle { assertTrue(model.notebook!!.trashedPageIds.isEmpty()); assertEquals(original.toSet(), model.notebook!!.pageIds.toSet()) }
        compose.onNodeWithText("Sayfalar").performClick()

        compose.onNodeWithContentDescription("Sayfa 1 işlemleri").performClick()
        compose.onNodeWithText("Başka deftere kopyala").performClick()
        compose.onAllNodesWithText("Hedef").onLast().performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals(3, model.notebook!!.pageIds.size)
            val target = model.library.notebooks.single { it.id == destination }
            assertEquals(2, target.pageIds.size)
            assertTrue(target.pageIds.none { it in original })
        }
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithContentDescription("Sayfaları göster").assertIsDisplayed()
    }

    @Test fun lastPageCannotBeDeletedOrMovedOutOfItsNotebook() {
        val model = reset()
        compose.runOnIdle { model.createNotebook("Tek sayfa", PaperTemplate.BLANK, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        compose.onNodeWithContentDescription("Sayfaları göster").performClick()
        compose.onNodeWithText("Defterde en az bir sayfa kalır.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sayfa 1 işlemleri").performClick()
        compose.onNodeWithText("Sayfayı sil").assertIsNotEnabled()
        compose.onNodeWithText("Başka deftere taşı").assertIsNotEnabled()
        compose.onNodeWithText("Başka deftere kopyala").assertIsEnabled()
    }

    @Test fun longPressDragReordersThePageGrid() {
        val model = reset()
        compose.runOnIdle { model.createNotebook("Sürükle", PaperTemplate.BLANK, 0xFF7162C9.toInt()) }
        awaitIdle(model)
        compose.runOnIdle { model.addPage(PaperTemplate.DOTS) }
        awaitIdle(model)
        val original = model.notebook!!.pageIds.toList()
        compose.onNodeWithContentDescription("Sayfaları göster").performClick()
        val source = compose.onNodeWithTag("page-card-${original[0]}")
        val destination = compose.onNodeWithTag("page-card-${original[1]}").fetchSemanticsNode().boundsInRoot
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(800)
            moveTo(center)
            moveTo(destination.center - sourceBounds.topLeft, delayMillis = 100)
            up()
        }
        awaitIdle(model)
        compose.runOnIdle { assertEquals(original.reversed(), model.notebook!!.pageIds) }
    }

    private fun reset(): DefterViewModel {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)
        return model
    }

    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.loading && !model.busy && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error); assertFalse(model.unsaved) }
    }
}
