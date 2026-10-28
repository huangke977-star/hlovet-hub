package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** 使用真实 WebRTC 轨道和 Surface 像素检查换轨、延迟到达及重开画面，不以连接状态替代视频证据。 */
@RunWith(AndroidJUnit4::class)
class NativeCallVideoRendererTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private val sources = mutableListOf<VideoSource>()
    private val tracks = mutableListOf<VideoTrack>()
    private val producers = mutableListOf<java.util.concurrent.ScheduledExecutorService>()
    private val displayedTrack = mutableStateOf<VideoTrack?>(null)
    private val displayed = mutableStateOf(true)
    private val context = mutableStateOf<EglBase.Context?>(null)
    private val extraEgl = mutableListOf<EglBase>()
    private var previousLocale = "zh-CN"

    /** 初始化真实媒体工厂；不启动 MainActivity、不修改登录态，也不访问业务网络。 */
    @Before fun prepare() {
        // 本专项只生成无业务内容的红/绿测试帧，保留截图供独立检查。
        previousLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(compose.activity).createInitializationOptions())
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        context.value = egl.eglBaseContext
    }

    /** 先移除 UI/停止帧生产，再释放轨道、媒体源和 EGL，避免后台帧访问已销毁对象。 */
    @After fun finish() {
        // 等待所有生产线程退出；最后释放上下文，不清理任何应用数据。
        try {
            compose.runOnIdle { displayed.value = false }
            compose.waitForIdle()
        } finally {
            try {
                producers.forEach { it.shutdownNow() }
                producers.forEach { check(it.awaitTermination(5, TimeUnit.SECONDS)) }
                tracks.forEach { it.dispose() }
                sources.forEach { it.dispose() }
                if (::factory.isInitialized) factory.dispose()
                extraEgl.forEach { it.release() }
                if (::egl.isInitialized) egl.release()
            } finally {
                compose.runOnUiThread { NativeI18n.applyLocale(previousLocale) }
            }
        }
    }

    /** 轨道晚于 Surface 到达时必须显示新帧，不能因重新绑定轨道释放仍在使用的渲染器。 */
    @Test fun delayedTrackStartsRenderingOnExistingSurface() {
        // 先建立无轨道的实际 Surface，再让远端轨道到达。
        open()
        val renderer = renderer()
        compose.runOnIdle { displayedTrack.value = track("delayed-red", red = true) }
        assertFrame(renderer, red = true, name = "late-track")
    }

    /** 换轨以及临时空轨道后恢复必须继续显示对应颜色，旧画面不能被当成成功。 */
    @Test fun replacementAndReattachmentKeepRendererAlive() {
        // 同一个 Surface 依次接收红色、绿色和重新接回的红色轨道。
        val red = track("first-red", red = true)
        val green = track("second-green", red = false)
        displayedTrack.value = red
        open()
        val original = renderer()
        assertFrame(original, red = true, name = "initial-red")
        compose.runOnIdle { displayedTrack.value = green }
        assertSame(original, renderer())
        assertFrame(original, red = false, name = "replacement-green")
        compose.runOnIdle { displayedTrack.value = null }
        compose.waitForIdle()
        compose.runOnIdle { displayedTrack.value = red }
        assertSame(original, renderer())
        assertFrame(original, red = true, name = "reattached-red")
    }

    /** 页面重新挂载和共享 EGL 改变都应新建可工作的渲染器，不复用已经释放的 Surface。 */
    @Test fun remountAndEglReplacementCreateWorkingSurface() {
        // 先验证移除/重开，再替换 EGL；颜色取证能排除只找到 View 的误通过。
        displayedTrack.value = track("remount-green", red = false)
        open()
        val first = renderer()
        assertFrame(first, red = false, name = "before-remount")
        compose.runOnIdle { displayed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { displayed.value = true }
        val reopened = renderer(excluding = first)
        assertNotSame(first, reopened)
        assertFrame(reopened, red = false, name = "after-remount")
        val next = EglBase.create().also { extraEgl.add(it) }
        compose.runOnIdle { context.value = next.eglBaseContext }
        val replaced = renderer(excluding = reopened)
        assertNotSame(reopened, replaced)
        assertFrame(replaced, red = false, name = "after-egl-change")
    }

    /** 同一通话中的远端大画面与本地小画面必须都出现在真实屏幕上，不能只在各自 Surface 内有帧。 */
    @Test fun localPreviewAndRemoteVideoAreBothVisibleInSamePanel() {
        // 复用产品的大画面叠小画面结构；两条轨道采用相反颜色，便于检出遮盖或层级错误。
        val remote = track("remote-red", red = true)
        val local = track("local-green", red = false)
        val remoteReady = mutableStateOf(false)
        compose.setContent {
            if (displayed.value) Box(Modifier.size(200.dp)) {
                if (remoteReady.value) NativeWebRtcVideoView(remote, requireNotNull(context.value), mirror = false, modifier = Modifier.fillMaxSize())
                NativeWebRtcVideoView(local, requireNotNull(context.value), mirror = true, overlay = true, modifier = Modifier.align(Alignment.TopEnd).size(64.dp))
            }
        }
        // 真实通话先有本地预览、后收到远端轨道，不能只验证两个 Surface 同时创建的理想顺序。
        assertFrame(renderer(), red = false, name = "local-before-remote")
        compose.runOnIdle { remoteReady.value = true }
        var views = emptyList<SurfaceViewRenderer>()
        compose.waitUntil(10_000) {
            compose.runOnUiThread { views = findRenderers(compose.activity.window.decorView) }
            views.size == 2 && views.all { it.width > 0 && it.holder.surface.isValid }
        }
        val large = views.maxBy { it.width }
        val small = views.minBy { it.width }
        assertFrame(large, red = true, name = "remote-large")
        assertFrame(small, red = false, name = "local-inset")
        assertVisibleTogether(large, small, "panel-composited")
    }

    /** 媒体引擎先释放轨道、随后 Compose 移除视图时必须安全，覆盖挂断时的异步清理顺序。 */
    @Test fun disposedTrackCanBeUnmountedWithoutCrashing() {
        // 模拟产品在 IO 线程关闭媒体后，下一次界面提交才移除视频 View 的顺序。
        val liveTrack = track("ending-red", red = true)
        displayedTrack.value = liveTrack
        open()
        assertFrame(renderer(), red = true, name = "before-dispose")
        tracks.remove(liveTrack)
        liveTrack.dispose()
        compose.runOnIdle { displayed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(findRenderers(compose.activity.window.decorView).isEmpty()) }
    }

    /** 通过生产面板连续收起/恢复视频，检查原轨道仍存活且新建 Surface 继续显示实际帧。 */
    @Test fun minimizedVideoRestoresFramesWithoutDisposingMediaTracks() {
        // 生命周期由真实 Dialog/Popup 驱动；收起不能调用业务挂断，也不能销毁媒体轨道。
        val local = track("overlay-local-green", red = false)
        val remote = track("overlay-remote-red", red = true)
        val call = NativeCallSession(100, 70, "video", "active", 1, 2, 2, "视频通话测试", "video_fixture", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString())
        var ended = 0
        compose.setContent {
            HlovetTheme {
                if (displayed.value) NativeCallOverlay(call, "active", false, local, remote, requireNotNull(context.value), false, true,
                    onAccept = {}, onDecline = {}, onEnd = { ended += 1; displayed.value = false },
                    onToggleMute = {}, onToggleCamera = {}, onSwitchCamera = {})
            }
        }
        var current = overlayRenderers()
        assertFrame(current.maxBy { it.width }, red = true, name = "overlay-initial-remote")
        assertFrame(current.minBy { it.width }, red = false, name = "overlay-initial-local")
        repeat(2) { index ->
            compose.onNodeWithContentDescription("最小化通话").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-call-mini").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.waitUntil(10_000) { windowRenderers().isEmpty() }
            assertEquals(0, ended)
            assertTrue(local.enabled())
            assertTrue(remote.enabled())
            assertEquals(org.webrtc.MediaStreamTrack.State.LIVE, local.state())
            assertEquals(org.webrtc.MediaStreamTrack.State.LIVE, remote.state())
            compose.onNodeWithTag("native-call-mini").performClick()
            val restored = overlayRenderers()
            assertNotSame(current.maxBy { it.width }, restored.maxBy { it.width })
            assertFrame(restored.maxBy { it.width }, red = true, name = "overlay-restored-$index-remote")
            assertFrame(restored.minBy { it.width }, red = false, name = "overlay-restored-$index-local")
            assertVisibleTogether(restored.maxBy { it.width }, restored.minBy { it.width }, "overlay-restored-$index-composited", checkHeader = true)
            current = restored
        }
        // 从恢复后的实际面板挂断，确认整个展示窗口移除且只触发一次结束回调。
        compose.onNodeWithContentDescription("结束通话").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { windowRenderers().isEmpty() }
        assertEquals(1, ended)
    }

    /** 矮窗口必须缩小视频并保留标题和所有控制按钮，不能依赖滚动 Surface 才能操作。 */
    @Test fun shortPanelKeepsVideoHeaderAndControlsVisible() {
        // 使用实际生产面板和两条真实轨道，220dp 高度近似横屏或分屏下的可用空间。
        val local = track("short-local-green", red = false)
        val remote = track("short-remote-red", red = true)
        val call = NativeCallSession(101, 70, "video", "active", 1, 2, 2, "短窗口测试", "short_fixture", null)
        compose.setContent {
            HlovetTheme {
                if (displayed.value) Box(Modifier.size(width = 300.dp, height = 220.dp)) {
                    NativeCallPanel(call, "active", false, "01:05", local, remote, requireNotNull(context.value), false, true,
                        onAccept = {}, onDecline = {}, onEnd = {}, onToggleMute = {}, onToggleCamera = {}, onSwitchCamera = {}, onMinimize = {})
                }
            }
        }
        compose.onNodeWithText("视频通话").assertIsDisplayed()
        compose.onNodeWithText("01:05").assertIsDisplayed()
        for (description in listOf("静音", "关闭摄像头", "切换摄像头", "最小化通话", "结束通话")) {
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
        }
        val views = overlayRenderers()
        assertVisibleTogether(views.maxBy { it.width }, views.minBy { it.width }, "short-panel-composited", checkHeader = true)
    }

    /** 返回所有本测试进程窗口中的 Surface，兼容真实 Dialog 与 Popup，不限 Activity 主窗口。 */
    private fun windowRenderers(): List<SurfaceViewRenderer> {
        // WindowInspector 只检查当前仪器进程，不读取其它应用窗口或用户媒体。
        var result = emptyList<SurfaceViewRenderer>()
        compose.runOnUiThread { result = WindowInspector.getGlobalWindowViews().flatMap(::findRenderers) }
        return result
    }

    /** 等待真实通话 Dialog 的本地、远端两个 Surface 都已创建且尺寸有效。 */
    private fun overlayRenderers(): List<SurfaceViewRenderer> {
        // 两个独立窗口切换时必须重新发现 View，不能继续读取已销毁的旧 Surface。
        var result = emptyList<SurfaceViewRenderer>()
        compose.waitUntil(10_000) {
            result = windowRenderers()
            result.size == 2 && result.all { it.width > 0 && it.height > 0 && it.holder.surface.isValid }
        }
        return result
    }

    /** 把生产视频组件放入真实 Activity，并等待其 Surface 创建完成。 */
    private fun open() {
        // 固定尺寸仅用于可重复像素断言，不替换产品的轨道绑定和渲染逻辑。
        compose.setContent {
            if (displayed.value) NativeWebRtcVideoView(displayedTrack.value, requireNotNull(context.value), mirror = false, modifier = Modifier.size(160.dp))
        }
        renderer()
    }

    /** 创建持续发送红/绿 I420 帧的真实轨道；red 指定颜色，name 只用于轨道标识。 */
    private fun track(name: String, red: Boolean): VideoTrack {
        // 通过 WebRTC CapturerObserver 进入实际渲染管道，不直接向 View 绘图。
        val source = factory.createVideoSource(false).also { sources.add(it) }
        source.capturerObserver.onCapturerStarted(true)
        val producer = Executors.newSingleThreadScheduledExecutor().also { producers.add(it) }
        producer.scheduleAtFixedRate({
            val buffer = JavaI420Buffer.allocate(64, 64)
            val y = if (red) 81 else 145
            val u = if (red) 90 else 54
            val v = if (red) 240 else 34
            val yPlane = buffer.dataY
            val uPlane = buffer.dataU
            val vPlane = buffer.dataV
            while (yPlane.hasRemaining()) yPlane.put(y.toByte())
            while (uPlane.hasRemaining()) uPlane.put(u.toByte())
            while (vPlane.hasRemaining()) vPlane.put(v.toByte())
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
        }, 0, 50, TimeUnit.MILLISECONDS)
        return factory.createVideoTrack(name, source).also { tracks.add(it) }
    }

    /** 返回已创建且尺寸有效的 Surface；excluding 非空时等待替换该旧 View，不把未提交的重组误判为失败。 */
    private fun renderer(excluding: SurfaceViewRenderer? = null): SurfaceViewRenderer {
        // Compose 空闲后仍可能等待 Surface 线程，因此同时核对 holder 和尺寸。
        var result: SurfaceViewRenderer? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { result = findRenderers(compose.activity.window.decorView).firstOrNull() }
            result?.let { it !== excluding && it.width > 0 && it.height > 0 && it.holder.surface.isValid } == true
        }
        return requireNotNull(result)
    }

    /** 从 root 递归查找所有 WebRTC Surface，不存在时返回空列表。 */
    private fun findRenderers(root: View): List<SurfaceViewRenderer> {
        // 检查真实 Android View 树，避免用 Compose 语义标签替代媒体界面。
        if (root is SurfaceViewRenderer) return listOf(root)
        return if (root is ViewGroup) buildList {
            for (index in 0 until root.childCount) addAll(findRenderers(root.getChildAt(index)))
        } else emptyList()
    }

    /** 核对 remote/local 在同一整屏中持续可见，checkHeader 要求标题也实际绘出，保存 name 对应截图。 */
    private fun assertVisibleTogether(remote: SurfaceViewRenderer, local: SurfaceViewRenderer, name: String, checkHeader: Boolean = false) {
        // 两个 Surface 单独取帧成功不能证明同时可见；等待窗口动画稳定，不以过渡帧作为截图证据。
        val remotePosition = IntArray(2)
        val localPosition = IntArray(2)
        val windowOffset = IntArray(2)
        val diagnostic = org.json.JSONObject()
        var visibleSince = 0L
        try { compose.waitUntil(10_000) {
            val captionBounds = if (checkHeader) compose.onNodeWithText("视频通话").fetchSemanticsNode().boundsInWindow else null
            compose.runOnUiThread {
                remote.getLocationOnScreen(remotePosition)
                local.getLocationOnScreen(localPosition)
                // 语义坐标属于当前窗口；与截图比较前补上 Dialog 或 Activity 的屏幕偏移。
                val root = WindowInspector.getGlobalWindowViews().first { findRenderers(it).contains(remote) }
                root.getLocationOnScreen(windowOffset)
                val inWindow = IntArray(2)
                root.getLocationInWindow(inWindow)
                windowOffset[0] -= inWindow[0]
                windowOffset[1] -= inWindow[1]
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val remoteColor = screenshot.getPixel(remotePosition[0] + remote.width / 2, remotePosition[1] + remote.height / 2)
                val localColor = screenshot.getPixel(localPosition[0] + local.width / 2, localPosition[1] + local.height / 2)
                // 几何上存在标题不代表合成像素可见；检测其范围内的主题蓝色文字，覆盖旧系统的整层丢失。
                var captionInk = 0
                if (captionBounds != null) {
                    val left = (captionBounds.left.toInt() + windowOffset[0]).coerceIn(0, screenshot.width)
                    val right = (captionBounds.right.toInt() + windowOffset[0]).coerceIn(left, screenshot.width)
                    val top = (captionBounds.top.toInt() + windowOffset[1]).coerceIn(0, screenshot.height)
                    val bottom = (captionBounds.bottom.toInt() + windowOffset[1]).coerceIn(top, screenshot.height)
                    for (y in top until bottom) for (x in left until right) {
                        val color = screenshot.getPixel(x, y)
                        if (Color.blue(color) > Color.red(color) + 60 && Color.green(color) > Color.red(color) + 30) captionInk += 1
                    }
                }
                val visible = Color.red(remoteColor) > 180 && Color.green(remoteColor) < 70 &&
                    Color.green(localColor) > 180 && Color.red(localColor) < 70 && (!checkHeader || captionInk >= 5)
                diagnostic.put("captionBounds", captionBounds?.toString()).put("windowOffsetX", windowOffset[0]).put("windowOffsetY", windowOffset[1])
                    .put("captionInk", captionInk).put("remoteColor", remoteColor).put("localColor", localColor)
                val now = SystemClock.elapsedRealtime()
                if (!visible) visibleSince = 0L else if (visibleSince == 0L) visibleSince = now
                val stable = visible && now - visibleSince >= 250
                if (stable) {
                    // 只保存实际满足双画面条件的这一帧，不复用较早的单 Surface 截图。
                    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-call-renderer-evidence").apply { mkdirs() }
                    File(directory, "${testName.methodName}-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                stable
            } finally { screenshot.recycle() }
        } } finally {
            // 成功与超时均保存最终整屏和非敏感像素诊断，保留断言未通过时的实际证据。
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-call-renderer-evidence").apply { mkdirs() }
            File(directory, "${testName.methodName}-$name-diagnostic.json").writeText(diagnostic.toString(2))
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "${testName.methodName}-$name-last.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    /** 等待 renderer 中实际像素符合 red 指定的颜色，并保存 name 对应的成功截图。 */
    private fun assertFrame(renderer: SurfaceViewRenderer, red: Boolean, name: String) {
        // 每次 PixelCopy 都读取 Surface 当前帧；换轨必须出现不同颜色，缓存旧帧不能通过。
        var captured: Bitmap? = null
        try {
            compose.waitUntil(10_000) {
                captured?.recycle()
                captured = copyFrame(renderer)
                val lastColor = captured?.getPixel(renderer.width / 2, renderer.height / 2) ?: 0
                if (red) Color.red(lastColor) > 180 && Color.green(lastColor) < 70
                else Color.green(lastColor) > 180 && Color.red(lastColor) < 70
            }
        } finally {
            // 失败同样保留最后读到的 Surface 和整屏；不会记录会话或业务媒体。
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-call-renderer-evidence").apply { mkdirs() }
            captured?.let { bitmap -> File(directory, "${testName.methodName}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "${testName.methodName}-$name-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    /** 从 renderer 的真实 Surface 复制一帧；未生成可复制帧时返回 null。 */
    private fun copyFrame(renderer: SurfaceViewRenderer): Bitmap? {
        // PixelCopy 必须回调成功；请求发出不等同于画面已经显示。
        val bitmap = Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(renderer, bitmap, { result -> status = result; done.countDown() }, Handler(Looper.getMainLooper()))
        check(done.await(5, TimeUnit.SECONDS)) { "视频 Surface 取证回调未到达。" }
        if (status == PixelCopy.SUCCESS) return bitmap
        bitmap.recycle()
        return null
    }
}
