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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NativeAdminViolationsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenArticle: (Int) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var authors by remember(token) { mutableStateOf<List<NativeViolationAuthor>>(emptyList()) }
    var selected by remember(token) { mutableStateOf<NativeViolationAuthorDetail?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var detailLoading by remember(token) { mutableStateOf(false) }
    var busy by remember(token) { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reason by remember(token) { mutableStateOf("") }
    var restrictionEnd by remember(token) { mutableStateOf("") }
    var pendingAction by remember(token) { mutableStateOf<String?>(null) }

    fun load() {
        if (token.isNullOrBlank()) return
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeAdminViolationsApi.list(token) } }
                .onSuccess { authors = it }
                .onFailure { error = it.message ?: nativePhrase("无法读取违规作者。", "Could not load restricted authors.") }
            loading = false
        }
    }

    fun openAuthor(author: NativeViolationAuthor) {
        if (token.isNullOrBlank() || detailLoading) return
        scope.launch {
            detailLoading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeAdminViolationsApi.get(token, author.user.id) } }
                .onSuccess {
                    selected = it
                    reason = it.summary.restriction?.reason.orEmpty()
                    restrictionEnd = it.summary.restriction?.endsAt?.replace("Z", "")?.take(16).orEmpty()
                }
                .onFailure { error = it.message ?: nativePhrase("无法读取作者详情。", "Could not load author details.") }
            detailLoading = false
        }
    }

    fun updateRestriction(action: String) {
        val target = selected ?: return
        if (token.isNullOrBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "remove" -> NativeAdminViolationsApi.updateRestriction(token, target.summary.user.id, active = false, reason = reason)
                        "permanent" -> NativeAdminViolationsApi.updateRestriction(token, target.summary.user.id, active = true, permanent = true, reason = reason)
                        else -> NativeAdminViolationsApi.updateRestriction(token, target.summary.user.id, active = true, endsAt = restrictionEnd.toRestrictionInstant(), reason = reason)
                    }
                }
            }.onSuccess {
                notice = if (action == "remove") nativePhrase("发布限制已解除。", "Publishing restriction removed.") else nativePhrase("发布限制已更新。", "Publishing restriction updated.")
                pendingAction = null
                selected = null
                reloadKey++
            }.onFailure { error = it.message ?: nativePhrase("限制更新失败。", "Could not update the restriction.") }
            busy = false
        }
    }

    LaunchedEffect(token, reloadKey) { load() }

    if (token.isNullOrBlank()) {
        NativeViolationGate(onBack)
        return
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
                        Text(nativePhrase("违规作者", "Restricted authors"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("查看举报趋势并管理文章发布限制", "Review report trends and manage publishing restrictions"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load, enabled = !loading && !detailLoading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) {
            item { NativeViolationMessage(error ?: notice.orEmpty(), error != null, onDismiss = { error = null; notice = null }) }
        }
        when {
            loading -> item { NativeViolationMessage(nativePhrase("正在读取违规作者…", "Loading restricted authors..."), false, null) }
            authors.isEmpty() -> item { NativeViolationMessage(nativePhrase("暂无违规作者记录。", "No restricted author records."), false, null) }
            else -> {
                item {
                    Text(nativePhrase("${authors.size} 位作者", "${authors.size} authors"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp))
                }
                items(authors, key = { it.user.id }) { author ->
                    NativeViolationAuthorRow(author, selected?.summary?.user?.id == author.user.id, detailLoading) { openAuthor(author) }
                }
                selected?.let { detail ->
                    item { NativeViolationDetail(detail, reason, restrictionEnd, busy, onReason = { reason = it }, onEnd = { restrictionEnd = it }, onAction = { action -> if (action == "close") selected = null else pendingAction = action }, onOpenArticle = onOpenArticle) }
                }
            }
        }
    }

    pendingAction?.let { action ->
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            title = { Text(if (action == "remove") nativePhrase("解除发布限制", "Remove publishing restriction") else nativePhrase("更新发布限制", "Update publishing restriction")) },
            text = { Text(if (action == "remove") nativePhrase("确定解除该账号的文章发布限制吗？", "Remove this account's article publishing restriction?") else if (action == "permanent") nativePhrase("确定永久限制该账号发布文章吗？", "Permanently restrict this account from publishing articles?") else nativePhrase("确定按当前结束时间限制该账号发布文章吗？", "Restrict this account until the current end time?"), fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = { TextButton(onClick = { updateRestriction(action) }, enabled = !busy) { Text(if (busy) nativePhrase("处理中…", "Working…") else nativePhrase("确认", "Confirm"), color = if (action == "remove") HlovetUi.accent else HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun NativeViolationGate(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(nativePhrase("违规作者", "Restricted authors"), fontSize = 19.sp, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        NativeViolationMessage(nativePhrase("登录后查看违规作者。", "Sign in to view restricted authors."), false, null)
    }
}

@Composable
private fun NativeViolationAuthorRow(author: NativeViolationAuthor, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(enabled = !busy, onClick = onClick), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeViolationAvatar(author.user.nickname)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(author.user.nickname, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nativePhrase("@${author.user.username} · 被举报 ${author.totalReceived} 次，近 30 天 ${author.recentReceived} 次", "@${author.user.username} · ${author.totalReceived} reports received, ${author.recentReceived} in the last 30 days"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (author.restriction != null) Icon(Icons.Filled.Block, contentDescription = nativePhrase("有发布限制", "Publishing restricted"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
            if (selected) Icon(Icons.Filled.Check, contentDescription = nativePhrase("已选择", "Selected"), tint = HlovetUi.accent, modifier = Modifier.padding(start = 5.dp).size(17.dp))
        }
    }
}

@Composable
private fun NativeViolationDetail(
    detail: NativeViolationAuthorDetail,
    reason: String,
    restrictionEnd: String,
    busy: Boolean,
    onReason: (String) -> Unit,
    onEnd: (String) -> Unit,
    onAction: (String) -> Unit,
    onOpenArticle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NativeViolationAvatar(detail.summary.user.nickname, large = true)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(detail.summary.user.nickname, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("@${detail.summary.user.username}", color = HlovetUi.muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { onAction("close") }, enabled = !busy, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭详情", "Close details"), modifier = Modifier.size(16.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    NativeViolationMetric(nativePhrase("被举报累计", "Reports received"), detail.summary.totalReceived)
                    NativeViolationMetric(nativePhrase("近 30 天", "Last 30 days"), detail.summary.recentReceived)
                    NativeViolationMetric(nativePhrase("举报他人", "Reports submitted"), detail.summary.totalSubmitted)
                    NativeViolationMetric(nativePhrase("近 30 天", "Last 30 days"), detail.summary.recentSubmitted)
                }
                val restriction = detail.summary.restriction
                val restrictionText = if (restriction == null) {
                    nativePhrase("发布限制：当前未限制", "Publishing: Not restricted")
                } else if (restriction.endsAt.isNullOrBlank()) {
                    nativePhrase("发布限制：永久限制", "Publishing: Permanent restriction")
                } else {
                    nativePhrase("发布限制：限制至 ${restriction.endsAt}", "Publishing: Until ${restriction.endsAt}")
                }
                Text(restrictionText, color = if (restriction == null) HlovetUi.muted else HlovetUi.secondaryAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                NativeViolationField(nativePhrase("限制原因", "Restriction reason"), reason, busy, false, onReason)
                if (detail.summary.restriction == null) NativeViolationField(nativePhrase("限制结束时间（留空默认 30 天）", "Restriction end (blank = 30 days)"), restrictionEnd, busy, true, onEnd)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (detail.summary.restriction != null) TextButton(onClick = { onAction("remove") }, enabled = !busy) { Text(nativePhrase("解除限制", "Remove restriction"), color = HlovetUi.accent) }
                    else {
                        TextButton(onClick = { onAction("temporary") }, enabled = !busy) { Text(nativePhrase("限制发布", "Restrict publishing"), color = HlovetUi.secondaryAccent) }
                        TextButton(onClick = { onAction("permanent") }, enabled = !busy) { Text(nativePhrase("永久限制", "Permanent restriction"), color = HlovetUi.secondaryAccent) }
                    }
                }
            }
        }
        NativeViolationReportList(nativePhrase("被举报记录", "Reports received"), detail.received, onOpenArticle)
        NativeViolationReportList(nativePhrase("举报他人记录", "Reports submitted"), detail.submitted, onOpenArticle)
    }
}

@Composable
private fun NativeViolationReportList(title: String, reports: List<NativeViolationReport>, onOpenArticle: (Int) -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(reports.size.toString(), color = HlovetUi.muted, fontSize = 10.sp)
            }
            if (reports.isEmpty()) Text(nativePhrase("暂无记录。", "No records."), color = HlovetUi.muted, fontSize = 11.sp)
            reports.take(30).forEach { report ->
                Column(Modifier.fillMaxWidth().clickable { onOpenArticle(report.articleId) }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FilePresent, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
                        Text(report.articleTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        Text(nativeViolationStatus(report.status), color = if (report.status == "resolved") HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 9.sp)
                    }
                    Text(nativePhrase("第 ${report.publicationNumber} 次发布 · ${nativeViolationReason(report.reason)} · ${report.createdAt}", "Publication ${report.publicationNumber} · ${nativeViolationReason(report.reason)} · ${report.createdAt}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
                    report.resolution?.takeIf(String::isNotBlank)?.let { Text(nativePhrase("处理反馈：$it", "Resolution: $it"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.padding(start = 22.dp, top = 2.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NativeViolationField(label: String, value: String, disabled: Boolean, singleLine: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = !disabled, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 2, label = { Text(label, fontSize = 11.sp) }, colors = HlovetUi.fieldColors())
}

@Composable
private fun NativeViolationMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Security, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            onDismiss?.let { IconButton(onClick = it, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(15.dp)) } }
        }
    }
}

@Composable
private fun NativeViolationAvatar(text: String, large: Boolean = false) {
    Surface(shape = CircleShape, color = HlovetUi.accentSoft, modifier = Modifier.size(if (large) 42.dp else 36.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(text.takeLast(2), color = HlovetUi.accent, fontSize = if (large) 12.sp else 10.sp, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun NativeViolationMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), color = HlovetUi.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(label, color = HlovetUi.muted, fontSize = 9.sp) }
}

private fun nativeViolationStatus(status: String): String = when (status) {
    "resolved" -> nativePhrase("已处理", "Resolved")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> nativePhrase("待处理", "Pending")
}

private fun nativeViolationReason(reason: String): String = when (reason) {
    "spam" -> nativePhrase("垃圾广告", "Spam")
    "harassment" -> nativePhrase("辱骂骚扰", "Harassment")
    "illegal" -> nativePhrase("违法违规", "Illegal content")
    "privacy" -> nativePhrase("隐私泄露", "Privacy leak")
    "misinformation" -> nativePhrase("不实内容", "Misinformation")
    "other" -> nativePhrase("其他", "Other")
    else -> reason.ifBlank { nativePhrase("未说明", "Not specified") }
}

private fun String.toRestrictionInstant(): String? {
    val value = trim()
    if (value.isBlank()) return null
    return runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            .atZone(ZoneId.systemDefault()).toInstant().toString()
    }.getOrElse { value }
}
