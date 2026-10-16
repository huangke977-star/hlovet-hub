@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeAdminDeletedUsersScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var data by remember(token) { mutableStateOf<NativeDeletedUserPage?>(null) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var page by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var detail by remember(token) { mutableStateOf<NativeDeletedUserContent?>(null) }
    var detailLoading by remember(token) { mutableStateOf(false) }

    LaunchedEffect(searchDraft) {
        delay(300)
        val next = searchDraft.trim()
        if (next != search) {
            search = next
            page = 1
        }
    }
    LaunchedEffect(token, page, search, reloadKey) {
        if (token.isNullOrBlank()) {
            data = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { NativeAdminDeletedUsersApi.list(token, page, 20, search) }
            data = result
            if (result.page != page) page = result.page
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("已注销账号读取失败。", "Could not load deleted accounts.")
        } finally {
            loading = false
        }
    }

    fun openDetail(id: Int) {
        val accessToken = token ?: return
        if (detailLoading) return
        detailLoading = true
        scope.launch {
            try {
                detail = withContext(Dispatchers.IO) { NativeAdminDeletedUsersApi.getContent(accessToken, id) }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("账号内容归属读取失败。", "Could not load account content ownership.")
            } finally {
                detailLoading = false
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
                        Text(nativePhrase("已注销账号", "Deleted accounts"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("查看原始身份和内容归属，公开页面仍保持匿名", "Review original identity and content ownership; public pages remain anonymous"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading && !detailLoading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it.take(120) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (searchDraft.isNotBlank()) ({ IconButton(onClick = { searchDraft = "" }) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), modifier = Modifier.size(17.dp)) } }) else null,
                placeholder = { Text(nativePhrase("搜索原用户名、昵称或邮箱", "Search original username, nickname, or email"), fontSize = 11.sp) },
                colors = nativeAdminDeletedUsersFieldColors(),
            )
        }
        if (!error.isNullOrBlank()) item { NativeAdminDeletedUsersMessage(error!!, true) { error = null } }
        if (token.isNullOrBlank()) {
            item { NativeAdminDeletedUsersMessage(nativePhrase("登录后查看已注销账号。", "Sign in to view deleted accounts."), false, null) }
        } else if (session?.isAdministrator != true && session?.isSuperAdmin != true) {
            item { NativeAdminDeletedUsersMessage(nativePhrase("当前账号没有查看已注销账号的权限。", "This account cannot view deleted accounts."), true, null) }
        } else if (loading && data == null) {
            item { NativeAdminDeletedUsersMessage(nativePhrase("正在读取注销账号…", "Loading deleted accounts…"), false, null) }
        } else if (data?.items.isNullOrEmpty()) {
            item { NativeAdminDeletedUsersMessage(if (search.isBlank()) nativePhrase("暂无已注销账号。", "No deleted accounts.") else nativePhrase("没有找到匹配的已注销账号。", "No matching deleted accounts."), false, null) }
        } else {
            item { Text(nativePhrase("共 ${data?.total ?: 0} 个账号", "${data?.total ?: 0} accounts"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(data!!.items, key = { it.id }) { item -> NativeDeletedUserRow(item, detailLoading, ::openDetail) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = !loading && page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                    Text(nativePhrase("第 ${data?.page ?: page} / ${data?.totalPages ?: 1} 页", "Page ${data?.page ?: page} / ${data?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 10.sp)
                    TextButton(onClick = { page++ }, enabled = !loading && page < (data?.totalPages ?: 1)) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                }
            }
        }
    }

    detail?.let { current ->
        Dialog(onDismissRequest = { detail = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 16.dp), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.originalNickname.ifBlank { current.originalUsername }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("@${current.originalUsername} · ${current.originalEmail}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { detail = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
                    }
                    NativeDeletedContentSection(nativePhrase("文章", "Articles"), current.articles.size)
                    if (current.articles.isEmpty()) Text(nativePhrase("暂无文章。", "No articles."), color = HlovetUi.muted, fontSize = 11.sp)
                    current.articles.forEach { article ->
                        NativeDeletedContentRow(article.title, "${article.status} · ${article.createdAt}")
                    }
                    NativeDeletedContentSection(nativePhrase("评论", "Comments"), current.comments.size)
                    if (current.comments.isEmpty()) Text(nativePhrase("暂无评论。", "No comments."), color = HlovetUi.muted, fontSize = 11.sp)
                    current.comments.forEach { comment ->
                        NativeDeletedContentRow(comment.body, "${comment.articleTitle} · ${comment.status} · ${comment.createdAt}")
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeDeletedUserRow(item: NativeDeletedUserSummary, busy: Boolean, onOpen: (Int) -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow, modifier = Modifier.size(38.dp)) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(item.originalNickname.ifBlank { item.originalUsername }.takeLast(2), color = HlovetUi.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.originalNickname.ifBlank { item.originalUsername }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${item.originalUsername}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nativePhrase("${item.articleCount} 篇文章 · ${item.commentCount} 条评论", "${item.articleCount} articles · ${item.commentCount} comments"), color = HlovetUi.muted, fontSize = 10.sp)
            }
            IconButton(onClick = { onOpen(item.id) }, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Info, contentDescription = nativePhrase("查看内容归属", "View content ownership"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun NativeDeletedContentSection(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(nativePhrase("$count 条", "$count items"), color = HlovetUi.muted, fontSize = 10.sp) }
}

@Composable
private fun NativeDeletedContentRow(title: String, meta: String) {
    Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title.ifBlank { nativePhrase("（无正文）", "(No body)") }, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis); Text(meta, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
}

@Composable
private fun NativeAdminDeletedUsersMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Info, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            onDismiss?.let { IconButton(onClick = it, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Dismiss"), modifier = Modifier.size(15.dp)) } }
        }
    }
}

@Composable
private fun nativeAdminDeletedUsersFieldColors() = HlovetUi.fieldColors()
