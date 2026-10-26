package xyz.hlovet.portal.prototype

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.rules.ExternalResource

/** 让设备内 HTTP 夹具绕过系统代理；其它地址保留原代理策略，测试退出时恢复全局选择器。 */
class NativeLoopbackNetworkRule : ExternalResource() {
    private var original: ProxySelector? = null

    /** 在夹具服务与 Compose 宿主启动前安装回环直连规则，不修改 Android 系统代理。 */
    override fun before() {
        // 保存当前进程的策略，规则只对本地测试服务的三个标准回环主机生效。
        original = ProxySelector.getDefault()
        val delegate = original
        ProxySelector.setDefault(object : ProxySelector() {
            /** 按 uri 的主机选择代理；回环返回直连，其它请求完整交回原选择器。 */
            override fun select(uri: URI): List<Proxy> {
                // 测试凭据不能通过设备代理访问本机夹具，也不放开任意私网或生产地址。
                return if (uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")) {
                    listOf(Proxy.NO_PROXY)
                } else {
                    delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
            }

            /** 将非回环 uri 的代理连接失败回交给原选择器，不输出地址或认证信息。 */
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                // 原代理需要收到失败反馈以维持其既有重试与降级策略。
                delegate?.connectFailed(uri, address, error)
            }
        })
    }

    /** 在 Compose 和服务夹具释放之后恢复选择器；异常或跳过测试也执行此清理。 */
    override fun after() {
        // 精确恢复包括 null 在内的原状态，不能把本轮直连规则留给下一项测试。
        ProxySelector.setDefault(original)
    }
}
