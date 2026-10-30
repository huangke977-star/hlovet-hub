package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证低频入口集中到“更多”页，确保用户可见入口和宿主分发目标保持一致。 */
@RunWith(AndroidJUnit4::class)
class NativeMoreNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val host = NativeComposeTestHost(compose)
    private val openedTargets = mutableListOf<AccountTarget>()
    private val openedDiscoveryModes = mutableListOf<String>()
    private var oldLocale: String? = null

    /** 固定本用例的显示语言，避免设备上一次运行留下的 locale 改变可见文案断言。 */
    @Before
    fun setChineseLocale() {
        // 记录并切换共享语言，结束时由 tearDown 恢复，不能影响其它设备用例。
        oldLocale = NativeI18n.locale
        compose.runOnIdle { NativeI18n.applyLocale("zh-CN") }
    }

    /** 解除组合树，避免测试页面协程和玻璃效果状态泄漏到后续设备用例。 */
    @After
    fun tearDown() {
        // 先卸载页面，再让测试 Activity 释放 Compose 资源。
        host.dispose()
        oldLocale?.let { locale -> compose.runOnIdle { NativeI18n.applyLocale(locale) } }
    }

    /** 公开入口完整展示，点击后分别传递专题/合集筛选和其它页面目标。 */
    @Test
    fun publicMoreEntriesDispatchToExpectedTargets() {
        // 使用匿名会话验证低频入口不会被登录状态错误隐藏，业务页面的登录门槛由宿主继续处理。
        host.setContent {
            HlovetTheme {
                NativeMoreScreen(
                    scaffoldPadding = PaddingValues(),
                    session = null,
                    onLogin = {},
                    onOpenSearch = {},
                    onOpenWrite = {},
                    onOpenDiscoveryMode = { openedDiscoveryModes += it },
                    onOpenTarget = { openedTargets += it },
                )
            }
        }

        // 先核对入口和匿名用户提示都存在，避免仅因回调可调用而忽略界面缺项。
        compose.onNodeWithText("更多").assertIsDisplayed()
        compose.onNodeWithText("登录后可使用写作、离线阅读、订阅和 AI 功能。").assertIsDisplayed()
        listOf("topics", "collections", "announcements", "navigation", "tools", "feedback", "suggestions", "resources")
            .forEach { key -> compose.onNodeWithTag("native-more-$key").performScrollTo().assertIsDisplayed() }

        // 专题和合集回到发现页对应筛选，其余入口交给宿主的 AccountTarget 分发。
        compose.onNodeWithTag("native-more-topics").performClick()
        compose.onNodeWithTag("native-more-collections").performClick()
        compose.onNodeWithTag("native-more-announcements").performClick()
        compose.onNodeWithTag("native-more-navigation").performClick()
        compose.onNodeWithTag("native-more-tools").performClick()
        compose.onNodeWithTag("native-more-feedback").performClick()
        compose.onNodeWithTag("native-more-suggestions").performClick()
        compose.onNodeWithTag("native-more-resources").performClick()

        // 回调顺序与页面顺序一致，防止某个入口误指向相邻功能页面。
        assertEquals(listOf("专题", "合集"), openedDiscoveryModes)
        assertEquals(
            listOf(
                AccountTarget.Announcements,
                AccountTarget.Navigation,
                AccountTarget.Tools,
                AccountTarget.Feedback,
                AccountTarget.Suggestions,
                AccountTarget.ResourceCenter,
            ),
            openedTargets,
        )
    }
}
