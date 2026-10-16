package xyz.hlovet.portal.prototype

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.net.URI

/** 根据 requestUrl 在 attachments 中查找准确媒体引用，apiBaseUrl 是唯一可接收凭据的服务器来源。 */
internal fun nativeEditorAttachmentForUrl(
    requestUrl: String,
    attachments: List<NativeArticleAttachment>,
    apiBaseUrl: String = BuildConfig.API_BASE_URL,
): NativeArticleAttachment? {
    // 只容许受控本地页面的相对引用或当前 API 的准确地址，不放行相似域名、端口或路径。
    val request = runCatching { URI(requestUrl) }.getOrNull() ?: return null
    if (request.rawUserInfo != null || request.rawFragment != null) return null
    val localPage = request.scheme == "https" && request.host in setOf("editor.invalid", "preview.invalid") && request.port == -1
    if (!localPage && !nativeMediaMayReceiveToken(requestUrl, apiBaseUrl)) return null
    for (attachment in attachments) {
        if (attachment.kind !in setOf("image", "audio", "video")) continue
        val declared = runCatching { URI(attachment.downloadUrl) }.getOrNull() ?: continue
        val absolute = if (declared.isAbsolute) declared.toString() else if (declared.path.startsWith("/api/")) {
            val base = URI(apiBaseUrl)
            "${base.scheme}://${base.rawAuthority}${attachment.downloadUrl}"
        } else "${apiBaseUrl.trimEnd('/')}/${attachment.downloadUrl.trimStart('/')}"
        if (!nativeMediaMayReceiveToken(absolute, apiBaseUrl)) continue
        val canonical = URI(absolute)
        val expectedPath = URI(apiBaseUrl).path.trimEnd('/') + "/articles/attachments/${attachment.id}/download"
        if (canonical.path != expectedPath || canonical.rawQuery != null) continue
        if (request.path == (if (localPage) declared.path else canonical.path) && request.rawQuery == null) return attachment
    }
    // 正文里手动加入其它接口或站外媒体时不发请求，也不携带用户令牌。
    return null
}

/** 为编辑器和预览加载当前文章媒体；context 存储缓存，session 与 attachments 提供实时账号和附件状态。 */
internal open class NativeEditorAttachmentWebViewClient(
    private val context: Context,
    private val session: () -> NativeSession?,
    private val attachments: () -> List<NativeArticleAttachment>,
) : WebViewClient() {
    /** 拦截 request 的子资源；仅本地生成的主文档交给 WebView 解码，其余只读取已确认媒体。 */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        // loadDataWithBaseURL 实际以 data 主文档加载，放行不产生网络请求；正文导航仍统一拒绝。
        if (request?.isForMainFrame == true && request.url.scheme == "data") return null
        if (request?.isForMainFrame != false) return blocked()
        // 命中准确附件后复用既有有界下载和按账号隔离的缓存，不把令牌放到 HTML URL 中。
        val attachment = nativeEditorAttachmentForUrl(request.url.toString(), attachments()) ?: return blocked()
        val currentSession = session()
        val maxBytes = nativeArticleAttachmentLimitBytes(attachment.mimeType, 8L * 1024 * 1024, prepared = true)
        val file = NativeOfflineCache.loadMediaFile(
            context = context, url = attachment.downloadUrl, accessToken = currentSession?.accessToken,
            cacheOwner = NativeOfflineOwner.forSession(currentSession), originalName = attachment.originalName,
            mimeType = attachment.mimeType, maxBytes = maxBytes,
        ) ?: return blocked()
        // 使用服务器附件 MIME，使无扩展名 download 路径的图片和播放器仍能正确解码。
        return runCatching { WebResourceResponse(attachment.mimeType, null, FileInputStream(file)) }.getOrElse { blocked() }
    }

    /** 拒绝 url 引起的页面跳转，编辑器不能变成携带 JavaScript 桥接的外部浏览器。 */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        // 工具栏和正文只能在当前本地编辑页中操作。
        return true
    }

    /** 返回空的 403 子资源响应，未知地址不会绕过原生允许列表。 */
    private fun blocked(): WebResourceResponse {
        // 禁止回退到 WebView 的默认联网路径。
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    }
}
