package xyz.hlovet.portal.prototype

import java.time.ZoneId
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 核对文章详情计划的真实嵌套结构及跨时区回显，防止重开后丢失计划或错误偏移。 */
class NativeArticleScheduleTest {
    /** 服务端 schedule 才是权威来源，顶层旧同名字段不能覆盖计划或错误说明。 */
    @Test fun draftUsesNestedScheduleAndLocalTimezone() {
        // 固定设备时区，并在结束时恢复 JVM 环境，不让其它时间相关测试受影响。
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val draft = NativeArticleApi.parseDraftArticle(JSONObject("""
                {"id": 12, "publishAt": "2000-01-01T00:00:00Z", "scheduleError": "wrong",
                 "schedule": {"publishAt": "2026-10-19T19:05:00Z", "unpublishAt": "2026-10-19T21:06:00Z", "error": "作者已停用"}}
            """.trimIndent()))
            assertEquals("2026-10-20 03:05", draft.publishAt)
            assertEquals("2026-10-20 05:06", draft.unpublishAt)
            assertEquals("作者已停用", draft.scheduleError)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** 没有计划、明确清空或接口 null 时都返回空值，不能显示字面量 null。 */
    @Test fun missingOrClearedScheduleIsEmpty() {
        // 已取消的计划不允许从文章顶层旧字段恢复。
        listOf(JSONObject(), JSONObject().put("schedule", JSONObject.NULL),
            JSONObject("""{"publishAt":"2026-10-20T10:00:00Z","schedule":{"publishAt":null,"unpublishAt":null,"error":null}}"""))
            .forEach { value ->
                val draft = NativeArticleApi.parseDraftArticle(value)
                assertNull(draft.publishAt)
                assertNull(draft.unpublishAt)
                assertNull(draft.scheduleError)
            }
    }

    /** UTC 和显式偏移表示同一时刻时显示相同值，跨日与夏令时按目标时区规则计算。 */
    @Test fun scheduleFormattingRespectsOffsetsAndDaylightSaving() {
        // 格式化仅保留分钟，不改变后续以设备时区提交的业务含义。
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("2026-10-20 03:05", nativeLocalDateTimeInput("2026-10-19T19:05:59Z", shanghai))
        assertEquals("2026-10-20 03:05", nativeLocalDateTimeInput("2026-10-20T03:05:00+08:00", shanghai))
        assertEquals("2026-03-08 01:30", nativeLocalDateTimeInput("2026-03-08T06:30:00Z", ZoneId.of("America/New_York")))
        assertEquals("2026-03-08 03:30", nativeLocalDateTimeInput("2026-03-08T07:30:00Z", ZoneId.of("America/New_York")))
    }

    /** 异常非空时间保持可见，空值保持无计划，不悄悄替换为当前时间。 */
    @Test fun invalidTimesRemainVisibleInsteadOfInventingADate() {
        // 让编辑页能够提示真实格式错误，而不是用截断字符串掩盖接口问题。
        assertEquals("bad-date", nativeLocalDateTimeInput("bad-date"))
        listOf(null, "", "  ", "null").forEach { assertNull(nativeLocalDateTimeInput(it)) }
    }
}
