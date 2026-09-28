@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background: Color get() = HlovetUi.background
private val SurfaceLow: Color get() = HlovetUi.surfaceLow
private val SurfaceAccent: Color get() = HlovetUi.surfaceAccent
private val TextPrimary: Color get() = HlovetUi.foreground
private val TextMuted: Color get() = HlovetUi.muted
private val Accent: Color get() = HlovetUi.accent
private val Coral: Color get() = HlovetUi.secondaryAccent

private sealed interface DetailTarget {
    data class Article(val article: RemoteArticle) : DetailTarget
    data class Topic(
        val title: String,
        val meta: String,
        val color: Color,
        val articles: List<RemoteArticle>,
    ) : DetailTarget
}

private sealed interface PreviewLoadState {
    data object Loading : PreviewLoadState
    data class Ready(val data: PreviewData) : PreviewLoadState
    data class Error(val message: String) : PreviewLoadState
}

private sealed interface NativeLoginStep {
    data object Credentials : NativeLoginStep
    data class Device(val challengeToken: String, val emailHint: String) : NativeLoginStep
    data class Totp(val challengeToken: String) : NativeLoginStep
    data class GoogleLink(val pendingToken: String, val email: String, val methods: GoogleLinkMethods) : NativeLoginStep
    data class GoogleLinkEmail(val pendingToken: String, val emailHint: String, val challengeToken: String) : NativeLoginStep
    data class GoogleLinkTotp(val pendingToken: String) : NativeLoginStep
    data class GoogleLinkPassword(val pendingToken: String) : NativeLoginStep
}

/** Keeps the existing page code on one shared glass surface implementation. */
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = HlovetUi.cardShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier, shape = shape, content = content)
}

