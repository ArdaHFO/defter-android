package dev.defter.app.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.data.DocumentJson
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.localization.FreshInstallApplication
import dev.defter.app.localization.tr
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

@RunWith(RobolectricTestRunner::class)
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkspacePolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await() {
        compose.waitUntil(25_000) { shadowOf(Looper.getMainLooper()).idle(); !model().loading && !model().busy && model().pendingSaves == 0 && !model().pageWindowLoading }
        compose.runOnIdle { assertNull(model().error); assertFalse(model().unsaved) }
    }
    private fun open() {
        await(); compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await()
        compose.runOnIdle { model().createNotebook("Workspace checks", PaperTemplate.DOTS, 0xff005ac1.toInt()) }; await()
    }
    private fun view(v: View = compose.activity.window.decorView): NotebookCanvas? {
        if (v is NotebookCanvas && !v.isReadOnly) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) view(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun referenceView(v: View = compose.activity.window.decorView): NotebookCanvas? {
        if (v is NotebookCanvas && v.isReadOnly) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) referenceView(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun appearance() {
        compose.onNodeWithContentDescription(tr("Defter menüsü")).performClick()
        compose.onNodeWithText(tr("Görünüm")).performClick()
    }

    @Test fun themeAndPanelSizeChangesPreserveTheCanvasAndStoredInk() {
        open()
        compose.runOnIdle { model().addText("Paper colors stay mine") }; await()
        val original = compose.runOnIdle { requireNotNull(view()) }
        val document = compose.runOnIdle { DocumentJson.pageToJson(model().page!!).toString() }
        appearance()
        compose.onNodeWithText(tr("Koyu tema")).performClick()
        compose.onNodeWithContentDescription(tr("Sayfa paneli")).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        compose.onNodeWithText(tr("Uygula")).performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, model().uiPreferences.theme)
            assertSame(original, view()); assertEquals(document, DocumentJson.pageToJson(model().page!!).toString())
        }
        compose.onNodeWithContentDescription(tr("Sayfaları göster")).performClick()
        val bounds = compose.onNodeWithTag("pages-side-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(400f, bounds.width, 1f)
        compose.onNodeWithContentDescription(tr("Kapat")).performClick()
        compose.runOnIdle { assertSame(original, view()) }
        compose.activityRule.scenario.recreate(); await()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, model().uiPreferences.theme); assertEquals(document, DocumentJson.pageToJson(model().page!!).toString()) }
    }

    @Test fun referenceOpensResizesAndClosesWithoutRemountingTheEditableView() {
        open()
        val reference = model().notebookId!!
        compose.runOnIdle { model().createNotebook("Editable study", PaperTemplate.GRID, 0xff005ac1.toInt()) }; await()
        val original = compose.runOnIdle { requireNotNull(view()) }
        compose.runOnIdle { model().openReference(reference) }; await()
        val initialWidth = compose.runOnIdle { assertSame(original, view()); original.width }
        val paper = compose.runOnIdle { model().referencePage!!.backgroundColor }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            compose.runOnIdle { model().updateUiPreferences(model().uiPreferences.copy(theme = theme)) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Reference margins must follow the same theme as editable margins",
                    (view()!!.background as android.graphics.drawable.ColorDrawable).color,
                    (referenceView()!!.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(paper, model().referencePage!!.backgroundColor)
                assertSame(original, view())
            }
        }
        compose.runOnIdle { model().updateUiPreferences(model().uiPreferences.copy(referenceShare = .65f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(original, view()); assertTrue(original.width < initialWidth) }
        compose.onNodeWithContentDescription(tr("Referansı kapat")).performClick()
        compose.runOnIdle { assertSame(original, view()); assertTrue(original.width > initialWidth) }
    }

    @Test fun eraserPopoverShowsRelevantModesFiltersAndSizeWithoutChangingTheDocument() {
        open()
        val revision = model().page!!.revision
        compose.onNodeWithContentDescription(tr("Çizgi silgisi")).performClick()
        compose.onNodeWithContentDescription(tr("Renk ve kalınlık")).performClick()
        compose.onNodeWithContentDescription(tr("%1\$s renk", tr("Mavi"))).assertDoesNotExist()
        compose.onNodeWithText(tr("Yalnız dokunulan bölüm")).performClick().assertIsSelected()
        compose.onNodeWithText(tr("Yalnız kalem")).performClick().assertIsSelected()
        compose.onNodeWithContentDescription(tr("Silgi boyutu")).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithText(tr("Kalınlık ayarı")).performScrollTo().performClick()
        compose.onNodeWithText(tr("Uygula")).performClick()
        compose.runOnIdle {
            assertEquals(EraserMode.PARTIAL, model().config.eraserMode); assertEquals(EraserFilter.PEN, model().config.eraserFilter)
            assertEquals(20f, model().config.width, .1f); assertEquals(revision, model().page!!.revision)
        }
        compose.onNodeWithContentDescription(tr("Kalem")).performClick()
        compose.onNodeWithContentDescription(tr("Renk ve kalınlık")).performClick()
        compose.onNodeWithContentDescription(tr("Çizgi önizlemesi")).assertIsDisplayed()
        compose.onNodeWithContentDescription(tr("%1\$s renk", tr("Mavi"))).assertIsDisplayed()
    }

    @Test fun supplementalMessagesDoNotBlockEditingAndWaitWhileADialogIsOpen() {
        open()
        compose.runOnIdle { model().reportFilePickerUnavailable() }
        compose.onNodeWithText(tr("Tamam")).assertIsDisplayed()
        compose.runOnIdle { model().addText("Editing continues") }; await()
        compose.onNodeWithContentDescription(tr("Geri al")).assertIsEnabled().performClick(); await()
        compose.runOnIdle { assertTrue(model().page!!.elements.isEmpty()); assertTrue(view()!!.isEnabled) }
        appearance()
        compose.onNodeWithText(tr("Tamam")).assertDoesNotExist()
        compose.onNodeWithText(tr("Vazgeç")).performClick()
        compose.onNodeWithText(tr("Tamam")).assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(model().notice) }
    }

    @Test fun longPressTooltipExplainsAToolWithoutEditingThePage() {
        open()
        compose.onNodeWithContentDescription(tr("Kalem")).performTouchInput { longClick() }
        compose.onNodeWithText(tr("Kalem")).assertIsDisplayed()
        compose.runOnIdle { assertTrue(model().page!!.elements.isEmpty()) }
    }
}
