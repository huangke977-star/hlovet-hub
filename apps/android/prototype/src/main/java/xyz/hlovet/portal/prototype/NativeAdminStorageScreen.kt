@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NativeStorageTab { ISSUES, TRASH, SETTINGS }

private fun nativeStorageCategories() = listOf(
    "" to nativePhrase("全部分类", "All categories"),
    "backgrounds" to nativePhrase("背景", "Backgrounds"),
    "site-assets" to nativePhrase("站点资源", "Site assets"),
    "android-releases" to nativePhrase("Android 安装包", "Android packages"),
    "avatars" to nativePhrase("头像", "Avatars"),
    "articles" to nativePhrase("文章", "Articles"),
    "chat" to nativePhrase("聊天", "Chat"),
)

private fun nativeStorageKinds() = listOf(
    "" to nativePhrase("全部异常", "All issue types"),
    "missing" to nativePhrase("文件缺失", "Missing file"),
    "orphan" to nativePhrase("孤立文件", "Orphan file"),
    "metadata_mismatch" to nativePhrase("大小不一致", "Size mismatch"),
)

@Composable
internal fun NativeAdminStorageScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(token) { mutableStateOf(NativeStorageTab.ISSUES) }
    var overview by remember(token) { mutableStateOf<NativeStorageOverview?>(null) }
    var issues by remember(token) { mutableStateOf<NativeStorageIssuePage?>(null) }
    var trash by remember(token) { mutableStateOf<NativeStorageTrashPage?>(null) }
    var configuration by remember(token) { mutableStateOf<NativeStorageConfiguration?>(null) }
    var issueKind by remember(token) { mutableStateOf("") }
    var category by remember(token) { mutableStateOf("") }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var issuePage by remember(token) { mutableStateOf(1) }
    var trashPage by remember(token) { mutableStateOf(1) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var scanning by remember(token) { mutableStateOf(false) }
    var busy by remember(token) { mutableStateOf<String?>(null) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableStateOf(0) }
    var preview by remember { mutableStateOf<NativeStoragePreview?>(null) }
    var unrecoverable by remember { mutableStateOf<NativeStorageIssue?>(null) }
    var note by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<Pair<NativeStorageIssue, List<NativeStorageRepair>>?>(null) }
    var reuploadIssue by remember { mutableStateOf<NativeStorageIssue?>(null) }
    var download by remember { mutableStateOf<NativeStorageDownload?>(null) }

    val saveDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val pending = download
        download = null
        if (uri != null && pending != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.file.bytes) } ?: error(nativePhrase("无法保存文件。", "Could not save the file.")) }
                .onSuccess { notice = nativePhrase("文件已保存。", "File saved.") }
                .onFailure { error = it.message ?: nativePhrase("文件保存失败。", "Could not save the file.") }
        }
    }
    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val issue = reuploadIssue
        reuploadIssue = null
        if (uri != null && issue != null && token != null) {
            busy = "reupload:${issue.id}"
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, 50L * 1024L * 1024L) }
                    withContext(Dispatchers.IO) { NativeAdminStorageApi.reupload(token, issue.id, file) }
                    notice = nativePhrase("替换文件已上传并写回原位置。", "Replacement uploaded and written back to the original location.")
                    reloadKey++
                } catch (exception: Exception) { error = exception.message ?: nativePhrase("替换文件上传失败。", "Could not upload the replacement.") } finally { busy = null }
            }
        }
    }

    fun reload() { reloadKey++ }
    fun refreshData(accessToken: String) {
        scope.launch {
            try {
                val current = withContext(Dispatchers.IO) { NativeAdminStorageApi.overview(accessToken) }
                overview = current
                configuration = current.configuration
                scanning = current.latestScan?.status == "running"
                issues = withContext(Dispatchers.IO) { NativeAdminStorageApi.issues(accessToken, issuePage, 20, issueKind, category, search) }
                trash = withContext(Dispatchers.IO) { NativeAdminStorageApi.trash(accessToken, trashPage, 20, category) }
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("存储管理读取失败。", "Could not load storage management data.") } finally { loading = false }
        }
    }

    LaunchedEffect(searchDraft) {
        delay(280)
        val next = searchDraft.trim()
        if (next != search) { search = next; issuePage = 1 }
    }
    LaunchedEffect(token, canAccess, issuePage, trashPage, issueKind, category, search, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        refreshData(token)
    }
    LaunchedEffect(token, overview?.latestScan?.id, overview?.latestScan?.status) {
        val accessToken = token ?: return@LaunchedEffect
        val scan = overview?.latestScan ?: return@LaunchedEffect
        if (scan.status != "running") return@LaunchedEffect
        while (true) {
            delay(1200)
            val current = runCatching { withContext(Dispatchers.IO) { NativeAdminStorageApi.scan(accessToken, scan.id) } }.getOrNull() ?: break
            overview = overview?.copy(latestScan = current)
            if (current.status != "running") { scanning = false; notice = if (current.status == "completed") nativePhrase("存储扫描已完成。", "Storage scan completed.") else current.error ?: nativePhrase("存储扫描失败。", "Storage scan failed."); reloadKey++; break }
        }
    }

    fun runAction(key: String, action: suspend () -> Unit, success: String) {
        if (busy != null) return
        busy = key
        error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; notice = success; reloadKey++ }
            catch (exception: Exception) { error = exception.message ?: nativePhrase("存储操作失败。", "Storage operation failed.") }
            finally { busy = null }
        }
    }

    fun fetchIssueFile(issue: NativeStorageIssue, forDownload: Boolean) {
        val accessToken = token ?: return
        if (busy != null) return
        busy = "file:${issue.id}"
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { NativeAdminStorageApi.issueFile(accessToken, issue.id) }
                if (forDownload) {
                    val name = issue.storedName.substringAfterLast('/').ifBlank { "storage-file" }
                    download = NativeStorageDownload(name, file)
                    saveDownload.launch(name)
                } else {
                    preview = NativeStoragePreview(issue.storedName, file)
                }
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("文件读取失败。", "Could not read the file.") } finally { busy = null }
        }
    }

    fun loadRepairHistory(issue: NativeStorageIssue) {
        val accessToken = token ?: return
        if (busy != null) return
        busy = "history:${issue.id}"
        error = null
        scope.launch {
            try { val repairs = withContext(Dispatchers.IO) { NativeAdminStorageApi.repairs(accessToken, issue.id) }; history = issue to repairs }
            catch (exception: Exception) { error = exception.message ?: nativePhrase("修复记录读取失败。", "Could not load repair history.") }
            finally { busy = null }
        }
    }

    fun saveConfiguration(current: NativeStorageConfiguration) {
        val accessToken = token ?: return
        if (busy != null) return
        busy = "configuration"
        error = null
        scope.launch {
            try { configuration = withContext(Dispatchers.IO) { NativeAdminStorageApi.updateConfiguration(accessToken, current) }; notice = nativePhrase("存储策略已保存。", "Storage policy saved."); reloadKey++ }
            catch (exception: Exception) { error = exception.message ?: nativePhrase("存储策略保存失败。", "Could not save the storage policy.") }
            finally { busy = null }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("存储管理", "Storage management"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("完整性扫描、修复和回收站", "Integrity scans, repairs, and trash"), color = HlovetUi.muted, fontSize = 10.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::reload, enabled = !loading && busy == null) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (token.isNullOrBlank()) item { NativeStorageMessage(nativePhrase("登录后查看存储管理。", "Sign in to view storage management."), false) }
        else if (!canAccess) item { NativeStorageMessage(nativePhrase("存储管理仅超级管理员可访问。", "Storage management is available to super administrators only."), true) }
        else if (loading && overview == null) item { NativeStorageMessage(nativePhrase("正在读取存储状态…", "Loading storage status..."), false) }
        else {
            if (!error.isNullOrBlank()) item { NativeStorageMessage(error!!, true) { error = null } }
            if (!notice.isNullOrBlank()) item { NativeStorageMessage(notice!!, false) { notice = null } }
            overview?.let { current ->
                item { NativeStorageSummaryCard(current, scanning, busy == null) { if (token != null) runAction("scan", { NativeAdminStorageApi.startScan(token) }, nativePhrase("存储扫描已开始。", "Storage scan started.")) } }
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        NativeStorageTabButton(nativePhrase("异常文件", "Issues"), tab == NativeStorageTab.ISSUES, { tab = NativeStorageTab.ISSUES }, Modifier.weight(1f))
                        NativeStorageTabButton(nativePhrase("回收站", "Trash"), tab == NativeStorageTab.TRASH, { tab = NativeStorageTab.TRASH }, Modifier.weight(1f))
                        NativeStorageTabButton(nativePhrase("扫描策略", "Scan policy"), tab == NativeStorageTab.SETTINGS, { tab = NativeStorageTab.SETTINGS }, Modifier.weight(1f))
                    }
                }
            }
            when (tab) {
                NativeStorageTab.ISSUES -> {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(value = searchDraft, onValueChange = { searchDraft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, placeholder = { Text(nativePhrase("搜索文件名、来源或上传账号", "Search filename, source, or uploader"), fontSize = 11.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), colors = nativeStorageFieldColors())
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                                NativeStorageFilter(nativePhrase("异常类型", "Issue type"), issueKind, nativeStorageKinds(), { issueKind = it; issuePage = 1 }, Modifier.weight(1f))
                                NativeStorageFilter(nativePhrase("文件分类", "File category"), category, nativeStorageCategories(), { category = it; issuePage = 1; trashPage = 1 }, Modifier.weight(1f))
                            }
                        }
                    }
                    if (issues?.items.isNullOrEmpty() && !loading) item { NativeStorageMessage(if (overview?.latestScan == null) nativePhrase("请先执行一次存储扫描。", "Run a storage scan first.") else nativePhrase("当前筛选条件下没有异常文件。", "No issue files match the current filters."), false) }
                    items(issues?.items.orEmpty(), key = { it.id }) { issue ->
                        NativeStorageIssueCard(issue, busy, onPreview = { fetchIssueFile(issue, false) }, onDownload = { fetchIssueFile(issue, true) }, onRemoteRestore = { if (token != null) runAction("remote:${issue.id}", { NativeAdminStorageApi.restoreRemote(token, issue.id) }, nativePhrase("文件已从远端备份恢复。", "File restored from remote backup.")) }, onReupload = { reuploadIssue = issue; pickReplacement.launch(arrayOf("*/*")) }, onUnrecoverable = { unrecoverable = issue; note = "" }, onHistory = { loadRepairHistory(issue) }, onTrash = { if (token != null) runAction("trash:${issue.id}", { NativeAdminStorageApi.trashIssue(token, issue.id) }, nativePhrase("文件已移入回收站。", "File moved to trash.")) })
                    }
                    item { NativeStoragePagination(issues?.page ?: 1, issues?.pageCount ?: 0, { if (issuePage > 1) issuePage-- }, { if (issuePage < (issues?.pageCount ?: 0)) issuePage++ }, loading) }
                }
                NativeStorageTab.TRASH -> {
                    item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("回收站 ${trash?.total ?: 0} 个文件", "Trash: ${trash?.total ?: 0} files"), fontSize = 12.sp, modifier = Modifier.weight(1f)); NativeStorageFilter(nativePhrase("分类", "Category"), category, nativeStorageCategories(), { category = it; trashPage = 1 }, Modifier.width(150.dp)) } }
                    if (trash?.items.isNullOrEmpty() && !loading) item { NativeStorageMessage(nativePhrase("回收站为空。", "Trash is empty."), false) }
                    items(trash?.items.orEmpty(), key = { it.id }) { entry ->
                        NativeStorageTrashCard(entry, busy, onRestore = { if (token != null) runAction("restore:${entry.id}", { NativeAdminStorageApi.restoreTrash(token, entry.id) }, nativePhrase("文件已恢复到原位置。", "File restored to its original location.")) }, onDelete = { if (token != null) runAction("delete:${entry.id}", { NativeAdminStorageApi.deleteTrash(token, entry.id) }, nativePhrase("回收站文件已永久删除。", "Trash file permanently deleted.")) })
                    }
                    item { NativeStoragePagination(trash?.page ?: 1, trash?.pageCount ?: 0, { if (trashPage > 1) trashPage-- }, { if (trashPage < (trash?.pageCount ?: 0)) trashPage++ }, loading) }
                }
                NativeStorageTab.SETTINGS -> {
                    configuration?.let { current -> item { NativeStorageConfigurationCard(current, busy == null, onChange = { configuration = it }, onSave = { saveConfiguration(current) }) } }
                }
            }
        }
    }

    unrecoverable?.let { issue ->
        HlovetAlertDialog(onDismissRequest = { if (busy == null) unrecoverable = null }, title = { Text(nativePhrase("确认无法恢复", "Confirm unrecoverable")) }, text = { Column { Text(issue.storedName, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(nativePhrase("处理说明（可选）", "Processing note (optional)")) }, minLines = 3, maxLines = 4, colors = nativeStorageFieldColors()) } }, confirmButton = { TextButton(onClick = { if (token != null) { unrecoverable = null; runAction("unrecoverable:${issue.id}", { NativeAdminStorageApi.confirmUnrecoverable(token, issue.id, note.trim()) }, nativePhrase("已记录为无法恢复。", "Marked as unrecoverable.")) } }, enabled = busy == null) { Text(nativePhrase("确认记录", "Record")) } }, dismissButton = { TextButton(onClick = { unrecoverable = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) } })
    }
    history?.let { (issue, repairs) ->
        HlovetAlertDialog(onDismissRequest = { history = null }, title = { Text(nativePhrase("文件修复记录", "File repair history")) }, text = { Column { Text(issue.storedName, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(7.dp)); if (repairs.isEmpty()) Text(nativePhrase("暂无修复记录。", "No repair history."), color = HlovetUi.muted, fontSize = 11.sp) else repairs.forEach { repair -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(nativeStorageRepairAction(repair.action), fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Text(repair.note ?: repair.error ?: repair.completedAt ?: repair.startedAt, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }; Text(nativeStorageRepairStatus(repair.status), color = if (repair.status == "completed") HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 10.sp) } } } }, confirmButton = { TextButton(onClick = { history = null }) { Text(nativePhrase("关闭", "Close")) } })
    }
    preview?.let { current ->
        val bitmap = remember(current.file.bytes) { BitmapFactory.decodeByteArray(current.file.bytes, 0, current.file.bytes.size) }
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            GlassCard(modifier = Modifier.fillMaxWidth(.92f), shape = HlovetUi.cardShape) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(current.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); IconButton(onClick = { preview = null }) { Text("×", fontSize = 22.sp) } }; if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = current.name, modifier = Modifier.fillMaxWidth().height(320.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) else Text(nativePhrase("此文件不是可直接预览的图片，请使用下载保存。", "This file is not a previewable image. Download it to save."), color = HlovetUi.muted, fontSize = 11.sp) } }
        }
    }
}

