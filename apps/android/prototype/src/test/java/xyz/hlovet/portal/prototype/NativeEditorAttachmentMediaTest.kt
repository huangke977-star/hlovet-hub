package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 验证编辑器媒体地址只匹配当前 API 的准确附件，不因相似域名或正文任意 URL 泄露凭据。 */
class NativeEditorAttachmentMediaTest {
    private val base = "https://example.test/api"
    private val image = NativeArticleAttachment(7, "image", "photo.png", "image/png", 100, "/articles/attachments/7/download", null)

    /** 本地编辑页相对 URL 和 API 绝对 URL 都对应同一附件。 */
    @Test fun exactRelativeAndAbsoluteAttachmentUrlsAreAccepted() {
        // 路径中的 /api 来自构建配置，不来自硬编码正式域名。
        assertEquals(image, nativeEditorAttachmentForUrl("https://editor.invalid/articles/attachments/7/download", listOf(image), base))
        assertEquals(image, nativeEditorAttachmentForUrl("https://example.test/api/articles/attachments/7/download", listOf(image), base))
    }

    /** 不同协议、主机或端口的相似地址均不能取得本地账号凭据。 */
    @Test fun foreignOriginsAndSimilarHostsAreBlocked() {
        // 同路径不是同身份，特别拒绝 localhost 其它端口与保留页面的相似域名。
        for (url in listOf("https://example.test.evil/api/articles/attachments/7/download", "http://example.test/api/articles/attachments/7/download",
            "https://example.test:8443/api/articles/attachments/7/download", "https://editor.invalid.evil/articles/attachments/7/download")) {
            assertNull(url, nativeEditorAttachmentForUrl(url, listOf(image), base))
        }
    }

    /** 查询串、用户信息和其它 API 路径不能借已知附件放行。 */
    @Test fun alteredUrlsAndUnrelatedEndpointsAreBlocked() {
        // 允许列表匹配不可变编号和准确下载路径，不接受重定向参数。
        for (url in listOf("https://editor.invalid/articles/attachments/70/download", "https://editor.invalid/articles/attachments/7/download?next=/auth",
            "https://editor.invalid/auth/security-policy", "https://user@editor.invalid/articles/attachments/7/download")) {
            assertNull(url, nativeEditorAttachmentForUrl(url, listOf(image), base))
        }
    }

    /** 文本附件和未知编号不作为编辑器子资源加载，外部附件地址也不能声明成本站媒体。 */
    @Test fun unknownAndNonMediaAttachmentsAreBlocked() {
        // 文本附件仍以正文链接展示，不能借媒体加载执行内容。
        val url = "https://editor.invalid/articles/attachments/7/download"
        assertNull(nativeEditorAttachmentForUrl(url, emptyList(), base))
        assertNull(nativeEditorAttachmentForUrl(url, listOf(image.copy(kind = "file")), base))
        assertNull(nativeEditorAttachmentForUrl("https://outside.test/image.png", listOf(image.copy(downloadUrl = "https://outside.test/image.png")), base))
    }
}
