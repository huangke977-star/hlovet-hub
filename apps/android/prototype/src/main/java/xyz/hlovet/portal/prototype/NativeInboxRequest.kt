package xyz.hlovet.portal.prototype

/** 保存一次收件箱异步操作的账号和请求代际，防止迟到回包写入新的页面状态。 */
internal data class NativeInboxRequest(
    val requestId: Int,
    val sessionKey: String,
    val accessToken: String,
)

/**
 * 判断收件箱异步操作是否仍属于当前账号、会话和请求代际。
 *
 * @param request 发起网络操作时保存的请求快照。
 * @param activeRequestId 当前页面最新请求代际。
 * @param activeSessionKey 当前页面账号会话标识。
 * @param activeAccessToken 当前页面访问令牌，空值表示已退出。
 * @return 请求仍可写回当前页面时返回 true。
 */
internal fun isCurrentInboxRequest(
    request: NativeInboxRequest,
    activeRequestId: Int,
    activeSessionKey: String,
    activeAccessToken: String?,
): Boolean = request.requestId == activeRequestId &&
    request.sessionKey == activeSessionKey &&
    request.accessToken == activeAccessToken