private data class NativeStoragePreview(val name: String, val file: NativeStorageFile)
private data class NativeStorageDownload(val name: String, val file: NativeStorageFile)

@Composable
private fun NativeStorageSummaryCard(current: NativeStorageOverview, scanning: Boolean, enabled: Boolean, onScan: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("存储概览", "Storage overview"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f)); TextButton(onClick = onScan, enabled = enabled && !scanning) { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp)); Text(if (scanning) nativePhrase("扫描中…", "Scanning...") else nativePhrase("立即扫描", "Scan now"), fontSize = 10.sp) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { NativeStorageMetric(nativePhrase("缺失", "Missing"), current.openMissing.toString(), HlovetUi.secondaryAccent, Modifier.weight(1f)); NativeStorageMetric(nativePhrase("孤立", "Orphaned"), current.openOrphan.toString(), HlovetUi.secondaryAccent, Modifier.weight(1f)); NativeStorageMetric(nativePhrase("不一致", "Mismatch"), current.openMismatch.toString(), HlovetUi.secondaryAccent, Modifier.weight(1f)); NativeStorageMetric(nativePhrase("回收站", "Trash"), current.trashCount.toString(), HlovetUi.muted, Modifier.weight(1f)) }
            val summary = current.latestScan?.summary
            Text(if (summary == null) nativePhrase("尚未执行扫描", "No scan has run") else nativePhrase("最近扫描：${storageTime(current.latestScan.completedAt ?: current.latestScan.startedAt)} · 磁盘 ${summary.usedPercent?.let { formatStorageDecimal(it) + "%" } ?: "未读取"} · ${formatStorageBytes(summary.totalBytes)} / ${summary.totalFiles} 个文件", "Last scan: ${storageTime(current.latestScan.completedAt ?: current.latestScan.startedAt)} · Disk ${summary.usedPercent?.let { formatStorageDecimal(it) + "%" } ?: "unavailable"} · ${formatStorageBytes(summary.totalBytes)} / ${summary.totalFiles} files"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NativeStorageMetric(label: String, value: String, color: Color, modifier: Modifier) { GlassCard(modifier = modifier, shape = HlovetUi.rowShape) { Column(Modifier.padding(8.dp)) { Text(label, color = HlovetUi.muted, fontSize = 9.sp); Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold) } } }

@Composable
private fun NativeStorageTabButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) { TextButton(onClick = onClick, modifier = modifier) { Text(label, color = if (selected) HlovetUi.accent else HlovetUi.muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) } }

