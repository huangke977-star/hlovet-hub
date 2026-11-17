package xyz.hlovet.portal.prototype

/** 管理异步请求代际，避免已过期的回包覆盖当前界面状态。 */
internal class NativeRequestGeneration {
    private var currentGeneration = 0

    /** 开启一代新请求并返回它的标识。 */
    fun begin(): Int {
        currentGeneration += 1
        return currentGeneration
    }

    /** 使当前代际立即失效，用于关闭界面或取消当前请求结果。 */
    fun invalidate() {
        currentGeneration += 1
    }

    /** 判断指定回包是否仍属于当前请求代际。
     * @param requestGeneration 发起该请求时记录的代际编号。
     * @return 代际仍有效时为 true，否则为 false。
     */
    fun isCurrent(requestGeneration: Int): Boolean = requestGeneration == currentGeneration
}
