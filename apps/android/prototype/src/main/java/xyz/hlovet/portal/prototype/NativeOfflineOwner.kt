package xyz.hlovet.portal.prototype

import android.content.Context

/** 使用稳定账号 ID 管理离线所有者，用户名迁移只作用于同一服务器命名空间。 */
internal object NativeOfflineOwner {
    const val PUBLIC = "public"

    /** 返回 session 的稳定账号所有者；未登录返回公共所有者，旧会话无 ID 时沿用名称。 */
    fun forSession(session: NativeSession?): String = when {
        // 账号 ID 优先于可修改名称，服务器边界由存储层独立加入。
        session == null -> PUBLIC
        session.userId > 0 -> stableUser(session.userId)
        else -> session.username.trim().ifBlank { PUBLIC }
    }

    /** 根据不可变 userId 返回账号所有者键，不使用昵称或邮箱参与身份判断。 */
    fun stableUser(userId: Int): String =
        // ID 仅作为账号维度，最终存储键还会包含服务器来源。
        "user:$userId"

    /** 将 context 中 apiBaseUrl 下 session 的用户名及 legacyOwner 缓存迁移到稳定 ID，旧版无来源数据保留。 */
    fun migrate(context: Context, session: NativeSession, legacyOwner: String? = null, apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 无有效账号 ID 时不能可靠判断目标所有者，因此不执行迁移。
        if (session.userId <= 0 || session.username.isBlank()) return
        val stableOwner = stableUser(session.userId)
        // 当前名称与修改前名称去重后逐个迁移，媒体路径仅从同来源正文及首页读取。
        listOf(session.username, legacyOwner)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { oldOwner ->
                val preview = NativeOfflineCache.loadPreview(context, oldOwner, apiBaseUrl)
                val mediaPaths = NativeOfflineContentStore.mediaPaths(context, oldOwner, apiBaseUrl) +
                    NativeOfflineContentStore.mediaPaths(context, stableOwner, apiBaseUrl) + preview?.let { data ->
                    listOfNotNull(data.backgroundUrl) + data.articles.flatMap(::nativeOfflineArticleMediaPaths) +
                        data.topics.flatMap { listOfNotNull(it.coverPath) + it.articles.flatMap(::nativeOfflineArticleMediaPaths) } +
                        data.collections.flatMap { listOfNotNull(it.coverPath) + it.articles.flatMap(::nativeOfflineArticleMediaPaths) }
                }.orEmpty()
                // 先迁移正文索引，再迁移引用的缓存；另一个服务器的同名所有者不会被触及。
                NativeOfflineContentStore.migrateOwner(context, stableOwner, oldOwner, apiBaseUrl)
                NativeOfflineCache.migrateOwner(context, stableOwner, oldOwner, mediaPaths.distinct(), apiBaseUrl)
            }
    }
}