/** 以 label/value/options 展示媒体筛选项；onChange 回传业务代码，modifier 保留父页面分配的宽度。 */
@Composable
private fun NativeStorageFilter(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier) {
    // 文字单行回显并在触发位置展开统一弹层，选中后关闭，不自行请求媒体列表。
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically) { Text(options.firstOrNull { it.first == value }?.second ?: label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Icon(Icons.Filled.Settings, contentDescription = nativePhrase("选择$label", "Select $label"), tint = HlovetUi.muted, modifier = Modifier.size(14.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) { options.forEach { option -> DropdownMenuItem(text = { Text(option.second, fontSize = 11.sp) }, onClick = { onChange(option.first); expanded = false }) } }
    }
}

@Composable
private fun NativeStorageIssueCard(issue: NativeStorageIssue, busy: String?, onPreview: () -> Unit, onDownload: () -> Unit, onRemoteRestore: () -> Unit, onReupload: () -> Unit, onUnrecoverable: () -> Unit, onHistory: () -> Unit, onTrash: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (issue.kind == "missing") Icons.Filled.Error else Icons.Filled.Warning, contentDescription = null, tint = if (issue.kind == "missing") HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(nativeStorageIssueKind(issue.kind), fontSize = 10.sp, color = HlovetUi.secondaryAccent, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(6.dp)); Text(issue.categoryLabel, color = HlovetUi.muted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text(storageSize(issue), color = HlovetUi.muted, fontSize = 9.sp) }
        Text(issue.storedName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(issue.sourceLabel, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotBlank(if (issue.uploadedBy.isNullOrBlank()) null else nativePhrase("上传：${issue.uploadedBy}", "Uploaded by: ${issue.uploadedBy}"), issue.fileUpdatedAt?.let(::storageTime)).joinToString(" · "), color = HlovetUi.muted, fontSize = 9.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
            if (issue.previewable) TextButton(onClick = onPreview, enabled = busy == null) { Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("预览", "Preview"), fontSize = 9.sp) }
            if (issue.kind == "orphan") TextButton(onClick = onDownload, enabled = busy == null) { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("下载", "Download"), fontSize = 9.sp) }
            if (issue.kind == "missing") { TextButton(onClick = onRemoteRestore, enabled = busy == null) { Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("远端恢复", "Remote restore"), fontSize = 9.sp) }; TextButton(onClick = onReupload, enabled = busy == null) { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("重新上传", "Re-upload"), fontSize = 9.sp) }; TextButton(onClick = onUnrecoverable, enabled = busy == null) { Text(nativePhrase("无法恢复", "Unrecoverable"), color = HlovetUi.secondaryAccent, fontSize = 9.sp) }; TextButton(onClick = onHistory, enabled = busy == null) { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(14.dp)); Text(nativePhrase("记录", "History"), fontSize = 9.sp) } }
            if (issue.canTrash) TextButton(onClick = onTrash, enabled = busy == null) { Icon(Icons.Filled.Delete, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(14.dp)); Text(nativePhrase("回收站", "Trash"), color = HlovetUi.secondaryAccent, fontSize = 9.sp) }
        }
    } }
}

