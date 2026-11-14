package dev.defter.app

import android.os.SystemClock
import android.view.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.defter.app.data.DocumentJson
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed optimized-release checks. Synthetic notebooks are added without clearing existing data. */
@RunWith(AndroidJUnit4::class)
class WorkspacePolishDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await() {
        compose.waitUntil(30_000) { !model().loading && !model().busy && !model().pageWindowLoading && model().pendingSaves == 0 }
        compose.runOnIdle { assertNull(model().error); assertFalse(model().unsaved) }
    }
    private fun find(v: View = compose.activity.window.decorView): NotebookCanvas? {
        if (v is NotebookCanvas && !v.isReadOnly) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun withNotebook(block: (NotebookCanvas) -> Unit) {
        await()
        val oldBook = model().notebookId; val oldPage = model().page?.id; val prefs = model().uiPreferences
        try {
            compose.runOnIdle { model().createNotebook("Workspace 0.8 verification", PaperTemplate.BLANK, 0xff005ac1.toInt()) }; await()
            block(compose.runOnIdle { requireNotNull(find()) })
        } finally {
            compose.runOnIdle {
                model().updateUiPreferences(prefs)
                if (oldBook != null && oldPage != null) model().openPage(oldBook, oldPage) else model().closeNotebook()
            }; await()
        }
    }
    @Test fun longBookMapAndFocusKeepTheInstalledCanvasAndStylusFunctional() = withNotebook { canvas ->
        repeat(8) { compose.runOnIdle { model().addPage(PaperTemplate.BLANK) }; await() }
        compose.onNodeWithContentDescription(tr("Sayfa haritası")).performClick()
        compose.onNodeWithContentDescription(tr("Haritada sayfaya git")).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }; await()
        compose.runOnIdle { assertEquals(0, model().pageIndex); assertTrue(model().loadedPages.size <= 5); assertSame(canvas, find()) }
        compose.onNodeWithContentDescription(tr("Odak modu")).performClick()
        compose.onNodeWithTag("editor-toolbar").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(canvas, find())
            val bounds = requireNotNull(canvas.pageBoundsInView(model().page!!.id))
            val start = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { x = bounds.left + bounds.width() * (.3f + .05f * index); y = bounds.top + 130f; pressure = 1f })
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { assertTrue(canvas.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
        }; await()
        compose.runOnIdle { assertEquals(1, model().page!!.elements.size); compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithContentDescription(tr("Geri al")).performClick(); await()
        compose.runOnIdle { assertTrue(model().page!!.elements.isEmpty()); assertSame(canvas, find()) }
        compose.onNodeWithContentDescription(tr("Haritada sayfaya git")).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }; await()
        compose.runOnIdle { assertEquals(8, model().pageIndex); assertTrue(model().loadedPages.size <= 5) }
        compose.onNodeWithContentDescription(tr("Haritayı kapat")).performClick()
    }
    @Test fun darkThemeAndPanelPreferencesPreserveDocumentAndNativeView() = withNotebook { canvas ->
        compose.runOnIdle { model().addText("Keep my paper and ink") }; await()
        val doc = compose.runOnIdle { DocumentJson.pageToJson(model().page!!).toString() }
        compose.onNodeWithContentDescription(tr("Defter menüsü")).performClick()
        compose.onNodeWithText(tr("Görünüm")).performClick()
        compose.onNodeWithText(tr("Koyu tema")).performClick()
        compose.onNodeWithText(tr("Liste")).performScrollTo().performClick()
        compose.onNodeWithContentDescription(tr("Sayfa paneli")).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        compose.onNodeWithText(tr("Uygula")).performClick()
        compose.onNodeWithContentDescription(tr("Sayfaları göster")).performClick()
        val width = compose.onNodeWithTag("pages-side-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.width / compose.activity.resources.displayMetrics.density
        assertEquals(400f, width, 1f)
        compose.runOnIdle { assertSame(canvas, find()); assertEquals(doc, DocumentJson.pageToJson(model().page!!).toString()); assertEquals(ThemeMode.DARK, model().uiPreferences.theme); assertEquals(LibraryLayout.LIST, model().uiPreferences.libraryLayout) }
        compose.onNodeWithContentDescription(tr("Kapat")).performClick()
        compose.activityRule.scenario.recreate(); await()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, model().uiPreferences.theme); assertEquals(400f, model().uiPreferences.pagesWidth, .1f); assertEquals(doc, DocumentJson.pageToJson(model().page!!).toString()) }
        val bookId = model().notebookId!!
        compose.runOnIdle { model().closeNotebook() }; await()
        val card = compose.onNodeWithTag("notebook-card-$bookId").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("List cards must use the compact horizontal layout", card.height / compose.activity.resources.displayMetrics.density < 160f)
    }
}
