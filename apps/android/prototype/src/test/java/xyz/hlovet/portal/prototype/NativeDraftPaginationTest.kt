package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDraftPaginationTest {
    private fun draft(id: Int) = NativeDraftArticle(
        id = id,
        title = "Draft $id",
        summary = "",
        content = "",
        contentFormat = "html",
        category = "",
        tags = emptyList(),
        titleColor = "",
        visibility = "public",
        roleCodes = emptyList(),
        status = "draft",
        slug = "draft-$id",
        updatedAt = "",
    )

    @Test
    fun appendDraftPageDeduplicatesAndKeepsLatestPaginationMetadata() {
        val current = NativeDraftList(listOf(draft(1), draft(2)), total = 4, page = 1, totalPages = 2)
        val next = NativeDraftList(listOf(draft(2), draft(3)), total = 4, page = 2, totalPages = 2)

        val result = appendNativeDraftPage(current, next)

        assertEquals(listOf(1, 2, 3), result.items.map { it.id })
        assertEquals(4, result.total)
        assertEquals(2, result.page)
        assertEquals(2, result.totalPages)
    }

    @Test
    fun firstPageReplacesExistingContent() {
        val current = NativeDraftList(listOf(draft(1)), total = 3, page = 2, totalPages = 3)
        val first = NativeDraftList(listOf(draft(4)), total = 1, page = 1, totalPages = 1)

        assertEquals(first, appendNativeDraftPage(current, first))
    }
}
