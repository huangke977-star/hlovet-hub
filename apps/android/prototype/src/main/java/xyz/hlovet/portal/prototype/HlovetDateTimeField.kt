package xyz.hlovet.portal.prototype

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect

/** 统一公告和文章计划的只读时间输入；label/value 为展示值，回调选择或清除，禁用时不接受任何操作。 */
@Composable
internal fun HlovetDateTimeField(
    label: String,
    value: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = nativePhrase("点击选择时间", "Tap to choose a time"),
    colors: TextFieldColors = HlovetUi.fieldColors(),
) {
    // 只读文本框会自己消费触摸，监听字段自身的释放事件，并使用重组后最新的草稿回调。
    val interaction = remember { MutableInteractionSource() }
    val currentOnPick by rememberUpdatedState(onPick)
    LaunchedEffect(interaction, enabled) {
        if (!enabled) return@LaunchedEffect
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Release) currentOnPick()
        }
    }
    // 清除图标消费独立触摸，无障碍点击复用选择逻辑，避免清除时意外打开系统窗口。
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = modifier.fillMaxWidth().semantics {
            onClick(label = label) { if (enabled) { currentOnPick(); true } else false }
        },
        enabled = enabled,
        interactionSource = interaction,
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        trailingIcon = if (value.isNotBlank() && enabled) ({
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除时间", "Clear time"), modifier = Modifier.size(15.dp))
            }
        }) else null,
        colors = colors,
    )
}

/** 将服务端 value 时刻转为 zoneId 下的分钟级输入值；空值返回 null，无效非空值保留供用户发现错误。 */
internal fun nativeLocalDateTimeInput(value: String?, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    // 只做展示与编辑回显转换，不截断 UTC 文本冒充设备本地时间。
    val source = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    return runCatching {
        Instant.parse(source).atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(source)
}
