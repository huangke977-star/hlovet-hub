@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NativeAdminIntegrationsScreen(session: NativeSession?, onBack: () -> Unit) {
    key(session?.accessToken, session?.isSuperAdmin) { NativeAdminIntegrationsContent(session, onBack) }
}

@Composable
private fun NativeAdminIntegrationsContent(session: NativeSession?, onBack: () -> Unit) {
    val token = session?.accessToken
    val canAccess = !token.isNullOrBlank() && session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var google by remember { mutableStateOf<NativeAdminGoogleOAuthConfig?>(null) }
    var draft by remember { mutableStateOf<NativeAdminGoogleOAuthDraft?>(null) }
    var googleSecret by remember { mutableStateOf("") }
    var webhooks by remember { mutableStateOf<List<NativeAdminWebhook>>(emptyList()) }
    var deliveries by remember { mutableStateOf<List<NativeAdminWebhookDelivery>>(emptyList()) }
    var googleLoading by remember { mutableStateOf(canAccess) }
    var hooksLoading by remember { mutableStateOf(canAccess) }
    var deliveriesLoading by remember { mutableStateOf(canAccess) }
    var googleReload by remember { mutableIntStateOf(0) }
    var hooksReload by remember { mutableIntStateOf(0) }
    var deliveriesReload by remember { mutableIntStateOf(0) }
    var googleReadError by remember { mutableStateOf<String?>(null) }
    var hooksReadError by remember { mutableStateOf<String?>(null) }
    var deliveriesReadError by remember { mutableStateOf<String?>(null) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleNotice by remember { mutableStateOf<String?>(null) }
    var hooksError by remember { mutableStateOf<String?>(null) }
    var hooksNotice by remember { mutableStateOf<String?>(null) }
    var deliveryNotice by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var savingGoogle by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val operating = savingGoogle || busy
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NativeAdminWebhook?>(null) }
    var replayTarget by remember { mutableStateOf<NativeAdminWebhookDelivery?>(null) }
    var confirmTest by remember { mutableStateOf(false) }
    var selectedWebhook by remember { mutableStateOf<NativeAdminWebhook?>(null) }
    var selectedDelivery by remember { mutableStateOf<NativeAdminWebhookDelivery?>(null) }
    var webhookName by remember { mutableStateOf("") }
    var webhookUrl by remember { mutableStateOf("") }
    var webhookSecret by remember { mutableStateOf("") }
    var webhookEvents by remember { mutableStateOf(listOf(nativeIntegrationEvents.first())) }
    var deliveriesDenied by remember { mutableStateOf(false) }

    LaunchedEffect(canAccess, googleReload) {
        if (!canAccess) return@LaunchedEffect
        googleLoading = true
        googleReadError = null
        try {
            val loaded = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.getGoogleOAuth(token!!) }
            val preserve = draft != null && (draft != google?.toDraft() || googleSecret.isNotBlank())
            google = loaded
            if (!preserve) draft = loaded.toDraft()
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { googleReadError = exception.message ?: nativePhrase("Google 配置读取失败。", "Could not load Google settings.") }
        finally { googleLoading = false }
    }
    LaunchedEffect(canAccess, hooksReload) {
        if (!canAccess) return@LaunchedEffect
        hooksLoading = true
        hooksReadError = null
        try {
            webhooks = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.listWebhooks(token!!) }
            selectedWebhook = selectedWebhook?.let { selected -> webhooks.firstOrNull { it.id == selected.id } }
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { hooksReadError = exception.message ?: nativePhrase("Webhook 读取失败。", "Could not load webhooks.") }
        finally { hooksLoading = false }
    }
    LaunchedEffect(canAccess, deliveriesReload) {
        if (!canAccess) return@LaunchedEffect
        deliveriesLoading = true
        deliveriesReadError = null
        try {
            deliveries = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.listDeliveries(token!!) }
            selectedDelivery = selectedDelivery?.let { selected -> deliveries.firstOrNull { it.id == selected.id } }
            deliveriesDenied = false
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) {
            deliveriesReadError = exception.message ?: nativePhrase("投递记录读取失败。", "Could not load delivery records.")
            deliveriesDenied = exception is NativeIntegrationHttpException && exception.status in listOf(401, 403)
        } finally { deliveriesLoading = false }
    }
    val hasPendingDelivery = deliveries.any { it.status == "pending" }
    LaunchedEffect(canAccess, hasPendingDelivery, deliveriesDenied) {
        if (!canAccess || !hasPendingDelivery || deliveriesDenied) return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (!busy && !savingGoogle && !deliveriesLoading) deliveriesReload++
        }
    }

    fun saveGoogle() {
        val current = draft ?: return
        val config = google ?: return
        if (!canAccess || operating || googleLoading) return
        googleError = nativeGoogleOAuthDraftError(config, current, googleSecret)
        googleNotice = null
        if (googleError != null) return
        savingGoogle = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.updateGoogleOAuth(token!!, current.enabled, current.clientId.trim(), googleSecret, current.redirectUri.trim()) }
                google = saved
                draft = saved.toDraft()
                googleSecret = ""
                googleNotice = nativePhrase("Google 登录配置已保存。", "Google sign-in configuration saved.")
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { googleError = exception.message ?: nativePhrase("Google 配置保存失败。", "Could not save Google settings.") }
            finally { savingGoogle = false }
        }
    }
    fun resetCreate() {
        showCreate = false
        webhookName = ""; webhookUrl = ""; webhookSecret = ""
        webhookEvents = listOf(nativeIntegrationEvents.first())
        dialogError = null
    }
    fun createWebhook() {
        if (!canAccess || operating) return
        dialogError = nativeWebhookDraftError(webhookName, webhookUrl, webhookSecret, webhookEvents)
        if (dialogError != null) return
        busy = true
        hooksError = null; hooksNotice = null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.createWebhook(token!!, webhookName.trim(), webhookUrl.trim(), webhookSecret, webhookEvents) }
                webhooks = listOf(saved) + webhooks.filterNot { it.id == saved.id }
                resetCreate()
                hooksNotice = nativePhrase("Webhook 已添加。", "Webhook added.")
                hooksReload++
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { dialogError = exception.message ?: nativePhrase("Webhook 添加失败。", "Could not add the webhook.") }
            finally { busy = false }
        }
    }
    fun toggleWebhook(item: NativeAdminWebhook, enabled: Boolean) {
        if (!canAccess || operating || hooksLoading) return
        busy = true
        hooksError = null; hooksNotice = null
        scope.launch {
            try {
                val confirmed = withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.updateWebhook(token!!, item.id, enabled) }
                val saved = item.copy(name = confirmed.name, url = confirmed.url, events = confirmed.events, enabled = confirmed.enabled, updatedAt = confirmed.updatedAt)
                webhooks = webhooks.map { if (it.id == item.id) saved else it }
                hooksNotice = if (saved.enabled) nativePhrase("Webhook 已启用。", "Webhook enabled.") else nativePhrase("Webhook 已停用。", "Webhook disabled.")
                hooksReload++
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { hooksError = exception.message ?: nativePhrase("Webhook 状态更新失败。", "Could not update webhook status.") }
            finally { busy = false }
        }
    }
    fun deleteWebhook(item: NativeAdminWebhook) {
        if (!canAccess || operating) return
        busy = true
        dialogError = null; hooksError = null; hooksNotice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminIntegrationsApi.deleteWebhook(token!!, item.id) }
                webhooks = webhooks.filterNot { it.id == item.id }
                deliveries = deliveries.filterNot { it.endpointId == item.id }
                deleteTarget = null
                hooksNotice = nativePhrase("Webhook 及其投递记录已删除。", "Webhook and its delivery records deleted.")
                hooksReload++; deliveriesReload++
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { dialogError = exception.message ?: nativePhrase("Webhook 删除失败。", "Could not delete the webhook.") }
            finally { busy = false }
        }
    }
    fun dispatchDelivery(replay: NativeAdminWebhookDelivery?) {
        if (!canAccess || operating) return
        busy = true
        dialogError = null; deliveryNotice = null
        scope.launch {
            try {
                deliveryNotice = withContext(Dispatchers.IO) { if (replay == null) NativeAdminIntegrationsApi.testWebhook(token!!) else NativeAdminIntegrationsApi.replayDelivery(token!!, replay.id) }
                confirmTest = false; replayTarget = null
                hooksReload++; deliveriesReload++
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { dialogError = exception.message ?: nativePhrase("投递请求失败。", "Could not submit the delivery request.") }
            finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("integrations-list"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            TopAppBar(title = { Column { Text(nativePhrase("外部集成", "External integrations"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("Google 登录、Webhook 与投递记录", "Google sign-in, webhooks, and deliveries"), color = HlovetUi.muted, fontSize = 10.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { googleReload++; hooksReload++; deliveriesReload++ }, enabled = canAccess && !operating && !googleLoading && !hooksLoading && !deliveriesLoading) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }
        if (!canAccess) {
            item(key = "gate") { HlovetFormFeedback(if (token.isNullOrBlank()) null else nativePhrase("外部集成仅超级管理员可管理。", "Only super administrators can manage external integrations."), if (token.isNullOrBlank()) nativePhrase("登录后查看外部集成。", "Sign in to view external integrations.") else null, Modifier.padding(horizontal = 20.dp)) }
        } else {
            item(key = "google") {
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_google_color), null, Modifier.size(20.dp), tint = Color.Unspecified)
                            Spacer(Modifier.width(8.dp)); Text(nativePhrase("Google 登录", "Google sign-in"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            google?.let { Text(if (it.enabled) nativePhrase("已启用", "Enabled") else nativePhrase("未启用", "Disabled"), color = HlovetUi.accent, fontSize = 10.sp) }
                        }
                        if (googleLoading) Text(nativePhrase("正在读取 Google 配置…", "Loading Google settings…"), color = HlovetUi.muted, fontSize = 11.sp)
                        HlovetFormFeedback(googleReadError)
                        if (googleReadError != null) TextButton(onClick = { googleReload++ }, enabled = !operating && !googleLoading) { Text(nativePhrase("重试 Google 配置", "Retry Google settings"), fontSize = 11.sp) }
                        draft?.let { current ->
                            val editable = !operating && !googleLoading
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("启用 Google 登录", "Enable Google sign-in"), fontSize = 12.sp, modifier = Modifier.weight(1f)); HlovetSwitch(current.enabled, { draft = current.copy(enabled = it) }, enabled = editable, modifier = Modifier.testTag("integration-google-enabled")) }
                            NativeIntegrationField("Client ID", current.clientId, editable, Modifier.fillMaxWidth().testTag("integration-client-id"), { draft = current.copy(clientId = it) })
                            NativeIntegrationField(if (google?.clientSecretConfigured == true) nativePhrase("Client Secret（已配置，留空不变）", "Client Secret (configured; blank keeps it)") else "Client Secret", googleSecret, editable && google?.encryptionConfigured == true, Modifier.fillMaxWidth().testTag("integration-client-secret"), { googleSecret = it }, password = true)
                            NativeIntegrationField("Redirect URI", current.redirectUri, editable, Modifier.fillMaxWidth().testTag("integration-redirect"), { draft = current.copy(redirectUri = it) })
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Security, null, Modifier.size(15.dp), tint = HlovetUi.accent); Spacer(Modifier.width(5.dp)); Text(if (google?.encryptionConfigured == true) nativePhrase("密钥加密已配置", "Secret encryption configured") else nativePhrase("服务器尚未配置密钥加密", "Secret encryption is not configured"), color = HlovetUi.muted, fontSize = 10.sp) }
                            Text(if (google?.source == "environment") nativePhrase("当前来源：环境变量", "Current source: environment variables") else nativePhrase("当前来源：数据库", "Current source: database"), color = HlovetUi.muted, fontSize = 10.sp)
                            HlovetFormFeedback(googleError, googleNotice)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = ::saveGoogle, enabled = editable && google?.encryptionConfigured == true) { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (savingGoogle) nativePhrase("保存中…", "Saving…") else nativePhrase("保存配置", "Save settings"), fontSize = 11.sp) } }
                        }
                    }
                }
            }
            item(key = "webhooks") {
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Web, null, Modifier.size(20.dp), tint = HlovetUi.accent); Spacer(Modifier.width(8.dp)); Text(nativePhrase("Webhook 端点", "Webhook endpoints"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(webhooks.size.toString(), color = HlovetUi.muted, fontSize = 10.sp); IconButton(onClick = { dialogError = null; showCreate = true }, enabled = !operating) { Icon(Icons.Filled.Link, nativePhrase("新增 Webhook", "Add webhook"), tint = HlovetUi.accent) } }
                        if (hooksLoading) Text(nativePhrase("正在读取 Webhook…", "Loading webhooks…"), color = HlovetUi.muted, fontSize = 11.sp)
                        HlovetFormFeedback(hooksReadError)
                        if (hooksReadError != null) TextButton(onClick = { hooksReload++ }, enabled = !operating && !hooksLoading) { Text(nativePhrase("重试 Webhook", "Retry webhooks"), fontSize = 11.sp) }
                        if (webhooks.isEmpty() && !hooksLoading && hooksReadError == null) Text(nativePhrase("尚未配置 Webhook。", "No webhooks configured."), color = HlovetUi.muted, fontSize = 11.sp)
                        webhooks.forEach { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { selectedWebhook = item }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.url, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.events.joinToString(" · "), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(nativePhrase("${item.deliveryCount} 条投递", "${item.deliveryCount} deliveries"), color = HlovetUi.muted, fontSize = 10.sp)
                                    if (item.lastError.isNotBlank()) Text(item.lastError, color = HlovetUi.secondaryAccent, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                HlovetSwitch(item.enabled, { toggleWebhook(item, it) }, enabled = !operating && !hooksLoading, modifier = Modifier.testTag("integration-hook-${item.id}-enabled"))
                                IconButton(onClick = { dialogError = null; deleteTarget = item }, enabled = !operating) { Icon(Icons.Filled.Delete, nativePhrase("删除 Webhook ${item.name}", "Delete webhook ${item.name}"), Modifier.size(18.dp), tint = HlovetUi.secondaryAccent) }
                            }
                            HorizontalDivider(color = HlovetUi.outline.copy(alpha = .35f))
                        }
                        HlovetFormFeedback(hooksError, hooksNotice)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { dialogError = null; confirmTest = true }, enabled = !operating && !hooksLoading && webhooks.any { it.enabled && "integration.test" in it.events }) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(nativePhrase("发送测试事件", "Send test event"), fontSize = 11.sp) } }
                    }
                }
            }
            item(key = "deliveries") {
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(20.dp), tint = HlovetUi.accent); Spacer(Modifier.width(8.dp)); Text(nativePhrase("最近投递", "Recent deliveries"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(deliveries.size.toString(), color = HlovetUi.muted, fontSize = 10.sp) }
                        if (deliveriesLoading) Text(nativePhrase("正在读取投递记录…", "Loading delivery records…"), color = HlovetUi.muted, fontSize = 11.sp)
                        HlovetFormFeedback(deliveriesReadError, deliveryNotice)
                        if (deliveriesReadError != null) TextButton(onClick = { deliveriesReload++ }, enabled = !operating && !deliveriesLoading) { Text(nativePhrase("重试投递记录", "Retry deliveries"), fontSize = 11.sp) }
                        if (deliveries.isEmpty() && !deliveriesLoading && deliveriesReadError == null) Text(nativePhrase("暂无投递记录。", "No delivery records."), color = HlovetUi.muted, fontSize = 11.sp)
                        deliveries.forEach { item ->
                            Row(Modifier.fillMaxWidth().testTag("integration-delivery-${item.id}"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { selectedDelivery = item }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("#${item.id} · ${item.eventType.ifBlank { nativePhrase("未知事件", "Unknown event") }}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${nativeIntegrationStatus(item.status)} · ${nativePhrase("尝试 ${item.attempts} 次", "${item.attempts} attempts")} · ${nativeIntegrationTime(item.createdAt)}", color = HlovetUi.muted, fontSize = 10.sp)
                                    if (item.lastError.isNotBlank()) Text(item.lastError, color = HlovetUi.secondaryAccent, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { dialogError = null; replayTarget = item }, enabled = !operating) { Icon(Icons.Filled.Replay, nativePhrase("重放投递 ${item.id}", "Replay delivery ${item.id}"), Modifier.size(18.dp), tint = HlovetUi.accent) }
                            }
                            HorizontalDivider(color = HlovetUi.outline.copy(alpha = .35f))
                        }
                    }
                }
            }
        }
    }
    if (canAccess && showCreate) {
        Dialog(onDismissRequest = { if (!operating) resetCreate() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
                GlassCard(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 16.dp), shape = HlovetUi.cardShape, containerColor = HlovetUi.dialogSurface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(nativePhrase("新增 Webhook", "Add webhook"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = ::resetCreate, enabled = !operating) { Icon(Icons.Filled.Close, nativePhrase("关闭", "Close")) } }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("integration-webhook-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            NativeIntegrationField(nativePhrase("名称", "Name"), webhookName, !operating, Modifier.fillMaxWidth().testTag("integration-hook-name"), { webhookName = it })
                            NativeIntegrationField(nativePhrase("HTTPS 地址", "HTTPS URL"), webhookUrl, !operating, Modifier.fillMaxWidth().testTag("integration-hook-url"), { webhookUrl = it })
                            NativeIntegrationField(nativePhrase("签名 Secret（16-255 位）", "Signing secret (16-255 characters)"), webhookSecret, !operating, Modifier.fillMaxWidth().testTag("integration-hook-secret"), { webhookSecret = it }, password = true)
                            Text(nativePhrase("事件", "Events"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            nativeIntegrationEvents.forEach { event -> Row(verticalAlignment = Alignment.CenterVertically) { HlovetCheckbox(event in webhookEvents, { checked -> webhookEvents = if (checked) (webhookEvents + event).distinct() else webhookEvents - event }, enabled = !operating, modifier = Modifier.testTag("integration-event-$event")); Text(event, fontSize = 11.sp) } }
                        }
                        HlovetFormFeedback(dialogError)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = ::createWebhook, enabled = !operating) { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存 Webhook", "Save webhook"), fontSize = 12.sp) } }
                    }
                }
            }
        }
    }
    if (canAccess) deleteTarget?.let { target ->
        HlovetAlertDialog(onDismissRequest = { if (!operating) deleteTarget = null }, title = { Text(nativePhrase("删除 Webhook", "Delete webhook"), fontSize = 17.sp) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(nativePhrase("确定删除“${target.name}”吗？该端点及其投递记录会一并删除，之后不会再向此地址发送事件。", "Delete “${target.name}”? The endpoint and its delivery records will be deleted, and no new events will be sent to this URL."), fontSize = 12.sp); HlovetFormFeedback(dialogError) } }, confirmButton = { TextButton(onClick = { deleteWebhook(target) }, enabled = !operating) { Text(if (busy) nativePhrase("删除中…", "Deleting…") else nativePhrase("确认删除", "Confirm delete"), fontSize = 12.sp) } }, dismissButton = { TextButton(onClick = { deleteTarget = null; dialogError = null }, enabled = !operating) { Text(nativePhrase("取消", "Cancel"), fontSize = 12.sp) } })
    }
    if (canAccess && (confirmTest || replayTarget != null)) {
        val target = replayTarget
        HlovetAlertDialog(onDismissRequest = { if (!operating) { confirmTest = false; replayTarget = null } }, title = { Text(if (target == null) nativePhrase("发送测试事件", "Send test event") else nativePhrase("重放投递 ${target.id}", "Replay delivery ${target.id}"), fontSize = 17.sp) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (target == null) nativePhrase("将向已启用并订阅 integration.test 的端点发送真实事件，是否继续？", "Send a real event to enabled endpoints subscribed to integration.test?") else nativePhrase("将再次向原端点发送 ${target.eventType}，可能触发外部系统的重复操作。是否继续？", "Send ${target.eventType} to its original endpoint again? This may repeat an action in the external system."), fontSize = 12.sp); HlovetFormFeedback(dialogError) } }, confirmButton = { TextButton(onClick = { dispatchDelivery(target) }, enabled = !operating) { Text(if (busy) nativePhrase("提交中…", "Submitting…") else nativePhrase("确认发送", "Confirm send"), fontSize = 12.sp) } }, dismissButton = { TextButton(onClick = { confirmTest = false; replayTarget = null; dialogError = null }, enabled = !operating) { Text(nativePhrase("取消", "Cancel"), fontSize = 12.sp) } })
    }
    if (canAccess) selectedWebhook?.let { item ->
        NativeIntegrationDetail(nativePhrase("Webhook 详情", "Webhook details"), listOf(nativePhrase("名称", "Name") to item.name, "URL" to item.url, nativePhrase("事件", "Events") to item.events.joinToString("\n"), nativePhrase("状态", "Status") to if (item.enabled) nativePhrase("已启用", "Enabled") else nativePhrase("已停用", "Disabled"), nativePhrase("投递数量", "Delivery count") to item.deliveryCount.toString(), nativePhrase("最近成功", "Last delivered") to nativeIntegrationTime(item.lastDeliveredAt), nativePhrase("错误", "Error") to item.lastError, nativePhrase("创建时间", "Created") to nativeIntegrationTime(item.createdAt)), onClose = { selectedWebhook = null })
    }
    if (canAccess) selectedDelivery?.let { item ->
        NativeIntegrationDetail(nativePhrase("投递详情", "Delivery details"), listOf("ID" to item.id.toString(), "Webhook" to (webhooks.firstOrNull { it.id == item.endpointId }?.name ?: "#${item.endpointId}"), nativePhrase("事件", "Event") to item.eventType, nativePhrase("事件 ID", "Event ID") to item.eventId, nativePhrase("状态", "Status") to nativeIntegrationStatus(item.status), nativePhrase("尝试次数", "Attempts") to item.attempts.toString(), nativePhrase("创建时间", "Created") to nativeIntegrationTime(item.createdAt), nativePhrase("下次尝试", "Next attempt") to nativeIntegrationTime(item.nextAttemptAt), nativePhrase("投递成功", "Delivered") to nativeIntegrationTime(item.deliveredAt), nativePhrase("错误", "Error") to item.lastError), onClose = { selectedDelivery = null })
    }
}

