package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeArticleCenterSummaryTest {
    @Test
    fun parsesAllCenterCounts() {
        val summary = NativeArticleApi.parseNativeArticleCenterSummary(
            JSONObject(
                """
                {
                  "discover": 42,
                  "subscriptions": 8,
                  "mine": 6,
                  "favorites": 5,
                  "liked": 4,
                  "readLater": 3,
                  "history": 2,
                  "manage": 99
                }
                """.trimIndent(),
            ),
        )

        assertEquals(42, summary.discover)
        assertEquals(8, summary.subscriptions)
        assertEquals(6, summary.mine)
        assertEquals(5, summary.favorites)
        assertEquals(4, summary.liked)
        assertEquals(3, summary.readLater)
        assertEquals(2, summary.history)
        assertEquals(99, summary.manage)
    }

    @Test
    fun missingCountsUseZeroWithoutBreakingProfile() {
        val summary = NativeArticleApi.parseNativeArticleCenterSummary(JSONObject())

        assertEquals(
            NativeArticleCenterSummary(0, 0, 0, 0, 0, 0, 0, 0),
            summary,
        )
    }
}
