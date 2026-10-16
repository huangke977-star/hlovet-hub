@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 展示 session 在当前服务器的离线内容；context 用于本地存储，回调负责返回或打开对应内容。 */
@Composable
internal fun NativeOfflineScreen(
    session: NativeSession?,
    context: Context,
    onBack: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
    onOpenTopic: (RemoteTopic) -> Unit,
    onOpenCollection: (RemoteCollection) -> Unit,
) {
    // 所有者跟随稳定账号 ID，来源由存储层统一加入，页面不查询其它服务器条目。
    val owner = NativeOfflineOwner.forSession(session)
    val scope = rememberCoroutineScope()
    var entries by remember(owner) { mutableStateOf<List<NativeOfflineContentEntry>>(emptyList()) }
    var mediaCache by remember(owner) { mutableStateOf(NativeOfflineCache.MediaCacheSummary(0, 0L, 32L * 1024L * 1024L)) }
    var loading by remember(owner) { mutableStateOf(true) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var legacyDataPresent by remember(owner) { mutableStateOf(false) }

    /** 读取当前来源账号的正文索引和共享缓存占用，不发起网络请求。 */
    fun load() {
        // 每次刷新独立检查旧数据存在状态，只显示留存提示，不迁移未知来源内容。
        scope.launch {
            loading = true
            error = null
            withContext(Dispatchers.IO) {
                entries = NativeOfflineContentStore.list(context, owner)
                mediaCache = NativeOfflineCache.mediaCacheSummary(context)
                legacyDataPresent = NativeOfflineOrigin.hasLegacyData(context)
            }
            loading = false
        }
    }

    // 切换账号重新读取其索引，保留其它来源和账号的磁盘数据。
    LaunchedEffect(owner) { load() }

    /** 读取并打开当前账号的 entry；文件缺失或损坏时显示反馈，不尝试其它来源。 */
    fun open(entry: NativeOfflineContentEntry) {
        // 内容类型决定回读接口，但每条路径都使用当前服务器的同一所有者边界。
        scope.launch {
            when (entry.kind) {
                "article" -> {
                    val article = withContext(Dispatchers.IO) { NativeOfflineContentStore.loadArticle(context, owner, entry.id) }
                    if (article != null) onOpenArticle(article) else error = nativePhrase("这条离线内容已损坏，请删除后重新保存。", "This offline content is damaged. Delete it and save it again.")
                }
                "topic" -> {
                    val topic = withContext(Dispatchers.IO) { NativeOfflineContentStore.loadTopic(context, owner, entry.id) }
                    if (topic != null) onOpenTopic(topic) else error = nativePhrase("这个离线专题已损坏，请删除后重新保存。", "This offline topic is damaged. Delete it and save it again.")
                }
                "collection" -> {
                    val collection = withContext(Dispatchers.IO) { NativeOfflineContentStore.loadCollection(context, owner, entry.id.toIntOrNull() ?: 0) }
                    if (collection != null) onOpenCollection(collection) else error = nativePhrase("这个离线合集已损坏，请删除后重新保存。", "This offline collection is damaged. Delete it and save it again.")
                }
            }
        }
    }

    // 列表与提示沿用共享玻璃组件，不展示来源不明缓存中的标题或账号信息。
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("离线阅读", "Offline reading"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("断网时继续查看已保存的内容", "Continue viewing saved content without a connection"), color = HlovetUi.muted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }; IconButton(onClick = { clearConfirm = true }, enabled = entries.isNotEmpty()) { Icon(Icons.Filled.Delete, nativePhrase("清空离线内容", "Clear offline content"), tint = HlovetUi.secondaryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(entries.size.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(nativePhrase("项内容", "items"), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(formatOfflineBytes(entries.sumOf { it.sizeBytes }), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(nativePhrase("正文占用", "content"), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(formatOfflineBytes(mediaCache.bytes), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(nativePhrase("媒体缓存", "media cache"), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                    }
                    Text(
                        nativePhrase(
                            "媒体缓存与聊天、AI 结果共用，${mediaCache.fileCount} 个文件 · 上限 ${formatOfflineBytes(mediaCache.maxBytes)}",
                            "Media cache is shared by chat and AI results, ${mediaCache.fileCount} files · limit ${formatOfflineBytes(mediaCache.maxBytes)}",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (legacyDataPresent) {
                        Text(
                            nativePhrase(
                                "来源不明的旧版缓存已保留，暂不可读取。旧版媒体占用 ${formatOfflineBytes(mediaCache.legacyBytes)}。",
                                "Legacy cache without a server origin is retained but unavailable. Legacy media uses ${formatOfflineBytes(mediaCache.legacyBytes)}.",
                            ),
                            color = HlovetUi.muted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        if (loading) item { offlineEmpty(nativePhrase("正在读取离线内容…", "Loading offline content…")) }
        else if (entries.isEmpty()) item { offlineEmpty(nativePhrase("还没有保存离线内容。打开文章、专题或合集后点击右上角的下载图标即可保存。", "No offline content has been saved. Open an article, topic, or collection and tap the download icon to save it.")) }
        else {
            items(entries, key = { it.fileName }) { entry ->
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { open(entry) }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${offlineKindLabel(entry.kind)} · ${formatOfflineTime(entry.cachedAt)} · ${formatOfflineBytes(entry.sizeBytes)}", color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        IconButton(onClick = {
                            // 单条删除必须通过当前来源索引校验，不能删除外部条目文件。
                            scope.launch {
                                withContext(Dispatchers.IO) { NativeOfflineContentStore.delete(context, owner, entry) }
                                entries = withContext(Dispatchers.IO) { NativeOfflineContentStore.list(context, owner) }
                                mediaCache = withContext(Dispatchers.IO) { NativeOfflineCache.mediaCacheSummary(context) }
                            }
                        }, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, nativePhrase("删除离线内容", "Delete offline content"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(17.dp)) }
                    }
                }
            }
        }
        error?.let { item { Text(it, color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    if (clearConfirm) {
        // 清空仅针对当前来源账号，保留线上内容、其它站点及旧版未知来源文件。
        HlovetAlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(nativePhrase("清空离线内容", "Clear offline content")) },
            text = { Text(nativePhrase("确定删除当前站点、当前账号保存的全部离线内容吗？不会影响线上内容和其它站点缓存。", "Delete all offline content for this account on this site? Online content and other sites' caches will not be affected.")) },
             confirmButton = { TextButton(onClick = {
                 scope.launch {
                     withContext(Dispatchers.IO) { NativeOfflineContentStore.clear(context, owner) }
                     entries = emptyList()
                     mediaCache = withContext(Dispatchers.IO) { NativeOfflineCache.mediaCacheSummary(context) }
                     clearConfirm = false
                 }
             }) { Text(nativePhrase("清空", "Clear"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun offlineEmpty(message: String) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) { Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp)) }
}

private fun offlineKindLabel(kind: String): String = when (kind) {
    "topic" -> nativePhrase("专题", "Topic")
    "collection" -> nativePhrase("合集", "Collection")
    else -> nativePhrase("文章", "Article")
}

private fun formatOfflineBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / 1024f / 1024f)
    value >= 1024L -> "%.1f KB".format(value / 1024f)
    else -> "$value B"
}

private fun formatOfflineTime(value: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
