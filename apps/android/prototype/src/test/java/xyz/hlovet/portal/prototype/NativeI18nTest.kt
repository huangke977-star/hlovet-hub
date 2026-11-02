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

    /** 系统通知的频道和空消息兜底应随账号语言切换，不能固定为中文。 */
    @Test
    fun notificationFallbacksFollowTheSelectedLocale() {
        try {
            NativeI18n.applyLocale("zh-CN")
            assertEquals("站内提醒", nativeNotificationChannelName())
            assertEquals("HLOVET 的聊天、评论和系统提醒", nativeNotificationChannelDescription())
            assertEquals("你有一条新的站内提醒。", nativeNotificationBodyFallback())
            NativeI18n.applyLocale("en-US")
            assertEquals("Site notifications", nativeNotificationChannelName())
            assertEquals("HLOVET chats, comments, and system notifications", nativeNotificationChannelDescription())
            assertEquals("You have a new site notification.", nativeNotificationBodyFallback())
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }

    /** Android 版本管理的网络兜底文案应跟随当前界面语言，不能在英文页面混入中文。 */
    @Test
    fun androidReleaseFallbacksFollowTheSelectedLocale() {
        try {
            NativeI18n.applyLocale("zh-CN")
            assertEquals("安装包上传失败", nativePhrase("安装包上传失败", "Package upload failed"))
            assertEquals("安装包请求失败", nativePhrase("安装包请求失败", "Package request failed"))
            assertEquals("服务器返回的安装包数据格式不正确。", nativePhrase("服务器返回的安装包数据格式不正确。", "The server returned an invalid package response."))
            NativeI18n.applyLocale("en-US")
            assertEquals("Package upload failed", nativePhrase("安装包上传失败", "Package upload failed"))
            assertEquals("Package request failed", nativePhrase("安装包请求失败", "Package request failed"))
            assertEquals("The server returned an invalid package response.", nativePhrase("服务器返回的安装包数据格式不正确。", "The server returned an invalid package response."))
        } finally {
            NativeI18n.applyLocale("zh-CN")
        }
    }
}
