package xyz.hlovet.portal.prototype

/** 管理同一信息流的请求代际，避免旧条件的异步回包覆盖当前列表。 */
internal class NativeRequestGeneration {
    private var currentGeneration = 0

    /** 开启一代新请求并返回它的标识。 */
    fun begin(): Int {
        currentGeneration += 1
        return currentGeneration
    }

    /** 判断回包是否仍属于当前请求代际。 */
    fun isCurrent(requestGeneration: Int): Boolean = requestGeneration == currentGeneration
}
