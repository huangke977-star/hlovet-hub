package xyz.hlovet.portal.prototype

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** 返回阅读视口与正文的几何观测修饰符，不包含评论区域的定位状态。 */
internal data class NativeReadingProgressModifiers(
    val viewport: Modifier,
    val body: Modifier,
)

/** 观测 article 正文在 listState 中的进度；session 决定持久化身份，loading/offlineNotice 决定可写状态，articleBodyIndex 用于一次恢复，restoreSavedProgress=false 时让显式评论定位优先。 */
@Composable
internal fun rememberNativeReadingProgress(
    article: RemoteArticle,
    session: NativeSession?,
    loading: Boolean,
    offlineNotice: String?,
    articleBodyIndex: Int,
    listState: LazyListState,
    restoreSavedProgress: Boolean = true,
): NativeReadingProgressModifiers {
    // 几何与已上报进度按文章和身份隔离，不复用其它账号的阅读位置。
    var bodyTop by remember(article.id, session?.accessToken) { mutableFloatStateOf(0f) }
    var bodyHeight by remember(article.id, session?.accessToken) { mutableFloatStateOf(0f) }
    var viewportTop by remember(article.id, session?.accessToken) { mutableFloatStateOf(0f) }
    var viewportHeight by remember(article.id, session?.accessToken) { mutableFloatStateOf(0f) }
    var restored by remember(article.id, session?.accessToken, restoreSavedProgress) { mutableStateOf(!restoreSavedProgress) }
    var restoring by remember(article.id, session?.accessToken, restoreSavedProgress) {
        mutableStateOf(restoreSavedProgress && article.readingProgress?.let { it > 1 } == true)
    }
    var lastReported by remember(article.id, session?.accessToken) {
        mutableIntStateOf(article.readingProgress ?: 0)
    }

    // 首次正文几何完整后恢复一次，后续媒体布局和窗口变化不能反复抢走用户滚动位置。
    LaunchedEffect(article.id, session?.accessToken, loading, articleBodyIndex, bodyHeight, viewportHeight, restoreSavedProgress) {
        if (!restoreSavedProgress || restored) return@LaunchedEffect
        val savedProgress = article.readingProgress
        if (loading || savedProgress == null || savedProgress <= 1 || bodyHeight <= 0f || viewportHeight <= 0f) {
            if (!loading && (savedProgress == null || savedProgress <= 1)) {
                restored = true
                restoring = false
            }
            return@LaunchedEffect
        }
        restoring = true
        delay(80)
        try {
            listState.scrollToItem(articleBodyIndex, 0)
            delay(80)
            val scrollableBody = (bodyHeight - viewportHeight).coerceAtLeast(0f)
            if (scrollableBody > 0f) listState.scrollBy(scrollableBody * savedProgress / 100f)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 几何变化取消本次恢复后由新任务接续，不能把未完成恢复误记为完成。
            throw cancelled
        } catch (_: Exception) {
            // 已移除的正文行不再反复抢占滚动，保留用户当前可见位置。
        }
        lastReported = savedProgress
        restored = true
        restoring = false
    }

    // 只有在线已发布文章由当前会话防抖写回，显式评论定位不禁止正常阅读记录。
    LaunchedEffect(session?.accessToken, article.id, loading, offlineNotice, restoring) {
        val token = session?.accessToken
        if (token.isNullOrBlank() || loading || restoring || !offlineNotice.isNullOrBlank() || article.status != "published") return@LaunchedEffect
        snapshotFlow {
            nativeReadingProgressPercent(bodyTop, bodyHeight, viewportTop, viewportHeight)
        }.distinctUntilChanged().collectLatest { progress ->
            if (progress == null) return@collectLatest
            delay(900)
            if (restoring || !offlineNotice.isNullOrBlank() || progress == lastReported) return@collectLatest
            runCatching {
                withContext(Dispatchers.IO) { NativeArticleApi.updateReadingProgress(token, article.id, progress) }
            }.onSuccess { saved ->
                lastReported = saved.progress
            }
        }
    }

    // 返回稳定观测入口，正文和外层视口各自记录当前实际尺寸。
    return NativeReadingProgressModifiers(
        viewport = Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            viewportTop = position.y
            viewportHeight = coordinates.size.height.toFloat()
        },
        body = Modifier.onGloballyPositioned { coordinates ->
            bodyTop = coordinates.positionInRoot().y
            bodyHeight = coordinates.size.height.toFloat()
        },
    )
}

/** 根据正文和视口的顶点及高度计算一到一百的阅读百分比，任一高度无效返回 null。 */
internal fun nativeReadingProgressPercent(
    bodyTop: Float,
    bodyHeight: Float,
    viewportTop: Float,
    viewportHeight: Float,
): Int? {
    // 只计算正文可滚动距离，评论滚动最多算读完正文，不产生超过一百的进度。
    if (bodyHeight <= 0f || viewportHeight <= 0f) return null
    val scrollableBody = (bodyHeight - viewportHeight).coerceAtLeast(1f)
    val travelled = (viewportTop - bodyTop).coerceAtLeast(0f)
    return ((travelled / scrollableBody) * 100f).toInt().coerceIn(1, 100)
}
