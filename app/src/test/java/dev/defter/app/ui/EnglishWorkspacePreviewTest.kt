package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
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
import java.io.File

/** Native UI renders with clearly synthetic lesson content for human visual review. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "en-rUS-w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishWorkspacePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
    private fun await() {
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); !model().loading && !model().busy && !model().pageWindowLoading && model().pendingSaves == 0 }
        compose.runOnIdle { assertNull(model().error) }
    }
    private fun create(title: String, color: Int) {
        compose.runOnIdle { model().createNotebook(title, PaperTemplate.DOTS, color) }; await()
        compose.runOnIdle {
            model().changePage(model().page!!.copy(elements = listOf(
                TextElement(text = title, x = 88f, y = 96f, size = 44f, color = 0xff25324b.toInt()),
                TextElement(text = "STUDY NOTES  /  WEEK 04", x = 90f, y = 165f, size = 22f, color = 0xff69758a.toInt()),
                StrokeElement(points = listOf(InkPoint(90f, 275f), InkPoint(920f, 275f)), width = 45f, color = 0xffffd66b.toInt(), kind = InkKind.HIGHLIGHTER),
                TextElement(text = "Small steps make difficult ideas clear.", x = 90f, y = 245f, size = 30f, color = 0xff25324b.toInt()),
                ShapeElement(shape = ShapeKind.ARROW, startX = 180f, startY = 640f, endX = 1000f, endY = 640f, width = 3f, color = 0xff69758a.toInt()),
                ShapeElement(shape = ShapeKind.ARROW, startX = 180f, startY = 640f, endX = 180f, endY = 350f, width = 3f, color = 0xff69758a.toInt()),
                StrokeElement(points = (0..40).map { InkPoint(210f + it * 18f, 610f - it * it * .14f) }, width = 7f, color = color),
                TextElement(text = "RECALL & PRACTICE", x = 90f, y = 805f, size = 22f, color = color),
                TextElement(text = "1. Explain the idea in your own words.\n2. Mark the part you want to review.", x = 90f, y = 855f, size = 29f, color = 0xff45516a.toInt())
            )))
        }; await()
    }
    @Test fun englishTabletLibraryMapFocusAndDarkToolsRender() {
        await(); compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await()
        listOf("Calculus" to 0xff005ac1.toInt(), "Physics" to 0xff397d79.toInt(), "Linear algebra" to 0xffad6e48.toInt(), "Research ideas" to 0xff7560a9.toInt()).forEach { (title, color) ->
            create(title, color); compose.runOnIdle { model().closeNotebook() }; await()
        }
        compose.onNodeWithText("Calculus").assertIsDisplayed()
        compose.waitUntil(30_000) {
            model().library.notebooks.all { compose.onAllNodesWithTag("notebook-preview-${it.id}", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        }
        settleImages(); capture("library-tablet-en.png")
        compose.runOnIdle { model().openNotebook(model().library.notebooks.first { it.title == "Calculus" }.id) }; await()
        repeat(7) { compose.runOnIdle { model().addPage(PaperTemplate.DOTS) }; await() }
        compose.runOnIdle { model().selectPage(model().notebook!!.pageIds.first()) }; await()
        compose.onNodeWithContentDescription("Page map").performClick()
        settleImages(); capture("editor-map-tablet-en.png")
        compose.onNodeWithContentDescription("Focus mode").performClick()
        compose.onNodeWithTag("editor-toolbar").assertDoesNotExist()
        capture("focus-map-tablet-en.png")
        compose.onNodeWithContentDescription("Exit focus mode").performClick()
        compose.onNodeWithContentDescription("Close page map").performClick()
        compose.runOnIdle { model().updateUiPreferences(model().uiPreferences.copy(theme = ThemeMode.DARK)) }
        compose.onNodeWithContentDescription(tr("Çizgi silgisi")).performClick()
        compose.onNodeWithContentDescription(tr("Renk ve kalınlık")).performClick()
        capture("eraser-dark-tablet-en.png", popup = true)
    }
    @Test
    @Config(qualifiers = "en-rUS-w393dp-h851dp-port-mdpi")
    fun englishPhoneAndLargeTextAppearanceRemainReachable() {
        await(); compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await()
        create("Calculus", 0xff005ac1.toInt())
        capture("editor-phone-en.png")
        compose.onNodeWithContentDescription("Focus mode").performClick()
        capture("focus-phone-en.png")
        compose.onNodeWithContentDescription("Exit focus mode").performClick()
        compose.runOnIdle { org.robolectric.RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate(); await()
        try {
            compose.onNodeWithContentDescription(tr("Defter menüsü")).performClick()
            compose.onNodeWithText(tr("Görünüm")).performClick()
            compose.runOnIdle { assertTrue(compose.activity.resources.configuration.fontScale >= 1.4f) }
            compose.onNodeWithContentDescription(tr("Referans alanı")).performScrollTo().assertIsDisplayed()
            capture("appearance-phone-large-text-en.png", popup = true)
            compose.onNodeWithText(tr("Uygula")).assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Focus mode").assertIsDisplayed()
        } finally { compose.runOnIdle { org.robolectric.RuntimeEnvironment.setFontScale(1f) } }
    }
    private fun settleImages() {
        repeat(6) { Thread.sleep(150); compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
    }
    private fun capture(name: String, popup: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = if (popup) android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull {
                it !== compose.activity.window.decorView && it.isShown && it.width > 0 && it.height > 0
            } ?: compose.activity.window.decorView else compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) colors += bitmap.getPixel(x, y)
        assertTrue("A native rendered UI must have content", colors.size > 20)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
