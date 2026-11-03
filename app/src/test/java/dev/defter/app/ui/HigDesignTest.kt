package dev.defter.app.ui

import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.defter.app.localization.FreshInstallApplication
import dev.defter.app.localization.tr
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
@Config(application = FreshInstallApplication::class, sdk = [33], qualifiers = "en-rUS-w393dp-h851dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HigDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextTabKeepsSelectionAndCloseAsSeparateFullSizeActions() {
        var selections = 0
        var closes = 0
        val title = "A long notebook title for revision"
        compose.setContent {
            DefterTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.fillMaxSize()) {
                        DefterNotebookTab(title, true, true, { selections++ }, { closes++ })
                    }
                }
            }
        }
        val select = compose.onNodeWithContentDescription(tr("Sekme %1\$s", title)).assertIsDisplayed().assertIsSelected()
        val close = compose.onNodeWithContentDescription(tr("%1\$s sekmesini kapat", title)).assertIsDisplayed()
        val selectBounds = select.fetchSemanticsNode().boundsInRoot
        val closeBounds = close.fetchSemanticsNode().boundsInRoot
        assertTrue(selectBounds.width >= 48f && selectBounds.height >= 48f)
        assertTrue(closeBounds.width >= 48f && closeBounds.height >= 48f)
        assertTrue("Closing must not overlap the tab selection target", closeBounds.left >= selectBounds.right)
        assertTrue(closeBounds.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
        select.performClick()
        compose.runOnIdle { assertEquals(1, selections); assertEquals(0, closes) }
        close.performClick()
        compose.runOnIdle { assertEquals(1, selections); assertEquals(1, closes) }
    }

    @Test fun narrowLargeTextPlaybackKeepsTimeStatusAndBothActionsVisible() {
        var plays = 0
        var speeds = 0
        compose.setContent {
            DefterTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.width(280.dp)) {
                            DefterPlaybackHeader("1:23 / 45:10", "Stored on your device", 1.25f, false, true, { plays++ }, { speeds++ })
                        }
                    }
                }
            }
        }
        val time = compose.onNodeWithText("1:23 / 45:10").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("Stored on your device").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription(tr("Sesi oynat veya duraklat")).assertIsDisplayed()
        val speed = compose.onNodeWithText("1.25×").assertIsDisplayed()
        val speedBounds = speed.fetchSemanticsNode().boundsInRoot
        assertTrue("Speed belongs below the expanded content", speedBounds.top >= time.bottom && speedBounds.top >= status.bottom)
        for (node in listOf(play, speed)) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
            assertTrue(bounds.left >= 0f && bounds.right <= 280f)
        }
        play.performClick(); speed.performClick()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, speeds) }
    }

    @Test fun customMotionObservesDisabledSystemAnimationsWithoutDoubleScaling() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getString(resolver, key)
        var duration = -1
        try {
            Settings.Global.putFloat(resolver, key, 0f)
            compose.setContent { DefterTheme { duration = defterTween<Float>(160).durationMillis } }
            compose.runOnIdle { assertEquals(0, duration) }
            compose.runOnIdle {
                Settings.Global.putFloat(resolver, key, 1.5f)
                resolver.notifyChange(Settings.Global.getUriFor(key), null)
                shadowOf(Looper.getMainLooper()).idle()
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Compose applies the platform multiplier itself", 160, duration) }
            compose.runOnIdle {
                Settings.Global.putFloat(resolver, key, 0f)
                resolver.notifyChange(Settings.Global.getUriFor(key), null)
                shadowOf(Looper.getMainLooper()).idle()
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(0, duration) }
        } finally {
            Settings.Global.putString(resolver, key, previous)
        }
    }

    @Test fun primaryAndSecondaryTextKeepReadableContrastOnAllSharedSurfaces() {
        for (background in listOf(DefterDesign.Surface, DefterDesign.Canvas, DefterDesign.Workspace, DefterDesign.AccentSoft)) {
            for (foreground in listOf(DefterDesign.Ink, DefterDesign.Muted, DefterDesign.Accent)) {
                assertTrue("Small text needs 4.5:1 contrast", contrast(foreground, background) >= 4.5f)
            }
        }
        assertTrue(contrast(Color.White, DefterDesign.Accent) >= 4.5f)
        assertTrue("Control outlines need a distinguishable edge", contrast(DefterDesign.ControlOutline, Color.White) >= 3f)
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
