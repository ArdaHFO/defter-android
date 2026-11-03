package dev.defter.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import dev.defter.app.DefterViewModel
import dev.defter.app.MainActivity
import dev.defter.app.localization.FreshInstallApplication
import dev.defter.app.localization.tr
import dev.defter.app.model.PaperTemplate
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "en-rUS-w320dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ResponsiveWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun narrowLargeTextFooterKeepsEveryActionVisibleAndNavigationWorks() = withLargeText {
        val model = model()
        compose.runOnIdle { model.createNotebook("Large text navigation", PaperTemplate.DOTS, 0xFF5488AA.toInt()) }
        awaitIdle()
        repeat(2) {
            compose.runOnIdle { model.addPage(PaperTemplate.DOTS) }
            awaitIdle()
        }
        val previous = compose.onNodeWithContentDescription(tr("Önceki sayfa"))
        val next = compose.onNodeWithContentDescription(tr("Sonraki sayfa"))
        val pages = compose.onNodeWithContentDescription(tr("Sayfaları göster"))
        val add = compose.onNodeWithContentDescription(tr("Yeni sayfa"))
        val zoom = compose.onNode(hasText("%", substring = true))
        for (action in listOf(previous, next, pages, add, zoom)) assertWholeTouchTargetVisible(action)
        assertTrue("Page navigation must move below the first footer row at large text",
            pages.fetchSemanticsNode().boundsInRoot.top >= add.fetchSemanticsNode().boundsInRoot.bottom)
        capture("responsive-phone-2x.png")

        previous.performClick()
        awaitIdle()
        compose.runOnIdle { assertEquals(1, model.pageIndex) }
        next.performClick()
        awaitIdle()
        compose.runOnIdle { assertEquals(2, model.pageIndex) }
        add.performClick()
        awaitIdle()
        compose.runOnIdle { assertEquals(4, model.notebook!!.pageIds.size) }
        assertWholeTouchTargetVisible(add)
    }

    @Test
    @Config(application = FreshInstallApplication::class, qualifiers = "en-rUS-w900dp-h600dp-land-mdpi")
    fun shortLargeTextTabletCanReachLowerSidebarActionsAndReturnToTop() = withLargeText {
        val restore = compose.onNodeWithText(tr("Yedekten yükle"))
        restore.performScrollTo()
        assertWholeTouchTargetVisible(restore)
        val firstNotebook = compose.onNodeWithText(tr("İlk defterini oluştur"))
        firstNotebook.performScrollTo()
        assertWholeTouchTargetVisible(firstNotebook)
        capture("responsive-tablet-2x.png")
        firstNotebook.performClick()
        compose.onNodeWithText(tr("Defteri oluştur")).assertIsDisplayed()
        compose.onNodeWithText(tr("Vazgeç")).performClick()
        val backup = compose.onNodeWithText(tr("Yedek al"))
        backup.performScrollTo()
        assertWholeTouchTargetVisible(backup)
        val handwriting = compose.onNodeWithText(tr("El yazısı ve arama"))
        handwriting.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(tr("El yazısı ve arama"), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) assertTrue("Large-text sidebar must fit the word Handwriting without splitting it",
            layout.getLineEnd(0, visibleEnd = true) >= "Handwriting".length)
        handwriting.performClick()
        compose.onNodeWithText(tr("Dil modelini indir")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(tr("Kapat")).performClick()
        val library = compose.onNodeWithText(tr("Kütüphane"))
        library.performScrollTo()
        assertWholeTouchTargetVisible(library)
        library.performClick()
        compose.runOnIdle { assertNull(model().folderId) }
    }

    private fun withLargeText(block: () -> Unit) {
        // The fixture uses the same English system/app locale so Robolectric's global
        // configuration update does not replace a different per-Activity locale.
        compose.runOnIdle { RuntimeEnvironment.setFontScale(2f) }
        compose.activityRule.scenario.recreate()
        try {
            awaitIdle()
            compose.runOnIdle {
                assertEquals(2f, compose.activity.resources.configuration.fontScale, 0f)
                resetSyntheticLibrary(compose.activity)
            }
            awaitIdle()
            block()
        } finally {
            compose.runOnIdle { RuntimeEnvironment.setFontScale(1f) }
        }
    }

    private fun assertWholeTouchTargetVisible(node: SemanticsNodeInteraction) {
        val bounds = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Action must retain a full 48dp target: $bounds", bounds.width >= 48f && bounds.height >= 48f)
        assertTrue("Whole action must fit the viewport: $bounds in $root",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom)
    }

    private fun model() = ViewModelProvider(compose.activity)[DefterViewModel::class.java]

    private fun capture(name: String) {
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try {
            val directory = File(System.getProperty("defter.screenshotDir") ?: "build/verification").apply { mkdirs() }
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitIdle() {
        compose.waitUntil(20_000) {
            shadowOf(Looper.getMainLooper()).idle()
            val model = model()
            !model.loading && !model.busy && model.pendingSaves == 0 && !model.pageWindowLoading
        }
        compose.runOnIdle { assertNull(model().error); assertFalse(model().unsaved) }
    }
}
