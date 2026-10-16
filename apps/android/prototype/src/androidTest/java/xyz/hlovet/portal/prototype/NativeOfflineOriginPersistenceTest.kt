package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在独立目录和偏好命名空间验证真实离线存储、来源隔离及界面反馈，不读取或清理用户缓存。 */
@RunWith(AndroidJUnit4::class)
class NativeOfflineOriginPersistenceTest {
    @get:Rule val compose = createComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var app: Context
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var oldLocale = "zh-CN"
    private val sourceA = "https://alpha.example/api"
    private val sourceB = "https://beta.example/api"
    private val owner = "user:11"

    /** 为每条用例创建独立文件目录、偏好和媒体服务，保留应用已登录会话及实际缓存。 */
    @Before
    fun setUp() {
        // 测试只操作新建夹具目录，服务器地址仅用于来源键，不向真实站点发写请求。
        app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "offline-origin-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = isolatedContext()
        server = MockWebServer().apply { start() }
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 先销毁组合，再关闭媒体服务和清理本用例夹具，不影响已有应用数据。 */
    @After
    fun tearDown() {
        // 夹具路径必须仍位于本应用测试缓存目录，禁止清理用户离线目录或会话。
        try { host.dispose() }
        finally {
            try { server.shutdown() }
            finally {
                context.getSharedPreferences("native-article-drafts", Context.MODE_PRIVATE).edit().clear().commit()
                check(requireNotNull(root.parentFile).canonicalPath == app.cacheDir.canonicalPath)
                root.deleteRecursively()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 相同账号、文章 slug、专题 slug 和合集 ID 在两站独立保存并能在重建上下文后回读。 */
    @Test
    fun allContentKindsSurviveReloadWithoutCrossingServers() {
        // 用相同稳定 ID 写入不同正文，确保隔离来自服务器来源而不是不同业务标识。
        for ((origin, marker) in listOf(sourceA to "Alpha", sourceB to "Beta")) {
            val body = article(marker)
            NativeOfflineContentStore.saveArticle(context, owner, body, origin)
            NativeOfflineContentStore.saveTopic(context, owner, topic(marker), origin)
            NativeOfflineContentStore.saveCollection(context, owner, collection(marker), origin)
            assertEquals(3, NativeOfflineContentStore.list(context, owner, origin).size)
        }
        // 重建 Context 后读取真实磁盘文件，不使用预先注入的内存条目。
        val reopened = isolatedContext()
        for ((origin, marker) in listOf(sourceA to "Alpha", sourceB to "Beta")) {
            assertEquals(article(marker), NativeOfflineContentStore.loadArticle(reopened, owner, "same-article", origin))
            assertEquals(topic(marker), NativeOfflineContentStore.loadTopic(reopened, owner, "same-topic", origin))
            assertEquals(collection(marker), NativeOfflineContentStore.loadCollection(reopened, owner, 2, origin))
            assertNull(NativeOfflineContentStore.loadArticle(reopened, "public", "same-article", origin))
        }
        assertEquals(0, server.requestCount)
    }

    /** 同 ID 条目不能跨站删除，清空本站后仍能切回另一站读取已保存正文。 */
    @Test
    fun deleteAndClearAffectOnlyTheRequestedServerAndAccount() {
        // 两站使用同一账号和内容 ID，并额外保存一个其它账号条目。
        val entryA = NativeOfflineContentStore.saveArticle(context, owner, article("Alpha"), sourceA)
        NativeOfflineContentStore.saveArticle(context, owner, article("Beta"), sourceB)
        NativeOfflineContentStore.saveArticle(context, "user:12", article("Another account"), sourceA)
        NativeOfflineContentStore.delete(context, owner, entryA, sourceB)
        assertEquals(article("Beta"), NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceB))
        // 当前站清空只影响其所属账号，不按目录批量删除其它来源文件。
        NativeOfflineContentStore.clear(context, owner, sourceA)
        assertTrue(NativeOfflineContentStore.list(context, owner, sourceA).isEmpty())
        assertEquals(article("Beta"), NativeOfflineContentStore.loadArticle(isolatedContext(), owner, "same-article", sourceB))
        assertEquals(article("Another account"), NativeOfflineContentStore.loadArticle(context, "user:12", "same-article", sourceA))
    }

    /** 匿名和登录首页分别隔离，复制另一站的首页文件不能通过来源元数据校验。 */
    @Test
    fun previewCachesAreOriginAndOwnerScopedAndRejectCopiedPayloads() {
        // 匿名首页也必须包含来源边界，不使用全局 preview-data.json 兜底。
        NativeOfflineCache.savePreview(context, preview("Alpha public"), "public", sourceA)
        assertNull(NativeOfflineCache.loadPreview(context, "public", sourceB))
        NativeOfflineCache.savePreview(context, preview("Beta public"), "public", sourceB)
        NativeOfflineCache.savePreview(context, preview("Alpha member"), owner, sourceA)
        assertEquals(preview("Alpha member"), NativeOfflineCache.loadPreview(isolatedContext(), owner, sourceA))
        assertEquals(preview("Alpha public"), NativeOfflineCache.loadPreview(context, "public", sourceA))
        assertEquals(preview("Beta public"), NativeOfflineCache.loadPreview(context, "public", sourceB))
        // 文件路径和元数据两层核对来源，复制到合法目标文件名仍应拒绝读取。
        previewFile("public", sourceA).copyTo(previewFile("public", sourceB), overwrite = true)
        assertNull(NativeOfflineCache.loadPreview(context, "public", sourceB))
    }

    /** 索引被跨站复制或加入外部相对路径时，不可引用和删除原文件。 */
    @Test
    fun copiedIndexesCannotReferenceForeignFilesOrEscapeTheDirectory() {
        // 复制索引但不改正文文件名，目标来源不能把它列成有效条目。
        val entry = NativeOfflineContentStore.saveArticle(context, owner, article("Alpha"), sourceA)
        val directory = File(context.filesDir, "offline-content")
        val indexA = File(directory, NativeOfflineOrigin.key("content|$owner", sourceA) + "-index.json")
        val indexB = File(directory, NativeOfflineOrigin.key("content|$owner", sourceB) + "-index.json")
        indexA.copyTo(indexB)
        assertTrue(NativeOfflineContentStore.list(context, owner, sourceB).isEmpty())
        // 索引中的 fileName 必须与来源、类型及 ID 计算结果一致，不能通过 ../ 指向其它文件。
        val sentinel = File(context.filesDir, "sentinel.txt").apply { writeText("untouched") }
        val values = JSONArray(indexB.readText())
        values.getJSONObject(0).put("fileName", "../sentinel.txt")
        indexB.writeText(values.toString())
        NativeOfflineContentStore.clear(context, owner, sourceB)
        assertEquals("untouched", sentinel.readText())
        assertEquals(article("Alpha"), NativeOfflineContentStore.loadArticle(context, owner, entry.id, sourceA))
    }

    /** 用户名迁移只合并本站数据，另一站同名所有者、首页及媒体保持原样。 */
    @Test
    fun usernameMigrationNeverMovesAnotherServersContentOrMedia() {
        // 相同媒体 URL 和旧用户名分别写入两站来源，防止依靠 URL 差异伪造隔离。
        val url = server.url("/same.png").toString()
        for ((origin, marker) in listOf(sourceA to "Alpha", sourceB to "Beta")) {
            NativeOfflineContentStore.saveArticle(context, "old-name", article(marker).copy(images = listOf(url)), origin)
            NativeOfflineCache.savePreview(context, preview(marker), "old-name", origin)
            server.enqueue(MockResponse().setBody(marker))
            assertNotNull(download(url, "old-name", origin))
        }
        // 只迁移 A 站，重复执行保持幂等，不对 B 站或历史无来源数据做任何操作。
        NativeOfflineOwner.migrate(context, session(), "old-name", sourceA)
        NativeOfflineOwner.migrate(context, session(), "old-name", sourceA)
        assertEquals("Alpha", NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceA)?.title)
        assertTrue(NativeOfflineContentStore.list(context, "old-name", sourceA).isEmpty())
        assertEquals("Beta", NativeOfflineContentStore.loadArticle(context, "old-name", "same-article", sourceB)?.title)
        assertEquals(preview("Alpha"), NativeOfflineCache.loadPreview(context, owner, sourceA))
        assertEquals(preview("Beta"), NativeOfflineCache.loadPreview(context, "old-name", sourceB))
        assertEquals("Alpha", NativeOfflineCache.loadCachedMediaFile(context, url, owner, sourceA)?.readText())
        assertEquals("Beta", NativeOfflineCache.loadCachedMediaFile(context, url, "old-name", sourceB)?.readText())
        assertEquals(2, server.requestCount)
    }

    /** 同一绝对媒体 URL 不能在不同服务器和账号间共用受保护缓存，离线命中不联网。 */
    @Test
    fun mediaCachesSeparateTheSameUrlAcrossServersAndAccounts() {
        // 先保存 A 站，同 URL 在 B 站和公共所有者下都应未命中。
        val url = server.url("/same.pdf").toString()
        server.enqueue(MockResponse().setBody("Alpha attachment"))
        assertNotNull(download(url, owner, sourceA))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, owner, sourceB))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "public", sourceA))
        server.enqueue(MockResponse().setBody("Beta attachment"))
        assertNotNull(download(url, owner, sourceB))
        // 重建上下文和多次切换来源仍读取各自文件，网络请求仅为两次显式下载。
        assertEquals("Alpha attachment", NativeOfflineCache.loadCachedMediaFile(isolatedContext(), url, owner, sourceA)?.readText())
        assertEquals("Beta attachment", NativeOfflineCache.loadCachedMediaFile(context, url, owner, sourceB)?.readText())
        assertEquals(2, server.requestCount)
    }

    /** 相对附件路径按显式 API 来源解析，服务器端口不同不会共用文件。 */
    @Test
    fun relativeMediaPathsFollowTheirApiSource() {
        // 用同一测试服务的不同主机来源发出真实下载，路径本身完全相同。
        val originA = "http://127.0.0.1:${server.port}"
        val originB = "http://localhost:${server.port}"
        server.enqueue(MockResponse().setBody("IP source"))
        assertNotNull(download("/same.bin", owner, originA))
        server.enqueue(MockResponse().setBody("Host source"))
        assertNotNull(download("/same.bin", owner, originB))
        assertEquals("IP source", NativeOfflineCache.loadCachedMediaFile(context, "/same.bin", owner, originA)?.readText())
        assertEquals("Host source", NativeOfflineCache.loadCachedMediaFile(context, "/same.bin", owner, originB)?.readText())
        assertEquals(2, server.requestCount)
    }

    /** 默认构建中相对 /api 路径继续按网页来源解析，API/网页分域不影响账号媒体迁移。 */
    @Test
    fun usernameMigrationPreservesConfiguredPortalMediaResolution() {
        // 缓存文件按真实媒体解析结果建立，适用于 API 和网页分别监听 3001/3000 的预览构建。
        val path = "/api/relative-photo.png"
        val url = nativeMediaUrl(path)
        NativeOfflineContentStore.saveArticle(context, "old-name", article("Relative media").copy(images = listOf(path)))
        val directory = File(context.filesDir, "media-cache").apply { mkdirs() }
        File(directory, NativeOfflineOrigin.key("media|old-name|$url") + ".png").writeText("retained relative image")
        // 改名只改变所有者键，不重写网页与 API 的地址关系，也不触发网络下载。
        NativeOfflineOwner.migrate(context, session(), "old-name")
        assertEquals("retained relative image", NativeOfflineCache.loadCachedMediaFile(context, url, owner)?.readText())
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, url, "old-name"))
        assertEquals(0, server.requestCount)
    }

    /** 旧版正文、首页、媒体和草稿既不能被当前来源读取，也不能被迁移或清空操作删除。 */
    @Test
    fun unscopedLegacyFilesAndDraftsRemainUntouchedButUnavailable() {
        // 构造实际旧版命名规则的文件，不能只用不存在的文件证明没有跨站兜底。
        val legacyFiles = createLegacyFiles()
        val preferences = context.getSharedPreferences("native-article-drafts", Context.MODE_PRIVATE)
        preferences.edit().putString("11:1", "legacy draft sentinel").commit()
        assertTrue(NativeOfflineOrigin.hasLegacyData(context))
        assertNull(NativeOfflineCache.loadPreview(context, "public", sourceA))
        assertNull(NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceA))
        assertNull(NativeOfflineCache.loadCachedMediaFile(context, "https://cdn.example/legacy.png", owner, sourceA))
        assertNull(readNativeLocalDraft(context, "11:1", sourceA))
        // 实际迁移和清空当前来源后，历史文件的字节必须保持不变。
        val original = legacyFiles.associateWith { it.readText() }
        NativeOfflineOwner.migrate(context, session(), "old-name", sourceA)
        NativeOfflineContentStore.clear(context, owner, sourceA)
        removeNativeLocalDraft(context, "11:1")
        original.forEach { (file, value) -> assertEquals(value, file.readText()) }
        assertEquals("legacy draft sentinel", preferences.getString("11:1", null))
        assertEquals(0, NativeOfflineCache.mediaCacheSummary(context).fileCount)
        assertTrue(NativeOfflineCache.mediaCacheSummary(context).legacyBytes > 0L)
        assertEquals(0, server.requestCount)
    }

    /** 同 ID 草稿在两站分别持久化，清除一站不删除其它站或旧版草稿。 */
    @Test
    fun draftsSurviveReloadAndRemovalOnlyAffectsOneSource() {
        // 使用写作页实际调用的键和偏好存储，验证正文不是仅在内存分开。
        val keyA = nativeLocalDraftKey(11, 1, sourceA)
        val keyB = nativeLocalDraftKey(11, 1, sourceB)
        writeNativeLocalDraft(context, keyA, draft("Alpha draft"), sourceA)
        writeNativeLocalDraft(context, keyB, draft("Beta draft"), sourceB)
        assertEquals(draft("Alpha draft"), readNativeLocalDraft(isolatedContext(), keyA, sourceA)?.input)
        assertEquals(draft("Beta draft"), readNativeLocalDraft(context, keyB, sourceB)?.input)
        assertNull(readNativeLocalDraft(context, keyA, sourceB))
        // 删除 A 站草稿后，B 站还能够完整恢复。
        removeNativeLocalDraft(context, keyA)
        assertNull(readNativeLocalDraft(context, keyA, sourceA))
        assertEquals(draft("Beta draft"), readNativeLocalDraft(context, keyB, sourceB)?.input)
    }

    /** 草稿 JSON 被复制到另一站正确的键时，来源元数据仍应阻止恢复。 */
    @Test
    fun copiedDraftPayloadDoesNotBecomeTheOtherSitesDraft() {
        // 文件名隔离之外还验证保存记录的来源，避免复制偏好值后误恢复正文。
        val keyA = nativeLocalDraftKey(11, 1, sourceA)
        val keyB = nativeLocalDraftKey(11, 1, sourceB)
        writeNativeLocalDraft(context, keyA, draft("Alpha private draft"), sourceA)
        val preferences = context.getSharedPreferences("native-article-drafts", Context.MODE_PRIVATE)
        preferences.edit().putString(keyB, preferences.getString(keyA, null)).commit()
        assertNull(readNativeLocalDraft(context, keyB, sourceB))
        assertEquals(draft("Alpha private draft"), readNativeLocalDraft(context, keyA, sourceA)?.input)
    }

    /** 有旧版备份副本时清空不能导致正文或索引重新出现。 */
    @Test
    fun clearRemovesAtomicBackupsWithoutResurrectingEntries() {
        // 模拟旧版本 AtomicFile 的 .bak 恢复路径，正文和索引都有可回读副本。
        val entry = NativeOfflineContentStore.saveArticle(context, owner, article("Alpha"), sourceA)
        val directory = File(context.filesDir, "offline-content")
        val body = File(directory, entry.fileName)
        val index = File(directory, NativeOfflineOrigin.key("content|$owner", sourceA) + "-index.json")
        body.copyTo(File(body.path + ".bak"))
        index.copyTo(File(index.path + ".bak"))
        // 清空需要删除主文件及原子副本，而不是让 load 再恢复已删除正文。
        NativeOfflineContentStore.clear(context, owner, sourceA)
        assertTrue(NativeOfflineContentStore.list(isolatedContext(), owner, sourceA).isEmpty())
        assertFalse(File(body.path + ".bak").exists())
        assertFalse(File(index.path + ".bak").exists())
    }

    /** 多站新媒体共用 32 MB 上限，淘汰不影响旧版留存文件或已保存正文。 */
    @Test
    fun sharedMediaBudgetDoesNotDeleteLegacyOrSavedText() {
        // 历史媒体不纳入新版淘汰集合，两站正文始终独立保留。
        val legacy = File(File(context.filesDir, "media-cache").apply { mkdirs() }, "legacy.bin").apply { writeText("retained legacy") }
        NativeOfflineContentStore.saveArticle(context, owner, article("Alpha"), sourceA)
        NativeOfflineContentStore.saveArticle(context, owner, article("Beta"), sourceB)
        for (index in 1..5) {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(7 * 1024 * 1024) { 1 })))
            assertNotNull(download(server.url("/large-$index.bin").toString(), owner, if (index % 2 == 0) sourceA else sourceB))
        }
        // 所有新来源共享固定预算，不为每个来源增加 32 MB 配额。
        val usage = NativeOfflineCache.mediaCacheSummary(context)
        assertTrue(usage.bytes <= usage.maxBytes)
        assertEquals(4, usage.fileCount)
        assertEquals("retained legacy", legacy.readText())
        assertEquals(legacy.length(), usage.legacyBytes)
        assertEquals(article("Alpha"), NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceA))
        assertEquals(article("Beta"), NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceB))
    }

    /** 离线界面只显示本站内容，并以共享样式提示保留的来源不明缓存。 */
    @Test
    fun offlineScreenWarnsAboutLegacyCacheAndClearsOnlyThisSite() {
        // 真实挂载离线页，另一来源同账号同 ID 条目不能出现在列表中。
        createLegacyFiles()
        NativeOfflineContentStore.saveArticle(context, owner, article("Current site"))
        NativeOfflineContentStore.saveArticle(context, owner, article("Other site"), sourceA)
        host.setContent { HlovetTheme { NativeOfflineScreen(session(), context, {}, {}, {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Current site").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Current site").assertIsDisplayed()
        compose.onNodeWithText("Other site").assertDoesNotExist()
        compose.onNodeWithText("来源不明的旧版缓存已保留", substring = true).assertIsDisplayed()
        saveScreenshot("legacy-warning.png")
        // 界面确认后实际调用清空，另一站正文和旧版文件都继续保留。
        compose.onNodeWithContentDescription("清空离线内容").performClick()
        compose.onNodeWithText("清空").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Current site").fetchSemanticsNodes().isEmpty() }
        assertEquals(article("Other site"), NativeOfflineContentStore.loadArticle(context, owner, "same-article", sourceA))
        assertTrue(NativeOfflineOrigin.hasLegacyData(context))
        saveScreenshot("current-site-cleared.png")
    }

    /** 创建共享同一夹具磁盘和偏好的新 Context，用于验证重建后回读。 */
    private fun isolatedContext(): Context =
        // 将文件与偏好统一映射到当前用例命名空间，不改变业务存储方法本身。
        object : ContextWrapper(app) {
        /** 返回本用例独立正文目录，不使用应用真实 filesDir。 */
        override fun getFilesDir(): File {
            // 测试文件均位于临时夹具根目录下。
            return File(root, "files").apply { mkdirs() }
        }
        /** 返回本用例独立媒体临时目录。 */
        override fun getCacheDir(): File {
            // 下载临时文件不能和用户媒体缓存混合。
            return File(root, "cache").apply { mkdirs() }
        }
        /** 将业务 name 偏好映射到独立夹具命名空间，mode 沿用调用方设置。 */
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            // 同一用例重建共享夹具偏好，但不会读取真实未提交草稿。
            return app.getSharedPreferences("${root.name}-$name", mode)
        }
    }

    /** 下载 url 到指定 cacheOwner 和 origin 的真实媒体缓存，返回命中文件或 null。 */
    private fun download(url: String, cacheOwner: String, origin: String): File? {
        // 测试使用 MockWebServer 文件，不向外部供应商下载或发送真实令牌。
        return NativeOfflineCache.loadMediaFile(context, url, "fixture-token", cacheOwner, "media.bin", "application/octet-stream", apiBaseUrl = origin)
    }

    /** 创建 marker 正文的文章，固定 ID 和 slug 以检验来源而非业务 ID 差异。 */
    private fun article(marker: String): RemoteArticle {
        // 所有来源使用相同稳定标识，只有展示内容不同。
        return RemoteArticle(id = 1, title = marker, slug = "same-article", summary = "Summary", content = marker,
            category = "Test", tags = listOf("test"), viewCount = 1, likeCount = 2, favoriteCount = 3, commentCount = 4,
            authorName = "Author", authorUsername = "author")
    }

    /** 创建固定 ID 和 slug 的 marker 专题及其文章清单。 */
    private fun topic(marker: String): RemoteTopic {
        // 专题名称不是去重标识，不同来源可使用相同 slug。
        return RemoteTopic(1, marker, "Description", 1, 2, listOf(article(marker)), slug = "same-topic")
    }

    /** 创建固定 ID 的 marker 合集及其文章清单。 */
    private fun collection(marker: String): RemoteCollection {
        // 合集 ID 仅在所属服务器命名空间内稳定。
        return RemoteCollection(2, marker, "Description", 1, 3, "Owner", listOf(article(marker)))
    }

    /** 创建不含真实凭据的账号 11 及共享主题测试会话。 */
    private fun session(): NativeSession {
        // 只用于离线所有者和界面展示，不登录任何服务器。
        return NativeSession(accessToken = "fixture-token", refreshToken = "fixture-refresh", username = "new-name",
            nickname = "Fixture", appearance = appearance(), userId = 11)
    }

    /** 返回各类夹具共用的现有主题配置。 */
    private fun appearance(): PreviewAppearance {
        // 使用已有设计令牌，不引入单页主题。
        return PreviewAppearance("cloud-blue", "#0284c7", "#1f2937", "#52616f", "#ffffff", 50, 18, "#fff3f6", 0)
    }

    /** 创建 marker 首页缓存数据，覆盖文章、专题、合集和背景。 */
    private fun preview(marker: String): PreviewData {
        // 使用完整真实序列化字段检查缓存往返。
        return PreviewData(listOf(article(marker)), listOf(topic(marker)), listOf(collection(marker)), appearance(), "https://cdn.example/background.png")
    }

    /** 返回 origin 下 cacheOwner 的实际首页文件，供跨来源拷贝校验使用。 */
    private fun previewFile(cacheOwner: String, origin: String): File {
        // 文件名来自生产存储键函数，不复制摘要算法猜测目标。
        return File(context.filesDir, "preview-${NativeOfflineOrigin.key("preview|$cacheOwner", origin)}.json")
    }

    /** 创建带 marker 的完整本地草稿输入。 */
    private fun draft(marker: String): NativeDraftInput {
        // 正文及可见范围一起回读，不能仅靠标题证明恢复成功。
        return NativeDraftInput(marker, "Summary", "<p>$marker</p>", "html", "Test", "one,two", "#123456", "roles", listOf("member"))
    }

    /** 构造旧版所有者摘要对应的首页、正文索引及媒体文件，返回需保留的全部文件。 */
    private fun createLegacyFiles(): List<File> {
        // 使用真实旧版命名而非随意文件名，防止测试漏掉回退读取和迁移路径。
        val oldKey = legacyHash(owner).take(24)
        val directory = File(context.filesDir, "offline-content").apply { mkdirs() }
        val bodyName = "$oldKey-${legacyHash("article|same-article").take(32)}.json"
        val body = File(directory, bodyName).apply { writeText(JSONObject().put("id", 1).put("slug", "same-article").put("title", "Legacy private").toString()) }
        val index = File(directory, "$oldKey-index.json").apply {
            writeText(JSONArray().put(JSONObject().put("kind", "article").put("id", "same-article").put("title", "Legacy private")
                .put("fileName", bodyName).put("cachedAt", 1).put("route", "/articles/same-article")).toString())
        }
        val preview = File(context.filesDir, "preview-data.json").apply {
            writeText(JSONObject().put("articles", JSONArray()).put("topics", JSONArray()).put("collections", JSONArray())
                .put("appearance", JSONObject()).put("backgroundUrl", "https://legacy.example/background.png").toString())
        }
        val media = File(File(context.filesDir, "media-cache").apply { mkdirs() }, legacyHash("$owner|https://cdn.example/legacy.png") + ".bin")
            .apply { writeText("legacy attachment") }
        return listOf(body, index, preview, media)
    }

    /** 按旧版规则为 value 计算摘要，仅用于构造历史文件夹具。 */
    private fun legacyHash(value: String): String {
        // 测试必须与旧命名规则独立一致，不能用新版本键模拟所谓旧数据。
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 将当前真实离线界面截图保存为 name，报告中不包含账号凭据。 */
    private fun saveScreenshot(name: String) {
        // 截图写入仪器测试附加输出，保留界面证据但不写用户缓存。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath
        val directory = File(output, "offline-origin-evidence").apply { mkdirs() }
        File(directory, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
