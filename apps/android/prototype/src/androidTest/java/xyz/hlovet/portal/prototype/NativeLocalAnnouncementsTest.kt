package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 在真实 MainActivity/API 中验证独立角色的公告生命周期，保留记录并恢复原读者会话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalAnnouncementsTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 返回当前测试应用上下文，仅访问其私有会话与证据目录。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var manager: NativeSession? = null
    private var recipient: NativeSession? = null
    private var oldLocale: String? = null
    private var rootToken = ""
    private var roleCode = ""
    private var roleName = ""
    private var marker = ""
    private var announcementId = 0
    private val checks = JSONArray()
    private val report = JSONObject().put("origin", BuildConfig.API_BASE_URL).put("testDataRemoved", false)
        .put("superAdminInteractiveLoginTested", false)

    /** 只接受精确本地构建和已有读者，读取一次性夹具授权，不改变站点安全策略。 */
    @Before fun prepare() {
        // 来源校验必须早于读取授权，生产或隔离服务构建不能执行真实写入专项。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val saved = requireNotNull(NativeSessionStore.load(context))
        check(saved.userId in listOf(63, 64))
        reader = saved
        reader = NativeAuthApi.refresh(saved.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
        val security = nativeLocalSecurityPolicy()
        check(!security.optBoolean("mailServiceEnabled") && !security.optBoolean("untrustedDeviceEmailVerificationEnabled"))

        // 授权文件仅用于本地准备；读取后删除明文文件，不写入报告或日志。
        val file = File(context.filesDir, "native-local-announcements-fixture.json")
        val fixture = try { JSONObject(file.readText()) } finally { file.delete() }
        check(fixture.getString("origin") == BuildConfig.API_BASE_URL)
        rootToken = fixture.getString("accessToken")
        val root = request("/auth/me", rootToken)
        check(root.first == 200 && root.second.getBoolean("isSuperAdmin") && root.second.getString("username") == "android_parity_admin")
        roleCode = fixture.getString("roleCode")
        roleName = fixture.getString("roleName")
        // 每轮使用独立标题，失败时保留的公告不能被下一轮搜索误算为重复保存。
        marker = fixture.getString("marker") + "_" + UUID.randomUUID().toString().take(8)
        recipient = authenticated(NativeAuthApi.login(fixture.getString("recipientUsername"), fixture.getString("recipientPassword")))
        check(requireNotNull(recipient).userId == fixture.getInt("recipientId"))
        NativeDiscoveryApi.completeOnboarding(requireNotNull(recipient).accessToken, emptyList(), emptyList())
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", saved.userId).put("recipientId", recipient?.userId).put("roleCode", roleCode).put("marker", marker)
    }

    /** 关闭业务页并恢复原读者；只撤销本次辅助管理员身份，不删除账号、公告或通知。 */
    @After fun restore() {
        // 先记录失败现场再销毁 Activity，避免清理遮盖未通过的业务状态。
        if (reader == null) return
        try {
            if (oldLocale != null) capture("last-state")
            closePage()
        } finally {
            check(NativeSessionStore.save(context, requireNotNull(reader)))
            report.put("readerSessionRestored", NativeSessionStore.load(context)?.userId == reader?.userId)
            val revoked = manager?.let { value -> runCatching { !NativeAdminUsersApi.setAdministrator(rootToken, value.userId, false).isAdministrator }.getOrDefault(false) }
            report.put("fixtureAdministratorRevoked", revoked ?: JSONObject.NULL)
            listOfNotNull(manager, recipient).forEach { value -> runCatching { NativeAuthApi.logout(value.refreshToken) } }
            oldLocale?.let { value -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(value) } }
            writeReport()
        }
    }

    /** 从真实密码登录开始验证草稿更新、定向发布、自动已读、归档和服务端权限。 */
    @Test fun realAnnouncementLifecycleRetainsRecordsAndRestrictsAudience() {
        // 普通读者不能打开管理操作，也不能直接调用管理 API。
        openAs(requireNotNull(reader), "/admin/announcements")
        waitText("当前账号没有公告管理权限。")
        assertEquals(403, request("/announcements/admin", requireNotNull(reader).accessToken).first)
        record("reader-is-denied-by-real-admin-screen-and-api")

        // 通过正常注册创建辅助管理员，由短期超级管理员授权；登录本身必须经过真实表单。
        val username = "native_announce_" + UUID.randomUUID().toString().take(8)
        val password = "Local!${UUID.randomUUID()}"
        manager = authenticated(NativeAuthApi.register(username, username, "$username@example.invalid", password, null, null))
        NativeDiscoveryApi.completeOnboarding(requireNotNull(manager).accessToken, emptyList(), emptyList())
        NativeAdminUsersApi.setAdministrator(rootToken, requireNotNull(manager).userId, true)
        NativeAuthApi.logout(requireNotNull(manager).refreshToken)
        closePage()
        NativeSessionStore.clear(context)
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitTag("native-navigation-4")
        compose.onNodeWithTag("native-navigation-4").performClick()
        waitTag("native-profile-login")
        compose.onNodeWithTag("native-profile-login").performClick()
        waitTag("native-login-account")
        compose.onNodeWithTag("native-login-account").performTextReplacement(username)
        compose.onNodeWithTag("native-login-password").performTextReplacement(password)
        compose.onNodeWithTag("native-login-submit").performScrollTo().performNativeStableClick(compose)
        compose.waitUntil(60_000) { NativeSessionStore.load(context)?.userId == manager?.userId }
        manager = requireNotNull(NativeSessionStore.load(context))
        assertTrue(requireNotNull(manager).isAdministrator)
        assertFalse(requireNotNull(manager).isSuperAdmin)
        report.put("managerId", manager?.userId).put("managerUsername", username).put("ordinaryAdminInteractiveLoginTested", true)
        record("independent-administrator-signs-in-through-real-password-form")

        // 空公告不能发布；使用唯一标题和较长正文验证有界编辑弹窗与草稿提交。
        openManager()
        listNode().performScrollToIndex(0)
        compose.onNodeWithContentDescription("新建公告").performClick()
        waitTag("announcement-publish")
        compose.onNodeWithTag("announcement-publish").assertIsNotEnabled()
        val title = "$marker updated"
        val body = (1..20).joinToString("\n\n") { "保留的安卓公告验收正文 $it：仅供本次测试角色阅读。" }
        fill("admin-announcement-title", marker)
        fill("admin-announcement-body", body)
        dialogChoice("指定角色").performNativeStableClick(compose).assertIsSelected()
        dialogChoice(roleName).performNativeStableClick(compose).assertIsSelected()
        dialogChoice("浏览器推送").assertIsSelected().performNativeStableClick(compose).assertIsNotSelected()
        compose.onNodeWithTag("admin-announcement-save").performNativeStableClick(compose)
        waitText("公告草稿已保存。")
        var saved = NativeAdminAnnouncementsApi.list(requireNotNull(manager).accessToken, search = marker).items.single()
        announcementId = saved.id
        assertEquals("draft", saved.status)
        assertEquals(listOf(roleCode), saved.roleCodes)
        assertFalse(saved.pushEnabled)
        assertEquals(body, NativeAdminAnnouncementsApi.get(requireNotNull(manager).accessToken, announcementId).content)
        report.put("announcementId", announcementId)
        record("long-draft-saved-once-with-private-role-and-browser-push-disabled")

        // 同一编辑器继续保存必须更新既有 ID，不能生成第二条公告。
        fill("admin-announcement-title", title)
        fill("admin-announcement-summary", "指定角色真实验收摘要")
        compose.onNodeWithTag("admin-announcement-save").performNativeStableClick(compose)
        waitText("公告草稿已保存。")
        saved = NativeAdminAnnouncementsApi.list(requireNotNull(manager).accessToken, search = marker).items.single()
        assertEquals(announcementId, saved.id)
        assertEquals(title, saved.title)
        dialogButtonDescription("关闭").performNativeStableClick(compose)
        search(marker)
        record("editing-saved-draft-keeps-original-id-and-refreshes-filtered-list")

        // 发布确认可取消，取消不得改变数据库状态或创建收件通知。
        rowAction("发布").performNativeStableClick(compose)
        dialogChoice("取消").performNativeStableClick(compose)
        assertEquals("draft", current().summary.status)
        assertEquals(0, current().summary.recipientCount)
        rowAction("发布").performNativeStableClick(compose)
        dialogChoice("发布").performNativeStableClick(compose)
        waitText("公告已发布或安排定时发布。")
        awaitRow()
        val published = current()
        assertEquals("published", published.summary.status)
        assertEquals(1, published.summary.recipientCount)
        assertEquals(1, published.deliveryAttempts)
        assertFalse(published.deliveredAt.isNullOrBlank())
        capture("published")
        record("cancel-is-read-only-and-publish-delivers-only-to-one-fixture-recipient")

        // 指定角色以外的读者与游客拿到 404，不能通过直接链接读取正文。
        assertEquals(404, request("/announcements/public/$announcementId", null).first)
        assertEquals(404, request("/announcements/visible/$announcementId", requireNotNull(reader).accessToken).first)
        val visible = NativeCommunityApi.listAnnouncements(requireNotNull(recipient).accessToken)
        assertTrue(visible.items.single { it.id == announcementId }.unread)
        assertTrue(runCatching { NativeAdminAnnouncementsApi.update(requireNotNull(manager).accessToken, announcementId,
            NativeAdminAnnouncementInput(title, "", body, "role_restricted", "immediate", false, 0, false, null, null, listOf(roleCode))) }.isFailure)
        assertEquals(title, current().summary.title)
        record("role-boundaries-and-published-edit-rejection-are-enforced-by-real-api")

        // 原生读者页面自动打开详情，服务端会同时标记已读；关闭后列表不能保留旧未读标记。
        openAs(requireNotNull(recipient), "/announcements/$announcementId")
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(title) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        dialogChoice("关闭").performNativeStableClick(compose)
        // 列表在详情背后仍有语义节点，必须等弹窗真正消失，不能提前把背后的行断言为关闭成功。
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        val row = compose.onNode(hasText(title) and hasClickAction())
        row.assertIsDisplayed()
        val text = row.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertFalse("Opening an announcement must clear the list unread marker", text.contains("未读"))
        assertFalse(NativeCommunityApi.listAnnouncements(requireNotNull(recipient).accessToken).items.single { it.id == announcementId }.unread)
        val read = current()
        assertEquals(1, read.summary.confirmedCount)
        assertTrue(read.summary.viewCount >= 1)
        capture("recipient-read")
        record("native-detail-opening-persists-confirmation-and-clears-list-unread-without-refresh")

        // 普通管理员无权删除已发布/归档记录，归档会保留记录但撤掉用户可见入口。
        openManager()
        search(marker)
        awaitRow()
        compose.onNode(hasContentDescription("删除") and hasAnyAncestor(hasTestTag("admin-announcement-$announcementId"))).assertDoesNotExist()
        assertEquals(403, request("/announcements/admin/$announcementId", requireNotNull(manager).accessToken, "DELETE").first)
        rowAction("归档").performNativeStableClick(compose)
        dialogChoice("归档").performNativeStableClick(compose)
        waitText("公告已归档。")
        awaitRow()
        assertEquals("archived", current().summary.status)
        assertEquals(404, request("/announcements/visible/$announcementId", requireNotNull(recipient).accessToken).first)
        assertEquals(1, NativeAdminAnnouncementsApi.list(requireNotNull(manager).accessToken, search = marker).total)
        capture("archived")
        record("archive-removes-recipient-access-but-retains-admin-record-and-delete-permissions")
    }

    /** 校验 result 为实际登录成功，返回会话；挑战不被绕过或伪造完成。 */
    private fun authenticated(result: NativeAuthResult): NativeSession {
        // 本地前置策略不满足时直接失败，不修改二次验证开关。
        assertTrue(result is NativeAuthResult.Authenticated)
        return (result as NativeAuthResult.Authenticated).session
    }

    /** 保存 session 后打开 path 的真实页面，确保旧 Activity 不覆盖新会话。 */
    private fun openAs(session: NativeSession, path: String) {
        // 关闭旧页面时保留可能轮换后的测试会话，原读者只在收尾恢复。
        closePage()
        val fresh = when (session.userId) { manager?.userId -> requireNotNull(manager); recipient?.userId -> requireNotNull(recipient); else -> session }
        check(NativeSessionStore.save(context, fresh))
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + path)))
    }

    /** 打开真实公告管理页并等待列表完成，不能将后台加载当作空列表。 */
    private fun openManager() {
        // 每次返回管理页都使用真实密码登录产生的会话。
        openAs(requireNotNull(manager), "/admin/announcements")
        waitTag("admin-announcements-search")
        compose.waitUntil(60_000) { compose.onAllNodesWithText("正在读取公告…").fetchSemanticsNodes().isEmpty() }
    }

    /** 关闭真实页面并保留当前测试账号的轮换会话。 */
    private fun closePage() {
        // 原读者没有任何权限变更，不被测试账号的自动刷新覆盖。
        activity?.close()
        activity = null
        NativeSessionStore.load(context)?.let { saved ->
            if (saved.userId == reader?.userId) reader = saved
            if (saved.userId == manager?.userId) manager = saved
            if (saved.userId == recipient?.userId) recipient = saved
        }
    }

    /** 返回当前公告管理的纵向列表。 */
    private fun listNode(): SemanticsNodeInteraction {
        // 固定标记避免滚动到页面背后的其它容器。
        return compose.onNodeWithTag("admin-announcements-list")
    }

    /** 搜索 value 并等待本轮独立公告行出现。 */
    private fun search(value: String) {
        // 防抖由测试时钟推进，结果仍等待真实 HTTP 返回。
        listNode().performScrollToNode(hasTestTag("admin-announcements-search"))
        compose.onNodeWithTag("admin-announcements-search").performTextReplacement(value)
        compose.mainClock.advanceTimeBy(400)
        awaitRow()
    }

    /** 等待稳定 ID 的公告行，避免点击旧列表或尚未创建的懒加载项。 */
    private fun awaitRow() {
        // 只定位本次 ID，不依赖标题排序和列表第一项。
        compose.waitUntil(60_000) { runCatching { listNode().performScrollToNode(hasTestTag("admin-announcement-$announcementId")); true }.getOrDefault(false) }
    }

    /** 返回本次公告行内 description 操作。 */
    private fun rowAction(description: String): SemanticsNodeInteraction {
        // 公告 ID 限定操作范围，不能点到其它保留的测试记录。
        awaitRow()
        return compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("admin-announcement-$announcementId")))
    }

    /** 返回活动弹窗中 text 对应选项；先展开纵向可见区域，再定位横向选项。 */
    private fun dialogChoice(text: String): SemanticsNodeInteraction {
        // 只接受弹窗祖先，避免点到列表中的同名状态。
        val matcher = hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        awaitDialogLayout()
        // Compose 只滚动最近的祖先；横向行必须先作为目标滚进外层纵向表单。
        val horizontalRow = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(matcher) and hasAnyAncestor(isDialog()) and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(horizontalRow).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(horizontalRow).performScrollTo()
        }
        val node = compose.onNode(matcher)
        // 整行可见后再滚动行内选项；固定操作栏没有滚动祖先，不强行调用滚动。
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        return node
    }

    /** 返回活动编辑弹窗的 description 图标按钮。 */
    private fun dialogButtonDescription(description: String): SemanticsNodeInteraction {
        // 关闭不使用返回键，避免将输入法收起误算作弹窗已关闭。
        return compose.onNode(hasContentDescription(description) and hasAnyAncestor(isDialog()))
    }

    /** 以真实编辑语义将 tag 字段替换为 value。 */
    private fun fill(tag: String, value: String) {
        // 长正文表单先滚动到可见字段，不直接修改 Compose 草稿。
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        awaitDialogLayout()
    }

    /** 等待活动弹窗尺寸连续稳定后再滚动表单，保留键盘真实展开，不用返回键规避布局变化。 */
    private fun awaitDialogLayout() {
        // IME 使用系统实时时钟，Compose 空闲不能证明弹窗已经完成重新测量。
        var previous: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            val bounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val now = SystemClock.uptimeMillis()
            if (bounds == previous) now - stableSince >= 300 else {
                previous = bounds
                stableSince = now
                false
            }
        }
    }

    /** 等待 tag 对应的页面节点出现。 */
    private fun waitTag(tag: String) {
        // 实际语义节点证明页面已挂载，不依赖固定延时。
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 等待 text 对应的业务反馈出现。 */
    private fun waitText(text: String) {
        // 成功提示必须来自页面，而非测试直接读取 API 代替交互。
        compose.waitUntil(60_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 返回当前公告的真实管理详情，不触发读者阅读计数。 */
    private fun current(): NativeAdminAnnouncementDetail {
        // 已登录普通管理员权限由服务端再次判定。
        return NativeAdminAnnouncementsApi.get(requireNotNull(manager).accessToken, announcementId)
    }

    /** 调用精确本地 path，token 可为空，返回 HTTP 状态及 JSON；不记录授权内容。 */
    private fun request(path: String, token: String?, method: String = "GET"): Pair<Int, JSONObject> {
        // 路径不接受外部来源，所有写操作只针对本次稳定 ID。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            // 无论服务端是否拒绝，关闭本次连接，不改变其它会话。
            connection.disconnect()
        }
    }

    /** 记录已经通过的 check，失败时仍保留之前完成的证据。 */
    private fun record(check: String) {
        // 报告只包含公开业务标识，不保存密码、授权或配置密钥。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 将账号、公告编号和检查点保存到应用私有目录，供主机导出核对。 */
    private fun writeReport() {
        // 测试数据明确保留，不把截图或 JSON 作为业务删除理由。
        File(context.filesDir, "native-local-announcements-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 等待实际屏幕像素稳定后保存 name 整屏截图，不把窗口关闭动画当作最终界面。 */
    private fun capture(name: String) {
        // Compose 语义与无障碍事件可能早于系统窗口动画结束，必须复核实际合成帧。
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var previous: Bitmap? = null
        var lastCapture = 0L
        var stableSince = SystemClock.uptimeMillis()
        try {
            compose.waitUntil(8_000) {
                val now = SystemClock.uptimeMillis()
                if (now - lastCapture < 100) return@waitUntil false
                lastCapture = now
                val bitmap = requireNotNull(automation.takeScreenshot())
                val unchanged = previous?.sameAs(bitmap) == true
                previous?.recycle()
                previous = bitmap
                if (!unchanged) stableSince = now
                unchanged && now - stableSince >= 250
            }
            // 密码保持遮罩，只有合成像素连续稳定后才输出用于页面复核的 PNG。
            File(context.filesDir, "native-local-announcements-$name.png").outputStream().use {
                check(requireNotNull(previous).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            previous?.recycle()
        }
    }
}
