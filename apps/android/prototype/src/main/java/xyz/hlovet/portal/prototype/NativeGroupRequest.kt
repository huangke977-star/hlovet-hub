package xyz.hlovet.portal.prototype

/** 判断群聊异步结果是否仍属于当前群、查询词、账号和请求代际。 */
internal fun isCurrentGroupRequest(
    requestId: Int,
    activeRequestId: Int,
    requestedGroupId: Int?,
    activeGroupId: Int?,
    requestedQuery: String,
    activeQuery: String,
    requestedToken: String?,
    activeToken: String?,
): Boolean = requestId == activeRequestId &&
    requestedGroupId == activeGroupId &&
    requestedQuery == activeQuery &&
    requestedToken == activeToken
