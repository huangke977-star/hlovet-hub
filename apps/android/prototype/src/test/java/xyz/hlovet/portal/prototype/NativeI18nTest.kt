package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeI18nTest {
    @Test
    fun mediaFallbacksFollowTheSelectedLocale() {
        try {
            NativeI18n.applyLocale("zh-CN")
            assertEquals("媒体文件", nativePhrase("媒体文件", "Media file"))
            assertEquals("图片暂不可用", nativePhrase("图片暂不可用", "Image unavailable"))
            NativeI18n.applyLocale("en-US")
            assertEquals("Media file", nativePhrase("媒体文件", "Media file"))
            assertEquals("Image unavailable", nativePhrase("图片暂不可用", "Image unavailable"))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    @Test
    fun normalizesSupportedLocales() {
        assertEquals("zh-CN", normalizeNativeLocale("zh-CN"))
        assertEquals("en-US", normalizeNativeLocale("en-US"))
        assertEquals("en-US", normalizeNativeLocale("en"))
        assertEquals("en-US", normalizeNativeLocale("en-GB"))
    }

    @Test
    fun fallsBackToChineseForUnknownOrMissingLocales() {
        assertEquals("zh-CN", normalizeNativeLocale(null))
        assertEquals("zh-CN", normalizeNativeLocale("fr-FR"))
    }

    @Test
    fun onlyEnglishLocalesUseEnglishDisplay() {
        assertEquals("en-US", normalizeNativeLocale("en"))
        assertEquals("en-US", normalizeNativeLocale("en-GB"))
        assertEquals("zh-CN", normalizeNativeLocale("zh-Hans-CN"))
        assertEquals("zh-CN", normalizeNativeLocale("ja-JP"))
    }

    @Test
    fun switchesSharedDisplayLabelsWithoutChangingRouteValues() {
        try {
            NativeI18n.applyLocale("en-US")
            assertEquals("Discover", nativeDiscoveryModeLabel("文章"))
            assertEquals("Most saved", nativeArticleSortDisplayLabel("favorites"))
            assertEquals(
                "Based on your reading · Popular on HLOVET",
                nativeRecommendationReasonDisplayText(listOf("based_on_reading", "popular")),
            )
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }
}
