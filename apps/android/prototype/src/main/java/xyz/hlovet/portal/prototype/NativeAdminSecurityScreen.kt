@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class NativeSecurityAdminTab(val apiValue: String) {
    Mail("mail"),
    Verification("verification"),
    Risk("risk"),
}

@Composable
internal fun NativeAdminSecurityScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    key(session?.accessToken, session?.isAdministrator, session?.isSuperAdmin) {
        NativeAdminSecurityContent(session, onBack)
    }
}

@Composable
private fun NativeAdminSecurityContent(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isAdministrator == true || session?.isSuperAdmin == true
    val canEdit = session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var config by remember(token) { mutableStateOf<NativeSecurityAdminConfig?>(null) }
    var draft by remember(token) { mutableStateOf<NativeSecurityAdminConfig?>(null) }
    var smtpPassword by remember(token) { mutableStateOf("") }
    var turnstileSecret by remember(token) { mutableStateOf("") }
    var portText by remember(token) { mutableStateOf("") }
    var thresholdText by remember(token) { mutableStateOf("") }
    var tab by remember(token) { mutableStateOf(NativeSecurityAdminTab.Mail) }
    var searchDraft by remember(token, tab) { mutableStateOf("") }
    var search by remember(token, tab) { mutableStateOf("") }
    var status by remember(token, tab) { mutableStateOf("") }
    var page by remember(token, tab) { mutableIntStateOf(1) }
    var records by remember(token, tab, page, search, status) { mutableStateOf<NativeSecurityAdminRecordPage?>(null) }
    var configReloadKey by remember(token) { mutableIntStateOf(0) }
    var recordsReloadKey by remember(token) { mutableIntStateOf(0) }
    var configLoading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var recordsLoading by remember(token, tab) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var saving by remember(token) { mutableStateOf(false) }
    var testing by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var configError by remember(token) { mutableStateOf<String?>(null) }
    var recordsError by remember(token, tab) { mutableStateOf<String?>(null) }
    var selectedRecord by remember { mutableStateOf<Pair<NativeSecurityAdminTab, NativeSecurityAdminRecord>?>(null) }

    fun hasUnsavedConfiguration(): Boolean = draft != config || smtpPassword.isNotBlank() || turnstileSecret.isNotBlank() ||
        portText != config?.smtpPort?.toString().orEmpty() || thresholdText != config?.loginFailureTurnstileThreshold?.toString().orEmpty()

    LaunchedEffect(searchDraft) {
        delay(280)
        val next = searchDraft.trim()
        if (next != search) {
            search = next
            page = 1
        }
    }

    LaunchedEffect(token, canAccess, configReloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            config = null
            draft = null
            configLoading = false
            return@LaunchedEffect
        }
        configLoading = true
        configError = null
        runCatching { withContext(Dispatchers.IO) { NativeAdminSecurityApi.getConfig(token) } }
            .onSuccess { loaded ->
                val preserveDraft = draft != null && hasUnsavedConfiguration()
                config = loaded
                if (!preserveDraft) {
                    draft = loaded
                    portText = loaded.smtpPort.toString()
                    thresholdText = loaded.loginFailureTurnstileThreshold.toString()
                } else {
                    draft = draft?.copy(smtpPasswordConfigured = loaded.smtpPasswordConfigured, turnstileSecretConfigured = loaded.turnstileSecretConfigured, encryptionConfigured = loaded.encryptionConfigured, updatedAt = loaded.updatedAt)
                }
            }
            .onFailure {
                if (it is CancellationException) throw it
                configError = it.message ?: nativePhrase("安全配置读取失败。", "Could not load security settings.")
            }
        configLoading = false
    }

    LaunchedEffect(token, canAccess, tab, page, search, status, recordsReloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            records = null
            recordsLoading = false
            return@LaunchedEffect
        }
        recordsLoading = true
        recordsError = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeAdminSecurityApi.listOverview(token, tab.apiValue, page, 10, search, status)
            }
        }.onSuccess {
            records = it
            if (it.page != page) page = it.page
        }.onFailure {
            if (it is CancellationException) throw it
            recordsError = it.message ?: nativePhrase("安全记录读取失败。", "Could not load security records.")
        }
        recordsLoading = false
    }

    fun saveConfiguration() {
        val accessToken = token ?: return
        val current = draft ?: return
        if (!canEdit || saving || testing || configLoading) return
        error = nativeSecurityDraftError(current, portText, thresholdText, smtpPassword, turnstileSecret)
        notice = null
        if (error != null) return
        saving = true
        error = null
        notice = null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    NativeAdminSecurityApi.updateConfig(
                        accessToken,
                        NativeSecurityAdminUpdate(
                            smtpEnabled = current.smtpEnabled,
                            smtpHost = current.smtpHost,
                            smtpPort = portText.toInt(),
                            smtpSecure = current.smtpSecure,
                            smtpUsername = current.smtpUsername,
                            smtpFromName = current.smtpFromName,
                            smtpFromEmail = current.smtpFromEmail,
                            registrationEmailVerificationEnabled = current.registrationEmailVerificationEnabled,
                            passwordRecoveryEnabled = current.passwordRecoveryEnabled,
                            untrustedDeviceEmailVerificationEnabled = current.untrustedDeviceEmailVerificationEnabled,
                            turnstileSiteKey = current.turnstileSiteKey,
                            turnstileRegistrationEnabled = current.turnstileRegistrationEnabled,
                            turnstileLoginEnabled = current.turnstileLoginEnabled,
                            turnstileRecoveryEnabled = current.turnstileRecoveryEnabled,
                            loginFailureTurnstileThreshold = thresholdText.toInt(),
                            smtpPassword = smtpPassword,
                            turnstileSecret = turnstileSecret,
                        ),
                    )
                }
                config = saved
                draft = saved
                portText = saved.smtpPort.toString()
                thresholdText = saved.loginFailureTurnstileThreshold.toString()
                smtpPassword = ""
                turnstileSecret = ""
                notice = nativePhrase("安全配置已保存。", "Security settings saved.")
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                error = exception.message ?: nativePhrase("安全配置保存失败。", "Could not save security settings.")
            } finally {
                saving = false
            }
        }
    }

    fun testSmtpConnection() {
        val accessToken = token ?: return
        if (!canEdit || testing || saving || configLoading || config?.smtpEnabled != true) return
        notice = null
        if (hasUnsavedConfiguration()) {
            error = nativePhrase("请先保存配置，再测试已保存的 SMTP 设置。", "Save your changes before testing the saved SMTP settings.")
            return
        }
        testing = true
        error = null
        notice = null
        scope.launch {
            try {
                notice = withContext(Dispatchers.IO) { NativeAdminSecurityApi.testSmtp(accessToken) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                error = exception.message ?: nativePhrase("SMTP 连接测试失败。", "SMTP connection test failed.")
            } finally {
                testing = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("security-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("安全管理", "Security"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("邮件、验证码和登录风险策略", "Email, verification, and sign-in risk policies"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { configReloadKey++; recordsReloadKey++ }, enabled = canAccess && !token.isNullOrBlank() && !configLoading && !recordsLoading && !saving && !testing) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (token.isNullOrBlank()) {
            item(key = "gate") { NativeSecurityMessage(nativePhrase("登录后查看安全管理。", "Sign in to view security settings."), false, null) }
        } else if (!canAccess) {
            item(key = "gate") { NativeSecurityMessage(nativePhrase("当前账号没有安全管理权限。", "This account cannot manage security settings."), true, null) }
        } else {
            if (configLoading && draft == null) item(key = "config-loading") { NativeSecurityMessage(nativePhrase("正在读取安全配置…", "Loading security settings…"), false, null) }
            if (configError != null) item(key = "config-error") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    HlovetFormFeedback(configError)
                    TextButton(onClick = { configReloadKey++ }, enabled = !configLoading && !saving && !testing) { Text(nativePhrase("重试配置", "Retry settings"), fontSize = 11.sp) }
                }
            }
            draft?.let { current ->
                item(key = "config") {
                    NativeSecurityConfigCard(current, canEdit && !saving && !testing && !configLoading, smtpPassword, turnstileSecret, portText, thresholdText,
                        { smtpPassword = it }, { turnstileSecret = it }, { portText = it }, { thresholdText = it }) { draft = it }
                }
                if (canEdit) {
                    item(key = "config-actions") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("security-actions")) {
                            HlovetFormFeedback(error, notice)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = ::testSmtpConnection, enabled = !testing && !saving && !configLoading && config?.smtpEnabled == true) {
                                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (testing) nativePhrase("测试中…", "Testing…") else nativePhrase("测试 SMTP", "Test SMTP"), fontSize = 11.sp)
                            }
                            TextButton(onClick = ::saveConfiguration, enabled = !testing && !saving && !configLoading) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (saving) nativePhrase("保存中…", "Saving…") else nativePhrase("保存配置", "Save settings"), fontSize = 11.sp)
                            }
                        }
                        }
                    }
                }
            }
            item(key = "records") {
                NativeSecurityRecordsCard(
                    tab = tab,
                    records = records,
                    searchDraft = searchDraft,
                    status = status,
                    loading = recordsLoading,
                    page = page,
                    error = recordsError,
                    onRetry = { recordsReloadKey++ },
                    onRecord = { selectedRecord = tab to it },
                    onTab = {
                        tab = it
                        page = 1
                        status = ""
                    },
                    onSearch = { searchDraft = it },
                    onStatus = { status = it; page = 1 },
                    onPage = { page = it },
                )
            }
        }
    }
    selectedRecord?.let { (recordTab, record) -> NativeSecurityRecordDetail(recordTab, record) { selectedRecord = null } }
}

