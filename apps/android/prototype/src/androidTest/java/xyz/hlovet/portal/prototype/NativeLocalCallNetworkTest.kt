package xyz.hlovet.portal.prototype

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
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

/** 通过专用本地 TCP 网关真实中断 Socket，检查断网清理、重连及重新来电，不清除已有会话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalCallNetworkTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 只读取本任务应用的私有本地登录态，不接触生产凭据。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var caller: NativeChatSocket? = null
    private var fixture: NativeSession? = null
    private var userId = 0
    private var conversationId = 0
    private var callId = 0
    private var previousLocale = "zh-CN"
    private val connected = AtomicBoolean(false)
    private val ended = AtomicReference<NativeCallSession?>()
    private val checks = JSONArray()
    private val calls = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 核对来源、保留账号与回环控制面，测试不能误中断真实服务器或全局网络。 */
    @Before fun prepare() {
        // 仅运行于固定本地构建及已有读者会话；控制端口必须由外部任务显式建立 reverse。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        userId = requireNotNull(NativeSessionStore.load(context)).userId
        previousLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
        check(!control("GET").getBoolean("blocked"))
        report.put("readerId", userId)
        // 每次开跑先写未完成状态，外部导出不会把上一次保留报告误认成本次成功。
        saveReport()
    }

    /** 恢复 TCP 可达后正常释放自己的通话/辅助会话，不删除夹具、关系或消息。 */
    @After fun finish() {
        // 即使断言失败也先等专用网关自动恢复，避免辅助退出变成新的网络错误。
        try {
            compose.waitUntil(15_000) { !control("GET").getBoolean("blocked") }
            caller?.endCall(callId)
            activity?.close()
            caller?.close()
            fixture?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
        } finally {
            // 原读者会话和界面语言必须保留，报告不存储访问令牌。
            report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == userId)
            saveReport()
            compose.runOnUiThread { NativeI18n.applyLocale(previousLocale) }
        }
    }

    /** 响铃和连接阶段分别中断，恢复后新来电可拒绝；同账号其它 Socket 接听时只清理本机来电。 */
    @Test fun disconnectClearsRingingAndConnectingThenAllowsFreshCall() {
        // 经正常 API 建立真实好友和会话，辅助 Socket 只提供信令，不冒充双原生媒体验收。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=74")))
        waitFor(hasContentDescription("聊天已连接"))
        val marker = "native_cn_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val registration = NativeAuthApi.register(marker, "断网通话样例", "$marker@android-fixture.invalid", "Native_${UUID.randomUUID()}_9!", null, null)
        check(registration is NativeAuthResult.Authenticated)
        val auxiliary = registration.session.also { fixture = it }
        // 首页刷新完成后读取最新加密会话，避免辅助 API 与 MainActivity 竞争刷新令牌。
        compose.waitUntil(20_000) { NativeSessionStore.load(context)?.accessToken != null }
        val reader = requireNotNull(NativeSessionStore.load(context))
        NativeFriendsApi.requestFriend(reader.accessToken, auxiliary.userId, "原生断网验收")
        val request = NativeFriendsApi.listFriendships(auxiliary.accessToken).incoming.single { it.user.id == userId }
        NativeFriendsApi.respondFriendRequest(auxiliary.accessToken, request.id, "accepted")
        conversationId = NativeFriendsApi.getOrCreateConversation(auxiliary.accessToken, userId).id
        report.put("fixtureId", auxiliary.userId).put("fixtureUsername", marker).put("conversationId", conversationId)
        activity?.close()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=$conversationId")))
        waitFor(hasContentDescription("聊天已连接"))
        caller = NativeChatSocket(auxiliary.accessToken, {}, { _, _ -> }, {}, { connected.set(it) }, onCallEnded = { ended.set(it) })
            .also { it.connect() }
        compose.waitUntil(20_000) { connected.get() }
        record("real-fixture-and-chat-ready")

        // 第一通尚未接听即中断，call:ended 无法送达本端，必须由实际 disconnect 主动清理。
        startIncoming()
        cutAndRecover("ringing")

        // 第二通真实点击接听后中断；辅助主叫没有媒体，验证目标仅为连接阶段生命周期。
        startIncoming()
        compose.onNodeWithContentDescription("接听通话").performClick()
        waitFor(hasText("连接中"))
        cutAndRecover("connecting")

        // 断网前的回执/媒体回调不能覆盖第三通来电，恢复后仍可使用正常拒接入口。
        startIncoming()
        compose.onNodeWithContentDescription("拒绝通话").performClick()
        compose.waitUntil(10_000) { ended.get()?.id == callId && ended.get()?.status == "declined" }
        waitFor(hasTestTag("native-call-peer-name"), expected = false)
        record("fresh-incoming-call-can-be-declined-after-two-network-recoveries")

        // 第三个被叫连接代表同账号另一个页面/设备，主界面必须依据真实 Socket ID 识别接听归属。
        val otherConnected = AtomicBoolean(false)
        val currentReader = requireNotNull(NativeSessionStore.load(context))
        val otherDevice = NativeChatSocket(currentReader.accessToken, {}, { _, _ -> }, {}, { otherConnected.set(it) })
        try {
            otherDevice.connect()
            compose.waitUntil(20_000) { otherConnected.get() }
            startIncoming()
            val response = AtomicReference<NativeCallSession?>()
            otherDevice.respondCall(callId, true) { ok, call, _ -> if (ok) response.set(call) }
            compose.waitUntil(15_000) { response.get()?.id == callId }
            waitFor(hasTestTag("native-call-peer-name"), expected = false)
            record("actual-other-socket-claim-clears-only-this-incoming-panel")
            requireNotNull(caller).endCall(callId)
            compose.waitUntil(15_000) { ended.get()?.id == callId }
        } finally {
            // 辅助接听者不提供媒体，不将 Socket 归属专项描述为双设备语音通话。
            otherDevice.close()
        }
        callId = 0
        report.put("completed", true)
        saveReport()
    }

    /** 通过真实服务端发起新语音来电，并保存编号供数据库独立核对。 */
    private fun startIncoming() {
        // 同一账号和同一会话连续呼入，能够检验旧结束状态是否误伤后续通话。
        ended.set(null)
        val result = AtomicReference<NativeCallSession?>()
        val failure = AtomicReference<String?>()
        requireNotNull(caller).startCall(conversationId, "voice") { ok, call, message ->
            if (ok) result.set(call) else failure.set(message ?: "start failed")
        }
        compose.waitUntil(15_000) { result.get() != null || failure.get() != null }
        check(failure.get() == null) { "新来电发起失败，保留真实记录供核对。" }
        callId = requireNotNull(result.get()).id
        calls.put(callId)
        waitFor(hasContentDescription("接听通话"))
        saveReport()
    }

    /** 中断专用 TCP 转发并自动恢复，检查 phase 的面板立即清理且重连后不复活。 */
    private fun cutAndRecover(phase: String) {
        // 只关闭被 reverse 到此网关的任务连接，server 和其它客户端持续运行。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val notice = automation.executeAndWaitForEvent({ assertTrue(control("POST").getBoolean("blocked")) }, { event ->
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == context.packageName &&
                event.text.any { it.toString() == "聊天连接已断开，当前通话已结束。" }
        }, 6_000)
        notice.recycle()
        record("$phase-disconnect-shows-real-system-notice")
        try {
            waitFor(hasTestTag("native-call-peer-name"), expected = false, timeout = 5_000)
        } finally {
            // 失败时也保存实际面板，不能只凭预期状态推断界面已经消失。
            capture("$phase-disconnected")
        }
        record("$phase-disconnect-clears-native-call-before-network-recovers")
        // 真实点击新增重试入口；离线期间刷新失败也不能清除会话或让已结束电话复活。
        waitFor(hasContentDescription("重新连接聊天"), timeout = 5_000)
        compose.onNodeWithContentDescription("重新连接聊天").performClick()
        assertEquals(userId, NativeSessionStore.load(context)?.userId)
        record("$phase-user-can-retry-without-losing-session")
        compose.waitUntil(15_000) { !control("GET").getBoolean("blocked") }
        compose.waitUntil(20_000) { connected.get() }
        waitFor(hasContentDescription("聊天已连接"))
        val until = SystemClock.uptimeMillis() + 1_000
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("native-call-peer-name").assertDoesNotExist()
            SystemClock.uptimeMillis() >= until
        }
        record("$phase-reconnect-does-not-resurrect-old-call")
        capture("$phase-reconnected")
    }

    /** 调用固定回环控制面，method=POST 触发一次限时中断，GET 只读取状态。 */
    private fun control(method: String): JSONObject {
        // 该接口不接收任何登录材料，显式直连且不能指向任意外部 URL。
        val connection = URL("http://127.0.0.1:3004/cut").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        try {
            check(connection.responseCode == 200)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** 有界等待 matcher 是否符合 expected，断网阶段使用短时限而非等恢复后误报清理。 */
    private fun waitFor(matcher: SemanticsMatcher, expected: Boolean = true, timeout: Long = 20_000) {
        // 显式检查真实 Compose 面板，不直接修改宿主 state 或手动触发断线回调。
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() == expected }
    }

    /** 记录已成功的 check，失败之后不补写成功状态。 */
    private fun record(check: String) {
        // 每一步立即落盘，保留导致失败前的真实进度。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保留 name 对应的真实整屏，核对通话关闭与重连后的聊天界面。 */
    private fun capture(name: String) {
        // 只导出当前本地页面，不抓取应用私有会话或电脑剪贴板。
        val directory = File(context.filesDir, "native-local-call-network-evidence").apply { mkdirs() }
        val run = InstrumentationRegistry.getArguments().getString("networkRunId")?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(directory, "${run?.plus("-").orEmpty()}$name.png"))
    }

    /** 将不含认证材料的报告写入私有目录，保留测试账号和通话编号。 */
    private fun saveReport() {
        // 完整通过与原登录态保留分别标记，测试数据有意不清除。
        File(context.filesDir, "native-local-call-network-report.json").writeText(report.put("callIds", calls).put("checks", checks).toString(2))
    }
}
