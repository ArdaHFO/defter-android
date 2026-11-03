package dev.defter.app.ui

import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.model.*
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

/** Exercises the shipped Compose screens, then captures their actual rendered pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DefterUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createNotebookEditAndReturnToLibrary() {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitModel(model, "initial library load") { !model.loading && !model.busy }
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitModel(model, "reset synthetic library") { !model.loading && !model.busy }
        compose.onNodeWithText("İlk defterini oluştur").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction() and hasText("Defter adı")).performTextInput("Lineer Cebir")
        compose.onNode(hasSetTextAction() and hasText("Defter adı")).assertTextContains("Lineer Cebir")
        compose.onNodeWithText("Defteri oluştur").assertIsEnabled().performClick()
        awaitModel(model, "create notebook from dialog") { model.page != null && !model.busy }
        compose.onNodeWithText("Lineer Cebir").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kalem").assertIsDisplayed()
        compose.onNodeWithContentDescription("Yeni sayfa").assertIsDisplayed()

        // Inject deterministic document content through the same edit/save boundary as the canvas.
        // The screenshot is a UI/render check, not evidence of physical pen latency or palm rejection.
        compose.runOnIdle {
            val current = requireNotNull(model.page)
            val dark = 0xFF293E37.toInt()
            val green = 0xFF397563.toInt()
            val blue = 0xFF3E6A98.toInt()
            model.changePage(current.copy(elements = listOf(
                TextElement(text = "Vektörler ve doğrusal dönüşümler", x = 100f, y = 98f, size = 42f, color = dark),
                StrokeElement(points = listOf(InkPoint(100f, 222f), InkPoint(790f, 222f)), color = 0xFFF4CA49.toInt(), width = 44f, kind = InkKind.HIGHLIGHTER),
                TextElement(text = "Bir vektörün yönünü ve uzunluğunu birlikte düşün.", x = 100f, y = 194f, size = 29f, color = dark),
                ShapeElement(shape = ShapeKind.ARROW, startX = 200f, startY = 720f, endX = 700f, endY = 720f, color = dark, width = 4f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 200f, startY = 720f, endX = 200f, endY = 350f, color = dark, width = 4f),
                ShapeElement(shape = ShapeKind.ARROW, startX = 200f, startY = 720f, endX = 600f, endY = 420f, color = green, width = 8f),
                TextElement(text = "v = (4, 3)", x = 490f, y = 350f, size = 34f, color = green),
                TextElement(text = "x", x = 720f, y = 690f, size = 30f, color = dark),
                TextElement(text = "y", x = 160f, y = 310f, size = 30f, color = dark),
                ShapeElement(shape = ShapeKind.RECTANGLE, startX = 100f, startY = 850f, endX = 1100f, endY = 1070f, color = blue, width = 3f),
                TextElement(text = "HATIRLA\nDoğrusal dönüşümler, toplama ve ölçeklemeyi korur.", x = 135f, y = 885f, size = 30f, color = blue),
                TextElement(text = "Çalışma planı", x = 100f, y = 1180f, size = 34f, color = dark),
                TextElement(text = "1. Temel örnekleri çöz\n2. Matris çarpımını tekrar et\n3. Kendi örneğini çiz", x = 110f, y = 1250f, size = 29f, color = dark),
                StrokeElement(points = listOf(InkPoint(104f, 1446f, .4f), InkPoint(180f, 1450f, .6f), InkPoint(295f, 1445f, .8f), InkPoint(390f, 1448f, .5f)), color = green, width = 6f)
            )))
        }
        awaitModel(model, "save sample document") { model.pendingSaves == 0 && !model.busy }
        compose.runOnIdle { assertFalse(model.unsaved); assertNull(model.error); assertTrue(model.canUndo) }
        compose.onNodeWithText("Cihazına kaydedildi").assertIsDisplayed()
        capture("editor-tablet.png")

        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        awaitModel(model, "return to library") { model.notebookId == null && !model.busy }
        compose.onNodeWithText("Kütüphanen").assertIsDisplayed()
        compose.onAllNodesWithText("Lineer Cebir").assertCountEquals(2)
        capture("library-tablet.png")

        // Reopen via the actual card. Content must have survived the save/reload boundary.
        compose.onAllNodesWithText("Lineer Cebir").onFirst().performClick()
        awaitModel(model, "reopen saved notebook") { model.notebookId != null && model.page != null && !model.busy }
        compose.runOnIdle {
            assertEquals(14, requireNotNull(model.page).elements.size)
            assertEquals("Vektörler ve doğrusal dönüşümler", (model.page!!.elements.first() as TextElement).text)
        }
    }

    private fun awaitModel(model: DefterViewModel, step: String, condition: () -> Boolean) {
        fun state() = "loading=${model.loading}, busy=${model.busy}, page=${model.page?.id}, " +
            "notebook=${model.notebookId}, pending=${model.pendingSaves}, error=${model.error}"
        try {
            compose.waitUntil(20_000) {
                // Compose's frame clock and Robolectric's PAUSED Android main looper are
                // separate queues. Repository IO posts its continuation to the latter.
                shadowOf(Looper.getMainLooper()).idle()
                model.error?.let { throw AssertionError("$step failed: ${state()}") }
                condition()
            }
        } catch (failure: Exception) {
            throw AssertionError("$step did not complete: ${state()}", failure)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // WindowCapture/PixelCopy waits for an asynchronous hardware redraw that the
        // PAUSED host looper cannot drive while capture blocks. Draw the real decor
        // tree synchronously with Robolectric's native graphics runtime instead.
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0) { "Activity has no laid-out window" }
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                decor.draw(Canvas(it))
            }
        }
        val sampledColors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 40)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 40)) {
                sampledColors.add(bitmap.getPixel(x, y))
            }
        }
        assertTrue("Actual UI capture must contain rendered controls and content", sampledColors.size > 8)
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { output ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
