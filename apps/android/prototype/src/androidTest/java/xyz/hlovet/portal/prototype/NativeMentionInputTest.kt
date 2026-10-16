@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package xyz.hlovet.portal.prototype

import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.awaitCancellation
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 在真实 Compose 窗口验证共用提及框的键盘、弹层、字形坐标和组合输入状态。 */
@RunWith(AndroidJUnit4::class)
class NativeMentionInputTest {
    @get:Rule val compose = createComposeRule()
    private var input by mutableStateOf(TextFieldValue(""))
    private var controlledConnection by mutableStateOf<InputConnection?>(null)
    private val candidates = (1..20).map { id ->
        NativeSocialSearchUser(NativeSocialUser(id, "Candidate $id", "candidate$id", null, "", "User"), null, null, null, null, false)
    }

    /** 安装共用组件；top 控制位置，contrastBackdrop 检查透明度，controlledIme 隔离外部键盘的连接竞争。 */
    private fun mount(top: Boolean = false, contrastBackdrop: Boolean = false, controlledIme: Boolean = false) {
        // 候选测试不依赖网络；真实权限及页面集成由本地 MainActivity 专项单独验证。
        compose.setContent {
            HlovetTheme {
                // 组合事务专项只接管平台输入连接，其它用例继续使用设备系统键盘。
                val interceptor = remember {
                    PlatformTextInputInterceptor { request, next ->
                        if (controlledIme) {
                            controlledConnection = request.createInputConnection(EditorInfo())
                            awaitCancellation()
                        } else next.startInputMethod(request)
                    }
                }
                InterceptPlatformTextInput(interceptor) {
                    Column(Modifier.fillMaxSize().background(if (contrastBackdrop) Color.Black else HlovetUi.background).imePadding().padding(16.dp)) {
                        TextButton(onClick = { input = TextFieldValue("") }) { Text("Outside") }
                        if (!top) Spacer(Modifier.weight(1f))
                        NativeMentionTextField(input, { input = it }, if (nativeActiveMention(input) != null) candidates else emptyList(), false, "Message", Modifier.fillMaxWidth())
                        if (top) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    /** 空 @ 显示首项高亮，点击后插入一个可见标签并保留原生编辑光标。 */
    @Test fun opensAtSignAndSelectsTheFirstCandidate() {
        // 使用真实可编辑节点操作，不直接调用提及插入方法。
        mount()
        editor().performTextInput("@")
        compose.onNodeWithTag("mention-option-1").assertIsDisplayed().assertIsSelected().performClick()
        assertEquals("@candidate1 ", input.text)
        assertEquals(TextRange(input.text.length), input.selection)
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        assertEquals(1, layout().layoutInput.text.spanStyles.size)
        screenshot("selected")
    }

    /** 上下箭头带动菜单滚动，回车使用当前高亮项，首尾可循环。 */
    @Test fun keyboardScrollsAllCandidatesAndEnterSelects() {
        // 跨越首屏可见候选，不能只改变不可见项目的选中索引。
        mount()
        editor().performTextInput("@")
        editor().performKeyInput { repeat(16) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag("mention-option-17").assertIsDisplayed().assertIsSelected()
        screenshot("keyboard-scrolled")
        editor().performKeyInput { pressKey(Key.Enter) }
        assertEquals("@candidate17 ", input.text)
        editor().performTextReplacement("@")
        editor().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("mention-option-20").assertIsDisplayed().assertIsSelected()
        editor().performKeyInput { pressKey(Key.Tab) }
        assertEquals("@candidate20 ", input.text)
    }

    /** 手动用户名仅在空格后形成标签，不复制字符，继续中文输入保持同一字形行。 */
    @Test fun manualConfirmationKeepsTextAndCaretExact() {
        // 比较转换后的真实 TextLayoutResult，而非只断言源码包含样式。
        mount()
        editor().performTextInput("@nice3")
        assertTrue(layout().layoutInput.text.spanStyles.isEmpty())
        editor().performTextInput(" ")
        assertEquals("@nice3 ", input.text)
        assertEquals(1, layout().layoutInput.text.spanStyles.size)
        editor().performTextInput("中文正文")
        assertEquals("@nice3 中文正文", input.text)
        assertEquals(TextRange(input.text.length), input.selection)
        assertEquals(input.text, layout().layoutInput.text.text)
        screenshot("manual-chinese")
    }

    /** 在正文中间选择候选不能覆盖光标后的内容。 */
    @Test fun selectingInTheMiddlePreservesTheSuffix() {
        // 用原生设置选区动作定位到 @n 后，再点击弹层中的候选。
        mount()
        editor().performTextInput("prefix @n suffix")
        editor().performSemanticsAction(SemanticsActions.SetSelection) { it(9, 9, false) }
        compose.onNodeWithTag("mention-option-1").performClick()
        assertEquals("prefix @candidate1  suffix", input.text)
        assertEquals(TextRange(19), input.selection)
    }

    /** 组合输入尚未提交时不显示候选，也不改变输入法组合范围。 */
    @Test fun preservesCompositionUntilImeCommits() {
        // 使用 Compose 官方平台输入拦截器提供的连接执行真实编辑命令，不重复创建系统正在使用的连接。
        mount(controlledIme = true)
        editor().performClick()
        editor().performTextInput("@")
        compose.waitUntil(5_000) { controlledConnection != null }
        val connection = requireNotNull(controlledConnection)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(connection.setComposingText("nice", 1))
        }
        compose.waitUntil(5_000) { input.composition == TextRange(1, 5) }
        assertEquals(TextRange(1, 5), input.composition)
        assertEquals("@nice", layout().layoutInput.text.text)
        screenshot("composing")
        // 组合输入期间必须移除候选内容，而不是只让窗口透明或移动到不可见位置。
        assertMenuHidden()
        assertEquals(TextRange(1, 5), input.composition)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { check(connection.commitText("nice ", 1)) }
        compose.waitUntil(5_000) { input.text == "@nice " && input.composition == null }
        assertEquals(1, layout().layoutInput.text.spanStyles.size)
    }

    /** 候选打开时 Shift+Enter 仍为换行，不能误选用户。 */
    @Test fun shiftEnterKeepsTheMultilineEditingAction() {
        // 普通回车选候选，组合键沿用 PC 的换行语义。
        mount()
        editor().performTextInput("@nice")
        editor().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        assertEquals("@nice\n", input.text)
        assertEquals(input.text, layout().layoutInput.text.text)
        assertMenuHidden()
    }

    /** 顶部输入时弹层改放下方，完整显示，Escape 仅关闭候选而不破坏输入。 */
    @Test fun popupRemainsVisibleNearTheTopAndCanBeDismissed() {
        // 两种窗口位置都使用同一菜单表面，不在页面内部占用额外高度。
        mount(top = true)
        editor().performTextInput("@")
        compose.onNodeWithTag("mention-option-1").assertIsDisplayed()
        screenshot("top-popup")
        editor().performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        assertEquals("@", input.text)
    }

    /** 键盘缩小底部输入区域后，按真实屏幕坐标核对菜单确实在输入框附近。 */
    @Test fun popupTracksBottomCaretAfterImeResizesWindow() {
        // 独立窗口的局部坐标不能证明真实位置，必须比较主窗口与 Popup 各自转换后的屏幕边界。
        mount()
        editor().performTextInput("@")
        popupNextToEditor()
        screenshot("physical-position")
    }

    /** 等待候选进入输入框附近的真实屏幕位置，返回菜单窗口边界；超时保留实际坐标和截图。 */
    private fun popupNextToEditor(): android.graphics.Rect {
        // Android 11 的无障碍窗口缓存可能保留键盘移动前的位置，用真实视图屏幕转换与系统截图交叉检查。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        var geometry = "No accessibility bounds observed"
        var result: android.graphics.Rect? = null
        try {
            compose.waitUntil(10_000) {
                val field = device.findObject(By.clazz("android.widget.EditText"))
                val menu = compose.onAllNodesWithTag("mention-suggestions").fetchSemanticsNodes().singleOrNull()
                if (field == null || menu == null) {
                    geometry = "fieldFound=${field != null}, menuFound=${menu != null}"
                    return@waitUntil false
                }
                val fieldBounds = field.visibleBounds
                // localToScreen 会经过 Popup 自己的 ViewRoot；不能使用它局部窗口中的 boundsInWindow。
                val popupBounds = compose.runOnIdle {
                    val coordinates = menu.layoutInfo.coordinates
                    val topLeft = coordinates.localToScreen(Offset.Zero)
                    val bottomRight = coordinates.localToScreen(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                    android.graphics.Rect(topLeft.x.roundToInt(), topLeft.y.roundToInt(), bottomRight.x.roundToInt(), bottomRight.y.roundToInt())
                }
                val gap = if (popupBounds.bottom <= fieldBounds.top) fieldBounds.top - popupBounds.bottom
                    else if (popupBounds.top >= fieldBounds.bottom) popupBounds.top - fieldBounds.bottom else -1
                geometry = "field=$fieldBounds, popup=$popupBounds, gap=$gap"
                val nearby = gap >= 0 && gap <= 48
                if (nearby) result = popupBounds
                nearby
            }
        } catch (failure: ComposeTimeoutException) {
            // 失败保留物理位置及截图，不能因候选节点在它自己的窗口内可见就判通过。
            screenshot("physical-position-failure")
            device.dumpWindowHierarchy(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-mention-input-position-hierarchy.xml"))
            throw AssertionError("Popup is not next to the editor: $geometry", failure)
        }
        // 只有弹层已测量且邻近真实输入框时才提供采样范围，随后由整屏像素确认实际绘制。
        return requireNotNull(result)
    }

    /** 在高对比背景上读取实际菜单像素，确认使用共享弹层表面而非透明卡片底色。 */
    @Test fun popupUsesTheSharedOpaqueMenuSurface() {
        // 采样未高亮行最右侧内边距，避开文字、头像和圆角。
        mount(contrastBackdrop = true)
        editor().performTextInput("@")
        compose.onNodeWithTag("mention-suggestions").assertIsDisplayed()
        val bounds = popupNextToEditor()
        screenshot("opaque-menu")
        // 独立窗口的 PixelCopy 可能返回透明像素或预合成白底，使用系统截图和屏幕边界核对真实黑底显示结果。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = HlovetUi.menuSurface.compositeOver(Color.Black)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val actual = Color(bitmap.getPixel(bounds.right - 4, bounds.centerY()))
            assertEquals("Screen menu sample at $bounds", expected.red.toDouble(), actual.red.toDouble(), .04)
            assertEquals(expected.green.toDouble(), actual.green.toDouble(), .04)
            assertEquals(expected.blue.toDouble(), actual.blue.toDouble(), .04)
        } finally {
            // 像素检查不保留大位图资源，截图证据由前面的统一方法单独保存。
            bitmap.recycle()
        }
    }

    /** 标签着色与普通输入的光标位置逐字一致，避免历史上的叠字与偏移。 */
    @Test fun mentionHighlightDoesNotChangeCursorCoordinates() {
        // 用相同字体及约束生成普通文本布局，与实际提及框内部的布局比较。
        mount()
        editor().performTextInput("中文 @nice3 text")
        val actual = layout()
        compose.runOnIdle {
            val plain = androidx.compose.ui.text.TextMeasurer(
                actual.layoutInput.fontFamilyResolver, actual.layoutInput.density, actual.layoutInput.layoutDirection,
            ).measure(
                text = androidx.compose.ui.text.AnnotatedString(input.text), style = actual.layoutInput.style,
                constraints = actual.layoutInput.constraints, maxLines = actual.layoutInput.maxLines,
                overflow = actual.layoutInput.overflow, softWrap = actual.layoutInput.softWrap,
            )
            (0..input.text.length).forEach { offset ->
                assertEquals(plain.getCursorRect(offset), actual.getCursorRect(offset))
            }
        }
        assertEquals(input.text, actual.layoutInput.text.text)
        assertTrue(actual.layoutInput.text.spanStyles.all { it.item.fontSize.isUnspecified && it.item.fontWeight == null })
        assertTrue(actual.getCursorRect(input.text.length).left > actual.getCursorRect(3).left)
    }

    /** 返回唯一的原生可编辑节点。 */
    private fun editor(): SemanticsNodeInteraction {
        // 菜单项目和提示文字不具有 SetText 动作，不能被误当成输入框。
        return compose.onNode(hasSetTextAction())
    }

    /** 确认候选内容已移除；失败时同时记录输入状态和语义节点，不能接受透明的残留菜单。 */
    private fun assertMenuHidden() {
        // 使用节点数量直接检查内容卸载，避免把被隐藏或尚未放置的候选当成关闭成功。
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("mention-suggestions").fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            // 失败时记录输入实际状态，区分系统重新提交文本与独立窗口残留，不掩盖可见菜单。
            screenshot("hidden-failure")
            val nodes = compose.onAllNodesWithTag("mention-suggestions").fetchSemanticsNodes()
            throw AssertionError("Menu remains: text=${input.text.replace("\n", "<newline>")}, selection=${input.selection}, composition=${input.composition}, active=${nativeActiveMention(input)}, nodes=${nodes.map { it.boundsInWindow }}", failure)
        }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
    }

    /** 读取正在绘制的文本布局，校验标签范围及字形而非仅检查输入状态。 */
    private fun layout(): TextLayoutResult {
        // 无布局时测试必须失败，不能略过样式检查。
        val results = mutableListOf<TextLayoutResult>()
        editor().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** 将不含敏感信息的 name 场景截图保存到应用私有目录。 */
    private fun screenshot(name: String) {
        // 供宿主机拉取并目视检查弹层层级、背景和光标邻近位置。
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "native-mention-input-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
