package xyz.hlovet.portal.prototype

import android.content.Context
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** 为离线内容、媒体、首页和草稿生成统一的服务器来源命名空间，不猜测旧数据所属站点。 */
internal object NativeOfflineOrigin {
    /** 将 apiBaseUrl 规范化为协议、主机及有效端口；非法来源抛出异常，不退回生产站点。 */
    fun origin(apiBaseUrl: String = BuildConfig.API_BASE_URL): String {
        // 与登录凭据共用来源规则，等价的默认端口及 API 路径不会拆分同一站点。
        return requireNotNull(nativeDeviceCookieOrigin(URI(apiBaseUrl))) { "Invalid offline server origin" }
    }

    /** 根据业务 identity 和 apiBaseUrl 返回带版本前缀的存储键，不包含账号或正文原文。 */
    fun key(identity: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): String {
        // 来源参与摘要，即使不同服务器的用户 ID、内容 ID 和媒体地址相同也不共用记录。
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${origin(apiBaseUrl)}|$identity".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "v2-$digest"
    }

    /** 检查 context 中未标记来源的旧版文件；只读取目录，不迁移或删除历史缓存。 */
    fun hasLegacyData(context: Context): Boolean {
        // 旧版首页缓存、离线索引和媒体文件都无法可靠反推所属服务器，统一留存而不自动读取。
        val preview = context.filesDir.listFiles().orEmpty().any {
            it.isFile && (it.name == "preview-data.json" ||
                (it.name.startsWith("preview-") && !it.name.startsWith("preview-v2-") && it.name.endsWith(".json")))
        }
        val content = File(context.filesDir, "offline-content").listFiles().orEmpty().any {
            it.isFile && !it.name.startsWith("v2-") && it.name.endsWith("-index.json")
        }
        val media = File(context.filesDir, "media-cache").listFiles().orEmpty().any {
            it.isFile && !it.name.startsWith("v2-")
        }
        // 仅暴露是否存在旧缓存，不把其它服务器的条目或账号信息显示在当前界面。
        return preview || content || media
    }
}