@Composable
private fun NativeStorageTrashCard(item: NativeStorageTrashItem, busy: String?, onRestore: () -> Unit, onDelete: () -> Unit) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Column(Modifier.weight(1f)) { Text(item.originalStoredName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${item.categoryLabel} · ${formatStorageBytes(item.sizeBytes)}", color = HlovetUi.muted, fontSize = 9.sp); Text(nativePhrase("移入 ${storageTime(item.deletedAt)} · 自动清理 ${storageTime(item.purgeAfter)}", "Moved ${storageTime(item.deletedAt)} · Auto-purge ${storageTime(item.purgeAfter)}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2) }; TextButton(onClick = onRestore, enabled = busy == null) { Text(nativePhrase("恢复", "Restore"), fontSize = 9.sp) }; TextButton(onClick = onDelete, enabled = busy == null) { Text(nativePhrase("永久删除", "Delete permanently"), color = HlovetUi.secondaryAccent, fontSize = 9.sp) } } } }

@Composable
private fun NativeStorageConfigurationCard(current: NativeStorageConfiguration, enabled: Boolean, onChange: (NativeStorageConfiguration) -> Unit, onSave: () -> Unit) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Settings, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("扫描与清理策略", "Scan and cleanup policy"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }; Row(verticalAlignment = Alignment.CenterVertically) { HlovetCheckbox(checked = current.automaticScanEnabled, onCheckedChange = { onChange(current.copy(automaticScanEnabled = it)) }, enabled = enabled, modifier = Modifier.size(32.dp)); Text(nativePhrase("每日自动扫描", "Scan daily"), fontSize = 11.sp) }; OutlinedTextField(value = current.scanTime, onValueChange = { onChange(current.copy(scanTime = it)) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text(nativePhrase("执行时间", "Run time")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeStorageFieldColors()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) { OutlinedTextField(value = current.trashRetentionDays.toString(), onValueChange = { onChange(current.copy(trashRetentionDays = it.toIntOrNull() ?: current.trashRetentionDays)) }, enabled = enabled, modifier = Modifier.weight(1f), label = { Text(nativePhrase("保留天数", "Retention days")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeStorageFieldColors()); OutlinedTextField(value = current.warningThresholdPercent.toString(), onValueChange = { onChange(current.copy(warningThresholdPercent = it.toIntOrNull() ?: current.warningThresholdPercent)) }, enabled = enabled, modifier = Modifier.weight(1f), label = { Text(nativePhrase("预警阈值 %", "Warning threshold %")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeStorageFieldColors()) }; Text(nativePhrase("时区 ${current.timezone} · 最近扫描 ${current.lastScanAt?.let(::storageTime) ?: "暂无"}", "Timezone ${current.timezone} · Last scan ${current.lastScanAt?.let(::storageTime) ?: "none"}"), color = HlovetUi.muted, fontSize = 10.sp); TextButton(onClick = onSave, enabled = enabled) { Text(nativePhrase("保存策略", "Save policy"), fontSize = 11.sp) } } } }

@Composable
private fun NativeStoragePagination(page: Int, pageCount: Int, onPrevious: () -> Unit, onNext: () -> Unit, loading: Boolean) { if (pageCount > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onPrevious, enabled = page > 1 && !loading) { Text(nativePhrase("上一页", "Previous"), fontSize = 10.sp) }; Text("$page / $pageCount", color = HlovetUi.muted, fontSize = 10.sp); TextButton(onClick = onNext, enabled = page < pageCount && !loading) { Text(nativePhrase("下一页", "Next"), fontSize = 10.sp) } } }

@Composable
private fun NativeStorageMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)? = null) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isError) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Close"), fontSize = 10.sp) } } } } }

