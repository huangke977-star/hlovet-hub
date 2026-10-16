@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ANDROID_RELEASE_BYTES = 120L * 1024L * 1024L

@Composable
internal fun NativeAdminAndroidScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var releases by remember(token) { mutableStateOf<List<NativeAndroidRelease>>(emptyList()) }
    var selectedFile by remember(token) { mutableStateOf<NativeUploadFile?>(null) }
    var versionName by remember(token) { mutableStateOf("") }
    var versionCode by remember(token) { mutableStateOf("1") }
    var channel by remember(token) { mutableStateOf("stable") }
    var notes by remember(token) { mutableStateOf("") }
    var activateAfterUpload by remember(token) { mutableStateOf(true) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(token) { mutableStateOf<NativeAndroidRelease?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, MAX_ANDROID_RELEASE_BYTES) }
                if (!file.name.lowercase().endsWith(".apk")) throw IllegalStateException(nativePhrase("只能选择 .apk 安装包。", "Select an .apk package."))
                selectedFile = file
                if (versionName.isBlank()) versionName = guessAndroidVersion(file.name).ifBlank { nextAndroidVersion(releases) }
                if (versionCode == "1") versionCode = nextAndroidVersionCode(releases).toString()
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("APK 读取失败。", "Could not read the APK.")
            }
        }
    }

    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            releases = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching { withContext(Dispatchers.IO) { NativeAdminAndroidApi.list(token) } }
            .onSuccess { next -> releases = next.sortedWith(compareByDescending<NativeAndroidRelease> { it.isActive }.thenByDescending { it.versionCode }); if (versionCode == "1") versionCode = nextAndroidVersionCode(next).toString() }
            .onFailure { error = it.message ?: nativePhrase("安装包列表读取失败。", "Could not load app releases.") }
        loading = false
    }

    fun upload() {
        val accessToken = token ?: return
        val file = selectedFile ?: run { error = nativePhrase("请选择 APK 安装包。", "Select an APK package first."); return }
        val parsedCode = versionCode.toIntOrNull()
        if (versionName.trim().isBlank() || parsedCode == null || parsedCode < 1) { error = nativePhrase("请填写版本名称和大于 0 的版本编码。", "Enter a version name and a version code greater than 0."); return }
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { NativeAdminAndroidApi.upload(accessToken, file, versionName.trim(), parsedCode, channel.trim().ifBlank { "stable" }, notes, activateAfterUpload) }
                selectedFile = null
                versionName = ""
                versionCode = (parsedCode + 1).toString()
                notes = ""
                notice = if (release.isActive) nativePhrase("安装包已上传并设为最新版。", "The package was uploaded and activated.") else nativePhrase("安装包已上传。", "The package was uploaded.")
                reloadKey++
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("安装包上传失败。", "Package upload failed.") } finally { busy = false }
        }
    }

    fun activate(release: NativeAndroidRelease) {
        val accessToken = token ?: return
        if (busy || release.isActive) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { NativeAdminAndroidApi.activate(accessToken, release.id) }; notice = nativePhrase("v${release.versionName} 已设为 Android 最新版。", "v${release.versionName} is now the active Android release."); reloadKey++ }
            catch (exception: Exception) { error = exception.message ?: nativePhrase("安装包切换失败。", "Could not activate the package.") }
            finally { busy = false }
        }
    }

    fun delete(release: NativeAndroidRelease) {
        val accessToken = token ?: return
        confirmDelete = null
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { NativeAdminAndroidApi.delete(accessToken, release.id) }; notice = nativePhrase("安装包及其磁盘文件已删除。", "The package and its disk file were deleted."); reloadKey++ }
            catch (exception: Exception) { error = exception.message ?: nativePhrase("安装包删除失败。", "Could not delete the package.") }
            finally { busy = false }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(title = { Column { Text(nativePhrase("安装包管理", "App releases"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("管理安装页展示的 Android APK", "Manage the Android APK shown on the install page"), color = HlovetUi.muted, fontSize = 10.sp) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } }, actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading && !busy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) item { NativeAndroidMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        if (token.isNullOrBlank()) {
            item { NativeAndroidMessage(nativePhrase("登录后查看安装包管理。", "Sign in to manage app releases."), false, null) }
        } else if (!canAccess) {
            item { NativeAndroidMessage(nativePhrase("安装包管理仅超级管理员可访问。", "Only super administrators can manage app releases."), true, null) }
        } else if (loading && releases.isEmpty()) {
            item { NativeAndroidMessage(nativePhrase("正在读取安装包…", "Loading app releases…"), false, null) }
        } else {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Upload, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Text(nativePhrase("上传新版安装包", "Upload a new release"), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Text(nativePhrase("单个 APK 不超过 120 MB；上传后可设为安装页展示的最新版。", "Each APK must be under 120 MB; you can activate it for the install page after upload."), color = HlovetUi.muted, fontSize = 10.sp)
                        TextButton(onClick = { filePicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = !busy) { Text(selectedFile?.name ?: nativePhrase("选择 APK 文件", "Choose APK file"), fontSize = 11.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { AndroidField(nativePhrase("版本名称", "Version name"), versionName, !busy, Modifier.weight(1f)) { versionName = it }; AndroidField(nativePhrase("版本编码", "Version code"), versionCode, !busy, Modifier.width(100.dp)) { versionCode = it.filter(Char::isDigit) } }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { AndroidField(nativePhrase("发布通道", "Release channel"), channel, !busy, Modifier.weight(1f)) { channel = it }; AndroidField(nativePhrase("更新说明", "Release notes"), notes, !busy, Modifier.weight(1f)) { notes = it } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("上传后设为最新版", "Activate after upload"), fontSize = 11.sp, modifier = Modifier.weight(1f)); HlovetSwitch(checked = activateAfterUpload, onCheckedChange = { activateAfterUpload = it }, enabled = !busy) }
                        TextButton(onClick = ::upload, enabled = !busy && selectedFile != null) { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (busy) nativePhrase("处理中…", "Processing…") else nativePhrase("上传安装包", "Upload package"), fontSize = 11.sp) }
                    }
                }
            }
            item { Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("安装包仓库", "Release library"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(nativePhrase("${releases.size} 个版本", "${releases.size} releases"), color = HlovetUi.muted, fontSize = 10.sp) } }
            if (releases.isEmpty()) item { NativeAndroidMessage(nativePhrase("暂无上传版本，安装页会继续使用内置下载入口。", "No uploaded releases; the install page will keep using its built-in download entry."), false, null) }
            items(releases, key = { it.id }) { release ->
                NativeAndroidReleaseRow(release, busy, { activate(release) }, { confirmDelete = release }) { val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NativeAdminAndroidApi.resolveUrl(release.apkUrl))); context.startActivity(intent) }
            }
        }
    }
    confirmDelete?.let { release -> HlovetAlertDialog(onDismissRequest = { if (!busy) confirmDelete = null }, title = { Text(nativePhrase("确认删除安装包", "Delete app release?")) }, text = { Text(if (release.isActive) nativePhrase("删除当前最新版后，安装页会暂时使用兜底下载包。确定继续吗？", "After deleting the active release, the install page will temporarily use its fallback download package. Continue?") else nativePhrase("将从服务器磁盘永久删除 ${release.originalName}，确定继续吗？", "${release.originalName} will be permanently deleted from the server. Continue?"), fontSize = 13.sp) }, confirmButton = { TextButton(onClick = { delete(release) }, enabled = !busy) { Text(nativePhrase("永久删除", "Delete permanently")) } }, dismissButton = { TextButton(onClick = { confirmDelete = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } }) }
}

