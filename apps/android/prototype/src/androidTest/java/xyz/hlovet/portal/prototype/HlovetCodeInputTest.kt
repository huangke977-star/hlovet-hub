package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlovetCodeInputTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sixCellsStayVisibleAtNarrowWidth() {
        compose.setContent {
            HlovetTheme {
                Box(Modifier.width(240.dp)) {
                    HlovetCodeInput("", {}, enabled = true)
                }
            }
        }
        repeat(6) { index ->
            compose.onNodeWithTag("hlovet-code-cell-$index", useUnmergedTree = true)
                .assertIsDisplayed().assertHeightIsEqualTo(44.dp)
        }
    }

    @Test
    fun pastedEmailCodePreservesLeadingZeroAndCompletesOnce() {
        var actual = ""
        val completions = mutableListOf<String>()
        compose.setContent {
            var value by remember { mutableStateOf("") }
            HlovetTheme {
                HlovetCodeInput(value, { value = it; actual = it }, enabled = true, onComplete = { completions.add(it) })
            }
        }
        compose.onNodeWithTag("hlovet-code-input").performTextInput("00-1234")
        compose.onNodeWithTag("hlovet-code-cell-5", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("001234", actual)
            assertEquals(listOf("001234"), completions)
        }
    }

    @Test
    fun recoveryCodeSupportsLettersAndReplacingAnExistingCell() {
        var actual = ""
        compose.setContent {
            var value by remember { mutableStateOf("") }
            HlovetTheme {
                HlovetCodeInput(value, { value = it; actual = it }, enabled = true, allowLetters = true)
            }
        }
        compose.onNodeWithTag("hlovet-code-input").performTextInput("abc123")
        compose.onNodeWithTag("hlovet-code-cell-1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("hlovet-code-input").performTextInput("z")
        compose.runOnIdle { assertEquals("AZC123", actual) }
    }

    @Test
    fun disabledVerificationCannotAcceptNewInput() {
        compose.setContent {
            HlovetTheme { HlovetCodeInput("123", { error("Disabled field changed") }, enabled = false) }
        }
        compose.onNodeWithTag("hlovet-code-input").assertIsNotEnabled()
    }
}
