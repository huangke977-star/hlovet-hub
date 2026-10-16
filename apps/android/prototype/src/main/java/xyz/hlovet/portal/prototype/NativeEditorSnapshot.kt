package xyz.hlovet.portal.prototype

import kotlinx.coroutines.withTimeoutOrNull

/** Reads the active editor at action time, independently of debounced input events. */
internal class NativeEditorSnapshot(private val timeoutMillis: Long = 5_000L) {
    private data class Binding(val owner: Any, val read: suspend () -> String)

    private var binding: Binding? = null

    val isAttached: Boolean
        get() = binding != null

    fun attach(owner: Any, read: suspend () -> String) {
        binding = Binding(owner, read)
    }

    fun detach(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    suspend fun capture(): String {
        val current = binding ?: throw unavailable()
        val content = withTimeoutOrNull(timeoutMillis) { current.read() } ?: throw unavailable()
        if (binding !== current) throw unavailable()
        return content
    }

    private fun unavailable() = IllegalStateException(
        nativePhrase("编辑器尚未准备好，请稍后重试。", "The editor is not ready. Please try again."),
    )
}
