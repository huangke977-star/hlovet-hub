package xyz.hlovet.portal.prototype

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 核对设备 UI 测试的线程归属、语义滚动和时钟控制，不调用真实接口或修改保存的账号。 */
@RunWith(AndroidJUnit4::class)
class NativeComposeThreadingDeviceTest {
    @get:Rule val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)

    /** 测试结束后卸载组合，确保剩余弹窗不会进入下一项测试。 */
    @After fun stop() {
        // 宿主使用正常组合卸载流程，不绕过 Android 的生命周期线程检查。
        host.dispose()
    }

    /** 列表语义滚动产生的排队协程必须能自动启动，六轮首尾往返不依赖测试手动推进时钟。 */
    @Test fun queuedSemanticScrollingDoesNotRequireManualClockPumping() {
        // 使用高于屏幕的真实懒加载列表，覆盖滚动查找、回到索引和多次复用同一调度器。
        host.setContent {
            HlovetTheme {
                LazyColumn(Modifier.fillMaxSize().testTag("threading-scroll-list")) {
                    items(80) { index -> Text("Scroll item $index", Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
        }
        repeat(6) {
            // 只调用官方滚动 API；如果队列未被空闲同步启动，performScrollToNode 将无法取得进展。
            compose.onNodeWithTag("threading-scroll-list").performScrollToNode(hasText("Scroll item 79"))
            compose.onNodeWithText("Scroll item 79").assertIsDisplayed()
            compose.onNodeWithTag("threading-scroll-list").performScrollToIndex(0)
            compose.onNodeWithText("Scroll item 0").assertIsDisplayed()
        }
    }

    /** 手动暂停测试时钟时不能偷偷启动按钮协程或跳过延迟，显式推进后才允许状态变化。 */
    @Test fun manualClockRetainsControlOfQueuedAndDelayedEffects() {
        // 原子计数允许检查尚未运行的协程，不借助重组来意外推动被测任务。
        val started = AtomicInteger()
        val completed = AtomicInteger()
        host.setContent {
            HlovetTheme {
                val scope = rememberCoroutineScope()
                Button(onClick = {
                    scope.launch {
                        started.incrementAndGet()
                        delay(1_000)
                        completed.incrementAndGet()
                    }
                }) { Text("Start delayed work") }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            // 语义点击排除触摸事件注入为事件时序主动推进的一帧，只核对空闲同步是否擅自运行任务。
            val beforeClick = compose.mainClock.currentTime
            compose.onNodeWithText("Start delayed work").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(beforeClick, compose.mainClock.currentTime)
            assertEquals(0, started.get())
            assertEquals(0, completed.get())
            // 先只运行当前时刻，再单独推进一秒；分别核对任务启动与延迟完成。
            compose.mainClock.advanceTimeBy(0)
            assertEquals(1, started.get())
            assertEquals(0, completed.get())
            compose.mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
            assertEquals(1, completed.get())
        } finally {
            // 恢复自动时钟，使宿主仍通过正常空闲同步卸载，而非强制终止组合。
            compose.mainClock.autoAdvance = true
        }
    }

    /** 连续十二轮阻塞 IO、恢复页面并销毁真实弹窗，两处操作都必须留在 Android 主线程。 */
    @Test fun ioCompletionAndDialogDisposalRemainOnMainThread() {
        // 用闩锁保证 IO 真正挂起后才返回，避免同步快路径掩盖恢复线程错误。
        val rounds = 12
        val currentRound = mutableIntStateOf(0)
        val entered = LinkedBlockingQueue<Int>()
        val releases = List(rounds) { CountDownLatch(1) }
        val completed = AtomicInteger()
        val resumedThreads = CopyOnWriteArrayList<String>()
        val disposedThreads = CopyOnWriteArrayList<String>()
        val mainThread = Looper.getMainLooper().thread.name
        host.setContent {
            val round = currentRound.intValue
            var dialogVisible by remember(round) { mutableStateOf(true) }
            LaunchedEffect(round) {
                // 外部工作只在 IO 调度器等待，后续界面更新应恢复到测试的主线程调度。
                withContext(Dispatchers.IO) {
                    entered.put(round)
                    check(releases[round].await(15, TimeUnit.SECONDS)) { "IO fixture was not released" }
                }
                resumedThreads.add(Thread.currentThread().name)
                dialogVisible = false
                completed.incrementAndGet()
            }
            Text("Completed round ${completed.get()}")
            if (dialogVisible) {
                HlovetAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Pending round $round") },
                    text = {
                        // 真实 Dialog 子组合销毁时记录线程，不能仅检查请求结果的恢复线程。
                        DisposableEffect(round) {
                            onDispose { disposedThreads.add(Thread.currentThread().name) }
                        }
                        Text("Waiting for isolated IO")
                    },
                    confirmButton = {},
                )
            }
        }
        try {
            repeat(rounds) { round ->
                // 每轮先确认真实弹窗可见，释放 IO 后再等待结果与子组合销毁。
                compose.waitUntil(10_000) { entered.peek() != null }
                assertEquals(round, entered.poll())
                compose.onNodeWithText("Pending round $round").assertIsDisplayed()
                releases[round].countDown()
                compose.waitUntil(10_000) { completed.get() == round + 1 }
                assertEquals("IO completion must resume on the UI thread", mainThread, resumedThreads[round])
                compose.waitUntil(10_000) { disposedThreads.size == round + 1 }
                assertEquals("Dialog disposal must run on the UI thread", mainThread, disposedThreads[round])
                if (round + 1 < rounds) compose.runOnIdle { currentRound.intValue = round + 1 }
            }
            assertTrue(resumedThreads.size == rounds && disposedThreads.size == rounds)
        } finally {
            // 即使断言失败，也先释放所有阻塞工作，避免夹具线程拖住后续测试。
            releases.forEach { it.countDown() }
        }
    }
}
