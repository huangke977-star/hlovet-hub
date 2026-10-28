package xyz.hlovet.portal.prototype

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离 HTTP 与实际 Compose 页面验证合集分页、保存恢复和身份隔离，不替代真实数据库验收。 */
@RunWith(AndroidJUnit4::class)
class NativeCollectionsDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val session = NativeSession("collection-fixture", "fixture-refresh", 72, "collection-owner", "Collection owner",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val account = mutableStateOf<NativeSession?>(session)
    private val rows = CopyOnWriteArrayList<JSONObject>()
    private val calls = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<Pair<String, String>>()
    /** 为当前用例覆盖响应，返回 null 时继续默认协议，不记录认证头。 */
    @Volatile private var respond: (RecordedRequest) -> MockResponse? = { null }
    private var selectedUri: Uri? = null
    private var oldLocale = "zh-CN"
    private val registry = object : ActivityResultRegistry() {
        /** 对系统文件选择 contract 返回 selectedUri，不伪称实际文件选择器已手工操作。 */
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            // 只替代系统选择结果，正文仍经真实 ContentResolver 和上传接口读取。
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, contract.createIntent(compose.activity, input).action)
            compose.activity.window.decorView.post { dispatchResult(requestCode, selectedUri) }
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        /** 将测试文件选择注册器交给页面，不覆盖真实应用会话。 */
        override val activityResultRegistry get() = registry
    }

    /** 仅精确隔离来源才能启动，使用独立内存数据和模拟器内部服务。 */
    @Before fun start() {
        // 正式与 Docker 来源必须在任何请求之前跳过本组测试。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 返回 request 对应真实协议结构，仅保存无凭据业务正文。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // multipart 不按 JSON 读取，令牌和请求头从不进入证据。
                    val key = "${request.method} ${request.path.orEmpty().substringBefore('?')}"
                    calls.add("${request.method} ${request.path}")
                    if (request.method != "GET") writes.add(key to request.body.clone().readUtf8())
                    return respond(request) ?: response(request)
                }
            }
            start(39871)
        }
    }

    /** 先卸载页面再关闭服务并恢复语言，保留新建媒体样例而不清理用户文件。 */
    @After fun stop() {
        // 被来源守卫跳过时不访问未初始化的服务器。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 空白名称和创建失败均在当前弹窗提示，重试保留输入且只生成一条合集。 */
    @Test fun createValidationAndFailureKeepVisibleDraft() {
        // 从真实新增入口操作，不直接写入编辑状态。
        show(); create()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithTag("collection-editor-error").assertIsDisplayed()
        assertTrue(writes.isEmpty())
        val attempts = AtomicInteger()
        respond = { request -> if (request.method == "POST" && request.path == "/discovery/collections" && attempts.getAndIncrement() == 0) json(JSONObject().put("message", "创建暂不可用"), 503) else null }
        name("保留名称"); compose.onNodeWithText("保存").performClick()
        waitText("创建暂不可用")
        compose.onNodeWithText("创建暂不可用").assertIsDisplayed()
        compose.onNodeWithTag("collection-name").assertTextContains("保留名称")
        compose.onNodeWithText("保存").performClick(); closed()
        assertEquals(1, rows.size)
        assertEquals(2, writes.count { it.first == "POST /discovery/collections" })
    }

    /** 删除收录成功、添加失败后继续保存，不重复已成功删除，也不丢失目标顺序。 */
    @Test fun partialArticleSaveRetriesFromConfirmedState() {
        // 两个原收录中移除一条再添加第三条，故意让添加的第一次请求失败。
        rows.add(collection(11, listOf(1, 2))); show(); edit(11)
        compose.onNode(hasContentDescription("移除文章") and hasAnyAncestor(hasTestTag("reorder-article-1"))).performScrollTo().performClick()
        waitTag("collection-available-3"); compose.onNodeWithTag("collection-available-3").performScrollTo().performClick()
        val additions = AtomicInteger()
        respond = { request -> if (request.method == "POST" && request.path == "/discovery/collections/11/articles/3" && additions.getAndIncrement() == 0) json(JSONObject().put("message", "添加暂不可用"), 503) else null }
        compose.onNodeWithText("保存").performClick()
        waitText("部分内容已保存，请重试剩余修改：添加暂不可用")
        compose.onNodeWithTag("collection-editor-error").assertIsDisplayed()
        compose.onNodeWithTag("reorder-article-3").assertExists()
        compose.onNodeWithText("保存").performClick(); closed()
        assertEquals(listOf(2, 3), ids(rows.single()))
        assertEquals(1, writes.count { it.first == "DELETE /discovery/collections/11/articles/1" })
        assertEquals(2, writes.count { it.first == "POST /discovery/collections/11/articles/3" })
    }

    /** 新建成功后封面失败，重试必须沿用创建编号而不是重复 POST 新建。 */
    @Test fun coverFailureDoesNotCreateDuplicateCollection() {
        // 返回真实媒体 URI，上传流仍由业务代码有界读取并发送 multipart。
        selectedUri = image(); show(); create(); name("封面重试")
        compose.onNode(hasText("选择") and hasClickAction()).performScrollTo().performClick()
        waitText("已选择：", substring = true)
        val uploads = AtomicInteger()
        respond = { request -> if (request.path == "/discovery/collections/99/cover" && uploads.getAndIncrement() == 0) json(JSONObject().put("message", "封面暂不可用"), 503) else null }
        compose.onNodeWithText("保存").performClick()
        waitText("部分内容已保存，请重试剩余修改：封面暂不可用")
        compose.onNodeWithTag("collection-editor-error").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick(); closed()
        assertEquals(1, rows.size)
        assertEquals(1, writes.count { it.first == "POST /discovery/collections" })
        assertEquals(2, writes.count { it.first == "POST /discovery/collections/99/cover" })
    }

    /** 删除失败留在确认弹窗并可重试，成功才移除原列表行。 */
    @Test fun deleteFailureKeepsConfirmationAndRow() {
        // 删除接口第一次拒绝，不提前从本地列表移除合集。
        rows.add(collection(11)); show()
        compose.onNode(hasContentDescription("删除合集") and hasAnyAncestor(hasTestTag("collection-11"))).performScrollTo().performClick()
        val attempts = AtomicInteger()
        respond = { request -> if (request.method == "DELETE" && attempts.getAndIncrement() == 0) json(JSONObject().put("message", "删除暂不可用"), 503) else null }
        compose.onNodeWithText("删除", substring = false).performClick()
        waitText("删除暂不可用")
        compose.onNodeWithTag("collection-delete-error").assertIsDisplayed()
        assertEquals(1, rows.size)
        compose.onNodeWithText("删除", substring = false).performClick()
        waitText("合集已删除，文章内容未受影响。")
        assertTrue(rows.isEmpty())
    }

    /** 第二页候选和关键词搜索都来自本人已发布接口，超过六十条也能继续收录。 */
    @Test fun publishedArticlePaginationAndSearchHaveNoSixtyItemCutoff() {
        // 初次读取五十条，加载第二页后选择第七十条，不借助直接状态赋值。
        show(); create(); name("完整候选"); waitTag("collection-available-50")
        compose.onNodeWithText("加载更多可添加文章").performScrollTo().performClick()
        waitTag("collection-available-70")
        compose.onNodeWithTag("collection-available-70").performScrollTo().performClick()
        compose.onNodeWithTag("collection-article-search").performScrollTo().performTextReplacement("needle")
        waitTag("collection-available-71")
        compose.onNode(hasText("Article 70") and hasAnyAncestor(hasTestTag("reorder-article-70"))).assertExists()
        compose.onNodeWithTag("collection-available-71").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick(); closed()
        assertEquals(listOf(70, 71), ids(rows.single()))
        assertTrue(calls.filter { it.startsWith("GET /articles/mine?") }.all { it.contains("status=published") })
        assertTrue(calls.any { it.contains("page=2") && it.startsWith("GET /articles/mine?") })
    }

    /** 合集刷新失败仍能管理旧记录；候选读取错误不阻止查看或保存已有合集。 */
    @Test fun independentReadFailuresPreserveCollectionAndDraft() {
        // 不把候选加载失败当成整个合集列表为空。
        rows.add(collection(11)); show()
        respond = { request -> when { request.path == "/discovery/collections/mine" -> json(JSONObject().put("message", "刷新暂不可用"), 503); request.path.orEmpty().startsWith("/articles/mine?") -> json(JSONObject().put("message", "文章暂不可用"), 503); else -> null } }
        compose.onNodeWithContentDescription("刷新合集").performClick(); waitText("刷新暂不可用")
        compose.onNodeWithTag("collection-11").assertExists(); edit(11)
        waitText("文章暂不可用"); name("独立草稿")
        compose.onNodeWithText("保存").performClick(); closed()
        assertEquals("独立草稿", rows.single().getString("name"))
    }

    /** 退出时销毁待完成保存，迟到成功不能在新会话出现或留下原表单。 */
    @Test fun logoutDropsDelayedCreateResult() {
        // 服务端可以已经创建；只约束新身份不得接收旧页面的成功状态。
        respond = { request -> if (request.method == "POST" && request.path == "/discovery/collections") json(collection(99)).setBodyDelay(700, TimeUnit.MILLISECONDS) else null }
        show(); create(); name("迟到创建"); compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { writes.isNotEmpty() }
        compose.runOnIdle { account.value = null }
        waitText("请先登录后查看个人合集。")
        Thread.sleep(900); compose.waitForIdle()
        compose.onNodeWithTag("collection-editor").assertDoesNotExist()
        compose.onNodeWithText("合集已创建。").assertDoesNotExist()
    }

    /** 旧搜索的加载更多迟到后，不得把旧合集追加到新关键词结果。 */
    @Test fun browseSearchRejectsLatePreviousPage() {
        // 第一页允许加载更多，旧第二页延迟返回，新搜索独立返回一个可识别合集。
        respond = { request ->
            if (request.requestUrl?.encodedPath != "/discovery/collections/visible") null else {
                val page = request.requestUrl!!.queryParameter("page")!!.toInt()
                val fresh = request.requestUrl!!.queryParameter("q") == "fresh"
                val id = if (fresh) 300 else if (page == 1) 200 else 201
                val response = json(JSONObject().put("items", JSONArray().put(collection(id))).put("page", page).put("totalPages", if (fresh) 1 else 2).put("total", if (fresh) 1 else 2))
                if (!fresh && page == 2) response.setBodyDelay(900, TimeUnit.MILLISECONDS) else response
            }
        }
        show(initialView = "browse"); waitTag("collection-browse-200")
        compose.onNodeWithText("加载更多").performScrollTo().performClick()
        compose.waitUntil(10000) { calls.any { it.startsWith("GET /discovery/collections/visible?page=2") } }
        compose.onNodeWithTag("collection-browse-search").performScrollTo().performTextReplacement("fresh")
        waitTag("collection-browse-300")
        Thread.sleep(1100); compose.waitForIdle()
        compose.onNodeWithTag("collection-browse-201").assertDoesNotExist()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
    }

    /** 英文页面的收录排序操作复用统一组件，不能遗留中文按钮和提示。 */
    @Test fun englishReorderControlsAndArrowSave() {
        // 同一组件也供专题使用，本测试验证语言和箭头顺序回传。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("en") }
        rows.add(collection(11, listOf(1, 2))); show("Edit collection")
        compose.onNode(hasContentDescription("Edit collection") and hasAnyAncestor(hasTestTag("collection-11"))).performScrollTo().performClick()
        waitTag("collection-editor")
        compose.onNode(hasContentDescription("Move up") and hasAnyAncestor(hasTestTag("reorder-article-2"))).performScrollTo().performClick()
        compose.onNodeWithText("长按左侧把手拖动排序，也可以用箭头微调").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick(); closed()
        assertEquals(listOf(2, 1), ids(rows.single()))
    }

    /** 连续长按同一文章的把手提交新顺序；取消手势不改变父页面已经确认的顺序。 */
    @Test fun dragHandleCommitsAndCancelledDragRestoresOrder() {
        // 独立组件场景只核对手势，不把它当作真实数据库写入。
        val order = mutableStateOf(listOf(1, 2, 3))
        host.setContent { HlovetTheme { NativeReorderableArticleRows(order.value, true, titleFor = { "Article $it" }, onReordered = { order.value = it }, onRemove = {}) } }
        compose.waitForIdle()
        val firstCenter = compose.onNodeWithTag("reorder-article-1").fetchSemanticsNode().boundsInRoot.center.y
        val secondCenter = compose.onNodeWithTag("reorder-article-2").fetchSemanticsNode().boundsInRoot.center.y
        val distance = secondCenter - firstCenter + 6f
        compose.onNode(hasContentDescription("长按拖动排序") and hasAnyAncestor(hasTestTag("reorder-article-1"))).performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, distance)); advanceEventTime(40); up()
        }
        compose.runOnIdle { assertEquals(listOf(2, 1, 3), order.value) }
        // 第二个手势只改变临时预览，取消不能将半途顺序交给父页面。
        compose.onNode(hasContentDescription("长按拖动排序") and hasAnyAncestor(hasTestTag("reorder-article-1"))).performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, -distance)); advanceEventTime(40); cancel()
        }
        compose.runOnIdle { assertEquals(listOf(2, 1, 3), order.value) }
        assertTrue("取消后文章 1 应仍在文章 2 下方", compose.onNodeWithTag("reorder-article-1").fetchSemanticsNode().boundsInRoot.center.y > compose.onNodeWithTag("reorder-article-2").fetchSemanticsNode().boundsInRoot.center.y)
        // 位置变化后再次拖动同一文章，回调不能仍绑定到该位置原来的文章编号。
        compose.onNode(hasContentDescription("长按拖动排序") and hasAnyAncestor(hasTestTag("reorder-article-1"))).performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, -distance)); advanceEventTime(40); up()
        }
        compose.runOnIdle { assertEquals(listOf(1, 2, 3), order.value) }
    }

    /** 挂载真实合集组件，ready 指定语言标签，initialView 指定入口视图。 */
    private fun show(ready: String = "新建合集", initialView: String = "mine") {
        // 使用独立选择注册器，不改变页面的业务网络和状态管理。
        host.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) { HlovetTheme { MyCollectionsScreen(account.value, initialView, onBack = {}, onOpenCollection = {}) } } }
        if (initialView == "browse") waitTag("collection-browse-search")
        else compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(ready).fetchSemanticsNodes().isNotEmpty() }
        rows.firstOrNull()?.let { waitTag("collection-${it.getInt("id")}") }
        compose.waitForIdle()
    }

    /** 点击实际创建按钮并等待表单挂载。 */
    private fun create() {
        // 按内容描述定位图标，避免与对话框标题重名。
        compose.onNodeWithContentDescription("新建合集").performClick(); waitTag("collection-editor")
    }

    /** 打开 id 对应卡片的编辑按钮。 */
    private fun edit(id: Int) {
        // 只点击目标卡片，页面多合集不会串选。
        compose.onNodeWithTag("collections-list").performScrollToNode(hasTestTag("collection-$id"))
        compose.onNode(hasContentDescription("编辑合集") and hasAnyAncestor(hasTestTag("collection-$id"))).performScrollTo().performClick(); waitTag("collection-editor")
    }

    /** 输入 value 作为名称，保持其它草稿字段不变。 */
    private fun name(value: String) {
        // 稳定业务标签不依赖系统键盘或翻译后的提示文本。
        compose.onNodeWithTag("collection-name").performScrollTo().performTextReplacement(value)
    }

    /** 等待当前编辑器实际关闭，不以请求完成代替页面状态迁移。 */
    private fun closed() {
        // 失败表单应持续存在，只有真正成功才满足条件。
        compose.waitUntil(15000) { compose.onAllNodesWithTag("collection-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
    }

    /** 等待 text 的精确或 substring 匹配进入真实语义树。 */
    private fun waitText(text: String, substring: Boolean = false) {
        // 网络和组合时序使用有界等待，不伪造页面节点。
        compose.waitUntil(15000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 tag 对应真实组件出现。 */
    private fun waitTag(tag: String) {
        // 翻页后节点必须真正创建，不能仅检查请求页码。
        compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 创建可由 ContentResolver 读取的独立 PNG 样例并返回 URI，保留文件以便复查。 */
    private fun image(): Uri {
        // MediaStore 文件只使用本次随机名称，不覆盖或清理既有下载。
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "native_collection_${UUID.randomUUID()}.png"); put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hlovet-collection-fixtures"); put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(output.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** 返回 request 的默认夹具响应，写入只作用于当前用例内存合集。 */
    private fun response(request: RecordedRequest): MockResponse {
        // 分页参数用 URL 解析，业务身份只使用稳定编号。
        val path = request.requestUrl!!.encodedPath
        if (request.method == "GET" && path == "/discovery/collections/mine") return json(JSONObject().put("items", JSONArray(rows)))
        if (request.method == "GET" && path == "/articles/mine") {
            val page = request.requestUrl!!.queryParameter("page")!!.toInt()
            val search = request.requestUrl!!.queryParameter("search").orEmpty()
            val candidates = if (search == "needle") listOf(article(71)) else (if (page == 1) 1..50 else 51..70).map(::article)
            return json(JSONObject().put("items", JSONArray(candidates)).put("page", page).put("totalPages", if (search.isBlank()) 2 else 1).put("total", if (search.isBlank()) 70 else 1))
        }
        if (request.method == "POST" && path == "/discovery/collections") {
            val body = JSONObject(request.body.clone().readUtf8())
            val saved = collection(99, (0 until body.getJSONArray("articleIds").length()).map { body.getJSONArray("articleIds").getInt(it) })
            for (field in listOf("name", "description", "visibility")) saved.put(field, body.getString(field))
            rows.add(saved); return json(saved)
        }
        val id = path.split('/').getOrNull(3)?.toIntOrNull()
        val saved = rows.firstOrNull { it.getInt("id") == id } ?: return json(JSONObject().put("message", "Unexpected collection fixture"), 404)
        if (request.method == "DELETE" && path == "/discovery/collections/$id") { rows.remove(saved); return json(JSONObject().put("success", true)) }
        if (request.method == "PATCH" && path == "/discovery/collections/$id") {
            val body = JSONObject(request.body.clone().readUtf8())
            body.keys().forEach { saved.put(it, body.get(it)) }; return json(saved)
        }
        if (path.endsWith("/cover")) return json(saved)
        val current = ids(saved).toMutableList()
        if (path.endsWith("/articles/order")) {
            val ordered = JSONObject(request.body.clone().readUtf8()).getJSONArray("ids")
            saved.put("articles", JSONArray((0 until ordered.length()).map { article(ordered.getInt(it)) }))
        } else if (path.contains("/articles/")) {
            val articleId = path.substringAfterLast('/').toInt()
            if (request.method == "DELETE") current.remove(articleId) else if (articleId !in current) current.add(articleId)
            saved.put("articles", JSONArray(current.map(::article)))
        } else return json(JSONObject().put("message", "Unexpected collection action"), 404)
        saved.put("articleCount", saved.getJSONArray("articles").length())
        return json(saved)
    }

    /** 构造 id 对应的最小完整文章协议，保持解析器使用真实字段。 */
    private fun article(id: Int): JSONObject {
        // 测试文章没有媒体、权限或正文副作用。
        return JSONObject().put("id", id).put("title", "Article $id").put("slug", "collection-article-$id").put("status", "published")
    }

    /** 构造 id 合集并按 articleIds 顺序收录文章。 */
    private fun collection(id: Int, articleIds: List<Int> = emptyList()): JSONObject {
        // 默认公开与无封面用于避免额外媒体请求。
        return JSONObject().put("id", id).put("name", "Collection $id").put("description", "Fixture description").put("visibility", "public")
            .put("articleCount", articleIds.size).put("subscriberCount", 0).put("articles", JSONArray(articleIds.map(::article)))
    }

    /** 返回 collection 当前收录编号，作为接口状态的独立断言。 */
    private fun ids(collection: JSONObject): List<Int> {
        // 顺序来自协议数组而不是界面文本位置。
        val array = collection.getJSONArray("articles"); return (0 until array.length()).map { array.getJSONObject(it).getInt("id") }
    }

    /** 以 status 返回 value 的标准 JSON 响应。 */
    private fun json(value: JSONObject, status: Int = 200): MockResponse {
        // 错误与成功均沿用业务解析器，不直接修改页面状态。
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value.toString())
    }
}
