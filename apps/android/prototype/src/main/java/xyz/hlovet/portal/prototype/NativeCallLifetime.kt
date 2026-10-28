package xyz.hlovet.portal.prototype

/** 为同一登录会话的异步通话操作分配代次，清理后旧配置、回执和媒体回调不得恢复面板。 */
internal class NativeCallLifetime {
    var version: Long = 0
        private set

    /** 开始独立的一次发起或接听，返回本次操作必须持有的代次。 */
    fun begin(): Long {
        // 新操作不能继续使用上一次通话留下的异步结果。
        invalidate()
        return version
    }

    /** 断网、挂断或宿主销毁时令全部在途结果失效，不重置以免旧值与新通话相同。 */
    fun invalidate() {
        // 所有调用均在宿主主线程串行处理，代次不依赖 Socket 回调线程的读取顺序。
        version += 1
    }

    /** 返回 expected 是否仍是当前代次；调用者在主线程处理结果之前检查。 */
    fun isCurrent(expected: Long): Boolean {
        // 只比较稳定代次，不以会话名称或当前非空面板替代同一次操作身份。
        return expected == version
    }
}
