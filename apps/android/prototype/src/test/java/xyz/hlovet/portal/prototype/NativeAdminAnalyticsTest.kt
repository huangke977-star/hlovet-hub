package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdminAnalyticsTest {
    @Test
    fun summaryCoversEveryPcMetricWithoutDuplicateKeys() {
        val keys = nativeAnalyticsSummaryMetricKeys()
        assertEquals(25, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.containsAll(listOf("anonymousTopics", "notificationOpens", "resourcePointsPending")))
    }
}
