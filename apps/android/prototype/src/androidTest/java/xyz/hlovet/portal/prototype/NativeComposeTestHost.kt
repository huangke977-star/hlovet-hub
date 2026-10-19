package xyz.hlovet.portal.prototype

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.test.StandardTestDispatcher

/** 创建设备 UI 测试规则，返回独立调度环境；自动同步时先在主线程启动当前时刻的排队任务。 */
@OptIn(ExperimentalTestApi::class)
internal fun createNativeComposeRule(): ComposeContentTestRule {
    // 默认 Unconfined 调度器会在真实 IO 完成线程直接恢复，破坏 Dialog 和组合的主线程约束。
    val rule = createComposeRule(effectContext = StandardTestDispatcher())
    // Compose 1.8 的内置空闲检查只识别重组、布局和帧等待，不识别尚未启动的标准调度器任务。
    // 先运行当前时刻的任务，让滚动协程进入帧等待，再由内置资源判断布局和动画是否完成。
    rule.registerIdlingResource(object : IdlingResource {
        /** 自动时钟启用时启动到期任务；返回 true 仅表示本资源不阻塞，界面空闲仍由内置资源判断。 */
        override val isIdleNow: Boolean
            get() {
                // 不推进未来时间，也不干预测试主动暂停的时钟，保留延迟和动画的手动控制。
                if (rule.mainClock.autoAdvance) rule.mainClock.advanceTimeBy(0)
                return true
            }
    })
    return rule
}

/** 等待按钮屏幕位置稳定后真实触摸；compose 可来自独立宿主或 MainActivity，不自动重试提交。 */
internal fun SemanticsNodeInteraction.performNativeStableClick(compose: ComposeTestRule): SemanticsNodeInteraction {
    // 输入法动画由 Android 实时时钟驱动，Compose 空闲不能保证 Dialog 的屏幕位置已经停止变化。
    var previousBounds: Rect? = null
    var stableSince = SystemClock.uptimeMillis()
    compose.waitUntil(5_000) {
        val node = fetchSemanticsNode()
        val bounds = compose.runOnUiThread {
            val coordinates = node.layoutInfo.coordinates
            if (!coordinates.isAttached) null else {
                val position = coordinates.localToScreen(Offset.Zero)
                if (!position.x.isFinite() || !position.y.isFinite()) null else Rect(position, coordinates.size.toSize())
            }
        }
        val now = SystemClock.uptimeMillis()
        if (bounds != null && bounds == previousBounds) now - stableSince >= 250 else {
            previousBounds = bounds
            stableSince = now
            false
        }
    }
    // 保留官方物理点击与可见性检查；位置五秒仍不稳定时直接失败，不调用业务回调绕过布局。
    return assertIsDisplayed().performClick()
}

/** 使用 compose 的空闲同步挂载页面，并在关闭接口夹具前卸载组合，隔离各测试的弹窗与协程。 */
internal class NativeComposeTestHost(private val compose: ComposeContentTestRule) {
    private val mounted = mutableStateOf(false)
    private var installed = false

    /** 仅挂载一次 content；空宿主先完成初始化，再在主线程展示业务页面。 */
    fun setContent(content: @Composable () -> Unit) {
        // 避免首次 Activity 初始化与异步请求同时改变组合树。
        check(!installed) { "A test host can install content only once" }
        installed = true
        compose.setContent { if (mounted.value) content() }
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()
    }

    /** 卸载已挂载页面并等待弹窗、协程完成取消；未安装时没有资源需要释放。 */
    fun dispose() {
        // 先卸载页面再关闭接口夹具，防止清理中的旧响应进入下一项测试。
        if (!installed) return
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
    }
}
