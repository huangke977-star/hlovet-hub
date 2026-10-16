package xyz.hlovet.portal.prototype

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Enter screens only after the host is idle, and detach them before stopping their fixtures. */
internal class NativeComposeTestHost(private val compose: ComposeContentTestRule) {
    private val mounted = mutableStateOf(false)
    private var installed = false

    fun setContent(content: @Composable () -> Unit) {
        check(!installed) { "A test host can install content only once" }
        installed = true
        compose.setContent { if (mounted.value) content() }
        compose.runOnIdle { mounted.value = true }
        compose.waitForIdle()
    }

    fun dispose() {
        if (!installed) return
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
    }
}
