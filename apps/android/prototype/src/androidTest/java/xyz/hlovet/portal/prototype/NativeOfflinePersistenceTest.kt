package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOfflinePersistenceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var originalProxySelector: ProxySelector? = null

    @Before
    /** 使用设备内 IPv4 回环服务器，避免模拟器的 localhost 地址族选择不一致。 */
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "offline-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        // BlueStacks 会把本地 HTTP 交给设备代理，测试夹具必须直连设备回环地址。
        originalProxySelector = ProxySelector.getDefault()
        ProxySelector.setDefault(directProxySelector())
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        originalProxySelector?.let(ProxySelector::setDefault)
        root.deleteRecursively()
    }

    @Test
    fun savedArticleRoundTripsWithoutNetworkAndIsAccountIsolated() {
        val original = article().copy(
            contentFormat = "html", content = "<p>Saved body</p>",
            visibility = "roles", allowedRoleCodes = listOf("member"), pinOrder = 3,
            readingProgress = 42, lastReadAt = "2026-10-13T00:00:00Z",
            contentSegments = listOf(RemoteArticleSegment("html", "<p>Saved body</p>"), RemoteArticleSegment("resource", key = "locked", pointCost = 10, unlocked = false)),
            resourceEnabled = true, resourceBlocks = listOf(RemoteResourceBlock("locked", 10, false)),
        )
        NativeOfflineContentStore.saveArticle(context, "user:11", original)
        assertEquals(original, NativeOfflineContentStore.loadArticle(context, "user:11", original.slug))
        assertNull(NativeOfflineContentStore.loadArticle(context, "user:12", original.slug))
        assertNull(NativeOfflineContentStore.loadArticle(context, "public", original.slug))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun topicAndCollectionRoundTripAndCannotBeDeletedByAnotherOwner() {
        val topic = RemoteTopic(1, "Topic", "Description", 1, 2, listOf(article()), slug = "topic")
        val collection = RemoteCollection(2, "Collection", "Description", 1, 3, "Owner", listOf(article()))
        val entry = NativeOfflineContentStore.saveTopic(context, "user:11", topic)
        NativeOfflineContentStore.saveCollection(context, "user:11", collection)
        assertEquals(topic, NativeOfflineContentStore.loadTopic(context, "user:11", "topic"))
        assertEquals(collection, NativeOfflineContentStore.loadCollection(context, "user:11", 2))
        NativeOfflineContentStore.delete(context, "user:12", entry)
        NativeOfflineContentStore.clear(context, "user:12")
        assertEquals(2, NativeOfflineContentStore.list(context, "user:11").size)
    }

    @Test
    fun concurrentSavesKeepEveryIndexEntryAndRefreshDoesNotEraseOtherSaves() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..20).map { id -> executor.submit {
                NativeOfflineContentStore.saveArticle(context, "user:11", article(id))
            } }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(20, NativeOfflineContentStore.list(context, "user:11").size)
            NativeOfflineContentStore.saveArticle(context, "user:11", article(1).copy(content = "Updated"))
            assertEquals(20, NativeOfflineContentStore.list(context, "user:11").size)
            assertEquals("Updated", NativeOfflineContentStore.loadArticle(context, "user:11", "article-1")?.content)
            assertEquals(article(20), NativeOfflineContentStore.loadArticle(context, "user:11", "article-20"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun interruptedLegacyAtomicWriteRestoresTheLastSavedBodyAndIndex() {
        val original = article()
        val entry = NativeOfflineContentStore.saveArticle(context, "user:11", original)
        val directory = File(context.filesDir, "offline-content")
        val body = File(directory, entry.fileName)
        val index = directory.listFiles()!!.single { it.name.endsWith("-index.json") }
        body.copyTo(File(body.path + ".bak"))
        index.copyTo(File(index.path + ".bak"))
        body.writeText("interrupted body")
        index.writeText("interrupted index")
        assertEquals(original, NativeOfflineContentStore.loadArticle(context, "user:11", original.slug))
        assertEquals(1, NativeOfflineContentStore.list(context, "user:11").size)
    }

    @Test
    fun cachedMediaIsReadableWithoutNetworkAndDoesNotCrossAccounts() {
        val url = serverUrl("/media/document.pdf")
        server.enqueue(MockResponse().setBody("saved attachment"))
        assertNotNull(download(url, "user:11"))
        assertEquals("saved attachment", NativeOfflineCache.loadCachedMediaFile(context, url, "user:11")?.readText())
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "user:12"))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "public"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun offlineWebViewReturnsCachedMediaOrExplicitMissWithoutNetworking() {
        val url = serverUrl("/media/document.pdf")
        server.enqueue(MockResponse().setBody("saved attachment"))
        download(url, "user:11")
        val client = nativeArticleBodyWebViewClient(context, "test-token", "user:11", true, {})
        val hit = client.shouldInterceptRequest(null, request(url))
        assertNotNull(hit)
        assertEquals("saved attachment", hit!!.data.bufferedReader().use { it.readText() })
        val miss = client.shouldInterceptRequest(null, request(serverUrl("/uncached.png")))
        assertEquals(404, miss?.statusCode)
        assertEquals(0, miss!!.data.use { it.readBytes().size })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun usernameMigrationMovesSavedBodyAndItsMediaToStableOwnerIdempotently() {
        val url = serverUrl("/media/photo.png")
        val original = article().copy(contentFormat = "html", content = "<img src=\"$url\">", images = listOf(url))
        NativeOfflineContentStore.saveArticle(context, "old-name", original)
        NativeOfflineContentStore.saveArticle(context, "user:11", article(2))
        server.enqueue(MockResponse().setBody("saved image"))
        download(url, "old-name")
        val session = NativeSession(
            accessToken = "test-token", refreshToken = "test-refresh", userId = 11, username = "new-name", nickname = "Test",
            appearance = PreviewAppearance("cloud-blue", "#0284c7", "#1f2937", "#52616f", "#ffffff", 50, 18, "#fff3f6", 0),
        )
        NativeOfflineOwner.migrate(context, session, "old-name")
        NativeOfflineOwner.migrate(context, session, "old-name")
        assertEquals(original, NativeOfflineContentStore.loadArticle(context, "user:11", original.slug))
        assertEquals(2, NativeOfflineContentStore.list(context, "user:11").size)
        assertTrue(NativeOfflineContentStore.list(context, "old-name").isEmpty())
        assertEquals("saved image", NativeOfflineCache.loadCachedMediaFile(context, url, "user:11")?.readText())
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "old-name"))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "public"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mediaEvictionStaysWithinBudgetWithoutDeletingSavedText() {
        val original = article()
        NativeOfflineContentStore.saveArticle(context, "user:11", original)
        val urls = (1..5).map { serverUrl("/media/file-$it.bin") }
        urls.forEach { url ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(7 * 1024 * 1024) { 1 })))
            assertNotNull(download(url, "user:11"))
        }
        val summary = NativeOfflineCache.mediaCacheSummary(context)
        assertTrue(summary.bytes <= summary.maxBytes)
        assertEquals(4, summary.fileCount)
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, urls.first(), "user:11"))
        assertNotNull(NativeOfflineCache.loadCachedMediaFile(context, urls.last(), "user:11"))
        assertEquals(original, NativeOfflineContentStore.loadArticle(context, "user:11", original.slug))
    }

    @Test
    fun oversizeDownloadLeavesNoBrokenCacheEntryAndExternalMediaReceivesNoToken() {
        val url = serverUrl("/media/large.bin")
        server.enqueue(MockResponse().setBody("too large"))
        val file = NativeOfflineCache.loadMediaFile(context, url, "test-token", "user:11", "large.bin", "application/octet-stream", maxBytes = 3)
        assertNull(file)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "user:11"))
        assertEquals(0, NativeOfflineCache.mediaCacheSummary(context).fileCount)
        assertFalse(File(context.cacheDir, "media-temp").listFiles().orEmpty().any { it.isFile })
    }

    /** 将 MockWebServer 的 localhost 地址固定为 IPv4 回环地址，绕过模拟器代理解析。 */
    private fun serverUrl(path: String): String = server.url(path).toString().replace("://localhost:", "://127.0.0.1:")

    /** 下载 url 到当前测试夹具的账号媒体缓存，失败返回 null。 */
    private fun download(url: String, owner: String): File? = NativeOfflineCache.loadMediaFile(
        context, url, "test-token", owner, "media.bin", "application/octet-stream",
    )

    /** 创建仅供本测试夹具使用的直连代理选择器。 */
    private fun directProxySelector(): ProxySelector = object : ProxySelector() {
        /** 为 MockWebServer 地址返回直连策略。 */
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)

        /** 直连策略没有可移除的代理故障记录。 */
        override fun connectFailed(uri: URI, address: SocketAddress, failure: java.io.IOException) = Unit
    }

    private fun article(id: Int = 1) = RemoteArticle(
        id = id, title = "Article $id", slug = "article-$id", summary = "Summary", content = "Saved text",
        category = "Test", tags = listOf("test"), viewCount = 1, likeCount = 2, favoriteCount = 3,
        commentCount = 4, authorName = "Author", authorUsername = "author",
    )

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
