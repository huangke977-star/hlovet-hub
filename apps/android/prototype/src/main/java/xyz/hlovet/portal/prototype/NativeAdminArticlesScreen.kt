@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val nativeAdminArticleStatuses = listOf("published", "draft", "unpublished", "blocked", "deleted")
private val nativeAdminArticleVisibilities = listOf("public", "authenticated", "role_restricted", "private")

private data class NativeAdminAppealAction(
    val appeal: NativeArticleAppeal,
    val status: String,
)

private data class NativeAdminArticleReportAction(
    val report: NativeModerationReport,
    val action: String,
)

private data class NativeAdminArticleLoad(
    val articles: NativeArticleList,
    val appeals: List<NativeArticleAppeal>,
    val reports: NativeModerationReportPage,
    val pendingReports: NativeModerationReportPage,
    val reportSummary: NativeModerationReportSummary,
)

@Composable
internal fun NativeAdminArticlesScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenPreview: (Int) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var articles by remember(token) { mutableStateOf(NativeArticleList(emptyList(), 0, 1, 1)) }
    var selected by remember(token) { mutableStateOf<RemoteArticle?>(null) }
    var comments by remember(token) { mutableStateOf<List<NativeArticleComment>>(emptyList()) }
    var commentFilter by remember(token) { mutableStateOf("all") }
    var searchInput by remember(token) { mutableStateOf("") }
    var searchQuery by remember(token) { mutableStateOf("") }
    var statusFilter by remember(token) { mutableStateOf("") }
    var tab by remember(token) { mutableStateOf("articles") }
    var loading by remember(token) { mutableStateOf(token != null) }
    var commentsLoading by remember(token) { mutableStateOf(false) }
    var saving by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var selectedStatus by remember(token) { mutableStateOf("published") }
    var selectedVisibility by remember(token) { mutableStateOf("public") }
    var selectedRoles by remember(token) { mutableStateOf("") }
    var selectedPinned by remember(token) { mutableStateOf(false) }
    var selectedPinOrder by remember(token) { mutableStateOf(0) }
    var selectedTitleColor by remember(token) { mutableStateOf("") }
    var selectedBlockedReason by remember(token) { mutableStateOf("") }
    var appeals by remember(token) { mutableStateOf<List<NativeArticleAppeal>>(emptyList()) }
    var moderationReports by remember(token) { mutableStateOf<List<NativeModerationReport>>(emptyList()) }
    var pendingModerationReports by remember(token) { mutableStateOf<List<NativeModerationReport>>(emptyList()) }
    var reportSummary by remember(token) { mutableStateOf<NativeModerationReportSummary?>(null) }
    var reportQueueExpanded by remember(token) { mutableStateOf(false) }
    var highlightedCommentId by remember(token) { mutableStateOf<Int?>(null) }
    var appealAction by remember { mutableStateOf<NativeAdminAppealAction?>(null) }
    var appealResolution by remember { mutableStateOf("") }
    var appealBusy by remember { mutableStateOf(false) }
    var reportAction by remember { mutableStateOf<NativeAdminArticleReportAction?>(null) }
    var reportResolution by remember { mutableStateOf("") }
    var reportBusy by remember { mutableStateOf(false) }

    fun applySelection(article: RemoteArticle) {
        selected = article
        selectedStatus = article.status
        selectedVisibility = article.visibility
        selectedRoles = article.allowedRoleCodes.joinToString(", ")
        selectedPinned = article.isPinned
        selectedPinOrder = article.pinOrder
        selectedTitleColor = article.titleColor
        selectedBlockedReason = article.blockedReason.orEmpty()
    }

    fun loadComments(article: RemoteArticle) {
        val accessToken = token ?: return
        scope.launch {
            commentsLoading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeArticleApi.listAdminComments(accessToken, article.id) } }
                .onSuccess { comments = it }
                .onFailure { error = it.message ?: nativePhrase("评论读取失败。", "Could not load comments.") }
            commentsLoading = false
        }
    }

    LaunchedEffect(token, searchQuery, statusFilter, reloadKey) {
        if (token.isNullOrBlank()) {
            loading = false
            articles = NativeArticleList(emptyList(), 0, 1, 1)
            selected = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val articleResult = NativeArticleApi.listAdminArticles(token, 1, 12, searchQuery, statusFilter.ifBlank { null })
                val appealResult = NativeArticleApi.listAdminAppeals(token)
                val reportResult = NativeReportsApi.listAdmin(token, page = 1, pageSize = 50, status = "all", source = "all")
                val pendingReportResult = NativeReportsApi.listAdmin(token, page = 1, pageSize = 50, status = "pending", source = "all")
                val reportSummaryResult = NativeReportsApi.summary(token)
                NativeAdminArticleLoad(articleResult, appealResult, reportResult, pendingReportResult, reportSummaryResult)
            }
        }.onSuccess { result ->
            articles = result.articles
            appeals = result.appeals
            moderationReports = result.reports.items
            pendingModerationReports = result.pendingReports.items
            reportSummary = result.reportSummary
            val next = result.articles.items.firstOrNull { it.id == selected?.id } ?: result.articles.items.firstOrNull()
            if (next != null) {
                applySelection(next)
                if (tab == "comments") loadComments(next)
            } else {
                selected = null
                comments = emptyList()
            }
        }.onFailure { error = it.message ?: nativePhrase("文章管理读取失败。", "Could not load article management data.") }
        loading = false
    }

    LaunchedEffect(searchInput) {
        delay(300)
        if (searchQuery != searchInput.trim()) searchQuery = searchInput.trim()
    }

    fun saveArticle() {
        val accessToken = token ?: return
        val article = selected ?: return
        if (saving) return
        scope.launch {
            saving = true
            error = null
            notice = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.moderateAdminArticle(
                        accessToken,
                        article.id,
                        selectedStatus,
                        selectedVisibility,
                        selectedRoles.split(",").map(String::trim).filter(String::isNotBlank),
                        selectedPinned,
                        selectedPinOrder,
                        selectedTitleColor,
                        selectedBlockedReason,
                    )
                }
            }.onSuccess { updated ->
                applySelection(updated)
                articles = articles.copy(items = articles.items.map { if (it.id == updated.id) updated else it })
                notice = nativePhrase("文章管理设置已保存。", "Article management settings saved.")
            }.onFailure { error = it.message ?: nativePhrase("文章管理保存失败。", "Could not save article management settings.") }
            saving = false
        }
    }

    fun updateComment(comment: NativeArticleComment, nextStatus: String) {
        val accessToken = token ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeArticleApi.moderateAdminComment(accessToken, comment.id, nextStatus) } }
                .onSuccess {
                    comments = comments.map { if (it.id == comment.id) it.copy(status = nextStatus) else it }
                    notice = when (nextStatus) {
                        "active" -> nativePhrase("评论已恢复。", "Comment restored.")
                        "blocked" -> nativePhrase("评论已屏蔽。", "Comment blocked.")
                        else -> nativePhrase("评论已删除。", "Comment deleted.")
                    }
                }
                .onFailure { error = it.message ?: nativePhrase("评论处理失败。", "Could not update the comment.") }
        }
    }

    fun openReportAction(report: NativeModerationReport, action: String) {
        reportAction = NativeAdminArticleReportAction(report, action)
        reportResolution = ""
    }

    fun submitReportAction() {
        val accessToken = token ?: return
        val target = reportAction ?: return
        if (reportBusy) return
        val feedback = reportResolution.trim().ifBlank {
            if (target.action == "reject") nativePhrase("未发现违规。", "No violation found.")
            else nativePhrase("举报已处理。", "Report handled.")
        }
        reportBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val status = nativeAdminReportResolutionStatus(target.action)
                    val contentStatus = nativeAdminReportContentStatus(target.report.source, target.action)
                    when (target.report.source) {
                        "article" -> NativeReportsApi.moderateArticleReport(accessToken, target.report.id, status, feedback, contentStatus)
                        "comment" -> NativeReportsApi.moderateCommentReport(accessToken, target.report.id, status, feedback, contentStatus)
                        else -> error("Unsupported article report source")
                    }
                }
            }.onSuccess {
                reportAction = null
                reportResolution = ""
                notice = when (target.action) {
                    "reject" -> nativePhrase("举报已驳回。", "Report rejected.")
                    "resolve" -> nativePhrase("举报已处理。", "Report resolved.")
                    "block" -> nativePhrase("内容已屏蔽，举报已处理。", "Content blocked and report resolved.")
                    else -> nativePhrase("内容已删除，举报已处理。", "Content deleted and report resolved.")
                }
                reloadKey += 1
            }.onFailure { error = it.message ?: nativePhrase("举报处理失败。", "Could not process the report.") }
            reportBusy = false
        }
    }

    fun openAppealAction(appeal: NativeArticleAppeal, status: String) {
        appealAction = NativeAdminAppealAction(appeal, status)
        appealResolution = ""
    }

    fun submitAppealAction() {
        val accessToken = token ?: return
        val target = appealAction ?: return
        val resolution = appealResolution.trim()
        if (resolution.isBlank() || appealBusy) return
        scope.launch {
            appealBusy = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.moderateAdminAppeal(accessToken, target.appeal.id, target.status, resolution)
                }
            }.onSuccess {
                appealAction = null
                appealResolution = ""
                notice = if (target.status == "approved") nativePhrase("申诉已通过，文章已解除屏蔽。", "Appeal approved; the article is no longer blocked.") else nativePhrase("申诉已驳回。", "Appeal rejected.")
                reloadKey += 1
            }.onFailure { error = it.message ?: nativePhrase("申诉处理失败，请稍后重试。", "Could not process the appeal. Please try again.") }
            appealBusy = false
        }
    }

    fun openQueuedReport(report: NativeModerationReport) {
        val accessToken = token ?: return
        val articleId = report.article?.id ?: return
        reportQueueExpanded = false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeArticleApi.getAdminArticle(accessToken, articleId) } }
                .onSuccess { article ->
                    applySelection(article)
                    if (report.source == "comment") {
                        tab = "comments"
                        commentFilter = "all"
                        highlightedCommentId = report.comment?.id
                        loadComments(article)
                        notice = nativePhrase("已定位到被举报评论所属文章。", "Located the article containing the reported comment.")
                    } else {
                        tab = "articles"
                        notice = nativePhrase("已定位到被举报文章。", "Located the reported article.")
                    }
                }
                .onFailure { error = it.message ?: nativePhrase("举报内容暂时无法定位。", "Could not locate the reported content.") }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("文章管理", "Article management"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("审核文章、评论和可见范围", "Review articles, comments, and visibility"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            NativeAdminArticleToolbar(
                search = searchInput,
                onSearch = { searchInput = it },
                status = statusFilter,
                onStatus = { statusFilter = it },
                tab = tab,
                articleCount = articles.total,
                commentCount = selected?.commentCount ?: 0,
                pendingReportCount = reportSummary?.let { it.article + it.comment }
                    ?: pendingModerationReports.count { it.source in setOf("article", "comment") },
                pendingReports = pendingModerationReports.filter { it.source in setOf("article", "comment") },
                reportQueueExpanded = reportQueueExpanded,
                onReportQueueExpanded = { reportQueueExpanded = it },
                onOpenReport = ::openQueuedReport,
                onTab = { next -> tab = next; if (next == "comments") selected?.let(::loadComments) },
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) {
            item { NativeAdminArticleMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        }
        if (token.isNullOrBlank()) {
            item { NativeAdminArticleMessage(nativePhrase("登录后查看文章管理。", "Sign in to view article management."), false, null) }
        } else if (loading && articles.items.isEmpty()) {
            item { NativeAdminArticleMessage(nativePhrase("正在读取文章管理…", "Loading article management..."), false, null) }
        } else if (articles.items.isEmpty()) {
            item { NativeAdminArticleMessage(nativePhrase("当前筛选下没有文章。", "No articles match the current filters."), false, null) }
        } else {
            items(articles.items, key = { it.id }) { article ->
                NativeAdminArticleRow(article, selected?.id == article.id) {
                    applySelection(article)
                    if (tab == "comments") loadComments(article)
                }
            }
            if (articles.page < articles.totalPages) {
                item {
                    TextButton(
                        onClick = {
                            val accessToken = token ?: return@TextButton
                            scope.launch {
                                loading = true
                                runCatching { withContext(Dispatchers.IO) { NativeArticleApi.listAdminArticles(accessToken, articles.page + 1, 12, searchQuery, statusFilter.ifBlank { null }) } }
                                    .onSuccess { next -> articles = next.copy(items = articles.items + next.items.filter { row -> articles.items.none { it.id == row.id } }) }
                                    .onFailure { error = it.message ?: nativePhrase("更多文章读取失败。", "Could not load more articles.") }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) { Text(nativePhrase("加载更多文章（${articles.page}/${articles.totalPages}）", "Load more articles (${articles.page}/${articles.totalPages})"), fontSize = 11.sp) }
                }
            }
            selected?.let { article ->
                if (tab == "articles") {
                    item { NativeAdminArticleInspector(article, selectedStatus, selectedVisibility, selectedRoles, selectedPinned, selectedPinOrder, selectedTitleColor, selectedBlockedReason, saving, { id -> onOpenPreview(id) }, { selectedStatus = it }, { selectedVisibility = it }, { selectedRoles = it }, { selectedPinned = it }, { selectedPinOrder = it }, { selectedTitleColor = it }, { selectedBlockedReason = it }, ::saveArticle) }
                    item {
                        NativeAdminArticleReportsPanel(
                            article = article,
                            source = "article",
                            reports = moderationReports.filter { it.article?.id == article.id && it.source == "article" },
                            onAction = ::openReportAction,
                        )
                    }
                    item {
                        NativeAdminArticleAppealsPanel(
                            article = article,
                            appeals = appeals.filter { it.articleId == article.id },
                            onResolve = ::openAppealAction,
                        )
                    }
                } else {
                    item {
                        NativeAdminCommentsPanel(
                            article = article,
                            comments = comments,
                            loading = commentsLoading,
                            filter = commentFilter,
                            highlightedCommentId = highlightedCommentId,
                            onFilter = { commentFilter = it },
                            onStatus = { comment, status -> updateComment(comment, status) },
                            onReload = ::loadComments,
                            onHighlightComplete = { highlightedCommentId = null },
                        )
                    }
                    item {
                        NativeAdminArticleReportsPanel(
                            article = article,
                            source = "comment",
                            reports = moderationReports.filter { it.article?.id == article.id && it.source == "comment" },
                            onAction = ::openReportAction,
                        )
                    }
                }
            }
        }
    }

    appealAction?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!appealBusy) appealAction = null },
            title = { Text(if (target.status == "approved") nativePhrase("通过文章申诉", "Approve article appeal") else nativePhrase("驳回文章申诉", "Reject article appeal")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("${target.appeal.authorName} 对《${target.appeal.articleTitle}》提交了申诉。", "${target.appeal.authorName} submitted an appeal for “${target.appeal.articleTitle}”."), fontSize = 12.sp)
                    Text(target.appeal.reason, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(
                        value = appealResolution,
                        onValueChange = { appealResolution = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 7,
                        label = { Text(nativePhrase("处理反馈", "Resolution message")) },
                        placeholder = { Text(nativePhrase("反馈内容会发送给申诉作者", "This message will be sent to the appeal author")) },
                        enabled = !appealBusy,
                        colors = HlovetUi.fieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::submitAppealAction, enabled = appealResolution.isNotBlank() && !appealBusy) {
                    Text(if (appealBusy) nativePhrase("提交中…", "Submitting…") else nativePhrase("提交处理", "Submit decision"), color = if (target.status == "approved") HlovetUi.accent else HlovetUi.secondaryAccent)
                }
            },
            dismissButton = { TextButton(onClick = { appealAction = null }, enabled = !appealBusy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    reportAction?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!reportBusy) reportAction = null },
            title = { Text(nativeAdminArticleReportActionTitle(target.action)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${nativeAdminArticleReportSourceLabel(target.report.source)} · ${target.report.article?.title.orEmpty()}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    target.report.comment?.body?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    target.report.detail?.takeIf(String::isNotBlank)?.let {
                        Text(nativePhrase("举报说明：$it", "Report detail: $it"), color = HlovetUi.muted, fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    if (target.action != "reject") {
                        Text(nativePhrase("处理方式", "Action"), color = HlovetUi.muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            NativeAdminArticleReportActionButton(nativePhrase("保留", "Keep"), target.action == "resolve") { reportAction = target.copy(action = "resolve") }
                            NativeAdminArticleReportActionButton(nativePhrase("屏蔽", "Block"), target.action == "block") { reportAction = target.copy(action = "block") }
                            NativeAdminArticleReportActionButton(nativePhrase("删除", "Delete"), target.action == "delete") { reportAction = target.copy(action = "delete") }
                        }
                    }
                    OutlinedTextField(
                        value = reportResolution,
                        onValueChange = { reportResolution = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text(nativePhrase("处理反馈", "Resolution message")) },
                        enabled = !reportBusy,
                        colors = nativeAdminArticleFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::submitReportAction, enabled = !reportBusy) {
                    Text(
                        if (reportBusy) nativePhrase("处理中…", "Working…") else nativePhrase("确认", "Confirm"),
                        color = if (target.action == "reject") HlovetUi.secondaryAccent else HlovetUi.accent,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { reportAction = null }, enabled = !reportBusy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun NativeAdminArticleAppealsPanel(
    article: RemoteArticle,
    appeals: List<NativeArticleAppeal>,
    onResolve: (NativeArticleAppeal, String) -> Unit,
) {
    NativeAdminArticleSection(nativePhrase("文章申诉 · ${article.title}", "Article appeals · ${article.title}"), Icons.Filled.Shield) {
        if (appeals.isEmpty()) {
            Text(nativePhrase("当前文章没有申诉记录。", "This article has no appeals."), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            appeals.forEach { appeal ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appeal.authorName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(nativeArticleAppealStatusLabel(appeal.status), color = nativeArticleAppealStatusColor(appeal.status), fontSize = 9.sp)
                    }
                    Text(appeal.reason, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(appeal.createdAt, color = HlovetUi.muted, fontSize = 9.sp)
                    if (appeal.status == "pending") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onResolve(appeal, "rejected") }) { Text(nativePhrase("驳回", "Reject"), fontSize = 10.sp) }
                            TextButton(onClick = { onResolve(appeal, "approved") }) { Text(nativePhrase("通过申诉", "Approve appeal"), fontSize = 10.sp) }
                        }
                    } else if (!appeal.resolution.isNullOrBlank()) {
                        Text(nativePhrase("处理反馈：${appeal.resolution}", "Resolution: ${appeal.resolution}"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (appeal != appeals.last()) HorizontalDivider(color = HlovetUi.divider)
                }
            }
        }
    }
}

@Composable
private fun NativeAdminArticleReportsPanel(
    article: RemoteArticle,
    source: String,
    reports: List<NativeModerationReport>,
    onAction: (NativeModerationReport, String) -> Unit,
) {
    NativeAdminArticleSection(
        nativePhrase(
            if (source == "comment") "评论举报 · ${article.title}" else "文章举报 · ${article.title}",
            if (source == "comment") "Comment reports · ${article.title}" else "Article reports · ${article.title}",
        ),
        Icons.Filled.Flag,
    ) {
        if (reports.isEmpty()) {
            Text(
                nativePhrase("当前文章没有举报记录。", "This article has no report records."),
                color = HlovetUi.muted,
                fontSize = 11.sp,
            )
        } else {
            reports.forEach { report ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nativeAdminArticleReportReasonLabel(report.reason), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(nativeAdminArticleReportStatusLabel(report.status), color = if (report.status == "pending") HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 9.sp)
                    }
                    Text(
                        nativePhrase("${report.reporter.nickname} · ${report.createdAt}", "${report.reporter.nickname} · ${report.createdAt}"),
                        color = HlovetUi.muted,
                        fontSize = 9.sp,
                    )
                    report.detail?.takeIf(String::isNotBlank)?.let { Text(it, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    if (report.status == "pending") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onAction(report, "reject") }) { Text(nativePhrase("驳回", "Reject"), fontSize = 10.sp) }
                            TextButton(onClick = { onAction(report, "resolve") }) { Text(nativePhrase("保留并处理", "Resolve"), color = HlovetUi.accent, fontSize = 10.sp) }
                            TextButton(onClick = { onAction(report, "block") }) { Text(nativePhrase("屏蔽", "Block"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                            TextButton(onClick = { onAction(report, "delete") }) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                        }
                    }
                }
                if (report != reports.last()) HorizontalDivider(color = HlovetUi.divider)
            }
        }
    }
}

@Composable
private fun RowScope.NativeAdminArticleReportActionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, color = if (selected) HlovetUi.accent else HlovetUi.muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** 展示 search/status 筛选、tab 计数和 pendingReports 队列；on* 回调交由页面处理查询及跳转。 */
@Composable
private fun NativeAdminArticleToolbar(
    search: String,
    onSearch: (String) -> Unit,
    status: String,
    onStatus: (String) -> Unit,
    tab: String,
    articleCount: Int,
    commentCount: Int,
    pendingReportCount: Int,
    pendingReports: List<NativeModerationReport>,
    reportQueueExpanded: Boolean,
    onReportQueueExpanded: (Boolean) -> Unit,
    onOpenReport: (NativeModerationReport) -> Unit,
    onTab: (String) -> Unit,
) {
    // 筛选和举报队列共用工具栏，独立弹层统一保持清晰背景，避免正文透出影响操作。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(17.dp))
                OutlinedTextField(search, onSearch, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(nativePhrase("搜索标题、作者、分类或正文", "Search title, author, category, or body"), fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAdminArticleFieldColors())
                NativeAdminCompactMenu(nativePhrase("状态", "Status"), status.ifBlank { nativePhrase("全部", "All") }, listOf("" to nativePhrase("全部", "All")) + nativeAdminArticleStatuses.map { it to nativeAdminArticleStatusLabel(it) }, onStatus)
                Box {
                    TextButton(onClick = { onReportQueueExpanded(!reportQueueExpanded) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.Flag, contentDescription = nativePhrase("待处理举报", "Pending reports"), modifier = Modifier.size(15.dp))
                        Text(pendingReportCount.toString(), fontSize = 10.sp, color = if (pendingReportCount > 0) HlovetUi.secondaryAccent else HlovetUi.muted)
                    }
                    DropdownMenu(expanded = reportQueueExpanded, onDismissRequest = { onReportQueueExpanded(false) }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (pendingReports.isEmpty()) {
                                DropdownMenuItem(text = { Text(nativePhrase("暂无待处理举报", "No pending reports"), fontSize = 11.sp) }, onClick = { onReportQueueExpanded(false) })
                            } else {
                                pendingReports.forEach { report ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    nativePhrase(
                                                        "${if (report.source == "comment") "评论" else "文章"}举报 · ${report.article?.title.orEmpty()}",
                                                        "${if (report.source == "comment") "Comment" else "Article"} report · ${report.article?.title.orEmpty()}",
                                                    ),
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text("${report.reporter.nickname} · ${report.createdAt}", color = HlovetUi.muted, fontSize = 9.sp)
                                            }
                                        },
                                        onClick = { onOpenReport(report) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onTab("articles") }) { Icon(Icons.Filled.FilePresent, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("文章 $articleCount", "Articles $articleCount"), fontSize = 11.sp, fontWeight = if (tab == "articles") FontWeight.Bold else FontWeight.Normal) }
                TextButton(onClick = { onTab("comments") }) { Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("评论与回复 $commentCount", "Comments and replies $commentCount"), fontSize = 11.sp, fontWeight = if (tab == "comments") FontWeight.Bold else FontWeight.Normal) }
            }
        }
    }
}

@Composable
private fun NativeAdminArticleRow(article: RemoteArticle, selected: Boolean, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = if (selected) HlovetUi.cardShape else HlovetUi.rowShape) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (article.isPinned) Icons.Filled.Star else Icons.Filled.FilePresent, contentDescription = null, tint = if (article.isPinned) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(nativePhrase("${article.authorName} · ${nativeAdminArticleStatusLabel(article.status)} · ${article.commentCount} 评论", "${article.authorName} · ${nativeAdminArticleStatusLabel(article.status)} · ${article.commentCount} comments"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${article.viewCount}", color = HlovetUi.muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun NativeAdminArticleInspector(article: RemoteArticle, status: String, visibility: String, roles: String, pinned: Boolean, pinOrder: Int, titleColor: String, blockedReason: String, saving: Boolean, onPreview: (Int) -> Unit, onStatus: (String) -> Unit, onVisibility: (String) -> Unit, onRoles: (String) -> Unit, onPinned: (Boolean) -> Unit, onPinOrder: (Int) -> Unit, onTitleColor: (String) -> Unit, onBlockedReason: (String) -> Unit, onSave: () -> Unit) {
    NativeAdminArticleSection(nativePhrase("文章检查器", "Article inspector"), Icons.Filled.Visibility) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(article.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${article.authorName} · @${article.authorUsername}", color = HlovetUi.muted, fontSize = 10.sp)
            }
            TextButton(onClick = { onPreview(article.id) }) { Text(nativePhrase("预览", "Preview"), fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NativeAdminArticleStat(Icons.Filled.Visibility, article.viewCount.toString(), nativePhrase("阅读", "Views"))
            NativeAdminArticleStat(Icons.Filled.Favorite, article.likeCount.toString(), nativePhrase("点赞", "Likes"))
            NativeAdminArticleStat(Icons.Filled.Flag, article.commentCount.toString(), nativePhrase("评论", "Comments"))
            NativeAdminArticleStat(Icons.Filled.Bookmark, article.favoriteCount.toString(), nativePhrase("收藏", "Favorites"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NativeAdminCompactMenu(nativePhrase("文章状态", "Article status"), nativeAdminArticleStatusLabel(status), nativeAdminArticleStatuses.map { it to nativeAdminArticleStatusLabel(it) }, onStatus, Modifier.weight(1f))
            NativeAdminCompactMenu(nativePhrase("阅读权限", "Visibility"), nativeAdminArticleVisibilityLabel(visibility), nativeAdminArticleVisibilities.map { it to nativeAdminArticleVisibilityLabel(it) }, onVisibility, Modifier.weight(1f))
        }
        if (visibility == "role_restricted") OutlinedTextField(roles, onRoles, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("角色代码", "Role codes")) }, placeholder = { Text(nativePhrase("用逗号分隔", "Separate with commas")) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAdminArticleFieldColors())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(17.dp))
            Text(nativePhrase("置顶文章", "Pin article"), fontSize = 11.sp, modifier = Modifier.weight(1f))
            HlovetSwitch(pinned, onPinned)
        }
        if (pinned) NativeAdminArticleIntField(nativePhrase("置顶顺序", "Pin order"), pinOrder, onPinOrder)
        OutlinedTextField(titleColor, onTitleColor, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("标题颜色", "Title color")) }, placeholder = { Text(nativePhrase("例如 #2b2530", "e.g. #2b2530")) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAdminArticleFieldColors())
        OutlinedTextField(blockedReason, onBlockedReason, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text(nativePhrase("屏蔽说明", "Block reason")) }, placeholder = { Text(nativePhrase("文章被屏蔽时记录原因", "Reason recorded when the article is blocked")) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAdminArticleFieldColors())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onSave, enabled = !saving) { Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(15.dp)); Text(if (saving) nativePhrase("保存中…", "Saving…") else nativePhrase("保存设置", "Save settings"), fontSize = 11.sp) } }
    }
}

@Composable
private fun NativeAdminCommentsPanel(article: RemoteArticle, comments: List<NativeArticleComment>, loading: Boolean, filter: String, highlightedCommentId: Int?, onFilter: (String) -> Unit, onStatus: (NativeArticleComment, String) -> Unit, onReload: (RemoteArticle) -> Unit, onHighlightComplete: () -> Unit) {
    NativeAdminArticleSection(nativePhrase("评论与回复 · ${article.title}", "Comments and replies · ${article.title}"), Icons.Filled.Flag) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onFilter("all") }) { Text(nativePhrase("全部", "All"), fontSize = 10.sp, fontWeight = if (filter == "all") FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { onFilter("reported") }) { Text(nativePhrase("有举报", "Reported"), fontSize = 10.sp, fontWeight = if (filter == "reported") FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { onFilter("pending") }) { Text(nativePhrase("待处理", "Pending"), fontSize = 10.sp, fontWeight = if (filter == "pending") FontWeight.Bold else FontWeight.Normal) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onReload(article) }, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
        }
        val visibleComments = comments.filter { comment ->
            when (filter) {
                "reported" -> comment.reportCount > 0
                "pending" -> comment.pendingReportCount > 0
                else -> true
            }
        }
        if (loading) Text(nativePhrase("正在读取评论…", "Loading comments..."), color = HlovetUi.muted, fontSize = 11.sp)
        else if (visibleComments.isEmpty()) Text(if (filter == "all") nativePhrase("当前文章没有评论和回复。", "This article has no comments or replies.") else nativePhrase("当前筛选下没有评论和回复。", "No comments or replies match the current filter."), color = HlovetUi.muted, fontSize = 11.sp)
        else visibleComments.forEach { comment ->
            val bringIntoViewRequester = remember(comment.id) { BringIntoViewRequester() }
            val highlighted = highlightedCommentId == comment.id
            if (highlighted) {
                LaunchedEffect(comment.id) {
                    bringIntoViewRequester.bringIntoView()
                    delay(2000)
                    onHighlightComplete()
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester), shape = HlovetUi.rowShape) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (highlighted) Modifier.background(HlovetUi.accent.copy(alpha = 0.14f), HlovetUi.rowShape) else Modifier)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(comment.authorName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text(nativeAdminCommentStatusLabel(comment.status), color = if (comment.status == "active") HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 9.sp)
                        if (comment.pendingReportCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(nativePhrase("待处理举报 ${comment.pendingReportCount}", "${comment.pendingReportCount} pending reports"), color = HlovetUi.secondaryAccent, fontSize = 9.sp)
                        } else if (comment.reportCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(nativePhrase("举报 ${comment.reportCount}", "${comment.reportCount} reports"), color = HlovetUi.muted, fontSize = 9.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(comment.createdAt, color = HlovetUi.muted, fontSize = 9.sp)
                    }
                    Text(comment.body, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (comment.status != "active") TextButton(onClick = { onStatus(comment, "active") }) { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("恢复", "Restore"), fontSize = 10.sp) }
                        else TextButton(onClick = { onStatus(comment, "blocked") }) { Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("屏蔽", "Block"), fontSize = 10.sp) }
                        TextButton(onClick = { onStatus(comment, "deleted") }) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("删除", "Delete"), fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAdminArticleSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}

@Composable
private fun RowScope.NativeAdminArticleStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    GlassCard(modifier = Modifier.weight(1f), shape = HlovetUi.rowShape) { Column(Modifier.padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(14.dp)); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(label, color = HlovetUi.muted, fontSize = 8.sp) } }
}

