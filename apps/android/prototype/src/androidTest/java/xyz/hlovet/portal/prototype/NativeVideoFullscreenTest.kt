package xyz.hlovet.portal.prototype

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用真实竖屏 MP4 与系统窗口验证全屏往返，不用元数据或模拟播放器替代实际画面。 */
@RunWith(AndroidJUnit4::class)
class NativeVideoFullscreenTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private var oldLocale = "zh-CN"
    private var downloads = 0
    private val progressPattern = Regex("^(\\d+):(\\d{2}) / 0:24$")
    private val progressMatcher = SemanticsMatcher("Current video progress") { node ->
        // 当前控件必须展示真实总时长，读取中和背景窗口不能冒充可操作播放状态。
        node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any { progressPattern.matches(it.text) }
    }

    /** 创建独立文件和服务，测试不读取账号密码、不请求生产接口。 */
    @Before fun start() {
        // 24 秒绿色竖屏资产只打包在测试 APK，每次证据目录独立以保留失败截图。
        directory = File(compose.activity.filesDir, "native-fullscreen-${UUID.randomUUID()}").apply { mkdirs() }
        server = MockWebServer().apply { start() }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("inline-portrait-test-pattern.mp4").use { it.readBytes() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "video/mp4"))
        oldLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
    }

    /** 回收夹具连接与界面，保留文件、屏幕证据和业务会话。 */
    @After fun stop() {
        // 先分离界面以释放播放器，再停止服务，不能让迟到回调进入下个用例。
        host.dispose()
        compose.runOnUiThread { NativeI18n.applyLocale(oldLocale) }
        server.shutdown()
    }

    /** 播放中的视频全屏往返继续推进，真实画面保持竖屏比例且只下载一次。 */
    @Test fun playingVideoKeepsProgressAndAspectAcrossFullscreen() {
        // 行内先实际播放到正进度，再切换真实 Dialog 窗口。
        openVideo()
        waitForProgress()
        val before = progressSeconds()
        assertPortraitFrame("inline-playing")
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.onNodeWithTag("native-video-fullscreen-801").assertIsDisplayed()
        assertTrue("Fullscreen reset the displayed progress", progressSeconds() >= before)
        compose.waitUntil(10000) { compose.mainClock.advanceTimeBy(250); progressSeconds() > before }
        compose.onNodeWithContentDescription("暂停媒体").assertIsDisplayed()
        assertPortraitFrame("fullscreen-playing")
        // 退出图标返回同一播放位置，不重新下载或从零开始。
        val fullscreenPosition = progressSeconds()
        compose.onNodeWithContentDescription("退出全屏").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("native-video-fullscreen-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        assertTrue("Returning inline reset the displayed progress", progressSeconds() >= fullscreenPosition)
        compose.waitUntil(10000) { compose.mainClock.advanceTimeBy(250); progressSeconds() > fullscreenPosition }
        compose.onNodeWithContentDescription("暂停媒体").assertIsDisplayed()
        assertPortraitFrame("inline-return-playing")
        assertEquals(1, server.requestCount)
    }

    /** 暂停状态进入全屏仍暂停，系统返回保持位置并恢复行内控制。 */
    @Test fun pausedVideoStaysPausedAndSystemBackReturnsInline() {
        // 使用真实暂停按钮，再核对新 Surface 仍输出暂停帧而不是自动播放。
        openVideo()
        waitForProgress()
        compose.onNodeWithContentDescription("暂停媒体").performClick()
        val before = progressSeconds()
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.onNodeWithTag("native-video-fullscreen-801").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停媒体").assertDoesNotExist()
        assertPortraitFrame("fullscreen-paused")
        // 返回值只代表一秒内收到无障碍事件；实际成功以窗口关闭和行内状态为准。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("native-video-fullscreen-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithContentDescription("全屏播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        assertEquals(before, progressSeconds())
        assertPortraitFrame("inline-return-paused")
        assertEquals(1, server.requestCount)
    }

    /** 全屏退到后台后暂停，前台恢复不自动开始，也不再次下载。 */
    @Test fun fullscreenBackgroundDoesNotAutoResume() {
        // 覆盖全屏窗口的真实 Activity 生命周期，而不是直接调用播放器暂停。
        openVideo()
        waitForProgress()
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.onNodeWithContentDescription("暂停媒体").assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("native-video-fullscreen-801").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停媒体").assertDoesNotExist()
        assertEquals(1, server.requestCount)
    }

    /** 全屏切换账号时关闭旧窗口并释放旧播放器，新身份不会接续或预取旧媒体。 */
    @Test fun accountChangeClosesFullscreenWithoutContinuingMedia() {
        // 同一地址保持不变，只变身份以验证真正的账号隔离键。
        val token = mutableStateOf<String?>("local-fixture-old")
        openVideo(token)
        waitForProgress()
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.onNodeWithTag("native-video-fullscreen-801").assertIsDisplayed()
        compose.runOnIdle { token.value = "local-fixture-new" }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("native-video-fullscreen-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停媒体").assertDoesNotExist()
        compose.onNodeWithText("0:00 / 0:00").assertIsDisplayed()
        assertEquals(1, server.requestCount)
    }

    /** 全屏下载退出播放窗口并交给原面板回调，不让面板被全屏遮住。 */
    @Test fun fullscreenDownloadReturnsToInlineAndPauses() {
        // 下载回调只负责原文件操作，播放器不得继续隐形播放。
        openVideo()
        waitForProgress()
        compose.onNodeWithContentDescription("全屏播放").performClick()
        compose.onNodeWithContentDescription("下载附件").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("native-video-fullscreen-801").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
        compose.onNodeWithContentDescription("播放媒体").assertIsDisplayed()
        assertEquals(1, downloads)
        assertEquals(1, server.requestCount)
    }

    /** 用 token 身份挂载实际共享组件并手动播放，服务地址在测试线程解析。 */
    private fun openVideo(token: MutableState<String?> = mutableStateOf(null)) {
        // 夹具来源独立，不使用业务令牌或修改当前存储会话。
        val attachment = NativeChatAttachmentPreview(801, 0, "video", "portrait.mp4", "video/mp4", 1, server.url("/portrait.mp4").toString(), null, "")
        val owner = "fullscreen-fixture-${UUID.randomUUID()}"
        host.setContent { HlovetTheme { NativeInlineMediaAttachment(attachment, token.value, owner, { downloads++ }) } }
        compose.onNodeWithContentDescription("播放媒体").performClick()
    }

    /** 等到真实解码进度为正，不要求忙碌设备恰好采样某一秒。 */
    private fun waitForProgress() {
        // 推进 Compose 测试帧，网络、系统解码器与状态展示仍各自真实执行。
        compose.waitUntil(20000) { compose.mainClock.advanceTimeBy(250); progressSeconds() > 0 }
    }

    /** 返回当前可见控件秒数，未就绪或无语义根时返回零继续等待。 */
    private fun progressSeconds(): Int {
        // 全屏期间背景控件被移出语义树，只读取当前窗口的真实进度。
        val text = compose.onAllNodes(progressMatcher).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { it.config[SemanticsProperties.Text] }.firstOrNull { progressPattern.matches(it.text) }?.text ?: return 0
        val match = progressPattern.matchEntire(text) ?: return 0
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    /** 抓取 stage 实际窗口并核对绿色画面和比例，不能仅用播放器宽高字段作为证据。 */
    private fun assertPortraitFrame(stage: String) {
        // 像素范围排除黑边和控件；绿色测试视频应为 2:3，允许采样步长误差。
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("native-video-surface").assertIsDisplayed()
        val frame = compose.onNodeWithTag("native-video-surface").fetchSemanticsNode().boundsInRoot
        File(directory, "$stage-bounds.txt").writeText("left=${frame.left},top=${frame.top},width=${frame.width},height=${frame.height}")
        // Compose 时钟不能推进系统解码器，等待真实合成像素；超时也保留最后的黑屏证据。
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        var pixels = 0
        try {
            compose.waitUntil(10000) {
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                left = bitmap.width; top = bitmap.height; right = 0; bottom = 0; pixels = 0
                for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.green(color) > 80 && Color.green(color) > Color.red(color) * 2 && Color.green(color) > Color.blue(color) * 2) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                        pixels++
                    }
                }
                File(directory, "$stage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                pixels > 100
            }
        } catch (timeout: ComposeTimeoutException) {
            // 限时内没有真实画面仍然失败，不能以等待结束或正确的元数据代替像素。
            throw AssertionError("Video window is blank: $stage", timeout)
        }
        assertTrue("Video window is blank: $stage", pixels > 100)
        assertEquals("Portrait video is stretched or cropped: $stage", 2f / 3f, (right - left + 8).toFloat() / (bottom - top + 8), .04f)
    }
}
