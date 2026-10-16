@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fireplace
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeSearchScreen(
    session: NativeSession?,
    initialQuery: String = "",
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenTopic: (String) -> Unit,
    onOpenCollection: (Int) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenAnnouncement: (Int) -> Unit,
    onOpenGroup: (NativeSearchChatGroup) -> Unit,
    onOpenEntry: (NativeSearchEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val token = session?.accessToken
    var query by remember(initialQuery) { mutableStateOf(initialQuery.take(80)) }
    var scopeFilter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("relevance") }
    var category by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var result by remember { mutableStateOf<NativeSearchResult?>(null) }
    var history by remember(token) { mutableStateOf<List<NativeSearchHistory>>(emptyList()) }
    var hot by remember { mutableStateOf<List<NativeSearchHistory>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearHistoryConfirm by remember { mutableStateOf(false) }
    var lastRecorded by remember { mutableStateOf("") }

    LaunchedEffect(token) {
        page = 1
        hot = withContext(Dispatchers.IO) { runCatching { NativeSearchApi.hot(8) }.getOrDefault(emptyList()) }
        if (!token.isNullOrBlank()) {
            history = withContext(Dispatchers.IO) { runCatching { NativeSearchApi.history(token) }.getOrDefault(emptyList()) }
        } else {
            history = emptyList()
        }
    }

    LaunchedEffect(query, scopeFilter, sort, category, page, token) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            result = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeSearchApi.search(token, keyword, scopeFilter, sort, category.takeIf { it.isNotBlank() }, page)
            }
        }.onSuccess {
            result = it
            if (!token.isNullOrBlank() && keyword != lastRecorded) {
                lastRecorded = keyword
                scope.launch(Dispatchers.IO) { runCatching { NativeSearchApi.record(token, keyword) } }
            }
        }.onFailure { error = it.message ?: nativePhrase("搜索失败，请稍后重试。", "Search failed. Please try again later.") }
        loading = false
    }

    fun chooseKeyword(keyword: String) {
        query = keyword
        scopeFilter = "all"
        sort = "relevance"
        category = ""
        page = 1
    }

    fun chooseScope(value: String) {
        scopeFilter = value
        if (value != "articles" && value != "all") category = ""
        page = 1
    }

    val total = result?.let {
        it.articles.total + it.users.total + it.navigation.total + it.tools.total +
            it.topics.total + it.collections.total + it.groups.total + it.announcements.total
    } ?: 0
    val maxPages = result?.let {
        maxOf(
            it.articles.totalPages,
            it.users.totalPages,
            it.navigation.totalPages,
            it.tools.totalPages,
            it.topics.totalPages,
            it.collections.totalPages,
            it.groups.totalPages,
            it.announcements.totalPages,
        )
    } ?: 1
    val scopeOptions = listOf(
        "all" to nativePhrase("全部", "All"),
        "articles" to nativePhrase("文章", "Articles"),
        "users" to nativePhrase("用户", "Users"),
        "navigation" to nativePhrase("导航", "Navigation"),
        "tools" to nativePhrase("工具", "Tools"),
        "topics" to nativePhrase("专题", "Topics"),
        "collections" to nativePhrase("合集", "Collections"),
        "groups" to nativePhrase("群聊", "Groups"),
        "announcements" to nativePhrase("公告", "Announcements"),
    )

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("全站搜索", "Search"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80); page = 1 },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = HlovetUi.accent) },
                trailingIcon = if (query.isNotBlank()) ({
                    IconButton(onClick = { query = ""; page = 1 }) { Icon(Icons.Filled.Clear, nativePhrase("清空", "Clear")) }
                }) else null,
                placeholder = { Text(nativePhrase("搜索文章、用户、专题、合集、群聊和公告", "Search articles, users, topics, collections, groups, and announcements")) },
                colors = searchFieldColors(),
            )
        }
        if (query.isBlank()) {
            if (history.isNotEmpty()) {
                item { SearchSubheading(nativePhrase("最近搜索", "Recent searches"), Icons.Filled.Search, action = { clearHistoryConfirm = true }) }
                items(history.take(8), key = { "history-${it.id}" }) { item ->
                    SearchKeywordRow(
                        keyword = item.keyword,
                        detail = nativePhrase("搜索 ${item.searchCount.coerceAtLeast(1)} 次", "${item.searchCount.coerceAtLeast(1)} searches"),
                        icon = Icons.Filled.Search,
                        onClick = { chooseKeyword(item.keyword) },
                        onDelete = {
                            token?.let { currentToken ->
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { NativeSearchApi.deleteHistory(currentToken, item.id) } }
                                        .onSuccess { history = history.filterNot { entry -> entry.id == item.id } }
                                        .onFailure { error = it.message ?: nativePhrase("搜索记录删除失败。", "Failed to delete search history.") }
                                }
                            }
                        },
                    )
                }
            }
            if (hot.isNotEmpty()) {
                item { SearchSubheading(nativePhrase("热门搜索", "Popular searches"), Icons.Filled.Fireplace) }
                items(hot, key = { "hot-${it.keyword}" }) { item ->
                    SearchKeywordRow(
                        keyword = item.keyword,
                        detail = nativePhrase("${item.searchCount} 次搜索", "${item.searchCount} searches"),
                        icon = Icons.Filled.Fireplace,
                        onClick = { chooseKeyword(item.keyword) },
                    )
                }
            }
            if (history.isEmpty() && hot.isEmpty()) item { searchEmpty(nativePhrase("输入关键词开始搜索。", "Enter a keyword to start searching.")) }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    scopeOptions.forEach { (value, label) ->
                        FilterChip(selected = scopeFilter == value, onClick = { chooseScope(value) }, label = { Text(label, fontSize = 10.sp) }, border = null)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nativePhrase("排序", "Sort"), color = HlovetUi.muted, fontSize = 11.sp)
                    listOf("relevance" to nativePhrase("综合", "Relevance"), "latest" to nativePhrase("最新", "Latest"), "popular" to nativePhrase("热门", "Popular")).forEach { (value, label) ->
                        FilterChip(selected = sort == value, onClick = { sort = value; page = 1 }, label = { Text(label, fontSize = 10.sp) }, border = null)
                    }
                }
            }
            val categoryFilters = when (scopeFilter) {
                "navigation" -> result?.filters?.navigationCategories.orEmpty()
                "tools" -> result?.filters?.toolCategories.orEmpty()
                else -> result?.filters?.articleCategories.orEmpty()
            }
            if (scopeFilter in listOf("all", "articles", "navigation", "tools") && categoryFilters.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(nativePhrase("分类", "Category"), color = HlovetUi.muted, fontSize = 11.sp)
                        FilterChip(selected = category.isBlank(), onClick = { category = ""; page = 1 }, label = { Text(nativePhrase("全部", "All"), fontSize = 10.sp) }, border = null)
                        categoryFilters.forEach { (label, value) ->
                            FilterChip(selected = category == value, onClick = { category = value; page = 1 }, label = { Text(label, fontSize = 10.sp) }, border = null)
                        }
                    }
                }
            }
            if (loading) item { searchEmpty(nativePhrase("正在搜索…", "Searching...")) }
            error?.let { message -> item { searchEmpty(message, isError = true) } }
            if (!loading && error == null && result != null && total == 0) item { searchEmpty(nativePhrase("没有找到匹配内容。", "No matching content found.")) }
            result?.let { data ->
                if ((scopeFilter == "all" || scopeFilter == "articles") && data.articles.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("文章", "Articles"), data.articles.total, Icons.Filled.Article) }
                    items(data.articles.items, key = { "article-${it.id}" }) { article ->
                        SearchRow(article.title, "${article.authorName} · ${article.category.ifBlank { nativePhrase("随笔", "Notes") }} · ${article.viewCount} ${nativePhrase("阅读", "views")} · ${article.commentCount} ${nativePhrase("评论", "comments")}", Icons.Filled.Article) { onOpenArticle(article.slug) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "users") && data.users.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("用户", "Users"), data.users.total, Icons.Filled.Person) }
                    items(data.users.items, key = { "user-${it.id}" }) { user ->
                        SearchRow(user.nickname, "@${user.username}${user.roleName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}${user.bio.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}", Icons.Filled.Person) { onOpenUser(user.username) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "navigation") && data.navigation.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("导航", "Navigation"), data.navigation.total, Icons.Filled.Explore) }
                    items(data.navigation.items, key = { "navigation-${it.id}" }) { entry ->
                        SearchRow(entry.title, "${entry.categoryName}${entry.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", Icons.Filled.Explore) { onOpenEntry(entry) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "tools") && data.tools.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("工具", "Tools"), data.tools.total, Icons.Filled.Build) }
                    items(data.tools.items, key = { "tool-${it.id}" }) { entry ->
                        SearchRow(entry.title, "${entry.categoryName}${entry.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", Icons.Filled.Build) { onOpenEntry(entry) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "topics") && data.topics.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("专题", "Topics"), data.topics.total, Icons.Filled.Topic) }
                    items(data.topics.items, key = { "topic-${it.id}" }) { topic ->
                        SearchRow(topic.title, "${topic.articleCount} ${nativePhrase("篇文章", "articles")} · ${topic.subscriberCount} ${nativePhrase("人订阅", "subscribers")}\n${topic.description}", Icons.Filled.Topic) { onOpenTopic(topic.slug) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "collections") && data.collections.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("合集", "Collections"), data.collections.total, Icons.Filled.CollectionsBookmark) }
                    items(data.collections.items, key = { "collection-${it.id}" }) { collection ->
                        SearchRow(collection.name, "${collection.articleCount} ${nativePhrase("篇文章", "articles")} · ${collection.subscriberCount} ${nativePhrase("人订阅", "subscribers")} · ${collection.ownerName}", Icons.Filled.CollectionsBookmark) { onOpenCollection(collection.id) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "groups") && data.groups.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("群聊", "Groups"), data.groups.total, Icons.Filled.Groups) }
                    items(data.groups.items, key = { "group-${it.id}" }) { group ->
                        SearchRow(group.name, "${if (group.isMember) nativePhrase("已加入", "Joined") else nativePhrase("可申请加入", "Joinable")} · ${group.memberCount} ${nativePhrase("人", "members")}${group.announcement.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}", Icons.Filled.Groups) { onOpenGroup(group) }
                    }
                }
                if ((scopeFilter == "all" || scopeFilter == "announcements") && data.announcements.items.isNotEmpty()) {
                    item { SearchGroupHeader(nativePhrase("站点公告", "Site announcements"), data.announcements.total, Icons.Filled.NotificationsNone) }
                    items(data.announcements.items, key = { "announcement-${it.id}" }) { announcement ->
                        SearchRow(announcement.title, announcement.summary.ifBlank { if (announcement.pinned) nativePhrase("置顶公告", "Pinned announcement") else nativePhrase("打开查看详情", "Open details") }, Icons.Filled.NotificationsNone) { onOpenAnnouncement(announcement.id) }
                    }
                }
                if (maxPages > 1) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(nativePhrase("第 $page / $maxPages 页", "Page $page / $maxPages"), color = HlovetUi.muted, fontSize = 11.sp)
                            Row {
                                TextButton(onClick = { page -= 1 }, enabled = !loading && page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                                TextButton(onClick = { page += 1 }, enabled = !loading && page < maxPages) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (clearHistoryConfirm) HlovetAlertDialog(
        onDismissRequest = { clearHistoryConfirm = false },
        title = { Text(nativePhrase("清空搜索记录", "Clear search history")) },
        text = { Text(nativePhrase("确定清空当前账号的搜索记录吗？", "Clear search history for this account?")) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    token?.let { withContext(Dispatchers.IO) { NativeSearchApi.clearHistory(it) } }
                    history = emptyList()
                    clearHistoryConfirm = false
                }
            }) { Text(nativePhrase("清空", "Clear"), color = HlovetUi.secondaryAccent) }
        },
        dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text(nativePhrase("取消", "Cancel")) } },
    )
}

@Composable
private fun SearchSubheading(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = action) { Text(nativePhrase("清空", "Clear"), fontSize = 11.sp) }
    }
}

@Composable
private fun SearchGroupHeader(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.width(5.dp))
        Text("$count", color = HlovetUi.muted, fontSize = 10.sp)
    }
}

@Composable
private fun SearchKeywordRow(
    keyword: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick), HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(keyword, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(detail, color = HlovetUi.muted, fontSize = 10.sp) }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Clear, contentDescription = nativePhrase("删除搜索记录", "Delete search history"), tint = HlovetUi.muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (() -> Unit)? = null) {
    val base = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
    val rowModifier = onClick?.let { base.clickable(onClick = it) } ?: base
    GlassCard(rowModifier, HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) Icon(Icons.Filled.Link, null, tint = HlovetUi.muted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun searchEmpty(message: String, isError: Boolean = false) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
        Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun searchFieldColors() = HlovetUi.fieldColors()
