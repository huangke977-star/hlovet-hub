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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
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
        check(!saved.isAdministrator && !saved.isSuperAdmin) { "Announcement reader fixture must be a normal local account" }
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
            .put("evidencePrefix", "native-local-announcements-$marker")
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

        // 真实后端返回归档 404 时，原生深链必须展示独立提示，而不是留在没有反馈的列表。
        openAs(requireNotNull(recipient), "/announcements/$announcementId")
        assertUnavailableAnnouncement()
        record("archived-deep-link-shows-unavailable-dialog-without-opening-private-content")
    }

    /** 用真实系统时间选择器安排公告，等待服务端自然发布和到期，再核对原生页面与保留记录。 */
    @Test fun realScheduledAnnouncementPublishesAndExpiresNaturally() {
        // 独立普通管理员由正常注册与刷新获得会话；本场景不计作交互式管理员登录。
        val username = "native_announce_" + UUID.randomUUID().toString().take(8)
        manager = authenticated(NativeAuthApi.register(username, username, "$username@example.invalid", "Local!${UUID.randomUUID()}", null, null))
        NativeDiscoveryApi.completeOnboarding(requireNotNull(manager).accessToken, emptyList(), emptyList())
        NativeAdminUsersApi.setAdministrator(rootToken, requireNotNull(manager).userId, true)
        manager = NativeAuthApi.refresh(requireNotNull(manager).refreshToken)
        assertTrue(requireNotNull(manager).isAdministrator)
        assertFalse(requireNotNull(manager).isSuperAdmin)
        report.put("managerId", manager?.userId).put("managerUsername", username).put("ordinaryAdminInteractiveLoginTested", false)
            .put("naturalSchedulerTested", false).put("serverClockModified", false).put("lifecycleInvokedManually", false)
        openManager()
        compose.onNodeWithContentDescription("新建公告").performNativeStableClick(compose)
        waitTag("announcement-publish")
        val title = "$marker scheduled"
        fill("admin-announcement-title", title)
        fill("admin-announcement-body", "通过安卓系统选择器定时发布和自动下线的保留验收正文。")
        dialogChoice("指定角色").performNativeStableClick(compose)
        dialogChoice(roleName).performNativeStableClick(compose)
        dialogChoice("浏览器推送").performNativeStableClick(compose).assertIsNotSelected()
        dialogChoice("定时发布").performNativeStableClick(compose).assertIsSelected()

        // 日期选择和时间选择分别取消都不能修改草稿；不调用业务回调代替系统窗口操作。
        openSystemDatePicker("admin-announcement-scheduled-at")
        systemControl("button2").click()
        compose.onNodeWithTag("admin-announcement-scheduled-at").assert(hasText("", substring = false))
        openSystemDatePicker("admin-announcement-scheduled-at")
        systemControl("button1").click()
        systemControl("toggle_mode")
        systemControl("button2").click()
        compose.onNodeWithTag("admin-announcement-scheduled-at").assert(hasText("", substring = false))
        record("native-date-and-time-cancellation-leave-scheduled-draft-empty")

        // 预留真实交互时间，所有目标时间均在设备时区选择，再核对实际入库的 UTC。
        val publishAt = LocalDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
        val expiresAt = publishAt.plusMinutes(1)
        selectSystemTime("admin-announcement-scheduled-at", publishAt)
        compose.onNode(hasContentDescription("清除时间") and hasAnyAncestor(hasTestTag("admin-announcement-scheduled-at"))).performNativeStableClick(compose)
        compose.onNodeWithTag("admin-announcement-scheduled-at").assert(hasText("", substring = false))
        val clearedAt = SystemClock.uptimeMillis()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(5_000) {
            assertFalse("Clearing a time must not also open its picker", device.hasObject(By.res("android", "datePicker")))
            SystemClock.uptimeMillis() - clearedAt >= 400
        }
        selectSystemTime("admin-announcement-scheduled-at", publishAt)
        selectSystemTime("admin-announcement-expires-at", expiresAt)
        compose.onNodeWithTag("announcement-publish").assertIsEnabled()
        compose.onNodeWithTag("admin-announcement-save").performNativeStableClick(compose)
        waitText("公告草稿已保存。")
        val saved = NativeAdminAnnouncementsApi.list(requireNotNull(manager).accessToken, search = marker).items.single()
        announcementId = saved.id
        val publishInstant = publishAt.atZone(ZoneId.systemDefault()).toInstant()
        val expiryInstant = expiresAt.atZone(ZoneId.systemDefault()).toInstant()
        assertEquals(publishInstant, Instant.parse(saved.scheduledAt))
        assertEquals(expiryInstant, Instant.parse(saved.expiresAt))
        assertEquals(listOf(roleCode), saved.roleCodes)
        assertFalse(saved.pushEnabled)
        assertEquals("draft", saved.status)
        report.put("announcementId", announcementId).put("scheduledAt", publishInstant.toString()).put("expiresAt", expiryInstant.toString())
        dialogButtonDescription("关闭").performNativeStableClick(compose)
        search(marker)
        record("system-picker-values-persist-as-exact-utc-times-with-private-audience")

        // 安排发布后、时间未到之前不能投递，也不能经读者深链提前读取。
        assertTrue(publishInstant.isAfter(Instant.now().plusSeconds(15)))
        rowAction("发布").performNativeStableClick(compose)
        dialogChoice("发布").performNativeStableClick(compose)
        waitText("公告已发布或安排定时发布。")
        val scheduled = current()
        assertEquals("scheduled", scheduled.summary.status)
        assertEquals(0, scheduled.summary.recipientCount)
        assertEquals(0, scheduled.deliveryAttempts)
        assertNull(scheduled.summary.publishedAt)
        openAs(requireNotNull(recipient), "/announcements/$announcementId")
        assertUnavailableAnnouncement()
        record("scheduled-announcement-is-not-visible-or-delivered-before-its-time")

        // 只轮询管理端只读接口，等待既有 30 秒后台任务自然触发，不修改数据库或调用任务方法。
        val published = awaitNaturalStatus("published", publishInstant.plusSeconds(50))
        assertFalse(Instant.parse(published.summary.publishedAt).isBefore(publishInstant))
        assertEquals(1, published.summary.recipientCount)
        assertEquals(1, published.deliveryAttempts)
        assertFalse(published.deliveredAt.isNullOrBlank())
        assertEquals(404, request("/announcements/public/$announcementId", null).first)
        assertEquals(404, request("/announcements/visible/$announcementId", requireNotNull(reader).accessToken).first)
        report.put("publishedAt", published.summary.publishedAt).put("publishedObservedAt", Instant.now().toString())
        record("natural-server-tick-publishes-on-time-and-delivers-only-to-fixture-role")

        // 发布后的真实深链能打开正文、自动已读，列表与服务端均确认同一条公告。
        openAs(requireNotNull(recipient), "/announcements/$announcementId")
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(title) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        dialogChoice("关闭").performNativeStableClick(compose)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText(title) and hasClickAction()).assert(hasText("未读").not())
        assertEquals(1, current().summary.confirmedCount)
        capture("recipient-read")
        record("naturally-published-detail-opens-and-synchronizes-confirmed-reading")

        // 到期后故意点击尚未刷新的旧行，真实 404 必须提示不可访问并刷新移除旧入口。
        val expired = awaitNaturalStatus("expired", expiryInstant.plusSeconds(50))
        assertEquals(1, expired.summary.recipientCount)
        report.put("expiredObservedAt", Instant.now().toString())
        compose.onNode(hasText(title) and hasClickAction()).performNativeStableClick(compose)
        assertUnavailableAnnouncement()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes().isEmpty() }
        assertEquals(404, request("/announcements/visible/$announcementId", requireNotNull(recipient).accessToken).first)
        record("natural-expiry-invalidates-stale-row-with-visible-feedback-and-removes-entry")

        // 普通管理员仍可查看下线记录及投递统计，验收不删除公告、通知或账号。
        openManager()
        search(marker)
        compose.onNode(hasText("已下线") and hasAnyAncestor(hasTestTag("admin-announcement-$announcementId"))).assertIsDisplayed()
        assertEquals(1, NativeAdminAnnouncementsApi.list(requireNotNull(manager).accessToken, search = marker).total)
        assertEquals("expired", current().summary.status)
        capture("expired")
        report.put("naturalSchedulerTested", true)
        record("expired-admin-row-and-delivery-history-remain-available-after-refresh")
    }

    /** 等待独立不可访问弹窗并关闭；确认没有重试按钮或详情正文，随后仍可继续操作列表。 */
    private fun assertUnavailableAnnouncement() {
        // 真实接口可能隐藏权限原因，只断言不泄漏存在性的统一提示。
        val message = "公告已下线、不存在或当前账号无权查看。"
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(message) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(message) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onAllNodes(hasText("重试") and hasAnyAncestor(isDialog())).assertCountEquals(0)
        capture("unavailable")
        dialogChoice("关闭").performNativeStableClick(compose)
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 点击 tag 字段打开真实日期选择器；失败时保留系统控件树，禁止绕过点击直接赋值。 */
    private fun openSystemDatePicker(tag: String) {
        // 字段必须先进入编辑器可见区域，触摸稳定后才等待系统窗口。
        compose.onNodeWithTag(tag).performScrollTo().performNativeStableClick(compose)
        systemControl("datePicker")
    }

    /** 返回 Android 系统 id 对应的真实控件；超时输出不含凭据的选择器层级供复核。 */
    private fun systemControl(id: String): UiObject2 {
        // 系统日期/时间选择器不是 Compose 语义节点，必须通过平台 UI 实际交互。
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        return device.wait(Until.findObject(By.res("android", id)), 5_000) ?: run {
            device.dumpWindowHierarchy(File(context.filesDir, "native-local-announcements-$marker-picker-failure.xml"))
            throw AssertionError("System picker control was not displayed: $id")
        }
    }

    /** 通过系统日期和键盘时间选择器填写 tag 对应的 target，返回后校验可见时间文本。 */
    private fun selectSystemTime(tag: String, target: LocalDateTime) {
        // 本次自然定时验收只选择当前默认日期，不将其计作跨日日期选择或其它时区的交互验收。
        check(target.toLocalDate() == LocalDateTime.now().plusHours(1).toLocalDate())
        openSystemDatePicker(tag)
        systemControl("button1").click()
        systemControl("toggle_mode").click()
        systemControl("input_hour").text = target.hour.toString()
        systemControl("input_minute").text = target.minute.toString()
        systemControl("button1").click()
        // 必须读取最终表单文本，不能只相信系统选择器回调已执行。
        val value = target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag) and hasText(value)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assert(hasText(value))
    }

    /** 仅轮询管理详情等待 status，deadline 是按自然时间计算的最终期限，不触发任何业务写入。 */
    private fun awaitNaturalStatus(status: String, deadline: Instant): NativeAdminAnnouncementDetail {
        // 每两秒最多一个只读请求，服务端仍按自身调度运行，减少本地与服务器资源占用。
        var nextRead = 0L
        var observed: NativeAdminAnnouncementDetail? = null
        val timeout = (deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1)
        compose.waitUntil(timeout) {
            val now = SystemClock.uptimeMillis()
            if (now >= nextRead) {
                nextRead = now + 2_000
                observed = current()
            }
            // 发布状态与投递统计可能有短暂先后，必须等这一轮投递完成，不能把中间态误判为失败。
            observed?.summary?.status == status && (status != "published" || !observed?.deliveredAt.isNullOrBlank())
        }
        return requireNotNull(observed)
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
            // 每轮带唯一标题前缀，失败时不能把上轮残留截图误作本轮通过证据。
            File(context.filesDir, "native-local-announcements-$marker-$name.png").outputStream().use {
                check(requireNotNull(previous).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            previous?.recycle()
        }
    }
}
