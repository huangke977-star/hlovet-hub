package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** 在真实本地 MainActivity 中保存、重开、取消文章计划，并等待自然发布/下线；保留全部业务记录。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalArticleScheduleTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 仅访问当前原生应用的私有会话与证据目录。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var session: NativeSession? = null
    private var oldLocale: String? = null
    private var articleId = 0
    private val marker = "native_article_schedule_20261019_" + UUID.randomUUID().toString().take(8)
    private val checks = JSONArray()
    private val report = JSONObject().put("origin", BuildConfig.API_BASE_URL).put("testDataRemoved", false)
        .put("evidencePrefix", marker).put("naturalSchedulerTested", false)

    /** 限定本地 API 与既有普通读者会话，不读取超级管理员授权或改变站点策略。 */
    @Before fun prepare() {
        // 来源检查早于任何写入，生产或设备内隔离来源不允许执行真实专项。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        // 只有本地注册策略允许且不要求外部验证时，失效会话才可以由正常注册流程补充测试夹具。
        val policy = NativeAuthApi.registrationPolicy()
        assumeTrue("Requires local registration without external verification", policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled)
        val saved = NativeSessionStore.load(context)
        // 优先复用当前设备的普通读者会话；令牌失效或没有会话时，通过产品注册接口创建独立夹具。
        val refreshed = saved
            ?.takeIf { !it.isAdministrator && !it.isSuperAdmin }
            ?.let { current -> runCatching { runBlocking { NativeSessionCoordinator.refresh(context, current) } }.getOrNull() }
        session = refreshed ?: registerReaderFixture()
        // 刷新或注册后再次确认权限边界，测试文章不能由管理员账号创建。
        assumeTrue("Requires a retained non-admin reader session after refresh or registration", !requireNotNull(session).isAdministrator && !requireNotNull(session).isSuperAdmin)
        check(NativeSessionStore.save(context, requireNotNull(session)))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("readerId", requireNotNull(session).userId).put("serverClockModified", false).put("lifecycleInvokedManually", false)
    }

    /** 即使失败也保存现场和部分检查点，恢复原读者，不清理文章、通知或本地证据。 */
    @After fun restore() {
        // 先保存当前页面，再销毁 Activity；清理不能掩盖业务失败现场。
        if (session == null) return
        try {
            if (oldLocale != null) capture("last-state")
        } finally {
            activity?.close()
            activity = null
            // 应用启动或重建可能已经轮换了 refresh token，结束时优先保留加密存储中的最新会话。
            val latest = NativeSessionStore.load(context) ?: requireNotNull(session)
            session = latest
            check(NativeSessionStore.save(context, latest))
            report.put("readerSessionRestored", NativeSessionStore.load(context)?.userId == latest.userId)
            oldLocale?.let { value -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(value) } }
            writeReport()
        }
    }

    /** 真实时间框完成取消/错误/重开/清除，最后由服务端自身任务自然发布并下线同一篇私有文章。 */
    @Test fun realSchedulePersistsCancelsPublishesAndUnpublishesNaturally() {
        // 独立私有草稿不会广播到公开发现页，不覆盖既有文章或修改作者权限。
        val reader = requireNotNull(session)
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.firstOrNull()?.name ?: "随笔"
        val article = NativeArticleApi.createArticle(reader.accessToken, NativeDraftInput(
            title = marker, summary = "Android article schedule acceptance", content = "<p>保留的文章定时发布与下线验收正文。</p>",
            contentFormat = "html", category = category, tags = "", visibility = "private",
        ))
        articleId = article.id
        report.put("articleId", article.id).put("articleSlug", article.slug)
        assertTrue(NativeArticleApi.publishCheck(reader.accessToken, articleId).valid)
        openEditor()
        openSchedule()

        // 取消系统日期窗口不能改变草稿；测试直接点击真实字段，不调用生产业务回调。
        compose.onNodeWithTag("article-schedule-publish-at").performNativeStableClick(compose)
        systemControl("datePicker")
        systemControl("button2").click()
        assertField("article-schedule-publish-at", "")
        record("private-draft-and-physical-time-field-open-with-cancel-preserving-empty-value")

        // 草稿仅有下线时间时应显示服务端错误、保留输入且不持久化非法计划。
        val future = LocalDateTime.now().plusMinutes(10).withSecond(0).withNano(0)
        selectTime("article-schedule-unpublish-at", future.plusMinutes(1))
        confirmSchedule()
        waitText("草稿需要同时设置未来的发布时间和下线时间。")
        assertField("article-schedule-unpublish-at", localText(future.plusMinutes(1)))
        assertTrue(rawArticle().getJSONObject("schedule").isNull("unpublishAt"))
        capture("validation-error")
        record("invalid-unpublish-only-plan-shows-error-and-keeps-draft-without-persisting")

        // 保存合法计划后回读原始 UTC，防止仅凭成功文案或本地状态判定成功。
        selectTime("article-schedule-publish-at", future)
        confirmSchedule()
        waitText("文章发布计划已保存")
        assertSchedule(future, future.plusMinutes(1))
        val listItem = NativeArticleApi.listMySchedules(reader.accessToken).single { it.id == articleId }
        assertEquals(localText(future), listItem.publishAt)
        assertEquals(localText(future.plusMinutes(1)), listItem.unpublishAt)
        record("native-confirm-persists-exact-utc-and-list-displays-device-local-time")

        // 销毁 Activity 后重开，并原样再次保存，服务端时间不得发生时区偏移。
        openEditor()
        openSchedule()
        assertField("article-schedule-publish-at", localText(future))
        assertField("article-schedule-unpublish-at", localText(future.plusMinutes(1)))
        capture("reopened")
        confirmSchedule()
        waitText("文章发布计划已保存")
        assertSchedule(future, future.plusMinutes(1))
        record("activity-reopen-restores-both-times-and-resaving-does-not-shift-utc")

        // 两个只读时间字段均能真实清除，确定后服务端计划与计划列表一起取消。
        openSchedule()
        clearTime("article-schedule-publish-at")
        clearTime("article-schedule-unpublish-at")
        confirmSchedule()
        waitText("文章发布计划已取消")
        assertSchedule(null, null)
        assertTrue(NativeArticleApi.listMySchedules(reader.accessToken).none { it.id == articleId })
        record("clearing-both-times-cancels-server-plan-and-removes-list-entry")

        // 预留系统交互时间后重新安排近未来计划；只观察真实时间，不修改时钟或数据库。
        val publishAt = LocalDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
        val unpublishAt = publishAt.plusMinutes(1)
        openSchedule()
        selectTime("article-schedule-publish-at", publishAt)
        selectTime("article-schedule-unpublish-at", unpublishAt)
        capture("natural-plan")
        confirmSchedule()
        waitText("文章发布计划已保存")
        assertSchedule(publishAt, unpublishAt)
        assertEquals("draft", rawArticle().getString("status"))
        assertTrue(instant(publishAt).isAfter(Instant.now().plusSeconds(15)))
        report.put("scheduledPublishAt", instant(publishAt).toString()).put("scheduledUnpublishAt", instant(unpublishAt).toString())
        record("future-plan-remains-draft-before-natural-publication")

        // 每两秒只读一次本人详情，等待既有服务端生命周期任务自然发布。
        val published = awaitStatus("published", instant(publishAt).plusSeconds(50))
        assertFalse(Instant.parse(published.getString("publishedAt")).isBefore(instant(publishAt)))
        assertTrue(published.getJSONObject("schedule").isNull("publishAt"))
        assertEquals(instant(unpublishAt), Instant.parse(published.getJSONObject("schedule").getString("unpublishAt")))
        assertEquals(articleId, NativeArticleApi.getArticle(reader.accessToken, article.slug).id)
        openEditor()
        waitText("已发布")
        capture("published")
        report.put("publishedAt", published.getString("publishedAt"))
        record("natural-publication-updates-server-and-real-editor-with-owner-readable-content")

        // 自然下线后管理记录与正文仍保留，编辑器重开得到空计划，不能恢复已消费的旧时间。
        val unpublished = awaitStatus("unpublished", instant(unpublishAt).plusSeconds(50))
        assertTrue(unpublished.getJSONObject("schedule").isNull("publishAt"))
        assertTrue(unpublished.getJSONObject("schedule").isNull("unpublishAt"))
        assertTrue(NativeArticleApi.listMySchedules(reader.accessToken).none { it.id == articleId })
        openEditor()
        openSchedule()
        assertField("article-schedule-publish-at", "")
        assertField("article-schedule-unpublish-at", "")
        capture("unpublished")
        report.put("naturalSchedulerTested", true).put("finalStatus", unpublished.getString("status"))
        record("natural-unpublication-retains-article-and-clears-consumed-plan-after-reopen")
    }

    /** 真实选择跨本地日期的发布时间和下线时间，核对 UTC 转换、重开回显和清除计划。 */
    @Test fun realSchedulePersistsAcrossLocalDate() {
        // 独立私有草稿只用于验证日期边界，不出现在公开发现页，也不修改既有文章。
        val reader = requireNotNull(session)
        val category = NativeSiteSettingsApi.loadWriterSettings().categories.firstOrNull()?.name ?: "随笔"
        val article = NativeArticleApi.createArticle(reader.accessToken, NativeDraftInput(
            // marker 已经包含唯一后缀，不能再追加文字而触发站点标题长度截断，导致页面等待条件失真。
            title = marker, summary = "Android cross-date schedule acceptance",
            content = "<p>保留的跨日期文章定时计划验收正文。</p>", contentFormat = "html",
            category = category, tags = "", visibility = "private",
        ))
        articleId = article.id
        report.put("crossDateArticleId", article.id).put("crossDateArticleSlug", article.slug)
        assertTrue(NativeArticleApi.publishCheck(reader.accessToken, articleId).valid)
        openEditor()
        openSchedule()

        // 目标固定为设备本地明天上午，避免测试运行时接近午夜或上午时间导致目标已经过去。
        val tomorrow = LocalDate.now().plusDays(1).atTime(9, 15)
        val tomorrowUnpublish = tomorrow.plusHours(2)
        selectTime("article-schedule-publish-at", tomorrow)
        selectTime("article-schedule-unpublish-at", tomorrowUnpublish)
        confirmSchedule()
        waitText("文章发布计划已保存")

        // 直接核对服务端原始 UTC 字符串，确保跨日期不是只在界面状态中成立。
        assertSchedule(tomorrow, tomorrowUnpublish)
        assertEquals(localText(tomorrow), NativeArticleApi.listMySchedules(reader.accessToken).single { it.id == articleId }.publishAt)
        record("cross-date-picker-persists-device-local-values-as-exact-utc")

        // 完全关闭并重新打开编辑页，确认服务端时间转换回设备本地日期和分钟没有偏移。
        openEditor()
        openSchedule()
        assertField("article-schedule-publish-at", localText(tomorrow))
        assertField("article-schedule-unpublish-at", localText(tomorrowUnpublish))
        capture("cross-date-reopened")
        record("cross-date-plan-restores-after-activity-reopen-without-timezone-shift")

        // 清除两个时间并核对计划列表，避免跨日测试留下仍会触发的未来任务。
        clearTime("article-schedule-publish-at")
        clearTime("article-schedule-unpublish-at")
        confirmSchedule()
        waitText("文章发布计划已取消")
        assertSchedule(null, null)
        assertTrue(NativeArticleApi.listMySchedules(reader.accessToken).none { it.id == articleId })
        record("cross-date-plan-clears-from-server-and-schedule-list")
    }

    /** 通过产品注册接口创建本地普通读者夹具，确保 refresh token 失效时专项仍可重复执行。 */
    private fun registerReaderFixture(): NativeSession {
        // 使用随机账号避免覆盖既有测试用户，账号和文章都保留供后续网页复查。
        val username = "native_sched_" + UUID.randomUUID().toString().replace("-", "").take(10)
        val password = "Local!" + UUID.randomUUID().toString() + "_9"
        val result = NativeAuthApi.register(username, "Android schedule reader", "$username@example.invalid", password, null, null)
        val registered = (result as? NativeAuthResult.Authenticated)?.session ?: error("本地定时发布读者夹具注册失败。")
        // 完成正常引导，避免首次登录提示覆盖编辑页并把 UI 等待误判成定时功能失败。
        NativeDiscoveryApi.completeOnboarding(registered.accessToken, emptyList(), emptyList())
        return registered
    }

    /** 打开当前 articleId 的真实编辑页面，等待服务端加载和保存操作可用。 */
    private fun openEditor() {
        // 每次冷启动前从加密会话存储读取最新 refresh token，避免前一次冷启动轮换令牌后测试继续使用旧令牌。
        refreshReaderSession()
        // 每次完全关闭旧页面，不能以内存中的时间状态替代重新读取服务端。
        activity?.close()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/articles/edit/$articleId")))
        waitText(marker)
        compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("发布计划").fetchSemanticsNodes().any { node ->
            !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithContentDescription("发布计划").assertIsEnabled()
    }

    /** 从设备私有存储刷新当前普通读者会话，保存旋转后的令牌并保持同一用户身份。 */
    private fun refreshReaderSession() {
        // 使用主应用最近一次启动保存的 refresh token，避免使用已被服务端轮换的旧内存值。
        val saved = requireNotNull(NativeSessionStore.load(context))
        check(saved.userId == requireNotNull(session).userId)
        // 通过真实认证接口刷新会话，不伪造访问令牌或绕过服务端鉴权。
        // 冷启动前也通过同一协调器刷新，确保测试使用服务端最新的一次性 refresh token。
        val refreshed = runBlocking { NativeSessionCoordinator.refresh(context, saved) }
        check(refreshed.userId == saved.userId)
        // 将新令牌写回应用私有存储，供随后冷启动的 MainActivity 使用。
        session = refreshed
        check(NativeSessionStore.save(context, refreshed))
    }

    /** 由工具栏按钮打开计划弹窗，不注入替代组合。 */
    private fun openSchedule() {
        // 等待字段出现再交互，避免页面工具栏与正文初始化时序造成假通过。
        compose.onNodeWithContentDescription("发布计划").performNativeStableClick(compose)
        waitText("设置发布计划")
        compose.onNodeWithTag("article-schedule-publish-at").assertExists()
    }

    /** 单次真实确认计划，失败只允许后续明确的用户操作再次提交。 */
    private fun confirmSchedule() {
        // 仅点击当前弹窗的确定按钮，不自动重试写请求。
        compose.onNode(hasText("确定") and hasAnyAncestor(isDialog())).performNativeStableClick(compose)
    }

    /** 清除 tag 对应的时间，并确认清除未额外打开系统日期窗口。 */
    private fun clearTime(tag: String) {
        // 清除按钮在字段内部，必须按祖先限定以免清除另一项时间。
        compose.onNode(hasContentDescription("清除时间") and hasAnyAncestor(hasTestTag(tag))).performNativeStableClick(compose)
        assertField(tag, "")
        assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.res("android", "datePicker")))
    }

    /** 用真实系统选择器设置 tag 对应的 target；日期跨月时通过系统控件切换到目标月份。 */
    private fun selectTime(tag: String, target: LocalDateTime) {
        // 空字段打开选择器时，产品默认以当前时间后一小时作为初始日期；跨日测试从这个真实初始日期计算月份差。
        val pickerInitialDate = LocalDateTime.now().plusHours(1).toLocalDate()
        compose.onNodeWithTag(tag).performNativeStableClick(compose)
        systemControl("datePicker")
        selectCalendarDate(pickerInitialDate, target.toLocalDate())
        systemControl("button1").click()
        systemControl("toggle_mode").click()
        systemControl("input_hour").text = target.hour.toString()
        systemControl("input_minute").text = target.minute.toString()
        systemControl("button1").click()
        assertField(tag, localText(target))
    }

    /** 在系统日期选择器中向前切换到目标月份，并点击目标日期单元格。 */
    private fun selectCalendarDate(initial: LocalDate, target: LocalDate) {
        // 仅允许未来日期，防止测试辅助方法在月份方向错误时静默选择了其它日期。
        val monthDelta = ChronoUnit.MONTHS.between(initial.withDayOfMonth(1), target.withDayOfMonth(1))
        check(monthDelta in 0..12) { "The schedule test only supports a future date within twelve months" }
        // 使用系统日期选择器的下一月控件，不调用 DatePicker 的业务回调或反射接口。
        repeat(monthDelta.toInt()) {
            systemControl("next").click()
        }
        // 优先从日期选择器内部查找目标日期，排除弹窗标题中的同数字文本。
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val picker = systemControl("datePicker")
        val monthView = device.findObject(By.res("android", "month_view"))
        val monthBounds = monthView?.visibleBounds
        val candidates = device.findObjects(By.text(target.dayOfMonth.toString())).filter { candidate ->
            val bounds = candidate.visibleBounds
            monthBounds == null || monthBounds.contains(bounds.centerX(), bounds.centerY())
        }
        val day = candidates.minByOrNull { it.visibleBounds.top } ?: picker.findObject(By.text(target.dayOfMonth.toString()))
        day?.click() ?: run {
            device.dumpWindowHierarchy(File(context.filesDir, "$marker-date-picker-failure.xml"))
            throw AssertionError("Target date ${target.dayOfMonth} was not displayed by the system picker")
        }
    }

    /** 查找 Android 系统 id 的控件，失败保留本轮系统层级，不直接调用时间选择业务回调。 */
    private fun systemControl(id: String): UiObject2 {
        // 系统窗口不在 Compose 树中，以有界等待获取真实平台控件。
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        return device.wait(Until.findObject(By.res("android", id)), 5_000) ?: run {
            device.dumpWindowHierarchy(File(context.filesDir, "$marker-picker-failure.xml"))
            throw AssertionError("Article schedule system control was not displayed: $id")
        }
    }

    /** 断言 tag 字段真实显示 value；空值时检查可编辑文本，避免误匹配标签中的空字符串。 */
    private fun assertField(tag: String, value: String) {
        // 只读输入的合并语义可能同时包含 label 和 value，非空值按字段文本匹配，空值仍要求精确为空。
        val matcher = if (value.isBlank()) {
            hasTestTag(tag) and hasText(value, substring = false)
        } else {
            hasTestTag(tag) and hasText(value, substring = true)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).assert(
            if (value.isBlank()) hasText(value, substring = false) else hasText(value, substring = true),
        )
    }

    /** 返回 value 的设备本地分钟文本，与真实输入组件格式一致。 */
    private fun localText(value: LocalDateTime): String {
        // 文本用于界面断言，服务端比较另外使用完整 UTC 时刻。
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    /** 将设备本地 value 转为服务端 UTC 比较值。 */
    private fun instant(value: LocalDateTime): Instant {
        // 不截断服务端字符串，不改变设备时区或系统时钟。
        return value.atZone(ZoneId.systemDefault()).toInstant()
    }

    /** 核对真实 API 中计划与 nullable 目标完全一致。 */
    private fun assertSchedule(publishAt: LocalDateTime?, unpublishAt: LocalDateTime?) {
        // 不通过编辑模型解析后的结果反向证明解析本身正确。
        val schedule = rawArticle().getJSONObject("schedule")
        if (publishAt == null) assertTrue(schedule.isNull("publishAt")) else assertEquals(instant(publishAt), Instant.parse(schedule.getString("publishAt")))
        if (unpublishAt == null) assertTrue(schedule.isNull("unpublishAt")) else assertEquals(instant(unpublishAt), Instant.parse(schedule.getString("unpublishAt")))
    }

    /** 只读返回当前文章的原始服务端 JSON，不记录认证头或密钥。 */
    private fun rawArticle(): JSONObject {
        // 只读本人详情并复用产品通道，异常不输出包含配置或凭据的网络正文。
        return NativeArticleApi.getMyArticleRaw(requireNotNull(session).accessToken, articleId)
    }

    /** 等待 status 直到自然 deadline，每两秒只读一次，不触发生命周期写操作。 */
    private fun awaitStatus(status: String, deadline: Instant): JSONObject {
        // 真实自然时间控制期限，Compose 时钟不会用来提前运行服务器任务。
        var nextRead = 0L
        var value: JSONObject? = null
        compose.waitUntil((deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1)) {
            if (SystemClock.uptimeMillis() >= nextRead) {
                nextRead = SystemClock.uptimeMillis() + 2_000
                value = rawArticle()
            }
            value?.optString("status") == status
        }
        return requireNotNull(value)
    }

    /** 等待 text 确实在当前页面语义树出现。 */
    private fun waitText(text: String) {
        // 页面反馈是 API 持久化断言的补充，不能替代后者。
        compose.waitUntil(60_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 记录已完成的 check，并立即写入不含认证信息的部分报告。 */
    private fun record(check: String) {
        // 尚未执行的业务阶段不预填成功，失败时保留已完成部分。
        checks.put(JSONObject().put("check", check).put("passed", true))
        writeReport()
    }

    /** 写入本轮报告，供主机核对检查点、文章编号及会话恢复。 */
    private fun writeReport() {
        // 固定报告入口包含唯一图片前缀，导出时不能误用历史截图。
        File(context.filesDir, "native-local-article-schedule-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 等待真实屏幕像素稳定后保存 name 截图，避免把系统窗口关闭动画当作布局。 */
    private fun capture(name: String) {
        // 截图不含输入凭据；每轮唯一前缀避免失败时复用旧图片。
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
            File(context.filesDir, "$marker-$name.png").outputStream().use {
                check(requireNotNull(previous).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            previous?.recycle()
        }
    }
}