private fun nativeStorageIssueKind(value: String): String = mapOf("missing" to nativePhrase("文件缺失", "Missing file"), "orphan" to nativePhrase("孤立文件", "Orphan file"), "metadata_mismatch" to nativePhrase("大小不一致", "Size mismatch"))[value] ?: value
private fun nativeStorageRepairAction(value: String): String = mapOf("remote_restore" to nativePhrase("远端恢复", "Remote restore"), "reupload" to nativePhrase("重新上传", "Re-upload"), "confirm_unrecoverable" to nativePhrase("确认无法恢复", "Confirm unrecoverable"))[value] ?: value
private fun nativeStorageRepairStatus(value: String): String = mapOf("running" to nativePhrase("处理中", "Working"), "completed" to nativePhrase("已完成", "Completed"), "failed" to nativePhrase("失败", "Failed"))[value] ?: value
private fun storageSize(issue: NativeStorageIssue): String = if (issue.kind == "metadata_mismatch") nativePhrase("${formatStorageBytes(issue.expectedSizeBytes ?: 0)} / 实际 ${formatStorageBytes(issue.actualSizeBytes ?: 0)}", "${formatStorageBytes(issue.expectedSizeBytes ?: 0)} / actual ${formatStorageBytes(issue.actualSizeBytes ?: 0)}") else formatStorageBytes(issue.actualSizeBytes ?: issue.expectedSizeBytes ?: 0)
private fun formatStorageBytes(value: Long): String { if (value <= 0) return "0 B"; val units = arrayOf("B", "KB", "MB", "GB", "TB"); var amount = value.toDouble(); var index = 0; while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }; return "${if (amount >= 10 || index == 0) amount.toInt() else "%.1f".format(amount)} ${units[index]}" }
private fun formatStorageDecimal(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
private fun storageTime(value: String): String = value.replace("T", " ").replace(Regex("\\.\\d+Z$"), "").replace("Z", "").takeLast(19)
private fun listOfNotBlank(vararg values: String?): List<String> = values.filterNot { it.isNullOrBlank() }.map { it!! }

@Composable
private fun nativeStorageFieldColors() = HlovetUi.fieldColors()
