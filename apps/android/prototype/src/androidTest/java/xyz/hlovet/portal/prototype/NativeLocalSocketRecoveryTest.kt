package xyz.hlovet.portal.prototype

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

/** 在真实本地 API 拒绝凭据后恢复原 Socket，并验证消息落库；不替代正式服务或双端媒体验收。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSocketRecoveryTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    /** 只读取目标应用上下文，后续存储始终按当前本地来源隔离。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adapter: NativeChatSocket? = null
    private var readerId = 0
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 释放本项的连接和协程，保留加密会话、已读状态与实际发送的验收消息。 */
    @After fun finish() {
        // 等待事件线程关闭后再结束刷新任务，不能让测试退出造成后续连接复活。
        adapter?.close()
        barrier()
        scope.cancel()
        report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == readerId)
        saveReport()
    }

    /** 真实服务端拒绝后，显式重试刷新真实会话并恢复业务；原连接对象和账号始终不变。 */
    @Test fun realRejectionThenExplicitRetryRestoresMessaging() {
        // 只使用任务设备已有的本地账号及真实可见会话，不创建或覆盖生产会话。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        val original = runBlocking { NativeSessionCoordinator.refresh(context) }
        readerId = original.userId
        val conversationId = NativeSocialApi.listConversations(original.accessToken).first { it.kind != "group" }.id
        report.put("readerId", readerId).put("conversationId", conversationId)
        val ready = AtomicInteger()
        val disconnected = AtomicInteger()
        val refreshRequests = AtomicInteger()
        adapter = NativeChatSocket(original.accessToken, {}, { _, _ -> }, {}, {
            if (it) ready.incrementAndGet() else disconnected.incrementAndGet()
        }, onReauthenticate = { complete ->
            // 与宿主相同，在主线程发起会话协调器，底层网络仍由协调器切换到 IO。
            refreshRequests.incrementAndGet()
            scope.launch {
                complete(runCatching { NativeSessionCoordinator.refresh(context) }.getOrNull()?.accessToken)
            }
        }).also { it.connect() }
        await("真实连接未完成初次认证") { ready.get() == 1 }
        val initial = socket()
        val initialId = initial.id()
        record("existing-local-account-and-real-server-authenticated")

        // 固定非法值使真实 JWT 校验拒绝，不伪造 ACK、不调用服务端私有方法或修改数据库。
        requireNotNull(adapter).updateAccessToken("fixture-invalid-credential")
        await("真实服务端没有拒绝非法凭据") { disconnected.get() == 1 }
        assertFalse(socket().connected())
        assertEquals(0, refreshRequests.get())
        record("real-server-rejects-invalid-jwt-and-closes-connection")

        // 连续重试应合并为一次真实刷新；同一个 Socket 在新握手后取得不同的连接编号。
        repeat(3) { requireNotNull(adapter).reconnect() }
        await("真实刷新后连接未恢复") { ready.get() == 2 }
        assertEquals(1, refreshRequests.get())
        assertSame(initial, socket())
        assertTrue(initialId != socket().id())
        assertEquals(readerId, NativeSessionStore.load(context)?.userId)
        record("one-real-refresh-restores-original-socket-and-account")

        // 已读 ACK 和持久化消息分别证明恢复后的权限和写入，不用图标或 connected 布尔值代替。
        val read = AtomicBoolean(false)
        requireNotNull(adapter).mutate("chat:read", JSONObject().put("conversationId", conversationId)) { ok, _ -> read.set(ok) }
        await("恢复后的已读操作失败") { read.get() }
        val marker = "native_socket_recovery_" + UUID.randomUUID().toString().take(8)
        val sent = AtomicBoolean(false)
        requireNotNull(adapter).send(conversationId, marker) { ok, _ -> sent.set(ok) }
        await("恢复后的消息发送失败") { sent.get() }
        val current = requireNotNull(NativeSessionStore.load(context))
        val persisted = NativeSocialApi.searchMessages(current.accessToken, conversationId, marker).items.single { it.body == marker }
        report.put("messageId", persisted.id).put("messageMarker", marker)
        record("read-ack-and-real-persisted-message-succeed-after-retry")
        report.put("completed", true)
        saveReport()
    }

    /** 只读取得当前产品 Socket，用于确认恢复没有重建适配器或更换账号。 */
    private fun socket(): Socket {
        // 读取之前冲刷事件队列，不反射修改产品状态或注入成功事件。
        barrier()
        return requireNotNull(NativeChatSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(adapter) as? Socket)
    }

    /** 等待已提交的 Socket.IO 生命周期操作完成，不无限阻塞测试线程。 */
    private fun barrier() {
        // 屏障只用于顺序观测，不改变网络或认证结果。
        val latch = CountDownLatch(1)
        EventThread.exec { latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS))
    }

    /** 在真实二十秒上限内等待 condition；失败只记录业务 message，不输出认证材料。 */
    private fun await(message: String, condition: () -> Boolean) {
        // 使用单调时钟等待真实服务端响应，不推进产品认证计时器。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            check(System.nanoTime() < deadline) { message }
            Thread.sleep(40)
        }
    }

    /** 将已经通过的 name 检查点立即保存，失败不能补写尚未完成的步骤。 */
    private fun record(name: String) {
        // 报告保留逐步证据，最后一项成功才会设置 completed。
        checks.put(JSONObject().put("check", name).put("passed", true))
        saveReport()
    }

    /** 保存本项脱敏报告；仅包含账号/会话/消息编号及实际断言，不保存密码或令牌。 */
    private fun saveReport() {
        // 固定输出位于应用私有目录，外部脚本只导出该报告。
        File(context.filesDir, "native-local-socket-recovery-report.json").writeText(report.put("checks", checks).toString(2))
    }
}
