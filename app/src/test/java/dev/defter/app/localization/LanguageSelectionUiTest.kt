package dev.defter.app.localization

import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.model.PaperTemplate
import dev.defter.app.model.TextElement
import dev.defter.app.ui.resetSyntheticLibrary
import java.io.File
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
@Config(sdk = [33], qualifiers = "tr-rTR-w1280dp-h900dp-land-mdpi", application = FreshInstallApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun languageChooserStartsInEnglishAndPersistsTurkishAcrossRecreation() {
        awaitIdle()
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle()
        compose.onNodeWithContentDescription("App language").assertIsDisplayed()
        capture("english-library.png")
        val userText = "İşaret = bookmark\nDo not translate my notes."
        compose.runOnIdle { notes().createNotebook("İntegral · My notes", PaperTemplate.LINED, 0xFF7162C9.toInt()) }
        awaitIdle()
        compose.runOnIdle { notes().addText(userText) }
        awaitIdle()
        val pageId = notes().page!!.id
        compose.onNodeWithContentDescription("Pen").assertIsDisplayed()
        capture("english-editor.png")
        compose.onNodeWithContentDescription("Notebook menu").performClick()
        compose.onNodeWithText("App language").performClick()
        compose.onNodeWithText("App language").assertIsDisplayed()
        compose.onNodeWithText("Türkçe").performClick()
        compose.onNodeWithText("Apply").performClick()
        awaitIdle()
        compose.onNodeWithContentDescription("Kalem").assertIsDisplayed()
        assertEquals("tr", AppLanguage.tag(compose.activity))
        assertEquals(pageId, notes().page!!.id)
        assertEquals(userText, notes().page!!.elements.filterIsInstance<TextElement>().single().text)
        compose.activityRule.scenario.recreate()
        awaitIdle()
        compose.onNodeWithContentDescription("Defter menüsü").performClick()
        compose.onNodeWithText("Uygulama dili").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Uygula").performClick()
        awaitIdle()
        compose.onNodeWithContentDescription("Pen").assertIsDisplayed()
        assertEquals("en", AppLanguage.tag(compose.activity))
        assertEquals(pageId, notes().page!!.id)
        assertEquals("İntegral · My notes", notes().notebook!!.title)
        assertEquals(userText, notes().page!!.elements.filterIsInstance<TextElement>().single().text)
    }

    private fun notes() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]

    @Test fun backgroundEditorRecreatesOnResumeAfterAnotherActivityChangesLanguage() {
        awaitIdle()
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle()
        compose.runOnIdle { notes().createNotebook("Back stack notes", PaperTemplate.DOTS, 0xFF7162C9.toInt()) }
        awaitIdle()
        val userText = "İntegral notes stay unchanged."
        compose.runOnIdle { notes().addText(userText) }
        awaitIdle()
        val original = compose.activity
        val model = notes()
        val pageId = model.page!!.id
        assertEquals("en", original.resources.configuration.locales[0].language)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Equivalent to another share-target Activity saving its language preference.
        AppLanguage.set(original.application, "tr")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            runCatching { compose.activity !== original && compose.activity.resources.configuration.locales[0].language == "tr" }.getOrDefault(false)
        }
        awaitIdle()
        assertSame(model, notes())
        assertEquals(pageId, notes().page!!.id)
        assertEquals(userText, notes().page!!.elements.filterIsInstance<TextElement>().single().text)
        assertEquals("Back stack notes", notes().notebook!!.title)
        compose.onNodeWithContentDescription("Kalem").assertIsDisplayed()
    }

    private fun awaitIdle() {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            val notes = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
            val audio = ViewModelProvider(compose.activity)[AudioViewModel::class.java]
            !notes.loading && !notes.busy && notes.pendingSaves == 0 && !audio.busy
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) colors += bitmap.getPixel(x, y)
        assertTrue("Capture must contain real rendered content", colors.size > 12)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
