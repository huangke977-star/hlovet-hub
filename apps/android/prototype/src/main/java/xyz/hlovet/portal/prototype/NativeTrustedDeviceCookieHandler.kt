package xyz.hlovet.portal.prototype

import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI
import java.util.Locale

internal const val NATIVE_TRUSTED_DEVICE_COOKIE = "hlovet_trusted_device"
private const val MAX_DEVICE_COOKIE_SECONDS = 365L * 24 * 60 * 60
private val deviceCookieValue = Regex("^[A-Za-z0-9_-]{32,128}$")

internal data class NativeTrustedDeviceCredential(val value: String, val expiresAtMillis: Long, val secure: Boolean)

internal interface NativeTrustedDevicePersistence {
    fun load(origin: String): NativeTrustedDeviceCredential?
    fun save(origin: String, credential: NativeTrustedDeviceCredential?)
}

internal fun nativeDeviceCookieOrigin(uri: URI): String? {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (scheme !in listOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return null
    val port = if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port
    if (port !in 1..65535) return null
    return URI(scheme, null, host, port, "/", null, null).toASCIIString()
}

/** Only the server-issued device cookie is shared by native API requests. */
internal class NativeTrustedDeviceCookieHandler(
    apiUri: URI,
    private val persistence: NativeTrustedDevicePersistence,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieHandler() {
    val origin = requireNotNull(nativeDeviceCookieOrigin(apiUri)) { "Invalid native API origin" }
    private val host = apiUri.host
    private var credential = persistence.load(origin)?.takeIf { saved ->
        deviceCookieValue.matches(saved.value) && saved.expiresAtMillis > now() &&
            (!saved.secure || apiUri.scheme.equals("https", ignoreCase = true))
    }

    init {
        if (credential == null) persistence.save(origin, null)
    }

    @Synchronized
    override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> {
        if (nativeDeviceCookieOrigin(uri) != origin) return emptyMap()
        val current = credential ?: return emptyMap()
        if (current.expiresAtMillis <= now()) {
            credential = null
            persistence.save(origin, null)
            return emptyMap()
        }
        if (current.secure && !uri.scheme.equals("https", ignoreCase = true)) return emptyMap()
        return mapOf("Cookie" to listOf("$NATIVE_TRUSTED_DEVICE_COOKIE=${current.value}"))
    }

    @Synchronized
    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
        if (nativeDeviceCookieOrigin(uri) != origin) return
        responseHeaders.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }.forEach { header ->
                val cookies = runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList())
                cookies.filter { it.name == NATIVE_TRUSTED_DEVICE_COOKIE }.forEach cookieLoop@ { cookie ->
                    if (cookie.path != null && cookie.path != "/") return@cookieLoop
                    if (cookie.domain != null && !cookie.domain.trimStart('.').equals(host, ignoreCase = true)) return@cookieLoop
                    if (cookie.secure && !uri.scheme.equals("https", ignoreCase = true)) return@cookieLoop
                    if (cookie.maxAge == 0L) {
                        credential = null
                        persistence.save(origin, null)
                    } else if (deviceCookieValue.matches(cookie.value)) {
                        val expires = if (cookie.maxAge < 0) Long.MAX_VALUE else
                            now() + cookie.maxAge.coerceAtMost(MAX_DEVICE_COOKIE_SECONDS) * 1_000
                        val accepted = NativeTrustedDeviceCredential(cookie.value, expires, cookie.secure)
                        credential = accepted
                        // A session cookie must not survive a process restart.
                        persistence.save(origin, accepted.takeUnless { cookie.maxAge < 0 })
                    }
                }
            }
    }
}