@Composable
private fun NativeSecurityConfigCard(
    current: NativeSecurityAdminConfig,
    editable: Boolean,
    smtpPassword: String,
    turnstileSecret: String,
    portText: String,
    thresholdText: String,
    onSmtpPassword: (String) -> Unit,
    onTurnstileSecret: (String) -> Unit,
    onPort: (String) -> Unit,
    onThreshold: (String) -> Unit,
    onChange: (NativeSecurityAdminConfig) -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(nativePhrase("安全配置", "Security settings"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (current.encryptionConfigured) nativePhrase("密钥加密可用", "Credential encryption enabled") else nativePhrase("密钥加密未配置", "Credential encryption not configured"), color = if (current.encryptionConfigured) HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 9.sp, modifier = Modifier.widthIn(max = 130.dp))
            }
            NativeSecurityPanelTitle(Icons.Filled.Mail, nativePhrase("SMTP 邮件", "SMTP email"))
            NativeSecurityToggle(nativePhrase("启用邮件服务", "Enable email service"), current.smtpEnabled, editable) { checked -> onChange(current.copy(smtpEnabled = checked, registrationEmailVerificationEnabled = if (checked) current.registrationEmailVerificationEnabled else false, passwordRecoveryEnabled = if (checked) current.passwordRecoveryEnabled else false, untrustedDeviceEmailVerificationEnabled = if (checked) current.untrustedDeviceEmailVerificationEnabled else false, turnstileRecoveryEnabled = if (checked) current.turnstileRecoveryEnabled else false)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NativeSecurityField(nativePhrase("SMTP 主机", "SMTP host"), current.smtpHost, editable, Modifier.weight(1f).testTag("security-host"), onChange = { onChange(current.copy(smtpHost = it)) })
                NativeSecurityField(nativePhrase("端口", "Port"), portText, editable, Modifier.width(92.dp).testTag("security-port"), onChange = onPort, number = true)
            }
            NativeSecurityField(nativePhrase("账号", "Username"), current.smtpUsername, editable, Modifier.fillMaxWidth(), onChange = { onChange(current.copy(smtpUsername = it)) })
            NativeSecurityField(if (current.smtpPasswordConfigured) nativePhrase("密码（已配置，留空不变）", "Password (configured; leave blank to keep)") else nativePhrase("密码", "Password"), smtpPassword, editable && current.encryptionConfigured, Modifier.fillMaxWidth().testTag("security-password"), onChange = onSmtpPassword, password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NativeSecurityField(nativePhrase("发件名称", "Sender name"), current.smtpFromName, editable, Modifier.weight(1f), onChange = { onChange(current.copy(smtpFromName = it)) })
                NativeSecurityField(nativePhrase("发件邮箱", "Sender email"), current.smtpFromEmail, editable, Modifier.weight(1f), onChange = { onChange(current.copy(smtpFromEmail = it)) })
            }
            NativeSecurityToggle(nativePhrase("使用 TLS 安全连接", "Use a secure TLS connection"), current.smtpSecure, editable) { onChange(current.copy(smtpSecure = it)) }
            NativeSecurityPanelTitle(Icons.Filled.Shield, nativePhrase("验证策略", "Verification policies"))
            NativeSecurityToggle(nativePhrase("注册邮箱验证", "Registration email verification"), current.registrationEmailVerificationEnabled, editable && current.smtpEnabled) { onChange(current.copy(registrationEmailVerificationEnabled = it)) }
            NativeSecurityToggle(nativePhrase("密码找回", "Password recovery"), current.passwordRecoveryEnabled, editable && current.smtpEnabled) { onChange(current.copy(passwordRecoveryEnabled = it)) }
            NativeSecurityToggle(nativePhrase("非信任设备邮箱验证", "Email verification for untrusted devices"), current.untrustedDeviceEmailVerificationEnabled, editable && current.smtpEnabled) { onChange(current.copy(untrustedDeviceEmailVerificationEnabled = it)) }
            NativeSecurityField("Turnstile Site Key", current.turnstileSiteKey, editable, Modifier.fillMaxWidth().testTag("security-site-key"), onChange = { onChange(current.copy(turnstileSiteKey = it)) })
            NativeSecurityField(if (current.turnstileSecretConfigured) nativePhrase("Turnstile Secret（已配置，留空不变）", "Turnstile secret (configured; leave blank to keep)") else "Turnstile Secret", turnstileSecret, editable && current.encryptionConfigured, Modifier.fillMaxWidth().testTag("security-secret"), onChange = onTurnstileSecret, password = true)
            NativeSecurityField(nativePhrase("登录失败触发次数（1-5）", "Failed sign-in threshold (1-5)"), thresholdText, editable, Modifier.fillMaxWidth().testTag("security-threshold"), onChange = onThreshold, number = true)
            NativeSecurityToggle(nativePhrase("Turnstile 注册验证", "Turnstile for registration"), current.turnstileRegistrationEnabled, editable) { onChange(current.copy(turnstileRegistrationEnabled = it)) }
            NativeSecurityToggle(nativePhrase("Turnstile 登录失败验证", "Turnstile after failed sign-ins"), current.turnstileLoginEnabled, editable) { onChange(current.copy(turnstileLoginEnabled = it)) }
            NativeSecurityToggle(nativePhrase("Turnstile 密码找回验证", "Turnstile for password recovery"), current.turnstileRecoveryEnabled, editable && current.smtpEnabled) { onChange(current.copy(turnstileRecoveryEnabled = it)) }
        }
    }
}

