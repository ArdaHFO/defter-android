package dev.defter.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProEditorDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eraserModeAndFilterCanBeChosenIndependently() {
        var config by mutableStateOf(DrawingConfig())
        compose.setContent { MaterialTheme { EraserSettingsDialog(config, { config = it }, {}) } }
        compose.onNodeWithText("Yalnız dokunulan bölüm").performClick()
        compose.onNodeWithText("Yalnız fosforlu").performClick()
        compose.runOnIdle {
            assertEquals(EraserMode.PARTIAL, config.eraserMode)
            assertEquals(EraserFilter.HIGHLIGHTER, config.eraserFilter)
        }
        compose.onNodeWithText("Çizginin tamamı").performClick()
        compose.runOnIdle {
            assertEquals(EraserMode.STROKE, config.eraserMode)
            assertEquals(EraserFilter.HIGHLIGHTER, config.eraserFilter)
        }
    }

    @Test fun geometryControlsEnableSnappingAndSelectAngle() {
        var config by mutableStateOf(DrawingConfig())
        compose.setContent { MaterialTheme { GeometrySettingsDialog(config, { config = it }, {}) } }
        compose.onNodeWithContentDescription("Şekil hizalama").performClick()
        compose.onNodeWithText("45°").performClick()
        compose.runOnIdle { assertTrue(config.shapeSnap); assertEquals(45f, config.snapAngleDegrees, 0f) }
    }

    @Test fun cropSliderCannotCrossOppositeEdgeAndResetRestoresFullImage() {
        val image = ImageElement(assetId = "image", x = 0f, y = 0f, width = 100f, height = 100f, cropLeft = .2f, cropRight = .8f)
        var applied: List<Float>? = null
        compose.setContent { MaterialTheme { ImageCropDialog(image, { l, t, r, b -> applied = listOf(l, t, r, b) }, {}) } }
        compose.onNodeWithContentDescription("Sol kırpma sınırı").performSemanticsAction(SemanticsActions.SetProgress) { it(.95f) }
        compose.onNodeWithText("Uygula").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(applied!![0] < applied!![2])
            assertTrue(applied!![2] - applied!![0] >= .0099f)
        }
        compose.onNodeWithText("Tam görsele dön").performClick()
        compose.onNodeWithText("Uygula").performClick()
        compose.runOnIdle { assertEquals(listOf(0f, 0f, 1f, 1f), applied) }
    }

    @Test fun editingTextPreservesAlphaAndDisablesInvalidColor() {
        val original = TextElement(text = "Before", x = 0f, y = 0f, color = 0x80334455.toInt())
        var saved: Triple<String, Float, Int>? = null
        compose.setContent { MaterialTheme { EditTextDialog(original, { t, s, c -> saved = Triple(t, s, c) }, {}) } }
        compose.onNode(hasSetTextAction() and hasText("Metin")).performTextReplacement("After")
        val color = compose.onNode(hasSetTextAction() and hasText("Renk kodu"))
        color.performTextReplacement("ABC")
        compose.onNodeWithText("Kaydet").assertIsNotEnabled()
        color.performTextReplacement("445566")
        compose.onNodeWithText("Kaydet").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("After", saved!!.first)
            assertEquals(0x80445566.toInt(), saved!!.third)
        }
    }
}
