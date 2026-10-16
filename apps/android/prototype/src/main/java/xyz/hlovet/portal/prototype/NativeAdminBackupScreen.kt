@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NativeBackupPendingSave(val fileName: String, val mimeType: String, val bytes: ByteArray)

private data class NativeBackupSnapshot(
    val configuration: NativeBackupConfiguration,
    val databaseBackups: List<NativeDatabaseBackup>,
    val mediaJobs: List<NativeMediaBackupJob>,
)

@Composable
internal fun NativeAdminBackupScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    key(session?.accessToken, session?.isSuperAdmin) {
        NativeAdminBackupContent(session, onBack)
    }
}

@Composable
private fun NativeAdminBackupContent(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configuration by remember(token) { mutableStateOf<NativeBackupConfiguration?>(null) }
    var form by remember(token) { mutableStateOf<NativeBackupConfigurationForm?>(null) }
    var backups by remember(token) { mutableStateOf<List<NativeDatabaseBackup>>(emptyList()) }
    var mediaJobs by remember(token) { mutableStateOf<List<NativeMediaBackupJob>>(emptyList()) }
    var mediaFiles by remember(token) { mutableStateOf<List<NativeMediaBackupFile>>(emptyList()) }
    var mediaFileTotal by remember(token) { mutableIntStateOf(0) }
    var mediaFilePage by remember(token) { mutableIntStateOf(1) }
    var mediaFilePageCount by remember(token) { mutableIntStateOf(0) }
    var mediaFileCategory by remember(token) { mutableStateOf("") }
    var mediaFileQuery by remember(token) { mutableStateOf("") }
    var mediaFileSearchText by remember(token) { mutableStateOf("") }
    var mediaFilesLoading by remember(token) { mutableStateOf(false) }
    var mediaFilesError by remember(token) { mutableStateOf<String?>(null) }
    var mediaFilesReloadKey by remember(token) { mutableIntStateOf(0) }
    var mediaJobsError by remember(token) { mutableStateOf<String?>(null) }
    var policyError by remember(token) { mutableStateOf<String?>(null) }
    var policyNotice by remember(token) { mutableStateOf<String?>(null) }
    var policyFeedbackProvider by remember(token) { mutableStateOf<String?>(null) }
    var feedbackKey by remember(token) { mutableStateOf<String?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf<String?>(null) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var selectedBackupNames by remember(token) { mutableStateOf(emptySet<String>()) }
    var restoreTarget by remember(token) { mutableStateOf<String?>(null) }
    var restorePreflight by remember(token) { mutableStateOf<NativeBackupRestorePreflight?>(null) }
    var restoreConfirmation by remember(token) { mutableStateOf("") }
    var selectedMediaJob by remember(token) { mutableStateOf<NativeMediaBackupJobDetail?>(null) }
    var selectedMediaFile by remember(token) { mutableStateOf<NativeMediaBackupFile?>(null) }
    var mediaFileRestoreConfirmation by remember(token) { mutableStateOf("") }
    var pendingSave by remember(token) { mutableStateOf<NativeBackupPendingSave?>(null) }
    var pendingDeletion by remember(token) { mutableStateOf<List<String>?>(null) }
    var pendingImport by remember(token) { mutableStateOf<NativeConfigurationImportPreview?>(null) }

    fun saveFile(uri: Uri?) {
        val pending = pendingSave
        pendingSave = null
        if (uri == null || pending == null) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pending.bytes) }
                        ?: throw IllegalStateException(nativePhrase("无法打开系统保存位置。", "Could not open the selected save location."))
                }
            }.onSuccess { notice = nativePhrase("文件已保存到所选位置。", "File saved to the selected location.") }.onFailure { error = it.message ?: nativePhrase("文件保存失败。", "Could not save the file.") }
        }
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), ::saveFile)
    val saveConfiguration = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::saveFile)
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || token.isNullOrBlank() || busy != null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "import:preview"
            error = null
            notice = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val maxBytes = 5 * 1024 * 1024
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= maxBytes) { nativePhrase("配置包超过 5 MB，请检查所选文件。", "The configuration bundle exceeds 5 MB.") }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalStateException(nativePhrase("无法读取配置文件。", "Could not read the configuration file."))
                    previewNativeConfigurationImport(org.json.JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")))
                }
            }.onSuccess { pendingImport = it }.onFailure { error = it.message ?: nativePhrase("配置包读取失败。", "Could not read the configuration bundle.") }
            busy = null
        }
    }

    fun reload() { reloadKey++ }

    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            configuration = null
            form = null
            backups = emptyList()
            mediaJobs = emptyList()
            mediaFiles = emptyList()
            mediaFileTotal = 0
            mediaFilePageCount = 0
            restoreTarget = null; restorePreflight = null; selectedMediaJob = null; selectedMediaFile = null
            pendingDeletion = null; pendingImport = null; pendingSave = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeBackupSnapshot(
                    NativeAdminBackupApi.getConfiguration(token),
                    NativeAdminBackupApi.listDatabaseBackups(token),
                    NativeAdminBackupApi.listMediaBackupJobs(token).items,
                )
            }
        }.onSuccess { snapshot ->
            val previousSavedForm = configuration?.toForm()
            configuration = snapshot.configuration
            if (form == null || form == previousSavedForm) form = snapshot.configuration.toForm()
            backups = snapshot.databaseBackups
            selectedBackupNames = selectedBackupNames.intersect(snapshot.databaseBackups.map { it.name }.toSet())
            mediaJobs = snapshot.mediaJobs
        }.onFailure { error = it.message ?: nativePhrase("备份管理读取失败。", "Could not load backup management data.") }
        loading = false
    }

    LaunchedEffect(token, canAccess, mediaFilePage, mediaFileCategory, mediaFileQuery, reloadKey, mediaFilesReloadKey) {
        if (token.isNullOrBlank() || !canAccess) { mediaFiles = emptyList(); mediaFilesError = null; mediaFilesLoading = false; return@LaunchedEffect }
        mediaFilesLoading = true
        mediaFilesError = null
        try {
            val page = withContext(Dispatchers.IO) { NativeAdminBackupApi.listMediaBackupFiles(token, mediaFilePage, category = mediaFileCategory, query = mediaFileQuery) }
            mediaFiles = page.items
            mediaFileTotal = page.total
            mediaFilePageCount = page.pageCount
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { mediaFilesError = exception.message ?: nativePhrase("媒体目录读取失败，请重试。", "Could not load the media catalog. Please retry.") }
        finally { mediaFilesLoading = false }
    }

    val mediaPollingRequired = mediaJobs.any { it.status in listOf("pending", "running") } || selectedMediaJob?.job?.status in listOf("pending", "running")
    LaunchedEffect(token, canAccess, mediaPollingRequired, selectedMediaJob?.job?.id) {
        if (token.isNullOrBlank() || !canAccess || !mediaPollingRequired) return@LaunchedEffect
        val detailId = selectedMediaJob?.job?.id
        while (true) {
            kotlinx.coroutines.delay(2_000)
            try {
                val next = withContext(Dispatchers.IO) { NativeAdminBackupApi.listMediaBackupJobs(token).items to detailId?.let { NativeAdminBackupApi.getMediaBackupJob(token, it) } }
                mediaJobsError = null
                mediaJobs = next.first
                if (selectedMediaJob?.job?.id == detailId) next.second?.let { selectedMediaJob = it }
                if (next.first.none { it.status in listOf("pending", "running") } && next.second?.job?.status !in listOf("pending", "running")) {
                    mediaFilesReloadKey++
                    break
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                mediaJobsError = exception.message ?: nativePhrase("媒体备份进度读取失败，将继续重试。", "Could not refresh media backup progress. Retrying.")
                if (exception is NativeAdminBackupHttpException && exception.statusCode in listOf(401, 403)) break
            }
        }
    }

    fun launchSave(pending: NativeBackupPendingSave) {
        pendingSave = pending
        if (pending.mimeType == "application/json") saveConfiguration.launch(pending.fileName)
        else saveDocument.launch(pending.fileName)
    }

    fun runAction(key: String, action: suspend () -> Unit, success: String, refresh: Boolean = true, onSuccess: () -> Unit = {}) {
        if (!canAccess || token.isNullOrBlank() || busy != null || loading) return
        busy = key
        feedbackKey = key
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess { onSuccess(); notice = success; if (refresh) reloadKey++ }
                .onFailure { error = it.message ?: nativePhrase("操作失败。", "Action failed.") }
            busy = null
        }
    }

    fun savePolicy() {
        val currentForm = form ?: return
        if (token.isNullOrBlank() || !canAccess || loading || busy != null) return
        busy = "configuration"
        policyFeedbackProvider = null
        policyError = null; policyNotice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.updateConfiguration(token, currentForm) } }
                .onSuccess { saved -> configuration = saved; form = saved.toForm(); policyNotice = nativePhrase("备份策略已保存。", "Backup policy saved.") }
                .onFailure { policyError = it.message ?: nativePhrase("备份策略保存失败。", "Could not save the backup policy.") }
            busy = null
        }
    }

    fun testProvider(provider: String) {
        val current = configuration ?: return
        if (token.isNullOrBlank() || !canAccess || loading || busy != null) return
        policyFeedbackProvider = provider
        policyError = null; policyNotice = null
        if (form != current.toForm()) { policyError = nativePhrase("请先保存备份策略，再测试已保存的远端连接。", "Save the backup policy before testing the saved remote connection."); return }
        busy = "provider:$provider"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.testProvider(token, provider) } }
                .onSuccess { policyNotice = if (provider == "oss") nativePhrase("阿里云 OSS 连接正常。", "Alibaba OSS connection is working.") else nativePhrase("Cloudflare R2 连接正常。", "Cloudflare R2 connection is working.") }
                .onFailure { policyError = it.message ?: nativePhrase("远端连接测试失败。", "Remote connection test failed.") }
            busy = null
        }
    }

    fun createBackup() {
        if (token.isNullOrBlank() || !canAccess || busy != null || loading) return
        busy = "create"
        feedbackKey = "create"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.createDatabaseBackup(token) } }
                .onSuccess { backup ->
                    backups = listOf(backup) + backups.filterNot { it.name == backup.name }
                    notice = nativePhrase("数据库备份已生成。", "Database backup created.") + (backup.warning?.let { "\n$it" } ?: "")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("数据库备份失败。", "Database backup failed.") }
            busy = null
        }
    }

    fun startMediaBackup() {
        if (token.isNullOrBlank() || !canAccess || busy != null || loading) return
        busy = "media:create"; feedbackKey = "media:create"; error = null; notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.startMediaBackup(token) } }
                .onSuccess { job -> mediaJobs = listOf(job) + mediaJobs.filterNot { it.id == job.id }; notice = nativePhrase("媒体备份任务已开始。", "Media backup job started.") }
                .onFailure { error = it.message ?: nativePhrase("媒体备份启动失败。", "Could not start the media backup.") }
            busy = null
        }
    }

    fun openDelete(names: List<String>) { if (busy == null) { error = null; notice = null; pendingDeletion = names } }

    fun deleteBackups(names: List<String>) {
        if (names.isEmpty() || token.isNullOrBlank() || busy != null) return
        runAction("delete", { if (names.size == 1) NativeAdminBackupApi.deleteDatabaseBackup(token, names.first()) else NativeAdminBackupApi.deleteDatabaseBackups(token, names) }, nativePhrase("已删除 ${names.size} 份数据库备份。", "Deleted ${names.size} database backups."), onSuccess = { pendingDeletion = null; backups = backups.filterNot { it.name in names }; selectedBackupNames = selectedBackupNames - names.toSet() })
    }

    fun importConfiguration() {
        val preview = pendingImport ?: return
        if (token.isNullOrBlank() || !canAccess || busy != null) return
        busy = "import"
        feedbackKey = "import"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.importConfigurationBundle(token, preview.bundle) } }
                .onSuccess { result ->
                    pendingImport = null
                    form = null
                    notice = nativePhrase("配置包导入完成：能力 ${result.optInt("importedCapabilities")} 项，分类标签 ${result.optInt("importedTaxonomies")} 项。", "Configuration import complete: ${result.optInt("importedCapabilities")} capabilities and ${result.optInt("importedTaxonomies")} taxonomies.")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("配置包导入失败。", "Configuration import failed.") }
            busy = null
        }
    }

    fun verifyBackup(name: String) {
        if (token.isNullOrBlank() || busy != null) return
        busy = "verify:$name"
        feedbackKey = "verify:$name"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.verifyDatabaseBackup(token, name) } }
                .onSuccess { verified ->
                    backups = backups.map { if (it.name == name) verified else it }
                    if (verified.verificationStatus == "failed") error = verified.verificationError ?: nativePhrase("备份校验未通过，不能用于恢复。", "Backup verification failed; it cannot be restored.")
                    else notice = nativePhrase("备份校验完成：${backupVerificationText(verified.verificationStatus)}。", "Backup verification complete: ${backupVerificationText(verified.verificationStatus)}.")
                }
                .onFailure { error = it.message ?: nativePhrase("备份校验失败。", "Backup verification failed.") }
            busy = null
        }
    }

    fun openRestore(name: String) {
        if (token.isNullOrBlank() || busy != null) return
        busy = "preflight:$name"
        feedbackKey = "preflight:$name"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.preflightRestore(token, name) } }
                .onSuccess { restoreTarget = name; restorePreflight = it; restoreConfirmation = "" }
                .onFailure { error = it.message ?: nativePhrase("恢复前检查失败。", "Restore preflight failed.") }
            busy = null
        }
    }

    fun restoreBackup() {
        val name = restoreTarget ?: return
        if (token.isNullOrBlank() || restorePreflight?.canRestore != true || restoreConfirmation != name || busy != null) return
        busy = "restore:$name"
        feedbackKey = "restore:$name"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.restoreDatabaseBackup(token, name) } }
                .onSuccess { result ->
                    restoreTarget = null; restorePreflight = null; restoreConfirmation = ""
                    val scanStarted = !result.isNull("storageScanId") && result.optInt("storageScanId") > 0
                    notice = nativePhrase("数据库已恢复。", "Database restored.") + (if (scanStarted) nativePhrase("恢复后的存储检查已启动。", "Post-restore storage scan started.") else "") +
                        ((result.opt("warning") as? String)?.takeIf(String::isNotBlank)?.let { "\n$it" } ?: "")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("数据库恢复失败。", "Database restore failed.") }
            busy = null
        }
    }

    fun loadMediaDetail(id: Int) {
        if (token.isNullOrBlank() || busy != null) return
        busy = "media:detail:$id"
        feedbackKey = "media:detail:$id"
        error = null; notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.getMediaBackupJob(token, id) } }
                .onSuccess { selectedMediaJob = it }
                .onFailure { error = it.message ?: nativePhrase("媒体备份详情读取失败。", "Could not load media backup details.") }
            busy = null
        }
    }

    fun applyMediaFileSearch() {
        mediaFileQuery = mediaFileSearchText.trim()
        mediaFilePage = 1
        mediaFilesReloadKey++
    }

    fun selectMediaFileCategory(category: String) {
        mediaFileCategory = category
        mediaFilePage = 1
        mediaFilesReloadKey++
    }

    fun openMediaFileRestore(file: NativeMediaBackupFile) {
        if (busy != null) return
        selectedMediaFile = file
        mediaFileRestoreConfirmation = ""
        error = null; notice = null
    }

    fun restoreMediaFile() {
        val file = selectedMediaFile ?: return
        if (token.isNullOrBlank() || busy != null || mediaFileRestoreConfirmation.trim() != file.storedName) return
        busy = "media:restore:${file.id}"
        feedbackKey = "media:restore:${file.id}"
        error = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.restoreMediaBackupFile(token, file.id, mediaFileRestoreConfirmation) } }
                .onSuccess {
                    selectedMediaFile = null
                    mediaFileRestoreConfirmation = ""
                    notice = nativePhrase("媒体文件已恢复。", "Media file restored.")
                    reloadKey++
                }
                .onFailure { error = it.message ?: nativePhrase("媒体文件恢复失败。", "Media file restore failed.") }
            busy = null
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("backup-list"), contentPadding = PaddingValues(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            TopAppBar(
                title = { Column { Text(nativePhrase("备份管理", "Backups"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("策略、数据库、媒体和配置迁移", "Policies, database, media, and configuration migration"), color = HlovetUi.muted, fontSize = 10.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::reload, enabled = !loading && busy == null) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (token.isNullOrBlank()) item(key = "access") { NativeBackupMessage(nativePhrase("登录后查看备份管理。", "Sign in to manage backups."), false) }
        else if (!canAccess) item(key = "access") { NativeBackupMessage(nativePhrase("备份管理仅超级管理员可访问。", "Only super administrators can manage backups."), true) }
        else if (loading && configuration == null) item(key = "loading") { NativeBackupMessage(nativePhrase("正在读取备份状态…", "Loading backup status…"), false) }
        else {
            if (!error.isNullOrBlank()) item(key = "error") { NativeBackupMessage(error!!, true) { error = null } }
            if (!notice.isNullOrBlank()) item(key = "notice") { NativeBackupMessage(notice!!, false) { notice = null } }
            configuration?.let { current ->
                item(key = "policy") {
                    NativeBackupConfigurationCard(
                        current, form ?: current.toForm(), busy != null || loading, { form = it }, ::savePolicy, ::testProvider, policyError, policyNotice, policyFeedbackProvider,
                    )
                }
            }
            item(key = "database") {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("backup-database-card"), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Archive, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("数据库备份", "Database backups"), fontSize = 15.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick = ::createBackup, enabled = busy == null && !loading) { Text(if (busy == "create") nativePhrase("生成中…", "Creating…") else nativePhrase("立即备份", "Back up now"), fontSize = 10.sp) } }
                        if (feedbackKey?.let { it == "create" || it == "delete" || it.startsWith("verify:") || it.startsWith("restore:") || it.startsWith("preflight:") } == true) HlovetFormFeedback(error, notice)
                        if (backups.isEmpty()) Text(nativePhrase("暂无可见数据库备份。", "No visible database backups."), color = HlovetUi.muted, fontSize = 11.sp)
                        else {
                            Row(verticalAlignment = Alignment.CenterVertically) { val allSelected = backups.isNotEmpty() && selectedBackupNames.containsAll(backups.map { it.name }); HlovetCheckbox(checked = allSelected, onCheckedChange = { selectedBackupNames = if (it) backups.map { backup -> backup.name }.toSet() else emptySet() }, enabled = busy == null, modifier = Modifier.size(30.dp).testTag("backup-select-all")); Text(if (selectedBackupNames.isEmpty()) nativePhrase("全选", "Select all") else nativePhrase("已选 ${selectedBackupNames.size} 份", "${selectedBackupNames.size} selected"), color = HlovetUi.muted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = { openDelete(selectedBackupNames.toList()) }, enabled = selectedBackupNames.isNotEmpty() && busy == null) { Text(nativePhrase("批量删除", "Delete selected"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) } }
                            backups.forEach { backup -> NativeDatabaseBackupRow(backup, selectedBackupNames.contains(backup.name), busy, { selectedBackupNames = if (selectedBackupNames.contains(backup.name)) selectedBackupNames - backup.name else selectedBackupNames + backup.name }, { scope.launch { busy = "download:${backup.name}"; runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.downloadDatabaseBackup(token, backup.name) } }.onSuccess { launchSave(NativeBackupPendingSave(backup.name, it.contentType, it.bytes)); notice = nativePhrase("请选择备份保存位置。", "Choose where to save the backup.") }.onFailure { error = it.message ?: nativePhrase("备份下载失败。", "Backup download failed.") }; busy = null } }, { verifyBackup(backup.name) }, { openRestore(backup.name) }, { openDelete(listOf(backup.name)) }) }
                        }
                    }
                }
            }
            item(key = "media-jobs") { NativeMediaBackupCard(mediaJobs, busy, ::startMediaBackup, ::loadMediaDetail, mediaJobsError ?: error.takeIf { feedbackKey?.startsWith("media:") == true }, notice.takeIf { feedbackKey?.startsWith("media:") == true }) }
            item(key = "media-files") {
                NativeMediaBackupFilesCard(
                    files = mediaFiles,
                    total = mediaFileTotal,
                    page = mediaFilePage,
                    pageCount = mediaFilePageCount,
                    category = mediaFileCategory,
                    searchText = mediaFileSearchText,
                    busy = busy ?: if (mediaFilesLoading) "media:files" else null,
                    loading = mediaFilesLoading, error = mediaFilesError, onRetry = { mediaFilesReloadKey++ },
                    onSearchTextChange = { mediaFileSearchText = it },
                    onSearch = ::applyMediaFileSearch,
                    onCategoryChange = ::selectMediaFileCategory,
                    onPreviousPage = { if (mediaFilePage > 1) mediaFilePage-- },
                    onNextPage = { if (mediaFilePageCount > mediaFilePage) mediaFilePage++ },
                    onRestore = ::openMediaFileRestore,
                )
            }
            item(key = "migration") {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Save, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("配置迁移", "Configuration migration"), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Text(nativePhrase("导出站点、安全、AI、备份策略等配置；不会导出用户和文章。导入前请确认 BACKUP_ENCRYPTION_KEY 已安全配置。", "Export site, security, AI, and backup policies; users and articles are excluded. Make sure BACKUP_ENCRYPTION_KEY is configured before importing."), color = HlovetUi.muted, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { busy = "export"; runCatching { withContext(Dispatchers.IO) { NativeAdminBackupApi.exportConfigurationBundle(token) } }.onSuccess { launchSave(NativeBackupPendingSave("hlovet-configuration-${System.currentTimeMillis()}.json", "application/json", it.toString(2).toByteArray(Charsets.UTF_8))); notice = nativePhrase("请选择配置包保存位置。", "Choose where to save the configuration bundle.") }.onFailure { error = it.message ?: nativePhrase("配置包导出失败。", "Configuration export failed.") }; busy = null } }, enabled = busy == null) { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("导出配置包", "Export bundle"), fontSize = 10.sp) }
                            TextButton(onClick = { importDocument.launch(arrayOf("application/json", "text/plain")) }, enabled = busy == null) { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("导入配置包", "Import bundle"), fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }

    pendingDeletion?.let { names ->
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) pendingDeletion = null },
            title = { Text(nativePhrase("删除 ${names.size} 份备份？", "Delete ${names.size} backups?")) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(nativePhrase("数据库备份及其本地媒体快照会永久删除，当前网站数据不受影响。", "Database backups and their local media snapshots will be permanently deleted. Current site data is not affected."), fontSize = 12.sp)
                    names.forEach { Text(it, fontSize = 11.sp) }
                    HlovetFormFeedback(error)
                }
            },
            confirmButton = { TextButton(onClick = { deleteBackups(names) }, enabled = busy == null) { Text(nativePhrase("确认删除", "Delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    pendingImport?.let { preview ->
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) pendingImport = null },
            title = { Text(nativePhrase("导入系统配置？", "Import system configuration?")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (preview.generatedAt.isNotBlank()) Text(nativePhrase("导出时间：${formatBackupTime(preview.generatedAt)}", "Exported: ${formatBackupTime(preview.generatedAt)}"), color = HlovetUi.muted, fontSize = 10.sp)
                    Text(nativePhrase("以下设置会按配置包覆盖，分类和标签按名称合并：", "The following settings will be overwritten; categories and tags are merged by name:"), fontSize = 12.sp)
                    preview.sections.forEach { Text("• $it", fontSize = 11.sp) }
                    Text(nativePhrase("用户、文章、聊天和知识库内容不会导入。加密凭据需要保持原 BACKUP_ENCRYPTION_KEY，域名和回调地址需适配当前服务器。", "Users, articles, chats, and knowledge-base content are not imported. Keep the original BACKUP_ENCRYPTION_KEY and adapt domains and callback URLs to this server."), color = HlovetUi.secondaryAccent, fontSize = 11.sp)
                    HlovetFormFeedback(error)
                }
            },
            confirmButton = { TextButton(onClick = ::importConfiguration, enabled = busy == null) { Text(if (busy == "import") nativePhrase("导入中…", "Importing…") else nativePhrase("确认导入", "Import")) } },
            dismissButton = { TextButton(onClick = { pendingImport = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    restoreTarget?.let { name ->
        val preflight = restorePreflight
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) { restoreTarget = null; restorePreflight = null } },
            title = { Text(nativePhrase("恢复数据库备份", "Restore database backup")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(nativePhrase("恢复会先生成安全备份，并可能短暂中断服务。", "A safety backup will be created first; service may be briefly interrupted."), fontSize = 12.sp)
                    preflight?.warnings?.forEach { warning -> Text("• $warning", color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
                    if (preflight?.canRestore == false) HlovetFormFeedback(nativePhrase("恢复前检查未通过，暂不能恢复。请关闭弹窗并检查备份校验状态和服务器配置。", "Restore preflight did not pass. Close this dialog and check backup verification and server configuration."))
                    OutlinedTextField(value = restoreConfirmation, onValueChange = { restoreConfirmation = it }, enabled = preflight?.canRestore == true && busy == null, modifier = Modifier.fillMaxWidth().testTag("backup-restore-confirmation"), singleLine = true, label = { Text(nativePhrase("输入备份文件名确认", "Enter the backup filename to confirm")) }, placeholder = { Text(name, fontSize = 10.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp), colors = nativeBackupFieldColors())
                    HlovetFormFeedback(error)
                }
            },
            confirmButton = { TextButton(onClick = ::restoreBackup, enabled = preflight?.canRestore == true && restoreConfirmation == name && busy == null) { Text(nativePhrase("确认恢复", "Restore"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { restoreTarget = null; restorePreflight = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    selectedMediaFile?.let { file ->
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) { selectedMediaFile = null; mediaFileRestoreConfirmation = "" } },
            title = { Text(nativePhrase("恢复媒体文件", "Restore media file")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(file.storedName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(nativePhrase("来源：${file.sourceLabel.ifBlank { file.sourceType }} · 分类：${mediaBackupCategory(file.category)}", "Source: ${file.sourceLabel.ifBlank { file.sourceType }} · Category: ${mediaBackupCategory(file.category)}"), color = HlovetUi.muted, fontSize = 10.sp)
                    Text(nativePhrase("服务器会自动选择可用的远端备份清单。请输入完整存储文件名确认恢复。", "The server will choose an available remote backup manifest. Enter the complete stored filename to confirm."), color = HlovetUi.muted, fontSize = 11.sp)
                    OutlinedTextField(
                        value = mediaFileRestoreConfirmation,
                        onValueChange = { mediaFileRestoreConfirmation = it },
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth().testTag("backup-media-restore-confirmation"),
                        singleLine = true,
                        label = { Text(nativePhrase("输入存储文件名", "Enter stored filename"), fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        colors = nativeBackupFieldColors(),
                    )
                    HlovetFormFeedback(error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::restoreMediaFile,
                    enabled = mediaFileRestoreConfirmation.trim() == file.storedName && busy == null,
                ) { Text(if (busy == "media:restore:${file.id}") nativePhrase("恢复中…", "Restoring…") else nativePhrase("确认恢复", "Restore"), color = HlovetUi.secondaryAccent) }
            },
            dismissButton = { TextButton(onClick = { selectedMediaFile = null; mediaFileRestoreConfirmation = "" }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    selectedMediaJob?.let { detail ->
        HlovetAlertDialog(
            onDismissRequest = { selectedMediaJob = null },
            title = { Text(nativePhrase("媒体备份任务 #${detail.job.id}", "Media backup job #${detail.job.id}")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { HlovetFormFeedback(mediaJobsError) }
                    item {
                        Text(nativePhrase("状态：${mediaBackupStatus(detail.job.status)} · ${detail.job.processedFiles}/${detail.job.totalFiles} 个文件", "Status: ${mediaBackupStatus(detail.job.status)} · ${detail.job.processedFiles}/${detail.job.totalFiles} files"), fontSize = 11.sp)
                        Text(nativePhrase("上传 ${detail.job.uploadedFiles} · 复用 ${detail.job.reusedFiles} · 跳过 ${detail.job.skippedFiles} · 失败 ${detail.job.failedFiles}", "Uploaded ${detail.job.uploadedFiles} · Reused ${detail.job.reusedFiles} · Skipped ${detail.job.skippedFiles} · Failed ${detail.job.failedFiles}"), color = HlovetUi.muted, fontSize = 10.sp)
                        Text(nativePhrase("数据量：${formatBackupBytes(detail.job.uploadedBytes)} / ${formatBackupBytes(detail.job.totalBytes)}", "Data: ${formatBackupBytes(detail.job.uploadedBytes)} / ${formatBackupBytes(detail.job.totalBytes)}"), color = HlovetUi.muted, fontSize = 10.sp)
                        detail.job.error?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                    }
                    item { Text(nativePhrase("文件清单（${detail.manifests.size}）", "File manifest (${detail.manifests.size})"), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) }
                    if (detail.manifests.isEmpty()) item { Text(nativePhrase("暂无文件清单。", "No file manifest."), color = HlovetUi.muted, fontSize = 10.sp) }
                    items(detail.manifests) { manifest ->
                        Column {
                            Text(manifest.storedName, fontSize = 11.sp)
                            Text("${manifest.provider.uppercase()} · ${manifest.status} · ${formatBackupBytes(manifest.sizeBytes)}", color = HlovetUi.muted, fontSize = 10.sp)
                            manifest.error?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                        }
                    }
                    item { Text(nativePhrase("任务日志（${detail.logs.size}）", "Job log (${detail.logs.size})"), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) }
                    if (detail.logs.isEmpty()) item { Text(nativePhrase("暂无任务日志。", "No job log."), color = HlovetUi.muted, fontSize = 10.sp) }
                    items(detail.logs) { log ->
                        Text("${formatBackupTime(log.createdAt)} · ${log.message}", color = if (log.level == "error") HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedMediaJob = null }) { Text(nativePhrase("关闭", "Close")) } },
        )
    }
}

@Composable
private fun NativeBackupConfigurationCard(current: NativeBackupConfiguration, form: NativeBackupConfigurationForm, busy: Boolean, onChange: (NativeBackupConfigurationForm) -> Unit, onSave: () -> Unit, onTestProvider: (String) -> Unit, error: String?, notice: String?, feedbackProvider: String?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("backup-policy-card"), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Settings, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("备份策略", "Backup policy"), fontSize = 15.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(if (current.encryptionConfigured) nativePhrase("加密已配置", "Encryption configured") else nativePhrase("未配置加密", "Encryption not configured"), color = if (current.encryptionConfigured) HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 9.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("启用自动备份", "Enable automatic backups"), fontSize = 11.sp, modifier = Modifier.weight(1f)); HlovetSwitch(checked = form.automaticEnabled, onCheckedChange = { onChange(form.copy(automaticEnabled = it)) }, enabled = !busy) }
            NativeBackupField(nativePhrase("执行时间", "Run time"), form.scheduleTime, !busy, Modifier.fillMaxWidth()) { onChange(form.copy(scheduleTime = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeBackupField(nativePhrase("本地保留天数", "Local retention days"), form.localRetentionDays, !busy, Modifier.weight(1f), KeyboardType.Number) { onChange(form.copy(localRetentionDays = it.filter(Char::isDigit))) }; NativeBackupField(nativePhrase("远端保留天数", "Remote retention days"), form.remoteRetentionDays, !busy, Modifier.weight(1f), KeyboardType.Number) { onChange(form.copy(remoteRetentionDays = it.filter(Char::isDigit))) } }
            Text(nativePhrase("时区 ${current.timezone} · 下次执行 ${current.nextRunAt?.let(::formatBackupTime) ?: "暂无"}", "Timezone ${current.timezone} · Next run ${current.nextRunAt?.let(::formatBackupTime) ?: "Not scheduled"}"), color = HlovetUi.muted, fontSize = 10.sp)
            NativeBackupProviderCard(nativePhrase("阿里云 OSS", "Alibaba Cloud OSS"), "oss", form.ossEnabled, form.ossRegion, form.ossEndpoint, form.ossBucket, form.ossPrefix, current.oss, busy, { onChange(form.copy(ossEnabled = it)) }, { onChange(form.copy(ossRegion = it)) }, { onChange(form.copy(ossEndpoint = it)) }, { onChange(form.copy(ossBucket = it)) }, { onChange(form.copy(ossPrefix = it)) }, { onChange(form.copy(ossAccessKeyId = it)) }, { onChange(form.copy(ossAccessKeySecret = it)) }, form.ossAccessKeyId, form.ossAccessKeySecret, form.clearOssCredentials, { onChange(form.copy(clearOssCredentials = it)) }, { onTestProvider("oss") }, error = error.takeIf { feedbackProvider == "oss" }, notice = notice.takeIf { feedbackProvider == "oss" })
            NativeBackupProviderCard("Cloudflare R2", "r2", form.r2Enabled, "", "", form.r2Bucket, form.r2Prefix, current.r2, busy, { onChange(form.copy(r2Enabled = it)) }, { onChange(form.copy(r2AccountId = it)) }, { }, { onChange(form.copy(r2Bucket = it)) }, { onChange(form.copy(r2Prefix = it)) }, { onChange(form.copy(r2AccessKeyId = it)) }, { onChange(form.copy(r2SecretAccessKey = it)) }, form.r2AccessKeyId, form.r2SecretAccessKey, form.clearR2Credentials, { onChange(form.copy(clearR2Credentials = it)) }, { onTestProvider("r2") }, r2AccountId = form.r2AccountId, error = error.takeIf { feedbackProvider == "r2" }, notice = notice.takeIf { feedbackProvider == "r2" })
            if (feedbackProvider == null) HlovetFormFeedback(error, notice)
            TextButton(onClick = onSave, enabled = !busy) { Text(nativePhrase("保存备份策略", "Save backup policy"), fontSize = 10.sp) }
        }
    }
}

@Composable
private fun NativeBackupProviderCard(title: String, provider: String, enabled: Boolean, firstValue: String, endpoint: String, bucket: String, prefix: String, current: NativeBackupProvider, busy: Boolean, onEnabled: (Boolean) -> Unit, onFirstChange: (String) -> Unit, onEndpointChange: (String) -> Unit, onBucketChange: (String) -> Unit, onPrefixChange: (String) -> Unit, onAccessChange: (String) -> Unit, onSecretChange: (String) -> Unit, access: String, secret: String, clearCredentials: Boolean, onClearCredentials: (Boolean) -> Unit, onTest: () -> Unit, r2AccountId: String = "", error: String? = null, notice: String? = null) {
    Surface(modifier = Modifier.fillMaxWidth().testTag("backup-provider-$provider"), shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Cloud, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); HlovetSwitch(checked = enabled, onCheckedChange = onEnabled, enabled = !busy) }
            if (provider == "oss") { NativeBackupField("Region", firstValue, !busy, Modifier.fillMaxWidth()) { onFirstChange(it) }; NativeBackupField(nativePhrase("Endpoint（可选）", "Endpoint (optional)"), endpoint, !busy, Modifier.fillMaxWidth()) { onEndpointChange(it) } } else NativeBackupField("Account ID", r2AccountId, !busy, Modifier.fillMaxWidth()) { onFirstChange(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) { NativeBackupField("Bucket", bucket, !busy, Modifier.weight(1f)) { onBucketChange(it) }; NativeBackupField("Prefix", prefix, !busy, Modifier.weight(1f)) { onPrefixChange(it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) { NativeBackupField("Access key", access, !busy, Modifier.weight(1f)) { onAccessChange(it) }; NativeBackupField("Secret key", secret, !busy, Modifier.weight(1f), visual = PasswordVisualTransformation()) { onSecretChange(it) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(if (current.hasAccessKeyId || current.hasSecretAccessKey) nativePhrase("已有凭据，留空表示保留", "Credentials configured; blank keeps them") else nativePhrase("尚未配置凭据", "No credentials configured"), color = HlovetUi.muted, fontSize = 9.sp, modifier = Modifier.weight(1f)); HlovetCheckbox(checked = clearCredentials, onCheckedChange = onClearCredentials, enabled = !busy, modifier = Modifier.size(29.dp)); Text(nativePhrase("清除", "Clear"), color = HlovetUi.secondaryAccent, fontSize = 9.sp); TextButton(onClick = onTest, enabled = !busy) { Text(nativePhrase("测试连接", "Test connection"), fontSize = 9.sp) } }
            HlovetFormFeedback(error, notice)
        }
    }
}

@Composable
private fun NativeDatabaseBackupRow(backup: NativeDatabaseBackup, selected: Boolean, busy: String?, onSelect: () -> Unit, onDownload: () -> Unit, onVerify: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("backup-database-${backup.name}"), verticalAlignment = Alignment.CenterVertically) {
        HlovetCheckbox(checked = selected, onCheckedChange = { onSelect() }, enabled = busy == null, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(start = 2.dp)) { Text(backup.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatBackupBytes(backup.sizeBytes + (backup.mediaSnapshotSizeBytes ?: 0))} · ${backupVerificationText(backup.verificationStatus)} · ${formatBackupTime(backup.updatedAt)}", color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        IconButton(onClick = onDownload, enabled = busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Download, contentDescription = nativePhrase("下载", "Download"), tint = HlovetUi.accent, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onVerify, enabled = busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Shield, contentDescription = nativePhrase("校验", "Verify"), tint = HlovetUi.accent, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onRestore, enabled = busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Restore, contentDescription = nativePhrase("恢复", "Restore"), tint = HlovetUi.accent, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onDelete, enabled = busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除", "Delete"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun NativeMediaBackupCard(jobs: List<NativeMediaBackupJob>, busy: String?, onStart: () -> Unit, onDetail: (Int) -> Unit, error: String?, notice: String?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("backup-media-jobs-card"), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("媒体文件备份", "Media backups"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onStart, enabled = busy == null) { Text(if (busy == "media:create") nativePhrase("启动中…", "Starting…") else nativePhrase("立即备份", "Back up now"), fontSize = 10.sp) } }
            HlovetFormFeedback(error, notice)
            if (jobs.isEmpty()) Text(nativePhrase("暂无媒体备份任务。", "No media backup jobs."), color = HlovetUi.muted, fontSize = 11.sp)
            jobs.forEach { job -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (job.status == "completed") Icons.Filled.CheckCircle else if (job.status == "failed") Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = if (job.status == "failed") HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Column(Modifier.weight(1f)) { Text(nativePhrase("任务 #${job.id} · ${mediaBackupStatus(job.status)}", "Job #${job.id} · ${mediaBackupStatus(job.status)}"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Text(nativePhrase("${job.processedFiles}/${job.totalFiles} 个文件 · 失败 ${job.failedFiles} · ${formatBackupTime(job.createdAt)}", "${job.processedFiles}/${job.totalFiles} files · Failed ${job.failedFiles} · ${formatBackupTime(job.createdAt)}"), color = HlovetUi.muted, fontSize = 9.sp) }; TextButton(onClick = { onDetail(job.id) }, enabled = busy == null) { Text(nativePhrase("详情", "Details"), fontSize = 9.sp) } } }
        }
    }
}

@Composable
private fun NativeMediaBackupFilesCard(
    files: List<NativeMediaBackupFile>,
    total: Int,
    page: Int,
    pageCount: Int,
    category: String,
    searchText: String,
    busy: String?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCategoryChange: (String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRestore: (NativeMediaBackupFile) -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("backup-media-files-card"), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(7.dp))
                Text(nativePhrase("媒体文件目录", "Media file catalog"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(nativePhrase("共 $total 个", "$total files"), color = HlovetUi.muted, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.weight(1f).testTag("backup-media-search"),
                    singleLine = true,
                    label = { Text(nativePhrase("搜索文件名或来源", "Search filename or source"), fontSize = 9.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                    colors = nativeBackupFieldColors(),
                )
                IconButton(onClick = onSearch, enabled = busy == null, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索", "Search"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                }
                NativeMediaBackupCategoryMenu(category, busy == null, onCategoryChange)
            }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HlovetUi.accent); Text(nativePhrase("正在读取媒体目录…", "Loading media catalog..."), fontSize = 11.sp, color = HlovetUi.muted) }
            } else if (error != null) {
                HlovetFormFeedback(error)
                TextButton(onClick = onRetry, enabled = busy == null) { Text(nativePhrase("重试", "Retry"), fontSize = 11.sp) }
            } else if (files.isEmpty()) {
                Text(nativePhrase("暂无媒体文件目录记录。请先完成一次媒体备份或存储扫描。", "No media file catalog records. Run a media backup or storage scan first."), color = HlovetUi.muted, fontSize = 11.sp)
            } else {
                files.forEach { file -> NativeMediaBackupFileRow(file, busy, onRestore) }
                if (pageCount > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(nativePhrase("第 $page / $pageCount 页", "Page $page / $pageCount"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = onPreviousPage, enabled = page > 1 && busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.ChevronLeft, contentDescription = nativePhrase("上一页", "Previous page"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = onNextPage, enabled = page < pageCount && busy == null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.ChevronRight, contentDescription = nativePhrase("下一页", "Next page"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

/** 选择媒体备份的 category 分类；enabled 禁用操作，onChange 回传分类代码，空串表示全部分类。 */
@Composable
private fun NativeMediaBackupCategoryMenu(category: String, enabled: Boolean, onChange: (String) -> Unit) {
    // 分类仅影响媒体备份列表，选项弹层沿用全站背景和圆角，不自行发起备份。
    var expanded by remember(category) { mutableStateOf(false) }
    val options = listOf(
        "" to nativePhrase("全部分类", "All categories"),
        "backgrounds" to nativePhrase("背景图", "Backgrounds"),
        "site-assets" to nativePhrase("站点资源", "Site assets"),
        "android-releases" to nativePhrase("Android 安装包", "Android releases"),
        "avatars" to nativePhrase("头像", "Avatars"),
        "articles" to nativePhrase("文章", "Articles"),
        "chat" to nativePhrase("聊天", "Chat"),
    )
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(options.firstOrNull { it.first == category }?.second ?: category, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("⌄", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 11.sp) },
                    onClick = { expanded = false; onChange(value) },
                )
            }
        }
    }
}

@Composable
private fun NativeMediaBackupFileRow(file: NativeMediaBackupFile, busy: String?, onRestore: (NativeMediaBackupFile) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Archive, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(file.storedName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nativePhrase("${mediaBackupCategory(file.category)} · ${formatBackupBytes(file.sizeBytes)} · ${mediaBackupRestoreStatus(file)}", "${mediaBackupCategory(file.category)} · ${formatBackupBytes(file.sizeBytes)} · ${mediaBackupRestoreStatus(file)}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(nativePhrase("来源：${file.sourceLabel.ifBlank { file.sourceType }}", "Source: ${file.sourceLabel.ifBlank { file.sourceType }}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onRestore(file) }, enabled = busy == null && file.lastBackedUpAt != null) { Text(nativePhrase("恢复", "Restore"), fontSize = 9.sp) }
    }
}

@Composable
private fun NativeBackupMessage(message: String, error: Boolean, onDismiss: (() -> Unit)? = null) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (error) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = if (error) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (error) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Dismiss"), fontSize = 10.sp) } } } } }

@Composable
private fun NativeBackupField(label: String, value: String, enabled: Boolean, modifier: Modifier, keyboardType: KeyboardType = KeyboardType.Text, visual: PasswordVisualTransformation? = null, onChange: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, modifier = modifier, singleLine = true, label = { Text(label, fontSize = 9.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), keyboardOptions = KeyboardOptions(keyboardType = keyboardType), visualTransformation = visual ?: androidx.compose.ui.text.input.VisualTransformation.None, colors = nativeBackupFieldColors()) }

private fun NativeBackupConfiguration.toForm() = NativeBackupConfigurationForm(automaticEnabled, scheduleTime, localRetentionDays.toString(), remoteRetentionDays.toString(), oss.enabled, oss.region, oss.endpoint, oss.bucket, oss.prefix, "", "", false, r2.enabled, r2.accountId, r2.bucket, r2.prefix, "", "", false)

@Composable
private fun nativeBackupFieldColors() = HlovetUi.fieldColors()

private fun backupVerificationText(status: String): String = when (status) { "verified" -> nativePhrase("已校验", "Verified"); "database_only" -> nativePhrase("仅数据库已校验", "Database verified only"); "failed" -> nativePhrase("校验失败", "Verification failed"); else -> nativePhrase("未校验", "Not verified") }
private fun mediaBackupStatus(status: String): String = when (status) { "pending" -> nativePhrase("等待中", "Pending"); "running" -> nativePhrase("执行中", "Running"); "completed" -> nativePhrase("已完成", "Completed"); "partial" -> nativePhrase("部分失败", "Partially failed"); "failed" -> nativePhrase("失败", "Failed"); else -> status }
private fun mediaBackupCategory(category: String): String = when (category) { "backgrounds" -> nativePhrase("背景图", "Backgrounds"); "site-assets" -> nativePhrase("站点资源", "Site assets"); "android-releases" -> nativePhrase("Android 安装包", "Android releases"); "avatars" -> nativePhrase("头像", "Avatars"); "articles" -> nativePhrase("文章", "Articles"); "chat" -> nativePhrase("聊天", "Chat"); else -> category.ifBlank { nativePhrase("未知分类", "Unknown category") } }
private fun mediaBackupRestoreStatus(file: NativeMediaBackupFile): String = if (file.lastBackedUpAt != null) nativePhrase("可恢复", "Restorable") else nativePhrase("未备份", "Not backed up")
private fun formatBackupBytes(value: Long): String = when { value < 1024L -> "$value B"; value < 1024L * 1024L -> "${value / 1024} KB"; value < 1024L * 1024L * 1024L -> "${"%.1f".format(value.toDouble() / 1024 / 1024)} MB"; else -> "${"%.1f".format(value.toDouble() / 1024 / 1024 / 1024)} GB" }
private fun formatBackupTime(value: String): String = value.replace("T", " ").substringBefore(".").substringBefore("+").ifBlank { nativePhrase("暂无", "None") }
