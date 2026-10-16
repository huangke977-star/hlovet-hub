package xyz.hlovet.portal.prototype

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlovetBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displayOnlyBadgesDoNotExposeButtonActions() {
        compose.setContent { HlovetTheme { HlovetBadge("Status") } }
        compose.onNodeWithText("Status").assertIsDisplayed().assertHasNoClickAction()
    }
}
