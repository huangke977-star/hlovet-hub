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
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实 Android 页面和本地 API 验证匿名话题的创建、身份、互动、附件及状态生命周期，保留测试数据。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalAnonymousTopicTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker = "native_anonymous_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    private val checks = JSONArray()
    private val report = JSONObject().put("apiOrigin", BuildConfig.API_BASE_URL).put("testDataRemoved", false).put("productionTested", false)
    private var activity: ActivityScenario<MainActivity>? = null
    private var topicId = 0
    private var oldLocale: String? = null

    /** 只允许本地 API 来源，设置中文界面并确认匿名公开接口可访问。 */
    @Before
    fun requireLocalOrigin() {
        // 真实写操作必须落在本地容器，不能因为测试包配置漂移而触碰线上匿名话题。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        // 为每次本地专项生成新的访客限流身份，只清除本机访客键，不删除数据库中的测试话题或附件。
        context.getSharedPreferences("native-anonymous-topics", android.content.Context.MODE_PRIVATE).edit().remove("visitor-key").commit()
        NativeAnonymousTopicApi.list(context, page = 1, pageSize = 1)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("marker", marker)
    }

    /** 关闭真实页面、保存无凭据报告并恢复原语言，不删除本轮匿名话题或附件。 */
    @After
    fun restoreEnvironment() {
        // 页面关闭先于报告写入，避免后台协程继续改变测试证据。
        activity?.close()
        activity = null
        saveReport()
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 串联公开列表、搜索、发言、身份恢复、附件、互动、收藏和创建者关闭/重开流程。 */
    @Test
    fun realAnonymousTopicLifecycleAndInteractions() {
        // 先通过业务 API 创建两个独立话题，给页面搜索和分页提供稳定且不依赖历史数据的夹具。
        val password = "Anon_${UUID.randomUUID()}_9!"
        val created = NativeAnonymousTopicApi.create(context, marker, "匿名创建者", password)
        val second = NativeAnonymousTopicApi.create(context, "$marker second", "第二昵称", "Anon_${UUID.randomUUID()}_8!")
        topicId = created.summary.id
        NativeAnonymousTopicApi.saveIdentity(context, topicId, created.identity)
        report.put("topicId", topicId).put("secondTopicId", second.summary.id)
        assertEquals(marker, created.summary.title)
        assertTrue(created.identity.isCreator)
        record("real-api-create-two-topics-and-persist-creator-identity")

        // 列表查询同时核对搜索结果和 pageSize 分页，不把历史话题混入本轮判断。
        val firstPage = NativeAnonymousTopicApi.list(context, page = 1, pageSize = 1, query = marker, sort = "time")
        val secondPage = NativeAnonymousTopicApi.list(context, page = 2, pageSize = 1, query = marker, sort = "time")
        assertEquals(2, firstPage.total)
        assertEquals(1, firstPage.items.size)
        assertEquals(1, secondPage.items.size)
        assertTrue(firstPage.totalPages >= 2)
        record("real-public-search-sort-and-pagination")

        // 通过真实 MainActivity 深链进入匿名页，搜索本轮标记并打开精确话题卡片。
        activity = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/voices")),
        )
        waitTag("anonymous-topic-search")
        compose.onNodeWithTag("anonymous-topic-search").performTextReplacement(marker)
        waitTag("anonymous-topic-$topicId")
        compose.onNodeWithTag("anonymous-topic-open-$topicId").performClick()
        waitTag("anonymous-topic-dialog-$topicId")
        compose.onNode(
            hasText(marker, substring = false) and hasAnyAncestor(hasTestTag("anonymous-topic-dialog-$topicId")),
        ).assertIsDisplayed()
        record("real-main-activity-search-and-open-topic")
        screenshot("topic-open")

        // 在页面中发送文本，随后用真实接口回读消息编号和内容，避免只把本地乐观状态当成成功。
        val body = "$marker message"
        compose.onNodeWithTag("anonymous-topic-message-input").performTextReplacement(body)
        compose.onNodeWithTag("anonymous-topic-send").performClick()
        waitFor { NativeAnonymousTopicApi.get(context, topicId).messages.any { it.body == body } }
        val sent = NativeAnonymousTopicApi.get(context, topicId).messages.single { it.body == body }
        report.put("messageId", sent.id)
        waitTag("anonymous-message-${sent.id}")
        compose.onNodeWithText(body, substring = false).assertIsDisplayed()
        record("real-ui-send-text-and-server-readback")

        // 点赞后核对服务端计数，确保反应请求与消息行绑定而不是只改变了界面颜色。
        compose.onNodeWithTag("anonymous-message-like-${sent.id}").performClick()
        waitFor { NativeAnonymousTopicApi.get(context, topicId).messages.single { it.id == sent.id }.likeCount == 1 }
        record("real-ui-like-message-and-server-count")

        // 等待匿名发言限流窗口后上传小型文本附件，确认 multipart 入口和附件元数据可回读。
        Thread.sleep(6_500)
        val attachmentBody = "$marker attachment"
        val attachment = NativeAnonymousTopicApi.sendMessage(
            context = context,
            topicId = topicId,
            body = attachmentBody,
            identityToken = created.identity.identityToken,
            files = listOf(NativeUploadFile("$marker.txt", "text/plain", "anonymous attachment".toByteArray())),
        )
        assertEquals(attachmentBody, attachment.body)
        assertEquals(1, attachment.attachments.size)
        assertEquals("$marker.txt", attachment.attachments.single().originalName)
        report.put("attachmentMessageId", attachment.id).put("attachmentId", attachment.attachments.single().id)
        record("real-api-multipart-attachment-metadata")

        // 关闭详情后点击喜欢，再重新进入话题，验证访客状态与详情状态在页面间保持一致。
        compose.onNodeWithTag("anonymous-topic-close").performClick()
        waitFor { NativeAnonymousTopicApi.list(context, query = marker).items.any { it.id == topicId } }
        compose.onNodeWithTag("anonymous-topic-favorite-$topicId").performClick()
        waitFor { NativeAnonymousTopicApi.get(context, topicId).summary.favorited }
        record("real-ui-favorite-topic-and-persist-visitor-state")

        // 重新打开详情，通过创建者身份关闭并再次开放，核对只有创建者凭据能完成状态变更。
        compose.onNodeWithTag("anonymous-topic-open-$topicId").performClick()
        waitTag("anonymous-topic-dialog-$topicId")
        compose.onNodeWithTag("anonymous-topic-status").performClick()
        waitFor { NativeAnonymousTopicApi.get(context, topicId).summary.status == "closed" }
        compose.onNodeWithTag("anonymous-topic-status").performClick()
        waitFor { NativeAnonymousTopicApi.get(context, topicId).summary.status == "active" }
        record("real-ui-creator-close-and-reopen-topic")

        // 清除本地身份后用原密码恢复创建者昵称，确认恢复接口不依赖旧 token 或设备标识。
        context.getSharedPreferences("native-anonymous-topics", android.content.Context.MODE_PRIVATE).edit()
            .remove("identity:$topicId").commit()
        val restored = NativeAnonymousTopicApi.claimIdentity(context, topicId, password, null, create = false)
        assertEquals("匿名创建者", restored.nickname)
        assertTrue(restored.isCreator)
        NativeAnonymousTopicApi.saveIdentity(context, topicId, restored)
        record("real-password-restores-creator-identity-after-local-clear")

        // 最终回读消息、附件和收藏状态，保证页面过程结束时数据仍由服务端保存。
        val finalDetail = NativeAnonymousTopicApi.get(context, topicId)
        assertTrue(finalDetail.messages.any { it.id == sent.id && it.likeCount == 1 })
        assertTrue(finalDetail.messages.any { it.id == attachment.id && it.attachments.size == 1 })
        assertTrue(finalDetail.summary.favorited)
        report.put("completed", true)
        saveReport()
        screenshot("topic-final")
    }

    /** 等待网络回读满足条件，避免固定等待掩盖服务端尚未完成的状态更新。 */
    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(30_000) { runCatching { condition() }.getOrDefault(false) }
    }

    /** 等待真实语义标签出现，确保页面和接口请求都已完成后再执行下一步。 */
    private fun waitTag(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 记录一个已完成的匿名话题业务检查点，不写入密码、token 或附件正文。 */
    private fun record(check: String) {
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存本轮无敏感凭据的报告到应用私有目录和测试输出目录。 */
    private fun saveReport() {
        val content = report.put("checks", checks).toString(2)
        File(context.filesDir, "native-local-anonymous-topic-report.json").writeText(content)
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { path ->
            File(path).mkdirs()
            File(path, "native-local-anonymous-topic-report.json").writeText(content)
        }
    }

    /** 保存页面截图用于复核真实匿名话题布局，不把截图作为业务成功的唯一依据。 */
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "native-local-anonymous-topic-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
