package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAdminArticleReportActionTest {
    @Test
    fun rejectUsesRejectedReportStatus() {
        assertEquals("rejected", nativeAdminReportResolutionStatus("reject"))
        assertNull(nativeAdminReportContentStatus("article", "reject"))
    }

    @Test
    fun resolveKeepsReportedContent() {
        assertEquals("resolved", nativeAdminReportResolutionStatus("resolve"))
        assertNull(nativeAdminReportContentStatus("comment", "resolve"))
    }

    @Test
    fun blockAndDeleteMapToContentStatuses() {
        assertEquals("blocked", nativeAdminReportContentStatus("article", "block"))
        assertEquals("deleted", nativeAdminReportContentStatus("comment", "delete"))
    }

    @Test
    fun unsupportedSourcesNeverReceiveContentMutation() {
        assertNull(nativeAdminReportContentStatus("group_message", "delete"))
    }
}