@Composable
private fun NativeIntegrationField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit, password: Boolean = false) {
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = modifier, enabled = enabled, singleLine = true, label = { Text(label, fontSize = 10.sp) },
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = if (password) { { IconButton(onClick = { visible = !visible }, enabled = enabled) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) nativePhrase("隐藏密钥", "Hide secret") else nativePhrase("显示密钥", "Show secret"), Modifier.size(18.dp)) } } } else null,
        textStyle = TextStyle(fontSize = 12.sp), colors = HlovetUi.fieldColors())
}

@Composable
private fun NativeIntegrationDetail(title: String, fields: List<Pair<String, String>>, onClose: () -> Unit) {
    HlovetAlertDialog(onDismissRequest = onClose, title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("integration-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEach { (label, value) -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, color = HlovetUi.muted, fontSize = 10.sp); Text(value.ifBlank { nativePhrase("暂无", "None") }, fontSize = 12.sp) } }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("关闭", "Close"), fontSize = 12.sp) } })
}

private fun nativeIntegrationStatus(value: String): String = when (value) {
    "pending" -> nativePhrase("待投递", "Pending")
    "delivered" -> nativePhrase("已投递", "Delivered")
    "failed" -> nativePhrase("失败", "Failed")
    else -> value.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun nativeIntegrationTime(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull() ?: value.ifBlank { nativePhrase("暂无", "None") }
