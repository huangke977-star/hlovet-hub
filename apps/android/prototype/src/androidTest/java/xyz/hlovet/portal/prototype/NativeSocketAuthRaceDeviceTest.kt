package xyz.hlovet.portal.prototype

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.socket.client.Socket
import io.socket.thread.EventThread
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用本机独立 Socket.IO 夹具验证认证竞态；不读取登录态、不代替真实 API 或媒体验收。 */
@RunWith(AndroidJUnit4::class)
class NativeSocketAuthRaceDeviceTest {
    @get:Rule val network = NativeLoopbackNetworkRule()
    private var adapter: NativeChatSocket? = null
    private val ready = AtomicInteger()
    private val disconnected = AtomicInteger()
    private val errors = AtomicInteger()
    private val callbacksOnMain = AtomicBoolean(true)
    private val refresh = AtomicReference<((String?) -> Unit)?>()
    private val refreshRequests = AtomicInteger()

    /** 测试结束释放自己的连接，不清除原应用会话或任何业务数据。 */
    @After fun finish() {
        // 事件屏障确保测试连接已关闭，再允许下一项重新配置夹具。
        adapter?.close()
        barrier()
        // 冲刷已经投递的界面回调，再断言认证结果、错误和连接状态均未越过主线程边界。
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { }
        assertTrue("界面回调必须在 Android 主线程执行", callbacksOnMain.get())
    }

    /** 在途旧 ACK 到达后必须继续提交最新值，不能遗漏连续更新或重建 Socket。 */
    @Test fun delayedAcknowledgementPreservesLatestUpdate() {
        // 第一条更新延迟，第二条保持排队，释放后精确核对服务端接收顺序。
        val socket = connect("delayed")
        requireNotNull(adapter).updateAccessToken("fixture-next")
        await { state().getInt("pending") == 1 }
        requireNotNull(adapter).updateAccessToken("fixture-latest")
        barrier()
        assertEquals(2, state().getJSONArray("received").length())
        command("/release")
        await { state().getJSONArray("received").length() == 3 }
        assertEquals("fixture-latest", state().getJSONArray("received").getString(2))
        assertSame(socket, socket())
        assertEquals(1, ready.get())
        assertEquals(0, disconnected.get())
    }

    /** 服务端请求重新认证时使用宿主回调，并继续使用同一个原连接。 */
    @Test fun serverPromptRefreshesWithoutReplacingSocket() {
        // 固定假令牌只用于协议夹具；不冒充 OAuth 或真实刷新接口的成功证据。
        val socket = connect("normal")
        command("/prompt")
        await { refresh.get() != null }
        requireNotNull(refresh.get())("fixture-refreshed")
        await { state().getJSONArray("received").length() == 2 }
        assertEquals("fixture-refreshed", state().getJSONArray("received").getString(1))
        assertSame(socket, socket())
        assertEquals(0, disconnected.get())
    }

    /** 更新值先于旧刷新回调到达时，迟到回调不能恢复旧握手凭据。 */
    @Test fun lateRefreshCannotOverwriteNewerToken() {
        // 主线程的最新更新与异步刷新结果交错，最终 Map 和最后一次认证都应保留最新值。
        connect("normal")
        command("/prompt")
        await { refresh.get() != null }
        requireNotNull(adapter).updateAccessToken("fixture-latest")
        await { state().getJSONArray("received").length() == 2 }
        requireNotNull(refresh.get())("fixture-next")
        await { state().getJSONArray("received").length() == 3 }
        assertEquals("fixture-latest", state().getJSONArray("received").getString(2))
        val auth = Socket::class.java.getDeclaredField("auth").apply { isAccessible = true }.get(socket()) as Map<*, *>
        assertTrue(auth["token"] == "fixture-latest")
    }

    /** 关闭后的刷新回调不得重连或重新发出认证，旧页面不能复活连接。 */
    @Test fun closedConnectionIgnoresLateRefreshCallback() {
        // 先记录真实接收次数，关闭后触发旧回调并通过事件屏障核对没有新增请求。
        connect("normal")
        command("/prompt")
        await { refresh.get() != null }
        val received = state().getJSONArray("received").length()
        requireNotNull(adapter).close()
        barrier()
        requireNotNull(refresh.get())("fixture-refreshed")
        barrier()
        await { state().getInt("connected") == 0 }
        assertEquals(received, state().getJSONArray("received").length())
        assertEquals(1, state().getInt("connections"))
    }

    /** 被拒绝的重新认证必须关闭连接并向宿主提供错误提示，不能继续显示为可用。 */
    @Test fun rejectedAcknowledgementDisconnectsAndReportsError() {
        // 夹具返回明确失败 ACK，与真实 API 会直接断开的场景分开覆盖。
        connect("normal")
        requireNotNull(adapter).updateAccessToken("fixture-reject")
        await { disconnected.get() == 1 && errors.get() == 1 }
        assertFalse(socket().connected())
    }

