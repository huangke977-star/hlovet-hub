package xyz.hlovet.portal.prototype

/** 判断文章详情异步结果是否仍属于当前文章、当前会话和当前请求代际。 */
internal fun isCurrentArticleDetailRequest(
    requestId: Int,
    activeRequestId: Int,
    requestedSlug: String,
    activeSlug: String,
    requestedToken: String?,
    activeToken: String?,
): Boolean = requestId == activeRequestId &&
    requestedSlug == activeSlug &&
    requestedToken == activeToken
