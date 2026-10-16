package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeArticleReportTest {
    @Test
    fun usesServerSupportedReasons() {
        assertEquals(
            listOf("spam", "harassment", "illegal", "privacy", "misinformation", "other"),
            NATIVE_ARTICLE_REPORT_REASONS,
        )
    }

    @Test
    fun trimsOptionalDetailAndCapsItAtServerLimit() {
        assertEquals("补充说明", normalizeNativeArticleReportDetail("  补充说明  "))
        assertNull(normalizeNativeArticleReportDetail("  "))
        assertEquals(500, normalizeNativeArticleReportDetail("x".repeat(700))?.length)
    }
}
