package dev.defter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.defter.app.R

/** Shared visual language for the library, writing desk, and audio workspace. */
object DefterDesign {
    val Accent = Color(0xFF5B50D6)
    val AccentSoft = Color(0xFFEFEDFC)
    val Ink = Color(0xFF19243A)
    val Muted = Color(0xFF68758A)
    val Canvas = Color(0xFFF5F6FA)
    val Workspace = Color(0xFFEAEFF5)
    val Outline = Color(0xFFE2E7F0)
    val Mint = Color(0xFF337C71)
    val MintSoft = Color(0xFFE3F3EE)
    val Brand = Color(0xFF30295F)

    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(32.dp)
    )

    val Typography = Typography(
        displayLarge = type(54, 62, FontWeight.SemiBold, -1.2f),
        displayMedium = type(44, 52, FontWeight.SemiBold, -0.8f),
        displaySmall = type(36, 44, FontWeight.SemiBold, -0.6f),
        headlineLarge = type(32, 40, FontWeight.SemiBold, -0.6f),
        headlineMedium = type(28, 36, FontWeight.SemiBold, -0.4f),
        headlineSmall = type(24, 32, FontWeight.SemiBold, -0.3f),
        titleLarge = type(21, 28, FontWeight.SemiBold, -0.2f),
        titleMedium = type(16, 24, FontWeight.SemiBold),
        titleSmall = type(14, 20, FontWeight.SemiBold),
        bodyLarge = type(16, 25, FontWeight.Normal),
        bodyMedium = type(14, 22, FontWeight.Normal),
        bodySmall = type(12, 18, FontWeight.Normal, 0.1f),
        labelLarge = type(14, 20, FontWeight.SemiBold, 0.1f),
        labelMedium = type(12, 17, FontWeight.Medium, 0.2f),
        labelSmall = type(11, 16, FontWeight.Medium, 0.3f)
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
    onPrimaryContainer = Color(0xFF342B87),
    secondary = DefterDesign.Mint,
    onSecondary = Color.White,
    secondaryContainer = DefterDesign.MintSoft,
    onSecondaryContainer = Color(0xFF245C54),
    tertiary = Color(0xFF8E6E43),
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
    surfaceDim = Color(0xFFE6E9F1),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFD),
    surfaceContainer = DefterDesign.Canvas,
    surfaceContainerHigh = Color(0xFFF0F1F7),
    surfaceContainerHighest = DefterDesign.Workspace,
    outline = DefterDesign.Outline,
    outlineVariant = Color(0xFFEDF0F5),
    inverseSurface = DefterDesign.Ink,
    inverseOnSurface = Color(0xFFF3F5FA),
    inversePrimary = Color(0xFFC5BEFF),
    error = Color(0xFFB63F53),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEF),
    onErrorContainer = Color(0xFF6B2430),
    scrim = DefterDesign.Ink
)

@Composable
fun DefterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DefterColors,
        typography = DefterDesign.Typography,
        shapes = DefterDesign.Shapes,
        content = content
    )
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
