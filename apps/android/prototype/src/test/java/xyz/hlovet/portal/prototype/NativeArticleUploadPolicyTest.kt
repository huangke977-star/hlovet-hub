package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 核对文章附件的真实接口上限、供应商 MIME 回退和删除引用边界，不用聊天限制代替文章合同。 */
class NativeArticleUploadPolicyTest {
    /** 九个文件及单篇五十个为合法边界，超出任意一项均拒绝。 */
    @Test fun countMatchesRequestAndArticleLimits() {
        // 每批和文章累计总量分别计算，不因已有文件减少合法的九文件请求上限。
        validateNativeArticleAttachmentCount(41, 9)
        assertNotNull(runCatching { validateNativeArticleAttachmentCount(0, 10) }.exceptionOrNull())
        assertNotNull(runCatching { validateNativeArticleAttachmentCount(42, 9) }.exceptionOrNull())
    }

    /** 普通文档可用五十 MB，不误套聊天客户端二十 MB 限制。 */
    @Test fun documentsAudioAndVideoUseServerLimits() {
        // 所有大小是实际字节上限，避免 MB 与文件类型误差。
        assertEquals(50L * 1024 * 1024, nativeArticleAttachmentLimitBytes("application/pdf", 10L, true))
        assertEquals(20L * 1024 * 1024, nativeArticleAttachmentLimitBytes("audio/webm", 10L, true))
        assertEquals(50L * 1024 * 1024, nativeArticleAttachmentLimitBytes("video/mp4", 10L, true))
    }

    /** 图片原始版本按站点设置，压缩后必须符合八 MB 的附件限制。 */
    @Test fun imageLimitsDistinguishSourceFromUpload() {
        // 原图设置允许十 MB，并不表示附件接口会接受十 MB 的最终图片。
        assertEquals(10L * 1024 * 1024, nativeArticleAttachmentLimitBytes("image/jpeg", 10L * 1024 * 1024, false))
        assertEquals(8L * 1024 * 1024, nativeArticleAttachmentLimitBytes("image/jpeg", 10L * 1024 * 1024, true))
    }

    /** 文件供应商给通用 MIME 时，合法 Office、JSON 和音频仍可正确上传。 */
    @Test fun supportedExtensionsNormalizeMissingOrGenericMime() {
        // 扩展名大小写不影响合法类型，WebM 明确的音频 MIME 得到保留。
        assertTrue(nativeArticleAttachmentMimeType("report.DOCX", "application/octet-stream").contains("wordprocessingml"))
        assertEquals("application/json", nativeArticleAttachmentMimeType("data.json", ""))
        assertEquals("audio/mpeg", nativeArticleAttachmentMimeType("voice.mp3", ""))
        assertEquals("audio/webm", nativeArticleAttachmentMimeType("voice.webm", "audio/webm"))
        assertEquals("video/webm", nativeArticleAttachmentMimeType("clip.webm", ""))
    }

    /** MIME 声称文本也不能放行脚本或接口不支持的格式。 */
    @Test fun unsupportedExtensionsAreRejected() {
        // 真实签名由服务器检查，客户端先排除显然不支持的扩展名。
        for (name in listOf("unsafe.ps1", "unsafe.exe", "picture.gif", "unknown")) {
            assertNotNull(name, runCatching { nativeArticleAttachmentMimeType(name, "text/plain") }.exceptionOrNull())
        }
    }

    /** 空文件不能作为一次成功上传提交。 */
    @Test fun emptyFilesAreRejected() {
        // 构造零字节合法后缀文件，确认非空校验不被 MIME 回退绕过。
        assertNotNull(runCatching {
            validateNativeArticleUpload(listOf(NativeUploadFile("empty.txt", "text/plain", byteArrayOf())), 10L, true)
        }.exceptionOrNull())
    }

    /** 压缩结果仍大于八 MB 时在发请求前拒绝，不制造失败的远程空草稿。 */
    @Test fun preparedImageCannotExceedEightMegabytes() {
        // 同一原图在压缩前可通过，压缩后接口上限必须再次生效。
        val file = NativeUploadFile("large.png", "image/png", ByteArray(8 * 1024 * 1024 + 1))
        validateNativeArticleUpload(listOf(file), 10L * 1024 * 1024, false)
        assertNotNull(runCatching { validateNativeArticleUpload(listOf(file), 10L * 1024 * 1024, true) }.exceptionOrNull())
    }

    /** HTML 附件可与新文字共处一段，删除附件不删除同段文字或其它附件。 */
    @Test fun inlineHtmlRemovalKeepsAdjacentTextAndOtherAttachments() {
        // 删除准确 href，保留另一编号的链接及用户在附件后继续输入的内容。
        val item = attachment()
        val body = "<p>Before <a href=\"${item.downloadUrl}\">file</a> latest</p><p><a href=\"/other\">other</a></p>"
        assertEquals("<p>Before  latest</p><p><a href=\"/other\">other</a></p>", removeNativeAttachmentReference(body, item, "html"))
    }

    /** 编辑器生成的 source 子节点也能移除完整媒体，不遗留无效播放器。 */
    @Test fun htmlSourceRemovalRemovesItsPlayer() {
        // source 属于播放器而非独立正文，整个播放器删除但结尾段落保留。
        val item = attachment()
        val body = "<div><video controls><source src=\"${item.downloadUrl}\"></video></div><p>tail</p>"
        assertEquals("<div></div><p>tail</p>", removeNativeAttachmentReference(body, item, "html"))
    }

    /** Markdown 文件名中的转义括号不阻止引用删除，相似 URL 不应误删。 */
    @Test fun markdownRemovalHandlesEscapedBracketsAndExactUrls() {
        // 两个链接只有一个精确引用被删，另一个相似链接保持完整。
        val item = attachment()
        val body = "[a\\[b\\].txt](${item.downloadUrl}) [keep](${item.downloadUrl}0)"
        assertEquals(" [keep](${item.downloadUrl}0)", removeNativeAttachmentReference(body, item, "markdown"))
    }

    /** HTML 中没有准确引用时返回原文，避免无关格式被序列化改写。 */
    @Test fun absentHtmlReferenceLeavesOriginalBytesUntouched() {
        // 原文包含不同属性引号和空白，无匹配时保持逐字符一致。
        val body = "<p class='note'>  unchanged </p>"
        assertEquals(body, removeNativeAttachmentReference(body, attachment(), "html"))
    }

    /** 返回固定附件供内容边界测试，编号和 URL 对应同一对象。 */
    private fun attachment(): NativeArticleAttachment {
        // 使用相对路径验证不依赖测试来源或正式域名。
        return NativeArticleAttachment(7, "file", "notes.txt", "text/plain", 10, "/articles/attachments/7/download", null)
    }
}
