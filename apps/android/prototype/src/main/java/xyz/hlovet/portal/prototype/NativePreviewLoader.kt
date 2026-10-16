package xyz.hlovet.portal.prototype

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 首页加载状态，区分缓存可用、在线数据和可重试错误。 */
internal sealed interface PreviewLoadState {
    /** 没有可用缓存时显示的初始状态。 */
    data object Loading : PreviewLoadState
    /** 可展示的首页数据；fromCache 表示本轮尚未取得在线结果。 */
    data class Ready(val data: PreviewData, val fromCache: Boolean = false) : PreviewLoadState
    /** 无缓存且加载失败时展示的错误。 */
    data class Error(val message: String) : PreviewLoadState
}

/**
 * 优先发布当前账号的缓存，再在后台刷新；状态仍在收集协程发布，供界面安全更新。
 * readCache 读取隔离缓存，fetch 取得在线数据，save 持久化成功结果，timeoutMillis 限制刷新等待。
 * 返回加载状态流；取消时不保存或发布过期结果，已有缓存不会被网络失败覆盖。
 */
internal fun nativePreviewLoad(
    readCache: suspend () -> PreviewData?,
    fetch: suspend () -> PreviewData,
    save: suspend (PreviewData) -> Unit,
    timeoutMillis: Long = 35_000L,
): Flow<PreviewLoadState> = flow {
    // 先让界面展示缓存，缓存的磁盘调度由调用方负责。
    val cached = readCache()
    emit(cached?.let { PreviewLoadState.Ready(it, fromCache = true) } ?: PreviewLoadState.Loading)
    try {
        // fetch 首次调用可能初始化网络客户端并解析 JSON，必须在第一次挂起之前离开主线程。
        val data = withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.IO) { fetch() }
        }
        if (data == null) {
            // 超时只在无缓存时显示错误，不删除已展示的离线内容。
            if (cached == null) {
                emit(PreviewLoadState.Error(nativePhrase("读取内容超时，请检查网络后重试。", "Loading timed out. Check your connection and retry.")))
            }
            return@flow
        }
        // 持久化完成后再发布在线结果，确保显示与可恢复的数据一致。
        save(data)
        emit(PreviewLoadState.Ready(data))
    } catch (error: CancellationException) {
        // 切换账号或关闭界面时继续传播取消，不把旧账号结果转换为网络错误。
        throw error
    } catch (error: Exception) {
        // 刷新失败保留缓存；没有缓存时才要求用户重试。
        if (cached == null) {
            emit(PreviewLoadState.Error(error.message ?: nativePhrase("暂时无法读取站内公开内容", "Public content is temporarily unavailable.")))
        }
    }
}
