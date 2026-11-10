package xyz.hlovet.portal.prototype

/** 判断会话搜索结果是否仍属于当前会话、查询词、账号和请求代际。 */
internal fun isCurrentConversationSearchRequest(
    requestId: Int,
    activeRequestId: Int,
    requestedConversationId: Int,
    activeConversationId: Int,
    requestedQuery: String,
    activeQuery: String,
    requestedToken: String?,
    activeToken: String?,
): Boolean = requestId == activeRequestId &&
    requestedConversationId == activeConversationId &&
    requestedQuery == activeQuery &&
    requestedToken == activeToken