@Composable
private fun NativeSecurityPanelTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NativeSecurityToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f), color = if (enabled) HlovetUi.foreground else HlovetUi.muted)
        HlovetSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun NativeSecurityField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit, password: Boolean = false, number: Boolean = false) {
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        modifier = modifier,
        singleLine = true,
        label = { Text(label, fontSize = 10.sp) },
        visualTransformation = if (password && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = if (password) { { IconButton(onClick = { visible = !visible }, enabled = enabled) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (visible) nativePhrase("隐藏密钥", "Hide secret") else nativePhrase("显示密钥", "Show secret"), modifier = Modifier.size(18.dp)) } } } else null,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        colors = nativeSecurityFieldColors(),
    )
}

/** 根据 tab 展示 records 的分页记录，searchDraft/status 为筛选值；loading/error 控制反馈，on* 回调处理查询、重试和详情。 */
@Composable
private fun NativeSecurityRecordsCard(
    tab: NativeSecurityAdminTab,
    records: NativeSecurityAdminRecordPage?,
    searchDraft: String,
    status: String,
    loading: Boolean,
    page: Int,
    error: String?,
    onRetry: () -> Unit,
    onRecord: (NativeSecurityAdminRecord) -> Unit,
    onTab: (NativeSecurityAdminTab) -> Unit,
    onSearch: (String) -> Unit,
    onStatus: (String) -> Unit,
    onPage: (Int) -> Unit,
) {
    // 邮件、验证码和风险使用各自状态字典，切换标签时关闭旧筛选弹层。
    var menuOpen by remember(tab) { mutableStateOf(false) }
    val statusOptions = when (tab) {
        NativeSecurityAdminTab.Mail -> listOf("" to nativePhrase("全部状态", "All statuses"), "pending" to nativePhrase("待发送", "Pending"), "sending" to nativePhrase("发送中", "Sending"), "sent" to nativePhrase("已发送", "Sent"), "failed" to nativePhrase("发送失败", "Failed"))
        NativeSecurityAdminTab.Verification -> listOf("" to nativePhrase("全部状态", "All statuses"), "pending" to nativePhrase("待验证", "Pending verification"), "verified" to nativePhrase("已验证", "Verified"), "consumed" to nativePhrase("已使用", "Consumed"), "expired" to nativePhrase("已过期", "Expired"))
        NativeSecurityAdminTab.Risk -> listOf("" to nativePhrase("全部等级", "All levels"), "info" to nativePhrase("信息", "Info"), "low" to nativePhrase("低风险", "Low"), "medium" to nativePhrase("中风险", "Medium"), "high" to nativePhrase("高风险", "High"))
    }
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NativeSecurityTabButton(nativePhrase("邮件任务", "Mail jobs"), tab == NativeSecurityAdminTab.Mail) { onTab(NativeSecurityAdminTab.Mail) }
                NativeSecurityTabButton(nativePhrase("验证码", "Verification codes"), tab == NativeSecurityAdminTab.Verification) { onTab(NativeSecurityAdminTab.Verification) }
                NativeSecurityTabButton(nativePhrase("风险事件", "Risk events"), tab == NativeSecurityAdminTab.Risk) { onTab(NativeSecurityAdminTab.Risk) }
                Spacer(Modifier.width(6.dp))
                Text(nativePhrase("${records?.total ?: "-"} 条", "${records?.total ?: "-"} records"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = searchDraft, onValueChange = onSearch, modifier = Modifier.weight(1f).testTag("security-search"), singleLine = true, placeholder = { Text(securitySearchPlaceholder(tab), fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp)) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp), colors = nativeSecurityFieldColors())
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("security-status")) { Text(statusOptions.firstOrNull { it.first == status }?.second ?: status.ifBlank { nativePhrase("全部", "All") }, fontSize = 10.sp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                        statusOptions.forEach { (value, label) -> DropdownMenuItem(text = { Text(label, fontSize = 11.sp) }, onClick = { onStatus(value); menuOpen = false }) }
                    }
                }
            }
            // 查询失败保留筛选输入并提供重试，不能把请求失败误显示为零条记录。
            if (error != null) {
                HlovetFormFeedback(error)
                TextButton(onClick = onRetry, enabled = !loading) { Text(nativePhrase("重试记录", "Retry records"), fontSize = 11.sp) }
            }
            if (loading) {
                Text(nativePhrase("正在读取安全记录…", "Loading security records…"), fontSize = 11.sp, color = HlovetUi.muted)
            } else if (records?.items.isNullOrEmpty() && error == null) {
                Text(nativePhrase("暂无匹配记录。", "No matching records."), fontSize = 11.sp, color = HlovetUi.muted)
            } else {
                records?.items?.forEach { record ->
                    NativeSecurityRecordRow(tab, record) { onRecord(record) }
                    HorizontalDivider(color = HlovetUi.outline.copy(alpha = 0.35f))
                }
            }
            // 仅在有分页元数据时显示页码，翻页继续由父页面按当前筛选请求。
            if (records != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onPage(page - 1) }, enabled = page > 1 && !loading) { Text(nativePhrase("上一页", "Previous"), fontSize = 10.sp) }
                    Text(nativePhrase("第 $page / ${records.totalPages} 页", "Page $page / ${records.totalPages}"), color = HlovetUi.muted, fontSize = 10.sp)
                    TextButton(onClick = { onPage(page + 1) }, enabled = page < records.totalPages && !loading) { Text(nativePhrase("下一页", "Next"), fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
private fun NativeSecurityTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = if (selected) HlovetUi.accent else HlovetUi.muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun NativeSecurityRecordRow(tab: NativeSecurityAdminTab, record: NativeSecurityAdminRecord, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().testTag("security-record-${record.id}").clickable(onClick = onClick).padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tab) {
                NativeSecurityAdminTab.Mail -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Mail, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(record.recipient.ifBlank { record.email.ifBlank { nativePhrase("未记录收件人", "Recipient not recorded") } }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NativeSecurityBadge(record.status)
                    }
                    Text("${securityMailType(record.type)} · ${record.subject.ifBlank { nativePhrase("无主题", "No subject") }}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (record.sentAt.isNotBlank()) nativePhrase("发送于 ${securityDate(record.sentAt)}", "Sent ${securityDate(record.sentAt)}") else nativePhrase("${record.attempts} 次尝试${if (record.lastError.isNotBlank()) " · ${record.lastError}" else ""}", "${record.attempts} attempts${if (record.lastError.isNotBlank()) " · ${record.lastError}" else ""}"), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(securityDate(record.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
                }
                NativeSecurityAdminTab.Verification -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(record.email.ifBlank { nativePhrase("未记录邮箱", "Email not recorded") }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NativeSecurityBadge(record.status)
                    }
                    Text(nativePhrase("${securityPurpose(record.purpose)} · ${record.attempts} 次尝试", "${securityPurpose(record.purpose)} · ${record.attempts} attempts"), color = HlovetUi.muted, fontSize = 10.sp)
                    Text(nativePhrase("有效至 ${securityDate(record.expiresAt)}${if (record.verifiedAt.isNotBlank() || record.consumedAt.isNotBlank()) " · 完成于 ${securityDate(record.consumedAt.ifBlank { record.verifiedAt })}" else ""}", "Expires ${securityDate(record.expiresAt)}${if (record.verifiedAt.isNotBlank() || record.consumedAt.isNotBlank()) " · Completed ${securityDate(record.consumedAt.ifBlank { record.verifiedAt })}" else ""}"), color = HlovetUi.muted, fontSize = 9.sp)
                    Text(securityDate(record.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
                }
                NativeSecurityAdminTab.Risk -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (record.riskLevel == "high" || record.riskLevel == "medium") Icons.Filled.Warning else Icons.Filled.ErrorOutline, contentDescription = null, tint = if (record.riskLevel == "high") HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(record.userNickname.ifBlank { nativePhrase("未知用户", "Unknown user") }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        NativeSecurityBadge(record.riskLevel)
                    }
                    Text("${securityType(record.type)}${if (record.userUsername.isNotBlank()) " · @${record.userUsername}" else ""}", color = HlovetUi.foreground, fontSize = 10.sp)
                    Text(record.deviceLabel.ifBlank { nativePhrase("未知设备", "Unknown device") } + " · " + record.ip.ifBlank { nativePhrase("IP 未记录", "IP not recorded") }, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (record.summary.isNotBlank()) Text(record.summary, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(securityDate(record.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
                }
            }
        }
}

@Composable
private fun NativeSecurityRecordDetail(tab: NativeSecurityAdminTab, record: NativeSecurityAdminRecord, onClose: () -> Unit) {
    val fields = buildList {
        add(nativePhrase("创建时间", "Created") to securityDate(record.createdAt))
        when (tab) {
            NativeSecurityAdminTab.Mail -> {
                add(nativePhrase("收件人", "Recipient") to record.recipient.ifBlank { record.email })
                add(nativePhrase("类型", "Type") to securityMailType(record.type))
                add(nativePhrase("主题", "Subject") to record.subject)
                add(nativePhrase("状态", "Status") to securityStatus(record.status))
                add(nativePhrase("尝试次数", "Attempts") to record.attempts.toString())
                if (record.sentAt.isNotBlank()) add(nativePhrase("发送时间", "Sent") to securityDate(record.sentAt))
                if (record.lastError.isNotBlank()) add(nativePhrase("发送错误", "Delivery error") to record.lastError)
            }
            NativeSecurityAdminTab.Verification -> {
                add(nativePhrase("邮箱", "Email") to record.email)
                add(nativePhrase("用途", "Purpose") to securityPurpose(record.purpose))
                add(nativePhrase("状态", "Status") to securityStatus(record.status))
                add(nativePhrase("尝试次数", "Attempts") to record.attempts.toString())
                add(nativePhrase("有效至", "Expires") to securityDate(record.expiresAt))
                if (record.verifiedAt.isNotBlank()) add(nativePhrase("验证时间", "Verified") to securityDate(record.verifiedAt))
                if (record.consumedAt.isNotBlank()) add(nativePhrase("使用时间", "Used") to securityDate(record.consumedAt))
                if (record.ip.isNotBlank()) add("IP" to record.ip)
            }
            NativeSecurityAdminTab.Risk -> {
                add(nativePhrase("用户", "User") to (record.userNickname.ifBlank { nativePhrase("未知用户", "Unknown user") } + if (record.userUsername.isNotBlank()) " · @${record.userUsername}" else ""))
                add(nativePhrase("事件", "Event") to securityType(record.type))
                add(nativePhrase("风险等级", "Risk level") to securityStatus(record.riskLevel))
                add(nativePhrase("设备", "Device") to record.deviceLabel)
                add("IP" to record.ip)
                if (record.summary.isNotBlank()) add(nativePhrase("说明", "Summary") to record.summary)
            }
        }
        if (record.updatedAt.isNotBlank()) add(nativePhrase("更新时间", "Updated") to securityDate(record.updatedAt))
    }
    HlovetAlertDialog(onDismissRequest = onClose, title = { Text(nativePhrase("安全记录详情", "Security record details"), fontSize = 17.sp) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("security-record-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEach { (label, value) -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 10.sp, color = HlovetUi.muted)
                Text(value.ifBlank { "-" }, fontSize = 12.sp, color = HlovetUi.foreground)
            } }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("关闭", "Close"), fontSize = 11.sp) } })
}

@Composable
private fun NativeSecurityBadge(value: String) {
    val danger = value == "failed" || value == "high"
    Text(securityStatus(value), color = if (danger) HlovetUi.secondaryAccent else HlovetUi.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NativeSecurityMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.Warning else Icons.Filled.Shield, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Dismiss"), fontSize = 10.sp) } }
        }
    }
}

