package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** HLOVET tokens on top of the open-source Material 3 design system. */
internal object HlovetUi {
    val background = Color(0xFFF8FAF9)
    val surface = Color(0xFFFFFFFF)
    val surfaceLow = Color(0xFFF0F6F4)
    val surfaceAccent = Color(0xFFDCEFEB)
    val foreground = Color(0xFF172A31)
    val muted = Color(0xFF60767E)
    val accent = Color(0xFF0D756B)
    val accentSoft = Color(0xFFDCEFEB)
    val secondaryAccent = Color(0xFFB85C4A)
    val onAccent = Color(0xFFFFFFFF)
    val outline = Color(0xFFC9D8D8)
    val divider = Color(0xFFE4EBEA)
    val contentPadding = PaddingValues(horizontal = 20.dp)
    val cardShape = RoundedCornerShape(20.dp)
    val rowShape = RoundedCornerShape(16.dp)
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
