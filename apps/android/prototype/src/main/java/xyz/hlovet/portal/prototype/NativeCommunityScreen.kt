@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CommunityAccent: Color get() = HlovetUi.accent
private val CommunityMuted: Color get() = HlovetUi.muted
private val CommunityCoral: Color get() = HlovetUi.secondaryAccent

/** 保存本次公告打开失败的稳定目标和用户提示；只允许临时故障由用户明确重试。 */
private data class NativeAnnouncementOpenFailure(val id: Int, val message: String, val retryable: Boolean)

/** 用 session 读取公告列表与附件，initialAnnouncementId 指定首次详情，onBack 返回上级页面。 */
@Composable
internal fun NativeAnnouncementsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    initialAnnouncementId: Int? = null,
) {
    // 身份或令牌变化时取消旧请求，避免旧账号详情、附件和阅读状态进入新页面。
    key(session?.userId, session?.accessToken) {
        NativeAnnouncementsContent(session, onBack, initialAnnouncementId)
    }
}

/** 展示 session 的可见公告，initialAnnouncementId 可自动打开详情，onBack 返回上级。 */
@Composable
private fun NativeAnnouncementsContent(session: NativeSession?, onBack: () -> Unit, initialAnnouncementId: Int?) {
    // 附件与当前账号缓存来源绑定，列表、详情及图片预览继续保持各自状态。
    val token = session?.accessToken
    val context = LocalContext.current
    val cacheOwner = NativeOfflineOwner.forSession(session)
    val scope = rememberCoroutineScope()
    var page by remember(token) { mutableIntStateOf(1) }
    var data by remember(token, page) { mutableStateOf<NativeAnnouncementPage?>(null) }
    var detail by remember { mutableStateOf<NativeAnnouncementDetail?>(null) }
    var loading by remember(token, page) { mutableStateOf(true) }
    var error by remember(token, page) { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var openFailure by remember { mutableStateOf<NativeAnnouncementOpenFailure?>(null) }
    var previewImage by remember { mutableStateOf<NativeArticleImageTarget?>(null) }
    var downloadedAttachment by remember(token, cacheOwner) { mutableStateOf<NativeAttachmentFile?>(null) }
    val confirmedSummaries = remember { mutableMapOf<Int, NativeAnnouncementSummary>() }
    var listRequest by remember { mutableIntStateOf(0) }

    /** 重新读取当前页，只采用最新请求；已确认阅读的公告不能被较早发出的列表响应恢复成未读。 */
    fun load() {
        // 请求编号同时隔离刷新和翻页，不让先发后到的旧结果覆盖当前列表。
        val request = ++listRequest
        val requestedPage = page
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) { NativeCommunityApi.listAnnouncements(token, requestedPage) }
                if (request == listRequest && requestedPage == page) {
                    // 仅合并服务端仍可见的行，不把已归档或撤权的公告重新插回列表。
                    data = result.copy(items = result.items.map { item ->
                        val confirmed = confirmedSummaries[item.id]
                        if (confirmed == null) item else item.copy(unread = false, viewCount = maxOf(item.viewCount, confirmed.viewCount))
                    })
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 身份变化销毁组合时继续传播取消，不生成旧账号的错误反馈。
                throw cancelled
            } catch (exception: Exception) {
                if (request == listRequest && requestedPage == page) error = exception.message ?: nativePhrase("公告读取失败，请稍后重试。", "Announcements could not be loaded. Try again later.")
            } finally {
                // 只有当前请求能够结束当前加载指示。
                if (request == listRequest && requestedPage == page) loading = false
            }
        }
    }

    LaunchedEffect(token, page) { load() }

    /** 应用 result 的权威详情及阅读状态，同时更新已在列表中的对应行，不额外请求或增加浏览次数。 */
    fun applyAnnouncementDetail(result: NativeAnnouncementDetail) {
        // 只有服务端确认过的阅读才进入防回退集合，失败和游客浏览不能伪造阅读确认。
        detail = result
        if (!result.summary.unread && !result.confirmedAt.isNullOrBlank()) confirmedSummaries[result.summary.id] = result.summary
        data = data?.let { current -> current.copy(items = current.items.map { if (it.id == result.summary.id) result.summary else it }) }
    }

    /** 读取 id 的可见详情；成功自动确认阅读，失败保留列表并展示可关闭或手动重试的提示。 */
    fun openAnnouncement(id: Int) {
        // 加载面板显示期间拒绝重复打开，不能让后续目标被较早的详情响应覆盖。
        if (detailLoading) return
        scope.launch {
            detailLoading = true
            actionError = null
            openFailure = null
            detail = null
            try {
                val result = withContext(Dispatchers.IO) { NativeCommunityApi.getAnnouncement(token, id) }
                applyAnnouncementDetail(result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 取消不转成失败提示，旧身份不能在新页面留下详情。
                throw cancelled
            } catch (exception: Exception) {
                // 404 同时用于不存在和权限隐藏，不能推断或泄漏目标是否真实存在。
                val status = (exception as? NativeCommunityHttpException)?.statusCode
                val message = when (status) {
                    404, 410 -> nativePhrase("公告已下线、不存在或当前账号无权查看。", "This announcement is unavailable or you do not have access.")
                    403 -> nativePhrase("当前账号无权查看此公告。", "This account cannot view this announcement.")
                    401 -> nativePhrase("登录状态已失效，请重新登录后重试。", "Your session expired. Sign in again to view this announcement.")
                    else -> nativePhrase("公告详情暂时无法读取，请检查网络后重试。", "Announcement details could not be loaded. Check your connection and try again.")
                }
                val retryable = status !in listOf(401, 403, 404, 410)
                openFailure = NativeAnnouncementOpenFailure(id, message, retryable)
                // 失效目标不能触发自动重试详情；仅回读有权查看的列表，移除已下线的旧入口。
                if (status in listOf(403, 404, 410)) load()
            } finally {
                detailLoading = false
            }
        }
    }

    /** 按当前账号下载 target 公告附件，成功后复用文章及聊天的系统文件操作面板。 */
    fun openAnnouncementAttachment(target: NativeArticleAttachmentTarget) {
        // 下载复用现有来源白名单和有界缓存，不改变公告阅读状态。
        if (detailLoading) return
        scope.launch {
            detailLoading = true
            actionError = null
            try {
                val file = withContext(Dispatchers.IO) {
                    NativeOfflineCache.loadMediaFile(
                        context = context,
                        url = target.url,
                        accessToken = token,
                        cacheOwner = cacheOwner,
                        originalName = target.fileName,
                        mimeType = guessNativeArticleMimeType(target.url),
                    )
                } ?: throw IllegalStateException(nativePhrase("附件下载失败，请检查网络后重试。", "Attachment download failed. Check your connection and try again."))
                // 只有下载成功才显示操作面板，原始文件名不能替换为内部缓存摘要。
                downloadedAttachment = NativeAttachmentFile(file, target.fileName, guessNativeArticleMimeType(target.fileName))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 身份或页面变化时传播取消，不继续展示旧账号附件。
                throw cancelled
            } catch (exception: Exception) {
                actionError = exception.message ?: nativePhrase("附件打开失败。", "Failed to open the attachment.")
            } finally {
                // 下载失败也释放详情的加载状态，方便当前公告再次尝试。
                detailLoading = false
            }
        }
    }

    LaunchedEffect(initialAnnouncementId, token) {
        val targetId = initialAnnouncementId ?: return@LaunchedEffect
        if (detail?.summary?.id != targetId) openAnnouncement(targetId)
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("站点公告", "Announcements"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("更新、服务通知和重要说明", "Updates, service notices, and important information"), color = CommunityMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = CommunityAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading && data == null) item { communityEmpty(nativePhrase("正在读取公告…", "Loading announcements...")) }
        else if (error != null && data == null) {
            item { communityEmpty(error!!) }
            item { TextButton(onClick = ::load, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重新加载", "Reload")) } }
        } else if (data?.items.isNullOrEmpty()) item { communityEmpty(nativePhrase("当前没有可见公告。", "No announcements are visible.")) }
        else {
            item {
                Text(
                    nativePhrase("共 ${data?.total ?: 0} 条公告", "${data?.total ?: 0} announcements"),
                    color = CommunityMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
            items(data?.items.orEmpty(), key = { it.id }) { item ->
                GlassCard(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { openAnnouncement(item.id) },
                    shape = HlovetUi.rowShape,
                ) {
                    Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.isPinned) Icons.Filled.PushPin else Icons.Filled.NotificationsNone, null, tint = if (item.isPinned) CommunityCoral else CommunityAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (item.unread) Text(nativePhrase("未读", "Unread"), color = CommunityAccent, fontSize = 10.sp)
                            }
                            Text(item.summary.ifBlank { nativePhrase("打开查看公告详情", "Open to view announcement details") }, color = CommunityMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(communityDate(item.publishedAt.ifBlank { item.updatedAt }), color = CommunityMuted, fontSize = 10.sp)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.Visibility, null, tint = CommunityMuted, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(item.viewCount.toString(), color = CommunityMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            if ((data?.totalPages ?: 1) > 1) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(nativePhrase("第 ${data?.page ?: page} / ${data?.totalPages ?: 1} 页", "Page ${data?.page ?: page} of ${data?.totalPages ?: 1}"), color = CommunityMuted, fontSize = 11.sp)
                        Row {
                            TextButton(onClick = { page -= 1 }, enabled = page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                            TextButton(onClick = { page += 1 }, enabled = page < (data?.totalPages ?: 1)) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        error?.let { item { Text(it, color = CommunityCoral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    // 打开失败时没有详情弹窗，错误必须独立展示；重试只由用户点击，不能循环请求或清除会话。
    openFailure?.let { failure ->
        HlovetAlertDialog(
            onDismissRequest = { openFailure = null },
            title = { Text(nativePhrase("无法打开公告", "Announcement unavailable"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(failure.message, fontSize = 12.sp, color = CommunityMuted) },
            confirmButton = {
                TextButton(onClick = { if (failure.retryable) openAnnouncement(failure.id) else openFailure = null }) {
                    Text(if (failure.retryable) nativePhrase("重试", "Retry") else nativePhrase("关闭", "Close"), fontSize = 12.sp)
                }
            },
            dismissButton = if (failure.retryable) ({
                TextButton(onClick = { openFailure = null }) { Text(nativePhrase("关闭", "Close"), fontSize = 12.sp) }
            }) else null,
        )
    }

    detail?.let { current ->
        HlovetAlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(current.summary.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(communityDate(current.summary.publishedAt.ifBlank { current.summary.updatedAt }), color = CommunityMuted, fontSize = 11.sp)
                    NativeArticleBodyWebView(
                        content = current.content.ifBlank { current.summary.summary.ifBlank { nativePhrase("暂无正文。", "No content.") } },
                        contentFormat = nativeDetectedArticleContentFormat(current.content),
                        accessToken = token,
                        cacheOwner = cacheOwner,
                        onPreviewImage = { previewImage = it },
                        onOpenAttachment = ::openAnnouncementAttachment,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    actionError?.let { Text(it, color = CommunityCoral, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                if (!token.isNullOrBlank() && current.summary.unread) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.confirmAnnouncement(token, current.summary.id) } }
                                    .onSuccess { confirmedAt ->
                                        // 显式确认与打开详情共用权威同步，迟到的列表响应也不能恢复未读。
                                        applyAnnouncementDetail(current.copy(summary = current.summary.copy(unread = false), confirmedAt = confirmedAt))
                                    }
                                    .onFailure { actionError = it.message ?: nativePhrase("标记已读失败。", "The announcement could not be marked as read.") }
                            }
                        },
                    ) { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("标记已读", "Mark as read")) }
                } else TextButton(onClick = { detail = null }) { Text(nativePhrase("关闭", "Close")) }
            },
        )
    }
    // 公告附件使用与文章、聊天完全相同的玻璃面板和系统保存行为。
    downloadedAttachment?.let { attachment ->
        NativeAttachmentActionsDialog(attachment, onDismiss = { downloadedAttachment = null })
    }
    previewImage?.let { target ->
        NativeArticleImagePreviewDialog(
            target = target,
            accessToken = token,
            cacheOwner = cacheOwner,
            onDismiss = { previewImage = null },
        )
    }
    if (detailLoading) communityLoadingDialog()
}

@Composable
internal fun NativeFeedbackScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var page by remember(token) { mutableIntStateOf(1) }
    var data by remember(token, page) { mutableStateOf<NativeFeedbackPage?>(null) }
    var loading by remember(token, page) { mutableStateOf(true) }
    var error by remember(token, page) { mutableStateOf<String?>(null) }
    var search by remember(token) { mutableStateOf("") }
    var loadGeneration by remember(token) { mutableIntStateOf(0) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<NativeFeedbackDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("bug") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun load() {
        val generation = ++loadGeneration
        if (token.isNullOrBlank()) { loading = false; return }
        val query = search.trim()
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.listMyFeedback(token, page, query = query) } }
                .onSuccess { if (generation == loadGeneration) data = it }
                .onFailure { if (generation == loadGeneration) error = it.message ?: nativePhrase("反馈读取失败，请稍后重试。", "Feedback could not be loaded. Try again later.") }
            if (generation == loadGeneration) loading = false
        }
    }
    LaunchedEffect(token, page, search, reloadKey) {
        if (search.isNotBlank()) delay(250)
        load()
    }

    if (token.isNullOrBlank()) {
        CommunityLoginGate(nativePhrase("登录后可以提交和跟进反馈。", "Sign in to submit and follow up on feedback."), onLogin)
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("问题反馈", "Issue feedback"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("提交问题并查看处理进度", "Report issues and track progress"), color = CommunityMuted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = CommunityAccent) }; IconButton(onClick = { actionError = null; title = ""; content = ""; category = "bug"; createOpen = true }) { Icon(Icons.Filled.AddCircleOutline, nativePhrase("提交反馈", "Submit feedback"), tint = CommunityAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { value ->
                    search = value.take(120)
                    page = 1
                    loadGeneration += 1
                    data = null
                    error = null
                    loading = true
                },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                label = { Text(nativePhrase("搜索标题或内容", "Search titles or content")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (search.isBlank()) null else ({
                    IconButton(onClick = { search = ""; page = 1; loadGeneration += 1; data = null; error = null; loading = true }) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"))
                    }
                }),
                colors = communityFieldColors(),
            )
        }
        if (loading && data == null) item { communityEmpty(nativePhrase("正在读取反馈…", "Loading feedback...")) }
        else if (error != null && data == null) {
            item { communityEmpty(error!!) }
            item { TextButton(onClick = ::load, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重新加载", "Reload")) } }
        }
        else if (data?.items.isNullOrEmpty()) item {
            communityEmpty(
                if (search.isBlank()) nativePhrase("还没有反馈。点击右上角加号提交第一条。", "No feedback yet. Use the plus button to submit the first item.")
                else nativePhrase("没有匹配的反馈。", "No matching feedback."),
            )
        }
        else {
            item { Text(nativePhrase("我的反馈 · ${data?.total ?: 0} 条", "My feedback · ${data?.total ?: 0}"), color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(data?.items.orEmpty(), key = { it.id }) { item ->
                CommunityFeedbackRow(item) {
                    scope.launch {
                        detailLoading = true; actionError = null
                        runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.getFeedback(token, item.id) } }
                            .onSuccess { detail = it }
                            .onFailure { actionError = it.message ?: nativePhrase("反馈详情读取失败。", "Feedback details could not be loaded.") }
                        detailLoading = false
                    }
                }
            }
            if ((data?.totalPages ?: 1) > 1) item { CommunityPager(page, data?.totalPages ?: 1, { page -= 1 }, { page += 1 }) }
        }
        error?.let { item { Text(it, color = CommunityCoral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    if (createOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) createOpen = false },
            title = { Text(nativePhrase("提交反馈", "Submit feedback")) },
            text = {
                Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(nativePhrase("问题类型", "Issue type"), color = CommunityMuted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            "bug" to nativePhrase("功能问题", "Feature issue"),
                            "account" to nativePhrase("账号", "Account"),
                            "content" to nativePhrase("内容", "Content"),
                            "payment" to nativePhrase("支付", "Payment"),
                            "other" to nativePhrase("其他", "Other"),
                        ).forEach { (value, label) -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(label, fontSize = 10.sp) }, border = null) }
                    }
                    OutlinedTextField(title, { title = it.take(120) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("标题", "Title")) }, colors = communityFieldColors())
                    OutlinedTextField(content, { content = it.take(5000) }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, label = { Text(nativePhrase("具体内容", "Details")) }, colors = communityFieldColors())
                    actionError?.let { Text(it, color = CommunityCoral, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(enabled = !saving && title.isNotBlank() && content.isNotBlank(), onClick = {
                saving = true; actionError = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.createFeedback(token, category, title, content) } }
                        .onSuccess {
                            createOpen = false
                            title = ""
                            content = ""
                            search = ""
                            page = 1
                            data = null
                            loading = true
                            reloadKey += 1
                        }
                        .onFailure { actionError = it.message ?: nativePhrase("反馈提交失败。", "Feedback could not be submitted.") }
                    saving = false
                }
            }) { Text(if (saving) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交", "Submit"), color = CommunityAccent) } },
            dismissButton = { TextButton(onClick = { createOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    detail?.let { current ->
        HlovetAlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(current.summary.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    CommunityStatus(current.summary.status, feedbackStatusLabel(current.summary.status))
                    Text(current.content, fontSize = 13.sp, lineHeight = 20.sp)
                    if (current.replies.isNotEmpty()) {
                        Text(nativePhrase("回复", "Replies"), color = CommunityMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        current.replies.forEach { item ->
                            GlassCard(Modifier.fillMaxWidth(), HlovetUi.rowShape) { Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(item.author.nickname, fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Text(item.content, fontSize = 12.sp, lineHeight = 18.sp); Text(communityDate(item.createdAt), color = CommunityMuted, fontSize = 10.sp) } }
                        }
                    }
                    OutlinedTextField(reply, { reply = it.take(3000) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text(nativePhrase("补充回复（可选）", "Additional reply (optional)")) }, colors = communityFieldColors())
                    actionError?.let { Text(it, color = CommunityCoral, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(enabled = !saving && reply.isNotBlank(), onClick = {
                saving = true; actionError = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.replyFeedback(token, current.summary.id, reply) } }
                        .onSuccess { detail = it; reply = "" }
                        .onFailure { actionError = it.message ?: nativePhrase("回复提交失败。", "The reply could not be submitted.") }
                    saving = false
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("回复", "Reply")) } },
            dismissButton = { TextButton(onClick = { detail = null }) { Text(nativePhrase("关闭", "Close")) } },
        )
    }
    if (detailLoading) communityLoadingDialog()
}

@Composable
internal fun NativeSuggestionsPreview(onOpenAll: () -> Unit) {
    var page by remember { mutableStateOf<NativeSuggestionPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedDetail by remember { mutableStateOf<NativeSuggestionDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.listSuggestions(null, page = 1, pageSize = 5) } }
                .onSuccess { page = it }
                .onFailure { error = it.message ?: nativePhrase("建议读取失败，请稍后重试。", "Suggestions could not be loaded. Try again later.") }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(nativePhrase("意见建议", "Suggestions"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenAll, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text(nativePhrase("查看全部", "View all"), color = CommunityMuted, fontSize = 11.sp)
            }
        }
        GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.cardShape) {
            Column {
                when {
                    loading && page == null -> Text(nativePhrase("正在读取公开建议…", "Loading public suggestions…"), color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    error != null && page == null -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(error!!, color = CommunityCoral, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::load) { Text(nativePhrase("重试", "Retry"), fontSize = 11.sp) }
                    }
                    page?.items.isNullOrEmpty() -> Text(nativePhrase("暂无公开建议。", "No public suggestions yet."), color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    else -> page!!.items.forEachIndexed { index, item ->
                        SuggestionSummaryRow(item) {
                            scope.launch {
                                detailLoading = true
                                runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.getSuggestion(item.id) } }
                                    .onSuccess { selectedDetail = it }
                                    .onFailure { error = it.message ?: nativePhrase("建议详情读取失败。", "Suggestion details could not be loaded.") }
                                detailLoading = false
                            }
                        }
                        if (index < page!!.items.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }

    selectedDetail?.let {
        NativeSuggestionDetailDialog(
            detail = it,
            isAdminMode = false,
            isSaving = false,
            reply = "",
            onReplyChange = {},
            onChangeStatus = {},
            onReply = {},
            onDismiss = { selectedDetail = null },
        )
    }
    if (detailLoading) communityLoadingDialog()
}

@Composable
internal fun NativeSuggestionsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val token = session?.accessToken
    val isSuperAdmin = session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var mode by remember(token, isSuperAdmin) { mutableStateOf("public") }
    var page by remember(token) { mutableIntStateOf(1) }
    var data by remember(token, mode, page) { mutableStateOf<NativeSuggestionPage?>(null) }
    var loading by remember(token, mode, page) { mutableStateOf(true) }
    var error by remember(token, mode, page) { mutableStateOf<String?>(null) }
    var search by remember(token) { mutableStateOf("") }
    var loadGeneration by remember(token) { mutableIntStateOf(0) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<NativeSuggestionDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf("") }

    val modes = buildList {
        add("public" to nativePhrase("公开建议", "Public suggestions"))
        if (!token.isNullOrBlank()) add("mine" to nativePhrase("我的建议", "My suggestions"))
        if (isSuperAdmin) add("inbox" to nativePhrase("管理收件箱", "Admin inbox"))
    }

    fun load() {
        val generation = ++loadGeneration
        val query = search.trim()
        scope.launch {
            loading = true; error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    when (mode) {
                        "mine" -> token?.let { NativeCommunityApi.listMySuggestions(it, page, query = query) }
                        "inbox" -> token?.let { NativeCommunityApi.listSuggestionInbox(it, page, query = query) }
                        else -> NativeCommunityApi.listSuggestions(token, page, query = query)
                    } ?: NativeSuggestionPage(emptyList(), 0, page, 1)
                }
            }
                .onSuccess { if (generation == loadGeneration) data = it }
                .onFailure { if (generation == loadGeneration) error = it.message ?: nativePhrase("建议读取失败，请稍后重试。", "Suggestions could not be loaded. Try again later.") }
            if (generation == loadGeneration) loading = false
        }
    }
    LaunchedEffect(token, mode, page, search, reloadKey) {
        if (search.isNotBlank()) delay(250)
        load()
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("意见建议", "Suggestions"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("一起把站点变得更好", "Help make the site better"), color = CommunityMuted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = CommunityAccent) }; IconButton(onClick = { if (token.isNullOrBlank()) onLogin() else { title = ""; content = ""; actionError = null; createOpen = true } }) { Icon(Icons.Filled.AddCircleOutline, nativePhrase("提交建议", "Submit suggestion"), tint = CommunityAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                modes.forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = {
                            if (mode != value) {
                                mode = value
                                page = 1
                                loadGeneration += 1
                                data = null
                                error = null
                                loading = true
                            }
                        },
                        label = { Text(label, fontSize = 10.sp) },
                        border = null,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { value ->
                    search = value.take(120)
                    page = 1
                    loadGeneration += 1
                    data = null
                    error = null
                    loading = true
                },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                label = { Text(nativePhrase("搜索建议", "Search suggestions")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (search.isBlank()) null else ({
                    IconButton(onClick = { search = ""; page = 1; loadGeneration += 1; data = null; error = null; loading = true }) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"))
                    }
                }),
                colors = communityFieldColors(),
            )
        }
        item { Text("${modes.firstOrNull { it.first == mode }?.second ?: nativePhrase("建议", "Suggestions")} · ${nativePhrase("${data?.total ?: 0} 条", "${data?.total ?: 0}")}", color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (loading && data == null) item { communityEmpty(nativePhrase("正在读取建议…", "Loading suggestions...")) }
        else if (error != null && data == null) {
            item { communityEmpty(error!!) }
            item { TextButton(onClick = ::load, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重新加载", "Reload")) } }
        }
        else if (data?.items.isNullOrEmpty()) item {
            communityEmpty(
                if (search.isBlank()) nativePhrase("还没有公开建议。登录后可以提交你的想法。", "No public suggestions yet. Sign in to share an idea.")
                else nativePhrase("没有匹配的建议。", "No matching suggestions."),
            )
        }
        else {
            items(data?.items.orEmpty(), key = { it.id }) { item ->
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    SuggestionSummaryRow(item) {
                    scope.launch {
                        detailLoading = true; actionError = null
                        runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.getSuggestion(item.id) } }
                            .onSuccess { detail = it; reply = "" }
                            .onFailure { actionError = it.message ?: nativePhrase("建议详情读取失败。", "Suggestion details could not be loaded.") }
                        detailLoading = false
                    }
                    }
                }
            }
            if ((data?.totalPages ?: 1) > 1) item { CommunityPager(page, data?.totalPages ?: 1, { page -= 1 }, { page += 1 }) }
        }
        error?.let { item { Text(it, color = CommunityCoral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    if (createOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) createOpen = false },
            title = { Text(nativePhrase("提交建议", "Submit suggestion")) },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("标题", "Title")) }, colors = communityFieldColors())
                OutlinedTextField(content, { content = it.take(4000) }, modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 11, label = { Text(nativePhrase("建议内容", "Suggestion")) }, colors = communityFieldColors())
                actionError?.let { Text(it, color = CommunityCoral, fontSize = 12.sp) }
            } },
            confirmButton = { TextButton(enabled = !saving && title.isNotBlank() && content.isNotBlank() && !token.isNullOrBlank(), onClick = {
                token?.let { currentToken ->
                    saving = true; actionError = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.createSuggestion(currentToken, title, content) } }
                            .onSuccess { created ->
                                detail = created
                                createOpen = false
                                title = ""
                                content = ""
                                search = ""
                                page = 1
                                data = null
                                loading = true
                                reloadKey += 1
                            }
                            .onFailure { actionError = it.message ?: nativePhrase("建议提交失败。", "The suggestion could not be submitted.") }
                        saving = false
                    }
                }
            }) { Text(if (saving) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交", "Submit"), color = CommunityAccent) } },
            dismissButton = { TextButton(onClick = { createOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    detail?.let { current ->
        NativeSuggestionDetailDialog(
            detail = current,
            isAdminMode = mode == "inbox" && isSuperAdmin,
            isSaving = saving,
            reply = reply,
            error = actionError,
            onReplyChange = { reply = it.take(2000) },
            onChangeStatus = { status ->
                token?.let { currentToken ->
                    saving = true
                    actionError = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.updateSuggestionStatus(currentToken, current.summary.id, status) } }
                            .onSuccess { updated ->
                                detail = updated
                                data = data?.copy(items = data?.items.orEmpty().map { if (it.id == updated.summary.id) updated.summary else it })
                            }
                            .onFailure { actionError = it.message ?: nativePhrase("更新建议进度失败。", "Suggestion progress could not be updated.") }
                        saving = false
                    }
                }
            },
            onReply = {
                if (reply.isNotBlank()) token?.let { currentToken ->
                    saving = true
                    actionError = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.replyToSuggestion(currentToken, current.summary.id, reply) } }
                            .onSuccess { updated ->
                                detail = updated
                                reply = ""
                                data = data?.copy(items = data?.items.orEmpty().map { if (it.id == updated.summary.id) updated.summary else it })
                            }
                            .onFailure { actionError = it.message ?: nativePhrase("回复建议失败。", "The suggestion reply could not be sent.") }
                        saving = false
                    }
                }
            },
            onDismiss = { detail = null; actionError = null; reply = "" },
        )
    }
    if (detailLoading) communityLoadingDialog()
}

@Composable
private fun SuggestionSummaryRow(item: NativeSuggestionSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CommunityAccent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                nativePhrase("${item.replyCount} 条回复 · ${communityDate(item.updatedAt)}", "${item.replyCount} replies · ${communityDate(item.updatedAt)}"),
                color = CommunityMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CommunityStatus(item.status, suggestionStatusLabel(item.status))
    }
}

/** 展示 detail 建议及回复；isAdminMode/isSaving 限制管理操作，reply/error 回显表单状态，on* 回调负责变更和关闭。 */
@Composable
private fun NativeSuggestionDetailDialog(
    detail: NativeSuggestionDetail,
    isAdminMode: Boolean,
    isSaving: Boolean,
    reply: String,
    error: String? = null,
    onReplyChange: (String) -> Unit,
    onChangeStatus: (String) -> Unit,
    onReply: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 更新进度菜单与详情共用弹窗配色，状态变更成功后通过详情新值重置菜单。
    var statusMenuOpen by remember(detail.summary.status) { mutableStateOf(false) }
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.summary.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                CommunityStatus(detail.summary.status, suggestionStatusLabel(detail.summary.status))
                if (isAdminMode) {
                    Box {
                        TextButton(onClick = { statusMenuOpen = true }, enabled = !isSaving) {
                            Text(
                                nativePhrase(
                                    "更新进度：${suggestionStatusLabel(detail.summary.status)}",
                                    "Update status: ${suggestionStatusLabel(detail.summary.status)}",
                                ),
                                color = CommunityAccent,
                                fontSize = 11.sp,
                            )
                        }
                        DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                            listOf("pending", "scheduled", "in_progress", "completed", "rejected").forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(suggestionStatusLabel(value), fontSize = 11.sp) },
                                    onClick = { statusMenuOpen = false; onChangeStatus(value) },
                                )
                            }
                        }
                    }
                }
                Text(detail.content, fontSize = 13.sp, lineHeight = 20.sp)
                // 回复按接口顺序展示，空列表不额外占用详情空间。
                if (detail.replies.isNotEmpty()) {
                    Text(nativePhrase("回复", "Replies"), color = CommunityMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    detail.replies.forEach { reply ->
                        GlassCard(Modifier.fillMaxWidth(), HlovetUi.rowShape) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(reply.author.nickname, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(reply.content, fontSize = 12.sp, lineHeight = 18.sp)
                                Text(communityDate(reply.createdAt), color = CommunityMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
                // 仅管理员可以回复，失败留在当前弹窗，发送中禁止重复提交。
                if (isAdminMode) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = onReplyChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text(nativePhrase("回复建议", "Reply to suggestion")) },
                        colors = communityFieldColors(),
                    )
                    TextButton(onClick = onReply, enabled = !isSaving && reply.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isSaving) nativePhrase("发送中…", "Sending...") else nativePhrase("发送回复", "Send reply"))
                    }
                }
                error?.let { Text(it, color = CommunityCoral, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(nativePhrase("关闭", "Close")) } },
    )
}

@Composable
private fun CommunityFeedbackRow(item: NativeFeedbackSummary, onClick: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick), HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChatBubbleOutline, null, tint = CommunityAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CommunityStatus(item.status, feedbackStatusLabel(item.status))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        nativePhrase("${item.replyCount} 条回复 · ${communityDate(item.updatedAt)}", "${item.replyCount} replies · ${communityDate(item.updatedAt)}"),
                        color = CommunityMuted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityStatus(@Suppress("UNUSED_PARAMETER") value: String, label: String) {
    HlovetBadge(text = label, containerColor = CommunityAccent.copy(alpha = .14f), contentColor = CommunityAccent)
}

@Composable
private fun CommunityPager(page: Int, totalPages: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(nativePhrase("第 $page / $totalPages 页", "Page $page of $totalPages"), color = CommunityMuted, fontSize = 11.sp)
        Row {
            TextButton(onClick = onPrevious, enabled = page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
            TextButton(onClick = onNext, enabled = page < totalPages) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
        }
    }
}

@Composable
private fun CommunityLoginGate(message: String, onLogin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.ChatBubbleOutline, null, tint = CommunityAccent, modifier = Modifier.size(34.dp))
        Text(message, color = CommunityMuted, fontSize = 13.sp)
        TextButton(onClick = onLogin) { Text(nativePhrase("去登录", "Sign in"), color = CommunityAccent) }
    }
}

