package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 核对通话时间的 API 回显和 PC 同款格式，不依赖设备时区或界面语言。 */
class NativeCallTimeTest {
    /** API 带毫秒 UTC 时间和等价带时区时间应指向同一接听时刻。 */
    @Test fun parsesServerMillisecondsAndOffsets() {
        // 身份时间以绝对时刻为准，不能把时区偏移当成本地钟面时间。
        val utc = nativeCallAcceptedAtMillis("2026-10-26T10:00:00.123Z")
        assertNotNull(utc)
        assertEquals(utc, nativeCallAcceptedAtMillis("2026-10-26T18:00:00.123+08:00"))
        assertEquals(utc, nativeCallAcceptedAtMillis("2026-10-26T05:00:00.123-05:00"))
    }

    /** 无毫秒 ISO 时间仍可解析，前后空白不会改变接听时间。 */
    @Test fun acceptsSecondPrecisionAndSurroundingWhitespace() {
        // 服务端精度变化不应让已接通的通话丢失计时。
        assertEquals(nativeCallAcceptedAtMillis("2026-10-26T10:00:00.000Z"), nativeCallAcceptedAtMillis(" 2026-10-26T10:00:00Z "))
    }

    /** 空值、错误日期和夹带后缀的时间不能伪造成有效接听时刻。 */
    @Test fun rejectsMissingMalformedAndPartialTimes() {
        // 非法字段应隐藏计时而非显示从 1970 年累计的巨大时长。
        for (value in listOf(null, "", "null", "not-a-date", "2026-02-30T10:00:00Z", "2026-10-26T10:00:00Zgarbage")) {
            assertNull(value, nativeCallAcceptedAtMillis(value))
        }
    }

    /** 分秒边界与超过一小时时的格式与 PC 一致，并按完整秒舍弃毫秒。 */
    @Test fun formatsMinuteAndHourBoundaries() {
        // 三位小时也应完整保留，不截断长通话。
        assertEquals("00:00", nativeCallDuration(999))
        assertEquals("00:59", nativeCallDuration(59_999))
        assertEquals("01:00", nativeCallDuration(60_000))
        assertEquals("59:59", nativeCallDuration(3_599_999))
        assertEquals("01:00:00", nativeCallDuration(3_600_000))
        assertEquals("100:00:01", nativeCallDuration(360_001_000))
    }

    /** 服务端时间略晚于设备时钟时不显示负数。 */
    @Test fun clampsNegativeElapsedTimeToZero() {
        // 设备与服务器有短暂时钟偏差时仍保留可理解的计时显示。
        assertEquals("00:00", nativeCallDuration(-1))
        assertEquals("00:00", nativeCallDuration(Long.MIN_VALUE))
    }
}
