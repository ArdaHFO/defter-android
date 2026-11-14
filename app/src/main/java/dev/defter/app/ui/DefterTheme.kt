package dev.defter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.ContextWrapper
import dev.defter.app.model.ThemeMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.defter.app.R

/** Shared visual language for the library, writing desk, and audio workspace. */
object DefterDesign {
    val Accent = Color(0xFF005AC1)
    val AccentSoft = Color(0xFFE8F1FF)
    val Ink = Color(0xFF1C1C1E)
    val Muted = Color(0xFF60646C)
    val Canvas = Color(0xFFF5F5F7)
    val Workspace = Color(0xFFE9EBEF)
    val Surface = Color.White
    val Outline = Color(0xFFD8DAE0)
    val ControlOutline = Color(0xFF8B9099)
    val Mint = Color(0xFF247455)
    val MintSoft = Color(0xFFE8F5EC)
    val Brand = Color(0xFF193451)
    val ControlShape = RoundedCornerShape(12.dp)
    val CardShape = RoundedCornerShape(18.dp)
    val MinTouchTarget = 48.dp
    val Space = 8.dp

    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = ControlShape,
        large = CardShape,
        extraLarge = RoundedCornerShape(24.dp)
    )

    val Typography = Typography(
        displayLarge = type(48, 56, FontWeight.SemiBold, -0.8f),
        displayMedium = type(40, 48, FontWeight.SemiBold, -0.6f),
        displaySmall = type(34, 42, FontWeight.SemiBold, -0.4f),
        headlineLarge = type(30, 38, FontWeight.SemiBold, -0.4f),
        headlineMedium = type(26, 34, FontWeight.SemiBold, -0.3f),
        headlineSmall = type(23, 31, FontWeight.SemiBold, -0.2f),
        titleLarge = type(20, 28, FontWeight.SemiBold),
        titleMedium = type(17, 25, FontWeight.SemiBold),
        titleSmall = type(15, 22, FontWeight.SemiBold),
        bodyLarge = type(17, 26, FontWeight.Normal),
        bodyMedium = type(15, 23, FontWeight.Normal),
        bodySmall = type(13, 19, FontWeight.Normal),
        labelLarge = type(15, 22, FontWeight.Medium),
        labelMedium = type(13, 18, FontWeight.Medium),
        labelSmall = type(12, 17, FontWeight.Medium)
    )

    private fun type(size: Int, lineHeight: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp
    )
}

private val DefterColors = lightColorScheme(
    primary = DefterDesign.Accent,
    onPrimary = Color.White,
    primaryContainer = DefterDesign.AccentSoft,
    onPrimaryContainer = Color(0xFF064A9D),
    secondary = DefterDesign.Mint,
    onSecondary = Color.White,
    secondaryContainer = DefterDesign.MintSoft,
    onSecondaryContainer = Color(0xFF18583E),
    tertiary = Color(0xFF7E602E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7EEDC),
    onTertiaryContainer = Color(0xFF654B29),
    background = DefterDesign.Canvas,
    onBackground = DefterDesign.Ink,
    surface = Color.White,
    onSurface = DefterDesign.Ink,
    surfaceVariant = DefterDesign.Workspace,
    onSurfaceVariant = DefterDesign.Muted,
    surfaceTint = DefterDesign.Accent,
    surfaceDim = Color(0xFFE3E5E9),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = DefterDesign.Canvas,
    surfaceContainerHigh = Color(0xFFF0F1F4),
    surfaceContainerHighest = DefterDesign.Workspace,
    outline = DefterDesign.ControlOutline,
    outlineVariant = DefterDesign.Outline,
    inverseSurface = DefterDesign.Ink,
    inverseOnSurface = Color(0xFFF3F5FA),
    inversePrimary = Color(0xFFADCFFF),
    error = Color(0xFFB32632),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEF),
    onErrorContainer = Color(0xFF6B2430),
    scrim = DefterDesign.Ink
)

private val DefterDarkColors = darkColorScheme(
    primary = Color(0xFFA9CAFF), onPrimary = Color(0xFF07315F),
    primaryContainer = Color(0xFF173C68), onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF9CD5BB), onSecondary = Color(0xFF123C2E),
    secondaryContainer = Color(0xFF224B3B), onSecondaryContainer = Color(0xFFBDEED4),
    background = Color(0xFF111419), onBackground = Color(0xFFE5E9F1),
    surface = Color(0xFF191D24), onSurface = Color(0xFFE5E9F1),
    surfaceVariant = Color(0xFF242A34), onSurfaceVariant = Color(0xFFB6BFCD),
    surfaceContainerLowest = Color(0xFF0D1015), surfaceContainerLow = Color(0xFF151920),
    surfaceContainer = Color(0xFF20252E), surfaceContainerHigh = Color(0xFF2B323E),
    surfaceContainerHighest = Color(0xFF353E4C),
    outline = Color(0xFF8C98AA), outlineVariant = Color(0xFF465160),
    error = Color(0xFFFFB4BA), onError = Color(0xFF641B27),
    errorContainer = Color(0xFF7D2938), onErrorContainer = Color(0xFFFFDADC),
    inverseSurface = Color(0xFFE5E9F1), inverseOnSurface = Color(0xFF242A34), inversePrimary = DefterDesign.Accent
)

@Composable
fun DefterTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val animationsEnabled = rememberDefterAnimationsEnabled()
    CompositionLocalProvider(LocalDefterAnimationsEnabled provides animationsEnabled) {
        MaterialTheme(
            colorScheme = if (dark) DefterDarkColors else DefterColors,
            typography = DefterDesign.Typography,
            shapes = DefterDesign.Shapes,
            content = content
        )
    }
}

/** Decorative mark. The adjacent Defter wordmark supplies its accessible name. */
@Composable
fun DefterBrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_defter_brand),
        contentDescription = null,
        modifier = modifier.size(44.dp)
    )
}
