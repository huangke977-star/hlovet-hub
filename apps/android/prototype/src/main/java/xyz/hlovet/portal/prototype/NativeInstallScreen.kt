@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeInstallScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<NativeInstallSettings?>(null) }
    var release by remember { mutableStateOf<NativeAndroidRelease?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeInstallApi.loadSettings() to NativeInstallApi.latestRelease()
                }
            }.onSuccess { (loadedSettings, latest) ->
                settings = loadedSettings
                release = latest
            }.onFailure { exception ->
                error = exception.message ?: nativePhrase("安装信息读取失败。", "Could not load installation information.")
            }
            loading = false
        }
    }

    LaunchedEffect(reloadKey) { load() }

    fun openUrl(url: String, failureMessage: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { exception ->
            if (exception is ActivityNotFoundException) {
                error = failureMessage
            } else {
                error = exception.message ?: failureMessage
            }
        }
    }

    val latest = release
    val currentCode = BuildConfig.VERSION_CODE
    val updateState = when {
        latest == null -> nativePhrase("暂无可用安装包", "No release available")
        latest.versionCode > currentCode -> nativePhrase("有可用更新", "Update available")
        latest.versionCode == currentCode -> nativePhrase("当前已是最新版本", "You are up to date")
        else -> nativePhrase("当前构建版本较新", "Current build is newer")
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
                        Text(nativePhrase("安装与版本", "Install and version"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("查看原生客户端和 PWA 安装入口", "View the native client and PWA installation options"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank()) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error.orEmpty(), color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { error = null }) { Text(nativePhrase("关闭", "Close"), fontSize = 10.sp) }
                    }
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Android, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("当前 Android 客户端", "Current Android client"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${BuildConfig.APPLICATION_ID} · v${BuildConfig.VERSION_NAME} (Code $currentCode)", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                    }
                    Text(nativePhrase("这个入口用于查看客户端版本和服务器发布的 APK，不会修改当前已安装的应用。", "Use this page to view the client version and server-published APKs. It will not change the installed app."), color = HlovetUi.muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = if (latest != null) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("服务器最新版", "Latest server release"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (loading) nativePhrase("正在读取…", "Loading...") else updateState, color = if (latest != null) HlovetUi.accent else HlovetUi.muted, fontSize = 10.sp)
                        }
                    }
                    if (latest == null && !loading) {
                        Text(nativePhrase("管理员还没有发布可下载的 APK。", "The administrator has not published a downloadable APK yet."), color = HlovetUi.muted, fontSize = 11.sp)
                    } else if (latest != null) {
                        Text("v${latest.versionName} · Code ${latest.versionCode} · ${formatNativeInstallBytes(latest.sizeBytes)}", color = HlovetUi.muted, fontSize = 11.sp)
                        if (latest.releaseNotes.isNotEmpty()) {
                            Text(latest.releaseNotes.joinToString(" / "), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { openUrl(NativeAdminAndroidApi.resolveUrl(latest.apkUrl), nativePhrase("设备上没有可以打开下载链接的应用。", "No app can open the download link on this device.")) }) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(nativePhrase("下载 APK", "Download APK"), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("浏览器 PWA 安装诊断", "Browser PWA diagnostics"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (settings?.enabled == false) nativePhrase("站点当前关闭了 PWA 安装入口", "PWA installation is currently disabled") else nativePhrase("PWA 诊断需要在 Chrome 或 Safari 浏览器中运行", "PWA diagnostics run in Chrome or Safari"), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                    }
                    Text(nativePhrase("原生客户端无法读取浏览器的安装弹窗状态。打开网页诊断页后，可以检查 HTTPS、manifest、图标和 Service Worker。", "The native client cannot read the browser install prompt. Open the web diagnostics page to check HTTPS, the manifest, icons, and the Service Worker."), color = HlovetUi.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { openUrl(NativeInstallApi.webInstallUrl(), nativePhrase("设备上没有可用的浏览器。", "No browser is available on this device.")) }, enabled = settings?.enabled != false) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(nativePhrase("打开网页诊断", "Open web diagnostics"), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatNativeInstallBytes(bytes: Long): String {
    if (bytes <= 0) return nativePhrase("大小未知", "Unknown size")
    var value = bytes.toDouble()
    val units = listOf("B", "KB", "MB", "GB")
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "${if (index == 0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)} ${units[index]}"
}
