package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun normalizeNativeVerificationCode(raw: String, allowLetters: Boolean): String =
    raw.filter { it in '0'..'9' || (allowLetters && (it in 'A'..'Z' || it in 'a'..'z')) }
        .take(6).uppercase(java.util.Locale.ROOT)

/** One editable value keeps paste, backspace and accessibility consistent across six cells. */
@Composable
internal fun HlovetCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    allowLetters: Boolean = false,
    modifier: Modifier = Modifier,
    label: String = nativePhrase("6 位验证码", "6-digit code"),
    onComplete: ((String) -> Unit)? = null,
) {
    var editingValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentValue = if (editingValue.text == value) editingValue else TextFieldValue(value, TextRange(value.length))
    val activeIndex = currentValue.selection.start.coerceAtMost(5)

    BasicTextField(
        value = currentValue,
        onValueChange = { raw ->
            val next = normalizeNativeVerificationCode(raw.text, allowLetters)
            val cursor = normalizeNativeVerificationCode(raw.text.take(raw.selection.start), allowLetters).length
            editingValue = TextFieldValue(next, TextRange(cursor.coerceAtMost(next.length)))
            if (next != value) {
                onValueChange(next)
                if (next.length == 6) onComplete?.invoke(next)
            }
        },
        modifier = modifier.fillMaxWidth().height(44.dp)
            .testTag("hlovet-code-input")
            .semantics { contentDescription = label }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = if (allowLetters) KeyboardType.Ascii else KeyboardType.NumberPassword),
        decorationBox = { innerTextField ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(6) { index ->
                        val shape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .testTag("hlovet-code-cell-$index")
                                .background(HlovetUi.surfaceLow, shape)
                                .border(1.dp, if (enabled && focused && index == activeIndex) HlovetUi.accent else HlovetUi.divider, shape)
                                .clickable(enabled = enabled) {
                                    val start = index.coerceAtMost(value.length)
                                    editingValue = TextFieldValue(value, TextRange(start, (start + 1).coerceAtMost(value.length)))
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                value.getOrNull(index)?.toString().orEmpty(),
                                color = HlovetUi.foreground,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Box(Modifier.size(1.dp)) { innerTextField() }
            }
        },
    )
}
