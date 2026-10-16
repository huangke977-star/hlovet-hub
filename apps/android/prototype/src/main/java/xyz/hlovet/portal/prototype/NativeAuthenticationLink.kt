package xyz.hlovet.portal.prototype

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal sealed interface NativeAuthenticationLink {
    data class Google(
        val resultToken: String,
        val errorMessage: String? = null,
        val returnTo: String? = null,
    ) : NativeAuthenticationLink
    data class PasswordReset(val token: String) : NativeAuthenticationLink
}

internal fun parseNativeAuthenticationLink(rawUrl: String?): NativeAuthenticationLink? {
    val uri = runCatching { URI(rawUrl?.trim().orEmpty()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    val path = normalizeNativeAuthenticationPath(uri.path)
    val portalLink = scheme == "https" && host in nativePortalHostnames() &&
        uri.userInfo == null && uri.port in setOf(-1, 443)
    val nativeLink = scheme == "hlovet-native" && host == "auth" && uri.userInfo == null && uri.port == -1
    val google = (nativeLink && path == "/google") || (portalLink && path == "/native-auth/google")
    val nativeReset = nativeLink && path == "/password-reset"
    val webReset = portalLink && path == "/forgot-password"
    if (!google && !nativeReset && !webReset) return null

    val query = runCatching {
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], Charsets.UTF_8.name()) to
                URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
    }.getOrNull() ?: return null
    return when {
        google -> NativeAuthenticationLink.Google(
            resultToken = query["result"].orEmpty().trim(),
            errorMessage = (query["error_description"] ?: query["error"]).orEmpty().trim().takeIf { it.isNotBlank() },
            returnTo = query["from"]?.trim()?.takeIf { it.startsWith("/") && !it.startsWith("//") },
        )
        nativeReset || "token" in query -> NativeAuthenticationLink.PasswordReset(query["token"].orEmpty().trim())
        else -> null
    }
}

private fun normalizeNativeAuthenticationPath(rawPath: String?): String {
    val path = rawPath.orEmpty().trimEnd('/').ifBlank { "/" }
    return when {
        path == "/en" -> "/"
        path.startsWith("/en/") -> path.removePrefix("/en")
        else -> path
    }
}
