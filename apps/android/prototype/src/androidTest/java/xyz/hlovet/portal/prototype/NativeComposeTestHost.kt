package xyz.hlovet.portal.prototype

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher

/** 创建设备 UI 测试规则，返回独立调度环境；异步回调由 MainTestClock 在主线程推进。 */
@OptIn(ExperimentalTestApi::class)
internal fun createNativeComposeRule(): ComposeContentTestRule {
    // 默认 Unconfined 调度器会在真实 IO 完成线程直接恢复，破坏 Dialog 和组合的主线程约束。
    return createComposeRule(effectContext = StandardTestDispatcher())
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
