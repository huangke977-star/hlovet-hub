package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证后台 FCM 双语文案选择，避免英文界面收到中文通知。 */
class NativeNotificationPayloadTextTest {
    /** 英文界面优先使用服务端发送的英文标题或正文。 */
    @Test
    fun englishLocaleUsesEnglishPayload() {
        // 同时提供中英文数据，英文设备必须读取英文字段。
        val result = nativeNotificationPayloadText(
            data = mapOf("title" to "中文标题", "titleEn" to "English title"),
            primaryKey = "title",
            englishKey = "titleEn",
            locale = "en-US",
            fallback = "fallback",
        )

        assertEquals("English title", result)
    }

    /** 中文界面继续使用中文字段，保持服务端通知的默认语言行为。 */
    @Test
    fun chineseLocaleUsesPrimaryPayload() {
        // 中文账号不应因为英文备用字段存在而切换语言。
        val result = nativeNotificationPayloadText(
            data = mapOf("body" to "中文正文", "bodyEn" to "English body"),
            primaryKey = "body",
            englishKey = "bodyEn",
            locale = "zh-CN",
            fallback = "fallback",
        )

        assertEquals("中文正文", result)
    }

    /** 英文字段缺失时必须回退到主字段而不是生成空通知。 */
    @Test
    fun missingEnglishPayloadFallsBackToPrimary() {
        // 第三方或旧服务端版本未发送英文字段时仍应展示可读内容。
        val result = nativeNotificationPayloadText(
            data = mapOf("title" to "中文标题"),
            primaryKey = "title",
            englishKey = "titleEn",
            locale = "en-US",
            fallback = "fallback",
        )

        assertEquals("中文标题", result)
    }
}
