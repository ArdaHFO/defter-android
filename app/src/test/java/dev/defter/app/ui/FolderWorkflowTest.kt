package dev.defter.app.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
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
@Config(application = dev.defter.app.localization.TurkishTestApplication::class, sdk = [33], qualifiers = "w1280dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FolderWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nestedFoldersKeepMultipleNotebooksInTheirChosenLocation() {
        val model = ViewModelProvider(compose.activity)[DefterViewModel::class.java]
        awaitIdle(model)
        compose.runOnIdle { resetSyntheticLibrary(compose.activity) }
        awaitIdle(model)

        createFolder(model, "Dersler")
        val parentId = compose.runOnIdle {
            model.library.folders.single().also { assertNull(it.parentId) }.id
        }
        compose.onNodeWithText("Dersler").performClick()
        compose.runOnIdle { assertEquals(parentId, model.folderId) }
        createNotebookAndReturn(model, "Genel notlar", parentId)

        createFolder(model, "Matematik")
        val childId = compose.runOnIdle {
            model.library.folders.single { it.name == "Matematik" }
                .also { assertEquals(parentId, it.parentId) }.id
        }
        compose.onNodeWithText("Matematik").performClick()
        compose.runOnIdle { assertEquals(childId, model.folderId) }
        compose.onAllNodesWithText("Genel notlar").assertCountEquals(0)
        createNotebookAndReturn(model, "Türev", childId)
        createNotebookAndReturn(model, "İntegral", childId)
        compose.onAllNodesWithText("Türev").assertCountEquals(2)
        compose.onAllNodesWithText("İntegral").assertCountEquals(2)
        compose.onNodeWithText("2 defter · 0 klasör").assertIsDisplayed()

        compose.onNodeWithContentDescription("Üst klasör").performClick()
        compose.runOnIdle { assertEquals(parentId, model.folderId) }
        compose.onAllNodesWithText("Genel notlar").assertCountEquals(2)
        compose.onAllNodesWithText("Türev").assertCountEquals(0)
        compose.onAllNodesWithText("İntegral").assertCountEquals(0)
        compose.onNodeWithText("Matematik").assertIsDisplayed()
        compose.onNodeWithText("1 defter · 1 klasör").assertIsDisplayed()

        compose.onNodeWithContentDescription("Üst klasör").performClick()
        compose.runOnIdle { assertNull(model.folderId) }
        compose.onNodeWithText("Kütüphanen").assertIsDisplayed()
        compose.onNodeWithText("Dersler").assertIsDisplayed()
        compose.onNodeWithText("0 defter · 1 klasör").assertIsDisplayed()
        compose.onAllNodesWithText("Genel notlar").assertCountEquals(0)
        compose.onAllNodesWithText("Türev").assertCountEquals(0)
        compose.onAllNodesWithText("İntegral").assertCountEquals(0)
        compose.onAllNodesWithText("Matematik").assertCountEquals(0)

        // Open the actual saved notebook card after leaving and revisiting its folder.
        compose.onNodeWithText("Dersler").performClick()
        compose.onNodeWithText("Matematik").performClick()
        compose.onAllNodesWithText("Türev").onFirst().performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertEquals(childId, model.notebook!!.folderId)
            assertEquals("Türev", model.notebook!!.title)
            assertNotNull(model.page)
            assertEquals(3, model.library.notebooks.size)
        }
    }

    private fun createFolder(model: DefterViewModel, name: String) {
        compose.onNodeWithContentDescription("Yeni klasör").performClick()
        compose.onNode(hasSetTextAction() and hasText("Klasör adı")).performTextInput(name)
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        awaitIdle(model)
    }

    private fun createNotebookAndReturn(model: DefterViewModel, title: String, folderId: String) {
        compose.onNodeWithText("Yeni defter").performClick()
        compose.onNode(hasSetTextAction() and hasText("Defter adı")).performTextInput(title)
        compose.onNodeWithText("Defteri oluştur").assertIsEnabled().performClick()
        awaitIdle(model)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(folderId, model.notebook!!.folderId)
            assertEquals(1, model.notebook!!.pageIds.size)
            assertNotNull(model.page)
        }
        compose.onNodeWithContentDescription("Kütüphaneye dön").performClick()
        awaitIdle(model)
        compose.runOnIdle {
            assertNull(model.notebookId)
            assertEquals(folderId, model.folderId)
            assertEquals(folderId, model.library.notebooks.single { it.title == title }.folderId)
        }
    }

    private fun awaitIdle(model: DefterViewModel) {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.error?.let { throw AssertionError(it) }
            !model.loading && !model.busy && model.pendingSaves == 0
        }
        compose.runOnIdle { assertNull(model.error) }
    }
}
