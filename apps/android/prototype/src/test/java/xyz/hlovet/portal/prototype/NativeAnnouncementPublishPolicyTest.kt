package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 核对公告状态与 UTC 时间边界，不以设备当前时间让测试偶发失效。 */
class NativeAnnouncementPublishPolicyTest {
    private val now = Instant.parse("2026-10-16T10:00:00Z")
    private val draft = NativeAdminAnnouncementSummary(1, "标题", "摘要", "public", "draft", "immediate",
        false, 0, false, null, null, null, 0, 0, 0, emptyList(), null, "", "")

    /** 草稿、下线和归档允许重新发布，已发布或已安排状态禁止重复执行。 */
    @Test fun statusControlsRepublishingWithoutBlockingArchivedDrafts() {
        // 状态只决定是否提供操作，不改变服务端发布权限。
        listOf("draft", "expired", "archived").forEach { assertTrue(canNativePublishAnnouncement(draft.copy(status = it), now)) }
        listOf("published", "scheduled").forEach { assertFalse(canNativePublishAnnouncement(draft.copy(status = it), now)) }
    }

    /** 定时发布必须是未来时刻，相等、过去、空值及无效时间都不能发布。 */
    @Test fun scheduleMustBeStrictlyAfterNow() {
        // 时间等于当前值不能依靠网络延迟变成合法计划。
        val scheduled = draft.copy(publishMode = "scheduled")
        listOf(null, "", "invalid", "2026-10-16T09:59:59Z", now.toString()).forEach {
            assertFalse(canNativePublishAnnouncement(scheduled.copy(scheduledAt = it), now))
        }
        assertTrue(canNativePublishAnnouncement(scheduled.copy(scheduledAt = "2026-10-16T10:00:01Z"), now))
    }

    /** 自动下线晚于本次发布，立即与定时模式都使用严格比较。 */
    @Test fun expiryMustBeStrictlyAfterEffectivePublishTime() {
        // 已经过期的公告需先修改时间，不能直接重新投放。
        assertFalse(canNativePublishAnnouncement(draft.copy(expiresAt = now.toString()), now))
        assertFalse(canNativePublishAnnouncement(draft.copy(expiresAt = "invalid"), now))
        assertTrue(canNativePublishAnnouncement(draft.copy(expiresAt = "2026-10-16T10:00:01Z"), now))
        val scheduled = draft.copy(publishMode = "scheduled", scheduledAt = "2026-10-16T12:00:00Z")
        assertFalse(canNativePublishAnnouncement(scheduled.copy(expiresAt = "2026-10-16T12:00:00Z"), now))
        assertTrue(canNativePublishAnnouncement(scheduled.copy(expiresAt = "2026-10-16T12:00:01Z"), now))
    }

    /** 不同时区偏移表示相同时刻时，校验结果按 Instant 而非原始字符串判断。 */
    @Test fun timezoneOffsetsCompareAsInstants() {
        // 本地时间转换后跨日也不能改变先后顺序。
        assertTrue(canNativePublishAnnouncement(draft.copy(publishMode = "scheduled", scheduledAt = "2026-10-16T20:00:00+08:00", expiresAt = "2026-10-16T13:00:00Z"), now))
        assertFalse(canNativePublishAnnouncement(draft.copy(publishMode = "scheduled", scheduledAt = "2026-10-16T18:00:00+08:00"), now))
    }

    /** 时间展示保留四位年份，并把 UTC 与偏移时间转换为指定时区。 */
    @Test fun displayedTimeRetainsYearAndConvertsTimezone() {
        // 以跨日时刻覆盖旧的字符串截断，以及毫秒和时区偏移输入。
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("2026-10-17 07:45", nativeAdminAnnouncementTime("2026-10-16T23:45:59.123Z", shanghai))
        assertEquals("2026-10-17 07:45", nativeAdminAnnouncementTime("2026-10-17T01:45:59+02:00", shanghai))
        assertEquals("2026-10-16 23:45", nativeAdminAnnouncementTime("2026-10-16T23:45:59Z", ZoneId.of("UTC")))
    }

    /** 无法解析的时间不能被截成貌似合法的日期，也不能把空值当作真实时间。 */
    @Test fun displayedTimePreservesUnknownValuesWithoutInventingDates() {
        // 不依赖 JVM 默认语言，沿用当前共享语言层的未知时间提示。
        assertEquals("server-time-unavailable", nativeAdminAnnouncementTime(" server-time-unavailable "))
        assertEquals(nativePhrase("时间未知", "Unknown time"), nativeAdminAnnouncementTime(" "))
    }
}
