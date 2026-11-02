package xyz.hlovet.portal.prototype

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 统一协调原生应用内的 refresh token 轮换，避免多个 Activity 同时刷新同一令牌。 */
internal object NativeSessionCoordinator {
    private val refreshLock = Mutex()

    /** 在进程内串行刷新当前来源会话，并防止迟到结果覆盖其它 Activity 已保存的新会话。 */
    suspend fun refresh(context: Context, fallback: NativeSession? = null): NativeSession = refreshLock.withLock {
        // 优先读取其它 Activity 最近保存的会话，保证轮换后的 refresh token 不被旧内存值覆盖。
        val stored = withContext(Dispatchers.IO) { NativeSessionStore.load(context) }
        val current = stored ?: fallback ?: error("Native session is unavailable")
        if (current.refreshToken.isBlank()) return@withLock current

        // 服务端 refresh token 采用一次性轮换，互斥锁保证同一进程内不会并发消费同一令牌。
        val refreshed = withContext(Dispatchers.IO) { NativeAuthApi.refresh(current.refreshToken) }

        // 刷新期间可能有其它 Activity 切换账号或完成了更新令牌，旧请求不能覆盖那次更新。
        val latest = withContext(Dispatchers.IO) { NativeSessionStore.load(context) }
        if (latest != null && (latest.userId != current.userId || latest.refreshToken != current.refreshToken)) {
            return@withLock latest
        }

        // 确认设备中仍是本次刷新来源后才保存新令牌，后续 Activity 冷启动只能读取最新会话。
        check(withContext(Dispatchers.IO) { NativeSessionStore.save(context, refreshed) }) {
            "Native session could not be persisted"
        }
        refreshed
    }
}
