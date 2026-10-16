package xyz.hlovet.portal.prototype

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核对首页 HTTP 请求的鉴权、后台调度、错误及真实调用取消。 */
class NativePreviewHttpTest {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private suspend fun awaitNoCalls() = withTimeout(2000) {
        while (client.dispatcher.runningCallsCount() > 0) delay(10)
    }

    /** callStart 在 enqueue 调用线程执行，可确认请求初始化没有留在收集线程。 */
    @Test
    fun requestSetupRunsOffCallingThread() = runBlocking {
        // 观察请求调度入口而非后台拦截器，避免把响应线程误当成初始化线程。
        val callerThread = Thread.currentThread()
        var setupThread: Thread? = null
        val observedClient = client.newBuilder().eventListener(object : EventListener() {
            /** 记录 call 开始时的线程，不读取或保存请求凭据。 */
            override fun callStart(call: Call) {
                // 该回调同步发生于 enqueue，反映请求设置的实际执行线程。
                setupThread = Thread.currentThread()
            }
        }).build()
        MockWebServer().use { server ->
            // 成功回包后检查线程，同时保留原有 JSON 请求契约。
            server.enqueue(MockResponse().setBody("{}"))
            nativePreviewJsonRequest(observedClient, server.url("/articles").toString())
            assertNotNull(setupThread)
            assertNotSame(callerThread, setupThread)
            assertEquals(callerThread, Thread.currentThread())
        }
    }

    @Test
    fun jsonRequestUsesExistingAcceptAndAuthenticationContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            val result = nativePreviewJsonRequest(client, server.url("/articles/visible").toString(), "test-token")
            assertEquals(0, result.getJSONArray("items").length())
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/articles/visible", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun anonymousRequestDoesNotSendAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            nativePreviewJsonRequest(client, server.url("/articles").toString())
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun serverErrorKeepsOnlyStatusAndNotResponseSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("private server detail"))
            val failure = runCatching {
                nativePreviewJsonRequest(client, server.url("/articles").toString())
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("HTTP 503", failure?.message)
        }
    }

    @Test
    fun coroutineTimeoutCancelsActualInFlightHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(500) {
                    nativePreviewJsonRequest(client, server.url("/articles").toString())
                }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNull(request.await())
            awaitNoCalls()
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }

    @Test
    fun accountSwitchCancelsActualInFlightHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                nativePreviewJsonRequest(client, server.url("/articles").toString())
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            awaitNoCalls()
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }
}
