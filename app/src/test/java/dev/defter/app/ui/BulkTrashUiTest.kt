package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.localization.TurkishTestApplication
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BulkTrashUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectAllClearCancelAndPermanentDeleteLeaveActiveNotebookIntact() {
        val model = fresh()
        val first = create(model, "Eski fizik")
        val second = create(model, "Eski kimya")
        val active = create(model, "Güncel notlar")
        compose.runOnIdle { model.trashNotebook(first, true) }; await(model)
        compose.runOnIdle { model.trashNotebook(second, true) }; await(model)
        compose.onNodeWithText("Çöp kutusu").performClick()
        compose.onNodeWithText("Seçilenleri sil").assertIsNotEnabled()
        compose.onNodeWithText("Tümünü seç").performClick()
        compose.onNodeWithText("2 seçildi").assertIsDisplayed()
        compose.onNodeWithText("Seçimi temizle").performClick()
        compose.onNodeWithText("0 seçildi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Silmek için seç: Eski fizik").performScrollTo().performClick()
        compose.onNodeWithText("1 seçildi").assertIsDisplayed()
        compose.onNodeWithText("Seçilenleri sil").performClick()
        compose.onNodeWithText("Kalıcı olarak silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.runOnIdle { assertEquals(3, model.library.notebooks.size) }
        compose.onNodeWithText("Tümünü seç").performClick()
        capture("bulk-trash-tablet.png")
        compose.onNodeWithText("Seçilenleri sil").performClick()
        compose.onNodeWithText("Kalıcı sil").performClick()
        await(model)
        compose.runOnIdle { assertEquals(listOf(active), model.library.notebooks.map { it.id }); assertNull(model.library.notebooks.single().deletedAt) }
        compose.onNodeWithText("Tamam").performClick()
        compose.onNodeWithText("Çöp kutusu boş").assertIsDisplayed()
        compose.onNodeWithText("Kütüphane").performClick()
        compose.onAllNodesWithText("Güncel notlar").onFirst().performClick()
        await(model)
        compose.runOnIdle { assertEquals(active, model.notebookId); assertNotNull(model.page) }
    }

    @Test @Config(application = TurkishTestApplication::class, qualifiers = "w393dp-h851dp-port-mdpi")
    fun phoneTrashSelectionSurvivesRecreationAndCancellingDoesNotDelete() {
        val model = fresh()
        val id = create(model, "Saklanacak not")
        compose.runOnIdle { model.trashNotebook(id, true) }; await(model)
        compose.onNodeWithContentDescription("Kütüphane menüsü").performClick()
        compose.onNodeWithText("Çöp kutusu").performClick()
        compose.onNodeWithText("Tümünü seç").performClick()
        compose.activityRule.scenario.recreate()
        await(model)
        compose.onNodeWithText("1 seçildi").assertIsDisplayed()
        compose.onNodeWithText("Seçilenleri sil").performScrollTo().performClick()
        compose.onNodeWithText("Kalıcı olarak silinsin mi?").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.runOnIdle { assertEquals(id, model.library.notebooks.single().id) }
        capture("bulk-trash-phone.png")
    }

    private fun fresh(): DefterViewModel {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        await(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }; await(model)
        return model
    }
    private fun create(model: DefterViewModel, name: String): String {
        compose.runOnIdle { model.createNotebook(name, PaperTemplate.DOTS, 0xFF5B50D6.toInt()) }; await(model)
        val id = model.notebookId!!
        compose.runOnIdle { model.closeNotebook() }; await(model)
        return id
    }
    private fun await(model: DefterViewModel) {
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); !model.loading && !model.busy && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { val decor = compose.activity.window.decorView; Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) } }
        val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
