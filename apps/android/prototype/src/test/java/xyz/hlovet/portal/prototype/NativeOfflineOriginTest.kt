package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证来源规范化和各类本地存储键的隔离，不依赖 Android 文件系统或网络。 */
class NativeOfflineOriginTest {
    /** 同域名大小写、显式默认端口和不同 API 路径应归入同一来源。 */
    @Test
    fun equivalentOriginsKeepTheSameNamespace() {
        // 来源以协议、域名和有效端口为准，不以 URL 展示形式拆分缓存。
        assertEquals("https://example.com:443/", NativeOfflineOrigin.origin("https://EXAMPLE.com/api"))
        assertEquals(NativeOfflineOrigin.key("content|user:11", "https://example.com/api"),
            NativeOfflineOrigin.key("content|user:11", "https://example.com:443/other"))
    }

    /** 协议、域名和非默认端口变化必须生成不同的存储键。 */
    @Test
    fun protocolHostAndPortSeparateTheSameIdentity() {
        // 不同服务器可能具有相同账号与文章 ID，必须在来源层分开。
        val origins = listOf("https://example.com", "http://example.com", "https://other.example", "https://example.com:444")
        assertEquals(origins.size, origins.map { NativeOfflineOrigin.key("content|user:11", it) }.distinct().size)
    }

    /** 非 HTTP 来源、带账号的 URL 和非法端口不能退回默认生产地址。 */
    @Test
    fun invalidOriginsAreRejected() {
        // 非法配置必须显式失败，不能读写另一个合法服务器的缓存。
        listOf("", "not-a-url", "file:///tmp/cache", "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536")
            .forEach { assertTrue(it, runCatching { NativeOfflineOrigin.key("preview|public", it) }.isFailure) }
    }

    /** 匿名、账号及其它业务类型不能共享同一个键。 */
    @Test
    fun ownersAndStorageKindsHaveIndependentKeys() {
        // 正文、首页、媒体和草稿分别保留各自的账号维度。
        val identities = listOf("content|public", "content|user:11", "content|user:12", "preview|public", "media|public|https://cdn.example/a")
        assertEquals(identities.size, identities.map { NativeOfflineOrigin.key(it, "https://example.com") }.distinct().size)
    }

    /** 存储键只包含版本前缀和摘要，不泄露输入文本或 URL。 */
    @Test
    fun keysContainOnlyVersionAndDigest() {
        // 不能把账号名称或 URL 直接作为磁盘文件名。
        assertTrue(NativeOfflineOrigin.key("content|sensitive-owner", "https://example.com").matches(Regex("v2-[a-f0-9]{64}")))
    }

    /** 同一服务器的新文章、既有文章和不同账号必须使用不同草稿键。 */
    @Test
    fun localDraftsAreAccountAndArticleScoped() {
        // 未发布文章使用 new 标识，不能覆盖已有文章或另一个账号的正文。
        assertNotEquals(nativeLocalDraftKey(11, null, "https://example.com"), nativeLocalDraftKey(11, 1, "https://example.com"))
        assertNotEquals(nativeLocalDraftKey(11, 1, "https://example.com"), nativeLocalDraftKey(12, 1, "https://example.com"))
    }

    /** 不同服务器上相同用户和文章 ID 的草稿必须分离。 */
    @Test
    fun localDraftsDoNotCrossServers() {
        // 来源维度需要进入实际写作页使用的键函数，不只验证通用摘要工具。
        assertNotEquals(nativeLocalDraftKey(11, 1, "https://alpha.example"), nativeLocalDraftKey(11, 1, "https://beta.example"))
    }

    /** 默认来源函数必须与显式构建来源一致。 */
    @Test
    fun defaultNamespaceUsesTheConfiguredApiOrigin() {
        // 生产与预览构建均从 BuildConfig 取来源，不另写生产兜底常量。
        assertEquals(NativeOfflineOrigin.key("preview|public", BuildConfig.API_BASE_URL), NativeOfflineOrigin.key("preview|public"))
        assertEquals(nativeLocalDraftKey(11, 1, BuildConfig.API_BASE_URL), nativeLocalDraftKey(11, 1))
    }
}
