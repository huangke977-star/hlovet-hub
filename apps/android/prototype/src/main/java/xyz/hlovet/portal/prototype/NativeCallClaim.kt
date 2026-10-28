package xyz.hlovet.portal.prototype

/** 比较本机 localSocketId 与服务端 acceptedBySocketId，返回是否本机接听；不依赖 UI 重组进度。 */
internal fun nativeCallClaimMatchesSocket(localSocketId: String?, acceptedBySocketId: String?): Boolean {
    // 缺失编号不能以 null==null 视作本机接听，也不允许其它设备借用当前媒体对象状态。
    return !localSocketId.isNullOrBlank() && !acceptedBySocketId.isNullOrBlank() && localSocketId == acceptedBySocketId
}