/** 以 label/value 展示紧凑筛选器；options 为代码和文案，选中后通过 onChange 回传代码，modifier 控制布局。 */
@Composable
private fun NativeAdminCompactMenu(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // 值变化时收起旧菜单，选项使用共享弹层样式，不改变后台筛选代码。
    var expanded by remember(value) { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = HlovetUi.muted, fontSize = 9.sp)
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text(value, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("⌄", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) { options.forEach { option -> DropdownMenuItem(text = { Text(option.second, fontSize = 11.sp) }, onClick = { expanded = false; onChange(option.first) }) } }
    }
}

@Composable
private fun NativeAdminArticleIntField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(value.toString(), { onChange(it.filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(0) ?: value) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAdminArticleFieldColors())
}

@Composable
private fun NativeAdminArticleMessage(message: String, error: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (error) Icons.Filled.ErrorOutline else Icons.Filled.FilePresent, contentDescription = null, tint = if (error) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (error) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Close"), fontSize = 10.sp) } } } }
}

internal fun nativeAdminReportResolutionStatus(action: String): String =
    if (action == "reject") "rejected" else "resolved"

internal fun nativeAdminReportContentStatus(source: String, action: String): String? = when (action) {
    "block" -> "blocked"
    "delete" -> "deleted"
    else -> null
}.takeIf { source == "article" || source == "comment" }

