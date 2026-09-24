package xyz.hlovet.portal.prototype

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** One source of truth for the native app's visual language. */
internal object HlovetUi {
    val background = Color(0xFF0B111A)
    val backgroundBrush = Brush.verticalGradient(
        0f to Color(0xFF142432),
        0.28f to Color(0xFF0F1A24),
        0.62f to background,
        1f to background
    )
    val foreground = Color(0xFFF1F6FA)
    val muted = Color(0xFF9EB0BF)
    val accent = Color(0xFF73DCC5)
    val accentSoft = Color(0x5A2F9387)
    val secondaryAccent = Color(0xFFFFAD98)
    val glass = Color(0xA5162432)
    val glassRaised = Color(0xC5253647)
    val glassSubtle = Color(0x64253545)
    val glassBorder = Color(0x3D9AB2C1)
    val glassHighlight = Color(0x38FFFFFF)
    val contentPadding = PaddingValues(horizontal = 20.dp)
    val panelShape: Shape = RoundedCornerShape(16.dp)
    val compactShape: Shape = RoundedCornerShape(12.dp)
    val pillShape: Shape = RoundedCornerShape(10.dp)
    val panelBorder = BorderStroke(1.dp, glassBorder)
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = HlovetUi.panelShape,
    color: Color = HlovetUi.glass,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        border = HlovetUi.panelBorder,
        shadowElevation = 10.dp,
        content = content
    )
}