@Composable
private fun communityEmpty(message: String) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) { Text(message, color = CommunityMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp)) }
}

@Composable
private fun communityLoadingDialog() {
    HlovetAlertDialog(
        onDismissRequest = {},
        title = { Text(nativePhrase("正在加载", "Loading")) },
        text = { Text(nativePhrase("请稍候…", "Please wait..."), color = CommunityMuted) },
        confirmButton = {},
    )
}

@Composable
private fun communityFieldColors() = HlovetUi.fieldColors(
    focusedBorderColor = CommunityAccent,
)

internal fun communityDate(value: String): String = value.replace('T', ' ').substringBefore('.').take(16).ifBlank {
    nativePhrase("时间未知", "Unknown time")
}

private fun feedbackStatusLabel(value: String): String = when (value) {
    "in_progress" -> nativePhrase("处理中", "In progress")
    "resolved" -> nativePhrase("已解决", "Resolved")
    "closed" -> nativePhrase("已关闭", "Closed")
    else -> nativePhrase("待处理", "Pending")
}

private fun suggestionStatusLabel(value: String): String = when (value) {
    "scheduled" -> nativePhrase("已排期", "Scheduled")
    "in_progress" -> nativePhrase("进行中", "In progress")
    "completed" -> nativePhrase("已完成", "Completed")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> nativePhrase("待评估", "Pending review")
}

private fun androidx.compose.ui.Modifier.horizontalScrollIfNeeded(): androidx.compose.ui.Modifier = this
