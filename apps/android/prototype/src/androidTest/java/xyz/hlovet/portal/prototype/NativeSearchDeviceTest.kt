package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.Collections

/** 在设备回环接口验证全站搜索的多类型结果、过滤排序、分页和文章点击分发。 */
@RunWith(AndroidJUnit4::class)
class NativeSearchDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createNativeComposeRule()

    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val requestPaths = Collections.synchronizedList(mutableListOf<String>())
    private var oldLocale = "zh-CN"

    /** 只允许专用回环构建启动搜索夹具，避免测试请求真实站点。 */
    @Before
    fun startFixture() {
        // 地址不匹配时主动跳过，不能把生产 API 当作测试服务或写入真实搜索记录。
        org.junit.Assume.assumeTrue("Requires a device-local preview build", BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            // 搜索接口按请求范围返回相应类型，页面仍通过真实 NativeSearchApi 发起请求和解析结果。
            dispatcher = object : Dispatcher() {
                /** 根据搜索路径返回可重复的多类型及分页夹具。 */
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
            start(InetAddress.getByName("127.0.0.1"), 39871)
        }
    }

    /** 释放 Compose 和回环服务，恢复测试前语言，避免污染后续设备用例。 */
    @After
    fun stopFixture() {
        if (!::server.isInitialized) return
        try {
            host.dispose()
        } finally {
            try {
                server.shutdown()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 验证全站搜索结果可以打开文章，并通过显式分页按钮读取第二页。 */
    @Test
    fun publicSearchDispatchesArticleAndPaginates() {
        // 用公开搜索验证无需账号也能读取允许公开展示的多类型结果。
        val openedArticles = mutableListOf<String>()
        host.setContent {
            HlovetTheme {
                NativeSearchScreen(
                    session = null,
                    initialQuery = "guide",
                    onBack = {},
                    onOpenArticle = { openedArticles += it },
                    onOpenTopic = {},
                    onOpenCollection = {},
                    onOpenUser = {},
                    onOpenAnnouncement = {},
                    onOpenGroup = {},
                    onOpenEntry = {},
                )
            }
        }
        waitTag("native-search-input")
        waitTag("native-search-article-1")
        synchronized(requestPaths) { check(requestPaths.any { it.contains("scope=all") && it.contains("page=1") }) }

        // 文章结果行必须把稳定 slug 交给宿主，不能只改变搜索页面的本地状态。
        compose.onNodeWithTag("native-search-article-1").performClick()
        assertEquals(listOf("guide-1"), openedArticles)

        // 分页按钮位于结果尾部，滚动到它再点击，确认第二页请求和结果都来自真实接口。
        val nextPage = hasTestTag("native-search-next-page") and hasClickAction()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("native-search-list").performScrollToNode(nextPage)
                true
            }.getOrDefault(false)
        }
        compose.onNode(nextPage).performClick()
        waitRequest(predicate = { it.contains("scope=all") && it.contains("page=2") })
        // 翻页后列表仍停留在底部，先把新页首条文章滚回语义树，再确认第二页内容已渲染。
        val secondPageArticle = hasTestTag("native-search-article-2")
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("native-search-list").performScrollToNode(secondPageArticle)
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithTag("native-search-article-2").assertIsDisplayed()
    }

    /** 验证范围、分类和排序切换都会重置分页并把条件传给服务端。 */
    @Test
    fun publicSearchAppliesScopeCategoryAndSort() {
        // 初始化全站结果，随后逐项切换范围，避免仅通过静态 UI 存在性判断筛选有效。
        host.setContent {
            HlovetTheme {
                NativeSearchScreen(
                    session = null,
                    initialQuery = "guide",
                    onBack = {},
                    onOpenArticle = {},
                    onOpenTopic = {},
                    onOpenCollection = {},
                    onOpenUser = {},
                    onOpenAnnouncement = {},
                    onOpenGroup = {},
                    onOpenEntry = {},
                )
            }
        }
        waitTag("native-search-scope-users")

        // 用户范围只展示用户结果，确认范围值不是停留在客户端而是进入请求参数。
        compose.onNodeWithTag("native-search-scope-users").performClick()
        waitText("测试用户")
        synchronized(requestPaths) { check(requestPaths.any { it.contains("scope=users") && it.contains("page=1") }) }

        // 专题范围切换后重新读取专题结果，验证多类型分发至少覆盖另一类站内资源。
        compose.onNodeWithTag("native-search-scope-topics").performClick()
        waitText("测试专题")
        synchronized(requestPaths) { check(requestPaths.any { it.contains("scope=topics") && it.contains("page=1") }) }

        // 文章范围返回分类选项；分类和排序动作都应把分页重新置为第一页。
        compose.onNodeWithTag("native-search-scope-articles").performClick()
        waitTag("native-search-category-tutorial")
        compose.onNodeWithTag("native-search-category-tutorial").performClick()
        waitRequest(predicate = { it.contains("scope=articles") && it.contains("category=tutorial") && it.contains("page=1") })
        compose.onNodeWithTag("native-search-sort-latest").performClick()
        waitRequest(predicate = { it.contains("scope=articles") && it.contains("category=tutorial") && it.contains("sort=latest") && it.contains("page=1") })
    }

    /** 根据请求范围和页码构造搜索结果，保持每个类型的字段形状与服务端契约一致。 */
    private fun respond(request: RecordedRequest): MockResponse {
        // 测试只记录路径和查询条件，不写入授权头或正文内容。
        val path = request.path.orEmpty()
        requestPaths += path
        val body = when {
            path.startsWith("/search/hot?") -> JSONObject().put("items", JSONArray())
            path.startsWith("/search/public?") -> searchPage(path)
            else -> return MockResponse().setResponseCode(404).setBody(JSONObject().put("message", "Unexpected search request: $path").toString())
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body.toString())
    }

    /** 构造范围筛选、分类和排序均可解析的搜索响应。 */
    private fun searchPage(path: String): JSONObject {
        // 从查询串读取页面状态，模拟服务端按范围返回不同结果集合。
        val scope = queryValue(path, "scope") ?: "all"
        val page = queryValue(path, "page")?.toIntOrNull() ?: 1
        val sort = queryValue(path, "sort") ?: "relevance"
        val category = queryValue(path, "category")
        val articleItems = if (scope == "all" || scope == "articles") JSONArray().put(article(if (page == 2) 2 else 1, category)) else JSONArray()
        val userItems = if (scope == "all" || scope == "users") JSONArray().put(user()) else JSONArray()
        val topicItems = if (scope == "all" || scope == "topics") JSONArray().put(topic()) else JSONArray()
        val collectionItems = if (scope == "all" || scope == "collections") JSONArray().put(collection()) else JSONArray()
        val groupItems = if (scope == "all" || scope == "groups") JSONArray().put(group()) else JSONArray()
        val announcementItems = if (scope == "all" || scope == "announcements") JSONArray().put(announcement()) else JSONArray()
        val entryItems = if (scope == "all" || scope == "navigation" || scope == "tools") JSONArray().put(entry()) else JSONArray()
        val articlePages = if (scope == "all" || scope == "articles") 2 else 1
        return JSONObject()
            .put("query", "guide")
            .put("sort", sort)
            .put("articles", group(articleItems, if (scope == "all" || scope == "articles") 21 else 0, page, articlePages))
            .put("users", group(userItems, userItems.length()))
            .put("navigation", group(if (scope == "all" || scope == "navigation") entryItems else JSONArray(), if (scope == "all" || scope == "navigation") 1 else 0))
            .put("tools", group(if (scope == "all" || scope == "tools") entryItems else JSONArray(), if (scope == "all" || scope == "tools") 1 else 0))
            .put("topics", group(topicItems, if (scope == "all" || scope == "topics") 1 else 0))
            .put("collections", group(collectionItems, if (scope == "all" || scope == "collections") 1 else 0))
            .put("groups", group(groupItems, if (scope == "all" || scope == "groups") 1 else 0))
            .put("announcements", group(announcementItems, if (scope == "all" || scope == "announcements") 1 else 0))
            .put("filters", JSONObject()
                .put("articleCategories", JSONArray().put(JSONObject().put("name", "教程").put("value", "tutorial")))
                .put("navigationCategories", JSONArray().put(JSONObject().put("name", "站内").put("value", "internal")))
                .put("toolCategories", JSONArray().put(JSONObject().put("name", "效率").put("value", "productivity"))))
    }

    /** 构造带分页元数据的搜索分组。 */
    private fun group(items: JSONArray, total: Int, page: Int = 1, totalPages: Int = 1): JSONObject = JSONObject()
        .put("items", items)
        .put("total", total)
        .put("page", page)
        .put("pageSize", 20)
        .put("totalPages", totalPages)

    /** 构造文章结果，第二页使用不同 slug 以确认分页确实更新了列表。 */
    private fun article(id: Int, category: String?): JSONObject = JSONObject()
        .put("id", id)
        .put("title", if (id == 2) "第二页文章" else "测试文章")
        .put("slug", "guide-$id")
        .put("category", category ?: "教程")
        .put("tags", JSONArray())
        .put("publishedAt", "2026-11-09T00:00:00.000Z")
        .put("viewCount", 12)
        .put("commentCount", 2)
        .put("author", JSONObject().put("nickname", "测试作者").put("username", "search-author"))

    /** 构造用户结果。 */
    private fun user(): JSONObject = JSONObject().put("id", 7).put("username", "search-user").put("nickname", "测试用户").put("profileBio", "搜索用户")

    /** 构造专题结果。 */
    private fun topic(): JSONObject = JSONObject().put("id", 8).put("title", "测试专题").put("slug", "search-topic").put("description", "专题说明").put("articleCount", 3).put("subscriberCount", 2)

    /** 构造合集结果。 */
    private fun collection(): JSONObject = JSONObject().put("id", 9).put("name", "测试合集").put("description", "合集说明").put("articleCount", 4).put("subscriberCount", 1).put("owner", JSONObject().put("nickname", "合集作者"))

    /** 构造群聊结果。 */
    private fun group(): JSONObject = JSONObject().put("id", 10).put("conversationId", 11).put("name", "测试群聊").put("announcement", "群聊公告").put("memberCount", 5).put("joinMode", "approval").put("isMember", false)

    /** 构造公告结果。 */
    private fun announcement(): JSONObject = JSONObject().put("id", 12).put("title", "测试公告").put("summary", "公告摘要").put("isPinned", false)

    /** 构造导航或工具结果的公共字段。 */
    private fun entry(): JSONObject = JSONObject().put("id", 13).put("title", "测试入口").put("description", "入口说明").put("url", "/tools/search").put("iconPath", JSONObject.NULL).put("openInNewTab", false).put("category", JSONObject().put("name", "站内"))

    /** 读取查询参数的简单编码值，夹具只接收本用例生成的 ASCII 参数。 */
    private fun queryValue(path: String, name: String): String? = path.substringAfter("?", "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /** 等待语义标签出现，超时直接暴露搜索请求或结果渲染失败。 */
    private fun waitTag(tag: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    /** 等待一个结果文字进入 Compose 树。 */
    private fun waitText(value: String, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) {
            runCatching {
                compose.onNodeWithText(value, substring = false).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    /** 等待请求条件出现，确认 UI 操作确实触发了对应 API 参数。 */
    private fun waitRequest(predicate: (String) -> Boolean, timeoutMillis: Long = 15_000) {
        compose.waitUntil(timeoutMillis) { synchronized(requestPaths) { requestPaths.any(predicate) } }
    }
}
