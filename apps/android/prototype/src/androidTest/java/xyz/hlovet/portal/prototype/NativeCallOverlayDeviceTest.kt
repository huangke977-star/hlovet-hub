package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** 在真实窗口中验收通话面板/小窗和时钟，不伪造 Socket 或宣称双设备媒体已通过。 */
@RunWith(AndroidJUnit4::class)
class NativeCallOverlayDeviceTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val call = mutableStateOf<NativeCallSession?>(null)
    private val phase = mutableStateOf("active")
    private val muted = mutableStateOf(false)
    private val canSwitchCamera = mutableStateOf(false)
    private var cameraSwitches = 0
    private var previousLocale = "zh-CN"
    private var ended = 0
    private var declined = 0
    private var accepted = 0
    private var mutedCount = 0
    private var underlyingClicks = 0

    /** 仅切换运行时语言，不写入或替换任何登录会话。 */
    @Before fun prepare() {
        // 固定中文用于主要交互断言，英文用例另行切换并在结束时恢复。
        previousLocale = NativeI18n.locale
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
    }

    /** 移除本轮窗口并恢复原语言，证据图片保留在应用私有目录。 */
    @After fun finish() {
        // Dialog 和 Popup 必须在测试 Activity 关闭前移除，不能遗留空弹层影响下一项。
        compose.runOnIdle { call.value = null }
        compose.waitForIdle()
        compose.runOnUiThread { NativeI18n.applyLocale(previousLocale) }
    }

    /** 收起后底层页面可操作，恢复不切换媒体开关，小窗挂断只触发一次业务回调。 */
    @Test fun minimizeBrowseRestoreAndEndWithoutChangingMedia() {
        // 使用生产展示组件，不通过假回调直接切换其内部最小化状态。
        open()
        compose.onNodeWithContentDescription("静音").performClick()
        assertEquals(1, mutedCount)
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitTag("native-call-mini")
        compose.onNodeWithTag("call-underlying-action").performClick()
        assertEquals(1, underlyingClicks)
        assertEquals(0, ended)
        assertEquals(0, declined)
        capture("minimized")
        compose.onNodeWithTag("native-call-mini").performClick()
        waitDescription("打开麦克风")
        assertEquals(1, mutedCount)
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitTag("native-call-mini")
        compose.onNodeWithContentDescription("结束通话").performClick()
        waitAbsentTag("native-call-mini")
        assertEquals(1, ended)
        assertEquals(0, accepted)
    }

    /** 活跃通话时钟在小窗中继续递增，返回面板后不能重新从零计时。 */
    @Test fun elapsedTimeKeepsAdvancingAcrossMinimizeAndRestore() {
        // 接听时间比当前早 65 秒，避免从零开始的假计时也能通过。
        open()
        val first = elapsedSeconds()
        assertTrue(first >= 65)
        compose.onNodeWithContentDescription("最小化通话").performClick()
        waitTag("native-call-mini")
        compose.waitUntil(6_000) { elapsedSeconds() > first }
        val minimized = elapsedSeconds()
        capture("clock-running")
        compose.onNodeWithTag("native-call-mini").performClick()
        waitDescription("最小化通话")
        assertTrue(elapsedSeconds() >= minimized)
        assertEquals(0, ended)
    }

    /** 来电不能被返回键或收起按钮隐藏，接听和拒绝仍由明确动作触发。 */
    @Test fun incomingRequiresExplicitDecisionAndNoFakeClock() {
        // 未接听的电话不能显示时长，也不能误发送挂断操作。
        open(initialPhase = "incoming", acceptedAt = null)
        compose.onNodeWithContentDescription("最小化通话").assertDoesNotExist()
        compose.onNodeWithTag("native-call-duration").assertDoesNotExist()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        waitDescription("接听通话")
        assertEquals(0, ended)
        compose.onNodeWithContentDescription("拒绝通话").performClick()
        compose.waitForIdle()
        assertEquals(1, declined)
        assertEquals(0, ended)
        assertNull(call.value)
    }

    /** 单摄视频仍可静音、开关摄像头和挂断，只有存在备用采集源时才允许发送切换动作。 */
    @Test fun cameraSwitchAvailabilityDoesNotDisableOtherCallControls() {
        // 直接验收生产展示组件的能力参数，不以测试空回调假装单摄设备能切换。
        open(type = "video")
        compose.onNodeWithContentDescription("切换摄像头").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭摄像头").assertIsEnabled()
        compose.onNodeWithContentDescription("静音").assertIsEnabled()
        compose.onNodeWithContentDescription("结束通话").assertIsEnabled()
        assertEquals(0, cameraSwitches)
        assertEquals(0, ended)

        // 媒体宿主报告双摄能力后同一窗口恢复按钮，并且一次点击只产生一次切换。
        compose.runOnIdle { canSwitchCamera.value = true }
        compose.onNodeWithContentDescription("切换摄像头").assertIsEnabled().performClick()
        assertEquals(1, cameraSwitches)
        assertEquals(0, ended)
        capture("camera-switch-availability")
    }

    /** 非来电阶段返回键收起；新通话编号重置展示状态且不继承旧计时。 */
    @Test fun backMinimizesButNewCallResetsPresentation() {
        // 下一通呼叫使用新编号和未接听状态，验证小窗状态不是全局残留。
        open()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        waitTag("native-call-mini")
        compose.runOnIdle {
            call.value = requireNotNull(call.value).copy(id = 2, acceptedAt = null)
            phase.value = "outgoing"
        }
        waitDescription("最小化通话")
        compose.onNodeWithTag("native-call-mini").assertDoesNotExist()
        compose.onNodeWithTag("native-call-duration").assertDoesNotExist()
        compose.onNodeWithText("等待接听").assertIsDisplayed()
        assertEquals(0, ended)
    }

    /** 英文界面的小窗限制长名称为单行，恢复和挂断保持正确语义。 */
    @Test fun englishLongNameFitsAndMissingAcceptedTimeStaysUntimed() {
        // 没有合法接听时间时只展示连接状态，不生成看似准确的假时长。
        compose.runOnUiThread { NativeI18n.applyLocale("en-US") }
        open(acceptedAt = "invalid-time", userName = "A very long participant name that must not push the end-call button outside the window")
        compose.onNodeWithTag("native-call-duration").assertDoesNotExist()
        compose.onNodeWithContentDescription("Minimize call").performClick()
        waitTag("native-call-mini")
        compose.onNodeWithContentDescription("Return to call").assertIsDisplayed()
        compose.onNodeWithContentDescription("End call").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("native-call-mini-name", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        capture("english-long-name")
        compose.onNodeWithTag("native-call-mini").performClick()
        waitDescription("Minimize call")
        assertEquals(0, ended)
    }

    /** 无对方名称时，存活面板、小窗和缺省头像随当前语言更新，不改变通话编号或计时。 */
    @Test fun missingIdentityUsesCurrentLocaleAcrossPanelAndMini() {
        // 缺省名称不由测试预先翻译，直接验收生产展示组件的语言兜底。
        open(userName = "")
        compose.onNodeWithTag("native-call-peer-name").assertTextEquals("聊天用户")
        compose.onNodeWithText("用户").assertIsDisplayed()
        compose.onNodeWithTag("native-call-peer-avatar").assertWidthIsEqualTo(54.dp).assertHeightIsEqualTo(54.dp)
        compose.runOnUiThread { NativeI18n.applyLocale("en-US") }
        compose.onNodeWithTag("native-call-peer-name").assertTextEquals("Chat user")
        compose.onNodeWithText("er").assertIsDisplayed()
        // 最小化后仍读取同一空身份，不能继承解析时固定的中文兜底名称。
        compose.onNodeWithContentDescription("Minimize call").performClick()
        waitTag("native-call-mini")
        compose.onNodeWithTag("native-call-mini-name", useUnmergedTree = true).assertTextEquals("Chat user")
        compose.runOnUiThread { NativeI18n.applyLocale("zh-CN") }
        compose.onNodeWithTag("native-call-mini-name", useUnmergedTree = true).assertTextEquals("聊天用户")
        capture("localized-missing-identity")
        assertEquals(0, ended)
    }

    /** 真正下载并绘制对方头像，失效地址回到名称后两字且不残留之前已解码的图片。 */
    @Test fun counterpartAvatarRendersAndFailedReplacementUsesInitials() {
        // 使用唯一回环地址和可识别像素，不能以缺省头像或网络请求出现代替实际图片显示。
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEB2861.toInt()) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
            val path = server.url("/call-avatar-${System.nanoTime()}.png").toString()
            open(userName = "头像测试", avatarUrl = path)
            compose.waitUntil(12_000) {
                val pixels = compose.onNodeWithTag("native-call-peer-avatar").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                center.red > .85f && center.green < .25f && center.blue > .25f
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            compose.onNodeWithText("测试").assertDoesNotExist()
            capture("avatar-loaded")
            // 切换为失败地址必须清掉旧图，复用共享图片组件的缺省行为而非通话专属占位图。
            server.enqueue(MockResponse().setResponseCode(404))
            compose.runOnIdle { call.value = requireNotNull(call.value).copy(userAvatarUrl = server.url("/missing-${System.nanoTime()}.png").toString()) }
            compose.waitForIdle()
            compose.waitUntil(5_000) { server.requestCount == 2 }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            compose.onNodeWithText("测试").assertIsDisplayed()
            capture("avatar-unavailable")
        } finally {
            // 只释放本用例 HTTP 夹具，登录会话、离线缓存和业务记录不清理。
            server.shutdown()
        }
    }

    /** 用 initialPhase、acceptedAt、userName、avatarUrl 和 type 构造展示状态；媒体回调仅计数，不访问业务服务器。 */
    private fun open(initialPhase: String = "active", acceptedAt: String? = Instant.ofEpochMilli(System.currentTimeMillis() - 65_000).toString(), userName: String = "通话测试用户", avatarUrl: String? = null, type: String = "voice") {
        // 接入真实窗口而非静态截图；底层按钮用来验证 Popup 不拦截页面操作。
        call.value = NativeCallSession(1, 70, type, initialPhase, 1, 2, 2, userName, "call_fixture", acceptedAt, avatarUrl)
        phase.value = initialPhase
        compose.setContent {
            HlovetTheme {
                Box(Modifier.fillMaxSize()) {
                    TextButton(onClick = { underlyingClicks += 1 }, modifier = Modifier.testTag("call-underlying-action")) { Text("Underlying page action") }
                    call.value?.let { current ->
                        NativeCallOverlay(current, phase.value, false, null, null, null, muted.value, true,
                            onAccept = { accepted += 1 }, onDecline = { declined += 1; call.value = null },
                            onEnd = { ended += 1; call.value = null },
                            onToggleMute = { muted.value = !muted.value; mutedCount += 1 }, onToggleCamera = {},
                            onSwitchCamera = { cameraSwitches += 1 }, canSwitchCamera = canSwitchCamera.value)
                    }
                }
            }
        }
        waitDescription(if (initialPhase == "incoming") nativePhrase("接听通话", "Answer call") else nativePhrase("最小化通话", "Minimize call"))
    }

    /** 等待 tag 标识的真实窗口控件出现，避免窗口创建尚未完成就发送操作。 */
    private fun waitTag(tag: String) {
        // 使用实际语义树，不通过固定坐标猜测 Popup 是否存在。
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 description 对应的无障碍动作可见，兼容 Dialog 和 Popup 的独立窗口。 */
    private fun waitDescription(description: String) {
        // 所有图标按钮都通过用户能识别的动作名称驱动。
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 tag 从全部窗口消失，用于确认挂断后不再拦截或覆盖页面。 */
    private fun waitAbsentTag(tag: String) {
        // 挂断的业务回调和 Compose 窗口移除必须都完成。
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
    }

    /** 读取实际显示的计时文字并转换为秒，供跨窗口连续性比较。 */
    private fun elapsedSeconds(): Long {
        // 小窗整行合并语义，读取未合并的实际文字，不读取组件私有时钟或改变产品无障碍行为。
        val text = compose.onNodeWithTag("native-call-duration", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        return text.split(':').fold(0L) { total, value -> total * 60 + value.toLong() }
    }

    /** 保存 name 对应的整屏截图，便于核对组件配色、长名称和按钮位置。 */
    private fun capture(name: String) {
        // 截图只含虚构展示数据，不读取或导出账号会话。
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-call-overlay-evidence").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "${testName.methodName}-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
