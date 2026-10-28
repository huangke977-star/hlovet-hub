package xyz.hlovet.portal.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实系统权限面板和本地 Socket，核对拒绝授权及旧来电授权不得接听新来电的边界。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalCallPermissionTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 只读取本任务应用与设备，不持有其它应用的账号或权限。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    /** 系统权限窗口不能用 Compose 回调替代，必须点击实际授权按钮。 */
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var activity: ActivityScenario<MainActivity>? = null
    private var callerSocket: NativeChatSocket? = null
    private var fixture: NativeSession? = null
    private lateinit var reader: NativeSession
    private var originalUserId = 0
    private var previousLocale = "zh-CN"
    private var activeCallId = 0
    private var conversationId = 0
    private var scenarioName = "not-started"
    private val accepted = AtomicReference<NativeCallSession?>()
    private val ended = AtomicReference<NativeCallSession?>()
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 每个专项在外部保存并撤销两项权限后独立运行，不在测试进程中撤权而导致宿主被系统杀死。 */
    @Before fun prepare() {
        // 严格限定来源、原账号和权限前置条件，不修改服务端策略或模拟系统授权结果。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.username.startsWith("native_restore_"))
        originalUserId = stored.userId
        clearUserDecisionFlags()
        assertFalse(granted(Manifest.permission.RECORD_AUDIO))
        assertFalse(granted(Manifest.permission.CAMERA))
        previousLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
        val policy = NativeAuthApi.registrationPolicy()
        check(policy.registrationOpen && !policy.emailVerificationEnabled && !policy.turnstileEnabled && !policy.loginTurnstileEnabled)
    }

    /** 只释放本轮窗口、Socket 和辅助登录；原账号、夹具、好友关系及通话系统消息均保留。 */
    @After fun finish() {
        // 即使断言失败也正常结束自己的来电，不以数据库更新伪造通话终态。
        if (activeCallId > 0) callerSocket?.endCall(activeCallId)
        try {
            // 仅在实际系统权限窗口尚未关闭时拒绝它，不能用返回键误关闭 MainActivity。
            repeat(2) { device.findObject(permissionButton("permission_deny_button"))?.click() }
            activity?.close()
        } finally {
            // 生命周期断言失败也恢复辅助资源、权限决定标志和非敏感报告。
            callerSocket?.close()
            fixture?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            clearUserDecisionFlags()
            report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == originalUserId)
            saveReport()
            compose.runOnUiThread { NativeI18n.applyLocale(previousLocale) }
        }
    }

    /** 发起视频时拒绝两项权限，界面必须提示且服务端不能新增通话或系统消息。 */
    @Test fun outgoingDenialDoesNotCreateCall() {
        // 独立创建好友会话，以真实空会话证明拒绝授权没有落库通话消息。
        scenarioName = "outgoing"
        prepareFixture()
        openConversation()
        val messagesBefore = NativeSocialApi.listMessages(reader.accessToken, conversationId).items.map { it.id }
        waitFor(hasContentDescription("发起视频通话"))
        compose.onNodeWithContentDescription("发起视频通话").performClick()
        // 两个权限组可能按不同顺序出现，均通过系统拒绝按钮处理而不假定文案语言。
        assertNotice("通话需要麦克风和摄像头权限。") {
            var denied = 0
            repeat(2) {
                val button = device.wait(Until.findObject(permissionButton("permission_deny_button")), 8_000)
                if (button != null) {
                    button.click()
                    denied += 1
                }
            }
            assertTrue("必须实际操作系统授权窗口", denied > 0)
        }
        assertFalse(granted(Manifest.permission.RECORD_AUDIO))
        assertFalse(granted(Manifest.permission.CAMERA))
        compose.onNodeWithTag("native-call-peer-name").assertDoesNotExist()
        assertEquals(messagesBefore, NativeSocialApi.listMessages(reader.accessToken, conversationId).items.map { it.id })
        capture("outgoing-permission-denied")
        record("system-denial-shows-notice-without-call-or-system-message")
        report.put("completed", true)
        saveReport()
    }

    /** 旧语音授权期间取消并换成视频来电，授权只能作废；新来电必须重新确认摄像头并明确接听。 */
    @Test fun staleGrantCannotAcceptReplacementIncomingCall() {
        // 首通语音拒绝后仍保留来电，允许用户重试或明确拒接，不替用户结束来电。
        scenarioName = "replacement"
        prepareFixture()
        openConversation()
        startIncoming("voice")
        report.put("cancelledVoiceCallId", activeCallId)
        compose.onNodeWithContentDescription("接听通话").performClick()
        assertNotice("通话需要麦克风权限。") { tapPermission("permission_deny_button") }
        compose.onNodeWithContentDescription("接听通话").assertExists()
        assertNull(accepted.get())
        capture("incoming-microphone-denied")
        record("incoming-microphone-denial-keeps-explicit-answer-choice")

        // 再次打开真实麦克风面板，在用户尚未授权时通过服务端取消旧来电并发起视频来电。
        compose.onNodeWithContentDescription("接听通话").performClick()
        assertNotNull(device.wait(Until.findObject(permissionButton("permission_allow_foreground_only_button")), 10_000))
        requireNotNull(callerSocket).endCall(activeCallId)
        compose.waitUntil(20_000) { ended.get()?.id == activeCallId && ended.get()?.status == "cancelled" }
        // 等旧原生面板真正退出，再让辅助主叫发送新通话，不直接注入宿主状态。
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-call-peer-name").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        activeCallId = 0
        startIncoming("video", waitForUi = false)
        report.put("replacementVideoCallId", activeCallId)
        tapPermission("permission_allow_foreground_only_button")
        compose.waitUntil(10_000) { granted(Manifest.permission.RECORD_AUDIO) }
        assertTrue(granted(Manifest.permission.RECORD_AUDIO))
        assertFalse(granted(Manifest.permission.CAMERA))
        // 旧授权如果误续接新通话，这里会出现 connecting/failed 或新面板消失，不能算作通过。
        waitFor(hasContentDescription("接听通话"))
        assertStillRinging()
        compose.onNodeWithContentDescription("接听通话").assertExists()
        assertNull(accepted.get())
        assertNull(ended.get())
        capture("replacement-awaits-own-consent")
        record("old-voice-grant-does-not-answer-new-video-invitation")

        // 新视频仍需本次用户明确接听；仅麦克风已授权不足以启动摄像头或发送 accepted。
        compose.onNodeWithContentDescription("接听通话").performClick()
        assertNotice("通话需要麦克风和摄像头权限。") { tapPermission("permission_deny_button") }
        compose.onNodeWithContentDescription("接听通话").assertExists()
        assertNull(accepted.get())
        record("partial-video-permission-denial-does-not-answer")
        compose.onNodeWithContentDescription("接听通话").performClick()
        tapPermission("permission_allow_foreground_only_button")
        compose.waitUntil(20_000) { accepted.get()?.id == activeCallId }
        assertTrue(granted(Manifest.permission.CAMERA))
        compose.onNodeWithContentDescription("接听通话").assertDoesNotExist()
        record("fresh-answer-with-all-permissions-sends-real-accepted-state")
        // 辅助主叫不提供媒体，故只验证接听信令及结束清理，不宣称真实视频已连通。
        requireNotNull(callerSocket).endCall(activeCallId)
        compose.waitUntil(20_000) { ended.get()?.id == activeCallId }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-call-peer-name").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        activeCallId = 0
        record("replacement-call-end-clears-native-panel")
        report.put("completed", true)
        saveReport()
    }

    /** 正常启动保留会话并注册独立辅助主叫；所有关系经公开本地 API 创建。 */
    private fun prepareFixture() {
        // MainActivity 刷新完成后才读取当前令牌，避免测试辅助 API 与宿主竞争 refresh。
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=74")))
        waitFor(hasContentDescription("聊天已连接"))
        reader = requireNotNull(NativeSessionStore.load(context))
        assertEquals(originalUserId, reader.userId)
        val marker = "native_cp_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val registration = NativeAuthApi.register(marker, "通话权限样例", "$marker@android-fixture.invalid", "Native_${UUID.randomUUID()}_9!", null, null)
        check(registration is NativeAuthResult.Authenticated)
        val caller = registration.session.also { fixture = it }
        report.put("readerId", reader.userId).put("fixtureId", caller.userId).put("fixtureUsername", marker)
        NativeFriendsApi.requestFriend(reader.accessToken, caller.userId, "原生通话权限验收")
        val request = NativeFriendsApi.listFriendships(caller.accessToken).incoming.single { it.user.id == reader.userId }
        NativeFriendsApi.respondFriendRequest(caller.accessToken, request.id, "accepted")
        conversationId = NativeFriendsApi.getOrCreateConversation(caller.accessToken, reader.userId).id
        report.put("conversationId", conversationId)
        val connected = AtomicBoolean(false)
        callerSocket = NativeChatSocket(caller.accessToken, {}, { _, _ -> }, {}, { connected.set(it) },
            onCallAccepted = { accepted.set(it) }, onCallEnded = { ended.set(it) }).also { it.connect() }
        compose.waitUntil(20_000) { connected.get() }
        record("real-fixture-friendship-and-socket-ready")
    }

    /** 通过真实深链打开 conversationId 对应聊天，保留原登录数据但不依赖前一个 ActivityScenario 的生命周期。 */
    private fun openConversation() {
        // 尚未发起任何通话，先关闭准备阶段页面并重新等到真实聊天连接，避免 Scenario 错配新 Intent 生命周期。
        activity?.close()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=$conversationId")))
        waitFor(hasContentDescription("聊天已连接"))
        waitFor(hasContentDescription("发起视频通话"))
    }

    /** 连续观察新来电仍未接听且未结束，不能在旧授权回调尚未处理的瞬间提前判定通过。 */
    private fun assertStillRinging() {
        // 本地信令回调至少跨越一秒真实时间；任意异步 accepted/ended 都应立即令专项失败。
        val deadline = SystemClock.uptimeMillis() + 1_000
        compose.waitUntil(5_000) {
            assertNull("未明确接听的新来电被异步接听", accepted.get())
            assertNull("仍在等待用户决定的新来电被异步结束", ended.get())
            SystemClock.uptimeMillis() >= deadline
        }
    }

    /** 辅助主叫通过 Socket 创建 type 来电；waitForUi 为 false 时只等服务端确认，避免系统权限窗口遮住原生树。 */
    private fun startIncoming(type: String, waitForUi: Boolean = true) {
        // 每通使用独立结果容器，旧通话结束事件不能冒充新邀请的状态。
        accepted.set(null)
        ended.set(null)
        val result = AtomicReference<NativeCallSession?>()
        val failure = AtomicReference<String?>()
        requireNotNull(callerSocket).startCall(conversationId, type) { ok, call, message ->
            if (ok) result.set(call) else failure.set(message ?: "call start failed")
        }
        compose.waitUntil(20_000) { result.get() != null || failure.get() != null }
        check(failure.get() == null) { "本地来电失败，请核对服务器保留的通话记录。" }
        activeCallId = requireNotNull(result.get()).id
        if (waitForUi) waitFor(hasContentDescription("接听通话"))
        saveReport()
    }

    /** 将系统按钮资源名转换为匹配器，兼容 AOSP 与 Google 权限控制器包名。 */
    private fun permissionButton(name: String): androidx.test.uiautomator.BySelector {
        // 控件资源 ID 稳定，权限控制器包名随系统发行版变化，不绑定可翻译按钮文字。
        return By.res(Pattern.compile(".*:id/$name"))
    }

    /** 真实点击 name 对应系统授权按钮，不修改权限管理器或回调结果。 */
    private fun tapPermission(name: String) {
        // 操作前保留实际系统窗口截图，失败时可以区分未弹窗与选择后的业务错误。
        val button = requireNotNull(device.wait(Until.findObject(permissionButton(name)), 12_000)) { "未出现预期系统权限按钮 $name" }
        capture("system-$name-${checks.length()}")
        button.click()
    }

    /** 监听 action 后实际发出的 message 系统 Toast；反馈不在 Compose 树内，不能用原生文本节点查找。 */
    private fun assertNotice(message: String, action: () -> Unit) {
        // 使用事件队列而非替换监听器，避免破坏 UiAutomator 点击系统窗口所需的事件订阅。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val event = automation.executeAndWaitForEvent({ action(); compose.waitForIdle() }, { candidate ->
            candidate.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                candidate.packageName?.toString() == context.packageName && candidate.text.any { it.toString() == message }
        }, 10_000)
        // 每次只接收本操作之后的反馈，且及时回收系统事件，旧 Toast 不能使下一次错误分支假通过。
        event.recycle()
    }

    /** 恢复本任务两项权限原有的无用户决定标志，兼容 Android 11 不提供 pm flags 子命令的限制。 */
    private fun clearUserDecisionFlags() {
        // 只借用测试进程的 shell 能力修改本包两个标志；不重置全设备权限、不清空应用数据。
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS", "android.permission.REVOKE_RUNTIME_PERMISSIONS")
        try {
            val updateFlags = context.packageManager.javaClass.getMethod("updatePermissionFlags", String::class.java,
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, UserHandle::class.java)
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                // 0x1/0x2 为 USER_SET/USER_FIXED；原设备只有敏感权限系统标志，必须原样保留其它位。
                updateFlags.invoke(context.packageManager, permission, context.packageName, 0x3, 0, Process.myUserHandle())
            }
        } finally {
            // 权限能力不可泄漏给后续产品交互；运行时 granted 状态由外部 finally 恢复原值。
            automation.dropShellPermissionIdentity()
        }
    }

    /** 返回 permission 当前的系统授予状态，不能用 ActivityResult 的空结果代替。 */
    private fun granted(permission: String): Boolean {
        // 直接查询目标应用运行时权限，不通过测试参数模拟已经授权。
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 等待 matcher 对应原生界面出现，所有异步 Socket 和窗口转换均有界。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 不对真实写入进行自动重试；超时保留夹具和当前检查点。
        compose.waitUntil(60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 保存已通过的 check，检查点不是独立测试数量。 */
    private fun record(check: String) {
        // 逐步落盘，不把最后失败的场景描述为完整通过。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 保存当前场景报告，包含可在本地网页核对的账号、会话和通话编号，不包含凭据。 */
    private fun saveReport() {
        // 报告仅进入本任务应用私有目录，不上传或加入 Git。
        File(context.filesDir, "native-local-call-permission-$scenarioName-report.json").writeText(report.put("checks", checks).toString(2))
    }

    /** 保存 name 对应的当前系统合成画面，便于核对真实授权窗口与通话选择。 */
    private fun capture(name: String) {
        // 系统权限面板不属于 Compose 树，使用设备截图保留完整可见状态。
        val directory = File(context.filesDir, "native-local-call-permission-$scenarioName-evidence").apply { mkdirs() }
        device.takeScreenshot(File(directory, "$name.png"))
    }
}
