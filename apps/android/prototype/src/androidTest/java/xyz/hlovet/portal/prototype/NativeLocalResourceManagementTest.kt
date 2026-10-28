package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 对真实本地 API 验证作者收益与管理员资源页面，保留业务数据并恢复原读者会话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalResourceManagementTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var creator: NativeSession? = null
    private var oldLocale: String? = null
    private lateinit var admin: NativeSession
    private val checks = JSONArray()
    private val report = JSONObject().put("apiBaseUrl", BuildConfig.API_BASE_URL)
        .put("testDataRemoved", false).put("adminInteractiveLoginTested", false)
        .put("adminAuthorization", "short-lived-local-container-signed-jwt")

    /** 仅接受精确本地构建、保留读者和独立短期管理员凭据，不修改账号角色或服务器配置。 */
    @Before fun requireLocalSessions() {
        // 来源保护先于任何凭据读取，正式构建必须跳过整个测试。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        reader = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        // 管理员令牌由本地容器签发，读取后立即删除传递文件，报告不写入任何认证信息。
        val credentialFile = File(context.filesDir, "native-local-resource-admin-token.json")
        val credential = try { JSONObject(credentialFile.readText()) } finally { credentialFile.delete() }
        val accessToken = credential.getString("accessToken")
        val (status, current) = get("/auth/me", accessToken)
        check(status == 200 && current.getInt("id") == 7 && current.getString("username") == "android_parity_admin" && current.getBoolean("isSuperAdmin"))
        admin = NativeSession(accessToken, "", 7, current.getString("username"), current.getString("nickname"),
            PreviewApi.appearanceFromJson(current.optJSONObject("appearance") ?: JSONObject()),
            isSuperAdmin = true, isAdministrator = current.optBoolean("isAdministrator"))
        report.put("buyerId", stored.userId).put("adminId", admin.userId)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 成败都保留截图与记录，关闭页面后恢复读者，注销临时作者而不删除文章或账本。 */
    @After fun restoreReader() {
        // 页面关闭后再恢复会话，防止旧 Activity 的异步刷新覆盖保留账号。
        if (reader == null) return
        try {
            if (oldLocale != null) { screenshot("last-state"); writeReport() }
            closePage()
        } finally {
            reader?.let { check(NativeSessionStore.save(context, it)) }
            creator?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 用独立作者的真实收益和专用兑换验证空态、幂等调整、失败与退款的实际页面同步。 */
    @Test fun authorEarningsAndAdminMutationsMatchRealLedger() {
        // 正常注册独立作者，密码仅留在内存；完成兴趣引导但不放宽认证策略。
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "native_earnings_$suffix"
        creator = (NativeAuthApi.register(username, username, "$username@example.com", "Local!${UUID.randomUUID()}", null, null)
            as NativeAuthResult.Authenticated).session
        NativeDiscoveryApi.completeOnboarding(requireNotNull(creator).accessToken, emptyList(), emptyList())
        report.put("authorId", requireNotNull(creator).userId).put("authorUsername", username)
        openAs(requireNotNull(creator), "/articles/resources/earnings")
        waitText("暂无资源收益。")
        metric("total", "0"); metric("pending", "0")
        screenshot("author-empty")
        record("real-author-earnings-route-displays-empty-state")
        closePage()

        // 通过正常发布和保留读者的真实兑换生成一条记录，不直接改积分或交付表。
        val author = requireNotNull(creator)
        val buyer = requireNotNull(reader)
        val buyerBefore = NativeResourceApi.getReputation(buyer.accessToken).points
        assertTrue("Retained reader needs one point", buyerBefore >= 1)
        val title = "native_earnings_20261015_$suffix"
        val draft = NativeArticleApi.createArticle(author.accessToken, NativeDraftInput(title, "Retained author/admin UI verification",
            "<p>Public introduction.</p><resource-block data-points=\"1\"><p>Protected management sample $suffix.</p></resource-block>", "html", "随笔", ""))
        val article = NativeArticleApi.publish(author.accessToken, draft.id)
        val before = NativeResourceApi.getReputation(author.accessToken)
        val block = NativeArticleApi.getArticle(buyer.accessToken, article.slug).resourceBlocks.single()
        NativeArticleApi.redeemResource(buyer.accessToken, article.id, block.key)
        val delivery = NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.articleId == article.id }
        assertEquals(buyerBefore - 1, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals(before.pendingPoints + 1, NativeResourceApi.getReputation(author.accessToken).pendingPoints)
        report.put("articleId", article.id).put("articleSlug", article.slug).put("title", title).put("exchangeId", delivery.id)
            .put("buyerPointsBefore", buyerBefore).put("authorPointsBeforeAdjustment", before.points).put("availableAt", delivery.availableAt)

        // 实际作者收益路由必须同时显示正确汇总、买家与服务器预计到账时间。
        openAs(author, "/articles/resources/earnings")
        waitText("累计兑换积分")
        metric("total", "1"); metric("pending", "1"); metric("settled", "0"); metric("refunded", "0")
        val recent = compose.onNodeWithTag("native-earnings-delivery-${delivery.id}")
        recent.performScrollTo().assertTextContains(buyer.nickname, substring = true)
        recent.assertTextContains("预计 ${resourceTime(delivery.availableAt)} 到账", substring = true)
        screenshot("author-pending")
        record("real-author-earnings-shows-matching-pending-summary-buyer-and-server-available-time")
        recent.performClick()
        waitText(title)
        compose.onNodeWithTag("native-earnings-total").assertDoesNotExist()
        record("real-author-recent-redemption-opens-the-correct-article")

        // 管理员使用现有真实角色的短期令牌进入 MainActivity，不将此当成密码登录验收。
        openAs(admin, "/admin/resources")
        waitText("资源与积分")
        rowText("native-admin-delivery-${delivery.id}", title)
        scrollText("native-admin-resources-list", "最近资源交付")
        compose.onNodeWithTag("native-admin-resources-list").performScrollToNode(hasContentDescription("积分调整"))
        compose.onNodeWithContentDescription("积分调整").performClick()
        clickDialog("确认提交")
        dialogText("请填写有效的 @用户名、正整数积分和唯一事件键。").assertIsDisplayed()
        fill("目标 @username", "@missing_$suffix")
        fill("积分数量", "3"); fill("唯一事件键", "native-topup-$suffix")
        clickDialog("确认提交"); waitText("用户不存在。")
        dialogText("用户不存在。").assertIsDisplayed()
        assertEquals(before.points, NativeResourceApi.getReputation(requireNotNull(creator).accessToken).points)
        record("real-admin-adjustment-invalid-fields-and-missing-user-errors-stay-in-dialog-without-charge")

        // 补发与重复提交共用同一事件键，页面必须区分新增与已经处理。
        fill("目标 @username", "@$username"); fill("备注（可选）", "native_topup_$suffix")
        clickDialog("确认提交"); waitText("积分账本已更新。")
        assertEquals(before.points + 3, NativeResourceApi.getReputation(requireNotNull(creator).accessToken).points)
        scrollText("native-admin-resources-list", "native_topup_$suffix")
        screenshot("admin-topup")
        openAdjustment(username, "3", "native-topup-$suffix", "native_topup_$suffix")
        clickDialog("确认提交"); waitText("该事件键已经处理过。")
        assertEquals(before.points + 3, NativeResourceApi.getReputation(requireNotNull(creator).accessToken).points)
        assertEquals(1, NativeResourceApi.listAdminPointAdjustments(admin.accessToken).items.count { it.description == "native_topup_$suffix" })
        record("real-admin-topup-refreshes-history-and-duplicate-event-does-not-credit-twice")

        // 违规扣分独立事件，同一真实目标只扣一次并刷新操作记录。
        openAdjustment(username, "1", "native-penalty-$suffix", "native_penalty_$suffix")
        dialogText("违规扣分").performClick()
        clickDialog("确认提交"); waitText("积分账本已更新。")
        assertEquals(before.points + 2, NativeResourceApi.getReputation(requireNotNull(creator).accessToken).points)
        scrollText("native-admin-resources-list", "native_penalty_$suffix")
        record("real-admin-penalty-updates-author-balance-and-adjustment-history")

        // 对本轮唯一交付操作，空原因不提交，确认失败后作者待结算收入不被重复记账。
        deliveryAction(delivery.id, "记录失败")
        clickDialog("确认记录"); dialogText("请填写失败原因。").assertIsDisplayed()
        fill("失败原因", "native_delivery_failure_$suffix")
        clickDialog("确认记录"); waitText("已记录资源交付失败。")
        rowText("native-admin-delivery-${delivery.id}", "交付失败")
        assertEquals("failed", NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == delivery.id }.status)
        assertFalse(NativeArticleApi.getArticle(buyer.accessToken, article.slug).resourceBlocks.single().unlocked)
        screenshot("admin-failed")
        record("real-admin-failure-dialog-updates-row-and-revokes-reader-resource-access")

        // 实际退款确认撤销待结算、退回买方一次，重新进入作者页核对汇总和明细。
        deliveryAction(delivery.id, "退款"); clickDialog("确认退款")
        waitText("已退款并写入积分账本。")
        rowText("native-admin-delivery-${delivery.id}", "已退款")
        assertEquals(buyerBefore, NativeResourceApi.getReputation(buyer.accessToken).points)
        assertEquals(before.pendingPoints, NativeResourceApi.getReputation(requireNotNull(creator).accessToken).pendingPoints)
        assertEquals("refunded", NativeResourceApi.listDeliveries(buyer.accessToken).items.single { it.id == delivery.id }.status)
        record("real-admin-refund-updates-row-restores-buyer-points-and-cancels-author-pending-income")
        openAs(requireNotNull(creator), "/articles/resources/earnings")
        waitText("累计兑换积分")
        metric("total", "1"); metric("pending", "0"); metric("settled", "0"); metric("refunded", "1")
        compose.onNodeWithTag("native-earnings-delivery-${delivery.id}").performScrollTo().assertTextContains("已退款", substring = true)
        screenshot("author-refunded")
        record("real-author-reentry-shows-refund-with-no-pending-or-settled-income")
        openAs(requireNotNull(creator), "/profile/points")
        waitText("积分明细")
        scrollText("native-points-list", "native_topup_$suffix")
        scrollText("native-points-list", "native_penalty_$suffix")
        screenshot("author-point-ledger")
        record("real-author-points-page-shows-matching-topup-and-penalty-ledger")
    }

    /** 用 session 打开 path 的实际 Activity；先关闭旧页并捕获刷新后的作者会话。 */
    private fun openAs(session: NativeSession, path: String) {
        // 避免重新写入已被 MainActivity 轮换的旧刷新令牌。
        closePage()
        val fresh = if (session.userId == creator?.userId) requireNotNull(creator) else session
        check(NativeSessionStore.save(context, fresh))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 关闭当前页面并保留轮换后的作者凭据，避免收尾恢复失效会话。 */
    private fun closePage() {
        // Activity 完全关闭后才读回安全存储，不让后续页面与旧页面并发写会话。
        scenario?.close(); scenario = null
        NativeSessionStore.load(context)?.let { stored -> if (stored.userId == creator?.userId) creator = stored }
    }

    /** 等待 text 的真实语义节点出现，不以固定延迟代替页面加载结果。 */
    private fun waitText(text: String) {
        // 异步读取期间持续推进 Compose 帧。
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 核对 metricName 指标中的 value，避免只因其它记录出现相同数字误判。 */
    private fun metric(metricName: String, value: String) {
        // 将值限定到命名指标的真实子节点，且确认当前视口可见。
        val tag = "native-earnings-$metricName"
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
    }

    /** 在 tag 代表的只读交付行内确认 text，不依赖可点击卡片的合并语义。 */
    private fun rowText(tag: String, text: String) {
        // 管理列表行不整体可点击，文字应通过行内子节点断言。
        compose.waitUntil(60_000) {
            runCatching {
                compose.onNodeWithTag("native-admin-resources-list").performScrollToNode(hasTestTag(tag))
                compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    /** 从 listTag 的真实懒加载列表查找 text，等待操作后的异步刷新完成再核对可见节点。 */
    private fun scrollText(listTag: String, text: String) {
        // 屏幕外项目尚未创建语义节点，不能直接对缺失节点执行 performScrollTo。
        compose.waitUntil(60_000) {
            runCatching {
                compose.onNodeWithTag(listTag).performScrollToNode(hasText(text))
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    /** 在积分弹窗填入 username/points/eventKey/note，不自动提交。 */
    private fun openAdjustment(username: String, points: String, eventKey: String, note: String) {
        // 每次从实际工具栏打开新表单，验证成功后清空输入的行为。
        compose.onNodeWithTag("native-admin-resources-list").performScrollToNode(hasContentDescription("积分调整"))
        compose.onNodeWithContentDescription("积分调整").performClick()
        fill("目标 @username", "@$username"); fill("积分数量", points)
        fill("唯一事件键", eventKey); fill("备注（可选）", note)
    }

    /** 在 id 指定交付行点击 description 对应动作，不能误操作历史样例。 */
    private fun deliveryAction(id: Int, description: String) {
        // 用稳定交付 ID 收窄操作节点，再滚动目标进入可见区域。
        val tag = "native-admin-delivery-$id"
        compose.onNodeWithTag("native-admin-resources-list").performScrollToNode(hasTestTag(tag))
        compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag(tag))).performClick()
    }

    /** 返回当前弹窗中的 text，防止列表同名节点被当成反馈。 */
    private fun dialogText(text: String): SemanticsNodeInteraction {
        // 仅匹配当前系统弹窗内节点。
        return compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    }

    /** 将 value 写入当前弹窗 label 对应输入框，保留其它输入。 */
    private fun fill(label: String, value: String) {
        // 依据真实表单标签定位，不使用固定坐标或直接改 Compose 状态。
        compose.onNode(hasSetTextAction() and hasText(label) and hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement(value)
    }

    /** 点击当前弹窗中 text 对应可操作按钮。 */
    private fun clickDialog(text: String) {
        // 同名弹窗标题没有点击行为，必须匹配真实按钮。
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
    }

    /** 对固定本地 path 使用 token 查询，返回 HTTP 状态和 JSON，不记录凭据。 */
    private fun get(path: String, token: String): Pair<Int, JSONObject> {
        // 不允许外部绝对地址复用本地管理令牌。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to JSONObject(stream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    /** 按服务器 ISO 字符串生成与页面一致的时间，仅用于比对显示，不改结算时间。 */
    private fun resourceTime(value: String): String {
        // 只去掉 ISO 展示分隔符，保持原始服务器时间语义。
        return value.replace("T", " ").replace(Regex("\\.\\d+Z?$"), "").removeSuffix("Z").take(16)
    }

    /** 保存 name 对应真实屏幕，便于与断言及账本一起核对。 */
    private fun screenshot(name: String) {
        // 只有业务页面截图，不截取密码或一次性凭据。
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(context.filesDir, "native-local-resource-management-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 在 check 的真实断言完成后追加检查点，失败时保留已通过部分。 */
    private fun record(check: String) {
        // 不预填未来步骤成功，也不把第一次失败覆盖成完整通过。
        checks.put(JSONObject().put("check", check).put("passed", true)); writeReport()
    }

    /** 写入无密钥与令牌的业务验证报告，所有夹具数据保留。 */
    private fun writeReport() {
        // 认证来源单独声明，不能把管理 JWT 页面测试宣称为交互登录通过。
        report.put("checks", checks)
        File(context.filesDir, "native-local-resource-management-report.json").writeText(report.toString(2))
    }
}
