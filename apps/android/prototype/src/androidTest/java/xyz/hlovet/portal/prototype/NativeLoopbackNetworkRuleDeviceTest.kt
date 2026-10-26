package xyz.hlovet.portal.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 验证本地测试网络规则的作用范围与异常清理，不改变设备系统代理或访问外部网络。 */
@RunWith(AndroidJUnit4::class)
class NativeLoopbackNetworkRuleDeviceTest {
    /** 回环地址全部直连，但同名外部域名和普通私网地址必须仍使用原代理。 */
    @Test fun onlyLoopbackHostsBypassTheOriginalProxy() {
        // 使用内存代理策略观察委派，不实际连接该代理或任何外部地址。
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))
        val delegated = mutableListOf<URI>()
        val original = ProxySelector.getDefault()
        val baseline = object : ProxySelector() {
            /** 记录 uri 并返回固定代理，供规则范围断言使用。 */
            override fun select(uri: URI): List<Proxy> {
                // 仅记录测试构造的无认证地址，不读取真实代理配置。
                delegated.add(uri)
                return listOf(proxy)
            }

            /** 本用例不发起网络连接，因此连接失败反馈无需修改状态。 */
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        }
        try {
            ProxySelector.setDefault(baseline)
            NativeLoopbackNetworkRule().apply(object : Statement() {
                /** 在规则生效期间核对直连白名单和外部委派边界。 */
                override fun evaluate() {
                    // 只接受精确主机，不能用字符串前缀误放行外部域名或整个内网。
                    listOf("127.0.0.1", "localhost", "[::1]").forEach { host ->
                        assertEquals(listOf(Proxy.NO_PROXY), ProxySelector.getDefault().select(URI("http://$host:39871/users")))
                    }
                    listOf("localhost.example.invalid", "127.0.0.1.example.invalid", "192.168.1.2").forEach { host ->
                        assertEquals(listOf(proxy), ProxySelector.getDefault().select(URI("https://$host/api")))
                    }
                    assertEquals(3, delegated.size)
                }
            }, Description.EMPTY).evaluate()
            assertSame(baseline, ProxySelector.getDefault())
        } finally {
            // 无论哪项断言失败，都不能把构造的代理留给其它设备测试。
            ProxySelector.setDefault(original)
        }
    }

    /** 测试主体抛错时仍恢复原选择器，不能依赖用例成功才执行清理。 */
    @Test fun statementFailureStillRestoresTheOriginalSelector() {
        // 使用同一个异常实例核对错误没有被清理吞掉或替换。
        val original = ProxySelector.getDefault()
        val expected = IllegalStateException("Expected fixture failure")
        val actual = runCatching {
            NativeLoopbackNetworkRule().apply(object : Statement() {
                /** 模拟业务断言失败，触发规则的异常退出路径。 */
                override fun evaluate() {
                    // 错误必须原样传播，而代理状态应在框架 finally 阶段恢复。
                    throw expected
                }
            }, Description.EMPTY).evaluate()
        }.exceptionOrNull()
        assertSame(expected, actual)
        assertSame(original, ProxySelector.getDefault())
    }
}
