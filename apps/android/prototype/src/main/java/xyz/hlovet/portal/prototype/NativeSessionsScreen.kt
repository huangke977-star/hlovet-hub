@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Devices
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
import androidx.compose.runtime.mutableIntStateOf
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

private sealed interface NativeSessionAction {
    data object Others : NativeSessionAction
    data object All : NativeSessionAction
    data class Single(val session: NativeAuthSession) : NativeSessionAction
}

@Composable
internal fun NativeSessionsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onCurrentSessionRevoked: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var sessions by remember(token) { mutableStateOf<List<NativeAuthSession>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<NativeSessionAction?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(token, reloadKey) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            sessions = emptyList()
            loading = false
            error = nativePhrase("请先登录后查看登录设备。", "Sign in to view signed-in devices.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { NativeAuthApi.listSessions(currentToken) }
        }.onSuccess { next ->
            sessions = next
        }.onFailure { exception ->
            error = exception.message ?: nativePhrase("登录设备加载失败，请稍后重试。", "Unable to load signed-in devices. Try again later.")
        }
        loading = false
    }

    fun runAction(target: NativeSessionAction) {
        val currentToken = token
        if (currentToken.isNullOrBlank() || busy) return
        busy = true
        action = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (target) {
                        NativeSessionAction.Others -> NativeAuthApi.revokeOtherSessions(currentToken) to false
                        NativeSessionAction.All -> NativeAuthApi.revokeAllSessions(currentToken) to true
                        is NativeSessionAction.Single -> NativeAuthApi.revokeSession(currentToken, target.session.id).let { 1 to it }
                    }
                }
            }.onSuccess { result ->
                val revokedCount = result.first
                val currentRevoked = result.second
                if (currentRevoked) {
                    onCurrentSessionRevoked()
                } else {
                    reloadKey += 1
                    onNotice(nativePhrase("已退出 $revokedCount 个设备会话。", "Signed out of $revokedCount device session(s)."))
                }
            }.onFailure { exception ->
                onNotice(exception.message ?: nativePhrase("退出设备失败，请稍后重试。", "Unable to sign out the device. Try again later."))
            }
            busy = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("登录设备", "Signed-in devices"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { if (!busy) reloadKey += 1 }, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新登录设备", "Refresh signed-in devices"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            GlassCard(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Devices, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nativePhrase("管理已登录的设备", "Manage signed-in devices"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            nativePhrase(
                                "可以退出不再使用的设备；当前设备会标记为当前设备。",
                                "Sign out devices you no longer use. This device is marked as the current device.",
                            ),
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        if (loading) {
            item {
                SessionMessage(nativePhrase("正在读取登录设备…", "Loading signed-in devices..."))
            }
        } else if (!error.isNullOrBlank()) {
            item {
                SessionMessage(error!!, isError = true)
            }
        } else {
            val otherCount = sessions.count { !it.current }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { action = NativeSessionAction.Others },
                        enabled = !busy && otherCount > 0,
                    ) { Text(nativePhrase("退出其他设备", "Sign out other devices")) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { action = NativeSessionAction.All },
                        enabled = !busy && sessions.isNotEmpty(),
                    ) { Text(nativePhrase("退出全部设备", "Sign out all devices"), color = HlovetUi.secondaryAccent) }
                }
            }
            if (sessions.isEmpty()) {
                item { SessionMessage(nativePhrase("当前没有可用的登录会话。", "No signed-in sessions are available.")) }
            } else {
                items(sessions, key = { it.id }) { item ->
                    NativeSessionRow(
                        session = item,
                        enabled = !busy,
                        onRevoke = { action = NativeSessionAction.Single(item) },
                    )
                }
            }
        }
    }

    val pending = action
    if (pending != null) {
        val message = when (pending) {
            NativeSessionAction.Others -> nativePhrase("确定退出其他设备吗？当前设备会保持登录。", "Sign out all other devices? This device will remain signed in.")
            NativeSessionAction.All -> nativePhrase("确定退出全部设备吗？当前设备也会退出登录。", "Sign out all devices? This device will also be signed out.")
            is NativeSessionAction.Single -> if (pending.session.current) {
                nativePhrase("确定退出当前设备吗？退出后需要重新登录。", "Sign out this device? You will need to sign in again.")
            } else {
                nativePhrase(
                    "确定退出${nativeSessionDevice(pending.session.userAgent)}吗？",
                    "Sign out ${nativeSessionDevice(pending.session.userAgent)}?",
                )
            }
        }
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) action = null },
            title = { Text(nativePhrase("确认退出设备", "Confirm device sign-out")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { runAction(pending) }, enabled = !busy) {
                    Text(nativePhrase("确认", "Confirm"), color = HlovetUi.secondaryAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { action = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun NativeSessionRow(
    session: NativeAuthSession,
    enabled: Boolean,
    onRevoke: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.rowShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(Icons.Filled.Devices, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(nativeSessionDevice(session.userAgent), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (session.current) {
                        Text(nativePhrase("当前设备", "Current device"), color = HlovetUi.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    listOf(
                        session.ip.takeIf { it.isNotBlank() && it != "unknown" } ?: nativePhrase("IP 未记录", "IP not recorded"),
                        nativePhrase("登录 ${nativeSessionTime(session.issuedAt)}", "Signed in ${nativeSessionTime(session.issuedAt)}"),
                    ).joinToString(" · "),
                    color = HlovetUi.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nativePhrase("有效期至 ${nativeSessionTime(session.expiresAt)}", "Expires ${nativeSessionTime(session.expiresAt)}"),
                    color = HlovetUi.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRevoke, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = nativePhrase("退出设备", "Sign out device"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun SessionMessage(message: String, isError: Boolean = false) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.rowShape,
    ) {
        Text(
            message,
            color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(17.dp),
        )
    }
}

private fun nativeSessionDevice(userAgent: String): String {
    val value = userAgent.lowercase()
    return when {
        value.contains("android") -> nativePhrase("Android 设备", "Android device")
        value.contains("iphone") || value.contains("ipad") || value.contains("ios") -> nativePhrase("iOS 设备", "iOS device")
        value.contains("windows") -> nativePhrase("Windows 设备", "Windows device")
        value.contains("mac os") || value.contains("macintosh") -> nativePhrase("macOS 设备", "macOS device")
        value.contains("linux") -> nativePhrase("Linux 设备", "Linux device")
        else -> nativePhrase("未知设备", "Unknown device")
    }
}

private fun nativeSessionTime(value: String): String = value
    .takeIf { it.isNotBlank() }
    ?.replace('T', ' ')
    ?.replace(Regex("\\.\\d+Z?$"), "")
    ?.removeSuffix("Z")
    ?.take(16)
    ?: nativePhrase("未知时间", "Unknown time")
