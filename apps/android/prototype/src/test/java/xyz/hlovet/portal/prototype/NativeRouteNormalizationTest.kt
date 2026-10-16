package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class NativeRouteNormalizationTest {
    @Test
    fun readingTabPreservesSupportedModes() {
        assertEquals("history", normalizeNativeReadingTab("history"))
        assertEquals("favorites", normalizeNativeReadingTab("favorites"))
        assertEquals("liked", normalizeNativeReadingTab("liked"))
    }

    @Test
    fun readingTabDefaultsUnknownValuesToReadLater() {
        assertEquals("read-later", normalizeNativeReadingTab(null))
        assertEquals("read-later", normalizeNativeReadingTab(""))
        assertEquals("read-later", normalizeNativeReadingTab("unexpected"))
    }

    @Test
    fun articleManagementDeepLinkKeepsOnlySupportedStatusAndBoundedSearch() {
        assertEquals("published", normalizeNativeArticleStatus(" Published "))
        assertEquals("deleted", normalizeNativeArticleStatus("deleted"))
        assertEquals(null, normalizeNativeArticleStatus("all"))
        assertEquals(null, normalizeNativeArticleStatus("unknown"))
        assertEquals("draft search", normalizeNativeRouteQuery("  draft search  "))
        assertEquals(80, normalizeNativeRouteQuery("x".repeat(100)).length)
    }

    @Test
    fun subscriptionDeepLinkDefaultsUnknownSortToLatest() {
        assertEquals("unread", normalizeNativeSubscriptionSort(" UNREAD "))
        assertEquals("popular", normalizeNativeSubscriptionSort("popular"))
        assertEquals("latest", normalizeNativeSubscriptionSort(null))
        assertEquals("latest", normalizeNativeSubscriptionSort("recent"))
    }

    @Test
    fun collectionEntryDefaultsToMineUnlessTheBrowseViewIsRequested() {
        assertEquals("browse", normalizeNativeCollectionView(" BROWSE "))
        assertEquals("mine", normalizeNativeCollectionView(null))
        assertEquals("mine", normalizeNativeCollectionView("unknown"))
    }

    @Test
    fun discoveryModesKeepDeepLinkedContentSeparated() {
        assertEquals(NativeDiscoveryVisibility(true, false, false), nativeDiscoveryVisibility("文章"))
        assertEquals(NativeDiscoveryVisibility(false, true, false), nativeDiscoveryVisibility("专题"))
        assertEquals(NativeDiscoveryVisibility(false, false, true), nativeDiscoveryVisibility("合集"))
        assertEquals(NativeDiscoveryVisibility(true, true, true), nativeDiscoveryVisibility("推荐"))
    }

    @Test
    fun articleSortAcceptsSupportedValuesAndDefaultsUnknownValues() {
        assertEquals("recommended", normalizeNativeArticleSort(null))
        assertEquals("recommended", normalizeNativeArticleSort("unknown"))
        assertEquals("views", normalizeNativeArticleSort(" VIEWS "))
        assertEquals("comments", normalizeNativeArticleSort("comments"))
        try {
            NativeI18n.applyLocale("zh-CN")
            assertEquals("最多收藏", nativeArticleSortLabel("favorites"))
            NativeI18n.applyLocale("en-US")
            assertEquals("Most saved", nativeArticleSortLabel("favorites"))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    @Test
    fun recommendationTypesAndReasonsAreNormalizedForNativeDisplay() {
        assertEquals("topic", normalizeNativeRecommendationTargetType(" TOPIC "))
        assertEquals("group", normalizeNativeRecommendationTargetType("group"))
        assertEquals("article", normalizeNativeRecommendationTargetType("unknown"))
        assertEquals("基于你的阅读 · 站内热门", nativeRecommendationReasonText(listOf("based_on_reading", "popular", "unknown")))
    }

    @Test
    fun recommendationPayloadKeepsAllNativeTargetKinds() {
        val parsed = NativeDiscoveryApi.parseRecommendations(
            JSONObject(
                """
                {
                  "batch": 2,
                  "hasMore": true,
                  "topics": [{"id": 1, "title": "专题", "slug": "topic", "articleCount": 3, "reasons": ["popular"]}],
                  "collections": [{"id": 2, "name": "合集", "owner": {"id": 8, "nickname": "作者", "username": "writer"}}],
                  "authors": [{"id": 8, "nickname": "作者", "username": "writer", "topCategory": "技术", "reasons": ["based_on_reading"]}],
                  "groups": [{"id": 4, "conversationId": 9, "name": "群聊", "memberCount": 12, "joinMode": "approval"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, parsed.batch)
        assertTrue(parsed.hasMore)
        assertEquals("专题", parsed.topics.single().title)
        assertEquals("writer", parsed.collections.single().owner.username)
        assertEquals("技术", parsed.authors.single().topCategory)
        assertEquals(9, parsed.groups.single().conversationId)
    }

    @Test
    fun anonymousTopicPublicAndManagementEntriesNeverFallbackIntoEachOther() {
        assertEquals(NativeAnonymousTopicAccess.Public, nativeAnonymousTopicAccess(management = false, isAdministrator = true))
        assertEquals(NativeAnonymousTopicAccess.Management, nativeAnonymousTopicAccess(management = true, isAdministrator = true))
        assertEquals(NativeAnonymousTopicAccess.Denied, nativeAnonymousTopicAccess(management = true, isAdministrator = false))
    }

    @Test
    fun titleColorAcceptsEmptyOrSixDigitHexAndNormalizesCase() {
        assertEquals("", normalizeNativeTitleColor(" "))
        assertEquals("#AABBCC", normalizeNativeTitleColor("#aabbcc"))
        assertEquals("#AABBCC", normalizeNativeTitleColor("aabbcc"))
        assertEquals(null, normalizeNativeTitleColor("#abc"))
        assertEquals(null, normalizeNativeTitleColor("red"))
    }

    @Test
    fun articleParsingAndPreviewPreserveOnlyValidatedTitleColor() {
        val article = NativeArticleApi.parseArticle(
            JSONObject("""{"id":1,"title":"Title","slug":"title","titleColor":"#12abEF"}"""),
        )
        assertEquals("#12abEF", article.titleColor)

        val html = buildPreviewHtml("Title", article.titleColor, "", "Body", "markdown")
        assertTrue(html.contains("style=\"color:#12ABEF\""))
        val invalid = buildPreviewHtml("Title", "red; background:black", "", "Body", "markdown")
        assertFalse(invalid.contains("red; background:black"))
    }
}
