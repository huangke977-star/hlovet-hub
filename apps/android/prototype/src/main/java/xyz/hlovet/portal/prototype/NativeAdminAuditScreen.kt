@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeAdminAuditScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val token = session?.accessToken
    var logs by remember(token) { mutableStateOf<NativeAuditLogPage?>(null) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var scopeFilter by remember(token) { mutableStateOf("") }
    var resultFilter by remember(token) { mutableStateOf("") }
    var page by remember(token) { mutableIntStateOf(1) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var expandedId by remember(token) { mutableIntStateOf(0) }
    var exporting by remember(token) { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException(nativePhrase("无法写入所选文件。", "Could not write the selected file."))
        }.onSuccess { notice = nativePhrase("审计日志已保存。", "Audit log saved.") }
            .onFailure { error = it.message ?: nativePhrase("审计日志保存失败。", "Could not save the audit log.") }
    }

    LaunchedEffect(searchDraft) {
        delay(300)
        val normalized = searchDraft.trim()
        if (normalized != search) {
            search = normalized
            page = 1
        }
    }

    LaunchedEffect(token, page, search, scopeFilter, resultFilter) {
        if (token.isNullOrBlank()) {
            logs = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeAdminDataApi.listAuditLogs(token, page, search = search, scope = scopeFilter, result = resultFilter)
            }
        }.onSuccess {
            logs = it
            if (it.page != page) page = it.page
        }.onFailure { error = it.message ?: nativePhrase("审计日志读取失败，请稍后重试。", "Could not load the audit log. Try again.") }
        loading = false
    }

    fun export() {
        val currentToken = token ?: return
        if (exporting) return
        exporting = true
        error = null
        scope.launch {
            try {
                exportBytes = withContext(Dispatchers.IO) {
                    NativeAdminDataApi.exportAuditLogs(currentToken, search, scopeFilter, resultFilter)
                }
                saveExport.launch("audit-log.csv")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("审计日志导出失败。", "Audit log export failed.")
            } finally {
                exporting = false
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
                        Text(nativePhrase("审计日志", "Audit log"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("管理员操作与系统记录", "Administrator actions and system records"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    if (session?.isSuperAdmin == true) {
                        IconButton(onClick = ::export, enabled = !exporting && !loading) {
                            Icon(Icons.Filled.Download, contentDescription = nativePhrase("导出", "Export"), tint = if (exporting) HlovetUi.muted else HlovetUi.accent)
                        }
                    }
                    IconButton(onClick = { page = 1 }, enabled = !loading && !token.isNullOrBlank()) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = if (loading) HlovetUi.muted else HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it.take(100) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text(nativePhrase("搜索操作者、路径、操作或目标", "Search actor, path, action, or target"), fontSize = 11.sp) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                colors = HlovetUi.fieldColors(),
            )
        }
        item {
            AuditFilterRow(nativePhrase("范围", "Scope"), listOf("" to nativePhrase("全部", "All"), "business" to nativePhrase("业务", "Business"), "security" to nativePhrase("安全", "Security"), "server" to nativePhrase("服务器", "Server")), scopeFilter) { scopeFilter = it; page = 1 }
        }
        item {
            AuditFilterRow(nativePhrase("结果", "Result"), listOf("" to nativePhrase("全部", "All"), "success" to nativePhrase("成功", "Success"), "failed" to nativePhrase("失败", "Failed")), resultFilter) { resultFilter = it; page = 1 }
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (token.isNullOrBlank()) {
            item { AuditMessage(nativePhrase("登录后查看审计日志。", "Sign in to view the audit log."), Icons.Filled.Shield) }
        } else if (loading && logs == null) {
            item { AuditMessage(nativePhrase("正在读取审计日志…", "Loading audit log…"), Icons.Filled.Info) }
        } else if (!error.isNullOrBlank() && logs == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        TextButton(onClick = { page = 1 }, enabled = !loading) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                    }
                }
            }
        } else {
            if (!error.isNullOrBlank()) item { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            logs?.let { current ->
                item { Text(nativePhrase("共 ${current.total} 条记录 · 第 ${current.page}/${current.totalPages} 页", "${current.total} records · Page ${current.page}/${current.totalPages}"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                if (current.items.isEmpty()) {
                    item { AuditMessage(nativePhrase("暂无匹配的审计记录。", "No matching audit records."), Icons.Filled.Info) }
                } else {
                    items(current.items) { item ->
                        AuditLogCard(item, expanded = expandedId == item.id) { expandedId = if (expandedId == item.id) 0 else item.id }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(nativePhrase("第 ${current.page} / ${current.totalPages} 页", "Page ${current.page} / ${current.totalPages}"), color = HlovetUi.muted, fontSize = 10.sp)
                        Row {
                            TextButton(onClick = { page -= 1 }, enabled = !loading && current.page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                            TextButton(onClick = { page += 1 }, enabled = !loading && current.page < current.totalPages) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditFilterRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.width(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (value, text) ->
                TextButton(onClick = { onSelect(value) }) {
                    Text(text, color = if (selected == value) HlovetUi.accent else HlovetUi.muted, fontSize = 10.sp, fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun AuditLogCard(item: NativeAuditLog, expanded: Boolean, onToggle: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onToggle), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.action.ifBlank { nativePhrase("管理操作", "Admin action") }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (item.statusCode < 400) nativePhrase("成功", "Success") else nativePhrase("失败 ${item.statusCode}", "Failed ${item.statusCode}"), color = if (item.statusCode < 400) HlovetUi.accent else Color(0xFFB42318), fontSize = 10.sp)
            }
            Text(item.summary.ifBlank { "${item.method} ${item.path}" }, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${item.actor.nickname.ifBlank { item.actor.username }} · ${auditScopeLabel(item.scope)}", color = HlovetUi.muted, fontSize = 9.sp)
                Text(auditTime(item.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
            }
            Text(nativePhrase("${item.method} ${item.path} · ${item.durationMs} ms · 目标 ${item.targetId ?: "-"}", "${item.method} ${item.path} · ${item.durationMs} ms · Target ${item.targetId ?: "-"}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                Spacer(Modifier.height(3.dp))
                Text(nativePhrase("动作代码：${item.action}", "Action code: ${item.action}"), color = HlovetUi.muted, fontSize = 10.sp)
                Text(nativePhrase("目标类型：${item.targetType ?: "-"} · IP：${item.ip.ifBlank { "-" }}", "Target type: ${item.targetType ?: "-"} · IP: ${item.ip.ifBlank { "-" }}"), color = HlovetUi.muted, fontSize = 10.sp)
                Text(nativePhrase("浏览器：${item.userAgent.ifBlank { "-" }}", "Browser: ${item.userAgent.ifBlank { "-" }}"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.metadata.isNotBlank()) {
                    Text(nativePhrase("元数据", "Metadata"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(item.metadata, color = HlovetUi.muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AuditMessage(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

private fun auditScopeLabel(scope: String): String = when (scope) {
    "business" -> nativePhrase("业务", "Business")
    "security" -> nativePhrase("安全", "Security")
    "server" -> nativePhrase("服务器", "Server")
    else -> scope.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun auditTime(value: String): String = value.replace('T', ' ').replace(Regex("\\.\\d+Z$"), "").replace("Z", "").takeLast(19)
