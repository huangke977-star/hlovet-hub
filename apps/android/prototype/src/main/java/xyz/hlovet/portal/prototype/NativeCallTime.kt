package xyz.hlovet.portal.prototype

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 将服务端 acceptedAt 转为毫秒时间戳；缺失或非法值返回 null，不伪造接听时间，兼容 API 24。 */
internal fun nativeCallAcceptedAtMillis(acceptedAt: String?): Long? {
    // 接听时间由 API 提供；完整消费 ISO 时间，避免合法前缀后拼入无效内容也被接受。
    val value = acceptedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
        val formatter = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(value, position)
        if (parsed != null && position.index == value.length) return parsed.time
    }
    return null
}

/** 将 elapsedMillis 格式化为与 PC 一致的分秒或时分秒；负数按零处理，数字不随界面语言变化。 */
internal fun nativeCallDuration(elapsedMillis: Long): String {
    // 未满一小时显示 mm:ss，满一小时显示 hh:mm:ss，不将小时累加到分钟中。
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainder = seconds % 60
    return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder)
    else String.format(Locale.US, "%02d:%02d", minutes, remainder)
}