    /** 超时后关闭原连接，随后到达的成功 ACK 也不能重新激活认证。 */
    @Test fun timeoutDisconnectsAndIgnoresLateAcknowledgement() {
        // 真实等待产品十秒上限，不缩短产品计时器或注入超时事件。
        connect("timeout")
        requireNotNull(adapter).updateAccessToken("fixture-next")
        await { state().getInt("pending") == 1 }
        await { disconnected.get() == 1 && errors.get() == 1 }
        command("/release")
        barrier()
        assertFalse(socket().connected())
        assertEquals(1, state().getInt("connections"))
    }

    /** 初次服务端 ready 之后仍需等待最新凭据 ACK，不能提前让界面显示可用。 */
    @Test fun initialConnectionWaitsForAuthenticationAcknowledgement() {
        // 延迟真实协议回执，检查等待期间没有在线回调；释放后才允许正常操作。
        createAdapter("initial-delayed")
        await { state().getInt("pending") == 1 }
        assertEquals(0, ready.get())
        command("/release")
        await { ready.get() == 1 }
        assertEquals(0, disconnected.get())
    }

    /** 认证拒绝后的连续重试只刷新一次，并用新凭据恢复原 Socket 对象。 */
    @Test fun rejectedAuthenticationCanRecoverWithOneRefresh() {
        // 先确认拒绝确实断开；重试不能用旧凭据直接重连，也不能创建第二个适配器。
        val original = connect("normal")
        requireNotNull(adapter).updateAccessToken("fixture-reject")
        await { disconnected.get() == 1 && errors.get() == 1 }
        repeat(3) { requireNotNull(adapter).reconnect() }
        await { refreshRequests.get() == 1 }
        barrier()
        assertEquals(1, state().getInt("connections"))
        requireNotNull(refresh.get())("fixture-refreshed")
        await { ready.get() == 2 }
        assertSame(original, socket())
        assertEquals(2, state().getInt("connections"))
        assertEquals("fixture-refreshed", state().getJSONArray("received").getString(2))
    }

    /** 真实十秒超时后用户可重新认证，迟到旧 ACK 不影响恢复后的新请求。 */
    @Test fun timedOutAuthenticationCanRecoverWithoutRecreatingSocket() {
        // 超时先按产品策略关闭；只在用户重试且宿主返回凭据后重新开放连接。
        val original = connect("timeout")
        requireNotNull(adapter).updateAccessToken("fixture-next")
        await { disconnected.get() == 1 && errors.get() == 1 }
        requireNotNull(adapter).reconnect()
        await { refreshRequests.get() == 1 }
        requireNotNull(refresh.get())("fixture-refreshed")
        await { ready.get() == 2 }
        command("/release")
        barrier()
        assertSame(original, socket())
        assertTrue(socket().connected())
        assertEquals(2, ready.get())
        assertEquals(1, errors.get())
    }

    /** 宿主刷新失败不重连，后续显式重试仍可恢复，不形成无效凭据循环。 */
    @Test fun failedRefreshStaysDisconnectedAndAllowsAnotherRetry() {
        // 第一次刷新返回空值并保留失败；第二次返回有效测试凭据才建立新握手。
        connect("normal")
        command("/disconnect")
        await { disconnected.get() == 1 }
        requireNotNull(adapter).reconnect()
        await { refreshRequests.get() == 1 }
        requireNotNull(refresh.get())(null)
        await { errors.get() == 1 }
        assertEquals(1, state().getInt("connections"))
        requireNotNull(adapter).reconnect()
        await { refreshRequests.get() == 2 }
        requireNotNull(refresh.get())("fixture-refreshed")
        await { ready.get() == 2 }
        assertEquals(2, state().getInt("connections"))
    }

    /** 临时断网时手动刷新失败，不应取消底层已安排的网络自动重连。 */
    @Test fun failedRefreshDoesNotStopTransportAutoReconnect() {
        // 服务端关闭真实传输；宿主返回空值后仍应使用原有效凭据按产品退避重连。
        val original = connect("normal")
        command("/drop")
        await { disconnected.get() == 1 }
        requireNotNull(adapter).reconnect()
        await { refreshRequests.get() == 1 }
        requireNotNull(refresh.get())(null)
        await { errors.get() == 1 }
        await { ready.get() == 2 }
        assertSame(original, socket())
        assertEquals(2, state().getInt("connections"))
    }

    /** 重试刷新期间退出页面，迟到的刷新回调不能恢复已释放的适配器。 */
    @Test fun closeDuringRetryIgnoresRefreshedCredentials() {
        // 断开后的重试与正常在线到期提示分开覆盖，不改变产品私有状态。
        connect("normal")
        command("/disconnect")
        await { disconnected.get() == 1 }
        requireNotNull(adapter).reconnect()
        await { refreshRequests.get() == 1 }
        val complete = requireNotNull(refresh.get())
        requireNotNull(adapter).close()
        barrier()
        complete("fixture-refreshed")
        requireNotNull(adapter).reconnect()
        barrier()
        assertEquals(1, state().getInt("connections"))
        assertEquals(0, state().getInt("connected"))
    }