@Composable
private fun AndroidField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, modifier = modifier, singleLine = true, label = { Text(label, fontSize = 10.sp) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeAndroidFieldColors()) }

@Composable
private fun NativeAndroidReleaseRow(release: NativeAndroidRelease, busy: Boolean, onActivate: () -> Unit, onDelete: () -> Unit, onDownload: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (release.isActive) Icons.Filled.CheckCircle else Icons.Filled.Storage, contentDescription = null, tint = if (release.isActive) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("v${release.versionName}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(" · Code ${release.versionCode} · ${release.channel}", color = HlovetUi.muted, fontSize = 9.sp) }; Text(release.originalName, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); if (release.releaseNotes.isNotEmpty()) Text(release.releaseNotes.joinToString(" / "), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${formatAndroidBytes(release.sizeBytes)} · SHA256 ${release.sha256.take(12)}…", color = HlovetUi.muted, fontSize = 9.sp) }; Column(horizontalAlignment = Alignment.End) { TextButton(onClick = onDownload, enabled = !busy) { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("下载", "Download"), fontSize = 9.sp) }; if (!release.isActive) TextButton(onClick = onActivate, enabled = !busy) { Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("设为最新版", "Activate"), fontSize = 9.sp) }; TextButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(15.dp)); Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent, fontSize = 9.sp) } } } }
}

@Composable
private fun NativeAndroidMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isError) Icons.Filled.Shield else Icons.Filled.Storage, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Dismiss"), fontSize = 10.sp) } } } } }

private fun nextAndroidVersionCode(releases: List<NativeAndroidRelease>): Int = (releases.maxOfOrNull { it.versionCode } ?: 0) + 1
private fun nextAndroidVersion(releases: List<NativeAndroidRelease>): String = "0.1.${releases.size + 1}"
private fun guessAndroidVersion(name: String): String = Regex("(?:v|version)?(\\d+(?:\\.\\d+){0,3})", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1).orEmpty()
private fun formatAndroidBytes(value: Long): String = if (value < 1024 * 1024) "${value / 1024} KB" else "${"%.1f".format(value.toDouble() / 1024 / 1024)} MB"
@Composable private fun nativeAndroidFieldColors() = HlovetUi.fieldColors()