class MainActivity : ComponentActivity() {
    private var oauthResultToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readOAuthResult(intent)
        window.statusBarColor = Background.toArgbCompat()
        window.navigationBarColor = Background.toArgbCompat()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        setContent {
            HlovetTheme {
                HlovetMobilePreview(
                    oauthResultToken = oauthResultToken,
                    onOAuthResultConsumed = { oauthResultToken = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOAuthResult(intent)
    }

    private fun readOAuthResult(intent: Intent?) {
        val data = intent?.data ?: return
        val customScheme = data.scheme == "hlovet-native" && data.host == "auth" && data.path == "/google"
        val verifiedAppLink = data.scheme == "https" && data.host == "5200918.xyz" && data.path == "/native-auth/google"
        if (customScheme || verifiedAppLink) {
            oauthResultToken = data.getQueryParameter("result")
        }
    }
}

@Composable
private fun HlovetMobilePreview(
    oauthResultToken: String? = null,
    onOAuthResultConsumed: () -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var detailTarget by remember { mutableStateOf<DetailTarget?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf<PreviewLoadState>(PreviewLoadState.Loading) }
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var session by remember { mutableStateOf<NativeSession?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    LaunchedEffect(reloadKey) {
        loadState = PreviewLoadState.Loading
        loadState = try {
            val data = withContext(Dispatchers.IO) { PreviewApi.load() }
            HlovetUi.applyAppearance(data.appearance)
            PreviewLoadState.Ready(data)
        } catch (error: Exception) {
            PreviewLoadState.Error(error.message ?: "暂时无法读取站内公开内容")
        }
    }
    LaunchedEffect(loadState) {
        val data = (loadState as? PreviewLoadState.Ready)?.data ?: return@LaunchedEffect
        backgroundBitmap = data.backgroundUrl?.let { url -> withContext(Dispatchers.IO) { PreviewApi.loadBitmap(url) } }
    }
    CompositionLocalProvider(LocalHlovetHaze provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            val backgroundPainter = backgroundBitmap?.asImageBitmap()?.let(::BitmapPainter)
            if (backgroundPainter != null) {
                Image(backgroundPainter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
            } else {
                Image(painterResource(id = R.drawable.hlovet_city_lights), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
            }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .34f)))
            if (loginVisible) {
                NativeLoginScreen(
                    oauthResultToken = oauthResultToken,
                    onOAuthResultConsumed = onOAuthResultConsumed,
                    onAuthenticated = {
                        session = it
                        loginVisible = false
                    },
                    onCancel = { loginVisible = false },
                )
            } else when (val state = loadState) {
                PreviewLoadState.Loading -> PreviewStatusScreen("正在读取站内内容…", null)
                is PreviewLoadState.Error -> PreviewStatusScreen(
                    title = "暂时无法读取内容",
                    detail = state.message,
                    onRetry = { reloadKey += 1 },
                )
                is PreviewLoadState.Ready -> if (detailTarget != null) {
                    DetailScreen(target = detailTarget!!, onBack = { detailTarget = null })
                } else Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.hazeEffect(state = hazeState, style = navigationGlassStyle()),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        val items = listOf(
                            Triple("首页", Icons.Filled.Home, 0),
                            Triple("发现", Icons.Filled.Explore, 1),
                            Triple("写作", Icons.Filled.AddCircleOutline, 2),
                            Triple("消息", Icons.Filled.ChatBubbleOutline, 3),
                            Triple("我的", Icons.Filled.PersonOutline, 4)
                        )
                        items.forEach { (label, icon, index) ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    indicatorColor = HlovetUi.accentSoft,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                when (selectedTab) {
                    0 -> HomeScreen(padding, state.data.articles, onArticleClick = { detailTarget = DetailTarget.Article(it) })
                    1 -> DiscoverScreen(
                        padding,
                        topics = state.data.topics,
                        collections = state.data.collections,
                        onTopicClick = { title, meta, color, entries -> detailTarget = DetailTarget.Topic(title, meta, color, entries) },
                        onArticleClick = { detailTarget = DetailTarget.Article(it) }
                    )
                    2 -> WriteScreen(padding)
                    3 -> MessagesScreen(padding)
                    else -> ProfileScreen(
                        padding = padding,
                        session = session,
                        onLogin = { loginVisible = true },
                        onLogout = { session = null },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun NativeLoginScreen(
    oauthResultToken: String?,
    onOAuthResultConsumed: () -> Unit,
    onAuthenticated: (NativeSession) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf<NativeLoginStep>(NativeLoginStep.Credentials) }
    var account by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var googleAvailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(Unit) {
        googleAvailable = runCatching { withContext(Dispatchers.IO) { NativeAuthApi.externalAuthProviders() } }.getOrDefault(false)
    }

    fun submit(action: suspend () -> NativeAuthResult) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { action() }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> {
                        step = NativeLoginStep.Device(result.challengeToken, result.emailHint)
                        code = ""
                    }
                    is NativeAuthResult.TotpVerification -> {
                        step = NativeLoginStep.Totp(result.challengeToken)
                        code = ""
                    }
                    is NativeAuthResult.GoogleLinkRequired -> {
                        step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                    }
                }
            } catch (exception: Exception) {
                error = exception.message ?: "认证失败，请稍后重试。"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(oauthResultToken) {
        val token = oauthResultToken?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        onOAuthResultConsumed()
        submit { NativeAuthApi.consumeOAuthResult(token) }
    }

    fun submitPasskey() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { NativeAuthApi.passkeyLoginOptions() }
                val credentialResult = credentialManager.getCredential(
                    context = context,
                    request = GetCredentialRequest(
                        credentialOptions = listOf(GetPublicKeyCredentialOption(options.requestJson)),
                    ),
                )
                val credential = credentialResult.credential as? PublicKeyCredential
                    ?: throw IllegalStateException("未返回通行密钥凭据")
                when (val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.verifyPasskeyLogin(options.challengeToken, credential.authenticationResponseJson)
                }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> step = NativeLoginStep.Device(result.challengeToken, result.emailHint)
                    is NativeAuthResult.TotpVerification -> step = NativeLoginStep.Totp(result.challengeToken)
                    is NativeAuthResult.GoogleLinkRequired -> step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                }
            } catch (exception: GetCredentialException) {
                val message = exception.message.orEmpty()
                error = if (message.contains("no provider dependencies", ignoreCase = true)) {
                    "当前模拟器的 Google Play 服务版本过旧，暂时无法使用通行密钥；请更新 Play services 或改用真实 Android 设备。"
                } else {
                    message.ifBlank { "通行密钥验证已取消。" }
                }
            } catch (exception: Exception) {
                error = exception.message ?: "通行密钥登录失败，请稍后重试。"
            } finally {
                loading = false
            }
        }
    }

    fun startGoogleLogin() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val authorizationUrl = withContext(Dispatchers.IO) { NativeAuthApi.startGoogleLogin() }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
            } catch (exception: ActivityNotFoundException) {
                error = "设备没有可用的浏览器，无法打开 Google 登录。"
            } catch (exception: Exception) {
                error = exception.message ?: "Google 登录暂不可用，请稍后重试。"
            } finally {
                loading = false
            }
        }
    }

