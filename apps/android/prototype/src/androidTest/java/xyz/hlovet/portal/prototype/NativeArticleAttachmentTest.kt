package xyz.hlovet.portal.prototype

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实编辑组件、文件读取和受控接口验证附件操作，只允许精确的本地夹具来源。 */
@RunWith(AndroidJUnit4::class)
class NativeArticleAttachmentTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var documents: AttachmentFixtureProvider
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private var oldLocale = "zh-CN"
    private var selectedUris = emptyList<Uri>()
    private var pickerLaunches = 0
    @Volatile private var uploadStatus = 200
    @Volatile private var deleteStatus = 200
    @Volatile private var deleteGate: CountDownLatch? = null
    @Volatile private var storedAttachments = JSONArray()
    @Volatile private var returnedAttachments = JSONArray()
    @Volatile private var body = "<p>Original body</p>"
    private val mediaId = 80_000 + (System.nanoTime() % 10_000).toInt().let { if (it < 0) -it else it }
    private var imageBytes = byteArrayOf()
    @Volatile private var mediaAuthenticated = false
    private val session = NativeSession(
        accessToken = "fixture-attachment-token", refreshToken = "fixture-attachment-refresh", userId = 872,
        username = "fixture-attachment", nickname = "Fixture attachment",
        appearance = PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46),
    )
    private val registry = object : ActivityResultRegistry() {
        /** 捕获真实文件选择合同，以当前 selectedUris 返回选择或取消，不启动外部页面。 */
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            // 合同必须是系统多文件选择，回调由主线程触发与实际 ActivityResult 同步。
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, contract.createIntent(compose.activity, input).action)
            pickerLaunches++
            compose.activity.window.decorView.post { dispatchResult(requestCode, selectedUris) }
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    /** 初始化独立偏好、可控文档供应商和 HTTP 服务，不读写真实账号的草稿。 */
    @Before fun setUp() {
        // 测试令牌只能进入夹具来源，正式或 Docker 构建不能执行本组写请求。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        val namespace = "attachment-${UUID.randomUUID()}"
        documents = AttachmentFixtureProvider(File(compose.activity.cacheDir, namespace)).apply {
            attachInfo(compose.activity, ProviderInfo().apply { authority = "native.attachment.fixture" })
        }
        context = object : ContextWrapper(compose.activity) {
            /** 将 name 对应偏好隔离到本条用例，不覆盖已安装应用的会话或草稿。 */
            override fun getSharedPreferences(name: String, mode: Int) =
                // Activity 仍保留在包装链中，确保文件选择合同具有正确宿主。
                super.getSharedPreferences("$namespace-$name", mode)
        }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                /** 按 request 路径返回真实协议结构，上传与删除失败由用例显式设置。 */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // 记录完整正文与 multipart 后再计数，保证断言对应确实发出的请求。
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path == "/site-settings/public") return json("{}")
                    if (path == "/roles") return json("{\"items\":[]}")
                    if (request.method == "GET" && path == "/articles/mine/71") return json(article().toString())
                    if (request.method == "GET" && path == "/articles/71/versions") {
                        // 历史列表和详情使用同一可识别版本，仍通过真实版本 API 路径读取。
                        return json(JSONObject().put("items", JSONArray().put(version())).toString())
                    }
                    if (request.method == "GET" && path == "/articles/71/versions/91") return json(version().toString())
                    if (request.method == "GET" && path == "/articles/attachments/$mediaId/download") {
                        // 下载身份仅记布尔结果，不把测试令牌写入诊断输出。
                        mediaAuthenticated = request.getHeader("Authorization") == "Bearer ${session.accessToken}"
                        requests.add(path to "media-download")
                        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(imageBytes))
                    }
                    val input = request.body.readUtf8()
                    requests.add(path to input)
                    if (request.method == "POST" && path == "/articles/71/attachments") {
                        if (uploadStatus != 200) return json("{\"message\":\"Fixture upload rejected\"}", uploadStatus)
                        storedAttachments = returnedAttachments
                        return json(JSONObject().put("attachments", returnedAttachments).toString())
                    }
                    if (request.method == "DELETE" && path == "/articles/71/attachments/7") {
                        deleteGate?.await(12, TimeUnit.SECONDS)
                        if (deleteStatus != 200) return json("{\"message\":\"Fixture delete rejected\"}", deleteStatus)
                        storedAttachments = JSONArray()
                        return json("{}")
                    }
                    if (path == "/articles/71/autosave") {
                        body = JSONObject(input).getString("content")
                        return json(article().toString())
                    }
                    return json("{\"message\":\"Unexpected fixture request\"}", 404)
                }
            }
            start(39871)
        }
    }

    /** 释放删除闸门后卸载组合和夹具服务，恢复语言但不清理任何真实会话。 */
    @After fun tearDown() {
        // 测试失败也不能留下阻塞请求，使后续设备回归无法执行。
        deleteGate?.countDown()
        if (::server.isInitialized) {
            try { host.dispose() } finally {
                server.shutdown()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
            }
        }
    }

    /** 文件选择取消时不上传、保存或修改原正文。 */
    @Test fun cancelledPickerMakesNoMutation() {
        // 用空选择走实际注册的回调，等待超过自动保存防抖后仍应无写请求。
        show()
        pick()
        compose.waitUntil(5_000) { pickerLaunches == 1 }
        Thread.sleep(1600)
        assertEquals(0, requests.size)
        assertEquals(0, documents.openCount)
        assertEquals("<p>Original body</p>", liveBody())
    }

    /** 文本文件通过 MIME 回退上传、插入并保存，重新读取仍有附件引用。 */
    @Test fun textFileUploadsInsertsAndSurvivesReload() {
        // 供应商故意给通用 MIME，multipart 应使用标准 text/plain 并包含文件内容。
        selectedUris = listOf(document("fixture.txt", "application/octet-stream", "Fixture bytes".toByteArray()))
        returnedAttachments = JSONArray().put(attachment())
        show()
        pick()
        waitText("已上传 1 个附件，并插入正文。")
        compose.waitUntil(10_000) { requests.any { it.first == "/articles/71/autosave" } }
        val multipart = requests.first { it.first.endsWith("/attachments") }.second
        assertTrue(multipart.contains("filename=\"fixture.txt\""))
        assertTrue(multipart.contains("Content-Type: text/plain"))
        assertTrue(multipart.contains("Fixture bytes"))
        val reloaded = NativeArticleApi.getMyArticle(session.accessToken, 71)
        assertEquals(1, reloaded.attachments.size)
        assertTrue(reloaded.content.contains("Original body"))
        assertTrue(reloaded.content.contains("/articles/attachments/7/download"))
    }

    /** 超九个文件在读取前拒绝，既不静默截断也不制造上传请求。 */
    @Test fun tooManySelectedFilesAreNotReadOrUploaded() {
        // 相同 URI 列表仍按用户选择的数量验证，不通过去重掩盖错误批次。
        val uri = Uri.parse("content://unreadable.fixture/never-open")
        selectedUris = List(10) { uri }
        show()
        pick()
        waitText("每次最多选择 9 个附件。")
        assertEquals(0, documents.openCount)
        assertEquals(0, requests.size)
    }

    /** 已有五十个附件时不能继续上传，即使单次只选择一个。 */
    @Test fun articleTotalCountIsCheckedBeforeReading() {
        // 使用不同编号的既有附件测试单篇上限，保证按对象数量而不是文件名计数。
        storedAttachments = JSONArray().apply { repeat(50) { put(attachment().put("id", it + 1)) } }
        selectedUris = listOf(Uri.parse("content://unreadable.fixture/never-open"))
        show()
        pick()
        waitText("单篇文章最多包含 50 个附件。")
        assertEquals(0, documents.openCount)
        assertEquals(0, requests.size)
    }

    /** 未知大小文档只能读取剩余批次额度，实际越界仍会明确拒绝。 */
    @Test fun unknownSizeIsBoundedByRemainingBatchBudget() {
        // 供应商不报告 SIZE，真实输入比剩余额度大一字节，不能绕过有界读取。
        val uri = documents.add("unknown.txt", "text/plain", byteArrayOf(65, 66, 67), reportedSize = null)
        val resolver = ContentResolver.wrap(documents)
        val readerContext = object : ContextWrapper(compose.activity) {
            /** 仅单次文件读取使用受控供应商，UI 和系统设置始终沿用真实解析器。 */
            override fun getContentResolver(): ContentResolver {
                // 不把文档供应商替换成整个 Compose/WebView 的系统解析器。
                return resolver
            }
        }
        val failure = runCatching { readUploadFile(readerContext, uri, 10L * 1024 * 1024, remainingBytes = 2) }.exceptionOrNull()
        assertEquals(nativeArticleAttachmentBatchError(), failure?.message)
        assertEquals(1, documents.openCount)
    }

    /** 空文件有明确反馈，不能发请求或插入空引用。 */
    @Test fun emptyDocumentShowsErrorWithoutUpload() {
        // 用合法后缀零字节文件检查选择回调后的失败路径。
        selectedUris = listOf(document("empty.txt", "text/plain", byteArrayOf()))
        show()
        pick()
        waitText("empty.txt 是空文件。")
        assertEquals(0, requests.size)
        assertEquals("<p>Original body</p>", liveBody())
    }

    /** 服务端拒绝上传时原正文保持不变，页面确实显示失败提示。 */
    @Test fun failedUploadPreservesBodyAndShowsError() {
        // 文件读取成功后让服务端返回 400，避免测试只覆盖客户端格式检查。
        uploadStatus = 400
        selectedUris = listOf(document("fixture.txt", "text/plain", byteArrayOf(65)))
        show()
        pick()
        waitText("Fixture upload rejected")
        assertEquals("<p>Original body</p>", liveBody())
        assertEquals(1, requests.count { it.first.endsWith("/attachments") })
        assertEquals(0, requests.count { it.first.endsWith("/autosave") })
    }

    /** 删除失败不移除本地附件或正文引用，用户仍可重试。 */
    @Test fun failedDeleteKeepsAttachmentAndBody() {
        // 服务端错误必须反馈给页面，不能先删除 UI 再无法恢复。
        storedAttachments = JSONArray().put(attachment())
        body = "<p>Original body</p><p><a href=\"/articles/attachments/7/download\">fixture.txt</a></p>"
        deleteStatus = 403
        show()
        remove()
        waitText("Fixture delete rejected")
        assertTrue(liveBody().contains("fixture.txt"))
        assertEquals(1, NativeArticleApi.getMyArticle(session.accessToken, 71).attachments.size)
    }

    /** 删除期间未防抖上报的新可视输入不会丢失，删除完成后自动保存最新正文。 */
    @Test fun deleteReadsLatestVisualInputAndPausesAutosave() {
        // 闸门确保输入发生在实际删除请求期间，不依赖网络延迟碰巧满足时序。
        storedAttachments = JSONArray().put(attachment())
        body = "<p>Original body</p><p><a href=\"/articles/attachments/7/download\">fixture.txt</a></p>"
        deleteGate = CountDownLatch(1)
        show()
        remove()
        compose.waitUntil(10_000) { requests.any { it.first == "/articles/71/attachments/7" } }
        evaluate("window.setEditorContent('<p>Latest during deletion</p><p><a href=\"/articles/attachments/7/download\">fixture.txt</a> final typed text</p>');")
        Thread.sleep(1600)
        assertEquals(0, requests.count { it.first.endsWith("/autosave") })
        deleteGate?.countDown()
        waitText("附件已移除")
        compose.waitUntil(10_000) { requests.any { it.first.endsWith("/autosave") } }
        val saved = NativeArticleApi.getMyArticle(session.accessToken, 71)
        assertTrue(saved.content.contains("Latest during deletion"))
        assertTrue(saved.content.contains("final typed text"))
        assertFalse(saved.content.contains("/articles/attachments/7/download"))
        assertEquals(0, saved.attachments.size)
    }

    /** 已保存图片在编辑、当前预览和历史版本预览解码，并由原生下载提供认证。 */
    @Test fun confirmedArticleImageRendersWithAuthenticatedMedia() {
        // 小型合法 PNG 不依赖外部素材，唯一编号避免历史夹具缓存替代实际下载。
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        imageBytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        storedAttachments = JSONArray().put(attachment().put("id", mediaId).put("kind", "image").put("mimeType", "image/png")
            .put("originalName", "fixture.png").put("downloadUrl", "/articles/attachments/$mediaId/download"))
        body = "<p>Image fixture</p><p><img src=\"/articles/attachments/$mediaId/download\"></p>"
        show()
        compose.waitUntil(10_000) { evaluate("document.querySelector('#editor img').naturalWidth === 3") == "true" }
        assertTrue(mediaAuthenticated)
        assertEquals(1, requests.count { it.first.endsWith("/$mediaId/download") })
        // 打开真实预览弹窗，确保显示的是同一附件而非只验证编辑区的引用。
        compose.onNodeWithContentDescription("预览").performClick()
        waitText("文章预览")
        try {
            compose.waitUntil(10_000) { evaluate("document.querySelector('img')?.naturalWidth === 3", preview = true) == "true" }
        } catch (failure: Throwable) {
            // 记录夹具预览图片与实际文档状态，不能把错误页或缺失窗口当作图片加载失败。
            val state = evaluate("JSON.stringify({url:location.href,ready:document.readyState,html:document.body.innerHTML.slice(0,1200)})", preview = true)
            throw AssertionError("Preview image failed: $state", failure)
        }
        // 历史版本仍可显示未删除的当前附件，不能只覆盖普通预览入口。
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithContentDescription("版本历史").performClick()
        waitText("版本 1")
        compose.onNode(hasText("版本 1") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { evaluate("document.querySelector('img')?.naturalWidth === 3", preview = true) == "true" }
    }

    /** 手动写入其它本站接口地址不会被 WebView 当作媒体自动请求。 */
    @Test fun unknownMediaCannotRequestOtherApiEndpoints() {
        // 与 API 同源也不代表属于当前文章附件，安全策略必须比来源判断更严格。
        body = "<p><img src=\"${BuildConfig.API_BASE_URL}/auth/security-policy\"></p>"
        show()
        compose.waitUntil(10_000) { evaluate("document.querySelector('#editor img').complete") == "true" }
        assertEquals(0, requests.count { it.first == "/auth/security-policy" })
        assertEquals("true", evaluate("document.querySelector('#editor img').naturalWidth === 0"))
    }

    /** 安装独立宿主并等待真实 WebView 就绪，返回后可以触发文件或正文操作。 */
    private fun show() {
        // 本组只显示编号 71 的测试文章，所有网络响应来自当前夹具。
        android.util.Log.i("AttachmentFixture", "mount editor")
        host.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) {
                HlovetTheme { NativeWriteEditorScreen(session, 71, onBack = {}, onSaved = {}, onPublished = {}) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("标题")).fetchSemanticsNodes().isNotEmpty() }
        android.util.Log.i("AttachmentFixture", "title mounted")
        // 失败时保留真实本地页面状态，区分网络拦截、脚本初始化和未挂载问题。
        try {
            compose.waitUntil(10_000) { evaluate("typeof window.readEditorContent === 'function'") == "true" }
        } catch (failure: Throwable) {
            val state = evaluate("JSON.stringify({url:location.href,ready:document.readyState,html:document.documentElement.outerHTML.slice(0,1200)})")
            throw AssertionError("Editor initialization failed: $state", failure)
        }
        android.util.Log.i("AttachmentFixture", "webview ready")
    }

    /** 点击原生附件按钮，真实触发注册的系统文件选择合同。 */
    private fun pick() {
        // 精确匹配可点击文本，排除 WebView 内的同名工具栏文案。
        compose.onNode(hasText("附件") and hasClickAction()).performScrollTo().performClick()
        android.util.Log.i("AttachmentFixture", "picker launched")
    }

    /** 将 name、mime 与 bytes 创建为本条用例的系统下载文档，正常 UI 不替换系统 ContentResolver。 */
    private fun document(name: String, mime: String, bytes: ByteArray): Uri {
        // 独立目录保留测试数据，不覆盖已有文件；应用只借系统选择回调获得访问 URI。
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hlovet-attachment-fixtures/${UUID.randomUUID()}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** 点击当前附件标签中的移除操作。 */
    private fun remove() {
        // 当前夹具只有一条待删附件，标签点击与用户操作使用相同处理函数。
        compose.onNode(hasText("fixture.txt") and hasClickAction()).performScrollTo().performClick()
    }

    /** 等待 text 对应成功或失败反馈实际进入语义树。 */
    private fun waitText(text: String) {
        // 不以请求数量代替页面反馈已经显示。
        compose.waitUntil(12_000) { compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 执行 script 并返回 WebView 回调；preview 为 true 时读取独立弹窗窗口，否则读取编辑器。 */
    private fun evaluate(script: String, preview: Boolean = false): String {
        // 只在本地编辑器执行测试脚本，等待真实回调而不猜测渲染时间。
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread {
            // 弹窗按独立窗口识别，不依赖 loadDataWithBaseURL 在不同 WebView 版本返回的 URL 格式。
            val editor = findWebView(compose.activity.window.decorView)
            val view = if (preview) android.view.inspector.WindowInspector.getGlobalWindowViews()
                .mapNotNull(::findWebView).firstOrNull { it !== editor }
            else editor
            if (view == null) done.countDown() else view.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue("WebView callback timeout", done.await(5, TimeUnit.SECONDS))
        return result
    }

    /** 从 view 的层级查找编辑 WebView，未挂载时返回 null。 */
    private fun findWebView(view: View): WebView? {
        // 原生组件仍是测试主体，不通过外部网页替代编辑器。
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    /** 返回当前实时正文，不依赖原生防抖状态。 */
    private fun liveBody(): String {
        // JSON 字符串回调还原为 HTML，逐字符检查是否被错误覆盖。
        return JSONArray("[${evaluate("window.readEditorContent()") }]").getString(0)
    }

    /** 生成当前夹具文章回包，正文与附件采用服务端已经确认的状态。 */
    private fun article(): JSONObject {
        // 保存后 GET 使用同一状态，避免只检查上传接口而遗漏持久化引用。
        return JSONObject().put("id", 71).put("title", "Attachment fixture").put("summary", "").put("content", body)
            .put("contentFormat", "html").put("category", "随笔").put("tags", JSONArray()).put("titleColor", "")
            .put("visibility", "public").put("status", "draft").put("attachments", storedAttachments)
            .put("slug", "fixture-attachment-71").put("updatedAt", "2026-10-14T01:00:00.000Z")
    }

    /** 返回夹具历史版本 91，正文与当前附件引用一致，不携带任何身份凭据。 */
    private fun version(): JSONObject {
        // 保持版本 API 的字段合同，图片加载仍由当前文章允许列表确认。
        return article().put("id", 91).put("versionNumber", 1).put("source", "manual")
            .put("changedFields", JSONArray().put("content")).put("createdAt", "2026-10-14T01:00:00.000Z")
    }

    /** 返回编号 7 的文件附件，URL 与删除测试的正文引用一致。 */
    private fun attachment(): JSONObject {
        // 相对下载地址也属于接口合同，不能在正文中插入测试令牌。
        return JSONObject().put("id", 7).put("kind", "file").put("originalName", "fixture.txt")
            .put("mimeType", "text/plain").put("sizeBytes", 13).put("downloadUrl", "/articles/attachments/7/download")
    }

    /** 返回 body 和 status 对应的 JSON 响应，错误仍走真实 API 解析路径。 */
    private fun json(body: String, status: Int = 200): MockResponse {
        // 显式标注内容类型，成功和失败遵循同一 HTTP 客户端。
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    }
}

/** 在独立 folder 内提供可控文档，允许真实读取未知大小和通用 MIME 的供应商返回值。 */
private class AttachmentFixtureProvider(private val folder: File) : ContentProvider() {
    /** 保存文件元数据和实际路径，reportedSize 为 null 表示供应商未提供 SIZE。 */
    private data class Document(val name: String, val mime: String, val file: File, val reportedSize: Long?)
    private val documents = mutableMapOf<String, Document>()
    var openCount = 0
        private set

    /** 创建 name 和 mime 对应文档，bytes 为实际内容，reportedSize 可模拟未知或不可信大小。 */
    fun add(name: String, mime: String, bytes: ByteArray, reportedSize: Long? = bytes.size.toLong()): Uri {
        // 每个 URI 使用独立编号，不依赖真实用户文件或清理现有媒体。
        check(folder.exists() || folder.mkdirs())
        val id = documents.size.toString()
        val file = File(folder, id).apply { writeBytes(bytes) }
        documents[id] = Document(name, mime, file, reportedSize)
        return Uri.parse("content://native.attachment.fixture/$id")
    }

    /** 初始化供应商，夹具不连接任何外部存储。 */
    override fun onCreate(): Boolean {
        // 所有文档由用例显式创建。
        return true
    }

    /** 按 uri 返回 DISPLAY_NAME 和 SIZE，未找到则返回 null。 */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        // 保持与系统文档供应商相同的查询列。
        val document = documents[uri.lastPathSegment] ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(document.name, document.reportedSize)) }
    }

    /** 根据 uri 返回供应商声明 MIME，允许测试通用类型回退。 */
    override fun getType(uri: Uri): String? {
        // 未知 URI 不借用其它文件的类型。
        return documents[uri.lastPathSegment]?.mime
    }

    /** 只读打开 uri 文件并统计读取次数，mode 不授予写权限。 */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // 计数用于证明超量选择在读取前就被拒绝。
        val document = documents[uri.lastPathSegment] ?: return null
        openCount++
        return ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 不支持通过 uri 和 values 新增供应商数据。 */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // 测试数据只允许 add 显式创建。
        throw UnsupportedOperationException()
    }

    /** 不支持通过 uri、values 和筛选条件修改供应商文档。 */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        // 防止测试上传路径意外写回文件。
        throw UnsupportedOperationException()
    }

    /** 不支持通过 uri 和筛选条件删除供应商文件。 */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // 夹具与真实数据均不通过供应商清理。
        throw UnsupportedOperationException()
    }
}
