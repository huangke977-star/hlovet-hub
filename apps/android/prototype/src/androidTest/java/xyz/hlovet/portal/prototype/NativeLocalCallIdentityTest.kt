package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用本地真实账号、头像上传和 Socket 来电验收 MainActivity 身份显示及拒接/取消，不冒充双端媒体验收。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalCallIdentityTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 只使用目标应用私有目录保存非敏感报告，不导出加密登录材料。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var callerSocket: NativeChatSocket? = null
    private var fixture: NativeSession? = null
    private var originalUserId = 0
    private var previousLocale = "zh-CN"
    private var activeCallId = 0
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 只允许保留的本地读者会话和无外部验证码策略，不修改服务器或生产登录配置。 */
    @Before fun prepare() {
        // 明确来源和账号前缀，禁止借用任意管理员或生产用户完成写入。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.username.startsWith("native_restore_"))
        originalUserId = stored.userId
        previousLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
    }

    /** 仅释放本轮 Activity/Socket 和辅助登录，保留账号、头像、好友关系、通话及其系统消息。 */
    @After fun finish() {
        // 异常中断也通过正常 Socket 尝试结束自己的来电，不写数据库或重置通话状态。
        if (activeCallId > 0) callerSocket?.endCall(activeCallId)
        activity?.close()
        callerSocket?.close()
        fixture?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
        report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == originalUserId)
        saveReport()
        compose.runOnUiThread { NativeI18n.applyLocale(previousLocale) }
    }

    /** 真实来电加载新账号头像；拒绝和主叫取消均关闭当前面板且服务端返回正确终态。 */
    @Test fun realIncomingAvatarDeclineAndCallerCancellation() {
        // 先正常启动已有会话，让 MainActivity 自己完成 token 刷新，再读取当前加密会话。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=74")))
        waitFor(hasContentDescription("聊天已连接"))
        val reader = requireNotNull(NativeSessionStore.load(context))
        assertEquals(originalUserId, reader.userId)
        val marker = "native_ci_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val registered = NativeAuthApi.register(marker, "头像来电样例", "$marker@android-fixture.invalid", "Native_${UUID.randomUUID()}_9!", null, null)
        check(registered is NativeAuthResult.Authenticated)
        val caller = registered.session.also { fixture = it }
        report.put("readerId", reader.userId).put("fixtureId", caller.userId).put("fixtureUsername", marker)
        // 图片走真实上传和服务器处理，随后从 Socket 响应中的头像 URL 下载；不注入 bitmap 给通话组件。
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEB2861.toInt()) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val profile = NativeProfileApi.uploadAvatar(caller.accessToken, NativeUploadFile("$marker.png", "image/png", bytes))
        assertTrue(requireNotNull(profile.avatarUrl).startsWith("/auth/avatars/"))
        report.put("avatarUrl", profile.avatarUrl)
        NativeFriendsApi.requestFriend(reader.accessToken, caller.userId, "原生头像及拒接验收")
        val request = NativeFriendsApi.listFriendships(caller.accessToken).incoming.single { it.user.id == reader.userId }
        NativeFriendsApi.respondFriendRequest(caller.accessToken, request.id, "accepted")
        val conversationId = NativeFriendsApi.getOrCreateConversation(caller.accessToken, reader.userId).id
        report.put("conversationId", conversationId)
        record("real-account-avatar-upload-and-accepted-friendship")

        // 辅助端只发真实信令，不提供媒体轨道；被叫端始终使用 MainActivity 全局来电界面。
        val connected = AtomicBoolean(false)
        val result = AtomicReference<NativeCallSession?>()
        val ended = AtomicReference<NativeCallSession?>()
        val failure = AtomicReference<String?>()
        val socket = NativeChatSocket(caller.accessToken, {}, { _, _ -> }, { failure.set(it) }, { connected.set(it) }, onCallEnded = { ended.set(it) })
        callerSocket = socket
        socket.connect()
        compose.waitUntil(20_000) { connected.get() }
        socket.startCall(conversationId, "voice") { ok, call, message -> if (ok) result.set(call) else failure.set(message ?: "call start failed") }
        compose.waitUntil(20_000) { result.get() != null || failure.get() != null }
        check(failure.get() == null) { "本地来电发起失败，详见服务器通话记录。" }
        activeCallId = requireNotNull(result.get()).id
        report.put("declinedCallId", activeCallId)
        waitFor(hasContentDescription("接听通话"))
        compose.onNodeWithTag("native-call-peer-name").assertTextEquals("头像来电样例")
        compose.waitUntil(15_000) {
            val pixels = compose.onNodeWithTag("native-call-peer-avatar").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.red > .8f && center.green < .3f && center.blue > .25f
        }
        compose.onNodeWithTag("native-call-duration").assertDoesNotExist()
        capture("real-incoming-avatar")
        record("real-main-activity-incoming-avatar-pixels-and-name")
        // 拒接必须由被叫真实按钮发出，等待辅助主叫收到服务端终态后再开始下一通。
        compose.onNodeWithContentDescription("拒绝通话").performClick()
        compose.waitUntil(20_000) { ended.get()?.status == "declined" }
        compose.onNodeWithTag("native-call-peer-name").assertDoesNotExist()
        activeCallId = 0
        record("native-ui-decline-synchronizes-server-terminal-state")

        // 第二通在来电面板可见时由主叫取消，验证被叫不会残留接听按钮或旧身份。
        result.set(null)
        ended.set(null)
        socket.startCall(conversationId, "voice") { ok, call, message -> if (ok) result.set(call) else failure.set(message ?: "call start failed") }
        compose.waitUntil(20_000) { result.get() != null || failure.get() != null }
        check(failure.get() == null)
        activeCallId = requireNotNull(result.get()).id
        report.put("cancelledCallId", activeCallId)
        waitFor(hasContentDescription("接听通话"))
        socket.endCall(activeCallId)
        compose.waitUntil(20_000) { ended.get()?.status == "cancelled" }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-call-peer-name").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        activeCallId = 0
        capture("real-caller-cancelled")
        record("caller-cancellation-clears-native-incoming-panel")
        report.put("completed", true)
        saveReport()
    }

    /** 等待 matcher 对应的真实页面控件，不通过固定坐标或修改宿主状态触发来电。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 主页面加载和 Socket 事件都有界等待，超时保留已有检查点而非重试整个写场景。
        compose.waitUntil(60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 将 check 已通过阶段写入非敏感报告，不记录令牌、密码或媒体密钥。 */
    private fun record(check: String) {
        // 每完成一项立即保存，真实接口失败时仍能核对已经创建的业务数据。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存当前专项报告，账号和通话编号可在本地管理页面继续核对。 */
    private fun saveReport() {
        // 私有目录报告不包含登录材料，不自动上传或加入仓库。
        File(context.filesDir, "native-local-call-identity-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 等实际合成帧稳定后保存 name 对应的 MainActivity 整屏，避免将窗口关闭动画当作最终状态。 */
    private fun capture(name: String) {
        // 语义树可能早于系统 Dialog 动画移除，沿用现有页面专项的连续稳定像素判定。
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var previous: Bitmap? = null
        var lastCapture = 0L
        var stableSince = SystemClock.uptimeMillis()
        val directory = File(context.filesDir, "native-local-call-identity-evidence").apply { mkdirs() }
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
            // 只保留本轮虚构测试账号界面，不截取其它应用或生产页面。
            File(directory, "$name.png").outputStream().use { requireNotNull(previous).compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            // 包括等待失败在内都释放临时位图，业务和报告记录继续保留。
            previous?.recycle()
        }
    }
}
