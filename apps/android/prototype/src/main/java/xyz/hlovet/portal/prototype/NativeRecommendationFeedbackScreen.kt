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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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

@Composable
internal fun NativeRecommendationFeedbackScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedType by remember { mutableStateOf(NativeRecommendationTargetType.ALL) }
    var items by remember { mutableStateOf<List<NativeRecommendationFeedbackItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var clearConfirmOpen by remember { mutableStateOf(false) }

    fun load() {
        val token = session?.accessToken
        if (token.isNullOrBlank() || loading) return
        loading = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeRecommendationFeedbackApi.list(token, selectedType) }
            }.onSuccess { items = it }
                .onFailure { error = it.message ?: nativePhrase("无法读取推荐反馈。", "Could not load recommendation feedback.") }
            loading = false
        }
    }

    LaunchedEffect(session?.accessToken, selectedType) { load() }

    fun restore(item: NativeRecommendationFeedbackItem) {
        val token = session?.accessToken ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeRecommendationFeedbackApi.restore(token, item) } }
                .onSuccess {
                    items = items.filterNot { it.id == item.id }
                    notice = nativePhrase("已恢复该项推荐。", "Recommendation restored.")
                }
                .onFailure { error = it.message ?: nativePhrase("恢复推荐失败。", "Could not restore the recommendation.") }
            busy = false
        }
    }

    fun clear() {
        val token = session?.accessToken ?: return
        if (busy) return
        clearConfirmOpen = false
        busy = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeRecommendationFeedbackApi.clear(token, selectedType) } }
                .onSuccess { count ->
                    items = emptyList()
                    notice = nativePhrase("已清理 $count 条推荐反馈。", "Cleared $count recommendation feedback item(s).")
                }
                .onFailure { error = it.message ?: nativePhrase("清理推荐反馈失败。", "Could not clear recommendation feedback.") }
            busy = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(nativePhrase("推荐反馈", "Recommendation feedback"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = ::load, enabled = !loading && !busy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh")) }
                    IconButton(onClick = { clearConfirmOpen = true }, enabled = items.isNotEmpty() && !busy) { Icon(Icons.Filled.DeleteSweep, contentDescription = nativePhrase("清理当前反馈", "Clear current feedback"), tint = HlovetUi.secondaryAccent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(nativePhrase("标记为不感兴趣的内容会暂时退出你的推荐。恢复后，它们可以重新参与推荐。", "Items marked not interested leave your recommendations temporarily and can reappear after you restore them."), color = HlovetUi.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        NativeRecommendationTargetType.ALL,
                        NativeRecommendationTargetType.ARTICLE,
                        NativeRecommendationTargetType.TOPIC,
                        NativeRecommendationTargetType.COLLECTION,
                        NativeRecommendationTargetType.AUTHOR,
                        NativeRecommendationTargetType.GROUP,
                    ).forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(nativePhrase(type.labelZh, type.labelEn), fontSize = 10.sp) },
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HlovetUi.accent, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            if (!error.isNullOrBlank()) item { Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
            if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp) }
            if (loading && items.isEmpty()) item { Text(nativePhrase("正在读取推荐反馈…", "Loading recommendation feedback..."), color = HlovetUi.muted, fontSize = 12.sp) }
            if (!loading && items.isEmpty()) item {
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(24.dp))
                        Text(nativePhrase("没有不感兴趣反馈", "No not-interested feedback"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(nativePhrase("你标记不感兴趣的内容会出现在这里。", "Items you mark not interested will appear here."), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                }
            }
            items(items, key = { it.id }) { item ->
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${nativePhrase(item.targetType.labelZh, item.targetType.labelEn)} · ${formatRecommendationTime(item.createdAt)}", color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        IconButton(onClick = { restore(item) }, enabled = !busy, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Restore, contentDescription = nativePhrase("恢复推荐", "Restore recommendation"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (clearConfirmOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) clearConfirmOpen = false },
            title = { Text(if (selectedType == NativeRecommendationTargetType.ALL) nativePhrase("清理全部推荐反馈", "Clear all recommendation feedback") else nativePhrase("清理当前类型反馈", "Clear this feedback type")) },
            text = { Text(nativePhrase("清理后，相关内容可以重新参与推荐。这个操作不会删除文章或其他站内内容。", "After clearing, these items can appear in recommendations again. Articles and other site content are not deleted."), color = HlovetUi.muted, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = ::clear, enabled = !busy) { Text(if (busy) nativePhrase("处理中…", "Working...") else nativePhrase("确认清理", "Clear")) } },
            dismissButton = { TextButton(onClick = { clearConfirmOpen = false }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

private fun formatRecommendationTime(value: String): String = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date.from(java.time.Instant.parse(value)))
}.getOrElse { value.take(16).replace('T', ' ') }