private fun securitySearchPlaceholder(tab: NativeSecurityAdminTab): String = when (tab) {
    NativeSecurityAdminTab.Mail -> nativePhrase("收件人、主题或错误", "Recipient, subject, or error")
    NativeSecurityAdminTab.Verification -> nativePhrase("邮箱或 IP", "Email or IP")
    NativeSecurityAdminTab.Risk -> nativePhrase("用户、事件、设备或 IP", "User, event, device, or IP")
}

private fun securityStatus(value: String): String = mapOf("pending" to nativePhrase("待处理", "Pending"), "sending" to nativePhrase("发送中", "Sending"), "sent" to nativePhrase("已发送", "Sent"), "failed" to nativePhrase("失败", "Failed"), "verified" to nativePhrase("已验证", "Verified"), "consumed" to nativePhrase("已使用", "Used"), "expired" to nativePhrase("已过期", "Expired"), "info" to nativePhrase("信息", "Info"), "low" to nativePhrase("低风险", "Low risk"), "medium" to nativePhrase("中风险", "Medium risk"), "high" to nativePhrase("高风险", "High risk"))[value] ?: value.ifBlank { nativePhrase("未记录", "Not recorded") }

private fun securityMailType(value: String): String = mapOf("registration_verification" to nativePhrase("注册验证", "Registration verification"), "account_email_verification" to nativePhrase("邮箱验证", "Email verification"), "device_login_verification" to nativePhrase("设备登录验证", "Device sign-in verification"), "password_reset" to nativePhrase("密码找回", "Password recovery"), "login_risk" to nativePhrase("登录风险", "Sign-in risk"), "security_notice" to nativePhrase("安全通知", "Security notice"), "subscription_digest" to nativePhrase("订阅日报", "Subscription digest"))[value] ?: value.ifBlank { nativePhrase("邮件", "Email") }

