@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun NativeProfileEditorScreen(
    session: NativeSession,
    onBack: () -> Unit,
    onSessionUpdated: (NativeSession) -> Unit,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentSession by remember(session) { mutableStateOf(session) }
    var nickname by remember(session) { mutableStateOf(session.nickname) }
    var username by remember(session) { mutableStateOf(session.username) }
    var profileBio by remember(session) { mutableStateOf(session.profileBio) }
    var locale by remember(session) { mutableStateOf(if (session.locale == "en-US") "en-US" else "zh-CN") }
    var appearance by remember(session) { mutableStateOf(session.appearance) }
    var settings by remember(session.accessToken) { mutableStateOf<NativeProfileSettings?>(null) }
    var profileArticles by remember(session.accessToken) { mutableStateOf<List<RemoteArticle>>(emptyList()) }
    var profileCollections by remember(session.accessToken) { mutableStateOf<List<RemoteCollection>>(emptyList()) }
    var loadingSettings by remember(session.accessToken) { mutableStateOf(true) }
    var loadingShowcaseOptions by remember(session.accessToken) { mutableStateOf(true) }
    var profileSaving by remember { mutableStateOf(false) }
    var usernameSaving by remember { mutableStateOf(false) }
    var localeSaving by remember { mutableStateOf(false) }
    var appearanceSaving by remember { mutableStateOf(false) }
    var settingsSaving by remember { mutableStateOf(false) }
    var avatarSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyAccount(account: NativeAccountProfile) {
        val next = currentSession.copy(
            username = account.username,
            nickname = account.nickname,
            email = account.email,
            profileBio = account.profileBio,
            avatarUrl = account.avatarUrl,
            locale = account.locale,
            appearance = account.appearance,
        )
        currentSession = next
        username = next.username
        nickname = next.nickname
        profileBio = next.profileBio
        locale = next.locale
        appearance = next.appearance
        HlovetUi.applyAppearance(next.appearance)
        NativeSessionStore.save(context, next)
        onSessionUpdated(next)
    }

    LaunchedEffect(session.accessToken) {
        runCatching { withContext(Dispatchers.IO) { NativeProfileApi.loadAccount(session.accessToken) } }
            .onSuccess(::applyAccount)
                .onFailure { error = it.message ?: nativePhrase("个人资料加载失败。", "Unable to load your profile.") }
    }

    LaunchedEffect(session.accessToken) {
        loadingSettings = true
        error = null
        runCatching { withContext(Dispatchers.IO) { NativeProfileApi.loadSettings(session.accessToken) } }
            .onSuccess { settings = it }
                .onFailure { error = it.message ?: nativePhrase("公开主页设置加载失败。", "Unable to load public profile settings.") }
        loadingSettings = false
    }

    LaunchedEffect(session.accessToken) {
        loadingShowcaseOptions = true
        runCatching {
            withContext(Dispatchers.IO) {
                val articles = NativeArticleApi.listMine(
                    accessToken = session.accessToken,
                    page = 1,
                    pageSize = 50,
                    status = "published",
                ).items
                val collections = NativeDiscoveryApi.listMyCollections(session.accessToken)
                articles to collections
            }
        }.onSuccess { (articles, collections) ->
            profileArticles = articles
            profileCollections = collections
        }.onFailure { error = it.message ?: nativePhrase("代表内容选项加载失败。", "Unable to load featured content options.") }
        loadingShowcaseOptions = false
    }

    fun saveProfile() {
        if (profileSaving) return
        val nextNickname = nickname.trim()
        val nextBio = profileBio.trim()
        when {
            nextNickname.isBlank() -> error = nativePhrase("昵称不能为空。", "Nickname is required.")
            nextNickname.length > 24 -> error = nativePhrase("昵称最多 24 个字符。", "Nickname must be 24 characters or fewer.")
            nextBio.isBlank() -> error = nativePhrase("个人介绍不能为空。", "Bio is required.")
            else -> {
                profileSaving = true
                error = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            NativeProfileApi.updateProfile(session.accessToken, nextNickname, currentSession.email, nextBio)
                        }
                    }.onSuccess(::applyAccount)
                .onFailure { error = it.message ?: nativePhrase("个人资料保存失败。", "Unable to save your profile.") }
                    profileSaving = false
                }
            }
        }
    }

    fun saveUsername() {
        if (usernameSaving) return
        val nextUsername = username.trim().lowercase()
        when {
            !Regex("^[a-z0-9_]{3,32}$").matches(nextUsername) -> error = nativePhrase("用户名只能包含英文、数字和下划线，长度为 3-32 位。", "Username may contain lowercase letters, numbers, and underscores, and must be 3-32 characters.")
            nextUsername == currentSession.username -> onNotice(nativePhrase("用户名没有变化。", "Username is unchanged."))
            else -> {
                usernameSaving = true
                error = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { NativeProfileApi.updateUsername(session.accessToken, nextUsername) }
                    }.onSuccess {
                        applyAccount(it)
                onNotice(nativePhrase("用户名已更新。", "Username updated."))
            }.onFailure { error = it.message ?: nativePhrase("用户名更新失败。", "Unable to update the username.") }
                    usernameSaving = false
                }
            }
        }
    }

    fun saveAppearance() {
        if (appearanceSaving) return
        appearanceSaving = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeProfileApi.updateAppearance(session.accessToken, appearance) }
            }.onSuccess {
                applyAccount(it)
                onNotice(nativePhrase("外观设置已保存。", "Appearance settings saved."))
            }.onFailure { error = it.message ?: nativePhrase("外观设置保存失败。", "Unable to save appearance settings.") }
            appearanceSaving = false
        }
    }

    fun saveLocale() {
        if (localeSaving) return
        localeSaving = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeProfileApi.updateLocale(session.accessToken, locale) }
            }.onSuccess {
                applyAccount(it)
                onNotice(nativePhrase("界面语言偏好已保存。", "Interface language preference saved."))
            }.onFailure { error = it.message ?: nativePhrase("界面语言保存失败。", "Unable to save the interface language.") }
            localeSaving = false
        }
    }

    fun saveSettings() {
        val current = settings ?: return
        if (settingsSaving) return
        settingsSaving = true
        error = null
        val body = JSONObject().apply {
            put("profileAccess", current.profileAccess)
            put("searchable", current.searchable)
            put("friendRequestPolicy", current.friendRequestPolicy)
            put("directMessagePolicy", current.directMessagePolicy)
            put("groupInvitationPolicy", current.groupInvitationPolicy)
            put("showBio", current.showBio)
            put("showJoinedAt", current.showJoinedAt)
            put("showStats", current.showStats)
            put("showFollowingCount", current.showFollowingCount)
            put("showPinnedContent", current.showPinnedContent)
            put("pinnedArticleId", current.pinnedArticleId ?: JSONObject.NULL)
            put("pinnedCollectionId", current.pinnedCollectionId ?: JSONObject.NULL)
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeProfileApi.updateSettings(session.accessToken, body) }
            }.onSuccess {
                settings = it
                onNotice(nativePhrase("公开主页设置已更新。", "Public profile settings updated."))
            }.onFailure { error = it.message ?: nativePhrase("公开主页设置保存失败。", "Unable to save public profile settings.") }
            settingsSaving = false
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || avatarSaving) return@rememberLauncherForActivityResult
        avatarSaving = true
        error = null
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, 2L * 1024L * 1024L) }
                withContext(Dispatchers.IO) { NativeProfileApi.uploadAvatar(session.accessToken, file) }
            }.onSuccess {
                applyAccount(it)
                onNotice(nativePhrase("头像已更新。", "Avatar updated."))
            }.onFailure { error = it.message ?: nativePhrase("头像上传失败。", "Unable to upload the avatar.") }
            avatarSaving = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("编辑个人资料", "Edit profile"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        loadingSettings = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { NativeProfileApi.loadSettings(session.accessToken) } }
                                .onSuccess { settings = it }
                                    .onFailure { error = it.message ?: nativePhrase("公开主页设置加载失败。", "Unable to load public profile settings.") }
                            loadingSettings = false
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NativeCoverImage(
                            path = currentSession.avatarUrl,
                            modifier = Modifier.size(64.dp),
                            fallbackText = currentSession.nickname.ifBlank { currentSession.username },
                            tint = HlovetUi.accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(currentSession.nickname.ifBlank { currentSession.username }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("@${currentSession.username}", color = HlovetUi.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { avatarPicker.launch("image/*") }, enabled = !avatarSaving) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                                Text(if (avatarSaving) nativePhrase("上传中", "Uploading") else nativePhrase("更换头像", "Change avatar"), fontSize = 12.sp)
                        }
                    }
                            Text(nativePhrase("头像建议使用清晰的 JPG、PNG 或 WebP 图片，大小不超过 2 MB。", "Use a clear JPG, PNG, or WebP image no larger than 2 MB."), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditorSectionTitle(Icons.Filled.Person, nativePhrase("个人资料", "Profile"), nativePhrase("昵称和个人介绍会显示在你的公开主页中。", "Your nickname and bio appear on your public profile."))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(24) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                                label = { Text(nativePhrase("昵称", "Nickname")) },
                        colors = editorFieldColors(),
                    )
                    OutlinedTextField(
                        value = currentSession.email,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = true,
                                label = { Text(nativePhrase("邮箱", "Email")) },
                                supportingText = { Text(nativePhrase("邮箱修改请前往“隐私与数据”完成安全验证。", "Change your email under Privacy and data after completing security verification."), fontSize = 10.sp) },
                        colors = editorFieldColors(),
                    )
                    OutlinedTextField(
                        value = profileBio,
                        onValueChange = { profileBio = it.take(180) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                                label = { Text(nativePhrase("个人介绍", "Bio")) },
                        colors = editorFieldColors(),
                    )
                            EditorSaveButton(nativePhrase("保存个人资料", "Save profile"), profileSaving, ::saveProfile)
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditorSectionTitle(Icons.Filled.Lock, "@username", nativePhrase("用于站内提及和公开主页地址，修改后 30 天内不能再次修改。", "Used for mentions and your public profile URL. It cannot be changed again for 30 days."))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.lowercase().filter { char -> char.isLetterOrDigit() || char == '_' }.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text("@") },
                                label = { Text(nativePhrase("用户名", "Username")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        colors = editorFieldColors(),
                    )
                            EditorSaveButton(nativePhrase("保存用户名", "Save username"), usernameSaving, ::saveUsername)
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditorSectionTitle(Icons.Filled.Language, nativePhrase("界面语言", "Interface language"), nativePhrase("同步账号语言偏好，并影响 AI 返回语言。", "Syncs your account language preference and influences AI response language."))
                    ChoiceChips(
                                options = listOf("zh-CN" to nativePhrase("中文", "Chinese"), "en-US" to "English"),
                        selected = locale,
                        onSelected = { locale = it },
                    )
                            EditorSaveButton(nativePhrase("保存语言偏好", "Save language preference"), localeSaving, ::saveLocale)
                }
            }
        }
        item {
            AppearanceEditorCard(
                appearance = appearance,
                saving = appearanceSaving,
                onAppearanceChanged = { appearance = it },
                onSave = ::saveAppearance,
            )
        }
        item {
            ProfileSettingsCard(
                settings = settings,
                loading = loadingSettings,
                    loadingShowcaseOptions = loadingShowcaseOptions,
                    articles = profileArticles,
                    collections = profileCollections,
                saving = settingsSaving,
                onSettingsChanged = { settings = it },
                onSave = ::saveSettings,
            )
        }
    }
}

