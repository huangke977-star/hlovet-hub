package xyz.hlovet.portal.prototype

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
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

/** 核对设备 UI 测试中的异步恢复和弹窗销毁线程，不调用真实接口或修改保存的账号。 */
@RunWith(AndroidJUnit4::class)
class NativeComposeThreadingDeviceTest {
    @get:Rule val compose = createNativeComposeRule()
    private val host = NativeComposeTestHost(compose)

    /** 测试结束后卸载组合，确保剩余弹窗不会进入下一项测试。 */
    @After fun stop() {
        // 宿主使用正常组合卸载流程，不绕过 Android 的生命周期线程检查。
        host.dispose()
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
