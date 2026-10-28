package xyz.hlovet.portal.prototype

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用独立作者及保留读者，验证真实阅读兑换、积分流水和系统下载，不清理业务记录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalResourceRedemptionTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var author: NativeSession? = null
    private var oldLocale: String? = null
    private var evidencePrefix = "native-local-resource"
    private lateinit var buyer: NativeSession
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL).put("testDataRemoved", false)

    /** 仅在精确本地来源刷新保留账号；不改变生产认证策略或替换读者会话。 */
    @Before fun requireLocalReader() {
        // 来源保护先于会话读写，避免正式地址构建误操作真实账号。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        buyer = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, buyer))
        report.put("buyerId", buyer.userId)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 失败同样保留现场、测试文章及流水，只注销临时作者会话并恢复语言。 */
    @After fun stop() {
        // 作者认证数据仅在内存中使用，不写入证据或覆盖保留读者的加密会话。
        try {
            if (oldLocale != null) {
                screenshot("last-state")
                writeReport()
            }
            try {
                scenario?.close()
            } finally {
                author?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            }
        } finally {
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 实际点击兑换与下载记录，再将资源文件另存为，核对独立账号权限及准确积分变动。 */
    @Test fun realReaderRedeemsOnceAndSavesProtectedAttachment() {
        // 正常注册独立作者，真实发布文章取得合法积分，不直接写余额或兑换记录。
        val suffix = UUID.randomUUID().toString().take(8)
        val authorName = "native_resource_$suffix"
        val authorResult = NativeAuthApi.register(authorName, authorName, "$authorName@example.com", "Local!${UUID.randomUUID()}", null, null)
        author = (authorResult as NativeAuthResult.Authenticated).session
        val creator = requireNotNull(author)
        assertNotEquals(buyer.userId, creator.userId)
        report.put("authorId", creator.userId).put("authorUsername", authorName)
        var balanceBefore = NativeResourceApi.getReputation(buyer.accessToken).points
        if (balanceBefore < 1) {
            val funding = NativeArticleApi.createArticle(buyer.accessToken, NativeDraftInput(
                "native_resource_points_$suffix", "Retained legitimate publication reward", "<p>Android resource redemption funding by real publication.</p>", "html", "随笔", "",
            ))
            NativeArticleApi.publish(buyer.accessToken, funding.id)
            report.put("fundingArticleId", funding.id)
            balanceBefore = NativeResourceApi.getReputation(buyer.accessToken).points
        }
        assertTrue("Publication did not provide enough legitimate points", balanceBefore >= 1)
        val title = "native_resource_20261015_$suffix"
        val draft = NativeArticleApi.createArticle(creator.accessToken, NativeDraftInput(
            title, "Retained real Android resource redemption verification", "<p>Public resource introduction.</p>", "html", "随笔", "",
        ))
        report.put("articleId", draft.id).put("articleSlug", draft.slug).put("title", title)
        val fileName = "native_resource_$suffix.txt"
        val bytes = "Actual protected resource $suffix\n".toByteArray()
        val imageBytes = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.GREEN)
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
        }
        val files = NativeArticleApi.uploadAttachments(creator.accessToken, draft.id, listOf(
            NativeUploadFile(fileName, "text/plain", bytes), NativeUploadFile("native_resource_$suffix.png", "image/png", imageBytes),
        ))
        val file = files.first { it.kind == "file" }
        val image = files.first { it.kind == "image" }
        val content = "<p>Public resource introduction.</p><resource-block data-points=\"1\"><p>Protected resource $suffix.</p><p><a href=\"${file.downloadUrl}\">$fileName</a></p><img src=\"${image.downloadUrl}\"></resource-block><resource-block data-points=\"2\"><p>Second protected block $suffix.</p></resource-block>"
        NativeArticleApi.updateArticle(creator.accessToken, draft.id, NativeDraftInput(title, draft.summary, content, "html", "随笔", ""))
        val published = NativeArticleApi.publish(creator.accessToken, draft.id)
        report.put("articleSlug", published.slug).put("attachmentId", file.id).put("imageAttachmentId", image.id).put("fileName", fileName)
        val authorBefore = NativeResourceApi.getReputation(creator.accessToken)
        val locked = NativeArticleApi.getArticle(buyer.accessToken, published.slug)
        assertEquals(2, locked.resourceBlocks.size)
        assertTrue(locked.resourceBlocks.none { it.unlocked })
        assertFalse(locked.contentSegments.any { it.content?.contains(fileName) == true })
        for (token in listOf(null, buyer.accessToken)) {
            assertEquals(403, response(file.downloadUrl, token).first)
            assertEquals(403, response("/articles/attachments/${image.id}/thumbnail", token).first)
        }
        val blockKey = locked.resourceBlocks.first().key
        report.put("blockKey", blockKey).put("buyerPointsBefore", balanceBefore).put("authorPendingBefore", authorBefore.pendingPoints)
        record("independent-author-published-two-blocks-and-unredeemed-file-and-thumbnail-denied")

        // 实际 MainActivity 阅读路由点击第一块兑换；第二块必须继续锁定。
        scenario = ActivityScenario.launch(route("/articles/${published.slug}"))
        waitText("兑换")
        compose.onAllNodesWithText("兑换")[0].performScrollTo().performClick()
        var delivery: NativeResourceDelivery? = null
        var lastPoll = 0L
        compose.waitUntil(60_000) {
            if (SystemClock.elapsedRealtime() - lastPoll >= 800) {
                lastPoll = SystemClock.elapsedRealtime()
                delivery = NativeResourceApi.listDeliveries(buyer.accessToken).items.firstOrNull { it.articleId == draft.id && it.blockKey == blockKey }
            }
            delivery != null
        }
        val exchanged = requireNotNull(delivery)
        report.put("exchangeId", exchanged.id)
        assertEquals("unlocked", exchanged.status)
        assertEquals(listOf("redeemed", "unlocked").sorted(), exchanged.events.map { it.type }.sorted())
        val unlocked = NativeArticleApi.getArticle(buyer.accessToken, published.slug)
        assertTrue(unlocked.resourceBlocks.first().unlocked)
        assertFalse(unlocked.resourceBlocks.last().unlocked)
        assertTrue(unlocked.contentSegments.any { it.content?.contains(fileName) == true })
        assertEquals(balanceBefore - 1, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals(authorBefore.pendingPoints + 1, NativeResourceApi.getReputation(creator.accessToken).pendingPoints)
        assertTrue(java.time.Instant.parse(exchanged.availableAt).isAfter(java.time.Instant.now().plusSeconds(71 * 3600L)))
        record("real-reading-button-unlocked-only-selected-block-and-debited-one-point-with-pending-author-income")

        // 重复提交走真实接口，既不重复扣分，也不新增交付；不以界面忙碌保护代替服务端幂等。
        repeat(2) { NativeArticleApi.redeemResource(buyer.accessToken, draft.id, blockKey) }
        assertEquals(balanceBefore - 1, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals(1, NativeResourceApi.listDeliveries(buyer.accessToken).items.count { it.articleId == draft.id && it.blockKey == blockKey })
        val earnings = NativeResourceApi.getCreatorEarnings(creator.accessToken)
        assertEquals(1, earnings.items.count { it.id == exchanged.id && it.buyer.id == buyer.userId && it.pointCost == 1 })
        assertEquals(authorBefore.pendingPoints + 1, earnings.pending)
        assertArrayEquals(bytes, response(file.downloadUrl, buyer.accessToken).second)
        assertEquals(200, response("/articles/attachments/${image.id}/thumbnail", buyer.accessToken).first)
        assertEquals(403, response(file.downloadUrl, null).first)
        record("duplicate-redemption-does-not-charge-again-and-author-earnings-match-real-exchange")

        // 解锁正文的真实链接触发既有原生文件面板，再由实际 DocumentsUI 保存到唯一新文件。
        clickBodyLink(file.downloadUrl)
        waitText("另存为")
        compose.onNodeWithText(fileName).assertExists()
        screenshot("file-actions")
        compose.onNodeWithText("另存为").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val outputName = "hlovet-resource-${UUID.randomUUID()}.txt"
        assertTrue(systemNode("android:id/title", fileName).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, outputName)
        }))
        assertTrue(systemNode("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitText("附件已保存到所选位置。")
        val saved = automation.executeShellCommand("cat /sdcard/Download/$outputName").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertArrayEquals(bytes, saved)
        report.put("savedName", outputName).put("bytes", saved.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(saved).joinToString("") { "%02x".format(it) })
        record("unlocked-real-body-link-and-system-saved-file-match-original-upload-bytes")

        // 从实际兑换记录路由点下载并展开事件，核对状态和事件刷新，不直接挂载替代页面。
        scenario!!.close()
        scenario = ActivityScenario.launch(route("/articles/resources/orders"))
        waitText("下载")
        compose.onNodeWithText(title).assertExists()
        compose.onAllNodesWithText("下载")[0].performScrollTo().performClick()
        waitText("已记录下载。")
        waitText("已下载")
        compose.onAllNodesWithText("交付记录")[0].performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("资源下载成功", substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        val completed = NativeResourceApi.listDeliveries(buyer.accessToken).items.first { it.id == exchanged.id }
        assertEquals("downloaded", completed.status)
        assertEquals(1, completed.events.count { it.type == "downloaded" })
        assertEquals(balanceBefore - 1, NativeResourceApi.getReputation(buyer.accessToken).points)
        record("real-redemption-orders-ui-refreshes-downloaded-status-and-delivery-event-without-extra-charge")
        screenshot("orders-success")
    }

    /** 根据本地夹具编号，从真实兑换记录页面重试交付，核对状态、事件和积分不重复扣除。 */
    @Test fun realFailedDeliveryRetriesThroughOrdersUiWithoutChargingAgain() {
        // 编号由受限本地准备脚本提供，不扫描或修改用户其它失败的兑换记录。
        evidencePrefix = "native-local-resource-retry"
        val arguments = InstrumentationRegistry.getArguments()
        val articleId = requireNotNull(arguments.getString("resourceArticleId")).toInt()
        val exchangeId = requireNotNull(arguments.getString("resourceExchangeId")).toInt()
        val before = NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == exchangeId }
        check(before.articleId == articleId && before.articleTitle.startsWith("native_resource_20261015_") && before.pointCost == 2 && before.status == "failed")
        val pointsBefore = NativeResourceApi.getReputation(buyer.accessToken).points
        report.put("articleId", articleId).put("exchangeId", exchangeId).put("buyerPointsBefore", pointsBefore)
        record("real-local-two-point-exchange-is-failed-before-native-retry")

        // 只通过真实订单页面按钮触发重试，不在测试中直接调用重试接口。
        scenario = ActivityScenario.launch(route("/articles/resources/orders"))
        waitText("重试")
        compose.onAllNodesWithText("重试")[0].performScrollTo().performClick()
        waitText("已重新尝试交付。")
        waitText("已解锁")
        val after = NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == exchangeId }
        assertEquals("unlocked", after.status)
        assertNull(after.lastError)
        assertEquals(before.attemptCount + 1, after.attemptCount)
        assertEquals(pointsBefore, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals(1, after.events.count { it.type == "retry" && it.attempt == after.attemptCount })
        record("native-retry-refreshes-status-attempt-and-events-without-another-point-charge")

        // 展开列表首条对应的新交付事件，确认重试后正文权限也已恢复。
        compose.onAllNodesWithText("交付记录")[0].performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("资源重新解锁", substring = true)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        val article = NativeArticleApi.getArticle(buyer.accessToken, after.articleSlug)
        assertTrue(article.resourceBlocks.single { it.key == after.blockKey }.unlocked)
        record("native-delivery-log-and-real-resource-body-unlock-agree-after-retry")
        screenshot("orders-success")
    }

    /** 从实际阅读页尝试已退款区块，验证明确错误反馈、正文继续锁定且不再次扣分。 */
    @Test fun realRefundedResourceShowsErrorAndStaysLocked() {
        // 只读取已明确传入的测试文章与退款记录，禁止用其它账号或正式文章构造负面场景。
        evidencePrefix = "native-local-resource-refunded"
        val arguments = InstrumentationRegistry.getArguments()
        val articleId = requireNotNull(arguments.getString("resourceArticleId")).toInt()
        val exchangeId = requireNotNull(arguments.getString("resourceExchangeId")).toInt()
        val delivery = NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == exchangeId }
        check(delivery.articleId == articleId && delivery.articleTitle.startsWith("native_resource_20261015_") && delivery.status == "refunded")
        val pointsBefore = NativeResourceApi.getReputation(buyer.accessToken).points
        val before = NativeArticleApi.getArticle(buyer.accessToken, delivery.articleSlug)
        assertFalse(before.resourceBlocks.single { it.key == delivery.blockKey }.unlocked)
        report.put("articleId", articleId).put("exchangeId", exchangeId).put("buyerPointsBefore", pointsBefore)
        record("real-refunded-resource-is-locked-before-opening-reading-page")

        // 点击真实阅读页兑换按钮，保留服务端业务提示而非静默返回或免费解锁。
        scenario = ActivityScenario.launch(route("/articles/${delivery.articleSlug}"))
        waitText("兑换")
        compose.onAllNodesWithText("兑换")[0].performScrollTo().performClick()
        waitText("该资源已退款，不能再次兑换。")
        compose.onNodeWithText("该资源已退款，不能再次兑换。").performScrollTo()
        val after = NativeArticleApi.getArticle(buyer.accessToken, delivery.articleSlug)
        assertFalse(after.resourceBlocks.single { it.key == delivery.blockKey }.unlocked)
        assertEquals(pointsBefore, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals("refunded", NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == exchangeId }.status)
        record("real-reading-page-displays-refunded-error-without-unlock-or-point-charge")
        screenshot("reading-error")
    }

    /** 根据 path 构建当前本地来源的真实业务路由，不绕开登录恢复和权限校验。 */
    private fun route(path: String): Intent {
        // 测试路由沿用应用声明的单实例 Activity 和 URI 解析。
        return Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path))
    }

    /** 等待 text 对应实际语义节点出现，同时推进 Compose 帧而非只阻塞线程。 */
    private fun waitText(text: String) {
        // 不把旧截图或保存的层级当作当前业务界面。
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 在真实阅读 WebView 中按 path 找到原链接并触发已接入的正式附件桥。 */
    private fun clickBodyLink(path: String) {
        // 每轮枚举真实 Activity 树，资源解锁后的重组可能替换原 WebView。
        var clicked = false
        compose.waitUntil(60_000) {
            val latch = CountDownLatch(1)
            scenario!!.onActivity { activity ->
                val views = bodyViews(activity.window.decorView)
                if (views.isEmpty()) latch.countDown() else {
                    var remaining = views.size
                    views.forEach { web ->
                        web.evaluateJavascript("(function(){var a=Array.from(document.querySelectorAll('a')).find(function(x){return x.getAttribute('href')===" + JSONObject.quote(nativeMediaUrl(path)) + ";});if(!a)return false;a.click();return true;})()") { result ->
                            if (result == "true") clicked = true
                            remaining -= 1
                            if (remaining == 0) latch.countDown()
                        }
                    }
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            clicked
        }
    }

    /** 从 view 当前子树返回全部 WebView，包含资源分段分别创建的阅读正文。 */
    private fun bodyViews(view: View): List<WebView> {
        // 不调用私有下载方法，不用自行创建的 WebView 替代真实阅读页。
        if (view is WebView) return listOf(view)
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { bodyViews(view.getChildAt(it)) }
    }

    /** 使用可选 token 访问本站 path，返回真实状态和成功响应字节，拒绝响应不记录敏感内容。 */
    private fun response(path: String, token: String?): Pair<Int, ByteArray> {
        // 网络请求只发给已限定的本地 API，外站 URL 不作为附件验收输入。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            status to if (status in 200..299) connection.inputStream.use { it.readBytes() } else byteArrayOf()
        } finally {
            connection.disconnect()
        }
    }

    /** 按 viewId 和可选 text 等待当前 DocumentsUI 节点，缺失则明确失败。 */
    private fun systemNode(viewId: String, text: String? = null): AccessibilityNodeInfo {
        // 严格匹配系统包名和原文件名，不以固定坐标选择用户已有文档。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 45_000
        do {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull {
                it.packageName?.toString() == "com.google.android.documentsui" && it.isVisibleToUser && (text == null || it.text?.toString() == text)
            }?.let { return it }
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("System document node unavailable: $viewId")
    }

    /** 将真实屏幕保存为 name 对应独立证据，不修改业务数据。 */
    private fun screenshot(name: String) {
        // 原始截图只保存在本地测试应用私有目录中。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(context.filesDir, "$evidencePrefix-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 只在 check 已通过断言后写入进度，不预填后续步骤的成功结果。 */
    private fun record(check: String) {
        // 持续保留已确认事实，失败运行不能覆盖为完整通过。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 保存无认证信息的报告，便于设备测试失败和成功后逐项核对。 */
    private fun writeReport() {
        // 作者密码、访问和刷新令牌从不进入测试报告。
        report.put("checks", checks)
        File(context.filesDir, "$evidencePrefix-report.json").writeText(report.toString(2))
    }
}
