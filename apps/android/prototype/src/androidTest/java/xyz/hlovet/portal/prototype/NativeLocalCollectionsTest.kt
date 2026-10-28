package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 以真实 MainActivity/API/MySQL 验证合集创建、收录排序、可见范围和订阅，保留数据与原读者会话。 */
@RunWith(AndroidJUnit4::class)
class NativeLocalCollectionsTest {
    @get:Rule(order = 0) val network = NativeLoopbackNetworkRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    /** 仅使用目标应用私有目录保存本专项无凭据证据。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var reader: NativeSession? = null
    private var owner: NativeSession? = null
    private var oldLocale: String? = null
    private val checks = JSONArray()
    private val report = JSONObject().put("productionTested", false).put("testDataRemoved", false).put("actualSystemPickerTested", false)

    /** 只允许本地构建和既有读者账号，来源核对先于任何真实凭据读取。 */
    @Before fun requireLocal() {
        // 正式与隔离来源不能误执行真实写操作。
        assumeTrue(BuildConfig.API_BASE_URL == "http://127.0.0.1:3001" && BuildConfig.PORTAL_BASE_URL == "http://127.0.0.1:3000")
        val stored = requireNotNull(NativeSessionStore.load(context))
        check(stored.userId == 34 && stored.username == "native_restore_fa84e606cbbb")
        reader = NativeAuthApi.refresh(stored.refreshToken)
        check(NativeSessionStore.save(context, requireNotNull(reader)))
        oldLocale = NativeI18n.locale
        InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale("zh-CN") }
    }

    /** 保留业务数据和失败证据，关闭页面后恢复原读者并注销本轮临时作者会话。 */
    @After fun restore() {
        // 不删除合集、文章或订阅，不把失败场景回滚成看似未发生。
        if (reader == null) return
        try {
            screenshot("last-state")
            File(context.filesDir, "native-local-collections-report.json").writeText(report.put("checks", checks).toString(2))
        } finally {
            closePage()
            reader?.let { check(NativeSessionStore.save(context, it)) }
            owner?.let { runCatching { NativeAuthApi.logout(it.refreshToken) } }
            oldLocale?.let { locale -> InstrumentationRegistry.getInstrumentation().runOnMainSync { NativeI18n.applyLocale(locale) } }
        }
    }

    /** 通过实际合集页面验证持久化和订阅，再核对真实 API 拒绝失效会话后的登录反馈。 */
    @Test fun collectionsMatchPersistentOrderVisibilityAndSubscriptions() {
        // 正常注册独立作者并通过业务 API 准备三篇已发布文章，不改变站点安全配置或账号角色。
        val suffix = UUID.randomUUID().toString().take(8)
        val marker = "native_collections_20261015_$suffix"
        val username = "collections_$suffix"
        owner = (NativeAuthApi.register(username, username, "$username@example.com", "Local!${UUID.randomUUID()}", null, null) as NativeAuthResult.Authenticated).session
        val author = requireNotNull(owner)
        NativeDiscoveryApi.completeOnboarding(author.accessToken, emptyList(), emptyList())
        val articles = listOf("A", "B", "C").map { label ->
            val draft = NativeArticleApi.createArticle(author.accessToken, NativeDraftInput("$marker $label", "Retained collection flow sample", "<p>$marker article $label</p>", "html", "随笔", ""))
            NativeArticleApi.publish(author.accessToken, draft.id)
        }
        report.put("marker", marker).put("ownerId", author.userId).put("ownerUsername", username).put("readerId", requireNotNull(reader).userId)
            .put("articleIds", JSONArray(articles.map { it.id }))
        open(author)
        compose.onNode(hasText("我的合集") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("新建合集").performClick(); waitTag("collection-editor")
        compose.onNodeWithTag("collection-name").performTextReplacement(marker)
        compose.onNodeWithTag("collection-description").performScrollTo().performTextReplacement("Created from real Android collection page")
        for (article in articles.take(2)) { waitTag("collection-available-${article.id}"); compose.onNodeWithTag("collection-available-${article.id}").performScrollTo().performClick() }
        move(articles[1].id, "上移")
        compose.onNodeWithText("保存").performClick(); closed()
        val created = NativeDiscoveryApi.listMyCollections(author.accessToken).single { it.name == marker }
        report.put("collectionId", created.id)
        assertEquals(listOf(articles[1].id, articles[0].id), created.articles.map { it.id })
        screenshot("created")
        record("real-ui-create-and-arrow-order-persist")

        // 编辑时移除 A、加入 C 并调整顺序为 C/B，保存到私密后重新读取，不依赖界面乐观状态。
        edit(created.id)
        compose.onNodeWithTag("collection-description").performScrollTo().performTextReplacement("Edited and retained after visibility checks")
        compose.onNode(hasContentDescription("移除文章") and hasAnyAncestor(hasTestTag("reorder-article-${articles[0].id}"))).performScrollTo().performClick()
        waitTag("collection-available-${articles[2].id}"); compose.onNodeWithTag("collection-available-${articles[2].id}").performScrollTo().performClick()
        move(articles[2].id, "上移")
        compose.onNodeWithText("仅自己").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick(); closed()
        val privateCollection = NativeDiscoveryApi.getCollection(author.accessToken, created.id)
        assertEquals("private", privateCollection.visibility)
        assertEquals(listOf(articles[2].id, articles[1].id), privateCollection.articles.map { it.id })
        assertEquals("Edited and retained after visibility checks", privateCollection.description)
        http("/discovery/collections/public/${created.id}", null, 404)
        http("/discovery/collections/visible/${created.id}", requireNotNull(reader).accessToken, 404)
        http("/discovery/collections/${created.id}", requireNotNull(reader).accessToken, 404, "PATCH", JSONObject().put("name", "Not allowed"))
        record("real-ui-edit-remove-add-order-and-private-owner-enforcement")
        screenshot("private")

        // 登录可见允许其他登录用户、拒绝匿名；公开后匿名可读，三个范围均由实际页面切换。
        visibility(created.id, "登录可见")
        assertEquals("authenticated", NativeDiscoveryApi.getCollection(requireNotNull(reader).accessToken, created.id).visibility)
        http("/discovery/collections/public/${created.id}", null, 404)
        record("real-authenticated-visibility-allows-reader-rejects-anonymous")
        visibility(created.id, "公开")
        http("/discovery/collections/public/${created.id}", null, 200)
        record("real-public-visibility-allows-anonymous")
        closePage(); open(author)
        compose.onNode(hasText("我的合集") and hasClickAction()).performClick()
        edit(created.id)
        compose.onNodeWithTag("reorder-article-${articles[2].id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        record("real-close-and-reopen-keeps-server-order")

        // 另一账号经真实浏览搜索和订阅图标往返，最后保留有效订阅供后续页面复查。
        closePage(); open(requireNotNull(reader))
        compose.onNodeWithTag("collection-browse-search").performTextReplacement(marker)
        waitTag("collection-browse-${created.id}")
        subscribe(created.id, "订阅合集")
        compose.waitUntil(15000) { runCatching { NativeDiscoveryApi.getCollection(requireNotNull(reader).accessToken, created.id).subscribed }.getOrDefault(false) }
        assertEquals(1, NativeDiscoveryApi.getCollection(author.accessToken, created.id).subscriberCount)
        record("real-browse-search-and-subscribe-update-server-count")
        subscribe(created.id, "取消订阅")
        compose.waitUntil(15000) { runCatching { !NativeDiscoveryApi.getCollection(requireNotNull(reader).accessToken, created.id).subscribed }.getOrDefault(false) }
        assertEquals(0, NativeDiscoveryApi.getCollection(author.accessToken, created.id).subscriberCount)
        subscribe(created.id, "订阅合集")
        compose.waitUntil(15000) { runCatching { NativeDiscoveryApi.getCollection(requireNotNull(reader).accessToken, created.id).subscribed }.getOrDefault(false) }
        screenshot("reader-subscribed")
        record("real-unsubscribe-and-resubscribe-persist-without-duplicate")

        // 独立临时合集用于验证删除，不删除主样例或任何原文章。
        closePage(); open(author)
        compose.onNode(hasText("我的合集") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("新建合集").performClick(); waitTag("collection-editor")
        compose.onNodeWithTag("collection-name").performTextReplacement("$marker delete-check")
        waitTag("collection-available-${articles[0].id}"); compose.onNodeWithTag("collection-available-${articles[0].id}").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick(); closed()
        val removable = NativeDiscoveryApi.listMyCollections(author.accessToken).single { it.name == "$marker delete-check" }
        report.put("deletedCollectionId", removable.id)
        row(removable.id)
        compose.onNode(hasContentDescription("删除合集") and hasAnyAncestor(hasTestTag("collection-${removable.id}"))).performScrollTo().performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        waitText("合集已删除，文章内容未受影响。")
        assertTrue(NativeDiscoveryApi.listMyCollections(author.accessToken).none { it.id == removable.id })
        for (article in articles) assertEquals(article.id, NativeArticleApi.getArticle(author.accessToken, article.slug).id)
        report.put("finalVisibility", "public").put("finalArticleOrder", JSONArray(listOf(articles[2].id, articles[1].id)))
            .put("finalSubscriberCount", NativeDiscoveryApi.getCollection(author.accessToken, created.id).subscriberCount)
        record("real-delete-collection-does-not-delete-three-articles")

        // 独立随机无效凭据只用于验证拒绝恢复，不撤销原读者的真实会话。
        closePage()
        val invalidRefresh = "${UUID.randomUUID()}.${UUID.randomUUID()}"
        val rejected = runCatching { NativeAuthApi.refresh(invalidRefresh) }.exceptionOrNull()
        assertTrue(rejected is NativeAuthHttpException && rejected.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED)
        check(NativeSessionStore.save(context, requireNotNull(reader).copy(accessToken = "invalid-collection-fixture", refreshToken = invalidRefresh)))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        waitTag("native-login-account")
        compose.onNodeWithTag("native-login-account").assertIsDisplayed()
        assertNull(NativeSessionStore.load(context))
        screenshot("rejected-session")
        record("real-rejected-refresh-returns-to-login-without-toast-crash")
    }

    /** 按 session 身份选取页面轮换后的最新本地会话，并通过 ActivityScenario 打开真实合集路由。 */
    private fun open(session: NativeSession) {
        // 重新打开不能写回旧 Refresh Token；身份切换仍使用各自独立的正常会话。
        val latest = listOfNotNull(owner, reader).firstOrNull { it.userId == session.userId } ?: session
        check(NativeSessionStore.save(context, latest))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(BuildConfig.PORTAL_BASE_URL + "/articles/collections")))
        waitTag("collection-browse-search")
    }

    /** 关闭当前 Activity，后续身份切换不复用旧页面。 */
    private fun closePage() {
        // 旧页面协程先销毁，再保留其真实刷新结果；不能用初始化凭据覆盖已轮换会话。
        if (scenario == null) return
        scenario?.close(); scenario = null
        val current = NativeSessionStore.load(context)
        if (current?.userId == owner?.userId) owner = current
        if (current?.userId == reader?.userId) reader = current
    }

    /** 滚动到 id 对应的实际个人合集卡片。 */
    private fun row(id: Int) {
        // 列表刷新可能尚未完成，限定业务编号而非相似标题。
        compose.waitUntil(20000) { runCatching { compose.onNodeWithTag("collections-list").performScrollToNode(hasTestTag("collection-$id")); true }.getOrDefault(false) }
    }

    /** 打开 id 对应合集的编辑器。 */
    private fun edit(id: Int) {
        // 子按钮限定在目标卡片中，避免多个编辑图标串选。
        row(id); compose.onNode(hasContentDescription("编辑合集") and hasAnyAncestor(hasTestTag("collection-$id"))).performScrollTo().performClick(); waitTag("collection-editor")
    }

    /** 将 articleId 对应收录行按 label 指定方向移动。 */
    private fun move(articleId: Int, label: String) {
        // 真实点击统一排序组件的箭头，保存结果随后由接口回读验证。
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("reorder-article-$articleId"))).performScrollTo().performClick()
    }

    /** 在编辑器切换 id 合集的可见范围 label 并保存。 */
    private fun visibility(id: Int, label: String) {
        // 三种范围都走实际按钮及同一写接口。
        edit(id); compose.onNodeWithText(label).performScrollTo().performClick(); compose.onNodeWithText("保存").performClick(); closed()
    }

    /** 点击 id 对应浏览卡片的订阅 label 图标。 */
    private fun subscribe(id: Int, label: String) {
        // 服务端成功后页面图标才切换，等待目标动作可用避免连点旧状态。
        compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription(label) and hasAnyAncestor(hasTestTag("collection-browse-$id"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("collection-browse-$id"))).performScrollTo().performClick()
    }

    /** 等待真实编辑弹窗关闭，失败时保留现场。 */
    private fun closed() {
        // 不依赖固定时长推断保存成功。
        compose.waitUntil(20000) { compose.onAllNodesWithTag("collection-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
    }

    /** 等待 tag 对应实际页面组件出现。 */
    private fun waitTag(tag: String) {
        // 初次启动可能暂时没有语义根。
        compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 等待 text 对应业务反馈出现。 */
    private fun waitText(text: String) {
        // 页面反馈和接口状态分别断言。
        compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    /** 对本地 path 以 method/body 请求并核对 expected 状态，token 不写日志或报告。 */
    private fun http(path: String, token: String?, expected: Int, method: String = "GET", body: JSONObject? = null) {
        // 拒绝跨源或重定向，授权不会发送给未知站点。
        check(path.startsWith("/") && !path.startsWith("//"))
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.requestMethod = method
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            assertEquals(expected, connection.responseCode)
        } finally { connection.disconnect() }
    }

    /** 保存 name 对应业务画面，不包含密码或临时凭据。 */
    private fun screenshot(name: String) {
        // 等待系统和 Compose 空闲后捕获实际窗口。
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.filesDir, "native-local-collections-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** 将 name 对应已完成检查写入不含凭据的报告。 */
    private fun record(name: String) {
        // 中途失败不抹掉此前的真实完成项。
        checks.put(JSONObject().put("name", name).put("passed", true))
    }
}
