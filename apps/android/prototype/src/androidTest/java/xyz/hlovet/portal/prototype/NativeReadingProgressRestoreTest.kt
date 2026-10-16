package xyz.hlovet.portal.prototype

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 核对阅读恢复与显式定位的优先级，使用真实 LazyColumn 几何而非仅检查配置布尔值。 */
class NativeReadingProgressRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private lateinit var listState: LazyListState
    private var viewportHeight by mutableIntStateOf(300)

    /** 分离独立阅读夹具，不读写实际账号或文章阅读记录。 */
    @After fun stop() {
        // 先取消几何观测和恢复任务，避免影响下一个测试页面。
        host.dispose()
    }

    /** 一次恢复成功后窗口尺寸变化不能把手动滚动的评论位置拉回正文。 */
    @Test fun viewportChangeDoesNotRepeatCompletedRestore() {
        // 保存百分之五十进度，验证真实列表偏移已恢复后再模拟窗口变化。
        open(true)
        compose.waitUntil(10000) { listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 100 }
        // 列表重测必须由 UI 线程执行，测试线程直接调用会与 Choreographer 布局并发。
        compose.runOnUiThread { compose.activity.lifecycleScope.launch { listState.scrollToItem(4) } }
        compose.waitUntil(10000) { listState.firstVisibleItemIndex == 4 }
        compose.runOnIdle { viewportHeight = 280 }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(4, listState.firstVisibleItemIndex)
    }

    /** 显式评论定位不触发正文保存进度恢复，定位位置保持在指定行。 */
    @Test fun explicitFocusTakesPriorityOverSavedBodyPosition() {
        // 同一篇有已保存进度的文章，入口要求评论行时必须优先服从入口目标。
        open(false)
        compose.waitUntil(10000) { listState.firstVisibleItemIndex == 4 }
        compose.runOnIdle { viewportHeight = 280 }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(4, listState.firstVisibleItemIndex)
    }

    /** 挂载带真实正文几何的列表，restore 决定普通阅读或显式评论入口。 */
    private fun open(restore: Boolean) {
        // 匿名夹具只验证本地滚动，不向服务器提交阅读记录。
        val article = RemoteArticle(1, "fixture", "fixture", "", "", category = "", tags = emptyList(), viewCount = 0,
            likeCount = 0, favoriteCount = 0, commentCount = 0, authorName = "fixture", authorUsername = "fixture", readingProgress = 50)
        host.setContent {
            listState = rememberLazyListState()
            val tracking = rememberNativeReadingProgress(article, null, false, null, 1, listState, restore)
            if (!restore) LaunchedEffect(Unit) { listState.scrollToItem(4) }
            LazyColumn(Modifier.fillMaxWidth().height(viewportHeight.dp).then(tracking.viewport), state = listState) {
                item { Text("header", Modifier.height(40.dp)) }
                item { Box(Modifier.fillMaxWidth().height(600.dp).then(tracking.body)) }
                items(10) { index -> Text("row-$index", Modifier.height(80.dp)) }
            }
        }
    }
}
