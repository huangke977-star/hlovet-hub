package xyz.hlovet.portal.prototype

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 核对首页缓存、后台刷新、超时与账号切换取消的边界。 */
class NativePreviewLoaderTest {
    private val sample = PreviewData(
        articles = emptyList(),
        topics = emptyList(),
        collections = emptyList(),
        appearance = PreviewAppearance(
            themeId = "cloud-blue",
            accent = "#0284c7",
            foreground = "#1f2937",
            muted = "#52616f",
            surface = "#ffffff",
            cardAlpha = 50,
            glassBlur = 18,
            glassTint = "#fff3f6",
            glassTintAlpha = 0,
        ),
        backgroundUrl = null,
    )

    /** 首次同步初始化阻塞后台时，收集线程仍能继续响应且不会提前发布在线状态。 */
    @Test
    fun synchronousFetchSetupDoesNotBlockCollectorThread() = runBlocking {
        // 用受控闩锁模拟客户端首次初始化，避免依赖机器速度判断主线程是否被阻塞。
        val callerThread = Thread.currentThread()
        val started = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        val states = mutableListOf<PreviewLoadState>()
        val job = launch {
            nativePreviewLoad(
                readCache = { sample },
                fetch = {
                    started.complete(Thread.currentThread())
                    check(release.await(3, TimeUnit.SECONDS))
                    sample.copy(backgroundUrl = "/online")
                },
                save = { assertEquals(callerThread, Thread.currentThread()) },
            ).collect { state ->
                assertEquals(callerThread, Thread.currentThread())
                states += state
            }
        }
        try {
            // 初始化尚未解除阻塞时，收集线程应已经取得缓存并能释放后台任务。
            val fetchThread = withTimeout(2_000) { started.await() }
            assertNotSame(callerThread, fetchThread)
            assertEquals(listOf(PreviewLoadState.Ready(sample, fromCache = true)), states)
            release.countDown()
            job.join()
            assertEquals("/online", (states.last() as PreviewLoadState.Ready).data.backgroundUrl)
        } finally {
            // 断言失败也释放线程，避免失败用例拖住后续回归。
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test
    fun cachedPreviewIsEmittedBeforeSlowRefresh() = runBlocking {
        val states = nativePreviewLoad(
            readCache = { sample },
            fetch = { delay(20); sample.copy(backgroundUrl = "/new-background") },
            save = {},
            timeoutMillis = 1000,
        ).toList()

        assertTrue(states.first() is PreviewLoadState.Ready)
        assertTrue((states.first() as PreviewLoadState.Ready).fromCache)
        assertEquals("/new-background", (states.last() as PreviewLoadState.Ready).data.backgroundUrl)
    }

    @Test
    fun missingCacheTurnsTimeoutIntoRetryableError() = runBlocking {
        val states = nativePreviewLoad(
            readCache = { null },
            fetch = { delay(1000); sample },
            save = {},
            timeoutMillis = 10,
        ).toList()

        assertTrue(states.first() is PreviewLoadState.Loading)
        assertTrue(states.last() is PreviewLoadState.Error)
    }

    @Test
    fun refreshFailureKeepsCachedPreviewVisible() = runBlocking {
        val states = nativePreviewLoad(
            readCache = { sample },
            fetch = { error("temporary network failure") },
            save = {},
            timeoutMillis = 1000,
        ).toList()

        assertEquals(1, states.size)
        assertTrue((states.single() as PreviewLoadState.Ready).fromCache)
    }

    @Test
    fun cachedPreviewSurvivesRefreshTimeoutWithoutSaving() = runBlocking {
        var saved = false
        val states = nativePreviewLoad(
            readCache = { sample },
            fetch = { awaitCancellation() },
            save = { saved = true },
            timeoutMillis = 10,
        ).toList()

        assertEquals(listOf(PreviewLoadState.Ready(sample, fromCache = true)), states)
        assertEquals(false, saved)
    }

    @Test
    fun successfulRefreshIsSavedBeforePublishingOnlineState() = runBlocking {
        val events = mutableListOf<String>()
        nativePreviewLoad(
            readCache = { null },
            fetch = { sample },
            save = { events += "save" },
        ).collect { state ->
            events += if (state is PreviewLoadState.Loading) "loading" else "ready"
        }

        assertEquals(listOf("loading", "save", "ready"), events)
    }

    @Test
    fun accountSwitchCancellationDoesNotPublishFailureOrSave() = runBlocking {
        val fetching = CompletableDeferred<Unit>()
        val states = mutableListOf<PreviewLoadState>()
        var saved = false
        val job = launch {
            nativePreviewLoad(
                readCache = { sample },
                fetch = { fetching.complete(Unit); awaitCancellation() },
                save = { saved = true },
            ).toList(states)
        }
        fetching.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(false, saved)
        assertEquals(listOf(PreviewLoadState.Ready(sample, fromCache = true)), states)
    }

    @Test
    fun upstreamCancellationIsNotConvertedToNetworkError() = runBlocking {
        val states = mutableListOf<PreviewLoadState>()
        var cancelled = false
        try {
            nativePreviewLoad(
                readCache = { null },
                fetch = { throw CancellationException("account changed") },
                save = {},
            ).toList(states)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf(PreviewLoadState.Loading), states)
    }
}
