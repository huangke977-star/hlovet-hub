package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** HLOVET tokens on top of the open-source Material 3 design system. */
internal object HlovetUi {
    var background by mutableStateOf(Color(0xFFEEF8FF))
    var surface by mutableStateOf(Color(0xFFFFFFFF))
    var surfaceLow by mutableStateOf(Color(0xFFEAF4FA))
    var surfaceAccent by mutableStateOf(Color(0xFFD6EFFF))
    var foreground by mutableStateOf(Color(0xFF1F2937))
    var muted by mutableStateOf(Color(0xFF52616F))
    var accent by mutableStateOf(Color(0xFF0284C7))
    var accentSoft by mutableStateOf(Color(0xFF0284C7).copy(alpha = .12f))
    var secondaryAccent by mutableStateOf(Color(0xFFB85C4A))
    var onAccent by mutableStateOf(Color(0xFFFFFFFF))
    var outline by mutableStateOf(Color(0xFFC9D8E2))
    var divider by mutableStateOf(Color(0xFF52616F).copy(alpha = .16f))
    var glassTint by mutableStateOf(Color(0xFFFFF3F6).copy(alpha = .46f))
    var glassFallback by mutableStateOf(Color.White.copy(alpha = .50f))
    var glassBlur by mutableStateOf(18)
    val contentPadding = PaddingValues(horizontal = 20.dp)
    val cardShape = RoundedCornerShape(20.dp)
    val rowShape = RoundedCornerShape(16.dp)

    fun applyAppearance(appearance: PreviewAppearance) {
        val accentColor = parseColor(appearance.accent, accent)
        val foregroundColor = parseColor(appearance.foreground, foreground)
        val mutedColor = parseColor(appearance.muted, muted)
        val surfaceColor = parseColor(appearance.surface, surface)
        val tintColor = parseColor(appearance.glassTint, glassTint)
        background = when (appearance.themeId) {
            "sakura-mist" -> Color(0xFFFFF3F6)
            "night-purple" -> Color(0xFFE7E5EF)
            "custom" -> Color(0xFFDCE8EE)
            else -> Color(0xFFEEF8FF)
        }
        surface = surfaceColor
        surfaceLow = surfaceColor.copy(alpha = .36f)
        surfaceAccent = accentColor.copy(alpha = .12f)
        foreground = foregroundColor
        muted = mutedColor
        accent = accentColor
        accentSoft = accentColor.copy(alpha = .12f)
        secondaryAccent = when (appearance.themeId) {
            "sakura-mist" -> Color(0xFFB85C79)
            "night-purple" -> Color(0xFF8B7BE8)
            else -> Color(0xFFB85C4A)
        }
        divider = mutedColor.copy(alpha = .16f)
        outline = mutedColor.copy(alpha = .24f)
        glassBlur = appearance.glassBlur.coerceIn(0, 36)
        glassTint = tintColor.copy(alpha = (appearance.glassTintAlpha.coerceIn(0, 100) / 100f).coerceAtLeast(.12f))
        glassFallback = surfaceColor.copy(alpha = (appearance.cardAlpha.coerceIn(38, 76) / 100f))
    }
}

internal val LocalHlovetHaze = compositionLocalOf<HazeState?> { null }

private fun glassStyle(): HazeStyle = HazeStyle(
    backgroundColor = HlovetUi.glassFallback,
    tints = listOf(HazeTint(HlovetUi.glassTint)),
    blurRadius = HlovetUi.glassBlur.dp,
    noiseFactor = HazeDefaults.noiseFactor,
    fallbackTint = HazeTint(HlovetUi.glassFallback)
)

internal fun navigationGlassStyle(): HazeStyle = HazeStyle(
    backgroundColor = HlovetUi.glassFallback,
    tints = listOf(HazeTint(HlovetUi.glassTint.copy(alpha = .62f))),
    blurRadius = (HlovetUi.glassBlur.coerceAtLeast(1) - 2).dp,
    noiseFactor = HazeDefaults.noiseFactor,
    fallbackTint = HazeTint(HlovetUi.glassFallback)
)

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = HlovetUi.cardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    val hazeState = LocalHlovetHaze.current
    Card(
        modifier = if (hazeState != null) {
            modifier.clip(shape).hazeEffect(state = hazeState, style = glassStyle())
        } else {
            modifier
        },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

private fun parseColor(value: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: IllegalArgumentException) {
    fallback
}

@Composable
internal fun HlovetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = HlovetUi.accent,
            onPrimary = HlovetUi.onAccent,
            primaryContainer = HlovetUi.accentSoft,
            onPrimaryContainer = HlovetUi.foreground,
            secondary = HlovetUi.secondaryAccent,
            onSecondary = HlovetUi.onAccent,
            background = HlovetUi.background,
            onBackground = HlovetUi.foreground,
            surface = HlovetUi.surface,
            onSurface = HlovetUi.foreground,
            surfaceVariant = HlovetUi.surfaceLow,
            onSurfaceVariant = HlovetUi.muted,
            outline = HlovetUi.outline
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
