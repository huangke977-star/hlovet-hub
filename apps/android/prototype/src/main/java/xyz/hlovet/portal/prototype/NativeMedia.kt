/** 提供原生上传读取、站点来源与媒体展示；认证域名以实际构建配置为唯一来源。 */
package xyz.hlovet.portal.prototype

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.OpenableColumns
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun readNativeUploadFile(context: Context, uri: Uri, maxBytes: Long): NativeUploadFile {
    val resolver = context.contentResolver
    var name = nativePhrase("媒体文件", "Media file")
    var reportedSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex)
        }
    }
    val sizeError = nativePhrase("$name 超过文件大小限制。", "$name exceeds the file size limit.")
    if (reportedSize > maxBytes) throw IllegalStateException(sizeError)
    val bytes = try {
        resolver.openInputStream(uri)?.use { readNativeUploadBytes(it, maxBytes) }
            ?: throw IllegalStateException(nativePhrase("无法读取 $name。", "Could not read $name."))
    } catch (_: NativeUploadLimitExceededException) {
        throw IllegalStateException(sizeError)
    }
    if (bytes.isEmpty()) throw IllegalStateException(nativePhrase("$name 是空文件。", "$name is empty."))
    return NativeUploadFile(name, resolver.getType(uri).orEmpty(), bytes)
}

internal fun readNativeChatUploadFile(context: Context, uri: Uri): NativeUploadFile {
    val resolver = context.contentResolver
    var name = nativePhrase("附件", "Attachment")
    var reportedSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex)
        }
    }
    if (nativeChatAttachmentIsBlocked(name)) {
        throw IllegalStateException(nativeChatAttachmentBlockedError(name))
    }
    val mimeType = resolver.getType(uri).orEmpty()
    val maxBytes = nativeChatAttachmentLimitBytes(mimeType, name)
    val sizeError = nativeChatAttachmentSizeError(name, mimeType)
    if (reportedSize > maxBytes) throw IllegalStateException(sizeError)
    val bytes = try {
        resolver.openInputStream(uri)?.use { readNativeUploadBytes(it, maxBytes) }
            ?: throw IllegalStateException(nativePhrase("无法读取 $name。", "Could not read $name."))
    } catch (_: NativeUploadLimitExceededException) {
        throw IllegalStateException(sizeError)
    }
    if (bytes.isEmpty()) throw IllegalStateException(nativePhrase("$name 是空文件。", "$name is empty."))
    return NativeUploadFile(name, mimeType, bytes)
}

internal fun nativeMediaUrl(path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val apiBase = BuildConfig.API_BASE_URL.trimEnd('/')
    val origin = nativePortalBaseUrl()
    return when {
        path.startsWith("/api/") -> "$origin$path"
        // API responses expose avatar, cover, article and attachment paths
        // relative to the API root, even when the path does not contain /api.
        path.startsWith('/') -> "$apiBase$path"
        else -> "$apiBase/${path.trimStart('/')}"
    }
}

private const val DEFAULT_NATIVE_PORTAL_BASE_URL = "https://5200918.xyz"

/**
 * The API property is also the source of truth for relative portal/media URLs.
 * Keeping this here prevents a second hard-coded production origin in the
 * native client when a preview build points at another server.
 */
internal fun nativePortalBaseUrl(): String {
    val configured = BuildConfig.PORTAL_BASE_URL.trim().trimEnd('/').ifBlank {
        BuildConfig.API_BASE_URL.trim().trimEnd('/')
    }
    if (configured.isBlank()) return DEFAULT_NATIVE_PORTAL_BASE_URL
    val origin = configured.substringBefore("/api", missingDelimiterValue = configured).trimEnd('/')
    return origin.ifBlank { DEFAULT_NATIVE_PORTAL_BASE_URL }
}

/** 返回实际配置的唯一站点主机，用于站内路由与认证回调；不隐式信任未部署的域名别名。 */
internal fun nativePortalHostnames(): Set<String> {
    // 与构建清单使用同一来源，避免系统只认证主域名而应用额外接收未认证别名的回调。
    val host = runCatching { Uri.parse(nativePortalBaseUrl()).host?.lowercase() }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "5200918.xyz"
    return setOf(host)
}

@Composable
internal fun NativeCoverImage(
    path: String?,
    modifier: Modifier = Modifier,
    fallbackText: String,
    tint: Color = HlovetUi.accent,
    accessToken: String? = null,
    cacheOwner: String = "public",
    offlineOnly: Boolean = false,
) {
    val context = LocalContext.current
    var bitmap by remember(path, accessToken, cacheOwner, offlineOnly) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path, accessToken, cacheOwner, offlineOnly) {
        bitmap = path?.takeIf(String::isNotBlank)?.let { mediaPath ->
            withContext(Dispatchers.IO) {
                NativeOfflineCache.loadBitmap(
                    context = context,
                    url = nativeMediaUrl(mediaPath),
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    offlineOnly = offlineOnly,
                )
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = .14f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(fallbackText.takeLast(2), color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun NativeArticleImage(
    path: String,
    modifier: Modifier = Modifier,
    accessToken: String? = null,
    cacheOwner: String = "public",
    offlineOnly: Boolean = false,
) {
    val context = LocalContext.current
    var bitmap by remember(path, accessToken, cacheOwner) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path, accessToken, cacheOwner) {
        bitmap = withContext(Dispatchers.IO) {
                NativeOfflineCache.loadBitmap(
                    context = context,
                    url = nativeMediaUrl(path),
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    offlineOnly = offlineOnly,
                )
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HlovetUi.surfaceLow),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = nativePhrase("文章图片", "Article image"),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                nativePhrase("图片暂不可用", "Image unavailable"),
                color = HlovetUi.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
