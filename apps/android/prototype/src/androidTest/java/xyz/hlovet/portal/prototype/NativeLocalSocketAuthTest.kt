package xyz.hlovet.portal.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.socket.client.Socket
import io.socket.client.Manager
import io.socket.thread.EventThread
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实本地 API 与 Socket 验收令牌轮换、原连接保留和后续重连凭据；辅助来电没有媒体，不冒充双原生通话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalSocketAuthTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var caller: NativeChatSocket? = null
    private var callee: NativeChatSocket? = null
    private var fixture: NativeSession? = null
    private var readerId = 0
    private var callId = 0
    private val checks = JSONArray()
    private val report = JSONObject().put("completed", false).put("productionTested", false).put("testDataRemoved", false)

    /** 只接受精确本地来源与已经保存的读者登录态，不覆盖生产来源或自动创建替代读者。 */
    @Before fun prepare() {
        // 原有登录态通过协调器正常轮换；测试日志和报告不保存任何令牌或密码。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        readerId = requireNotNull(NativeSessionStore.load(context)).userId
        report.put("readerId", readerId)
        saveReport()
    }

    /** 结束本测试连接和辅助会话，保留账号、好友、通话与现有加密读者会话。 */
    @After fun finish() {
        // 辅助账号只正常退出，不删除已产生的数据；退出前请求结束尚存的来电。
        caller?.endCall(callId)
        caller?.close()
        callee?.close()
        eventBarrier()
        fixture?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
        report.put("sessionPreserved", NativeSessionStore.load(context)?.userId == readerId)
        saveReport()
    }

    /** 响铃期间真实轮换令牌不得重建 Socket；后续真实断线重连必须继续使用最新握手凭据。 */
    @Test fun rotationKeepsSocketAndNextReconnectUsesLatestCredentials() {
        // 通过正常接口保留新辅助账号并建立好友关系，来电状态由实际服务端管理。
        val original = runBlocking { NativeSessionCoordinator.refresh(context) }
        val marker = "native_ca_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val registered = NativeAuthApi.register(marker, "认证轮换样例", "$marker@android-fixture.invalid", "Native_${UUID.randomUUID()}_9!", null, null)
        check(registered is NativeAuthResult.Authenticated)
        val auxiliary = registered.session.also { fixture = it }
        NativeFriendsApi.requestFriend(original.accessToken, auxiliary.userId, "原生 Socket 令牌验收")
        val request = NativeFriendsApi.listFriendships(auxiliary.accessToken).incoming.single { it.user.id == readerId }
        NativeFriendsApi.respondFriendRequest(auxiliary.accessToken, request.id, "accepted")
        val conversationId = NativeFriendsApi.getOrCreateConversation(original.accessToken, auxiliary.userId).id
        report.put("fixtureId", auxiliary.userId).put("fixtureUsername", marker).put("conversationId", conversationId)
        val connections = AtomicInteger()
        val peerReady = AtomicBoolean(false)
        val incoming = AtomicReference<NativeCallSession?>()
        val ended = AtomicReference<NativeCallSession?>()
        callee = NativeChatSocket(auxiliary.accessToken, {}, { _, _ -> }, {}, { peerReady.set(it) }, onCallIncoming = { incoming.set(it) })
            .also { it.connect() }
        caller = NativeChatSocket(original.accessToken, {}, { _, _ -> }, {}, { if (it) connections.incrementAndGet() }, onCallEnded = { ended.set(it) })
            .also { it.connect() }
        await("双方真实 Socket 未就绪") { connections.get() == 1 && peerReady.get() }
        val started = AtomicReference<NativeCallSession?>()
        requireNotNull(caller).startCall(conversationId, "voice") { ok, call, _ -> if (ok) started.set(call) }
        await("真实来电未到达") { started.get() != null && incoming.get()?.id == started.get()?.id }
        callId = requireNotNull(started.get()).id
        report.put("callId", callId)
        val initialSocket = currentSocket(requireNotNull(caller))
        val initialId = requireNotNull(initialSocket.id())
        record("real-ringing-call-and-original-socket-ready")

        // JWT iat 精度为秒，跨过一秒再真实刷新，避免同值访问令牌掩盖更新行为。
        Thread.sleep(1_100)
        val refreshed = runBlocking { NativeSessionCoordinator.refresh(context) }
        check(refreshed.accessToken != original.accessToken) { "真实刷新未产生不同的访问令牌。" }
        requireNotNull(caller).updateAccessToken(refreshed.accessToken)
        eventBarrier()
        val updatedSocket = currentSocket(requireNotNull(caller))
        assertTrue("令牌轮换不能替换 Socket 对象", updatedSocket === initialSocket)
        assertTrue("令牌轮换不能改变服务端连接编号", updatedSocket.id() == initialId)
        assertTrue("轮换后握手凭据必须是最新令牌", handshakeTokenMatches(updatedSocket, refreshed.accessToken))
        checkReadAcknowledgement(conversationId)
        assertNull("认证轮换不能结束响铃中的通话", ended.get())
        assertEquals(1, connections.get())
        record("real-token-rotation-keeps-socket-id-and-ringing-call")

        // 同值或空值同步不得重复连接；正常挂断后再只关闭该测试连接的底层传输。
        requireNotNull(caller).updateAccessToken(refreshed.accessToken)
        requireNotNull(caller).updateAccessToken(" ")
        eventBarrier()
        assertTrue(currentSocket(requireNotNull(caller)) === initialSocket)
        requireNotNull(caller).endCall(callId)
        await("真实来电未正常取消") { ended.get()?.id == callId }
        record("same-or-empty-token-does-not-replace-connection")
        val engineField = Manager::class.java.getDeclaredField("engine").apply { isAccessible = true }
        EventThread.exec { (engineField.get(initialSocket.io()) as io.socket.engineio.client.Socket).close() }
        await("真实传输断开后未自动重连") { connections.get() >= 2 }
        val reconnected = currentSocket(requireNotNull(caller))
        assertTrue("自动重连仍应复用原 Socket", reconnected === initialSocket)
        assertTrue("实际重新连接应获得新连接编号", reconnected.id() != initialId)
        assertTrue("重连不能退回旧令牌", handshakeTokenMatches(reconnected, refreshed.accessToken))
        checkReadAcknowledgement(conversationId)
        record("real-transport-reconnect-uses-latest-token-and-remains-usable")
        report.put("completed", true)
        saveReport()
    }

    /** 等待先前提交的 Socket.IO 操作全部执行，不用固定延迟假定异步更新已完成。 */
    private fun eventBarrier() {
        // 只投递空屏障，不修改产品状态或注入网络事件。
        val barrier = CountDownLatch(1)
        EventThread.exec { barrier.countDown() }
        check(barrier.await(5, TimeUnit.SECONDS))
    }

    /** 只读取得 adapter 当前实际 Socket，用于证明对象与连接编号没有重建，不给产品增加测试入口。 */
    private fun currentSocket(adapter: NativeChatSocket): Socket {
        // 字段读取前先过事件线程屏障，避免把尚未执行的连接动作误判为空。
        eventBarrier()
        return requireNotNull(NativeChatSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(adapter) as? Socket)
    }

    /** 只比较 socket 内实际握手 token 与 expected，返回布尔值，失败也不得打印认证材料。 */
    private fun handshakeTokenMatches(socket: Socket, expected: String): Boolean {
        // Socket.IO 2.1 没有公开 auth setter，此处仅只读验证它实际持有的共享 Map。
        val auth = Socket::class.java.getDeclaredField("auth").apply { isAccessible = true }.get(socket) as Map<*, *>
        return auth["token"] == expected
    }

    /** 使用 conversationId 的真实已读操作确认认证后连接仍可执行业务，不以连接图标代替服务端 ACK。 */
    private fun checkReadAcknowledgement(conversationId: Int) {
        // ACK 不含敏感信息，只记录成功布尔值；请求由产品 Socket 适配器实际发送。
        val acknowledged = AtomicBoolean(false)
        requireNotNull(caller).mutate("chat:read", JSONObject().put("conversationId", conversationId)) { ok, _ -> acknowledged.set(ok) }
        await("认证后的业务 ACK 未成功返回") { acknowledged.get() }
    }

    /** 在有界时限内等待 condition；失败只显示业务 message，不输出对象或令牌。 */
    private fun await(message: String, condition: () -> Boolean) {
        // 使用单调时钟和短轮询，避免修改设备时间或消耗整个测试线程。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            check(System.nanoTime() < deadline) { message }
            Thread.sleep(50)
        }
    }

    /** 保存已通过的业务检查点 name，失败报告保留此前实际完成的部分而非虚构全部成功。 */
    private fun record(name: String) {
        // 每次写入都保留当前 completed 状态，最终成功由测试结束前显式设置。
        checks.put(JSONObject().put("check", name).put("passed", true))
        saveReport()
    }

    /** 在应用私有目录保存脱敏报告，外部运行脚本只导出该固定文件。 */
    private fun saveReport() {
        // 不存储访问/刷新令牌、密码或 Socket 握手报文。
        File(context.filesDir, "native-local-socket-auth-report.json").writeText(report.put("checks", checks).toString(2))
    }
}
