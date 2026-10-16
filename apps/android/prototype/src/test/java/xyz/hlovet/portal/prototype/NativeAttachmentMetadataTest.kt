package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证附件交给系统前的名称及类型边界，不启动安卓组件或访问服务器。 */
class NativeAttachmentMetadataTest {
    /** 正常中文及带空格名称不被替换为内部摘要。 */
    @Test fun preservesOriginalName() {
        // 文件名用于系统展示，不要求限制为 ASCII。
        assertEquals("测试 notes.txt", nativeAttachmentDisplayName("测试 notes.txt"))
    }

    /** 外部名称中的路径及控制字符不能进入系统文件选择器。 */
    @Test fun removesPathsAndControlCharacters() {
        // 同时覆盖 Windows 分隔符和换行伪装。
        assertEquals("notes.txt", nativeAttachmentDisplayName("C:\\folder\\notes\r\n.txt"))
        assertEquals("notes.txt", nativeAttachmentDisplayName("../../notes.txt"))
    }

    /** 空白名称使用明确兜底，过长名称受固定限制。 */
    @Test fun boundsAndDefaultsNames() {
        // 展示限制不依赖私有缓存文件名。
        assertEquals("attachment", nativeAttachmentDisplayName(" / "))
        assertEquals(240, nativeAttachmentDisplayName("x".repeat(400)).length)
    }

    /** 带编码参数的 MIME 类型在系统合同中保留真实文件类型。 */
    @Test fun normalizesConcreteMimeType() {
        // 内容参数不应干扰系统保存后缀及查看器匹配。
        assertEquals("text/plain", nativeAttachmentMimeType(" Text/Plain; charset=utf-8 "))
        assertEquals("image/svg+xml", nativeAttachmentMimeType("image/svg+xml"))
    }

    /** 通配、缺失及非法类型不能作为系统创建文档的具体类型。 */
    @Test fun rejectsInvalidMimeType() {
        // 全部非法输入一致回退，不使用过宽通配类型。
        listOf("", "image/*", "bad type", "text/plain\r\nimage/png", "*/plain").forEach {
            assertEquals("application/octet-stream", nativeAttachmentMimeType(it))
        }
    }

    /** 正文中的普通文件名称必须给系统查看器具体类型，不能全部退化为二进制类型。 */
    @Test fun resolvesDocumentMimeTypesFromOriginalName() {
        // 文本、PDF、办公文件及压缩包均使用同一正文附件规则。
        assertEquals("text/plain", guessNativeArticleMimeType("测试 notes.txt"))
        assertEquals("application/pdf", guessNativeArticleMimeType("manual.PDF"))
        assertEquals("text/csv", guessNativeArticleMimeType("table.csv"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", guessNativeArticleMimeType("document.docx"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", guessNativeArticleMimeType("table.xlsx"))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", guessNativeArticleMimeType("slides.pptx"))
        assertEquals("application/zip", guessNativeArticleMimeType("bundle.zip"))
    }

    /** 参数、片段及大小写不影响媒体识别，未知名称仍使用明确兜底。 */
    @Test fun handlesUrlSuffixesWithoutChangingExistingMediaTypes() {
        // 保留图片及音视频类型，新增普通文件不能改变已接入的正文渲染行为。
        assertEquals("text/plain", guessNativeArticleMimeType("https://example.invalid/notes.TXT?download=1#target"))
        assertEquals("image/png", guessNativeArticleMimeType("image.PNG#preview"))
        assertEquals("audio/mp4", guessNativeArticleMimeType("recording.m4a"))
        assertEquals("video/quicktime", guessNativeArticleMimeType("clip.mov"))
        assertEquals("application/octet-stream", guessNativeArticleMimeType("/articles/attachments/10/download"))
        assertEquals("application/octet-stream", guessNativeArticleMimeType("unknown"))
    }
}