private fun nativeAdminArticleReportActionTitle(action: String): String = when (action) {
    "reject" -> nativePhrase("驳回举报", "Reject report")
    "block" -> nativePhrase("屏蔽内容并处理举报", "Block content and resolve report")
    "delete" -> nativePhrase("删除内容并处理举报", "Delete content and resolve report")
    else -> nativePhrase("处理举报", "Resolve report")
}

private fun nativeAdminArticleReportSourceLabel(source: String): String = when (source) {
    "article" -> nativePhrase("文章举报", "Article report")
    "comment" -> nativePhrase("评论举报", "Comment report")
    else -> source
}

private fun nativeAdminArticleReportReasonLabel(reason: String): String = when (reason) {
    "spam" -> nativePhrase("垃圾信息", "Spam")
    "harassment" -> nativePhrase("骚扰辱骂", "Harassment")
    "illegal" -> nativePhrase("违法违规", "Illegal content")
    "privacy" -> nativePhrase("隐私泄露", "Privacy violation")
    "misinformation" -> nativePhrase("不实信息", "Misinformation")
    else -> nativePhrase("其他", "Other")
}

private fun nativeAdminArticleReportStatusLabel(status: String): String = when (status) {
    "resolved" -> nativePhrase("已处理", "Resolved")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> nativePhrase("待处理", "Pending")
}

