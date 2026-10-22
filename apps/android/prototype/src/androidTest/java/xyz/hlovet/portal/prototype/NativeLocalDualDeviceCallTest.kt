package xyz.hlovet.portal.prototype

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在两台真实 MainActivity 之间验收原生语音通话，覆盖信令、WebRTC 连接和通话控制。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalDualDeviceCallTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** 读取当前设备的加密会话，不跨设备传递任何认证材料。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: NativeSession
    private lateinit var peer: NativeSocialUser
    private lateinit var role: String
    private lateinit var conversationId: String
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 校验本地来源、角色和已有会话，避免通话专项误连生产或创建隐含账号。 */
    @Before fun prepare() {
        // 本用例只允许本地 API/门户和明确的 host/peer 参数，第三台设备不参与协作。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val args = InstrumentationRegistry.getArguments()
        val turnApiBaseUrl = requireNotNull(args.getString("dualTurnApiBaseUrl"))
        check(turnApiBaseUrl == "http://127.0.0.1:3002")
        role = requireNotNull(args.getString("dualRole"))
        check(role in listOf("host", "peer"))
        conversationId = requireNotNull(args.getString("dualConversationId"))
        check(conversationId.toIntOrNull() != null)
        session = requireNotNull(NativeSessionStore.load(context)) { "本地测试会话缺失，停止测试且不自动登录。" }
        // 从只配置临时 TURN 的本地 API 获取短效凭据，生产服务和正式配置不参与此测试。
        val iceServers = loadTestIceServers(turnApiBaseUrl, session.accessToken)
        val hasTurnServer = iceServers.any { server -> server.urls.any { url -> url.startsWith("turn:") || url.startsWith("turns:") } }
        report.put("iceServerCount", iceServers.size).put("turnConfigured", hasTurnServer).put("forceRelay", true)
        saveReport()
        // 必须取得可用的本地 TURN 服务，强制 relay 后通话成功才构成中继链路证据。
        check(iceServers.isNotEmpty() && hasTurnServer) { "本地测试 API 未提供 TURN 服务，停止本次中继验收。" }
        val peerUsername = requireNotNull(args.getString("dualPeerUsername"))
        peer = NativeFriendsApi.searchUsers(session.accessToken, peerUsername).single { it.user.username == peerUsername }.user
        check(peer.id != session.userId)
        val conversation = NativeFriendsApi.getOrCreateConversation(session.accessToken, peer.id)
        assertEquals(conversationId.toInt(), conversation.id)
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        // 将短效凭据只写入 Debug 应用私有偏好，并强制 relay 以排除点对点候选的误通过。
        NativeCallIceServerTestOverride.installForDebug(context, iceServers, forceRelay = true)
        report.put("role", role).put("userId", session.userId).put("username", session.username)
            .put("peerId", peer.id).put("conversationId", conversation.id)
        saveReport()
    }

    /** 关闭页面、恢复语言并保留会话与通话产生的系统消息记录。 */
    @After fun finish() {
        // 结束阶段只关闭本端页面；服务端挂断事件由产品页面真实发送，不直接调用测试 API。
        activity?.close()
        NativeCallIceServerTestOverride.clear(context)
        if (::session.isInitialized) {
            report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == session.userId)
            saveReport()
        }
        oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
    }

    /** 双端真实发起并接听语音通话，检查 WebRTC 活跃状态、静音切换及挂断同步。 */
    @Test fun pairedNativeVoiceCallConnectsAndEnds() {
        // 两端都先进入同一个私聊页面，确保来电监听已经建立后再由 host 发起。
        openConversation()
        record("chat-socket-ready-before-call")
        // 主端从真实聊天控件发起，并等待对端通过 UI 返回的活跃状态。
        if (role == "host") {
            waitFor(hasContentDescription("发起语音通话"))
            compose.onNodeWithContentDescription("发起语音通话").performClick()
            waitFor(hasText("等待接听"))
            record("native-host-starts-voice-call")
            waitForCallActive()
            record("native-host-receives-webrtc-active-state")
            waitFor(hasContentDescription("静音"))
            compose.onNodeWithContentDescription("静音").performClick()
            waitFor(hasContentDescription("打开麦克风"))
            compose.onNodeWithContentDescription("打开麦克风").performClick()
            record("native-host-mute-toggle-updates-control")
            waitFor(hasContentDescription("结束通话"))
            compose.onNodeWithContentDescription("结束通话").performClick()
            waitAbsent(hasContentDescription("结束通话"))
            record("native-host-ends-call-from-main-activity")
        } else {
            // 对端通过全局来电面板接听，随后等待主端挂断事件同步到本端。
            waitFor(hasContentDescription("接听通话"))
            record("native-peer-receives-incoming-call")
            compose.onNodeWithContentDescription("接听通话").performClick()
            record("native-peer-accepts-incoming-call")
            waitForCallActive()
            record("native-peer-answers-and-reaches-webrtc-active-state")
            waitFor(hasContentDescription("结束通话"))
            waitAbsent(hasContentDescription("结束通话"))
            record("native-peer-sees-synchronized-call-end")
        }
        report.put("completed", true)
        saveReport()
    }

    /** 打开真实聊天路由并等待 Socket.IO 连接及输入框准备完成。 */
    private fun openConversation() {
        // 每台设备独立重建 Activity，避免把上一次聊天页的状态当成通话连接。
        activity?.close()
        activity = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("${BuildConfig.PORTAL_BASE_URL}/messages?conversation=$conversationId")),
        )
        waitFor(hasContentDescription("聊天已连接"))
        waitFor(hasSetTextAction() and hasText("输入消息…"))
    }

    /** 使用当前设备会话向本地测试 API 请求 ICE 服务，返回短效 TURN 凭据且不写入报告。 */
    private fun loadTestIceServers(apiBaseUrl: String, accessToken: String): List<NativeIceServer> {
        // 只接受测试命令固定的 loopback API，避免误将会话令牌发送到生产或外部主机。
        check(apiBaseUrl == "http://127.0.0.1:3002")
        val connection = URL("$apiBaseUrl/social/calls/ice-servers").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        try {
            // 鉴权或服务异常直接失败，不回退到 STUN-only 导致误报为 TURN 测试通过。
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "本地 TURN 配置请求失败（HTTP $status）。" }

            // 保留服务端生成的过期用户名和凭据，不把密钥内容写入屏幕、日志或设备报告。
            val values = JSONObject(body).optJSONArray("iceServers") ?: return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    val urls = when (val raw = value.opt("urls")) {
                        is JSONArray -> buildList {
                            for (urlIndex in 0 until raw.length()) add(raw.optString(urlIndex))
                        }
                        else -> listOf(value.optString("urls"))
                    }.filter(String::isNotBlank)
                    if (urls.isNotEmpty()) {
                        add(NativeIceServer(
                            urls = urls,
                            username = value.optString("username").takeIf(String::isNotBlank),
                            credential = value.optString("credential").takeIf(String::isNotBlank),
                        ))
                    }
                }
            }
        } finally {
            // 包含临时凭据的 HTTP 响应完成解析后立即释放连接。
            connection.disconnect()
        }
    }

    /** 等待指定语义节点出现，避免网络和 Compose 调度差异被误判为通话失败。 */
    private fun waitFor(matcher: SemanticsMatcher) {
        // 使用用户实际可操作的合并语义树；最长等待 90 秒，不循环点击或伪造页面状态。
        compose.waitUntil(90_000) {
            compose.onAllNodes(matcher)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    /** 等待通话控制语义消失，确认挂断状态已经从页面清除而非只改变文案。 */
    private fun waitAbsent(matcher: SemanticsMatcher) {
        // 使用有限等待收敛异步 call:ended 事件，避免以固定 sleep 代替业务状态。
        compose.waitUntil(30_000) {
            compose.onAllNodes(matcher)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty()
        }
    }

    /** 在 WebRTC 活跃或可见错误到达时结束等待，避免 ICE 已失败仍空等完整超时。 */
    private fun waitForCallActive() {
        // 服务端媒体连接看门狗为 45 秒；等待稍长于看门狗，并将明确失败反馈作为立即失败条件。
        compose.waitUntil(60_000) {
            val active = compose.onAllNodes(hasText("通话中"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
            // 只识别通话组件自己的失败文案，避免把页面其它请求的“失败”提示误判为媒体失败。
            val failed = compose.onAllNodes(
                hasText("通话连接失败", substring = true) or
                    hasText("媒体连接失败", substring = true) or
                    hasText("ICE 媒体连接失败", substring = true),
            )
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
            if (failed) throw AssertionError("通话界面报告媒体或信令失败。")
            active
        }
    }

    /** 将已通过的阶段立即保存到设备报告，失败时保留最后一个真实检查点。 */
    private fun record(check: String) {
        // 报告只记录业务标识和阶段名称，不保存密码、令牌、ICE 凭据或媒体内容。
        checks.put(JSONObject().put("check", check).put("passed", true))
        saveReport()
    }

    /** 写入本设备私有报告，供宿主机在两端测试完成后核对。 */
    private fun saveReport() {
        // 使用应用私有目录，避免报告被系统备份或在设备间意外共享。
        File(context.filesDir, "native-local-dual-call-report.json").writeText(report.put("checks", checks).toString(2))
    }
}
