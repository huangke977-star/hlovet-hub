@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val nativeFeedbackStatuses = listOf("all", "pending", "in_progress", "resolved", "closed")

@Composable
internal fun NativeAdminFeedbackScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var data by remember(token) { mutableStateOf<NativeFeedbackPage?>(null) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var status by remember(token) { mutableStateOf("all") }
    var page by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var detailLoading by remember(token) { mutableStateOf(false) }
    var detail by remember(token) { mutableStateOf<NativeFeedbackDetail?>(null) }
    var reply by remember(token) { mutableStateOf("") }
    var saving by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }

    LaunchedEffect(searchDraft) {
        delay(350)
        val next = searchDraft.trim()
        if (next != search) {
            search = next
            page = 1
        }
    }

    LaunchedEffect(token, page, search, status, reloadKey) {
        if (token.isNullOrBlank()) {
            loading = false
            data = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { NativeCommunityApi.listFeedbackInbox(token, page = page, query = search, status = status) }
        }.onSuccess { result ->
            data = result
            if (result.page != page) page = result.page
        }.onFailure { error = it.message ?: nativePhrase("无法读取用户反馈。", "Could not load user feedback.") }
        loading = false
    }

    fun openDetail(id: Int) {
        if (token.isNullOrBlank() || detailLoading) return
        detailLoading = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.getFeedback(token, id) } }
                .onSuccess { detail = it; reply = "" }
                .onFailure { error = it.message ?: nativePhrase("无法读取反馈详情。", "Could not load feedback details.") }
            detailLoading = false
        }
    }

    fun updateStatus(nextStatus: String) {
        val current = detail ?: return
        if (token.isNullOrBlank() || saving || nextStatus == current.summary.status) return
        saving = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.updateFeedbackStatus(token, current.summary.id, nextStatus) } }
                .onSuccess {
                    detail = it
                    notice = nativePhrase("反馈状态已更新。", "Feedback status updated.")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("状态更新失败。", "Could not update the status.") }
            saving = false
        }
    }

    fun sendReply() {
        val current = detail ?: return
        val body = reply.trim()
        if (token.isNullOrBlank() || saving || body.isBlank()) return
        saving = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeCommunityApi.replyFeedback(token, current.summary.id, body) } }
                .onSuccess {
                    detail = it
                    reply = ""
                    notice = nativePhrase("回复已发送。", "Reply sent.")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("回复失败。", "Could not send the reply.") }
            saving = false
        }
    }

    if (token.isNullOrBlank()) {
        NativeAdminFeedbackGate(onBack)
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
                        Text(nativePhrase("用户反馈", "User feedback"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("处理用户提交的问题和使用反馈", "Process issues and usage feedback submitted by users"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) item { NativeAdminFeedbackMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        item {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it.take(120) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                label = { Text(nativePhrase("搜索标题或内容", "Search title or content"), fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors = nativeAdminFeedbackFieldColors(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                nativeFeedbackStatuses.forEach { value ->
                    FilterChip(selected = status == value, onClick = { status = value; page = 1 }, label = { Text(nativeFeedbackStatus(value), fontSize = 10.sp) })
                }
            }
        }
        when {
            loading -> item { NativeAdminFeedbackMessage(nativePhrase("正在读取用户反馈…", "Loading user feedback..."), false, null) }
            data?.items.isNullOrEmpty() -> item { NativeAdminFeedbackMessage(nativePhrase("暂无反馈记录。", "No feedback records."), false, null) }
            else -> {
                item { Text(nativePhrase("共 ${data?.total ?: 0} 条", "${data?.total ?: 0} items"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                items(data?.items.orEmpty(), key = { it.id }) { item -> NativeAdminFeedbackRow(item, detailLoading) { openDetail(item.id) } }
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = !loading && page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                        Text(nativePhrase("第 $page / ${data?.totalPages ?: 1} 页", "Page $page / ${data?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 10.sp)
                        TextButton(onClick = { page++ }, enabled = !loading && page < (data?.totalPages ?: 1)) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    detail?.let { current ->
        Dialog(onDismissRequest = { if (!saving) detail = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth().fillMaxHeight(.90f), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(current.summary.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${nativeFeedbackCategory(current.summary.category)} · @${current.summary.user.username.ifBlank { "unknown" }}", color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        IconButton(onClick = { if (!saving) detail = null }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(16.dp)) }
                    }
                    Text(current.content, fontSize = 12.sp, lineHeight = 19.sp)
                    Text(nativePhrase("处理状态", "Status"), color = HlovetUi.muted, fontSize = 10.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        nativeFeedbackStatuses.drop(1).forEach { value -> FilterChip(selected = current.summary.status == value, onClick = { updateStatus(value) }, enabled = !saving, label = { Text(nativeFeedbackStatus(value), fontSize = 10.sp) }) }
                    }
                    Text(nativePhrase("回复记录 · ${current.replies.size}", "Replies · ${current.replies.size}"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (current.replies.isEmpty()) Text(nativePhrase("暂无回复。", "No replies."), color = HlovetUi.muted, fontSize = 11.sp)
                    current.replies.forEach { item ->
                        Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.author.nickname.ifBlank { item.author.username }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(item.createdAt, color = HlovetUi.muted, fontSize = 9.sp)
                                Text(item.content, fontSize = 11.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                    OutlinedTextField(value = reply, onValueChange = { reply = it.take(3000) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text(nativePhrase("回复用户", "Reply to user"), fontSize = 11.sp) }, trailingIcon = { IconButton(onClick = ::sendReply, enabled = !saving && reply.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = nativePhrase("发送回复", "Send reply"), tint = HlovetUi.accent) } }, colors = nativeAdminFeedbackFieldColors())
                }
            }
        }
    }
}

@Composable
private fun NativeAdminFeedbackGate(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(nativePhrase("用户反馈", "User feedback"), fontSize = 19.sp, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        NativeAdminFeedbackMessage(nativePhrase("登录后查看用户反馈管理。", "Sign in to manage user feedback."), false, null)
    }
}

@Composable
private fun NativeAdminFeedbackRow(item: NativeFeedbackSummary, busy: Boolean, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(enabled = !busy, onClick = onClick), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Inbox, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nativePhrase("${nativeFeedbackCategory(item.category)} · ${item.user.nickname.ifBlank { "站内用户" }} · ${item.replyCount} 条回复", "${nativeFeedbackCategory(item.category)} · ${item.user.nickname.ifBlank { "Site user" }} · ${item.replyCount} replies"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(nativeFeedbackStatus(item.status), color = nativeFeedbackStatusColor(item.status), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NativeAdminFeedbackMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
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
private fun nativeAdminFeedbackFieldColors() = HlovetUi.fieldColors()

private fun nativeFeedbackStatus(status: String): String = when (status) {
    "all" -> nativePhrase("全部", "All")
    "pending" -> nativePhrase("待处理", "Pending")
    "in_progress" -> nativePhrase("处理中", "In progress")
    "resolved" -> nativePhrase("已解决", "Resolved")
    "closed" -> nativePhrase("已关闭", "Closed")
    else -> status
}

private fun nativeFeedbackStatusColor(status: String): Color = when (status) {
    "resolved" -> HlovetUi.accent
    "closed" -> HlovetUi.muted
    else -> HlovetUi.secondaryAccent
}

private fun nativeFeedbackCategory(category: String): String = when (category) {
    "bug" -> nativePhrase("功能异常", "Bug")
    "account" -> nativePhrase("账号问题", "Account")
    "content" -> nativePhrase("内容问题", "Content")
    "payment" -> nativePhrase("积分与资源", "Points and resources")
    else -> nativePhrase("其他", "Other")
}
