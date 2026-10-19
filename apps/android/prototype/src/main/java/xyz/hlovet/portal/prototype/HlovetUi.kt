package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 基于 Material 3 集中维护站点外观令牌，保证原生页面与弹层使用一致的颜色和形状。 */
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
    // 弹窗和菜单使用独立窗口，无法依赖页面模糊保护文字对比度，因此设置最低背景不透明度。
    val dialogSurface: Color get() = glassFallback.copy(alpha = glassFallback.alpha.coerceAtLeast(.94f))
    val menuSurface: Color get() = dialogSurface
    val contentPadding = PaddingValues(horizontal = 20.dp)
    val cardShape = RoundedCornerShape(20.dp)
    val rowShape = RoundedCornerShape(16.dp)
    val menuShape = RoundedCornerShape(14.dp)
    val incomingMessageShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    val outgoingMessageShape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)

    /** The shared glass input palette used by public, account, and admin screens. */
    @Composable
    fun fieldColors(
        focusedBorderColor: Color = accent,
        unfocusedBorderColor: Color = outline,
        focusedLabelColor: Color = accent,
        unfocusedLabelColor: Color = muted,
        cursorColor: Color = accent,
        disabledContainerColor: Color = surfaceLow,
        disabledBorderColor: Color = outline.copy(alpha = .45f),
    ): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = glassFallback,
        unfocusedContainerColor = glassFallback,
        disabledContainerColor = disabledContainerColor,
        focusedBorderColor = focusedBorderColor,
        unfocusedBorderColor = unfocusedBorderColor,
        disabledBorderColor = disabledBorderColor,
        focusedLabelColor = focusedLabelColor,
        unfocusedLabelColor = unfocusedLabelColor,
        cursorColor = cursorColor,
    )

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
        glassTint = tintColor.copy(alpha = normalizedGlassTintAlpha(appearance.glassTintAlpha))
        glassFallback = surfaceColor.copy(alpha = (appearance.cardAlpha.coerceIn(38, 76) / 100f))
    }
}

/** Keep the site setting's opacity semantics, including a deliberately transparent value of 0. */
internal fun normalizedGlassTintAlpha(value: Int): Float = value.coerceIn(0, 100) / 100f

internal val LocalHlovetHaze = compositionLocalOf<HazeState?> { null }

@Composable
internal fun HlovetBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = HlovetUi.accentSoft,
    contentColor: Color = HlovetUi.accent,
    fontSize: TextUnit = 10.sp,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = containerColor, contentColor = contentColor) {
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shared checkbox treatment for compact forms and list rows. */
@Composable
internal fun HlovetCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = CheckboxDefaults.colors(
            checkedColor = HlovetUi.accent,
            uncheckedColor = HlovetUi.outline,
            checkmarkColor = HlovetUi.onAccent,
            disabledCheckedColor = HlovetUi.accent.copy(alpha = .38f),
            disabledUncheckedColor = HlovetUi.outline.copy(alpha = .38f),
            disabledIndeterminateColor = HlovetUi.outline.copy(alpha = .38f),
        ),
    )
}

/** Shared switch treatment for settings and administration toggles. */
@Composable
internal fun HlovetSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = HlovetUi.onAccent,
            checkedTrackColor = HlovetUi.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = HlovetUi.muted,
            uncheckedTrackColor = HlovetUi.surfaceLow,
            uncheckedBorderColor = HlovetUi.outline,
            disabledCheckedThumbColor = HlovetUi.onAccent.copy(alpha = .52f),
            disabledCheckedTrackColor = HlovetUi.accent.copy(alpha = .38f),
            disabledUncheckedThumbColor = HlovetUi.muted.copy(alpha = .38f),
            disabledUncheckedTrackColor = HlovetUi.surfaceLow.copy(alpha = .38f),
            disabledUncheckedBorderColor = HlovetUi.outline.copy(alpha = .38f),
        ),
    )
}

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
    containerColor: Color = HlovetUi.glassFallback,
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
        // Keep the configured surface alpha visible even when the device cannot
        // render the blur layer or the Haze implementation falls back.
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** Shared dialog surface so confirmation and form dialogs follow account appearance. */
@Composable
internal fun HlovetAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.imePadding(),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = HlovetUi.cardShape,
        containerColor = HlovetUi.dialogSurface,
        iconContentColor = HlovetUi.accent,
        titleContentColor = HlovetUi.foreground,
        textContentColor = HlovetUi.foreground,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/** Keep submission feedback visible in the active form, including screen readers. */
@Composable
internal fun HlovetFormFeedback(
    error: String?,
    notice: String? = null,
    modifier: Modifier = Modifier,
) {
    val message = error?.takeIf(String::isNotBlank) ?: notice?.takeIf(String::isNotBlank) ?: return
    Text(
        message,
        color = if (!error.isNullOrBlank()) MaterialTheme.colorScheme.error else HlovetUi.accent,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun parseColor(value: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(value))
} catch (_: IllegalArgumentException) {
    fallback
}

/** 为 content 应用当前账号的共享色板；选中组件同样读取站点外观，不回退到 Material 默认紫色。 */
@Composable
internal fun HlovetTheme(content: @Composable () -> Unit) {
    // FilterChip 等组件默认使用 secondaryContainer，必须与显式指定颜色的站内组件保持一致。
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = HlovetUi.accent,
            onPrimary = HlovetUi.onAccent,
            primaryContainer = HlovetUi.accentSoft,
            onPrimaryContainer = HlovetUi.foreground,
            secondary = HlovetUi.secondaryAccent,
            onSecondary = HlovetUi.onAccent,
            secondaryContainer = HlovetUi.accentSoft,
            onSecondaryContainer = HlovetUi.foreground,
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
