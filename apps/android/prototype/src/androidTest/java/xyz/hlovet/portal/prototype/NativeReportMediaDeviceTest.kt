package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 在真实举报页面和隔离 HTTP 服务验证媒体操作；不替代数据库和真实权限验收。 */
@RunWith(AndroidJUnit4::class)
class NativeReportMediaDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private val runId = UUID.randomUUID().toString()
    private val calls = CopyOnWriteArrayList<String>()
    private val session = NativeSession("report-fixture-token", "fixture-refresh", 34, "fixture-reader", "Fixture reader",
        PreviewAppearance("sky-blue", "#0284C7", "#1F2937", "#52616F", "#FFFFFF", 50, 18, "#FFFFFF", 46))
    private val account = mutableStateOf<NativeSession?>(session)
    @Volatile private var itemsJson = "[]"
    @Volatile private var groupJson = "{}"
    /** 隔离响应覆盖器只控制本用例的失败与延迟，不记录凭据。 */
    @Volatile private var override: (RecordedRequest) -> MockResponse? = { null }
    private var oldLocale = "zh-CN"

    /** 仅对专用本机来源启动服务，不向真实站点发送替身凭据。 */
    @Before fun start() {
        // 每个媒体 URL 带独立标识，已有缓存不能掩盖 HTTP 错误测试。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:39871")
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            /** 对 request 返回明确夹具响应；身份头只在内存断言，绝不输出。 */
            override fun dispatch(request: RecordedRequest): MockResponse {
                // 当前站点的附件请求必须携带当前登录身份。
                calls.add(request.path.orEmpty())
                if (request.path?.startsWith("/social/") == true) {
                    check(request.getHeader("Authorization") == "Bearer ${account.value?.accessToken}")
                }
                return override(request) ?: response(request)
            }
        }
        server.start(39871)
    }

    /** 先销毁媒体页面，再停止服务并还原语言；保留证据和业务缓存。 */
    @After fun stop() {
        // 环境不匹配跳过时不能再次访问未启动的服务。
        if (!::server.isInitialized) return
        try { host.dispose() } finally {
            server.shutdown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(oldLocale) }
        }
    }

    /** 图片缩略图与原图分别认证读取，关闭原图后仍停留在原举报详情。 */
    @Test fun imageOpensOriginalAndReturnsToReport() {
        // 实际点击举报卡片和图片，不直接调用图片弹窗函数。
        show(listOf("image")); openReport()
        compose.onNodeWithTag("report-attachment-71").performScrollTo()
        compose.onNodeWithContentDescription("image.png").performClick()
        waitDescription("关闭图片预览")
        compose.waitUntil(15000) { count("/social/attachments/71/download") == 1 }
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        compose.onNodeWithTag("report-message-91").assertExists()
        assertEquals(1, count("/social/attachments/71/thumbnail"))
    }

    /** 原图加载失败在预览中显示错误，点击重试不丢失原举报。 */
    @Test fun imageFailureCanRetryInSamePreview() {
        // 缩略图成功但原图首次拒绝，确保错误不被占位图掩盖。
        val attempts = AtomicInteger()
        override = { if (it.path?.contains("/71/download") == true && attempts.incrementAndGet() == 1) MockResponse().setResponseCode(403) else null }
        show(listOf("image")); openReport()
        compose.onNodeWithTag("report-attachment-71").performScrollTo()
        compose.onNodeWithContentDescription("image.png").performClick()
        waitText("图片加载失败，请检查网络后重试。")
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(15000) { count("/social/attachments/71/download") == 2 }
        compose.waitUntil(15000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        compose.onNodeWithTag("report-message-91").assertExists()
    }

    /** 文件拒绝不打开系统面板，重试成功核对缓存字节并提供统一打开和另存为。 */
    @Test fun deniedFileStaysInReportAndRetryDownloadsBytes() {
        // 服务端的 403 不能误报成功，也不能令附件永久停留读取中。
        val attempts = AtomicInteger()
        override = { if (it.path?.contains("/71/download") == true && attempts.incrementAndGet() == 1) MockResponse().setResponseCode(403) else null }
        show(listOf("file")); openReport(); clickAttachment(71)
        waitText("附件下载失败，请检查网络后重试。")
        compose.onNodeWithText("附件下载失败，请检查网络后重试。").assertIsDisplayed()
        compose.onNodeWithText("另存为").assertDoesNotExist()
        clickAttachment(71); waitText("另存为")
        compose.onNodeWithText("打开").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cached = NativeOfflineCache.loadCachedMediaFile(context, path(71, "download"), NativeOfflineOwner.forSession(session))
        assertArrayEquals("Retained report file".toByteArray(), requireNotNull(cached).readBytes())
        assertEquals(2, count("/social/attachments/71/download"))
    }

    /** 举报详情中的音频与视频按需真实解码，未点击前不下载媒体。 */
    @Test fun audioAndVideoPlayOnlyAfterClick() {
        // 使用测试 APK 中的有效 WAV 与 MP4，不用虚假进度代替系统播放器。
        show(listOf("audio", "video")); openReport()
        assertEquals(0, count("/download"))
        for (id in listOf(71, 72)) {
            val parent = hasAnyAncestor(hasTestTag("native-inline-media-$id"))
            compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(20000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
            assertEquals(1, count("/social/attachments/$id/download"))
        }
    }

    /** 账号切换取消旧下载的消费，不能把旧账号文件面板显示给新账号。 */
    @Test fun accountChangeDropsPendingDownloadAndDetail() {
        // 延迟真实 HTTP 回包到账号更换后，核对整个页面状态而非单个变量。
        override = { if (it.path?.contains("/71/download") == true) MockResponse().setBody("Old account bytes").setBodyDelay(1, TimeUnit.SECONDS) else null }
        show(listOf("file")); openReport(); clickAttachment(71)
        compose.waitUntil(5000) { count("/71/download") == 1 }
        compose.runOnIdle { account.value = session.copy(userId = 35, accessToken = "report-new-account") }
        Thread.sleep(1300)
        compose.waitForIdle()
        compose.onNodeWithTag("report-message-91").assertDoesNotExist()
        compose.onNodeWithText("另存为").assertDoesNotExist()
        compose.onNodeWithTag("my-report-group_message-1").assertExists()
    }

    /** 首次列表失败与刷新失败都明确提示，刷新失败不清空已读记录。 */
    @Test fun listFailureAndRefreshRetainRecords() {
        // 第一次失败、第二次成功、第三次失败分别覆盖空列表和已有列表。
        val attempts = AtomicInteger()
        override = { if (it.path == "/moderation/my-reports" && attempts.incrementAndGet() != 2) MockResponse().setResponseCode(503).setBody("{\"message\":\"Report read failed\"}") else null }
        show(listOf("file")); waitText("Report read failed")
        compose.onNodeWithContentDescription("刷新").performClick()
        waitTag("my-report-group_message-1")
        compose.onNodeWithContentDescription("刷新").performClick()
        waitText("Report read failed")
        compose.onNodeWithTag("my-report-group_message-1").assertExists()
    }

    /** 后台举报入口使用同一个附件组件，点击文件进入同一系统文件面板。 */
    @Test fun adminDetailSupportsSameFileActions() {
        // 挂载真实后台页面，列表、摘要和模板也走隔离 HTTP 接口。
        show(listOf("file"), admin = true)
        waitText("Report fixture body")
        compose.onNodeWithText("Report fixture body").performScrollTo().performClick()
        waitTag("report-message-91"); clickAttachment(71); waitText("另存为")
        assertEquals(1, count("/social/attachments/71/download"))
    }

    /** 群管理从真实详情路径读取举报，图片原图与文件面板复用同一媒体组件。 */
    @Test fun groupManagerImageAndFileActions() {
        // 不直接挂载私有详情或设置举报状态，初始群编号触发真实页面请求。
        show(listOf("image", "file"), groupEntry = "manager")
        waitTag("group-management-report-1")
        compose.onNodeWithTag("report-attachment-71").performScrollTo()
        compose.onNodeWithContentDescription("image.png").performClick()
        waitDescription("关闭图片预览")
        compose.waitUntil(15000) { count("/71/download") == 1 }
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        clickAttachment(72); waitText("另存为")
        assertEquals(1, count("/72/download"))
    }

    /** 群管理中的音视频仍为按需播放，滚动后可以分别暂停。 */
    @Test fun groupManagerAudioAndVideoPlay() {
        // 未点击前只允许列表读取，不应预下载音视频。
        show(listOf("audio", "video"), groupEntry = "manager")
        waitTag("group-management-report-1")
        assertEquals(0, count("/download"))
        playAndPause(71); playAndPause(72)
    }

    /** 后台群聊举报详情能播放媒体并下载文件，长弹窗内各控件可滚动到达。 */
    @Test fun adminGroupsMediaAndFileActions() {
        // 使用群聊后台的独立举报页签，不用举报中心的成功结果代替。
        show(listOf("audio", "video", "file"), groupEntry = "admin")
        openAdminGroupReport()
        playAndPause(71); playAndPause(72)
        clickAttachment(73); waitText("另存为")
        assertEquals(1, count("/73/download"))
    }

    /** 后台群聊举报的下载错误必须可见，重试后能够进入统一文件面板。 */
    @Test fun adminGroupsDeniedFileCanRetry() {
        // 第一次拒绝、第二次成功，不能把失败缓存为成功附件。
        val attempts = AtomicInteger()
        override = { if (it.path?.contains("/71/download") == true && attempts.incrementAndGet() == 1) MockResponse().setResponseCode(403) else null }
        show(listOf("file"), groupEntry = "admin"); openAdminGroupReport(); clickAttachment(71)
        waitText("附件下载失败，请检查网络后重试。")
        compose.onNodeWithText("附件下载失败，请检查网络后重试。").assertIsDisplayed()
        clickAttachment(71); waitText("另存为")
        assertEquals(2, count("/71/download"))
    }

    /** 群管理在退出登录后销毁旧详情，迟到文件不能弹出到未登录页面。 */
    @Test fun groupManagerLogoutDropsPendingDownload() {
        // 延迟回包跨越真实 Compose 账号变化边界。
        override = { if (it.path?.contains("/71/download") == true) MockResponse().setBody("Old group file").setBodyDelay(1, TimeUnit.SECONDS) else null }
        show(listOf("file"), groupEntry = "manager"); waitTag("group-management-report-1"); clickAttachment(71)
        compose.waitUntil(5000) { count("/71/download") == 1 }
        compose.runOnIdle { account.value = null }
        waitText("登录后管理群聊。")
        Thread.sleep(1300); compose.waitForIdle()
        compose.onNodeWithTag("report-message-91").assertDoesNotExist()
        compose.onNodeWithText("另存为").assertDoesNotExist()
    }

    /** 后台换号销毁已选举报和迟到下载，不把旧文件交给新账号。 */
    @Test fun adminGroupsAccountChangeDropsPendingDownload() {
        // 新账号仍有后台身份，但必须重新选择举报才能打开详情。
        override = { if (it.path?.contains("/71/download") == true) MockResponse().setBody("Old admin file").setBodyDelay(1, TimeUnit.SECONDS) else null }
        show(listOf("file"), groupEntry = "admin"); openAdminGroupReport(); clickAttachment(71)
        compose.waitUntil(5000) { count("/71/download") == 1 }
        compose.runOnIdle { account.value = session.copy(userId = 35, accessToken = "group-new-account", isAdministrator = true) }
        Thread.sleep(1300); compose.waitForIdle()
        compose.onNodeWithTag("report-message-91").assertDoesNotExist()
        compose.onNodeWithText("另存为").assertDoesNotExist()
    }

    /** 无管理权限的群成员不读取举报列表，也不能触发举报附件请求。 */
    @Test fun ordinaryGroupMemberCannotReadReportMedia() {
        // 服务器摘要明确 canManage=false，客户端不依据已知群编号扩大读取。
        show(listOf("image", "file"), groupEntry = "manager", canManage = false)
        waitText("我的群昵称")
        compose.onNodeWithTag("group-management-report-1").assertDoesNotExist()
        assertEquals(0, count("/social/group-reports"))
        assertEquals(0, count("/social/attachments"))
    }

    /** 播放 id 对应媒体并等待真实解码状态，再手动暂停。 */
    private fun playAndPause(id: Int) {
        // 按附件业务标识定位，避免同时存在多个媒体控件时误点。
        val parent = hasAnyAncestor(hasTestTag("native-inline-media-$id"))
        compose.onNode(hasContentDescription("播放媒体") and parent, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodes(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("暂停媒体") and parent, useUnmergedTree = true).performClick()
        assertEquals(1, count("/social/attachments/$id/download"))
    }

    /** 从群聊后台切到举报页签后打开唯一报告，不绕过列表交互。 */
    private fun openAdminGroupReport() {
        // 先等待数据加载，页签文字包含实际待处理数量。
        waitText("举报 1")
        compose.onNodeWithText("举报 1").performScrollTo().performClick()
        waitTag("admin-group-report-1")
        compose.onNodeWithText("查看详情").performScrollTo().performClick()
        waitTag("report-message-91")
    }

    /** 为 kinds 构建真实响应；admin 为治理后台，groupEntry 指定群入口，canManage 模拟明确权限摘要。 */
    private fun show(kinds: List<String>, admin: Boolean = false, groupEntry: String? = null, canManage: Boolean = true) {
        // 页面和网络数据都使用真实生产模型解析，不注入私有状态。
        val user = JSONObject().put("id", 35).put("nickname", "Fixture sender").put("username", "fixture-sender")
        val attachments = JSONArray(kinds.mapIndexed { index, kind ->
            val id = 71 + index
            val extension = when (kind) { "image" -> "png"; "audio" -> "wav"; "video" -> "mp4"; else -> "txt" }
            JSONObject().put("id", id).put("kind", kind).put("originalName", "$kind.$extension")
                .put("mimeType", when (kind) { "image" -> "image/png"; "audio" -> "audio/wav"; "video" -> "video/mp4"; else -> "text/plain" })
                .put("sizeBytes", 100).put("downloadUrl", path(id, "download")).put("thumbnailUrl", if (kind == "image") path(id, "thumbnail") else JSONObject.NULL)
        })
        groupJson = JSONObject().put("id", 81).put("conversationId", 82).put("name", "Fixture group")
            .put("owner", user).put("canManage", canManage).put("currentMemberRole", if (canManage) "owner" else "member").toString()
        itemsJson = JSONArray().put(JSONObject().put("id", 1).put("key", "group_message-1").put("source", "group_message").put("reason", "other")
            .put("createdAt", "2026-10-15T00:00:00Z").put("reporter", user).put("targetUser", user)
            .put("group", JSONObject(groupJson))
            .put("message", JSONObject().put("id", 91).put("body", "Report fixture body").put("sender", user).put("createdAt", "2026-10-15T00:00:00Z").put("attachments", attachments))).toString()
        if (admin || groupEntry == "admin") account.value = session.copy(isAdministrator = true)
        host.setContent { HlovetTheme {
            when (groupEntry) {
                "manager" -> NativeGroupScreen(account.value, {}, {}, initialGroupId = 81)
                "admin" -> NativeAdminGroupsScreen(account.value, {})
                else -> if (admin) NativeAdminReportsScreen(account.value, {}) else NativeReportsScreen(account.value, {}, {})
            }
        } }
    }

    /** 点击唯一群消息举报，等待真正的详情组件出现。 */
    private fun openReport() {
        // 使用卡片的稳定业务键，避免误点弹窗或其它来源的同名文字。
        waitTag("my-report-group_message-1")
        compose.onNodeWithTag("my-report-group_message-1").performScrollTo().performClick()
        waitTag("report-message-91")
    }

    /** 点击 id 对应的文件行，确保动作来自实际附件控件。 */
    private fun clickAttachment(id: Int) {
        // 下载按钮由父文件行处理，先滚动到可见区域。
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("report-attachment-$id"))).performScrollTo().performClick()
    }

    /** 等待 text 出现在当前语义树，最长十五秒。 */
    private fun waitText(text: String) {
        // 推进重组后再判断，避免只依赖固定睡眠。
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 tag 对应真实组件挂载。 */
    private fun waitTag(tag: String) {
        // 业务标识避免多个相同文件名之间串选。
        compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 description 对应的图片操作出现。 */
    private fun waitDescription(description: String) {
        // 图片窗口必须真正可操作，而不只是发出了网络请求。
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回请求中包含 fragment 的次数，不返回 Authorization 内容。 */
    private fun count(fragment: String): Int {
        // 路径是无敏感信息的测试证据。
        return calls.count { it.contains(fragment) }
    }

    /** 为 id/action 生成独立缓存路径，避免前一次运行缓存掩盖网络行为。 */
    private fun path(id: Int, action: String): String {
        // 同一附件缩略图和原图共享标识但使用不同资源地址。
        return "/social/attachments/$id/$action?fixture=$runId"
    }

    /** 根据 request 提供列表、文件和真实媒体；未知路径明确返回 404。 */
    private fun response(request: RecordedRequest): MockResponse {
        // 媒体取自测试 APK，普通文件使用固定可核对字节。
        val path = request.path.orEmpty()
        if (path == "/moderation/my-reports" || path.startsWith("/moderation/reports?")) return MockResponse().setBody("{\"items\":$itemsJson,\"total\":1,\"page\":1,\"totalPages\":1}")
        if (path == "/moderation/reports/summary") return MockResponse().setBody("{\"pending\":1,\"total\":1,\"bySource\":{\"group_message\":1}}")
        if (path == "/moderation/templates") return MockResponse().setBody("{\"items\":[]}")
        if (path == "/social/groups/81") return MockResponse().setBody(groupJson)
        if (path == "/social/groups" || path.startsWith("/social/groups/admin?")) return MockResponse().setBody("{\"items\":[$groupJson]}")
        if (path == "/social/group-approvals") return MockResponse().setBody("{\"invitations\":[],\"joinRequests\":[]}")
        if (path == "/social/groups/81/join-requests" || path == "/social/groups/81/ban-records") return MockResponse().setBody("{\"items\":[]}")
        if (path.startsWith("/social/group-reports?")) return MockResponse().setBody("{\"items\":$itemsJson}")
        val media = JSONArray(itemsJson).getJSONObject(0).getJSONObject("message").getJSONArray("attachments")
        val attachment = (0 until media.length()).map { media.getJSONObject(it) }.firstOrNull { path.contains("/${it.getInt("id")}/") }
            ?: return MockResponse().setResponseCode(404)
        val kind = attachment.getString("kind")
        val bytes = when (kind) {
            "image" -> ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
                output.toByteArray()
            }
            "audio", "video" -> InstrumentationRegistry.getInstrumentation().context.assets.open(if (kind == "audio") "inline-silence.wav" else "inline-test-pattern.mp4").use { it.readBytes() }
            else -> "Retained report file".toByteArray()
        }
        return MockResponse().setHeader("Content-Type", attachment.getString("mimeType")).setBody(Buffer().write(bytes))
    }
}
