package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdminRouteTest {
    @Test
    fun everyImplementedHomeEntryHasANativeTarget() {
        val paths = implementedNativeAdminPaths()
        assertEquals(paths.size, paths.toSet().size)
        paths.forEach { path -> assertNotNull("Missing native target for $path", nativeAdminAccountTarget(path)) }
    }

    /** 管理员首页的每个已声明入口都必须接入原生页面，避免新增入口静默回退到未移植提示。 */
    @Test
    fun everyAdminHomeEntryIsImplemented() {
        assertEquals(
            nativeAdminPathsFor(isAdministrator = true, isSuperAdmin = true).toSet(),
            implementedNativeAdminPaths().toSet(),
        )
    }

    @Test
    fun backgroundsAndSettingsShareTheImplementedSettingsScreen() {
        assertEquals(AccountTarget.AdminSettings, nativeAdminAccountTarget("/admin/backgrounds"))
        assertEquals(AccountTarget.AdminSettings, nativeAdminAccountTarget("/admin/settings"))
    }

    @Test
    fun localizedAndTrailingSlashPathsResolveWithoutLosingNestedModules() {
        assertEquals(AccountTarget.AdminHome, nativeAdminAccountTarget("/en/admin/"))
        assertEquals(AccountTarget.AdminBackups, nativeAdminAccountTarget("/en/admin/system/backups/"))
        assertEquals(AccountTarget.AdminOperations, nativeAdminAccountTarget("/admin/system/operations"))
        assertEquals(AccountTarget.AdminStorage, nativeAdminAccountTarget("/admin/storage"))
    }

    @Test
    fun unsupportedPathsNeverOpenADifferentModule() {
        assertNull(nativeAdminAccountTarget("/profile/privacy"))
        assertNull(nativeAdminAccountTarget("/admin/articles/123"))
        assertNull(nativeAdminAccountTarget("/admin/unknown"))
    }

    @Test
    fun siteAdministratorsCanSeeTopicAndAnonymousTopicManagement() {
        val paths = nativeAdminPathsFor(isAdministrator = true, isSuperAdmin = false)

        assertTrue(paths.contains("/admin/topics"))
        assertTrue(paths.contains("/admin/voices"))
        assertFalse(paths.contains("/admin/ai"))
        assertFalse(paths.contains("/admin/settings"))
    }

    @Test
    fun adminTopicsUsesAnIndependentBackStackTarget() {
        assertEquals(AccountTarget.AdminTopics, nativeAdminAccountTarget("/admin/topics"))
    }

    @Test
    fun legacyArticleManagementPathsKeepTheirPcMeaning() {
        assertEquals(AccountTarget.MyArticles, nativeArticleManagementTarget("/articles/mine"))
        assertEquals(AccountTarget.MyArticles, nativeArticleManagementTarget("/profile/articles"))
        assertEquals(AccountTarget.AdminArticles, nativeArticleManagementTarget("/articles/manage"))
        assertEquals(AccountTarget.AdminArticles, nativeArticleManagementTarget("/en/articles/manage/"))
    }

    /** 编辑器和预览深链接必须在普通文章详情之前被识别，避免有效入口回退到外部页面。 */
    @Test
    fun articleEditorAndPreviewDeepLinksAreClassifiedBeforeArticleDetails() {
        assertEquals(
            NativeArticleDeepLink(NativeArticleDeepLinkKind.Edit, 42),
            parseNativeArticleDeepLink("/articles/edit/42/"),
        )
        assertEquals(
            NativeArticleDeepLink(NativeArticleDeepLinkKind.Preview, 73),
            parseNativeArticleDeepLink("/en/articles/preview/73"),
        )
        assertNull(parseNativeArticleDeepLink("/articles/example-slug"))
    }

    /** 深链接编号无效时仍保留入口类型，由运行时显示错误而不是误打开普通文章详情。 */
    @Test
    fun articleDeepLinksKeepInvalidIdsVisibleToRuntimeValidation() {
        assertEquals(
            NativeArticleDeepLink(NativeArticleDeepLinkKind.Edit, null),
            parseNativeArticleDeepLink("/articles/edit/not-a-number"),
        )
        assertEquals(
            NativeArticleDeepLink(NativeArticleDeepLinkKind.Preview, 0),
            parseNativeArticleDeepLink("/articles/preview/0"),
        )
    }
}