private fun nativeAdminArticleStatusLabel(status: String): String = when (status) {
    "published" -> nativePhrase("已发布", "Published")
    "draft" -> nativePhrase("草稿", "Draft")
    "unpublished" -> nativePhrase("已下架", "Unpublished")
    "blocked" -> nativePhrase("受限", "Blocked")
    "deleted" -> nativePhrase("回收站", "Deleted")
    else -> status.ifBlank { nativePhrase("全部", "All") }
}

private fun nativeAdminArticleVisibilityLabel(visibility: String): String = when (visibility) {
    "public" -> nativePhrase("公开", "Public")
    "authenticated" -> nativePhrase("登录可见", "Signed-in users")
    "role_restricted" -> nativePhrase("指定角色", "Selected roles")
    "private" -> nativePhrase("仅自己", "Only me")
    else -> visibility
}

private fun nativeAdminCommentStatusLabel(status: String): String = when (status) {
    "active" -> nativePhrase("正常", "Active")
    "blocked" -> nativePhrase("已屏蔽", "Blocked")
    "deleted" -> nativePhrase("已删除", "Deleted")
    else -> status
}

private fun nativeArticleAppealStatusLabel(status: String): String = when (status) {
    "pending" -> nativePhrase("待处理", "Pending")
    "approved" -> nativePhrase("已通过", "Approved")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> status.ifBlank { nativePhrase("未知状态", "Unknown status") }
}

private fun nativeArticleAppealStatusColor(status: String) = when (status) {
    "approved" -> HlovetUi.accent
    "rejected" -> HlovetUi.secondaryAccent
    else -> HlovetUi.secondaryAccent
}

@Composable
private fun nativeAdminArticleFieldColors() = HlovetUi.fieldColors()
