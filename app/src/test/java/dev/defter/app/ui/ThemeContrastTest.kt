package dev.defter.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.ColorUtils
import dev.defter.app.model.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()
    @Test fun darkThemeKeepsTextAndActiveToolIndicatorsReadable() = check(ThemeMode.DARK)
    @Test fun lightThemeKeepsTextAndActiveToolIndicatorsReadable() = check(ThemeMode.LIGHT)
    private fun check(mode: ThemeMode) {
        lateinit var colors: ColorScheme
        compose.setContent { DefterTheme(mode) { val value = MaterialTheme.colorScheme; SideEffect { colors = value }; Box { } } }
        compose.runOnIdle {
            fun contrast(text: Color, background: Color, minimum: Double) = assertTrue("Contrast ${ColorUtils.calculateContrast(text.toArgb(), background.toArgb())} < $minimum", ColorUtils.calculateContrast(text.toArgb(), background.toArgb()) >= minimum)
            for (background in listOf(colors.background, colors.surface, colors.surfaceContainer, colors.surfaceContainerHigh)) {
                contrast(colors.onSurface, background, 4.5); contrast(colors.onSurfaceVariant, background, 4.5)
                contrast(colors.primary, background, 3.0)
            }
            contrast(colors.onPrimaryContainer, colors.primaryContainer, 4.5)
            contrast(colors.onSecondaryContainer, colors.secondaryContainer, 4.5)
        }
    }
}
