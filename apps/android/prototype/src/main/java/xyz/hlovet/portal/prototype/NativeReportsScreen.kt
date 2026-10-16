@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 展示 session 本人的举报；onBack 返回上一页，onOpenArticle 打开已授权的文章预览，账号变化销毁旧状态。 */
@Composable
internal fun NativeReportsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
) {
    // 身份边界同时覆盖页面状态和协程，不能把旧账号的迟到结果交给新账号。
    key(session?.userId, session?.accessToken) {
        NativeReportsContent(session, onBack, onOpenArticle)
    }
}

/** 为当前 session 加载举报和详情；导航回调仅接收服务器确认可预览的文章。 */
@Composable
private fun NativeReportsContent(session: NativeSession?, onBack: () -> Unit, onOpenArticle: (RemoteArticle) -> Unit) {
    // 所有请求归属当前组合生命周期，页面销毁时取消未完成的预览。
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var reports by remember(token) { mutableStateOf<List<NativeModerationReport>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<NativeModerationReport?>(null) }
    var articleLoading by remember { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        // 直接使用效果协程，刷新或离开时取消旧读取，不启动脱离效果生命周期的请求。
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后查看举报记录。", "Sign in to view your reports.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            reports = withContext(Dispatchers.IO) { NativeReportsApi.list(currentToken) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            // 刷新失败保留已有记录，同时在列表上方明确展示失败原因。
            error = exception.message ?: nativePhrase("举报记录加载失败，请稍后重试。", "Could not load reports. Please try again later.")
        } finally {
            loading = false
        }
    }

    /** 打开 report 对应详情；文章先验证专用预览权限，评论与群消息不跳转到已失效的原位置。 */
    fun openReport(report: NativeModerationReport) {
        // 非文章举报展示服务器返回的详情，附件仍由独立下载接口校验当前成员权限。
        if (report.source != "article" || report.article == null) {
            selected = report
            return
        }
        val currentToken = token ?: return
        if (articleLoading) return
        articleLoading = true
        scope.launch {
            try {
                val article = withContext(Dispatchers.IO) { NativeReportsApi.getArticlePreview(currentToken, report.id) }
                onOpenArticle(article)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("被举报文章暂时无法打开。", "The reported article cannot be opened right now.")
            } finally {
                articleLoading = false
            }
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
                        Text(nativePhrase("我的举报", "My reports"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("查看提交记录和处理结果", "Review submitted reports and outcomes"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() && reports.isNotEmpty()) item {
            Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp))
        }
        if (loading && reports.isEmpty()) {
            item { reportEmpty(nativePhrase("正在读取举报记录…", "Loading reports...")) }
        } else if (!error.isNullOrBlank() && reports.isEmpty()) {
            item { reportEmpty(error!!) }
        } else if (reports.isEmpty()) {
            item { reportEmpty(nativePhrase("暂无举报记录。", "No reports yet.")) }
        } else {
            item { Text(nativePhrase("共 ${reports.size} 条记录", "${reports.size} reports"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(reports, key = { it.key }) { report ->
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("my-report-${report.key}").clickable { openReport(report) }, shape = HlovetUi.cardShape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(reportIcon(report.source), null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(reportTitle(report), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${reportSource(report)} · ${reportReason(report.reason)} · ${reportTime(report.createdAt)}",
                                color = HlovetUi.muted,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            report.resolution?.takeIf(String::isNotBlank)?.let { Text(nativePhrase("处理反馈：$it", "Resolution: $it"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(statusIcon(report.status), null, tint = statusColor(report.status), modifier = Modifier.size(15.dp))
                            Text(statusLabel(report.status), color = statusColor(report.status), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    selected?.let { report ->
        HlovetAlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(reportIcon(report.source), null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(reportSource(report))
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    ReportDetailLine(nativePhrase("状态", "Status"), statusLabel(report.status))
                    ReportDetailLine(nativePhrase("提交时间", "Submitted"), reportTime(report.createdAt))
                    report.handledAt?.let { ReportDetailLine(nativePhrase("处理时间", "Handled"), reportTime(it)) }
                    ReportDetailLine(nativePhrase("举报原因", "Reason"), reportReason(report.reason))
                    report.targetUser?.let { ReportDetailLine(nativePhrase("涉及用户", "User"), "${it.nickname} (@${it.username})") }
                    report.article?.let { ReportDetailLine(nativePhrase("文章", "Article"), it.title) }
                    report.comment?.let {
                        Text(nativePhrase("被举报评论", "Reported comment"), color = HlovetUi.muted, fontSize = 11.sp)
                        GlassCard(shape = HlovetUi.rowShape) { Text(it.body.ifBlank { nativePhrase("（评论内容为空）", "(empty comment)") }, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
                    }
                    report.group?.let { ReportDetailLine(nativePhrase("群聊", "Group"), it.name) }
                    report.message?.let {
                        Text(nativePhrase("被举报消息", "Reported message"), color = HlovetUi.muted, fontSize = 11.sp)
                        NativeReportMessagePreview(report, session)
                    }
                    report.detail?.takeIf(String::isNotBlank)?.let { ReportDetailLine(nativePhrase("补充说明", "Additional details"), it) }
                    report.resolution?.takeIf(String::isNotBlank)?.let { ReportDetailLine(nativePhrase("处理反馈", "Resolution"), it) }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(nativePhrase("关闭", "Close"), color = HlovetUi.accent) } },
        )
    }
}

@Composable
private fun ReportDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 11.sp)
        Text(value.ifBlank { "—" }, fontSize = 13.sp)
    }
}

private fun reportTitle(report: NativeModerationReport): String = when (report.source) {
    "article" -> report.article?.title ?: nativePhrase("文章内容", "Article content")
    "comment" -> report.comment?.body?.ifBlank { nativePhrase("评论内容", "Comment content") } ?: nativePhrase("评论内容", "Comment content")
    else -> report.message?.body?.ifBlank { report.message.attachments.firstOrNull()?.originalName ?: nativePhrase("群消息内容", "Group message content") } ?: nativePhrase("群消息内容", "Group message content")
}

private fun reportSource(report: NativeModerationReport): String = when (report.source) {
    "article" -> nativePhrase("文章举报", "Article report")
    "comment" -> nativePhrase("评论举报", "Comment report")
    "group_message" -> nativePhrase("群消息举报", "Group message report")
    else -> report.sourceLabel.ifBlank { nativePhrase("举报记录", "Report") }
}

private fun reportReason(reason: String): String = when (reason) {
    "spam" -> nativePhrase("垃圾广告", "Spam")
    "harassment" -> nativePhrase("辱骂骚扰", "Harassment")
    "illegal" -> nativePhrase("违法违规", "Illegal content")
    "privacy" -> nativePhrase("隐私泄露", "Privacy leak")
    "misinformation" -> nativePhrase("不实内容", "Misinformation")
    "other" -> nativePhrase("其他", "Other")
    else -> reason.ifBlank { nativePhrase("其他", "Other") }
}

private fun statusLabel(status: String): String = when (status) {
    "resolved" -> nativePhrase("已处理", "Resolved")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> nativePhrase("待处理", "Pending")
}

private fun statusColor(status: String): Color = when (status) {
    "resolved" -> HlovetUi.accent
    "rejected" -> HlovetUi.secondaryAccent
    else -> Color(0xFFB7791F)
}

private fun reportIcon(source: String) = when (source) {
    "comment" -> Icons.Filled.ChatBubbleOutline
    "group_message" -> Icons.Filled.ChatBubbleOutline
    else -> Icons.Filled.Report
}

private fun statusIcon(status: String) = when (status) {
    "resolved" -> Icons.Filled.CheckCircle
    "rejected" -> Icons.Filled.Close
    else -> Icons.Filled.Schedule
}

private fun reportTime(value: String): String = when {
    value.length >= 16 -> value.substring(0, 16).replace('T', ' ')
    value.isBlank() -> ""
    else -> value
}

@Composable
private fun reportEmpty(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}
