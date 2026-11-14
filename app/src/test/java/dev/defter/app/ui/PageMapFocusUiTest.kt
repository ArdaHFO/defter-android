package dev.defter.app.ui

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.localization.FreshInstallApplication
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
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PageMapFocusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await() {
        compose.waitUntil(25_000) { shadowOf(Looper.getMainLooper()).idle(); !model().loading && !model().busy && model().pendingSaves == 0 && !model().pageWindowLoading }
        compose.runOnIdle { assertNull(model().error); assertFalse(model().unsaved) }
    }
    private fun open(pages: Int = 1) {
        await(); compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await()
        compose.runOnIdle { model().createNotebook("Navigation study", PaperTemplate.BLANK, 0xff005ac1.toInt()) }; await()
        repeat(pages - 1) { compose.runOnIdle { model().addPage(PaperTemplate.BLANK) }; await() }
    }
    private fun canvas(view: View = compose.activity.window.decorView): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) canvas(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun pageMapSeeksAcrossALongNotebookWithoutExpandingTheLoadedWindow() {
        open(14)
        val view = compose.runOnIdle { requireNotNull(canvas()) }
        compose.onNodeWithContentDescription("Page map").performClick()
        compose.onNodeWithTag("page-map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seek to a page").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        await()
        compose.runOnIdle { assertEquals(0, model().pageIndex); assertTrue(model().loadedPages.size <= 5); assertSame(view, canvas()) }
        compose.onNodeWithContentDescription("Seek to a page").performSemanticsAction(SemanticsActions.SetProgress) { it(13f) }
        await()
        compose.runOnIdle { assertEquals(13, model().pageIndex); assertTrue(model().loadedPages.size <= 5); assertSame(view, canvas()) }
        compose.onNodeWithContentDescription("Close page map").performClick()
        compose.onNodeWithTag("page-map").assertDoesNotExist()
        compose.runOnIdle { assertSame(view, canvas()) }
    }
    @Test fun focusModeHidesChromeKeepsWritingAndBackRestoresTheSameCanvas() {
        open()
        val view = compose.runOnIdle { requireNotNull(canvas()) }
        compose.onNodeWithContentDescription("Focus mode").performClick()
        compose.onNodeWithTag("editor-toolbar").assertDoesNotExist()
        compose.onNodeWithTag("editor-document-header").assertDoesNotExist()
        compose.onNodeWithTag("focus-controls").assertIsDisplayed()
        compose.onNodeWithContentDescription("New page").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(view, canvas())
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            fun send(action: Int, x: Float) {
                val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 220f; pressure = 1f })
                val event = MotionEvent.obtain(100, 120, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 180f); send(MotionEvent.ACTION_MOVE, 260f); send(MotionEvent.ACTION_UP, 300f)
        }; await()
        compose.runOnIdle { assertEquals(1, model().page!!.elements.size); compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("focus-controls").assertDoesNotExist()
        compose.runOnIdle { assertSame(view, canvas()); assertNotNull(model().notebookId) }
        compose.onNodeWithContentDescription("Undo").performClick(); await()
        compose.runOnIdle { assertTrue(model().page!!.elements.isEmpty()) }
    }
    @Test
    @Config(application = FreshInstallApplication::class, qualifiers = "w320dp-h720dp-port-mdpi")
    fun phoneMapAndFocusControlsRemainReachableAndFocusSurvivesRecreation() {
        open(4)
        compose.onNodeWithContentDescription("Page map").assertIsDisplayed().performClick()
        compose.onNodeWithTag("page-map").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close page map").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Focus mode").assertIsDisplayed().performClick()
        compose.activityRule.scenario.recreate(); await()
        compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
    }
}
