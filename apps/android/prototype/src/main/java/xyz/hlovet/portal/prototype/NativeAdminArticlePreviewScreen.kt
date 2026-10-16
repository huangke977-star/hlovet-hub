@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only administrator preview for article status links from the PC portal. */
@Composable
internal fun NativeAdminArticlePreviewScreen(
    session: NativeSession,
    articleId: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var article by remember(session.accessToken, articleId) { mutableStateOf<RemoteArticle?>(null) }
    var loading by remember(session.accessToken, articleId) { mutableStateOf(true) }
    var error by remember(session.accessToken, articleId) { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) { NativeArticleApi.getAdminArticle(session.accessToken, articleId) }
            }.onSuccess { article = it }
                .onFailure { error = it.message ?: nativePhrase("文章预览加载失败。", "Could not load the article preview.") }
            loading = false
        }
    }

    LaunchedEffect(session.accessToken, articleId) { load() }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("文章预览", "Article preview"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("查看文章状态，不改变文章内容", "Review article status without changing content"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = ::load, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading && article == null) {
            item { previewStateCard(nativePhrase("正在读取文章…", "Loading article…"), null, isError = false) }
        } else if (error != null && article == null) {
            item { previewStateCard(error.orEmpty(), ::load, isError = true) }
        } else {
            article?.let { current ->
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FilePresent, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                                Text(
                                    nativeAdminArticleStatusLabel(current.status),
                                    color = if (current.status == "blocked" || current.status == "deleted") HlovetUi.secondaryAccent else HlovetUi.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Text(
                                current.title.ifBlank { nativePhrase("未命名文章", "Untitled article") },
                                fontSize = 24.sp,
                                lineHeight = 31.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${current.authorName} · @${current.authorUsername.ifBlank { "unknown" }}",
                                color = HlovetUi.muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!current.updatedAt.isNullOrBlank()) {
                                Text(nativePhrase("更新于 ${current.updatedAt}", "Updated ${current.updatedAt}"), color = HlovetUi.muted, fontSize = 11.sp)
                            }
                            if (!current.blockedReason.isNullOrBlank()) {
                                Text(nativePhrase("屏蔽说明：${current.blockedReason}", "Blocked because: ${current.blockedReason}"), color = HlovetUi.secondaryAccent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape,
                    ) {
                        NativePreviewWebView(
                            title = current.title,
                            summary = current.summary,
                            content = current.content,
                            contentFormat = current.contentFormat,
                            modifier = Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun previewStateCard(message: String, retry: (() -> Unit)?, isError: Boolean) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.rowShape,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isError) Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = HlovetUi.secondaryAccent)
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (retry != null) IconButton(onClick = retry) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("重试", "Retry"), tint = HlovetUi.accent) }
        }
    }
}

private fun nativeAdminArticleStatusLabel(status: String): String = when (status) {
    "draft" -> nativePhrase("草稿", "Draft")
    "published" -> nativePhrase("已发布", "Published")
    "unpublished" -> nativePhrase("已下架", "Unpublished")
    "blocked" -> nativePhrase("已屏蔽", "Blocked")
    "deleted" -> nativePhrase("已删除", "Deleted")
    else -> status.ifBlank { nativePhrase("未知状态", "Unknown status") }
}
