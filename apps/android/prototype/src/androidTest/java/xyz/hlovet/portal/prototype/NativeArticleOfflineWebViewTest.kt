package xyz.hlovet.portal.prototype

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeArticleOfflineWebViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun existingWebViewUpdatesItsMediaReaderWhenOwnerAndOfflineModeChange() {
        val root = File(compose.activity.cacheDir, "offline-webview-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(compose.activity) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val owner = mutableStateOf("user:11")
        val offline = mutableStateOf(true)
        val server = MockWebServer().apply { start() }
        try {
            val png = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                    output.toByteArray()
                }
            }
            val url = server.url("/image.png").toString()
            server.enqueue(MockResponse().setBody(Buffer().write(png)))
            assertNotNull(NativeOfflineCache.loadMediaFile(context, url, null, owner.value, "image.png", "image/png"))
            compose.setContent {
                HlovetTheme {
                    CompositionLocalProvider(LocalContext provides context) {
                        NativeArticleBodyWebView(
                            content = "<p>Saved body</p><img src=\"$url\">", contentFormat = "html",
                            accessToken = null, cacheOwner = owner.value, offlineOnly = offline.value,
                            onPreviewImage = {}, onOpenAttachment = {}, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            lateinit var webView: WebView
            compose.runOnIdle { webView = requireNotNull(findWebView(compose.activity.window.decorView)) }
            compose.waitUntil(10_000) { evaluate(webView, "document.querySelector('img')?.naturalWidth === 1") == "true" }
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setBody(Buffer().write(png)))
            compose.runOnIdle { owner.value = "user:12"; offline.value = false }
            compose.waitUntil(10_000) {
                server.requestCount == 2 && NativeOfflineCache.loadCachedMediaFile(context, url, "user:12") != null &&
                    evaluate(webView, "document.querySelector('img')?.naturalWidth === 1") == "true"
            }

            compose.runOnIdle { owner.value = "user:13"; offline.value = true }
            compose.waitUntil(10_000) {
                evaluate(webView, "document.querySelector('img')?.complete && document.querySelector('img')?.naturalWidth === 0") == "true"
            }
            assertEquals("Offline cache misses must not fall back to a network load", 2, server.requestCount)
            compose.runOnIdle { webView.destroy() }
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("WebView callback timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
