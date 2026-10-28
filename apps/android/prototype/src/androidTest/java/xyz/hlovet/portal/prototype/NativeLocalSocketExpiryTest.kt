package xyz.hlovet.portal.prototype

import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.socket.client.Socket
import io.socket.thread.EventThread
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 等待真实 API 令牌到期，验证服务端提示、原连接续期及未完成刷新的宽限期关闭，不加速产品时钟。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSocketExpiryTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    /** 读取本任务应用上下文，所有会话及证据只属于当前本地 API 来源。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cooperative: NativeChatSocket? = null
    private var unattended: NativeChatSocket? = null
    private var readerId = 0
    private var startedAt = 0L
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)
        .put("run", InstrumentationRegistry.getArguments().getString("expiryRunId") ?: "manual")

    /** 关闭本项两个 Socket 和刷新协程，保留原账号、加密会话及实际发送的消息。 */
    @After fun finish() {
        // 清理与普通产品退出采用相同生命周期，不登出或删除已有读者会话。
        cooperative?.close()
        unattended?.close()
        barrier()
        scope.cancel()
        report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == readerId)
        saveReport()
    }

    /** 正常等待原 JWT 到期，续期成功保持同一连接，未响应续期的连接在服务端宽限期后被关闭。 */
    @Test fun realExpiryPromptRenewsOriginalSocketAndClosesUnattendedSocket() {
        // 仅在精确本地来源运行；JWT 必须来自正常刷新接口，不自行签发短令牌或修改服务端寿命。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        saveReport()
        val original = runBlocking { NativeSessionCoordinator.refresh(context) }
        readerId = original.userId
        startedAt = SystemClock.elapsedRealtime()
        val claims = JSONObject(String(Base64.decode(original.accessToken.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        val expiresAt = claims.getLong("exp") * 1_000
        val remaining = expiresAt - System.currentTimeMillis()
        check(remaining in 60_000..1_800_000) { "真实令牌剩余寿命不在本项允许的 1 至 30 分钟范围。" }
        val conversationId = NativeSocialApi.listConversations(original.accessToken).first { it.kind != "group" }.id
        report.put("readerId", readerId).put("conversationId", conversationId)
            .put("originalLifetimeSeconds", claims.getLong("exp") - claims.getLong("iat"))
            .put("initialRemainingSeconds", remaining / 1_000)
        saveReport()

        // 两个独立原生适配器使用同一有效会话：一条正常刷新，一条故意不完成宿主回调以检验服务端兜底。
        val ready = AtomicInteger()
        val disconnected = AtomicInteger()
        val prompts = AtomicInteger()
        val refreshed = AtomicBoolean(false)
        val errors = AtomicInteger()
        val peerReady = AtomicInteger()
        val peerDisconnected = AtomicInteger()
        val peerPrompts = AtomicInteger()
        val peerPromptAt = AtomicLong()
        val peerClosedAt = AtomicLong()
        val callbacksOnMain = AtomicBoolean(true)
        cooperative = NativeChatSocket(original.accessToken, {}, { _, _ -> }, { errors.incrementAndGet() }, {
            if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false)
            if (it) ready.incrementAndGet() else disconnected.incrementAndGet()
        }, onReauthenticate = { complete ->
            if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false)
            prompts.incrementAndGet()
            scope.launch {
                val next = runCatching { NativeSessionCoordinator.refresh(context) }.getOrNull()
                refreshed.set(next != null && next.accessToken != original.accessToken && next.refreshToken != original.refreshToken)
                complete(next?.accessToken)
            }
        }).also { it.connect() }
        unattended = NativeChatSocket(original.accessToken, {}, { _, _ -> }, {}, {
            if (it) peerReady.incrementAndGet() else {
                peerDisconnected.incrementAndGet()
                peerClosedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
            }
        }, onReauthenticate = {
            // 不调用完成回调，而非返回空值让客户端主动断开；只有服务端实际宽限期能关闭这条连接。
            peerPrompts.incrementAndGet()
            peerPromptAt.compareAndSet(0L, SystemClock.elapsedRealtime())
        }).also { it.connect() }
        await("初次真实认证未完成", 20_000) { ready.get() == 1 && peerReady.get() == 1 }
        val initialSocket = socket(requireNotNull(cooperative))
        val initialId = initialSocket.id()
        record("two-native-sockets-ready-with-real-api-issued-token")

        // 以服务端真实事件和单调时钟等待，不推进 Compose 虚拟时钟或注入 reauthenticate 事件。
        await("未收到服务端真实到期提示", remaining + 45_000) {
            check(disconnected.get() == 0 && errors.get() == 0) { "等待真实到期时原连接提前断开或出错。" }
            prompts.get() == 1 && peerPrompts.get() == 1
        }
        val promptElapsed = SystemClock.elapsedRealtime() - startedAt
        check(promptElapsed >= remaining - 40_000) { "到期提示早于真实令牌预计时点。" }
        report.put("promptElapsedSeconds", promptElapsed / 1_000)
        record("real-server-expiry-prompt-arrives-near-original-expiration")
        await("真实刷新没有返回不同令牌", 20_000) { refreshed.get() }
        await("原 Socket 没有完成真实续期 ACK", 20_000) { authenticatedTokenMatches(requireNotNull(cooperative)) }
        assertSame(initialSocket, socket(requireNotNull(cooperative)))
        assertTrue(initialId == initialSocket.id())
        assertEquals(1, ready.get())
        assertEquals(0, disconnected.get())
        record("normal-refresh-reauthenticates-the-original-socket-without-disconnect")

        // 等待服务端实际关闭未完成刷新的连接，并核对接近 30 秒宽限期而非客户端立即关闭。
        await("未完成刷新的旧连接没有被服务端关闭", 45_000) { peerDisconnected.get() == 1 }
        val grace = peerClosedAt.get() - peerPromptAt.get()
        check(grace in 28_000..40_000) { "实际关闭时点不符合服务端 30 秒认证宽限期。" }
        assertFalse(socket(requireNotNull(unattended)).connected())
        report.put("unattendedGraceMilliseconds", grace)
        record("server-closes-unattended-socket-after-real-thirty-second-grace")

        // 原访问令牌实际过期后必须得到 HTTP 401；续期后的原连接仍可完成真实已读和消息持久化。
        await("原 JWT 尚未达到实际过期时点", 10_000) { System.currentTimeMillis() > expiresAt + 1_000 }
        val expiredFailure = runCatching { NativeSocialApi.listConversations(original.accessToken) }.exceptionOrNull()
        check(expiredFailure is NativeSocialHttpException && expiredFailure.statusCode == 401) { "原访问令牌实际过期后没有被接口拒绝。" }
        val read = AtomicBoolean(false)
        requireNotNull(cooperative).mutate("chat:read", JSONObject().put("conversationId", conversationId)) { ok, _ -> read.set(ok) }
        await("续期后的已读 ACK 失败", 20_000) { read.get() }
        val marker = "native_socket_expiry_" + UUID.randomUUID().toString().take(8)
        val sent = AtomicBoolean(false)
        requireNotNull(cooperative).send(conversationId, marker) { ok, _ -> sent.set(ok) }
        await("续期后的消息发送失败", 20_000) { sent.get() }
        val current = requireNotNull(NativeSessionStore.load(context))
        val persisted = NativeSocialApi.searchMessages(current.accessToken, conversationId, marker).items.single { it.body == marker }
        report.put("messageId", persisted.id).put("messageMarker", marker).put("originalExpiredHttpStatus", 401)
        assertEquals(readerId, current.userId)
        assertTrue(callbacksOnMain.get())
        assertEquals(1, prompts.get())
        assertEquals(0, errors.get())
        assertEquals(0, disconnected.get())
        record("expired-jwt-is-rejected-while-renewed-original-socket-persists-message")
        report.put("completed", true)
        saveReport()
    }

    /** 只读取得 adapter 的 Socket，用于比较对象和连接编号，不反射改写产品状态。 */
    private fun socket(adapter: NativeChatSocket): Socket {
        // 屏障保证此前认证和连接操作已经完成，再读取真实产品对象。
        barrier()
        return requireNotNull(NativeChatSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(adapter) as? Socket)
    }

    /** 返回 adapter 最近成功认证的令牌是否与当前保存会话一致，不在断言中输出任何令牌。 */
    private fun authenticatedTokenMatches(adapter: NativeChatSocket): Boolean {
        // 读取的是服务端成功 ACK 后的产品字段，不以保存会话成功冒充 Socket 续期成功。
        barrier()
        val token = NativeChatSocket::class.java.getDeclaredField("authenticatedToken").apply { isAccessible = true }.get(adapter) as? String
        return token != null && token == NativeSessionStore.load(context)?.accessToken
    }

    /** 在五秒上限内冲刷 Socket.IO 事件队列，不修改产品认证或时钟。 */
    private fun barrier() {
        // 有界顺序屏障让生命周期异常直接失败，而不是无限等待隐藏卡死。
        val latch = CountDownLatch(1)
        EventThread.exec { latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS))
    }

    /** 在 timeoutMilliseconds 内等待 condition；超时显示业务 message，周期保存脱敏进度。 */
    private fun await(message: String, timeoutMilliseconds: Long, condition: () -> Boolean) {
        // 单调时钟只用于等待实际经过的时间，禁止提前推进认证寿命或服务端宽限期。
        val deadline = SystemClock.elapsedRealtime() + timeoutMilliseconds
        var nextReportAt = 0L
        while (!condition()) {
            val now = SystemClock.elapsedRealtime()
            check(now < deadline) { message }
            if (now >= nextReportAt) {
                saveReport()
                nextReportAt = now + 30_000
            }
            Thread.sleep(100)
        }
    }

    /** 保存已经完成的 name 检查点；失败后不能补写尚未通过的步骤。 */
    private fun record(name: String) {
        // 每个阶段立即落盘，长时间等待途中也能核对当前真实进度。
        checks.put(JSONObject().put("check", name).put("passed", true))
        saveReport()
    }

    /** 保存阶段及真实已等待秒数，不记录 JWT、refreshToken、密码或 Socket 编号。 */
    private fun saveReport() {
        // 使用固定应用私有文件，外部脚本只导出这份不含凭据的报告。
        report.put("elapsedSeconds", if (startedAt == 0L) 0L else (SystemClock.elapsedRealtime() - startedAt) / 1_000)
        File(context.filesDir, "native-local-socket-expiry-report.json").writeText(report.put("checks", checks).toString(2))
    }
}
