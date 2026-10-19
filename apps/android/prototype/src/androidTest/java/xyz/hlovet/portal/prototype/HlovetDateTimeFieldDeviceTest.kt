package xyz.hlovet.portal.prototype

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 独立核对共享时间字段的真实触摸、清除、重组和无障碍边界，不访问业务 API。 */
@RunWith(AndroidJUnit4::class)
class HlovetDateTimeFieldDeviceTest {
    @get:Rule val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)

    /** 卸载每项测试的组件，避免焦点及交互监听跨场景遗留。 */
    @After fun dispose() {
        // 不读写账号或服务器数据，只销毁当前隔离组件。
        host.dispose()
    }

    /** 空字段和已有时间均通过真实触摸触发选择，重组后使用最新回调。 */
    @Test fun touchOpensPickerWithLatestCallback() {
        // 与真实表单一样由外部状态保存时间，不在字段内维护另一份草稿。
        val value = mutableStateOf("")
        val calls = mutableListOf<String>()
        host.setContent { HlovetTheme {
            val current = value.value
            HlovetDateTimeField("时间", current, { calls.add(current) }, { value.value = "" }, Modifier.testTag("time"))
        } }
        compose.onNodeWithTag("time").performNativeStableClick(compose)
        compose.runOnIdle { value.value = "2026-10-20 08:30" }
        compose.onNodeWithTag("time").performNativeStableClick(compose)
        compose.runOnIdle { assertEquals(listOf("", "2026-10-20 08:30"), calls) }
    }

    /** 清除只触发清空，不同时触发选择；清空后仍可重新选择。 */
    @Test fun clearDoesNotAlsoOpenPicker() {
        // 两个回调分别计数，避免“值清除了”掩盖重复弹出选择器。
        val value = mutableStateOf("2026-10-20 08:30")
        var picks = 0
        var clears = 0
        host.setContent { HlovetTheme {
            HlovetDateTimeField("时间", value.value, { picks++ }, { clears++; value.value = "" }, Modifier.testTag("time"))
        } }
        compose.onNodeWithContentDescription(nativePhrase("清除时间", "Clear time")).performNativeStableClick(compose)
        compose.onNodeWithTag("time").assert(hasText("2026-10-20 08:30").not())
        compose.runOnIdle { assertEquals(1, clears); assertEquals(0, picks) }
        compose.onNodeWithTag("time").performNativeStableClick(compose)
        compose.runOnIdle { assertEquals(1, picks) }
    }

    /** 保存中禁用字段后不能选择或清除，重新启用后才恢复操作。 */
    @Test fun disabledFieldHasNoInteractiveClearOrPick() {
        // 真实物理触摸禁用输入框，验证它不会泄漏交互事件到外层。
        val enabled = mutableStateOf(false)
        var picks = 0
        host.setContent { HlovetTheme {
            HlovetDateTimeField("时间", "2026-10-20 08:30", { picks++ }, { error("Disabled clear") }, Modifier.testTag("time"), enabled.value)
        } }
        compose.onNodeWithTag("time").assertIsNotEnabled().performTouchInput { click() }
        compose.onAllNodesWithContentDescription(nativePhrase("清除时间", "Clear time")).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, picks); enabled.value = true }
        compose.onNodeWithTag("time").performNativeStableClick(compose)
        compose.runOnIdle { assertEquals(1, picks) }
    }

    /** 读屏语义点击只触发一次选择，与触摸共用相同回调。 */
    @Test fun accessibilityClickOpensPickerOnce() {
        // 显式调用无障碍语义，不把此项当作真实物理点击验证。
        var picks = 0
        host.setContent { HlovetTheme {
            HlovetDateTimeField("时间", "", { picks++ }, {}, Modifier.testTag("time"))
        } }
        compose.onNodeWithTag("time").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle { assertEquals(1, picks) }
    }
}
