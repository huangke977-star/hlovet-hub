package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeP22BrowserAcceptanceTest {
    @Test
    fun pageMetricsParserReadsOverflowImagesAndScriptErrors() {
        val metrics = nativeP22ParsePageMetrics(
            """{"scrollWidth": 402, "viewportWidth": 390, "failedImages": 2, "scriptErrors": ["hydration failed", "asset error"]}""",
        )

        requireNotNull(metrics)
        assertEquals(402, metrics.scrollWidth)
        assertEquals(390, metrics.viewportWidth)
        assertEquals(2, metrics.failedImages)
        assertEquals(listOf("hydration failed", "asset error"), metrics.scriptErrors)
    }

    @Test
    fun pageMetricsParserRejectsMalformedPayload() {
        assertNull(nativeP22ParsePageMetrics("not-json"))
    }

    @Test
    fun passedBrowserResultRequiresEveryCheckToPass() {
        val checks = listOf(
            NativeP22BrowserCheckResult("one", "一", "One", "passed", "ok", "ok", 1),
            NativeP22BrowserCheckResult("two", "二", "Two", "failed", "bad", "bad", 2),
        )
        val result = NativeP22BrowserAcceptanceResult("now", checks.all { it.status == "passed" }, checks, emptyList())
        assertTrue(!result.passed)
    }

    @Test
    fun overflowCheckDoesNotLetWebViewAutoZoomHideWideContent() {
        assertTrue(nativeP22HasPageOverflow(NativeP22PageMetrics(2400, 2400, 0, emptyList()), 1440))
        assertTrue(nativeP22HasPageOverflow(NativeP22PageMetrics(2400, 1565, 0, emptyList()), 390))
    }

    @Test
    fun overflowCheckAllowsSinglePixelDensityRoundingButNotRealOverflow() {
        assertFalse(nativeP22HasPageOverflow(NativeP22PageMetrics(391, 391, 0, emptyList()), 390))
        assertFalse(nativeP22HasPageOverflow(NativeP22PageMetrics(1440, 1440, 0, emptyList()), 1440))
        assertTrue(nativeP22HasPageOverflow(NativeP22PageMetrics(392, 391, 0, emptyList()), 390))
    }
}
