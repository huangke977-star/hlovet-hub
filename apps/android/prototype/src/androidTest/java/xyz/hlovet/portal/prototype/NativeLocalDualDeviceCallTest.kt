package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglRenderer
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.SurfaceViewRenderer

/** 两台真实 MainActivity 配对验收语音/视频、实际媒体出帧、最小化及挂断，不使用替代信令或合成视频。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalDualDeviceCallTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 读取当前设备的加密会话，不跨设备传递任何认证材料。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: NativeSession
    private lateinit var peer: NativeSocialUser
    private lateinit var role: String
    private lateinit var conversationId: String
    private var activity: ActivityScenario<MainActivity>? = null
    private var oldLocale: String? = null
    private var storedRefreshTokenBeforeTest: String = ""
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
        conversationId = args.getString("dualConversationId").orEmpty()
        check(conversationId.isEmpty() || conversationId.toIntOrNull() != null)
        session = requireNotNull(NativeSessionStore.load(context)) { "本地测试会话缺失，停止测试且不自动登录。" }
        storedRefreshTokenBeforeTest = session.refreshToken
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
        report.put("role", role).put("userId", session.userId).put("username", session.username)
            .put("conversationId", conversationId.toIntOrNull())
        saveReport()
    }

    /** 关闭页面、恢复语言并保留会话与通话产生的系统消息记录。 */
    @After fun finish() {
        // 结束阶段只关闭本端页面；服务端挂断事件由产品页面真实发送，不直接调用测试 API。
        if (activity != null) screenshot("final")
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
        // 先确认真实账号和同一好友会话，再安装仅 Debug 生效的临时 TURN 配置。
        prepareCall()
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

    /** 两端真实语音连通后只切断主叫信令网络，双方清理；恢复后必须重新明确发起才能再次通话。 */
    @Test fun pairedNativeVoiceDisconnectsThenFreshCallConnects() {
        // 独立标记防止历史同步消息误使本轮提前挂断，媒体继续使用临时 TURN。
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("dualRunId"))
        check(run.matches(Regex("[A-Za-z0-9_]{1,24}")))
        val marker = "network_$run"
        report.put("marker", marker).put("mediaType", "voice").put("networkInterruptedRole", "host")
        prepareCall()
        sendUi("$marker $role-ready")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-ready")
        if (role == "host") startUiCall("voice")
        else {
            waitFor(hasContentDescription("接听通话"))
            compose.onNodeWithContentDescription("接听通话").performClick()
        }
        waitForCallActive()
        record("first-native-voice-call-has-real-webrtc-connection")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitFor(hasTestTag("native-call-mini"))
        sendUi("$marker $role-connected")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-connected")
        screenshot("network-first-active")

        // TCP 网关只绑定主叫模拟器的 reverse，另一台、API、数据库和电脑网络继续正常运行。
        if (role == "host") {
            val control = URL("http://127.0.0.1:3004/cut").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            control.requestMethod = "POST"
            control.connectTimeout = 2_000
            control.readTimeout = 2_000
            try {
                check(control.responseCode == 200)
                check(JSONObject(control.inputStream.bufferedReader().use { it.readText() }).getBoolean("blocked"))
            } finally {
                control.disconnect()
            }
            // 在网关恢复之前就应清除小窗，不能靠重连后的其它事件碰巧清理。
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-call-mini").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        } else waitAbsent(hasTestTag("native-call-mini"))
        waitAbsent(hasContentDescription("结束通话"))
        record(if (role == "host") "transport-loss-clears-host-mini-and-media" else "server-disconnect-ends-peer-call")
        screenshot("network-call-cleared")

        // 等待产品自身重连，不重启 Activity、不重建账号；旧通话必须保持结束。
        waitFor(hasContentDescription("聊天已连接"))
        if (role == "host") sendUi("$marker host-recovered")
        else awaitPeerMessage("$marker host-recovered")
        compose.onNodeWithTag("native-call-mini").assertDoesNotExist()
        sendUi("$marker $role-recovered")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-recovered")
        record("chat-reconnects-without-restoring-ended-call")

        // 第二通必须由主叫再次点击发起、被叫再次点击接听，并真实完成新的 WebRTC 连接。
        if (role == "host") startUiCall("voice")
        else {
            waitFor(hasContentDescription("接听通话"))
            compose.onNodeWithContentDescription("接听通话").performClick()
        }
        waitForCallActive()
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitFor(hasTestTag("native-call-mini"))
        sendUi("$marker $role-second-active")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-second-active")
        record("explicit-fresh-call-connects-after-network-recovery")
        screenshot("network-second-active")
        if (role == "host") compose.onNodeWithContentDescription("结束通话").performClick()
        waitAbsent(hasTestTag("native-call-mini"))
        record("fresh-call-hangup-synchronizes-both-devices")
        report.put("completed", true)
        saveReport()
    }

    /** 在双端语音中只暂停临时媒体中继，验证短暂恢复、持续故障收尾及明确重新拨号。 */
    @Test fun pairedNativeVoiceMediaOutageKeepsChatAndAllowsFreshCall() {
        // 复用真实语音控件及本地账号，不借助辅助 Socket 冒充另一原生设备。
        verifyMediaOnlyOutage("voice")
    }

    /** 在双端视频中只暂停临时媒体中继，并通过恢复后的真实本地/远端帧证明媒体重新可用。 */
    @Test fun pairedNativeVideoMediaOutageKeepsChatAndAllowsFreshCall() {
        // 视频在中断前、短暂恢复后和新呼叫中均核对实际 Surface 帧及像素。
        verifyMediaOnlyOutage("video")
    }

    /** 按 type 验证独立媒体故障；仅主叫控制本轮中继，所有聊天与通话动作仍通过真实页面执行。 */
    private fun verifyMediaOnlyOutage(type: String) {
        // 严格限制媒体类型与唯一轮次，历史同步记录不能触发本轮提前恢复或结束。
        check(type in listOf("voice", "video"))
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("dualRunId"))
        check(run.matches(Regex("[A-Za-z0-9_]{1,24}")))
        val marker = "media_${run}_$type"
        report.put("marker", marker).put("mediaType", type).put("outageScope", "temporary-turn-only")
        prepareCall()
        sendUi("$marker $role-ready")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-ready")
        if (role == "host") startUiCall(type) else {
            waitFor(hasContentDescription("接听通话"))
            compose.onNodeWithContentDescription("接听通话").performClick()
        }
        waitForCallActive()
        if (type == "video") assertVideoFrames("$marker-initial")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        sendUi("$marker $role-active")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-active")
        record("initial-native-media-connected-before-isolated-outage")

        // 短暂停止的仅是专用 TURN 容器，暂停期间真实 UI 消息仍应双向到达。
        if (role == "host") {
            setMediaPaused(true)
            sendUi("$marker host-short-cut")
        } else awaitPeerMessage("$marker host-short-cut")
        compose.onNodeWithContentDescription("聊天已连接").assertExists()
        compose.onNodeWithTag("native-call-mini").assertExists()
        sendUi("$marker $role-chat-while-paused")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-chat-while-paused")
        if (role == "host") {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            compose.waitUntil(8_000) { SystemClock.elapsedRealtime() >= deadline }
            setMediaPaused(false)
            sendUi("$marker host-short-restored")
        } else awaitPeerMessage("$marker host-short-restored")
        compose.onNodeWithTag("native-call-mini").performClick()
        waitForCallActive()
        if (type == "video") assertVideoFrames("$marker-short-restored")
        record("short-media-pause-keeps-chat-and-original-call-recovers")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        sendUi("$marker $role-short-complete")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-short-complete")

        // 持续媒体中断时给真实 WebRTC 故障检测留出上限，不直接注入 FAILED 或清理产品面板。
        if (role == "host") {
            setMediaPaused(true)
            sendUi("$marker host-long-cut")
        } else awaitPeerMessage("$marker host-long-cut")
        val interruptedAt = SystemClock.elapsedRealtime()
        compose.waitUntil(120_000) {
            compose.onAllNodesWithTag("native-call-mini").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        report.put("sustainedOutageUntilClearedMilliseconds", SystemClock.elapsedRealtime() - interruptedAt)
        compose.onNodeWithContentDescription("结束通话").assertDoesNotExist()
        compose.onNodeWithContentDescription("聊天已连接").assertExists()
        sendUi("$marker $role-media-cleared-chat-online")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-media-cleared-chat-online")
        record("sustained-media-loss-clears-both-call-panels-with-chat-online")
        screenshot("$marker-cleared")

        // 恢复中继不能自动拨回旧电话，双方先明确证明聊天继续正常及面板没有复活。
        if (role == "host") {
            setMediaPaused(false)
            sendUi("$marker host-long-restored")
        } else awaitPeerMessage("$marker host-long-restored")
        compose.onNodeWithTag("native-call-mini").assertDoesNotExist()
        compose.onNodeWithContentDescription("接听通话").assertDoesNotExist()
        sendUi("$marker $role-ready-for-fresh")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-ready-for-fresh")
        record("media-restoration-does-not-resurrect-ended-call")

        // 新通话必须再次点击发起和接听，视频还必须再次从两个真实轨道取得后续帧。
        if (role == "host") startUiCall(type) else {
            waitFor(hasContentDescription("接听通话"))
            compose.onNodeWithContentDescription("接听通话").performClick()
        }
        waitForCallActive()
        if (type == "video") assertVideoFrames("$marker-fresh")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        sendUi("$marker $role-fresh-active")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-fresh-active")
        record("explicit-fresh-native-call-connects-after-media-restoration")
        if (role == "host") compose.onNodeWithContentDescription("结束通话").performClick()
        waitAbsent(hasTestTag("native-call-mini"))
        record("fresh-call-hangup-synchronizes-both-devices")
        report.put("completed", true)
        saveReport()
    }

    /** 只暂停或恢复本轮临时 TURN；API 签发及聊天服务不受影响，返回后核对夹具实际暂停状态。 */
    private fun setMediaPaused(paused: Boolean) {
        // 控制地址固定为本机签发夹具，不允许把危险控制请求发送到生产或任意外部地址。
        check(role == "host")
        val action = if (paused) "cut" else "restore"
        val connection = URL("http://127.0.0.1:3002/media/$action").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        try {
            // 只有 Docker 已实际完成状态切换才继续业务断言，控制失败直接保留本轮失败证据。
            check(connection.responseCode == 200) { "本地媒体控制夹具没有完成操作。" }
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.getBoolean("paused") == paused)
        } finally {
            // 不复用控制 HTTP 连接，避免暂停媒体时与 API 请求共享半关闭连接。
            connection.disconnect()
        }
    }

    /** 双端真实视频经 TURN 出帧，收起保持计时/静音；按明确的单摄/双摄模式验收实际设备能力及挂断同步。 */
    @Test fun pairedNativeVideoCallMinimizesAndEnds() {
        // 使用唯一运行标记同步两端准备状态，旧聊天记录不能让本次提前发起或结束。
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("dualRunId"))
        check(run.matches(Regex("[A-Za-z0-9_]{1,24}")))
        val marker = "video_$run"
        report.put("marker", marker).put("mediaType", "video")
        val cameraMode = InstrumentationRegistry.getArguments().getString("dualCameraMode") ?: "dual"
        check(cameraMode in listOf("single", "dual"))
        val cameras = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context).deviceNames else Camera1Enumerator(true).deviceNames
        val cameraCount = cameras.distinct().size
        check(if (cameraMode == "single") cameraCount == 1 else cameraCount > 1) { "实际相机数量与验收模式不符。" }
        report.put("cameraMode", cameraMode).put("cameraCount", cameraCount)
        prepareCall()
        sendUi("$marker $role-ready")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-ready")
        if (role == "host") {
            startUiCall("video")
            record("native-host-starts-video-call")
        } else {
            waitFor(hasContentDescription("接听通话"))
            screenshot("incoming")
            compose.onNodeWithContentDescription("接听通话").performClick()
            record("native-peer-accepts-video-call")
        }
        waitForCallActive()
        assertVideoFrames("connected")
        record("native-video-active-with-local-and-remote-camera-frames")

        // 两次销毁/重建展示 Surface，媒体会话保持原样；真实输入/发送证明小窗不挡底层操作。
        repeat(2) { cycle ->
            compose.onNodeWithContentDescription("静音").performClick()
            waitFor(hasContentDescription("打开麦克风"))
            val before = durationSeconds()
            compose.onNodeWithContentDescription("最小化通话").performClick()
            waitFor(hasTestTag("native-call-mini"))
            sendUi("$marker $role-minimized-$cycle")
            // 小窗内先真实走过下一秒，再核对恢复后的秒数，排除初始零秒掩盖计时重置。
            compose.waitUntil(5_000) { durationSeconds() > before }
            val minimized = durationSeconds()
            screenshot("minimized-$cycle")
            compose.onNodeWithTag("native-call-mini").performClick()
            waitFor(hasContentDescription("打开麦克风"))
            val restored = durationSeconds()
            check(restored >= minimized) { "恢复通话后计时倒退。" }
            report.put("duration-cycle-$cycle", JSONObject().put("before", before).put("minimized", minimized).put("restored", restored))
            assertVideoFrames("restored-$cycle")
            compose.onNodeWithContentDescription("打开麦克风").performClick()
            waitFor(hasContentDescription("静音"))
            record("video-minimize-message-restore-timer-and-mute-cycle-$cycle")
        }

        // 摄像头开关由真实控制按钮执行，单摄不能切换但不得影响通话；双摄必须真实切换后继续出帧。
        compose.onNodeWithContentDescription("关闭摄像头").performClick()
        waitFor(hasContentDescription("打开摄像头"))
        compose.onNodeWithContentDescription("打开摄像头").performClick()
        waitFor(hasContentDescription("关闭摄像头"))
        val switch = compose.onNodeWithContentDescription("切换摄像头")
        if (cameraMode == "single") {
            switch.assertIsNotEnabled()
            assertVideoFrames("single-camera-preserved")
            record("single-camera-switch-disabled-and-video-preserved")
        } else {
            switch.assertIsEnabled().performClick()
            assertVideoFrames("camera-switched")
            record("camera-toggle-and-switch-preserve-real-video-frames")
        }

        // 两端完成全部出帧检查后才进入收尾；使用真实已持久化消息同步，避免主端过早挂断。
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitFor(hasTestTag("native-call-mini"))
        sendUi("$marker $role-completed-media-checks")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-completed-media-checks")
        if (role == "host") compose.onNodeWithContentDescription("结束通话").performClick()
        waitAbsent(hasContentDescription("结束通话"))
        waitAbsent(hasTestTag("native-call-mini"))
        record(if (role == "host") "native-host-hangs-up-from-mini" else "native-peer-observes-synchronized-video-end")
        report.put("completed", true)
        saveReport()
    }

    /** 双端真实视频跨过宿主八分钟自动令牌刷新，确认原通话、计时、远端帧和聊天继续有效。 */
    @Test fun pairedNativeVideoSurvivesAutomaticSessionRefresh() {
        // 只使用产品自己的自动刷新定时器，不注入刷新事件、不写临时短效 JWT 或替换媒体。
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("dualRunId"))
        check(run.matches(Regex("[A-Za-z0-9_]{1,24}")))
        val marker = "refresh_$run"
        report.put("marker", marker).put("mediaType", "video").put("automaticRefresh", true)
        prepareCall()
        // 先确认启动恢复已完成，不能把启动阶段的刷新误认为通话持续八分钟后的定时刷新。
        compose.waitUntil(30_000) { NativeSessionStore.load(context)?.refreshToken != storedRefreshTokenBeforeTest }
        val original = requireNotNull(NativeSessionStore.load(context))
        sendUi("$marker $role-ready")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-ready")
        if (role == "host") startUiCall("video") else {
            waitFor(hasContentDescription("接听通话"))
            compose.onNodeWithContentDescription("接听通话").performClick()
        }
        waitForCallActive()
        assertVideoFrames("refresh-before")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitFor(hasTestTag("native-call-mini"))
        val started = System.nanoTime()
        val testClockStarted = compose.mainClock.currentTime
        val deadline = started + TimeUnit.MINUTES.toNanos(10)
        var previousDuration = durationSeconds()
        var nextProgressAt = 60L
        record("real-video-active-before-automatic-refresh")

        // 每秒检查真实小窗和单调计时，刷新前的断线也应立即失败，不能等待后重新建立一通冒充连续。
        while (true) {
            compose.onNodeWithTag("native-call-mini").assertIsDisplayed()
            val elapsed = durationSeconds()
            check(elapsed >= previousDuration) { "自动刷新等待期间通话计时倒退。" }
            previousDuration = elapsed
            val stored = requireNotNull(NativeSessionStore.load(context))
            check(stored.userId == original.userId)
            if (stored.refreshToken != original.refreshToken) {
                check(stored.accessToken != original.accessToken) { "自动刷新未产生新访问令牌。" }
                session = stored
                break
            }
            // 每分钟保存无敏感信息的实际计时，便于外部观察测试仍在推进，不修改产品定时器。
            val waited = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            if (waited >= nextProgressAt) {
                check(elapsed >= waited - 2) { "通话计时未随真实时间推进。" }
                report.put("waitingSeconds", waited).put("waitingDuration", elapsed)
                    .put("testClockElapsedMillis", compose.mainClock.currentTime - testClockStarted)
                saveReport()
                nextProgressAt += 60
            }
            check(System.nanoTime() < deadline) { "产品自动刷新未在十分钟内发生。" }
            Thread.sleep(1_000)
            // Compose 测试调度器不会随 Thread.sleep 推进；只补齐实际经过的时间，不提前触发八分钟刷新。
            val targetClock = testClockStarted + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val missingMillis = targetClock - compose.mainClock.currentTime
            if (missingMillis > 0) compose.mainClock.advanceTimeBy(missingMillis)
            compose.waitForIdle()
        }
        val waited = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        check(waited >= 400) { "刷新过早，不能当作八分钟自动刷新验收。" }
        check(previousDuration >= waited - 2) { "刷新后的计时未覆盖本次真实等待。" }
        report.put("refreshWaitSeconds", waited)
            .put("durationAfterRefresh", previousDuration).put("accessTokenChanged", true).put("refreshTokenChanged", true)

        // 两台设备都实际完成刷新后继续通过原页面发消息，再恢复同一通电话检查新到达的远端帧。
        sendUi("$marker $role-refreshed")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-refreshed")
        compose.onNodeWithTag("native-call-mini").assertIsDisplayed().performClick()
        waitForCallActive()
        check(durationSeconds() >= previousDuration)
        assertVideoFrames("refresh-after")
        record("both-automatic-refreshes-preserve-original-video-and-chat")
        compose.onNodeWithContentDescription("最小化通话").performClick()
        sendUi("$marker $role-media-verified")
        awaitPeerMessage("$marker ${if (role == "host") "peer" else "host"}-media-verified")
        if (role == "host") compose.onNodeWithContentDescription("结束通话").performClick()
        waitAbsent(hasTestTag("native-call-mini"))
        record("original-call-ends-normally-after-both-refreshes")
        report.put("completed", true)
        saveReport()
    }

    /** 准备现有本地好友会话和临时 TURN；没有显式会话编号时仅通过正常业务接口准备关系。 */
    private fun prepareCall() {
        // 无固定会话时先刷新本端会话再准备好友夹具，不把准备关系当作原生 UI 好友验收。
        val args = InstrumentationRegistry.getArguments()
        val peerUsername = requireNotNull(args.getString("dualPeerUsername"))
        if (conversationId.isEmpty()) {
            session = NativeAuthApi.refresh(session.refreshToken)
            check(NativeSessionStore.save(context, session))
            peer = NativeFriendsApi.searchUsers(session.accessToken, peerUsername).single { it.user.username == peerUsername }.user
            check(peer.id != session.userId)
            if (role == "host" && NativeFriendsApi.listFriendships(session.accessToken).friends.none { it.user.id == peer.id }) {
                NativeFriendsApi.requestFriend(session.accessToken, peer.id, "本地双端视频验收")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            while (true) {
                val relations = NativeFriendsApi.listFriendships(session.accessToken)
                if (relations.friends.any { it.user.id == peer.id }) break
                if (role == "peer") relations.incoming.firstOrNull { it.user.id == peer.id }?.let {
                    NativeFriendsApi.respondFriendRequest(session.accessToken, it.id, "accepted")
                }
                check(System.nanoTime() < deadline) { "两端好友夹具未能在时限内准备完成。" }
                Thread.sleep(700)
            }
            conversationId = NativeFriendsApi.getOrCreateConversation(session.accessToken, peer.id).id.toString()
        }
        // 以 MainActivity 正常启动后的加密会话为准，测试不并发轮换应用正在使用的令牌。
        openConversation()
        session = requireNotNull(NativeSessionStore.load(context))
        report.put("sessionRefreshed", session.refreshToken != storedRefreshTokenBeforeTest)
        peer = NativeFriendsApi.searchUsers(session.accessToken, peerUsername).single { it.user.username == peerUsername }.user
        assertEquals(conversationId.toInt(), NativeFriendsApi.getOrCreateConversation(session.accessToken, peer.id).id)
        val iceServers = loadTestIceServers(requireNotNull(args.getString("dualTurnApiBaseUrl")), session.accessToken)
        val hasTurn = iceServers.any { it.urls.any { url -> url.startsWith("turn:") || url.startsWith("turns:") } }
        check(hasTurn) { "本地测试签发服务未提供 TURN 配置。" }
        NativeCallIceServerTestOverride.installForDebug(context, iceServers, forceRelay = true)
        report.put("conversationId", conversationId.toInt()).put("peerId", peer.id)
            .put("iceServerCount", iceServers.size).put("turnConfigured", hasTurn).put("forceRelay", true)
        record("chat-socket-ready-before-call")
    }

    /** 等待 type 对应的实际通话入口可用后点击；聊天 Socket 就绪不代表全局通话 Socket 也已重连。 */
    private fun startUiCall(type: String) {
        // 等待用户真正能操作的控件，不能用固定延迟或聊天消息成功代替通话连接就绪。
        val action = hasContentDescription(if (type == "video") "发起视频通话" else "发起语音通话") and isEnabled()
        waitFor(action)
        compose.onNode(action).performClick()
    }

    /** 在小窗或普通聊天页用实际输入框发送 body，并等待编辑框清空及真实服务端回显。 */
    private fun sendUi(body: String) {
        // 发送动作走产品控件，不用测试 Socket 或 API 代发消息。
        val editor = hasSetTextAction() and hasText("输入消息…")
        waitFor(editor)
        compose.onNode(editor).performTextReplacement(body)
        val send = hasContentDescription("发送") and hasClickAction() and isEnabled()
        waitFor(send)
        compose.onNode(send).performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitFor(editor)
        awaitPeerMessage(body)
    }

    /** 只读轮询当前会话中的 body，作为双设备阶段屏障；不创建消息或泄露令牌。 */
    private fun awaitPeerMessage(body: String) {
        // 每次最多读取一页且间隔 700ms；唯一运行标记可排除此前验收记录。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (NativeSocialApi.listMessages(session.accessToken, conversationId.toInt()).items.none { it.body == body }) {
            check(System.nanoTime() < deadline) { "等待对端真实消息超时：$body" }
            Thread.sleep(700)
        }
    }

    /** 返回当前真实通话计时秒数；无计时或非法显示直接失败，不以固定等待代替连续性检查。 */
    private fun durationSeconds(): Int {
        // 使用未合并语义树取得时长，避免卡片合并名称后把整行当作计时字符串。
        val node = compose.onNodeWithTag("native-call-duration", useUnmergedTree = true).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].single().text
        val parts = text.split(':').map { it.toInt() }
        check(parts.size == 2)
        return parts[0] * 60 + parts[1]
    }

    /** 检查实际大/小 Surface 连续收到至少三帧，像素非空，并保留 name 对应的画面证据。 */
    private fun assertVideoFrames(name: String) {
        // 从真实全局窗口找渲染器，远端轨道只能来自产品 PeerConnection，不注入任何合成帧。
        var renderers = emptyList<SurfaceViewRenderer>()
        compose.waitUntil(20_000) {
            compose.runOnUiThread { renderers = WindowInspector.getGlobalWindowViews().flatMap(::findRenderers).filter { it.isShown && it.width > 0 && it.holder.surface.isValid } }
            renderers.size == 2
        }
        val frames = renderers.map { AtomicInteger() }
        val listeners = renderers.mapIndexed { index, _ -> EglRenderer.FrameListener { bitmap -> frames[index].incrementAndGet(); bitmap.recycle() } }
        try {
            // WebRTC 的帧监听是一次性的；逐次重新注册，保证每个 Surface 真正交付三张后续帧。
            for (sample in 1..3) {
                compose.runOnUiThread { renderers.forEachIndexed { index, renderer -> renderer.addFrameListener(listeners[index], .1f) } }
                compose.waitUntil(20_000) { frames.all { it.get() >= sample } }
            }
            renderers.forEachIndexed { index, renderer ->
                var captured: Bitmap? = null
                try {
                    // 切换相机或重新开启视频时可先到达禁用期间的黑帧，必须等到真实非空画面恢复。
                    compose.waitUntil(15_000) {
                        captured?.recycle()
                        val bitmap = copyFrame(renderer).also { captured = it }
                        val colors = mutableSetOf<Int>()
                        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 40)) {
                            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 40)) colors.add(bitmap.getPixel(x, y))
                        }
                        colors.size > 8 && colors.any { Color.red(it) + Color.green(it) + Color.blue(it) > 90 }
                    }
                } finally {
                    // 无论成功或超时，都保留最后读取的实际 Surface，失败黑帧不能被后续整屏覆盖。
                    captured?.let { bitmap ->
                        val directory = File(context.filesDir, "native-local-dual-video-evidence").apply { mkdirs() }
                        File(directory, "$name-$index-surface.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
            screenshot(name)
        } finally {
            // 仅移除测试监听器，不移除产品的轨道 sink、不关闭摄像头或媒体会话。
            compose.runOnUiThread { renderers.forEachIndexed { index, renderer -> renderer.removeFrameListener(listeners[index]) } }
            report.put("videoFrames-$name", JSONArray(frames.map { it.get() }))
            saveReport()
        }
    }

    /** 遍历 root 的原生子视图，返回全部真实 Surface 渲染器，空窗口返回空列表。 */
    private fun findRenderers(root: View): List<SurfaceViewRenderer> {
        // 遍历只用于设备取证，不修改原生 View 层级或媒体归属。
        if (root is SurfaceViewRenderer) return listOf(root)
        return if (root is ViewGroup) (0 until root.childCount).flatMap { findRenderers(root.getChildAt(it)) } else emptyList()
    }

    /** 从 renderer 的当前 Surface 复制实际相机帧，回调失败或无有效帧时停止验收。 */
    private fun copyFrame(renderer: SurfaceViewRenderer): Bitmap {
        // PixelCopy 回调在主线程完成，测试线程有限等待，不阻塞产品媒体线程。
        val bitmap = Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(renderer, bitmap, { result -> status = result; done.countDown() }, Handler(Looper.getMainLooper()))
        if (!done.await(5, TimeUnit.SECONDS) || status != PixelCopy.SUCCESS) {
            bitmap.recycle()
            error("真实视频 Surface 取证失败。")
        }
        return bitmap
    }

    /** 保存 name 对应的真实整屏，成功/失败均可复核通话面板和底层聊天是否可见。 */
    private fun screenshot(name: String) {
        // 先等 Compose 完成布局再截系统画面，避免 Android 11 在断线卸载面板时重入测量。
        compose.waitForIdle()
        val directory = File(context.filesDir, "native-local-dual-video-evidence").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
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
