package xyz.hlovet.portal.prototype

/** 描述系统凭据页与原生宿主返回键之间的短暂保护状态。 */
internal data class NativeCredentialReturnState(
    val promptActive: Boolean = false,
    val returnBackGuard: Boolean = false,
    val version: Int = 0,
) {
    /** 判断当前是否应拦截宿主返回事件。 */
    fun blocksHostBack(): Boolean = promptActive || returnBackGuard

    /** 标记系统凭据页已打开，并使上一轮延迟释放失效。 */
    fun promptStarted(): NativeCredentialReturnState = copy(
        promptActive = true,
        returnBackGuard = false,
        version = version + 1,
    )

    /** 标记系统凭据页已结束，并开始保护宿主页面的短暂返回窗口。 */
    fun promptFinished(): NativeCredentialReturnState = if (!promptActive) {
        this
    } else {
        copy(
            promptActive = false,
            returnBackGuard = true,
            version = version + 1,
        )
    }

    /** 仅释放仍属于指定凭据请求的返回保护，避免旧请求覆盖新请求。 */
    fun releaseReturnGuard(expectedVersion: Int): NativeCredentialReturnState = if (
        returnBackGuard && version == expectedVersion
    ) {
        copy(returnBackGuard = false)
    } else {
        this
    }
}
