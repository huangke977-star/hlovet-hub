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
    private val originalProxyProperties = mutableMapOf<String, String?>()
    private var originalHttpNonProxyHosts: String? = null
    private var originalHttpsNonProxyHosts: String? = null

    /** 在夹具服务与 Compose 宿主启动前安装回环直连规则，不修改 Android 系统代理。 */
    override fun before() {
        // 保存当前进程的策略，规则只对本地测试服务的三个标准回环主机生效。
        original = ProxySelector.getDefault()
        listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { name ->
            originalProxyProperties[name] = System.getProperty(name)
            // Android 设备的全局代理可能绕过 ProxySelector；清空 Java 代理属性后由下方选择器决定非本地请求。
            System.clearProperty(name)
        }
        originalHttpNonProxyHosts = System.getProperty("http.nonProxyHosts")
        originalHttpsNonProxyHosts = System.getProperty("https.nonProxyHosts")
        // Android 模拟器可能通过 Java 系统属性强制指定代理；显式补充本地地址白名单，避免认证请求误发到外部环境。
        val localHosts = listOf("localhost", "127.*", "[::1]", "::1", "10.0.2.2")
        System.setProperty("http.nonProxyHosts", mergeNonProxyHosts(originalHttpNonProxyHosts, localHosts))
        System.setProperty("https.nonProxyHosts", mergeNonProxyHosts(originalHttpsNonProxyHosts, localHosts))
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
        originalProxyProperties.forEach { (name, value) -> restoreProperty(name, value) }
        restoreProperty("http.nonProxyHosts", originalHttpNonProxyHosts)
        restoreProperty("https.nonProxyHosts", originalHttpsNonProxyHosts)
    }

    /** 合并 Java 代理白名单，保留设备原配置并去重本地测试地址。 */
    private fun mergeNonProxyHosts(original: String?, localHosts: List<String>): String =
        (original.orEmpty().split('|') + localHosts).filter { it.isNotBlank() }.distinct().joinToString("|")

    /** 恢复 Java 系统属性，原值为空时删除属性而不是写入空字符串。 */
    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
}