    fun submitGoogleLinkPasskey(current: NativeLoginStep.GoogleLink) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { NativeAuthApi.googleLinkPasskeyOptions(current.pendingToken) }
                val credentialResult = credentialManager.getCredential(
                    context = context,
                    request = GetCredentialRequest(
                        credentialOptions = listOf(GetPublicKeyCredentialOption(options.requestJson)),
                    ),
                )
                val credential = credentialResult.credential as? PublicKeyCredential
                    ?: throw IllegalStateException("未返回通行密钥凭据")
                when (val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.verifyGoogleLinkPasskey(current.pendingToken, options.challengeToken, credential.authenticationResponseJson)
                }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> step = NativeLoginStep.Device(result.challengeToken, result.emailHint)
                    is NativeAuthResult.TotpVerification -> step = NativeLoginStep.Totp(result.challengeToken)
                    is NativeAuthResult.GoogleLinkRequired -> step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                }
            } catch (exception: GetCredentialException) {
                error = exception.message ?: "通行密钥验证已取消。"
            } catch (exception: Exception) {
                error = exception.message ?: "Google 关联验证失败，请稍后重试。"
            } finally {
                loading = false
            }
        }
    }

    fun requestGoogleLinkEmail(current: NativeLoginStep.GoogleLink) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { NativeAuthApi.requestGoogleLinkEmail(current.pendingToken) }
                step = NativeLoginStep.GoogleLinkEmail(current.pendingToken, challenge.emailHint, challenge.challengeToken)
            } catch (exception: Exception) {
                error = exception.message ?: "验证码发送失败，请稍后重试。"
            } finally {
                loading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("登录 HLOVET", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("登录后同步你的个人外观配置", color = TextMuted, fontSize = 12.sp)
                    }
                }
                when (val currentStep = step) {
                    NativeLoginStep.Credentials -> {
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("账号或邮箱") },
                            leadingIcon = { Icon(Icons.Filled.PersonOutline, contentDescription = null) },
                            colors = loginFieldColors(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = loginFieldColors(),
                        )
                        Text("原生预览不会保存密码，关闭应用后登录状态会清除。", color = TextMuted, fontSize = 11.sp)
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.login(account.trim(), password) } },
                            enabled = account.isNotBlank() && password.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (loading) "登录中…" else "登录")
                        }
                        if (googleAvailable || !loading) {
                            Spacer(Modifier.height(2.dp))
                            FilledTonalButton(
                                onClick = ::submitPasskey,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("使用通行密钥登录")
                            }
                        }
                        if (googleAvailable) {
                            FilledTonalButton(
                                onClick = ::startGoogleLogin,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("使用 Google 登录")
                            }
                        }
                    }
                    is NativeLoginStep.Device -> {
                        Text("新设备验证", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("验证码已发送到 ${currentStep.emailHint.ifBlank { "绑定邮箱" }}。", color = TextMuted, fontSize = 12.sp)
                        VerificationCodeField(code, { code = it }, loading)
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyDevice(currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "验证中…" else "验证设备") }
                    }
                    is NativeLoginStep.Totp -> {
                        Text("双因素认证", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("请输入验证器中的 6 位验证码。", color = TextMuted, fontSize = 12.sp)
                        VerificationCodeField(code, { code = it }, loading)
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyTotp(currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "验证中…" else "完成登录") }
                    }
                    is NativeLoginStep.GoogleLink -> {
                        Text("关联 Google 账号", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${currentStep.email} 已存在站内账号，需要完成一次安全验证后才能关联。",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                        if (currentStep.methods.passkey) {
                            FilledTonalButton(
                                onClick = { submitGoogleLinkPasskey(currentStep) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("使用通行密钥验证") }
                        }
                        if (currentStep.methods.email) {
                            FilledTonalButton(
                                onClick = { requestGoogleLinkEmail(currentStep) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("使用邮箱验证码") }
                        }
                        if (currentStep.methods.totp) {
                            FilledTonalButton(
                                onClick = { step = NativeLoginStep.GoogleLinkTotp(currentStep.pendingToken) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("使用双因素认证") }
                        }
                        if (currentStep.methods.password) {
                            FilledTonalButton(
                                onClick = { step = NativeLoginStep.GoogleLinkPassword(currentStep.pendingToken) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("使用账号密码") }
                        }
                    }
                    is NativeLoginStep.GoogleLinkEmail -> {
                        Text("邮箱验证", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "验证码已发送到 ${currentStep.emailHint.ifBlank { "绑定邮箱" }}。",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                        VerificationCodeField(code, { code = it }, loading)
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkEmail(currentStep.pendingToken, currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "验证中…" else "完成关联") }
                    }
                    is NativeLoginStep.GoogleLinkTotp -> {
                        Text("双因素认证", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("请输入身份验证器中的 6 位验证码。", color = TextMuted, fontSize = 12.sp)
                        VerificationCodeField(code, { code = it }, loading)
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkTotp(currentStep.pendingToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "验证中…" else "完成关联") }
                    }
                    is NativeLoginStep.GoogleLinkPassword -> {
                        Text("账号密码验证", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("当前密码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            colors = loginFieldColors(),
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkPassword(currentStep.pendingToken, password) } },
                            enabled = password.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "验证中…" else "完成关联") }
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun VerificationCodeField(value: String, onValueChange: (String) -> Unit, loading: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(6)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        singleLine = true,
        label = { Text("6 位验证码") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = loginFieldColors(),
    )
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = HlovetUi.divider,
    focusedLabelColor = Accent,
    cursorColor = Accent,
)

@Composable
private fun PreviewStatusScreen(title: String, detail: String?, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Explore, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(detail, color = TextMuted, fontSize = 13.sp)
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) { Text("重新加载") }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
private fun DetailScreen(target: DetailTarget, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        text = when (target) {
                            is DetailTarget.Article -> "文章详情"
                            is DetailTarget.Topic -> "专题与合集"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
        when (target) {
            is DetailTarget.Article -> {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(target.article.authorName.takeLast(2), Coral)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(target.article.authorName, fontWeight = FontWeight.SemiBold)
                                    Text(articleReadTime(target.article), color = TextMuted, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(target.article.title, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(target.article.category, fontSize = 12.sp) },
                                border = null,
                                colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.accentSoft)
                            )
                            if (target.article.tags.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(target.article.tags.joinToString("  ·  "), color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (target.article.summary.isNotBlank()) {
                                Text(target.article.summary, fontSize = 16.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
                            }
                            Text(
                                cleanArticleText(target.article.content).ifBlank { "这篇文章暂时没有可预览的正文。" },
                                color = if (target.article.summary.isNotBlank()) TextMuted else TextPrimary,
                                fontSize = 15.sp,
                                lineHeight = 25.sp,
                            )
                            Text(
                                "阅读 ${target.article.viewCount}  ·  点赞 ${target.article.likeCount}  ·  评论 ${target.article.commentCount}",
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            is DetailTarget.Topic -> {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Surface(shape = HlovetUi.rowShape, color = target.color.copy(alpha = .15f)) {
                                Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = target.color, modifier = Modifier.padding(12.dp).size(26.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(target.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(target.meta, color = TextMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("围绕一个主题持续整理，让每一篇文章都能成为下一篇文章的入口。", color = TextMuted, fontSize = 14.sp, lineHeight = 23.sp)
                        }
                    }
                }
                item { SectionHeading("收录文章", "查看全部") }
                item {
                    if (target.articles.isEmpty()) EmptyState("这个内容集合暂时还没有文章")
                    else ArticleGroup(target.articles)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(scaffoldPadding: PaddingValues, articles: List<RemoteArticle>, onArticleClick: (RemoteArticle) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppHeader(
                title = "HLOVET",
                subtitle = "今天，也为值得留下的内容留一点空间。",
                action = { IconButton(onClick = {}) { Icon(Icons.Filled.NotificationsNone, "通知") } }
            )
        }
        item { FeaturedEntry() }
        item { SectionHeading("为你推荐", "更多") }
        item {
            if (articles.isEmpty()) EmptyState("暂时没有公开文章")
            else ArticleGroup(articles, onArticleClick = onArticleClick)
        }
        item { SectionHeading("正在关注", "查看订阅") }
        item { FollowingStrip() }
    }
}

@Composable
private fun FeaturedEntry() {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text("从一篇好文章开始", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            supportingContent = { Text("把想法整理成可以继续使用的知识。", color = TextMuted, fontSize = 12.sp) },
            leadingContent = { Avatar("H", Accent, large = true) },
            trailingContent = { Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Accent) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {}
        )
    }
}

@Composable
private fun SectionHeading(title: String, action: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(action, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ArticleGroup(
    displayArticles: List<RemoteArticle>,
    onArticleClick: (RemoteArticle) -> Unit = {}
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            displayArticles.forEachIndexed { index, article ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArticleClick(article) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(article.authorName.takeLast(2), Coral)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(article.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(article.authorName, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(" · ", color = TextMuted, fontSize = 11.sp)
                            Text(articleReadTime(article), color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.width(7.dp))
                            Text(article.category, color = Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = null, tint = TextMuted)
                }
                if (index < displayArticles.lastIndex) {
                    HorizontalDivider(color = HlovetUi.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun FollowingStrip() {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf("nice3", "hlovet", "maria", "工程笔记")) { name ->
            AssistChip(
                onClick = {},
                label = { Text(name, fontSize = 12.sp) },
                leadingIcon = { Avatar(name.takeLast(2), Accent, compact = true) },
                colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.surface),
                border = null
            )
        }
    }
}

@Composable
private fun DiscoverScreen(
    scaffoldPadding: PaddingValues,
    topics: List<RemoteTopic>,
    collections: List<RemoteCollection>,
    onTopicClick: (String, String, Color, List<RemoteArticle>) -> Unit = { _, _, _, _ -> },
    onArticleClick: (RemoteArticle) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("发现", "找到正在发生的内容") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceLow)
            ) {
                ListItem(
                    headlineContent = { Text("搜索文章、用户、专题和合集", color = TextMuted, fontSize = 13.sp) },
                    leadingContent = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("推荐", "热门", "最新", "专题", "合集")) { label ->
                    FilterChip(
                        selected = label == "推荐",
                        onClick = {},
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = HlovetUi.onAccent,
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = null
                    )
                }
            }
        }
        item { SectionHeading("专题与合集", "全部") }
        if (topics.isEmpty() && collections.isEmpty()) {
            item { EmptyState("暂时没有公开专题或合集") }
        } else {
            items(topics.take(3)) { topic ->
                TopicRow(
                    title = topic.title,
                    meta = "专题 · ${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅",
                    color = Accent,
                    articles = topic.articles,
                    onClick = onTopicClick,
                )
            }
            items(collections.take(3)) { collection ->
                TopicRow(
                    title = collection.name,
                    meta = "合集 · ${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅",
                    color = Coral,
                    articles = collection.articles,
                    onClick = onTopicClick,
                )
            }
        }
        item { SectionHeading("最近更新", "查看全部") }
        item {
            val recent = (topics.flatMap { it.articles } + collections.flatMap { it.articles }).distinctBy { it.id }.take(4)
            if (recent.isEmpty()) EmptyState("暂无可展示的最近更新")
            else ArticleGroup(recent, onArticleClick = onArticleClick)
        }
    }
}

@Composable
private fun TopicRow(
    title: String,
    meta: String,
    color: Color,
    articles: List<RemoteArticle>,
    onClick: (String, String, Color, List<RemoteArticle>) -> Unit = { _, _, _, _ -> }
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onClick(title, meta, color, articles) },
        shape = HlovetUi.rowShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(meta, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Surface(shape = HlovetUi.rowShape, color = color.copy(alpha = .14f)) {
                    Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = color, modifier = Modifier.padding(9.dp).size(20.dp))
                }
            },
            trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun WriteScreen(scaffoldPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("写作", "把想法留下来") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("从一个空白草稿开始", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text("离线保存、自动保存和 AI 辅助都在这里继续。", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.AddCircleOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("新建文章", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Text("最近草稿", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
        item { DraftGroup() }
    }
}

@Composable
private fun DraftGroup() {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        val drafts = listOf("移动端产品方向" to "刚刚自动保存", "AI 知识库实践记录" to "昨天编辑")
        Column {
            drafts.forEachIndexed { index, draft ->
                ListItem(
                    headlineContent = { Text(draft.first, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(draft.second, color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = Accent) },
                    trailingContent = { Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
                if (index < drafts.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun MessagesScreen(scaffoldPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("消息", "聊天、评论和提醒", action = { IconButton(onClick = {}) { Icon(Icons.Filled.Settings, "设置") } }) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                ListItem(
                    headlineContent = { Text("你有 3 条未读消息", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
                    supportingContent = { Text("包括 1 条 @提醒", color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = Accent) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
            }
        }
        item { ChatGroup() }
    }
}

@Composable
private fun ChatGroup() {
    val chats = listOf(
        ChatPreview("项目讨论组", "今天的首页草稿已经更新了", "刚刚", "P"),
        ChatPreview("nice3", "我看到了你 @ 的消息", "12:36", "N"),
        ChatPreview("内容共创", "附件：移动端设计方向.pdf", "昨天", "C"),
    )
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            chats.forEachIndexed { index, chat ->
                ListItem(
                    headlineContent = { Text(chat.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(chat.message, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(chat.initials, Accent) },
                    trailingContent = { Text(chat.time, color = TextMuted, fontSize = 10.sp) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
                if (index < chats.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    padding: PaddingValues,
    session: NativeSession?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("我的", "账号、内容和安全设置", action = { IconButton(onClick = {}) { Icon(Icons.Filled.MoreHoriz, "更多") } }) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
            ) {
                if (session == null) {
                    ListItem(
                        headlineContent = { Text("登录账号", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                        supportingContent = { Text("登录后同步 admin 的个人主题和卡片配置", color = TextMuted, fontSize = 11.sp) },
                        leadingContent = { Icon(Icons.Filled.Login, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onLogin),
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(session.nickname, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                        supportingContent = { Text("@${session.username} · 已同步个人外观", color = TextMuted, fontSize = 11.sp) },
                        leadingContent = { Avatar(session.username.takeLast(2), Accent) },
                        trailingContent = {
                            IconButton(onClick = onLogout) {
                                Icon(Icons.Filled.Logout, contentDescription = "退出登录", tint = TextMuted)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                ListItem(
                    headlineContent = { Text("你的 HLOVET", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    supportingContent = { Text("@hlovet · 已发布 12 篇文章", color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Avatar("VT", Coral, large = true) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
            }
        }
        item { ProfileSection("内容管理", listOf("我的文章", "我的合集", "我的订阅"), Icons.Filled.LibraryBooks) }
        item { ProfileSection("账号与隐私", listOf("账号凭据", "双因素认证", "通行密钥"), Icons.Filled.Settings) }
        item { ProfileSection("AI 助手", listOf("对话记录", "我的用量", "帮助与反馈"), Icons.Filled.AutoAwesome) }
    }
}

@Composable
private fun ProfileSection(title: String, rows: List<String>, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = HlovetUi.cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                rows.forEachIndexed { index, label ->
                    ListItem(
                        headlineContent = { Text(label, fontSize = 13.sp) },
                        leadingContent = { Icon(icon, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {}
                    )
                    if (index < rows.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun Avatar(text: String, color: Color, large: Boolean = false, compact: Boolean = false) {
    val size = when {
        large -> 54.dp
        compact -> 24.dp
        else -> 40.dp
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .14f), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text.takeLast(2), color = color, fontSize = if (large) 15.sp else 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private data class ChatPreview(val name: String, val message: String, val time: String, val initials: String)

@Composable
private fun EmptyState(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

private fun articleReadTime(article: RemoteArticle): String {
    val minutes = maxOf(1, cleanArticleText(article.content).length / 420)
    return "$minutes 分钟阅读"
}

private fun cleanArticleText(content: String): String = content
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("[`*_>#]"), "")
    .replace(Regex("\\n{3,}"), "\\n\\n")
    .trim()

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