    /** 宿主已有新凭据时可直接同步并恢复连接，不必等待下一次进入聊天页。 */
    @Test fun newerCredentialsRecoverRejectedConnection() {
        // 使用与被拒绝值不同的新值；同值更新仍保持既有去重行为。
        val original = connect("normal")
        requireNotNull(adapter).updateAccessToken("fixture-reject")
        await { disconnected.get() == 1 }
        requireNotNull(adapter).updateAccessToken("fixture-refreshed")
        await { ready.get() == 2 }
        assertSame(original, socket())
        assertEquals(0, refreshRequests.get())
    }

    /** 再次调用连接入口能恢复服务端主动断开的同一 Socket，重复调用不创建额外连接。 */
    @Test fun connectReusesAnExistingDisconnectedSocket() {
        // 该场景凭据未失效，只核对底层 disconnect 后的连接生命周期。
        val original = connect("normal")
        command("/disconnect")
        await { disconnected.get() == 1 }
        repeat(3) { requireNotNull(adapter).connect() }
        await { ready.get() == 2 }
        assertSame(original, socket())
        assertEquals(2, state().getInt("connections"))
    }

    /** 配置 scenario 对应夹具并创建实际产品适配器，返回已收到初次认证 ACK 的 Socket。 */
    private fun connect(scenario: String): Socket {
        // 先创建连接，再等待真实首个认证成功，不能用 transport connected 替代业务就绪。
        createAdapter(scenario)
        await { ready.get() == 1 && state().getJSONArray("received").length() == 1 }
        await { authenticationIdle() }
        return socket()
    }

    /** 创建 scenario 的真实适配器但不等待 ready，便于检查初次 ACK 前的不可用状态。 */
    private fun createAdapter(scenario: String) {
        // 构建与控制来源都限制本地，夹具只接收固定非敏感测试值。
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001")
        command("/reset/$scenario")
        adapter = NativeChatSocket("fixture-initial", {}, { _, _ -> }, {
            observeCallbackThread()
            errors.incrementAndGet()
        }, {
            observeCallbackThread()
            if (it) ready.incrementAndGet() else disconnected.incrementAndGet()
        }, onReauthenticate = {
            observeCallbackThread()
            refreshRequests.incrementAndGet()
            refresh.set(it)
        }).also { it.connect() }
    }

    /** 记录当前回调是否在主线程，不在后台抛断言干扰产品后续清理。 */
    private fun observeCallbackThread() {
        // 只要一次后台回调就保留失败，后来的正确回调不能覆盖问题。
        if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false)
    }

    /** 只读检查初次认证是否结束，避免把初次在途 ACK 错算为测试创建的延迟。 */
    private fun authenticationIdle(): Boolean {
        // 只读反射用于观测产品实际状态，不注入或改写认证字段。
        barrier()
        return NativeChatSocket::class.java.getDeclaredField("pendingAuthenticationToken").apply { isAccessible = true }.get(adapter) == null
    }

    /** 只读取得当前 Socket，返回对象用于身份比较，不输出 Socket 或凭据。 */
    private fun socket(): Socket {
        // 同一事件队列上的屏障保证先前生命周期操作已执行。
        barrier()
        return requireNotNull(NativeChatSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.get(adapter) as? Socket)
    }

    /** 等待已排队的 Socket.IO 操作执行，不修改连接或产品线程配置。 */
    private fun barrier() {
        // 有界屏障在异常时直接失败，不能用无限等待隐藏死锁。
        val latch = CountDownLatch(1)
        EventThread.exec { latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS))
    }

    /** 读取夹具的脱敏状态；返回的 token 字段只可能是服务端白名单中的固定测试字符串。 */
    private fun state(): JSONObject {
        // 固定只读入口不改变夹具的请求顺序或认证状态。
        return request("/state", "GET")
    }

    /** 对夹具提交 path 对应的固定动作，返回该动作后的连接与回执计数。 */
    private fun command(path: String): JSONObject {
        // 所有状态操作均显式使用 POST，与只读观测区分。
        return request(path, "POST")
    }

    /** 使用 method 访问本机控制 path，返回 JSON，网络错误直接使测试失败。 */
    private fun request(path: String, method: String): JSONObject {
        // 该端口仅反向映射到独立夹具控制面，不访问业务 API 或外部地址。
        val connection = URL("http://127.0.0.1:3004$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            check(connection.responseCode == 200)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** 在真实十五秒上限内等待 condition，不推进或替换产品的认证超时计时器。 */
    private fun await(condition: () -> Boolean) {
        // 少量轮询用于外部服务同步；失败不打印任何状态对象或认证材料。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "认证竞态夹具等待超时。" }
            Thread.sleep(30)
        }
    }
}
