package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeArticleAppealTest {
    @Test
    fun parsesNestedArticleAndAuthorFields() {
        val appeal = NativeArticleApi.parseAppeal(
            JSONObject(
                """
                {
                  "id": 12,
                  "article": {"id": 88, "title": "测试文章", "slug": "test-article", "status": "blocked"},
                  "author": {"nickname": "作者昵称", "username": "author"},
                  "reason": "已修复违规内容",
                  "status": "pending",
                  "resolution": "",
                  "createdAt": "2026-10-09T10:00:00.000Z",
                  "reviewedAt": null
                }
                """.trimIndent(),
            ),
        )

        assertEquals(12, appeal.id)
        assertEquals(88, appeal.articleId)
        assertEquals("测试文章", appeal.articleTitle)
        assertEquals("test-article", appeal.articleSlug)
        assertEquals("blocked", appeal.articleStatus)
        assertEquals("作者昵称", appeal.authorName)
        assertEquals("author", appeal.authorUsername)
        assertEquals("已修复违规内容", appeal.reason)
        assertEquals("pending", appeal.status)
        assertNull(appeal.resolution)
        assertTrue(appeal.createdAt.isNotBlank())
        assertFalse(appeal.createdAt.contains('T'))
        assertNull(appeal.reviewedAt)
    }

    @Test
    fun usesSafeDefaultsWhenArticleAndAuthorAreMissing() {
        val appeal = NativeArticleApi.parseAppeal(
            JSONObject()
                .put("id", 13)
                .put("articleId", 89)
                .put("status", JSONObject.NULL)
                .put("reason", JSONObject.NULL)
                .put("reviewedAt", JSONObject.NULL),
        )

        assertEquals(13, appeal.id)
        assertEquals(89, appeal.articleId)
        assertEquals("未命名文章", appeal.articleTitle)
        assertEquals("作者", appeal.authorName)
        assertEquals("", appeal.authorUsername)
        assertEquals("", appeal.reason)
        assertEquals("pending", appeal.status)
        assertNull(appeal.resolution)
        assertNull(appeal.reviewedAt)
    }

    @Test
    fun preservesAllModerationStatusesAndResolution() {
        listOf("pending", "approved", "rejected").forEach { status ->
            val appeal = NativeArticleApi.parseAppeal(
                JSONObject()
                    .put("id", 14)
                    .put("status", status)
                    .put("resolution", "处理说明"),
            )

            assertEquals(status, appeal.status)
            assertEquals("处理说明", appeal.resolution)
        }
    }
}