@Composable
private fun EditorSectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = HlovetUi.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EditorSaveButton(label: String, saving: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick, enabled = !saving) {
            Icon(if (saving) Icons.Filled.Refresh else Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        Text(if (saving) nativePhrase("保存中…", "Saving...") else label, fontSize = 12.sp)
        }
    }
}

private data class AppearanceColorControl(
    val key: String,
    val label: String,
    val value: String,
)

internal fun isValidNativeAppearanceColor(value: String): Boolean =
    Regex("^#[0-9A-Fa-f]{6}$").matches(value)

@Composable
private fun AppearanceEditorCard(
    appearance: PreviewAppearance,
    saving: Boolean,
    onAppearanceChanged: (PreviewAppearance) -> Unit,
    onSave: () -> Unit,
) {
    var colorErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val colorControls = listOf(
        AppearanceColorControl("accent", nativePhrase("强调色", "Accent"), appearance.accent),
        AppearanceColorControl("surface", nativePhrase("卡片底色", "Card surface"), appearance.surface),
        AppearanceColorControl("foreground", nativePhrase("主文字", "Primary text"), appearance.foreground),
        AppearanceColorControl("muted", nativePhrase("辅助文字", "Secondary text"), appearance.muted),
        AppearanceColorControl("glassTint", nativePhrase("磨砂颜色", "Glass tint"), appearance.glassTint),
    )

    fun updateColor(control: AppearanceColorControl, value: String) {
        val next = when (control.key) {
            "accent" -> appearance.copy(accent = value, themeId = "custom")
            "surface" -> appearance.copy(surface = value, themeId = "custom")
            "foreground" -> appearance.copy(foreground = value, themeId = "custom")
            "muted" -> appearance.copy(muted = value, themeId = "custom")
            else -> appearance.copy(glassTint = value, themeId = "custom")
        }
        onAppearanceChanged(next)
        colorErrors = if (isValidNativeAppearanceColor(value)) {
            colorErrors - control.key
        } else {
        colorErrors + (control.key to nativePhrase("颜色格式应为 #RRGGBB", "Use #RRGGBB color format"))
        }
    }

    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorSectionTitle(Icons.Filled.Edit, nativePhrase("主题外观", "Theme appearance"), nativePhrase("Android 端与 PC 端共用账号级外观配置。", "Android and PC share the same account-level appearance settings."))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
            listOf(
                "sakura-mist" to nativePhrase("樱花", "Sakura"),
                "cloud-blue" to nativePhrase("云蓝", "Cloud blue"),
                "night-purple" to nativePhrase("夜紫", "Night purple"),
                "custom" to nativePhrase("自定义", "Custom"),
            ).forEach { (id, label) ->
                    FilterChip(
                        selected = appearance.themeId == id,
                        onClick = {
                            val next = when (id) {
                                "sakura-mist" -> PreviewApi.appearanceFromJson(JSONObject().put("themeId", id))
                                "cloud-blue" -> PreviewApi.appearanceFromJson(JSONObject().put("themeId", id))
                                "night-purple" -> PreviewApi.appearanceFromJson(JSONObject().put("themeId", id))
                                else -> appearance.copy(themeId = "custom")
                            }
                            onAppearanceChanged(next)
                            colorErrors = emptyMap()
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        leadingIcon = if (appearance.themeId == id) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }) else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HlovetUi.accentSoft,
                            selectedLabelColor = HlovetUi.foreground,
                        ),
                    )
                }
            }
            Slider(
                value = appearance.cardAlpha.toFloat(),
                onValueChange = { onAppearanceChanged(appearance.copy(cardAlpha = it.toInt().coerceIn(38, 76), themeId = "custom")) },
                valueRange = 38f..76f,
                steps = 37,
            )
        Text(nativePhrase("卡片透明度：${appearance.cardAlpha}%", "Card opacity: ${appearance.cardAlpha}%"), color = HlovetUi.muted, fontSize = 11.sp)
            Slider(
                value = appearance.glassBlur.toFloat(),
                onValueChange = { onAppearanceChanged(appearance.copy(glassBlur = it.toInt().coerceIn(0, 36), themeId = "custom")) },
                valueRange = 0f..36f,
                steps = 35,
            )
        Text(nativePhrase("磨砂程度：${appearance.glassBlur}px", "Glass blur: ${appearance.glassBlur}px"), color = HlovetUi.muted, fontSize = 11.sp)
            Slider(
                value = appearance.glassTintAlpha.toFloat(),
                onValueChange = {
                    onAppearanceChanged(
                        appearance.copy(
                            glassTintAlpha = it.toInt().coerceIn(0, 100),
                            themeId = "custom",
                        ),
                    )
                },
                valueRange = 0f..100f,
                steps = 99,
            )
        Text(nativePhrase("磨砂透明度：${appearance.glassTintAlpha}%", "Glass tint opacity: ${appearance.glassTintAlpha}%"), color = HlovetUi.muted, fontSize = 11.sp)
            colorControls.forEach { control ->
                val errorMessage = colorErrors[control.key]
                val swatch = runCatching { Color(android.graphics.Color.parseColor(control.value)) }
                    .getOrDefault(HlovetUi.surfaceLow)
                OutlinedTextField(
                    value = control.value,
                    onValueChange = { updateColor(control, it.take(7)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(control.label, fontSize = 11.sp) },
                    leadingIcon = {
                        Spacer(
                            Modifier
                                .size(18.dp)
                                .background(swatch, HlovetUi.rowShape),
                        )
                    },
                    supportingText = errorMessage?.let { message ->
                        { Text(message, fontSize = 10.sp) }
                    },
                    isError = errorMessage != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    colors = editorFieldColors(),
                )
            }
            EditorSaveButton(
            nativePhrase("保存外观设置", "Save appearance settings"),
                saving,
                onClick = {
                    val invalid = colorControls
                        .filterNot { isValidNativeAppearanceColor(it.value) }
                    .associate { it.key to nativePhrase("颜色格式应为 #RRGGBB", "Use #RRGGBB color format") }
                    if (invalid.isEmpty()) onSave() else colorErrors = invalid
                },
            )
        }
    }
}

