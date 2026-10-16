@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeAdminCacheScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    key(session?.accessToken, session?.isSuperAdmin) { NativeAdminCacheContent(session, onBack) }
}

@Composable
private fun NativeAdminCacheContent(session: NativeSession?, onBack: () -> Unit) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ttlKeyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() })
    var overview by remember(token) { mutableStateOf<NativeCacheOverview?>(null) }
    var keyPage by remember(token) { mutableStateOf<NativeCacheKeyPage?>(null) }
    var cursor by remember(token) { mutableStateOf("0") }
    var cursorHistory by remember(token) { mutableStateOf<List<String>>(emptyList()) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var type by remember(token) { mutableStateOf("") }
    var category by remember(token) { mutableStateOf("") }
    var batchSize by remember(token) { mutableIntStateOf(10) }
    var selected by remember(token) { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember(token) { mutableStateOf<NativeCacheKeyDetail?>(null) }
    var detailLoading by remember(token) { mutableStateOf(false) }
    var ttlDraft by remember(token) { mutableStateOf("3600") }
    var ttlDialogKeys by remember(token) { mutableStateOf<List<String>?>(null) }
    var deleteDialogKeys by remember(token) { mutableStateOf<List<String>?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var overviewError by remember { mutableStateOf<String?>(null) }
    var keysError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var deleteProgress by remember { mutableStateOf(NativeCacheDeleteResult(0, 0, 0)) }
    var ttlCompleted by remember { mutableIntStateOf(0) }
    var overviewLoading by remember { mutableStateOf(false) }
    var overviewReload by remember { mutableIntStateOf(0) }
    var loadedQuery by remember { mutableStateOf<List<String>?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }

    LaunchedEffect(searchDraft) {
        delay(280)
        val next = searchDraft.trim()
        if (next != search) {
            search = next
            cursor = "0"
            cursorHistory = emptyList()
        }
    }

    LaunchedEffect(token, canAccess, overviewReload) {
        if (token.isNullOrBlank() || !canAccess) {
            overview = null
            return@LaunchedEffect
        }
        overviewLoading = true
        overviewError = null
        try {
            overview = withContext(Dispatchers.IO) { NativeAdminCacheApi.overview(token) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            overviewError = exception.message ?: nativePhrase("Redis 概览读取失败。", "Could not load Redis overview.")
        } finally { overviewLoading = false }
    }

    LaunchedEffect(token, canAccess, cursor, batchSize, search, type, category, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            keyPage = null
            loading = false
            return@LaunchedEffect
        }
        val query = listOf(cursor, batchSize.toString(), search, type, category)
        if (loadedQuery != query) { keyPage = null; selected = emptySet() }
        loading = true
        keysError = null
        try {
            keyPage = withContext(Dispatchers.IO) { NativeAdminCacheApi.listKeys(token, cursor, batchSize, search, type, category) }
            loadedQuery = query
            selected = selected.intersect(keyPage!!.keys.map { it.key }.toSet())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            keysError = exception.message ?: nativePhrase("缓存键读取失败。", "Could not load cache keys.")
        } finally { loading = false }
    }

    fun openDetail(key: String) {
        val accessToken = token ?: return
        if (!canAccess || detailLoading || busy || loading) return
        actionError = null
        ttlCompleted = 0
        detailLoading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativeAdminCacheApi.inspect(accessToken, key) }
                detail = result
                ttlDraft = if (result.summary.ttlSeconds > 0) result.summary.ttlSeconds.toString() else "3600"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("缓存详情读取失败。", "Could not load cache details.")
            } finally {
                detailLoading = false
            }
        }
    }

    fun runDelete(keys: List<String>) {
        val accessToken = token ?: return
        if (!canAccess || keys.isEmpty() || busy) return
        busy = true
        actionError = null
        notice = null
        scope.launch {
            try {
                val result = deleteProgress + withContext(Dispatchers.IO) { NativeAdminCacheApi.delete(accessToken, keys) }
                keyPage = keyPage?.let { it.copy(keys = it.keys.filterNot { item -> item.key in keys }) }
                deleteDialogKeys = null
                detail = null
                selected = emptySet()
                notice = nativePhrase("已删除 ${result.deletedKeys} 个缓存键；撤销会话 ${result.revokedSessions} 个；清除失败计数 ${result.clearedLoginFailures} 个。", "Deleted ${result.deletedKeys} cache key(s); revoked ${result.revokedSessions} session(s); cleared ${result.clearedLoginFailures} failure counter(s).")
                reloadKey++
                overviewReload++
                deleteProgress = NativeCacheDeleteResult(0, 0, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: NativeCacheBatchException) {
                val completed = exception.completedKeys.toSet()
                deleteProgress += exception.deletion ?: NativeCacheDeleteResult(0, 0, 0)
                keyPage = keyPage?.let { it.copy(keys = it.keys.filterNot { item -> item.key in completed }) }
                selected = selected - completed
                deleteDialogKeys = keys.filterNot { it in completed }
                if (detail?.summary?.key in completed) detail = null
                actionError = exception.message
                overviewReload++
            } catch (exception: Exception) {
                actionError = exception.message ?: nativePhrase("缓存清理失败。", "Could not clean the cache.")
            } finally {
                busy = false
            }
        }
    }

    fun updateTtl(keys: List<String>) {
        val accessToken = token ?: return
        val ttl = ttlDraft.toIntOrNull()
        if (!canAccess || busy || keys.isEmpty()) return
        if (ttl == null || ttl !in 60..31_536_000) {
            actionError = nativePhrase("TTL 必须是 60 到 31536000 之间的整数秒。", "TTL must be an integer between 60 and 31536000 seconds.")
            return
        }
        busy = true
        actionError = null
        notice = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    if (keys.size == 1) listOf(NativeAdminCacheApi.updateTtl(accessToken, keys.first(), ttl))
                    else NativeAdminCacheApi.updateTtls(accessToken, keys, ttl)
                }
                detail = detail?.let { current -> updated.firstOrNull { it.key == current.summary.key }?.let { current.copy(summary = it) } ?: current }
                keyPage = keyPage?.let { page -> page.copy(keys = page.keys.map { item -> updated.firstOrNull { it.key == item.key } ?: item }) }
                ttlDialogKeys = null
                val total = ttlCompleted + updated.size
                notice = nativePhrase("已更新 $total 个业务缓存的 TTL。", "Updated TTL for $total business cache key(s).")
                ttlCompleted = 0
                reloadKey++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: NativeCacheBatchException) {
                val updated = exception.updated.associateBy { it.key }
                ttlCompleted += updated.size
                keyPage = keyPage?.let { page -> page.copy(keys = page.keys.map { updated[it.key] ?: it }) }
                detail = detail?.let { it.copy(summary = updated[it.summary.key] ?: it.summary) }
                ttlDialogKeys = keys.filterNot { it in updated }
                actionError = exception.message
            } catch (exception: Exception) {
                actionError = exception.message ?: nativePhrase("缓存 TTL 更新失败。", "Could not update cache TTL.")
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("cache-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("缓存管理", "Cache management"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("Redis 概览、缓存键和脱敏维护", "Redis overview, cache keys, and redacted maintenance"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++; overviewReload++ }, enabled = canAccess && !loading && !overviewLoading && !busy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (canAccess && (!error.isNullOrBlank() || !notice.isNullOrBlank())) item(key = "feedback") { NativeCacheMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        if (token.isNullOrBlank()) {
            item(key = "permission") { NativeCacheMessage(nativePhrase("登录后查看缓存管理。", "Sign in to view cache management."), false, null) }
        } else if (!canAccess) {
            item(key = "permission") { NativeCacheMessage(nativePhrase("缓存数据仅超级管理员可查看和操作。", "Only super administrators can view and manage cache data."), true, null) }
        } else {
            overview?.let { item(key = "overview") { NativeCacheOverviewCard(it) } }
            if (overviewLoading && overview == null) item(key = "overview-loading") { NativeCacheMessage(nativePhrase("正在连接 Redis…", "Connecting to Redis..."), false, null) }
            overviewError?.let { item(key = "overview-error") { NativeCacheMessage(it, true, null); TextButton(onClick = { overviewReload++ }, enabled = !overviewLoading, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重试概览", "Retry overview")) } } }
            item(key = "toolbar") {
                Column(Modifier.testTag("cache-toolbar")) {
                NativeCacheToolbar(
                    searchDraft = searchDraft,
                    type = type,
                    category = category,
                    batchSize = batchSize,
                    onSearch = { searchDraft = it },
                    onType = { type = it; cursor = "0"; cursorHistory = emptyList() },
                    onCategory = { category = it; cursor = "0"; cursorHistory = emptyList() },
                    onBatchSize = { batchSize = it; cursor = "0"; cursorHistory = emptyList() },
                    enabled = !busy,
                )
                }
            }
            if (selected.isNotEmpty()) {
                item(key = "selection") {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(nativePhrase("已选 ${selected.size} 项", "${selected.size} selected"), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { actionError = null; ttlCompleted = 0; ttlDialogKeys = selected.filter { key -> keyPage?.keys?.firstOrNull { it.key == key }?.canUpdateTtl == true } }, enabled = !busy && !loading && selected.any { key -> keyPage?.keys?.firstOrNull { it.key == key }?.canUpdateTtl == true }) { Text(nativePhrase("修改 TTL", "Edit TTL"), fontSize = 10.sp) }
                            TextButton(onClick = { actionError = null; deleteProgress = NativeCacheDeleteResult(0, 0, 0); deleteDialogKeys = selected.toList() }, enabled = !busy && !loading) { Text(nativePhrase("删除/清理", "Delete / clean"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                        }
                    }
                }
            }
            item(key = "keys-header") {
                Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    val keys = keyPage?.keys.orEmpty().map { it.key }.toSet()
                    HlovetCheckbox(checked = keys.isNotEmpty() && selected.containsAll(keys), onCheckedChange = { checked -> selected = if (checked) keys else emptySet() }, enabled = keys.isNotEmpty() && !busy && !loading, modifier = Modifier.testTag("cache-select-all").size(32.dp))
                    Text(nativePhrase("缓存键", "Cache keys"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(nativePhrase("${keyPage?.keys?.size ?: 0} 项", "${keyPage?.keys?.size ?: 0} items"), color = HlovetUi.muted, fontSize = 10.sp)
                }
            }
            if (loading) item(key = "keys-loading") { NativeCacheMessage(nativePhrase("正在读取缓存键…", "Loading cache keys..."), false, null) }
            if (detailLoading) item(key = "detail-loading") { NativeCacheMessage(nativePhrase("正在读取缓存详情…", "Loading cache details..."), false, null) }
            keysError?.let { item(key = "keys-error") { NativeCacheMessage(it, true, null); TextButton(onClick = { reloadKey++ }, enabled = !loading) { Text(nativePhrase("重试缓存键", "Retry cache keys")) } } }
            if (keyPage?.keys.isNullOrEmpty() && !loading && keysError == null) item(key = "empty") { NativeCacheMessage(nativePhrase("没有找到匹配的缓存键。", "No matching cache keys found."), false, null) }
            items(keyPage?.keys.orEmpty(), key = { "cache-key:${it.key}" }) { item ->
                NativeCacheKeyRow(item, selected.contains(item.key), busy || detailLoading || loading, { checked -> selected = if (checked) selected + item.key else selected - item.key }, ::openDetail)
            }
            item(key = "pagination") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { val previous = cursorHistory.lastOrNull() ?: return@TextButton; cursorHistory = cursorHistory.dropLast(1); cursor = previous }, enabled = !busy && !loading && cursorHistory.isNotEmpty()) { Icon(Icons.Filled.ChevronLeft, contentDescription = null, modifier = Modifier.size(16.dp)); Text(nativePhrase("上一批", "Previous"), fontSize = 10.sp) }
                    Text(nativePhrase(if (keyPage?.done == true) "已到扫描末尾" else "Redis 游标分批加载", if (keyPage?.done == true) "End of scan" else "Redis cursor batches"), color = HlovetUi.muted, fontSize = 10.sp)
                    TextButton(onClick = { val next = keyPage?.nextCursor ?: return@TextButton; cursorHistory = cursorHistory + cursor; cursor = next }, enabled = !busy && !loading && keyPage != null && keyPage?.done == false) { Text(nativePhrase("下一批", "Next"), fontSize = 10.sp); Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }

    if (canAccess) deleteDialogKeys?.let { keys ->
        HlovetAlertDialog(onDismissRequest = { if (!busy) deleteDialogKeys = null }, title = { Text(nativePhrase("确认清理缓存", "Confirm cache cleanup")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (keys.size == 1) nativePhrase("该操作可能撤销登录会话或清除失败计数，是否继续？", "This may revoke a sign-in session or clear a failure counter. Continue?") else nativePhrase("将处理 ${keys.size} 个缓存键，其中认证缓存可能让设备重新登录。是否继续？", "${keys.size} cache keys will be processed; authentication caches may require devices to sign in again. Continue?"), fontSize = 13.sp)
                actionError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
            }
        }, confirmButton = { TextButton(onClick = { runDelete(keys) }, enabled = !busy) { Text(if (busy) nativePhrase("清理中…", "Cleaning...") else nativePhrase("确认清理", "Confirm cleanup")) } }, dismissButton = { TextButton(onClick = { deleteDialogKeys = null; actionError = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } })
    }
    if (canAccess) ttlDialogKeys?.let { keys ->
        HlovetAlertDialog(onDismissRequest = { if (!busy) ttlDialogKeys = null }, title = { Text(nativePhrase("修改 TTL", "Edit TTL")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nativePhrase("只会修改业务缓存；登录相关缓存由登录策略统一管理。", "Only business cache TTL can be changed; sign-in caches are managed by the sign-in policy."), fontSize = 12.sp)
            Text(nativePhrase("待处理 ${keys.size} 项", "${keys.size} items remaining"), fontSize = 11.sp)
            OutlinedTextField(value = ttlDraft, onValueChange = { ttlDraft = it; actionError = null }, enabled = !busy, modifier = Modifier.testTag("cache-ttl-input"), singleLine = true, label = { Text(nativePhrase("TTL（秒）", "TTL (seconds)"), fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 12.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = ttlKeyboardActions, colors = nativeCacheFieldColors())
            actionError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
        } }, confirmButton = { TextButton(onClick = { updateTtl(keys) }, enabled = !busy) { Text(if (busy) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save")) } }, dismissButton = { TextButton(onClick = { ttlDialogKeys = null; actionError = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } })
    }
    if (canAccess && deleteDialogKeys == null && ttlDialogKeys == null) detail?.let { current ->
        Dialog(onDismissRequest = { if (!busy) detail = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            GlassCard(modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 16.dp), shape = HlovetUi.cardShape, containerColor = HlovetUi.dialogSurface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) { Text(nativePhrase("缓存键详情", "Cache key details"), fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(current.summary.key, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = { detail = null }, enabled = !busy) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭详情", "Close details")) }
                    }
                    actionError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
                    notice?.let { Text(it, color = HlovetUi.accent, fontSize = 11.sp) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("cache-detail-content"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${cacheCategory(current.summary.category)} · ${current.summary.type} · TTL ${cacheTtl(current.summary.ttlSeconds)} · ${formatCacheBytes(current.summary.memoryBytes)}", color = HlovetUi.muted, fontSize = 10.sp)
                    Text(nativePhrase("${current.length ?: "—"} 个元素 · ${if (current.truncated) "内容已截断" else "完整显示"}", "${current.length ?: "—"} element(s) · ${if (current.truncated) "Content truncated" else "Full content"}"), color = HlovetUi.muted, fontSize = 10.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("键值内容", "Key value"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = { clipboard.setText(AnnotatedString(current.valueText)); notice = nativePhrase("已复制脱敏内容。", "Redacted content copied.") }) { Text(nativePhrase("复制", "Copy"), fontSize = 10.sp) } }
                    Text(current.valueText.ifBlank { nativePhrase("（空值）", "(Empty value)") }, modifier = Modifier.fillMaxWidth().padding(11.dp), fontSize = 10.sp, lineHeight = 14.sp)
                    if (current.summary.canUpdateTtl) {
                        OutlinedTextField(value = ttlDraft, onValueChange = { ttlDraft = it; actionError = null }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("cache-detail-ttl-input"), singleLine = true, label = { Text(nativePhrase("设置 TTL（秒）", "Set TTL (seconds)"), fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 12.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = ttlKeyboardActions, colors = nativeCacheFieldColors())
                        TextButton(onClick = { updateTtl(listOf(current.summary.key)) }, enabled = !busy) { Text(nativePhrase("保存 TTL", "Save TTL"), fontSize = 11.sp) }
                    } else {
                        Text(nativePhrase("认证缓存 TTL 由登录策略统一管理。", "Authentication cache TTL is managed by the sign-in policy."), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { actionError = null; deleteProgress = NativeCacheDeleteResult(0, 0, 0); deleteDialogKeys = listOf(current.summary.key) }, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(cacheDeleteLabel(current.summary.category), color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun NativeCacheOverviewCard(overview: NativeCacheOverview) {
    val metrics = listOf(nativePhrase("Redis", "Redis") to "v${overview.redisVersion}", nativePhrase("缓存键", "Cache keys") to overview.keyCount.toString(), nativePhrase("已用内存", "Used memory") to formatCacheBytes(overview.usedMemoryBytes), nativePhrase("内存占比", "Memory usage") to (if (overview.maxMemoryBytes > 0) "${(overview.usedMemoryBytes.toDouble() / overview.maxMemoryBytes * 100).toFixed(1)}%" else nativePhrase("未限制", "Unlimited")), nativePhrase("连接数", "Connections") to overview.connectedClients.toString(), nativePhrase("命中率", "Hit rate") to (overview.hitRate?.let { "${(it * 100).toFixed(1)}%" } ?: nativePhrase("暂无数据", "No data")), nativePhrase("已过期", "Expired") to overview.expiredKeys.toString(), nativePhrase("已淘汰", "Evicted") to overview.evictedKeys.toString())
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Memory, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("Redis 运行概览", "Redis overview"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }; metrics.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (label, value) -> Column(Modifier.weight(1f)) { Text(label, color = HlovetUi.muted, fontSize = 9.sp); Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } } }
}

@Composable
private fun NativeCacheToolbar(searchDraft: String, type: String, category: String, batchSize: Int, onSearch: (String) -> Unit, onType: (String) -> Unit, onCategory: (String) -> Unit, onBatchSize: (Int) -> Unit, enabled: Boolean) {
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = searchDraft, onValueChange = onSearch, enabled = enabled, modifier = Modifier.weight(1f).testTag("cache-search"), singleLine = true, placeholder = { Text(nativePhrase("搜索键名", "Search key name"), fontSize = 10.sp) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeCacheFieldColors())
        NativeCacheMenu(nativePhrase("分类", "Category"), category, listOf("" to nativePhrase("全部", "All"), "refresh-session" to nativePhrase("登录会话", "Sign-in sessions"), "user-sessions" to nativePhrase("用户会话", "User sessions"), "login-failure" to nativePhrase("失败计数", "Failure counters"), "business-cache" to nativePhrase("业务缓存", "Business cache")), onCategory, enabled)
        NativeCacheMenu(nativePhrase("类型", "Type"), type, listOf("" to nativePhrase("全部", "All"), "string" to "String", "list" to "List", "set" to "Set", "zset" to "ZSet", "hash" to "Hash", "stream" to "Stream"), onType, enabled)
        NativeCacheMenu(nativePhrase("数量", "Count"), batchSize.toString(), listOf("10" to "10", "20" to "20", "50" to "50", "100" to "100"), { onBatchSize(it.toIntOrNull() ?: 10) }, enabled)
    }
}

/** 展示 title 对应缓存筛选菜单；selected/options 使用业务代码，enabled 控制交互，onSelected 通知页面重新筛选。 */
@Composable
private fun NativeCacheMenu(title: String, selected: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit, enabled: Boolean) {
    // 只维护弹层开合，筛选结果和网络状态由父页面管理。
    var expanded by remember(title) { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("cache-menu-$title")) { Text(options.firstOrNull { it.first == selected }?.second ?: title, fontSize = 10.sp) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) { options.forEach { (value, label) -> DropdownMenuItem(text = { Text(label, fontSize = 11.sp) }, onClick = { onSelected(value); expanded = false }) } }
    }
}

@Composable
private fun NativeCacheKeyRow(item: NativeCacheKeySummary, selected: Boolean, busy: Boolean, onSelected: (Boolean) -> Unit, onOpen: (String) -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            HlovetCheckbox(checked = selected, onCheckedChange = onSelected, enabled = !busy, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.key, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${cacheCategory(item.category)} · ${item.type} · TTL ${cacheTtl(item.ttlSeconds)} · ${formatCacheBytes(item.memoryBytes)}${if (!item.canUpdateTtl) nativePhrase(" · 系统管理", " · System managed") else ""}", color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onOpen(item.key) }, enabled = !busy) { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text(nativePhrase("查看", "View"), fontSize = 10.sp) }
        }
    }
}

@Composable
private fun NativeCacheMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isError) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Close"), fontSize = 10.sp) } } } }
}

private fun cacheCategory(value: String): String = mapOf("refresh-session" to nativePhrase("登录会话", "Sign-in sessions"), "user-sessions" to nativePhrase("用户会话", "User sessions"), "login-failure" to nativePhrase("失败计数", "Failure counters"), "business-cache" to nativePhrase("业务缓存", "Business cache"))[value] ?: value.ifBlank { nativePhrase("未知分类", "Unknown category") }
private fun cacheDeleteLabel(value: String): String = mapOf("refresh-session" to nativePhrase("撤销此会话", "Revoke this session"), "user-sessions" to nativePhrase("撤销全部会话", "Revoke all sessions"), "login-failure" to nativePhrase("清除失败计数", "Clear failure counter"))[value] ?: nativePhrase("删除缓存键", "Delete cache key")
private fun cacheTtl(seconds: Long): String = when { seconds == -1L -> nativePhrase("永久", "Persistent"); seconds < 0 -> nativePhrase("已失效", "Expired"); seconds < 60 -> nativePhrase("$seconds 秒", "$seconds sec"); seconds < 3600 -> nativePhrase("${seconds / 60} 分钟", "${seconds / 60} min"); seconds < 86400 -> nativePhrase("${seconds / 3600} 小时", "${seconds / 3600} hr"); else -> nativePhrase("${seconds / 86400} 天", "${seconds / 86400} days") }
private fun formatCacheBytes(value: Long?): String { if (value == null || value < 0) return "—"; if (value < 1024) return "$value B"; var amount = value.toDouble(); val units = arrayOf("B", "KB", "MB", "GB"); var index = 0; while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }; return "${"%.1f".format(amount)} ${units[index]}" }
private fun Double.toFixed(digits: Int): String = "%1$.${digits}f".format(this)

@Composable
private fun nativeCacheFieldColors() = HlovetUi.fieldColors()
