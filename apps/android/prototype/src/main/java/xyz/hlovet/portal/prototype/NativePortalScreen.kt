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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class NativePortalMode { Navigation, Tools }

@Composable
internal fun NativePortalScreen(
    mode: NativePortalMode,
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenEntry: (NativePortalEntry) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var content by remember(mode, token) { mutableStateOf<NativePortalContent?>(null) }
    var preferences by remember(token) { mutableStateOf<NativePortalPreferences?>(null) }
    var query by remember { mutableStateOf("") }
    var loading by remember(mode, token) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(mode, token) { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(mode, token, reloadKey) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val kinds = if (mode == NativePortalMode.Navigation) {
                    listOf("navigation")
                } else {
                    listOf("navigation", "tool", "server")
                }
                val nextContent = NativePortalApi.listContent(token, kinds)
                val nextPreferences = if (mode == NativePortalMode.Tools && !token.isNullOrBlank()) {
                    NativePortalApi.getPreferences(token)
                } else {
                    null
                }
                nextContent to nextPreferences
            }
        }.onSuccess { (nextContent, nextPreferences) ->
            content = nextContent
            preferences = nextPreferences
        }.onFailure {
            error = it.message ?: nativePhrase("门户内容加载失败，请稍后重试。", "Could not load portal content. Please try again.")
        }
        loading = false
    }

    val categories = content?.categories.orEmpty()
    val records = categories.flatMap { category -> category.entries }
    val normalizedQuery = query.trim().lowercase()
    val filteredRecords = if (normalizedQuery.isBlank()) records else records.filter { entry ->
        listOf(entry.title, entry.description, entry.categoryName, entry.url.orEmpty())
            .joinToString(" ")
            .lowercase()
            .contains(normalizedQuery)
    }
    val savedIds = preferences?.toolEntryIds.orEmpty()
    val homeIds = preferences?.homeEntryIds.orEmpty()
    val savedRecords = savedIds.mapNotNull { id -> records.firstOrNull { it.id == id } }

    fun savePreferences(nextHomeIds: List<Int>, nextToolIds: List<Int>) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            onLogin()
            return
        }
        if (saving) return
        val previous = preferences
        preferences = NativePortalPreferences(nextHomeIds, nextToolIds)
        saving = true
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativePortalApi.updatePreferences(currentToken, nextHomeIds, nextToolIds)
                }
            }.onSuccess { updated ->
                preferences = updated
                notice = nativePhrase("门户偏好已保存。", "Portal preferences saved.")
            }.onFailure {
                preferences = previous
                error = it.message ?: nativePhrase("门户偏好保存失败。", "Could not save portal preferences.")
            }
            saving = false
        }
    }

    fun toggleTool(entry: NativePortalEntry) {
        val next = if (entry.id in savedIds) savedIds.filterNot { it == entry.id } else savedIds + entry.id
        if (next.size > 30) {
            error = nativePhrase("常用工具最多保存 30 项。", "You can save up to 30 favorite tools.")
            return
        }
        savePreferences(homeIds, next)
    }

    fun toggleHome(entry: NativePortalEntry) {
        val next = if (entry.id in homeIds) homeIds.filterNot { it == entry.id } else homeIds + entry.id
        if (next.size > 12) {
            error = nativePhrase("首页快捷方式最多保存 12 项。", "You can add up to 12 home shortcuts.")
            return
        }
        savePreferences(next, savedIds)
    }

    fun moveSaved(entryId: Int, delta: Int) {
        val oldIndex = savedIds.indexOf(entryId)
        val newIndex = oldIndex + delta
        if (oldIndex < 0 || newIndex !in savedIds.indices) return
        val next = savedIds.toMutableList().apply {
            add(newIndex, removeAt(oldIndex))
        }
        savePreferences(homeIds, next)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(if (mode == NativePortalMode.Navigation) nativePhrase("站内导航", "Site navigation") else nativePhrase("工具中心", "Tool center"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = !loading && !saving) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = if (loading) HlovetUi.muted else HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    Text(
                        if (mode == NativePortalMode.Navigation) nativePhrase("按分类查找站内入口", "Find site entries by category") else nativePhrase("搜索、收藏并整理你常用的站内工具", "Search, save, and organize your favorite tools"),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        if (mode == NativePortalMode.Navigation) nativePhrase("内容由站点配置控制，权限不匹配的入口不会显示。", "Entries are controlled by site settings and hidden when you lack access.") else nativePhrase("登录后可保存常用工具，并把入口添加到首页快捷方式。", "Sign in to save tools and add them to your home shortcuts."),
                        color = HlovetUi.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (mode == NativePortalMode.Tools) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(19.dp)) },
                    placeholder = { Text(nativePhrase("搜索工具", "Search tools"), fontSize = 13.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    colors = HlovetUi.fieldColors(),
                )
            }
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(nativePhrase("我的常用工具", "My favorite tools"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("${savedRecords.size}/30", color = HlovetUi.muted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (savedRecords.isEmpty()) {
                            Text(nativePhrase("还没有收藏工具。", "No favorite tools yet."), color = HlovetUi.muted, fontSize = 12.sp)
                        } else {
                            savedRecords.forEachIndexed { index, entry ->
                                PortalSavedToolRow(
                                    entry = entry,
                                    first = index == 0,
                                    last = index == savedRecords.lastIndex,
                                    onOpen = { onOpenEntry(entry) },
                                    onMoveUp = { moveSaved(entry.id, -1) },
                                    onMoveDown = { moveSaved(entry.id, 1) },
                                    onRemove = { toggleTool(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (loading) {
            item { Text(nativePhrase("正在读取门户内容…", "Loading portal content..."), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        } else if (error.isNullOrBlank() && categories.isEmpty()) {
            item { PortalEmptyState(if (mode == NativePortalMode.Navigation) nativePhrase("暂时没有可见导航入口。", "No visible navigation entries.") else nativePhrase("暂时没有可见工具。", "No visible tools.")) }
        } else {
            if (mode == NativePortalMode.Tools) {
                filteredRecords.groupBy { it.categoryId }.forEach { (_, entries) ->
                    val category = categories.firstOrNull { it.id == entries.firstOrNull()?.categoryId }
                    item(key = "category-${category?.id ?: entries.first().categoryId}") {
                        PortalCategoryCard(
                            category = category,
                            entries = entries,
                            savedIds = savedIds,
                            homeIds = homeIds,
                            onOpen = onOpenEntry,
                            onToggleSaved = ::toggleTool,
                            onToggleHome = ::toggleHome,
                        )
                    }
                }
                if (filteredRecords.isEmpty() && error.isNullOrBlank()) {
                    item { PortalEmptyState(nativePhrase("没有匹配的工具。", "No matching tools.")) }
                }
            } else {
                categories.forEach { category ->
                    item(key = "navigation-${category.id}") {
                        PortalCategoryCard(
                            category = category,
                            entries = category.entries,
                            savedIds = emptyList(),
                            homeIds = emptyList(),
                            onOpen = onOpenEntry,
                            onToggleSaved = null,
                            onToggleHome = null,
                        )
                    }
                }
            }
        }
        error?.let { message ->
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    TextButton(onClick = { reloadKey += 1 }, enabled = !loading) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                }
            }
        }
        notice?.let { message -> item { Text(message, color = HlovetUi.accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
    }
}

@Composable
private fun PortalCategoryCard(
    category: NativePortalCategory?,
    entries: List<NativePortalEntry>,
    savedIds: List<Int>,
    homeIds: List<Int>,
    onOpen: (NativePortalEntry) -> Unit,
    onToggleSaved: ((NativePortalEntry) -> Unit)?,
    onToggleHome: ((NativePortalEntry) -> Unit)?,
) {
    val title = category?.name ?: entries.firstOrNull()?.categoryName ?: nativePhrase("入口", "Entry")
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    category?.description?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(nativePhrase("${entries.size} 个入口", "${entries.size} entries"), color = HlovetUi.muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(7.dp))
            entries.forEachIndexed { index, entry ->
                PortalEntryRow(
                    entry = entry,
                    saved = entry.id in savedIds,
                    home = entry.id in homeIds,
                    onOpen = { onOpen(entry) },
                    onToggleSaved = onToggleSaved?.let { { it(entry) } },
                    onToggleHome = onToggleHome?.let { { it(entry) } },
                )
                if (index < entries.lastIndex) androidx.compose.material3.HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(start = 52.dp))
            }
        }
    }
}

@Composable
private fun PortalEntryRow(
    entry: NativePortalEntry,
    saved: Boolean,
    home: Boolean,
    onOpen: () -> Unit,
    onToggleSaved: (() -> Unit)?,
    onToggleHome: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NativeCoverImage(
            path = entry.iconPath,
            modifier = Modifier.size(40.dp),
            fallbackText = entry.title,
            tint = HlovetUi.accent,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.description.ifBlank { portalEntrySubtitle(entry.url) },
                color = HlovetUi.muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onToggleHome != null) {
            IconButton(onClick = onToggleHome, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Home, contentDescription = if (home) nativePhrase("移除首页快捷方式", "Remove home shortcut") else nativePhrase("添加到首页", "Add to home"), tint = if (home) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(17.dp))
            }
        }
        if (onToggleSaved != null) {
            IconButton(onClick = onToggleSaved, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Star, contentDescription = if (saved) nativePhrase("移出常用工具", "Remove from favorites") else nativePhrase("加入常用工具", "Add to favorites"), tint = if (saved) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(17.dp),)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PortalSavedToolRow(
    entry: NativePortalEntry,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeCoverImage(path = entry.iconPath, modifier = Modifier.size(32.dp), fallbackText = entry.title, tint = HlovetUi.accent)
        Spacer(Modifier.width(8.dp))
        Text(entry.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable(onClick = onOpen))
        IconButton(onClick = onMoveUp, enabled = !first, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = nativePhrase("上移", "Move up"), tint = if (first) HlovetUi.divider else HlovetUi.muted, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onMoveDown, enabled = !last, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = nativePhrase("下移", "Move down"), tint = if (last) HlovetUi.divider else HlovetUi.muted, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Star, contentDescription = nativePhrase("移出常用工具", "Remove from favorites"), tint = HlovetUi.accent, modifier = Modifier.size(17.dp)) }
    }
}

private fun portalEntrySubtitle(url: String?): String = when {
    url.isNullOrBlank() -> nativePhrase("暂未配置链接", "No link configured")
    url.startsWith("/") -> nativePhrase("站内页面", "Internal page")
    else -> runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.ifBlank { nativePhrase("链接入口", "Link entry") } ?: nativePhrase("链接入口", "Link entry")
}

@Composable
private fun PortalEmptyState(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}
