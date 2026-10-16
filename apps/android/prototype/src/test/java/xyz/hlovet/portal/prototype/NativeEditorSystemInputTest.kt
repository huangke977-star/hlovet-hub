package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证系统剪贴板 URI 降级兼容只扩大文件入口，不改变普通文字输入语义。 */
class NativeEditorSystemInputTest {
    /** 合法 content URI 应被恢复，后续读取仍由系统授权和上传流程决定。 */
    @Test fun acceptsStructuredContentUriText() {
        // 只检查地址结构，不在 JVM 测试中伪造 ContentProvider 或文件权限。
        assertEquals(
            "content://example.provider/document/42",
            nativeEditorContentUriText(" content://example.provider/document/42 "),
        )
    }

    /** 普通文本、网页和本地路径不能被当作附件候选。 */
    @Test fun rejectsNonContentText() {
        // 粘贴正文必须继续交给 WebView 的 HTML/纯文本净化流程。
        assertNull(nativeEditorContentUriFromText("content body"))
        assertNull(nativeEditorContentUriFromText("https://example.com/file.png"))
        assertNull(nativeEditorContentUriFromText("C:\\Users\\mk\\file.png"))
        assertNull(nativeEditorContentUriFromText("content://example.provider/file name"))
    }

    /** 多余描述或超长值不能绕过候选地址边界。 */
    @Test fun rejectsMixedOrOversizedText() {
        // 文件同步服务必须提供完整 URI，不能从带说明的多行文本中猜测路径。
        assertNull(nativeEditorContentUriFromText("文件: content://example.provider/1"))
        assertNull(nativeEditorContentUriFromText("content://example.provider/1\nnext"))
        assertNull(nativeEditorContentUriFromText("content://example.provider/" + "x".repeat(2049)))
    }
}