private fun securityPurpose(value: String): String = mapOf("registration" to nativePhrase("注册验证", "Registration verification"), "account_email" to nativePhrase("账号邮箱", "Account email"), "device_login" to nativePhrase("设备登录验证", "Device sign-in verification"), "totp_disable" to nativePhrase("解除双因素认证", "Disable two-factor authentication"), "passkey_delete" to nativePhrase("移除通行密钥", "Remove passkey"), "sensitive_action" to nativePhrase("敏感操作验证", "Sensitive-action verification"))[value] ?: value.ifBlank { nativePhrase("验证码", "Verification code") }

private fun securityType(value: String): String = mapOf("login_success" to nativePhrase("登录成功", "Sign-in succeeded"), "new_device" to nativePhrase("新设备登录", "New-device sign-in"), "new_ip" to nativePhrase("陌生 IP 登录", "Unfamiliar IP sign-in"), "unusual_frequency" to nativePhrase("异常登录频率", "Unusual sign-in frequency"), "login_blocked" to nativePhrase("登录已限制", "Sign-in blocked"), "password_reset" to nativePhrase("密码已重置", "Password reset"), "password_changed" to nativePhrase("密码已修改", "Password changed"), "email_verified" to nativePhrase("邮箱已验证", "Email verified"))[value] ?: value.ifBlank { nativePhrase("安全事件", "Security event") }

private fun securityDate(value: String): String {
    if (value.isBlank()) return "-"
    return runCatching {
        Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrElse { value.take(16).replace('T', ' ') }
}

@Composable
private fun nativeSecurityFieldColors() = HlovetUi.fieldColors()
