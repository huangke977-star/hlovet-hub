package xyz.hlovet.portal.prototype

import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用系统解码器与真实音视频验证共享播放组件，网络夹具不替代本地业务入口验收。 */
@RunWith(AndroidJUnit4::class)
class NativeInlineMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var directory: File
    private var playback: NativeInlineMediaPlayback? = null
    private var server: MockWebServer? = null
    private var oldLocale = "zh-CN"

    /** 创建独立媒体文件目录，保持当前账号会话与其它测试证据不变。 */
    @Before fun setUp() {
        // 测试媒体来自测试 APK 的自生成静音/测试图资产，不读取用户附件。
        directory = File(compose.activity.cacheDir, "inline-media-${UUID.randomUUID()}").apply { mkdirs() }
        oldLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
    }

    /** 释放真实解码器与独立服务，保留媒体和报告，不清理业务缓存或账号。 */
    @After fun tearDown() {
        // 先分离界面再停服务，避免后台下载和播放器回调跨用例。
        host.dispose()
        compose.runOnUiThread { playback?.release(); NativeI18n.applyLocale(oldLocale) }
        server?.shutdown()
    }

    /** 将测试资产 name 复制到私有文件并返回，供真实 MediaPlayer 解码。 */
    private fun fixture(name: String): File {
        // 测试 APK 资产与业务 APK 分离，正式 APK 不携带测试音视频。
        return File(directory, name).apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    /** 音频实际推进、暂停、跳转和再次播放，释放后不能被迟到回调恢复。 */
    @Test fun audioDecoderAdvancesPausesSeeksAndReleases() {
        // 不用空播放器或假进度模拟真实解码。
        val file = fixture("inline-silence.wav")
        compose.runOnUiThread {
            playback = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(file) }
        }
        waitState { it.ready && it.playing && it.durationMs >= 4000 }
        waitState { it.poll(); it.positionMs > 250 }
        compose.runOnUiThread { playback!!.pause() }
        assertFalse(playback!!.playing)
        compose.runOnUiThread { playback!!.seek(2500); playback!!.toggle() }
        waitState { it.poll(); it.positionMs >= 2500 && it.playing }
        compose.runOnUiThread { playback!!.release(); playback!!.toggle() }
        assertTrue(playback!!.released)
        assertFalse(playback!!.ready)
        assertFalse(playback!!.playing)
    }

    /** 在后台准备完成不自动播放，回到前台也必须手动点击。 */
    @Test fun backgroundPreparationNeverAutoResumes() {
        // 覆盖下载/准备过程中切页，不能只验证已经播放后的暂停。
        val file = fixture("inline-silence.wav")
        compose.runOnUiThread {
            playback = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(file); setForeground(false) }
        }
        waitState { it.ready }
        assertFalse(playback!!.playing)
        compose.runOnUiThread { playback!!.setForeground(true) }
        assertFalse(playback!!.playing)
        compose.runOnUiThread { playback!!.toggle() }
        waitState { it.playing }
    }

    /** 播放完毕归还焦点并保留末尾进度，再次点击从头播放。 */
    @Test fun completedMediaReplaysFromStart() {
        // 完成由系统播放器事件触发，不手动伪造完成状态。
        val file = fixture("inline-silence.wav")
        compose.runOnUiThread {
            playback = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(file) }
        }
        waitState { it.playing }
        waitState { it.ready && !it.playing && it.positionMs == it.durationMs }
        compose.runOnUiThread { playback!!.toggle() }
        waitState { it.poll(); it.playing && it.positionMs < 1000 }
    }

    /** 损坏音频给出错误且可重新准备正确文件，不保留旧的解码或焦点状态。 */
    @Test fun corruptMediaCanRetryWithValidFile() {
        // 独立无效内容不是空请求，确保异常确实来自系统解码器。
        val broken = File(directory, "broken.wav").apply { writeText("not a media stream") }
        compose.runOnUiThread {
            playback = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(broken) }
        }
        waitState { it.error != null }
        assertFalse(playback!!.ready)
        val valid = fixture("inline-silence.wav")
        compose.runOnUiThread { playback!!.prepare(valid) }
        waitState { it.ready && it.playing }
        assertNull(playback!!.error)
    }

    /** 同屏多个媒体竞争真实音频焦点，后一条开始时前一条必须暂停。 */
    @Test fun newAudioFocusPausesPreviousPlayer() {
        // 使用两个独立 MediaPlayer，而不是直接调用前一个暂停方法。
        val file = fixture("inline-silence.wav")
        lateinit var first: NativeInlineMediaPlayback
        compose.runOnUiThread {
            first = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(file) }
            playback = first
        }
        waitState { it.playing }
        var second: NativeInlineMediaPlayback? = null
        try {
            compose.runOnUiThread { second = NativeInlineMediaPlayback(compose.activity, false).apply { setForeground(true); prepare(file) } }
            compose.waitUntil(15000) { second?.playing == true && !first.playing }
        } finally {
            compose.runOnUiThread { second?.release() }
        }
    }

    /** 点击共享音频组件才发请求，暂停不再次下载，下载入口仍可调用。 */
    @Test fun audioComponentUsesManualDownloadAndRealControls() {
        // 服务只处理一份有效 WAV，地址为独立本机夹具，不发送身份头到第三方。
        val bytes = fixture("inline-silence.wav").readBytes()
        val fixtureServer = MockWebServer().also { server = it; it.start() }
        fixtureServer.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "audio/wav"))
        var downloads = 0
        val owner = "fixture-audio-${UUID.randomUUID()}"
        val media = attachment(fixtureServer.url("/audio.wav").toString(), "audio")
        host.setContent {
            HlovetTheme { NativeInlineMediaAttachment(media, null, owner, { downloads++ }) }
        }
        assertEquals(0, fixtureServer.requestCount)
        compose.onNodeWithContentDescription("播放媒体").performClick()
        // 等待推进 Compose 帧后再取服务请求，不能阻塞尚未启动的 LaunchedEffect。
        compose.waitUntil(15000) { fixtureServer.requestCount > 0 }
        assertNotNull(fixtureServer.takeRequest(15, TimeUnit.SECONDS))
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("暂停媒体").performClick()
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        compose.onNodeWithContentDescription("下载附件").performClick()
        assertEquals(1, downloads)
        assertEquals(1, fixtureServer.requestCount)
    }

    /** 真实 MP4 视频解码并输出到行内 Surface，不只是出现视频图标。 */
    @Test fun videoComponentDisplaysDecodedFrameAndPauses() {
        // 先确认资产包含非空视频帧，再通过组件的播放按钮启动真实解码器。
        val file = fixture("inline-test-pattern.mp4")
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            assertNotNull(retriever.getFrameAtTime(1000000))
        }
        val fixtureServer = MockWebServer().also { server = it; it.start() }
        fixtureServer.enqueue(MockResponse().setBody(Buffer().write(file.readBytes())).setHeader("Content-Type", "video/mp4"))
        val owner = "fixture-video-${UUID.randomUUID()}"
        val media = attachment(fixtureServer.url("/video.mp4").toString(), "video")
        host.setContent {
            HlovetTheme { NativeInlineMediaAttachment(media, null, owner, {}) }
        }
        compose.onNodeWithContentDescription("播放媒体").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isNotEmpty() }
        // 按任何正进度验证实际推进，不要求忙碌设备恰好采样到一秒。
        val advanced = SemanticsMatcher("Video progress has advanced") { node ->
            node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any {
                Regex("^0:0[1-6] / 0:06$").matches(it.text)
            }
        }
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeBy(250)
            compose.onAllNodes(advanced).fetchSemanticsNodes().isNotEmpty()
        }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        // 实际窗口必须渲染红色测试画面，不能把 metadata 帧代替 Surface 的输出。
        var coloredPixels = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) < 90 && android.graphics.Color.blue(pixel) < 90) coloredPixels++
        }
        File(directory, "video-playing.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue("Inline video frame is blank", coloredPixels > 100)
        // 短视频在截图期间完成时重新手动播放，随后才检查暂停动作。
        if (compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("播放媒体").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithContentDescription("暂停媒体").performClick()
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
    }

    /** 网络拒绝后提示错误，第二次点击使用新的请求完成播放。 */
    @Test fun failedDownloadCanRetry() {
        // 明确返回 403 而不是永远阻塞，重试仍由用户操作发起。
        val fixtureServer = MockWebServer().also { server = it; it.start() }
        fixtureServer.enqueue(MockResponse().setResponseCode(403))
        fixtureServer.enqueue(MockResponse().setBody(Buffer().write(fixture("inline-silence.wav").readBytes())))
        val owner = "fixture-retry-${UUID.randomUUID()}"
        val media = attachment(fixtureServer.url("/retry.wav").toString(), "audio")
        host.setContent {
            HlovetTheme { NativeInlineMediaAttachment(media, null, owner, {}) }
        }
        compose.onNodeWithContentDescription("播放媒体").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("媒体读取失败，请检查网络后重试。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("播放媒体").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, fixtureServer.requestCount)
    }

    /** 当前身份改变时释放原播放器，新身份不自动接续原音频或重复下载。 */
    @Test fun accountChangeDisposesVisiblePlayback() {
        // 两个身份使用同一附件 URL，以确认身份本身参与播放器和缓存的重建键。
        val fixtureServer = MockWebServer().also { server = it; it.start() }
        fixtureServer.enqueue(MockResponse().setBody(Buffer().write(fixture("inline-silence.wav").readBytes())))
        val media = attachment(fixtureServer.url("/identity.wav").toString(), "audio")
        var token by mutableStateOf("local-test-old")
        val owner = "fixture-identity-${UUID.randomUUID()}"
        host.setContent { HlovetTheme { NativeInlineMediaAttachment(media, token, owner, {}) } }
        compose.onNodeWithContentDescription("播放媒体").performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("暂停媒体").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { token = "local-test-new" }
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停媒体").assertDoesNotExist()
        compose.onNodeWithText("0:00 / 0:00").assertIsDisplayed()
        assertEquals(1, fixtureServer.requestCount)
    }

    /** 根据 url/kind 构造单个独立媒体附件，不伪造用户消息或保存业务数据。 */
    private fun attachment(url: String, kind: String): NativeChatAttachmentPreview {
        // 只有夹具 URL 和文件类型参与展示，不使用任何真实令牌。
        return NativeChatAttachmentPreview(1, 0, kind, "fixture.$kind", if (kind == "video") "video/mp4" else "audio/wav", 1, url, null, "")
    }

    /** 等待 predicate 对当前真实播放器成立，超时视为失败，不用睡眠后的假状态。 */
    private fun waitState(predicate: (NativeInlineMediaPlayback) -> Boolean) {
        // 所有原生播放器状态都在主线程读取或更新。
        compose.waitUntil(20000) {
            var passed = false
            compose.runOnUiThread { passed = playback?.let(predicate) == true }
            passed
        }
    }
}
