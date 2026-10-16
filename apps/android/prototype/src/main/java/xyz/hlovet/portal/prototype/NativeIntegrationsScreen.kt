@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

private val integrationScopes = listOf("read_articles", "read_profile", "read_notifications")

/** 保存当前账号待验证的 Google 关联请求，不跨账号复用。 */
private data class NativePendingGoogleLink(
    val pendingToken: String,
    val email: String,
    val methods: GoogleLinkMethods,
)

/** 按账号和会话隔离集成页面，切换时销毁旧协程、弹窗与一次性令牌。 */
@Composable
internal fun NativeIntegrationsScreen(
    session: NativeSession?,
    oauthResultToken: String? = null,
    oauthErrorMessage: String? = null,
    onOAuthResultConsumed: () -> Unit = {},
    onBack: () -> Unit,
) {
    // 会话刷新或退出后不能沿用旧账号的验证请求、明文密钥或异步回包。
    key(session?.userId, session?.accessToken) {
        NativeIntegrationsContent(session, oauthResultToken, oauthErrorMessage, onOAuthResultConsumed, onBack)
    }
}

/** 管理当前会话的只读令牌、通知通道及 Google 关联，写入成功后立即更新本地列表。 */
@Composable
private fun NativeIntegrationsContent(
    session: NativeSession?,
    oauthResultToken: String?,
    oauthErrorMessage: String?,
    onOAuthResultConsumed: () -> Unit,
    onBack: () -> Unit,
) {
    // 所有状态和任务均属于外层 key 确定的单一会话。
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var tokens by remember(token) { mutableStateOf<List<NativeReadOnlyToken>>(emptyList()) }
    var channels by remember(token) { mutableStateOf<List<NativeExternalChannel>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(true) }
    var busy by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var tokenLoadError by remember { mutableStateOf<String?>(null) }
    var channelLoadError by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var tokenDialog by remember { mutableStateOf(false) }
    var channelDialog by remember { mutableStateOf(false) }
    var tokenName by remember { mutableStateOf("") }
    var tokenExpiry by remember { mutableStateOf("") }
    var tokenScopes by remember { mutableStateOf(listOf("read_articles")) }
    var endpoint by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var verifyChannelId by remember { mutableIntStateOf(0) }
    var verifyCode by remember { mutableStateOf("") }
    var createdToken by remember { mutableStateOf<NativeCreatedReadOnlyToken?>(null) }
    var deleteChannel by remember { mutableStateOf<NativeExternalChannel?>(null) }
    var deleteToken by remember { mutableStateOf<NativeReadOnlyToken?>(null) }
    var googleAvailable by remember(token) { mutableStateOf<Boolean?>(null) }
    var googleBusy by remember(token) { mutableStateOf(false) }
    var pendingGoogleLink by remember(token) { mutableStateOf<NativePendingGoogleLink?>(null) }
    var googleVerificationStep by remember(token) { mutableStateOf("choose") }
    var googleCode by remember(token) { mutableStateOf("") }
    var googlePassword by remember(token) { mutableStateOf("") }
    var googleEmailChallenge by remember(token) { mutableStateOf("") }
    var googleCooldown by remember(token) { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }

    /** 分别读取两类数据；保留读取成功的一类和刷新前的内容，失败可独立反馈。 */
    suspend fun load() {
        // 未登录不访问私有接口，避免把空列表误报为已成功读取。
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后管理外部集成。", "Sign in to manage integrations.")
            return
        }
        coroutineScope {
            loading = true
            tokenLoadError = null
            channelLoadError = null
            try {
                // 两个子任务均受 LaunchedEffect 生命周期控制，旧会话取消不能写入新页面。
                val tokenRead = launch {
                    runCatching { withContext(Dispatchers.IO) { NativeIntegrationsApi.listTokens(currentToken) } }
                        .onSuccess { tokens = it }
                        .onFailure {
                            coroutineContext.ensureActive()
                            tokenLoadError = it.message ?: nativePhrase("令牌读取失败，请刷新重试。", "Could not load tokens. Refresh to retry.")
                        }
                }
                val channelRead = launch {
                    runCatching { withContext(Dispatchers.IO) { NativeIntegrationsApi.listChannels(currentToken) } }
                        .onSuccess { channels = it }
                        .onFailure {
                            coroutineContext.ensureActive()
                            channelLoadError = it.message ?: nativePhrase("通知通道读取失败，请刷新重试。", "Could not load channels. Refresh to retry.")
                        }
                }
                tokenRead.join()
                channelRead.join()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(token, reloadKey) { load() }

    LaunchedEffect(token) {
        if (token.isNullOrBlank()) { googleAvailable = false; return@LaunchedEffect }
        googleAvailable = runCatching {
            withContext(Dispatchers.IO) { NativeAuthApi.externalAuthProviders() }
        }.getOrDefault(false)
    }

    LaunchedEffect(googleCooldown) {
        if (googleCooldown > 0) {
            kotlinx.coroutines.delay(1000)
            googleCooldown = (googleCooldown - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(oauthResultToken, oauthErrorMessage) {
        if (oauthResultToken == null && oauthErrorMessage.isNullOrBlank()) return@LaunchedEffect
        val resultToken = oauthResultToken.orEmpty()
        onOAuthResultConsumed()
        if (!oauthErrorMessage.isNullOrBlank()) {
            error = oauthErrorMessage
            return@LaunchedEffect
        }
        val currentSession = session
        if (resultToken.isBlank() || currentSession == null) {
            error = nativePhrase("Google 绑定回调无效，请重新开始。", "The Google linking callback is invalid. Start again.")
            return@LaunchedEffect
        }
        googleBusy = true
        error = null
        runCatching { withContext(Dispatchers.IO) { NativeAuthApi.consumeOAuthResult(resultToken) } }
            .onSuccess { result ->
                when (result) {
                    is NativeAuthResult.GoogleLinkRequired -> {
                        pendingGoogleLink = NativePendingGoogleLink(result.pendingToken, result.email, result.methods)
                        googleVerificationStep = "choose"
                        googleCode = ""
                        googlePassword = ""
                        googleEmailChallenge = ""
                    }
                    is NativeAuthResult.Authenticated -> {
                        if (result.session.userId == currentSession.userId) {
                            notice = nativePhrase("这个 Google 账号已经绑定当前账号。", "This Google account is already linked to the current account.")
                        } else {
                            error = nativePhrase("这个 Google 账号已绑定其他站内账号，未切换当前登录状态。", "This Google account belongs to another site account. Your current session was not changed.")
                        }
                    }
                    is NativeAuthResult.DeviceVerification,
                    is NativeAuthResult.TotpVerification -> {
                        error = nativePhrase("这个 Google 账号已经绑定站内账号，请从登录页完成登录验证。", "This Google account is already linked. Complete sign-in verification from the login page.")
                    }
                }
            }
            .onFailure { error = it.message ?: nativePhrase("Google 绑定结果读取失败。", "Could not read the Google linking result.") }
        googleBusy = false
    }

    /** 清空本次 Google 验证步骤及临时凭据，不影响已经关联的身份。 */
    fun resetGoogleVerification() {
        // 返回选择页时不复用上一种方式的验证码或密码。
        googleVerificationStep = "choose"
        googleCode = ""
        googlePassword = ""
        googleEmailChallenge = ""
        googleCooldown = 0
    }

    /** 放弃待关联请求并给出未执行绑定的反馈。 */
    fun cancelGoogleLink() {
        // 只清理本地请求，不解绑已有 Google 身份。
        pendingGoogleLink = null
        resetGoogleVerification()
        notice = nativePhrase("本次未绑定 Google 账号。", "Google account was not linked.")
    }

    /** 在外部浏览器启动当前账号的 Google 关联，不改变当前登录会话。 */
    fun startGoogleLink() {
        // 防止双击产生多个浏览器授权流程。
        if (googleBusy) return
        googleBusy = true
        error = null
        scope.launch {
            try {
                val authorizationUrl = withContext(Dispatchers.IO) { NativeAuthApi.startGoogleLogin("/profile/integrations", linkOnly = true) }
                coroutineContext.ensureActive()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
            } catch (exception: ActivityNotFoundException) {
                error = nativePhrase("设备没有可用的浏览器，无法打开 Google 绑定。", "No browser is available to open Google linking.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("Google 绑定暂不可用，请稍后重试。", "Google linking is unavailable. Try again later.")
            } finally {
                googleBusy = false
            }
        }
    }

    /** 使用 verificationToken 消费当前待关联请求，成功后销毁临时凭据。 */
    suspend fun completeGoogleLink(verificationToken: String) {
        // 关联必须同时拥有当前会话及本次安全验证，不能将 Google 邮箱直接当作凭据。
        val currentToken = token ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
        val pending = pendingGoogleLink ?: throw IllegalStateException(nativePhrase("Google 绑定请求已失效。", "The Google linking request is no longer available."))
        withContext(Dispatchers.IO) {
            NativeIntegrationsApi.bindGoogleIdentity(currentToken, pending.pendingToken, verificationToken)
        }
        pendingGoogleLink = null
        resetGoogleVerification()
        notice = nativePhrase("Google 账号已绑定。", "Google account linked.")
    }

    /** 获取系统 Passkey 凭据并完成当前 Google 关联的安全验证。 */
    fun verifyGooglePasskey() {
        // 保留系统验证流程，取消或失败时允许用户选择其他方式。
        val currentToken = token ?: return
        if (googleBusy || pendingGoogleLink == null) return
        googleVerificationStep = "passkey"
        googleBusy = true
        error = null
        scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { NativePrivacyApi.sensitivePasskeyOptions(currentToken, "google_account_link") }
                val result = withTimeout(20_000L) {
                    credentialManager.getCredential(
                        context = context,
                        request = GetCredentialRequest(credentialOptions = listOf(GetPublicKeyCredentialOption(options.requestJson))),
                    )
                }
                val credential = result.credential as? PublicKeyCredential
                    ?: throw IllegalStateException(nativePhrase("未返回通行密钥凭据。", "No passkey credential was returned."))
                val verificationToken = withContext(Dispatchers.IO) {
                    NativePrivacyApi.verifySensitivePasskey(currentToken, "google_account_link", options.challengeToken, credential.authenticationResponseJson)
                }
                completeGoogleLink(verificationToken)
            } catch (exception: GetCredentialException) {
                error = exception.message ?: nativePhrase("通行密钥验证失败，请重试。", "Passkey verification failed. Try again.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("Google 绑定验证失败，请重试。", "Google linking verification failed. Try again.")
            } finally {
                googleBusy = false
            }
        }
    }

    /** 向当前账号邮箱发送安全验证码，并记录服务端重发冷却时间。 */
    fun requestGoogleEmail() {
        // 每次重发清空旧验证码，避免自动提交过期内容。
        val currentToken = token ?: return
        if (googleBusy || pendingGoogleLink == null) return
        googleVerificationStep = "email"
        googleCode = ""
        googleBusy = true
        error = null
        scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { NativePrivacyApi.requestSensitiveEmail(currentToken, "google_account_link") }
                googleEmailChallenge = challenge.challengeToken
                googleCooldown = challenge.retryAfterSeconds.coerceAtLeast(1)
                notice = nativePhrase("验证码已发送到当前账号邮箱。", "A verification code was sent to the current account email.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("验证码发送失败，请稍后重试。", "Could not send the verification code. Try again later.")
            } finally {
                googleBusy = false
            }
        }
    }

    /** 验证 code 对应的邮箱或 TOTP 凭据，六位输入完成后自动提交。 */
    fun verifyGoogleCode(code: String) {
        // 邮箱必须先取得 challenge，TOTP 不复用邮箱 challenge。
        val currentToken = token ?: return
        if (pendingGoogleLink == null) return
        if (code.length != 6 || googleBusy || (googleVerificationStep == "email" && googleEmailChallenge.isBlank())) return
        googleBusy = true
        error = null
        scope.launch {
            try {
                val verificationToken = withContext(Dispatchers.IO) {
                    if (googleVerificationStep == "email") {
                        NativePrivacyApi.verifySensitiveEmail(currentToken, "google_account_link", googleEmailChallenge, code)
                    } else {
                        NativePrivacyApi.verifySensitiveTotp(currentToken, "google_account_link", code)
                    }
                }
                completeGoogleLink(verificationToken)
            } catch (exception: Exception) {
                googleCode = ""
                error = exception.message ?: nativePhrase("验证码验证失败，请检查后重试。", "Code verification failed. Check it and try again.")
            } finally {
                googleBusy = false
            }
        }
    }

    /** 验证当前密码后关联 Google，错误时保留输入供用户更正。 */
    fun verifyGooglePassword() {
        // 密码仅用于当前安全验证，不用于切换账号。
        val currentToken = token ?: return
        if (googlePassword.isBlank() || googleBusy || pendingGoogleLink == null) return
        googleBusy = true
        error = null
        scope.launch {
            try {
                val verificationToken = withContext(Dispatchers.IO) {
                    NativePrivacyApi.verifySensitivePassword(currentToken, "google_account_link", googlePassword)
                }
                completeGoogleLink(verificationToken)
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("密码验证失败，请检查密码后重试。", "Password verification failed. Check it and try again.")
            } finally {
                googleBusy = false
            }
        }
    }

    /** 校验令牌草稿并创建；完整令牌只保留在本次成功弹窗。 */
    fun submitToken() {
        // 在请求前校验权限和日期；同步上锁防止双击重复创建。
        val currentToken = token ?: return
        if (busy || loading) return
        val expiry = runCatching { normalizeIntegrationExpiry(tokenExpiry) }.getOrElse {
            dialogError = nativePhrase("请输入晚于当前时间的有效日期，例如 2026-12-31T23:59:59Z。", "Enter a valid future date, for example 2026-12-31T23:59:59Z.")
            return
        }
        if (tokenName.trim().isBlank() || tokenName.trim().length > 120 || tokenScopes.isEmpty()) {
            dialogError = nativePhrase("请填写 1–120 字名称并至少选择一种权限。", "Enter a name of 1–120 characters and choose at least one scope.")
            return
        }
        busy = true
        dialogError = null
        scope.launch {
            // 成功响应直接写入列表，后续刷新失败也不会丢掉创建结果。
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeIntegrationsApi.createToken(currentToken, tokenName, tokenScopes, expiry)
                }
            }.onSuccess {
                createdToken = it
                tokens = listOf(NativeReadOnlyToken(it.id, it.name, it.tokenPrefix, it.scopes, it.expiresAt, null, null, it.createdAt)) + tokens.filterNot { row -> row.id == it.id }
                tokenDialog = false
                tokenName = ""
                tokenExpiry = ""
                tokenScopes = listOf("read_articles")
            }.onFailure { dialogError = it.message ?: nativePhrase("只读令牌创建失败。", "Failed to create the read-only token.") }
            busy = false
        }
    }

    /** 校验 HTTPS 通道并发送验证消息；失败保留草稿，成功后进入六位码验证。 */
    fun submitChannel() {
        // 只接受带主机的 HTTPS 地址，不能把明显无效的地址提交给服务器投递。
        val currentToken = token ?: return
        if (busy || loading) return
        val uri = runCatching { java.net.URI(endpoint.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || endpoint.trim().length > 512) {
            dialogError = nativePhrase("请输入有效的 HTTPS 通知地址。", "Enter a valid HTTPS notification URL.")
            return
        }
        busy = true
        dialogError = null
        scope.launch {
            // 验证消息发送失败仍停留原弹窗，不能只在被遮挡的页面显示错误。
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeIntegrationsApi.createChannel(currentToken, endpoint, secret.takeIf(String::isNotBlank))
                }
            }.onSuccess {
                channelDialog = false
                endpoint = ""
                secret = ""
                channels = listOf(it.channel) + channels.filterNot { row -> row.id == it.channel.id }
                if (it.verificationRequired) {
                    verifyChannelId = it.channel.id
                    verifyCode = ""
                    notice = nativePhrase("验证消息已发送，请输入外部通道收到的 6 位验证码。", "Verification sent. Enter the 6-digit code delivered to the external channel.")
                } else {
                    notice = nativePhrase("外部通知通道已创建。", "External notification channel created.")
                }
            }.onFailure { dialogError = it.message ?: nativePhrase("外部通道创建失败。", "Failed to create the external channel.") }
            busy = false
        }
    }

    /** 用 code 验证选中通道，成功后以服务端返回状态更新列表。 */
    fun verifyChannelCode(code: String) {
        // 输入满六位只提交一次；失败清空验证码并在原弹窗提示。
        val currentToken = token ?: return
        if (verifyChannelId == 0 || code.length != 6 || busy) return
        busy = true
        dialogError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeIntegrationsApi.verifyChannel(currentToken, verifyChannelId, code) }
            }.onSuccess {
                channels = channels.map { row -> if (row.id == it.id) it else row }
                verifyChannelId = 0
                verifyCode = ""
                notice = nativePhrase("外部通知通道已验证并启用。", "External notification channel verified and enabled.")
            }.onFailure {
                verifyCode = ""
                dialogError = it.message ?: nativePhrase("通道验证码不正确。", "The channel verification code is incorrect.")
            }
            busy = false
        }
    }

    /** 将 channel 切换到 enabled，以服务端确认结果为准，不提前伪造成功状态。 */
    fun toggleChannel(channel: NativeExternalChannel, enabled: Boolean) {
        // 未验证通道不能启用，加载或变更中不发出竞争请求。
        val currentToken = token ?: return
        if (!channel.verified || busy || loading) return
        busy = true
        scope.launch {
            error = null
            runCatching {
                withContext(Dispatchers.IO) { NativeIntegrationsApi.updateChannel(currentToken, channel.id, enabled) }
            }.onSuccess { updated ->
                channels = channels.map { if (it.id == updated.id) updated else it }
                notice = if (enabled) nativePhrase("外部通知通道已启用。", "External notification channel enabled.") else nativePhrase("外部通知通道已停用。", "External notification channel disabled.")
            }.onFailure { error = it.message ?: nativePhrase("通道状态更新失败。", "Failed to update channel status.") }
            busy = false
        }
    }

    /** 撤销 selected，仅在服务端确认成功后禁用该行，失败保留确认弹窗。 */
    fun revokeSelectedToken(selected: NativeReadOnlyToken) {
        // 撤销不删除记录，方便用户检查历史使用凭证。
        val currentToken = token ?: return
        if (busy || loading || selected.revokedAt != null) return
        busy = true
        dialogError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeIntegrationsApi.revokeToken(currentToken, selected.id) } }
                .onSuccess {
                    deleteToken = null
                    tokens = tokens.map { if (it.id == selected.id) it.copy(revokedAt = Instant.now().toString()) else it }
                    notice = nativePhrase("只读令牌已撤销。", "Read-only token revoked.")
                }
                .onFailure { dialogError = it.message ?: nativePhrase("令牌撤销失败。", "Failed to revoke the token.") }
            busy = false
        }
    }

    /** 删除 selected，成功立即移除本地行；失败保留目标和弹窗以便重试。 */
    fun deleteSelectedChannel(selected: NativeExternalChannel) {
        // 删除请求按当前账号鉴权，不能使用旧账号缓存的通道选择。
        val currentToken = token ?: return
        if (busy || loading) return
        busy = true
        dialogError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeIntegrationsApi.deleteChannel(currentToken, selected.id) } }
                .onSuccess {
                    deleteChannel = null
                    channels = channels.filterNot { it.id == selected.id }
                    notice = nativePhrase("外部通知通道已删除。", "External notification channel deleted.")
                }
                .onFailure { dialogError = it.message ?: nativePhrase("通道删除失败。", "Failed to delete the channel.") }
            busy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("集成管理", "Integrations"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("令牌与外部通知通道", "Tokens and external notification channels"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading && !busy && token != null) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (token.isNullOrBlank()) {
            item { integrationEmpty(nativePhrase("请先登录后管理外部集成。", "Sign in to manage integrations.")) }
        } else if (loading && tokens.isEmpty() && channels.isEmpty()) {
            item { integrationEmpty(nativePhrase("正在读取集成配置…", "Loading integration settings...")) }
        } else {
            if (googleAvailable == true || pendingGoogleLink != null || !oauthErrorMessage.isNullOrBlank()) {
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_google_color),
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    nativePhrase("Google 账号", "Google account"),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (pendingGoogleLink == null) {
                                    TextButton(onClick = ::startGoogleLink, enabled = !googleBusy) {
                                        Text(nativePhrase("绑定", "Link"), color = HlovetUi.accent, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (pendingGoogleLink == null) {
                                Text(
                                    nativePhrase("将当前账号与 Google 账号绑定，之后可使用 Google 登录。绑定前需要完成一次安全验证。", "Link the current account to Google so you can sign in with Google later. A security check is required before linking."),
                                    color = HlovetUi.muted,
                                    fontSize = 11.sp,
                                )
                            } else {
                                val pending = pendingGoogleLink!!
                                Text(
                                    nativePhrase("Google 邮箱 ${pending.email} 已存在站内账号。请选择一种验证方式完成绑定，系统不会自动合并账号。", "The Google email ${pending.email} already belongs to a site account. Choose one verification method to link it; accounts are never merged automatically."),
                                    color = HlovetUi.muted,
                                    fontSize = 11.sp,
                                )
                                when (googleVerificationStep) {
                                    "choose" -> {
                                        if (pending.methods.passkey) {
                                            FilledTonalButton(onClick = ::verifyGooglePasskey, enabled = !googleBusy, modifier = Modifier.fillMaxWidth()) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(painterResource(R.drawable.ic_passkey_color), null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(nativePhrase("使用通行密钥验证", "Verify with a passkey"))
                                                }
                                            }
                                        }
                                        if (pending.methods.email) {
                                            FilledTonalButton(onClick = ::requestGoogleEmail, enabled = !googleBusy, modifier = Modifier.fillMaxWidth()) {
                                                Text(nativePhrase("使用邮箱验证码", "Use an email code"))
                                            }
                                        }
                                        if (pending.methods.totp) {
                                            FilledTonalButton(
                                                onClick = {
                                                    googleVerificationStep = "totp"
                                                    googleCode = ""
                                                    error = null
                                                },
                                                enabled = !googleBusy,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text(nativePhrase("使用双因素认证", "Use two-factor authentication")) }
                                        }
                                        if (pending.methods.password) {
                                            FilledTonalButton(
                                                onClick = {
                                                    googleVerificationStep = "password"
                                                    googlePassword = ""
                                                    error = null
                                                },
                                                enabled = !googleBusy,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text(nativePhrase("使用账号密码", "Use your password")) }
                                        }
                                    }
                                    "passkey" -> Text(
                                        nativePhrase("请在设备提示中完成通行密钥验证。", "Complete the passkey verification in the device prompt."),
                                        color = HlovetUi.muted,
                                        fontSize = 12.sp,
                                    )
                                    "email", "totp" -> {
                                        Text(
                                            if (googleVerificationStep == "email") nativePhrase("输入邮箱中的 6 位验证码，输入完成后自动验证。", "Enter the 6-digit email code. It verifies automatically when complete.")
                                            else nativePhrase("输入身份验证器中的 6 位验证码，输入完成后自动验证。", "Enter the 6-digit authenticator code. It verifies automatically when complete."),
                                            color = HlovetUi.muted,
                                            fontSize = 12.sp,
                                        )
                                        HlovetCodeInput(
                                            value = googleCode,
                                            onValueChange = { googleCode = it },
                                            enabled = !googleBusy,
                                            onComplete = ::verifyGoogleCode,
                                        )
                                        if (googleVerificationStep == "email") {
                                            TextButton(onClick = ::requestGoogleEmail, enabled = !googleBusy && googleCooldown == 0) {
                                                Text(
                                                    if (googleCooldown > 0) nativePhrase("${googleCooldown}s 后重发", "Resend in ${googleCooldown}s") else nativePhrase("重新发送验证码", "Resend code"),
                                                    color = HlovetUi.secondaryAccent,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                    "password" -> {
                                        OutlinedTextField(
                                            value = googlePassword,
                                            onValueChange = { googlePassword = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            enabled = !googleBusy,
                                            label = { Text(nativePhrase("当前密码", "Current password")) },
                                            colors = integrationFieldColors(),
                                        )
                                        FilledTonalButton(onClick = ::verifyGooglePassword, enabled = googlePassword.isNotBlank() && !googleBusy, modifier = Modifier.fillMaxWidth()) {
                                            Text(nativePhrase("确认验证", "Verify"))
                                        }
                                    }
                                }
                                if (googleVerificationStep != "choose") {
                                    TextButton(onClick = ::resetGoogleVerification, enabled = !googleBusy) {
                                        Text(nativePhrase("返回验证方式", "Back to methods"), color = HlovetUi.muted, fontSize = 11.sp)
                                    }
                                }
                                TextButton(onClick = ::cancelGoogleLink, enabled = !googleBusy) {
                                    Text(nativePhrase("暂不绑定", "Not now"), color = HlovetUi.secondaryAccent, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(nativePhrase("只读 API 令牌", "Read-only API tokens"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                tokenName = ""
                                tokenExpiry = ""
                                tokenScopes = listOf("read_articles")
                                dialogError = null
                                tokenDialog = true
                            }, enabled = !busy && !loading, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Add, nativePhrase("新增令牌", "Add token"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
                        }
                        Text(nativePhrase("给脚本或自动化工具使用的只读访问凭证。令牌正文只在创建成功时显示一次。", "Read-only credentials for scripts and automation. The full token is shown only once after creation."), color = HlovetUi.muted, fontSize = 11.sp)
                        tokenLoadError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                        if (tokens.isEmpty() && tokenLoadError == null) Text(nativePhrase("暂无只读令牌。", "No read-only tokens."), color = HlovetUi.muted, fontSize = 12.sp)
                        tokens.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(color = HlovetUi.divider)
                            Row(Modifier.fillMaxWidth().testTag("integration-token-${item.id}").padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append(item.tokenPrefix.ifBlank { nativePhrase("令牌", "Token") })
                                            append(" · ")
                                            append(item.scopes.map(::scopeLabel).joinToString("、"))
                                            item.expiresAt?.let { append(nativePhrase(" · 到期 ${integrationTime(it)}", " · Expires ${integrationTime(it)}")) }
                                        },
                                        color = HlovetUi.muted,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(integrationTokenStatus(item), color = if (item.revokedAt == null) HlovetUi.accent else HlovetUi.muted, fontSize = 11.sp)
                                IconButton(onClick = { dialogError = null; deleteToken = item }, modifier = Modifier.size(34.dp), enabled = item.revokedAt == null && !busy && !loading) {
                                    Icon(Icons.Filled.Delete, nativePhrase("撤销令牌", "Revoke token"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Link, null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(nativePhrase("外部通知通道", "External notification channels"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                endpoint = ""
                                secret = ""
                                dialogError = null
                                channelDialog = true
                            }, enabled = !busy && !loading, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Add, nativePhrase("添加通道", "Add channel"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
                        }
                        Text(nativePhrase("通过 HTTPS Webhook 接收站内通知。添加后需要输入通道收到的 6 位验证码。", "Receive site notifications through an HTTPS webhook. Verify each channel with the 6-digit code it receives."), color = HlovetUi.muted, fontSize = 11.sp)
                        channelLoadError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                        if (channels.isEmpty() && channelLoadError == null) Text(nativePhrase("暂无外部通知通道。", "No external notification channels."), color = HlovetUi.muted, fontSize = 12.sp)
                        channels.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(color = HlovetUi.divider)
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.endpoint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        when {
                                            !item.verified -> nativePhrase("待验证", "Pending verification")
                                            item.lastError.isNullOrBlank() -> nativePhrase("已验证 · ${if (item.enabled) "已启用" else "已停用"}", "Verified · ${if (item.enabled) "Enabled" else "Disabled"}")
                                            else -> nativePhrase("最近投递失败：${item.lastError}", "Last delivery failed: ${item.lastError}")
                                        },
                                        color = if (item.lastError.isNullOrBlank()) HlovetUi.muted else HlovetUi.secondaryAccent,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (item.verified) {
                                    HlovetSwitch(checked = item.enabled, onCheckedChange = { toggleChannel(item, it) }, enabled = !busy && !loading)
                                } else {
                                    TextButton(
                                        onClick = { dialogError = null; verifyChannelId = item.id; verifyCode = "" },
                                        enabled = !busy && !loading,
                                    ) { Text(nativePhrase("验证", "Verify"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
                                }
                                IconButton(onClick = { dialogError = null; deleteChannel = item }, modifier = Modifier.size(34.dp), enabled = !busy && !loading) {
                                    Icon(Icons.Filled.Delete, nativePhrase("删除通道", "Delete channel"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!error.isNullOrBlank()) item { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
    }

    if (tokenDialog) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) tokenDialog = false },
            title = { Text(nativePhrase("新增只读令牌", "Create read-only token")) },
            text = {
                IntegrationForm(dialogError) {
                    OutlinedTextField(value = tokenName, onValueChange = { tokenName = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("名称", "Name")) }, colors = integrationFieldColors())
                    OutlinedTextField(value = tokenExpiry, onValueChange = { tokenExpiry = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("过期时间（可选）", "Expiration (optional)")) }, placeholder = { Text("2026-12-31T23:59:59Z") }, colors = integrationFieldColors())
                    Text(nativePhrase("权限范围", "Scopes"), color = HlovetUi.muted, fontSize = 11.sp)
                    integrationScopes.forEach { value ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { tokenScopes = if (value in tokenScopes) tokenScopes - value else tokenScopes + value }, verticalAlignment = Alignment.CenterVertically) {
                            HlovetCheckbox(checked = value in tokenScopes, onCheckedChange = { checked -> tokenScopes = if (checked) tokenScopes + value else tokenScopes - value }, enabled = !busy)
                            Text(scopeLabel(value), fontSize = 12.sp)
                        }
                    }
                    Text(nativePhrase("令牌创建后无法再次查看完整内容，请立即复制并妥善保存。", "The full token cannot be viewed again. Copy and store it securely now."), color = HlovetUi.muted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = ::submitToken, enabled = !busy && tokenName.isNotBlank() && tokenScopes.isNotEmpty()) { Text(if (busy) nativePhrase("创建中…", "Creating...") else nativePhrase("创建", "Create"), color = HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { tokenDialog = false }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (channelDialog) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) channelDialog = false },
            title = { Text(nativePhrase("添加外部通知通道", "Add external notification channel")) },
            text = {
                IntegrationForm(dialogError) {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS URL") }, placeholder = { Text("https://example.com/webhook") }, colors = integrationFieldColors())
                    OutlinedTextField(value = secret, onValueChange = { secret = it.take(255) }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("Secret（可选）", "Secret (optional)")) }, colors = integrationFieldColors())
                    Text(nativePhrase("系统会向这个地址发送一条验证消息，只有输入验证码后才会启用通道。", "The system sends a verification message to this URL. The channel is enabled only after the code is verified."), color = HlovetUi.muted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = ::submitChannel, enabled = !busy && endpoint.isNotBlank()) { Text(if (busy) nativePhrase("发送中…", "Sending...") else nativePhrase("发送验证", "Send verification"), color = HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { channelDialog = false }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (verifyChannelId != 0) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) verifyChannelId = 0 },
            title = { Text(nativePhrase("验证外部通道", "Verify external channel")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(nativePhrase("请输入外部通道收到的 6 位验证码，输入完成后会自动验证。", "Enter the 6-digit code delivered to the external channel. It is verified automatically when complete."), color = HlovetUi.muted, fontSize = 12.sp)
                    HlovetCodeInput(value = verifyCode, onValueChange = { verifyCode = it }, enabled = !busy, onComplete = ::verifyChannelCode)
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { verifyChannelCode(verifyCode) }, enabled = verifyCode.length == 6 && !busy) { Text(nativePhrase("确认", "Confirm"), color = HlovetUi.accent) } },
        )
    }

    createdToken?.let { created ->
        HlovetAlertDialog(
            onDismissRequest = { createdToken = null },
            title = { Text(nativePhrase("令牌已创建", "Token created")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(nativePhrase("完整令牌只显示这一次，请复制后保存。", "The full token is shown only once. Copy and save it now."), color = HlovetUi.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(created.token, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("HLOVET API token", created.token))
                            notice = nativePhrase("令牌已复制到剪贴板。", "Token copied to clipboard.")
                        }) { Icon(Icons.Filled.ContentCopy, nativePhrase("复制令牌", "Copy token"), tint = HlovetUi.accent) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { createdToken = null }) { Text(nativePhrase("我已保存", "Saved"), color = HlovetUi.accent) } },
        )
    }

    deleteToken?.let { selected ->
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) deleteToken = null },
            title = { Text(nativePhrase("撤销只读令牌", "Revoke read-only token")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(nativePhrase("撤销“${selected.name}”后，使用它的脚本将立即无法访问站内只读接口。", "Revoking “${selected.name}” immediately blocks scripts using it from site read-only APIs."))
                dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            } },
            confirmButton = { TextButton(onClick = { revokeSelectedToken(selected) }, enabled = !busy) { Text(nativePhrase("撤销", "Revoke"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { deleteToken = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    deleteChannel?.let { selected ->
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) deleteChannel = null },
            title = { Text(nativePhrase("删除外部通知通道", "Delete external notification channel")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(nativePhrase("确定删除这个外部通知通道吗？删除后不会再向它发送站内通知。", "Delete this external notification channel? Site notifications will no longer be sent to it."))
                dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            } },
            confirmButton = { TextButton(onClick = { deleteSelectedChannel(selected) }, enabled = !busy) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { deleteChannel = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

/** 提供与站内其他表单相同的毛玻璃输入框颜色。 */
@Composable
private fun integrationFieldColors() = HlovetUi.fieldColors(
    unfocusedBorderColor = HlovetUi.divider,
)

/** 为 content 提供限高滚动表单，error 固定在顶部避免键盘或长表单遮挡验证反馈。 */
@Composable
private fun IntegrationForm(error: String?, content: @Composable ColumnScope.() -> Unit) {
    // 错误不参与表单滚动，输入框滚动到末尾时也能看到本次提交结果。
    Column(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

/** 将 value 权限码转换为当前语言的名称，未知权限保留原值。 */
private fun scopeLabel(value: String): String = when (value) {
    "read_articles" -> nativePhrase("文章只读", "Read articles")
    "read_profile" -> nativePhrase("公开资料只读", "Read public profiles")
    "read_notifications" -> nativePhrase("通知只读", "Read notifications")
    else -> value
}

/** 简化 value 的 ISO 时间展示，空值保持为空。 */
private fun integrationTime(value: String): String = when {
    value.length >= 16 -> value.substring(0, 16).replace('T', ' ')
    value.isBlank() -> ""
    else -> value
}

/** 规范化可选 value 日期；无时区按设备时区解释，无效或过期时抛出校验异常。 */
internal fun normalizeIntegrationExpiry(value: String, now: Instant = Instant.now()): String? {
    // 允许粘贴 ISO 时间或本地日期时间，但提交给服务器统一使用 UTC。
    val input = value.trim().takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(input) }.getOrElse {
        LocalDateTime.parse(input).atZone(ZoneId.systemDefault()).toInstant()
    }
    require(instant > now)
    return instant.toString()
}

/** 根据令牌服务端撤销和到期信息展示真实状态。 */
private fun integrationTokenStatus(item: NativeReadOnlyToken): String {
    // 撤销优先于到期，不能把已过期令牌继续标记为有效。
    if (item.revokedAt != null) return nativePhrase("已撤销", "Revoked")
    val expiry = item.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return if (expiry != null && expiry <= Instant.now()) nativePhrase("已过期", "Expired") else nativePhrase("有效", "Active")
}

/** 以统一卡片显示 message 对应的登录或加载状态。 */
@Composable
private fun integrationEmpty(message: String) {
    // 复用现有 GlassCard，不引入另一套集成页面样式。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}