@Composable
private fun ProfileSettingsCard(
    settings: NativeProfileSettings?,
    loading: Boolean,
    loadingShowcaseOptions: Boolean,
    articles: List<RemoteArticle>,
    collections: List<RemoteCollection>,
    saving: Boolean,
    onSettingsChanged: (NativeProfileSettings) -> Unit,
    onSave: () -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            EditorSectionTitle(Icons.Filled.Person, nativePhrase("公开主页设置", "Public profile settings"), nativePhrase("控制别人能看到的资料范围和互动入口。", "Control what others can see and how they can interact with you."))
            if (loading || settings == null) {
                Text(nativePhrase("正在读取设置…", "Loading settings..."), color = HlovetUi.muted, fontSize = 11.sp)
            } else {
                val current = settings
                Text(nativePhrase("主页可见范围", "Profile visibility"), color = HlovetUi.muted, fontSize = 11.sp)
                ChoiceChips(
                    options = listOf(
                        "public" to nativePhrase("所有人", "Everyone"),
                        "authenticated" to nativePhrase("登录用户", "Signed-in users"),
                        "friends" to nativePhrase("仅好友", "Friends only"),
                        "private" to nativePhrase("仅自己", "Only me"),
                    ),
                    selected = current.profileAccess,
                    onSelected = { onSettingsChanged(current.copy(profileAccess = it)) },
                )
                SettingSwitch(nativePhrase("允许站内搜索找到我", "Allow site search to find me"), current.searchable) { onSettingsChanged(current.copy(searchable = it)) }
                ProfileSelectionMenu(
                    label = nativePhrase("好友申请", "Friend requests"),
                    value = current.friendRequestPolicy,
                    options = listOf(
                        "everyone" to nativePhrase("允许所有人申请", "Allow requests from everyone"),
                        "none" to nativePhrase("不接收申请", "Do not accept requests"),
                    ),
                    enabled = !saving,
                    onSelected = { onSettingsChanged(current.copy(friendRequestPolicy = it)) },
                )
                ProfileSelectionMenu(
                    label = nativePhrase("陌生私信", "Messages from strangers"),
                    value = current.directMessagePolicy,
                    options = listOf(
                        "everyone" to nativePhrase("所有人可直接私信", "Everyone can message directly"),
                        "request" to nativePhrase("陌生人先进入请求箱", "Strangers go to message requests"),
                        "friends" to nativePhrase("仅好友可私信", "Friends only"),
                        "none" to nativePhrase("不接收私信", "Do not accept messages"),
                    ),
                    enabled = !saving,
                    onSelected = { onSettingsChanged(current.copy(directMessagePolicy = it)) },
                )
                ProfileSelectionMenu(
                    label = nativePhrase("群聊邀请", "Group invitations"),
                    value = current.groupInvitationPolicy,
                    options = listOf(
                        "everyone" to nativePhrase("允许所有人邀请", "Allow invitations from everyone"),
                        "friends" to nativePhrase("仅好友可邀请", "Friends only"),
                        "none" to nativePhrase("不接收邀请", "Do not accept invitations"),
                    ),
                    enabled = !saving,
                    onSelected = { onSettingsChanged(current.copy(groupInvitationPolicy = it)) },
                )
                SettingSwitch(nativePhrase("显示个人介绍", "Show bio"), current.showBio) { onSettingsChanged(current.copy(showBio = it)) }
                SettingSwitch(nativePhrase("显示加入时间", "Show join date"), current.showJoinedAt) { onSettingsChanged(current.copy(showJoinedAt = it)) }
                SettingSwitch(nativePhrase("显示内容与访问统计", "Show content and visit statistics"), current.showStats) { onSettingsChanged(current.copy(showStats = it)) }
                SettingSwitch(nativePhrase("显示订阅数量", "Show subscription counts"), current.showFollowingCount) { onSettingsChanged(current.copy(showFollowingCount = it)) }
                SettingSwitch(nativePhrase("显示代表内容", "Show featured content"), current.showPinnedContent) { onSettingsChanged(current.copy(showPinnedContent = it)) }
                if (current.showPinnedContent) {
                    if (loadingShowcaseOptions) {
                        Text(nativePhrase("正在读取代表内容选项…", "Loading featured content options..."), color = HlovetUi.muted, fontSize = 11.sp)
                    } else {
                        ProfileSelectionMenu(
                            label = nativePhrase("代表文章", "Featured article"),
                            value = current.pinnedArticleId?.toString() ?: "0",
                            options = buildList {
                                add("0" to nativePhrase("暂不设置", "Not set"))
                                articles.forEach { article -> add(article.id.toString() to article.title) }
                            },
                            enabled = !saving,
                            onSelected = { value -> onSettingsChanged(current.copy(pinnedArticleId = value.toIntOrNull()?.takeIf { it > 0 })) },
                        )
                        ProfileSelectionMenu(
                            label = nativePhrase("代表合集", "Featured collection"),
                            value = current.pinnedCollectionId?.toString() ?: "0",
                            options = buildList {
                                add("0" to nativePhrase("暂不设置", "Not set"))
                                collections.forEach { collection -> add(collection.id.toString() to collection.name) }
                            },
                            enabled = !saving,
                            onSelected = { value -> onSettingsChanged(current.copy(pinnedCollectionId = value.toIntOrNull()?.takeIf { it > 0 })) },
                        )
                    }
                }
                EditorSaveButton(nativePhrase("保存主页设置", "Save profile settings"), saving, onSave)
            }
        }
    }
}

@Composable
private fun ChoiceChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label, fontSize = 10.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HlovetUi.accentSoft,
                    selectedLabelColor = HlovetUi.foreground,
                ),
            )
        }
    }
}

/** 展示 label 对应个人设置的 value；options 为代码文案对，enabled 控制可用性，onSelected 回传代码。 */
@Composable
private fun ProfileSelectionMenu(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    // 未匹配到候选时保留服务器原值；选择时关闭菜单，再交由父级保存设置。
    var expanded by remember(value) { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value
    Column {
        Text(label, color = HlovetUi.muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selectedLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("⌄", fontSize = 13.sp, color = HlovetUi.muted)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = HlovetUi.menuShape,
                containerColor = HlovetUi.menuSurface,
                tonalElevation = 0.dp,
            ) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onSelected(optionValue)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        HlovetSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun editorFieldColors() = HlovetUi.fieldColors()
