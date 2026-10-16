@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Close
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

private val nativeSettingThemes = listOf("cloud-blue", "sakura-mist", "night-purple", "custom")
private val nativeSettingVisibilities = listOf("public", "authenticated", "role_restricted", "private")

@Composable
internal fun NativeAdminSettingsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember(token) { mutableStateOf<NativeAdminSiteSettings?>(null) }
    var assets by remember(token) { mutableStateOf<List<NativeAdminSiteAsset>>(emptyList()) }
    var backgrounds by remember(token) { mutableStateOf<List<NativeAdminBackground>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var uploadKind by remember(token) { mutableStateOf<String?>(null) }
    var taxonomyDialog by remember(token) { mutableStateOf<NativeAdminTaxonomy?>(null) }
    var newTaxonomyKind by remember(token) { mutableStateOf("category") }
    var confirmReset by remember(token) { mutableStateOf(false) }
    var confirmDeleteAsset by remember(token) { mutableStateOf<NativeAdminSiteAsset?>(null) }
    var confirmDeleteBackground by remember(token) { mutableStateOf<NativeAdminBackground?>(null) }
    var previewBackground by remember(token) { mutableStateOf<NativeAdminBackground?>(null) }
    var backgroundPickerBusy by remember(token) { mutableStateOf(false) }

    val siteAssetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val kind = uploadKind ?: return@rememberLauncherForActivityResult
        uploadKind = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            try {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, 5L * 1024L * 1024L) }
                val uploaded = withContext(Dispatchers.IO) { NativeAdminSettingsApi.uploadAsset(token ?: error(nativePhrase("登录状态已失效", "The sign-in session has expired")), kind, file) }
                assets = listOf(uploaded) + assets
                settings = settings?.copy(
                    logoPath = if (kind == "logo") "/api${uploaded.url}" else settings!!.logoPath,
                    pwaIconPath = if (kind == "pwa_icon") "/api${uploaded.url}" else settings!!.pwaIconPath,
                )
                saveCurrentSettings(token, settings, onSaved = { notice = if (kind == "logo") nativePhrase("Logo 已上传并应用。", "Logo uploaded and applied.") else nativePhrase("PWA 图标已上传并应用。", "PWA icon uploaded and applied.") }, onError = { error = it })
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点资源上传失败。", "Could not upload the site asset.")
            } finally {
                busy = false
            }
        }
    }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            backgroundPickerBusy = true
            error = null
            notice = null
            try {
                val files = withContext(Dispatchers.IO) {
                    readNativeBackgroundUploads(uris) { uri, maxBytes -> readNativeUploadFile(context, uri, maxBytes) }
                }
                val uploaded = withContext(Dispatchers.IO) { NativeAdminSettingsApi.uploadBackgrounds(token ?: error(nativePhrase("登录状态已失效", "The sign-in session has expired")), files) }
                backgrounds = uploaded + backgrounds
                notice = nativePhrase("${uploaded.size} 张背景图已上传。", "${uploaded.size} background image(s) uploaded.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景图片上传失败。", "Could not upload the background image.")
            } finally {
                backgroundPickerBusy = false
            }
        }
    }

    fun load() {
        val accessToken = token ?: return
        if (!canAccess) return
        scope.launch {
            loading = true
            error = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    Triple(
                        NativeAdminSettingsApi.get(accessToken),
                        NativeAdminSettingsApi.listAssets(accessToken),
                        NativeAdminSettingsApi.listBackgrounds(accessToken),
                    )
                }
                settings = loaded.first
                assets = loaded.second
                backgrounds = loaded.third
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点设置读取失败。", "Could not load site settings.")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            settings = null
            assets = emptyList()
            backgrounds = emptyList()
            loading = false
        } else {
            load()
        }
    }

    fun save() {
        val accessToken = token ?: return
        val current = settings ?: return
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                settings = withContext(Dispatchers.IO) { NativeAdminSettingsApi.update(accessToken, current) }
                notice = nativePhrase("站点设置已保存。", "Site settings saved.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点设置保存失败。", "Could not save site settings.")
            } finally {
                busy = false
            }
        }
    }

    fun reset() {
        val accessToken = token ?: return
        if (busy) return
        confirmReset = false
        busy = true
        scope.launch {
            try {
                settings = withContext(Dispatchers.IO) { NativeAdminSettingsApi.reset(accessToken) }
                notice = nativePhrase("站点设置已恢复默认；资源、背景、分类和标签未删除。", "Site settings restored to defaults; assets, backgrounds, categories, and tags were kept.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("恢复默认设置失败。", "Could not restore default settings.")
            } finally {
                busy = false
            }
        }
    }

    fun activateBackground(item: NativeAdminBackground) {
        val accessToken = token ?: return
        if (busy || item.isActive) return
        busy = true
        scope.launch {
            try {
                val active = withContext(Dispatchers.IO) { NativeAdminSettingsApi.activateBackground(accessToken, item.id) }
                backgrounds = backgrounds.map { it.copy(isActive = it.id == active.id) }
                settings = settings?.copy(defaultBackgroundUrl = "/api${active.url}")
                notice = nativePhrase("全站背景已切换。", "Site background changed.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景切换失败。", "Could not change the background.")
            } finally {
                busy = false
            }
        }
    }

    fun deleteBackground(item: NativeAdminBackground) {
        val accessToken = token ?: return
        if (busy) return
        confirmDeleteBackground = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteBackground(accessToken, item.id) }
                backgrounds = backgrounds.filterNot { it.id == item.id }
                if (item.isActive) settings = settings?.copy(defaultBackgroundUrl = "")
                notice = nativePhrase("背景图片已删除。", "Background image deleted.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景图片删除失败。", "Could not delete the background image.")
            } finally {
                busy = false
            }
        }
    }

    fun deleteAsset(item: NativeAdminSiteAsset) {
        val accessToken = token ?: return
        if (busy) return
        confirmDeleteAsset = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteAsset(accessToken, item.id) }
                assets = assets.filterNot { it.id == item.id }
                notice = nativePhrase("站点资源已删除。", "Site asset deleted.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点资源删除失败。", "Could not delete the site asset.")
            } finally {
                busy = false
            }
        }
    }

    fun saveTaxonomy(item: NativeAdminTaxonomy) {
        val accessToken = token ?: return
        if (busy || item.name.isBlank()) return
        taxonomyDialog = null
        busy = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    if (item.id == 0) NativeAdminSettingsApi.createTaxonomy(accessToken, item.kind, item.name.trim(), item.color, item.sortOrder, item.enabled)
                    else NativeAdminSettingsApi.updateTaxonomy(accessToken, item)
                }
                val current = settings ?: return@launch
                val next = if (saved.kind == "tag") current.copy(tags = (current.tags.filterNot { it.id == saved.id } + saved).sortedBy { it.sortOrder }) else current.copy(categories = (current.categories.filterNot { it.id == saved.id } + saved).sortedBy { it.sortOrder })
                settings = next
                notice = if (item.id == 0) nativePhrase("分类或标签已新增。", "Category or tag added.") else nativePhrase("分类或标签已更新。", "Category or tag updated.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("分类或标签保存失败。", "Could not save the category or tag.")
            } finally {
                busy = false
            }
        }
    }

    fun deleteTaxonomy(item: NativeAdminTaxonomy) {
        val accessToken = token ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteTaxonomy(accessToken, item.id) }
                settings = settings?.let { current -> if (item.kind == "tag") current.copy(tags = current.tags.filterNot { it.id == item.id }) else current.copy(categories = current.categories.filterNot { it.id == item.id }) }
                notice = nativePhrase("分类或标签已删除。", "Category or tag deleted.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("分类或标签删除失败。", "Could not delete the category or tag.")
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("站点设置", "Site settings"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("站点身份、外观、资源和内容规则", "Site identity, appearance, assets, and content rules"), color = HlovetUi.muted, fontSize = 10.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = !loading && !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
                    IconButton(onClick = { confirmReset = true }, enabled = !loading && !busy) { Icon(Icons.Filled.Restore, contentDescription = nativePhrase("恢复默认", "Restore defaults"), tint = HlovetUi.secondaryAccent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) item { NativeSettingsMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        if (token.isNullOrBlank()) item { NativeSettingsMessage(nativePhrase("登录后查看站点设置。", "Sign in to view site settings."), false, null) }
        else if (!canAccess) item { NativeSettingsMessage(nativePhrase("站点设置仅超级管理员可访问。", "Only super administrators can access site settings."), true, null) }
        else if (loading && settings == null) item { NativeSettingsMessage(nativePhrase("正在读取站点设置…", "Loading site settings..."), false, null) }
        else settings?.let { current ->
            item {
                NativeSettingsCard(nativePhrase("站点身份", "Site identity"), Icons.Filled.Settings) {
                    NativeSettingsField(nativePhrase("站点名称", "Site name"), current.siteName, !busy) { settings = current.copy(siteName = it) }
                    NativeSettingsField(nativePhrase("浏览器标题", "Browser title"), current.browserTitle, !busy) { settings = current.copy(browserTitle = it) }
                    NativeSettingsAssetPicker("Logo", current.logoPath, assets.filter { it.kind == "logo" }, !busy, onSelect = { settings = current.copy(logoPath = it) }, onUpload = { uploadKind = "logo"; siteAssetPicker.launch(arrayOf("image/*")) }, onDelete = { confirmDeleteAsset = it })
                    NativeSettingsAssetPicker(nativePhrase("PWA 图标", "PWA icon"), current.pwaIconPath, assets.filter { it.kind == "pwa_icon" }, !busy, onSelect = { settings = current.copy(pwaIconPath = it) }, onUpload = { uploadKind = "pwa_icon"; siteAssetPicker.launch(arrayOf("image/*")) }, onDelete = { confirmDeleteAsset = it })
                }
            }
            item {
                NativeSettingsCard(nativePhrase("外观与毛玻璃", "Appearance and glass"), Icons.Filled.Palette) {
                    NativeSettingsMenuField(nativePhrase("默认主题", "Default theme"), current.defaultTheme.themeId, nativeSettingThemes, !busy) { settings = current.copy(defaultTheme = current.defaultTheme.copy(themeId = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NativeSettingsField(nativePhrase("强调色", "Accent color"), current.defaultTheme.accent, !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(accent = normalizeHex(it))) }
                        NativeSettingsField(nativePhrase("表面色", "Surface color"), current.defaultTheme.surface, !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(surface = normalizeHex(it))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NativeSettingsField(nativePhrase("正文色", "Text color"), current.defaultTheme.foreground, !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(foreground = normalizeHex(it))) }
                        NativeSettingsField(nativePhrase("弱化文字色", "Muted text color"), current.defaultTheme.muted, !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(muted = normalizeHex(it))) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        NativeSettingsNumberField(nativePhrase("卡片透明度 38-76", "Card opacity 38-76"), current.defaultTheme.cardAlpha.toString(), !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(cardAlpha = it.toIntOrNull()?.coerceIn(38, 76) ?: current.defaultTheme.cardAlpha)) }
                        NativeSettingsNumberField(nativePhrase("模糊 0-36", "Blur 0-36"), current.defaultTheme.glassBlur.toString(), !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(glassBlur = it.toIntOrNull()?.coerceIn(0, 36) ?: current.defaultTheme.glassBlur)) }
                        NativeSettingsNumberField(nativePhrase("色调透明度", "Tint opacity"), current.defaultTheme.glassTintAlpha.toString(), !busy, Modifier.weight(1f)) { settings = current.copy(defaultTheme = current.defaultTheme.copy(glassTintAlpha = it.toIntOrNull()?.coerceIn(0, 100) ?: current.defaultTheme.glassTintAlpha)) }
                    }
                    NativeSettingsField(nativePhrase("玻璃色调", "Glass tint"), current.defaultTheme.glassTint, !busy) { settings = current.copy(defaultTheme = current.defaultTheme.copy(glassTint = normalizeHex(it))) }
                }
            }
            item {
                NativeSettingsCard(nativePhrase("运行规则", "Runtime rules"), Icons.Filled.Security) {
                    NativeSettingsToggle(nativePhrase("开放注册", "Open registration"), current.registrationOpen, !busy) { settings = current.copy(registrationOpen = it) }
                    NativeSettingsField(nativePhrase("新用户默认角色", "Default role for new users"), current.defaultRoleCode, !busy) { settings = current.copy(defaultRoleCode = it) }
                    NativeSettingsToggle(nativePhrase("启用安装页面", "Enable install page"), current.installPageEnabled, !busy) { settings = current.copy(installPageEnabled = it) }
                    NativeSettingsToggle(nativePhrase("保留 APK 历史版本", "Keep APK history"), current.apkHistoryEnabled, !busy) { settings = current.copy(apkHistoryEnabled = it) }
                    NativeSettingsToggle(nativePhrase("自动清理旧 APK", "Auto-clean old APKs"), current.apkAutoCleanupEnabled, !busy) { settings = current.copy(apkAutoCleanupEnabled = it) }
                    NativeSettingsNumberField(nativePhrase("APK 保留数量 1-20", "APK retention count 1-20"), current.apkRetentionCount.toString(), !busy) { settings = current.copy(apkRetentionCount = it.toIntOrNull()?.coerceIn(1, 20) ?: current.apkRetentionCount) }
                    NativeSettingsMenuField(nativePhrase("默认文章可见范围", "Default article visibility"), current.defaultArticleVisibility, nativeSettingVisibilities, !busy) { settings = current.copy(defaultArticleVisibility = it) }
                    NativeSettingsNumberField(nativePhrase("文章图片大小 1-30 MB", "Article image size 1-30 MB"), current.articleImageMaxSizeMb.toString(), !busy) { settings = current.copy(articleImageMaxSizeMb = it.toIntOrNull()?.coerceIn(1, 30) ?: current.articleImageMaxSizeMb) }
                    NativeSettingsToggle(nativePhrase("允许评论", "Allow comments"), current.commentsEnabled, !busy) { settings = current.copy(commentsEnabled = it) }
                    NativeSettingsToggle(nativePhrase("允许举报", "Allow reports"), current.reportsEnabled, !busy) { settings = current.copy(reportsEnabled = it) }
                }
            }
            item {
                NativeSettingsCard(nativePhrase("全站背景", "Site backgrounds"), Icons.Filled.Image) {
                    Text(nativePhrase("上传、启用或删除全站背景；最多选择 5 张。", "Upload, activate, or delete site backgrounds; select up to 5."), color = HlovetUi.muted, fontSize = 10.sp)
                    TextButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }, enabled = !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (backgroundPickerBusy) nativePhrase("上传中…", "Uploading…") else nativePhrase("选择背景图片", "Choose background images"), fontSize = 11.sp) }
                    if (backgrounds.isEmpty()) Text(nativePhrase("还没有上传背景图片。", "No background images uploaded."), color = HlovetUi.muted, fontSize = 11.sp)
                    backgrounds.forEach { item ->
                        NativeBackgroundRow(
                            item,
                            !busy,
                            onPreview = { previewBackground = item },
                            onUse = { activateBackground(item) },
                            onDelete = { confirmDeleteBackground = item },
                        )
                    }
                }
            }
            item {
                NativeSettingsCard(nativePhrase("文章分类", "Article categories"), Icons.Filled.Folder) {
                    TextButton(onClick = { newTaxonomyKind = "category"; taxonomyDialog = NativeAdminTaxonomy(0, "category", "", "#0284C7", 0, true, "", "") }, enabled = !busy) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("新增分类", "Add category"), fontSize = 11.sp) }
                    current.categories.forEach { taxonomy -> NativeTaxonomyRow(taxonomy, !busy, onEdit = { taxonomyDialog = taxonomy }, onDelete = { deleteTaxonomy(taxonomy) }) }
                    if (current.categories.isEmpty()) Text(nativePhrase("暂无分类。", "No categories."), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            item {
                NativeSettingsCard(nativePhrase("文章标签", "Article tags"), Icons.Filled.Folder) {
                    TextButton(onClick = { newTaxonomyKind = "tag"; taxonomyDialog = NativeAdminTaxonomy(0, "tag", "", "#0284C7", 0, true, "", "") }, enabled = !busy) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("新增标签", "Add tag"), fontSize = 11.sp) }
                    current.tags.forEach { taxonomy -> NativeTaxonomyRow(taxonomy, !busy, onEdit = { taxonomyDialog = taxonomy }, onDelete = { deleteTaxonomy(taxonomy) }) }
                    if (current.tags.isEmpty()) Text(nativePhrase("暂无标签。", "No tags."), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            item {
                NativeNotificationSettingsCard(current.notifications, !busy) { next -> settings = current.copy(notifications = next) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::save, enabled = !busy) { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存全部设置", "Save all settings"), fontSize = 12.sp) }
                }
            }
        }
    }

    taxonomyDialog?.let { draft -> NativeTaxonomyDialog(draft, !busy, onDismiss = { taxonomyDialog = null }, onSave = ::saveTaxonomy) }
    confirmReset.takeIf { it }?.let { HlovetAlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(nativePhrase("恢复默认站点设置", "Restore default site settings")) }, text = { Text(nativePhrase("只会恢复设置值；已上传的 Logo、PWA 图标、背景、分类和标签不会删除。", "Only setting values are restored; uploaded logos, PWA icons, backgrounds, categories, and tags are kept."), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = ::reset) { Text(nativePhrase("恢复默认", "Restore defaults"), color = HlovetUi.secondaryAccent) } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(nativePhrase("取消", "Cancel")) } }) }
    confirmDeleteAsset?.let { item -> HlovetAlertDialog(onDismissRequest = { confirmDeleteAsset = null }, title = { Text(nativePhrase("删除站点资源", "Delete site asset")) }, text = { Text(nativePhrase("确定永久删除“${item.originalName}”吗？如果资源正在使用，请先选择其他资源。", "Permanently delete “${item.originalName}”? If it is in use, select another asset first."), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { deleteAsset(item) }) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } }, dismissButton = { TextButton(onClick = { confirmDeleteAsset = null }) { Text(nativePhrase("取消", "Cancel")) } }) }
    confirmDeleteBackground?.let { item -> HlovetAlertDialog(onDismissRequest = { confirmDeleteBackground = null }, title = { Text(nativePhrase("删除背景图片", "Delete background image")) }, text = { Text(if (item.isActive) nativePhrase("删除当前背景后会回到内置背景，确定继续吗？", "Deleting the active background will restore the built-in background. Continue?") else nativePhrase("确定从服务器永久删除“${item.originalName}”吗？", "Permanently delete “${item.originalName}” from the server?"), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { deleteBackground(item) }) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } }, dismissButton = { TextButton(onClick = { confirmDeleteBackground = null }) { Text(nativePhrase("取消", "Cancel")) } }) }
    previewBackground?.let { item ->
        HlovetAlertDialog(
            onDismissRequest = { previewBackground = null },
            title = { Text(item.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                NativeCoverImage(
                    path = item.url,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                    fallbackText = item.originalName,
                    tint = HlovetUi.accent,
                    accessToken = token,
                    cacheOwner = "admin-background-preview",
                )
            },
            confirmButton = { TextButton(onClick = { previewBackground = null }) { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("关闭", "Close")) } },
        )
    }
}

private suspend fun saveCurrentSettings(token: String?, settings: NativeAdminSiteSettings?, onSaved: () -> Unit, onError: (String) -> Unit) {
    if (token.isNullOrBlank() || settings == null) return
    runCatching { withContext(Dispatchers.IO) { NativeAdminSettingsApi.update(token, settings) } }.onSuccess { onSaved() }.onFailure { onError(it.message ?: nativePhrase("站点设置保存失败。", "Could not save site settings.")) }
}

@Composable
private fun NativeSettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}

@Composable
private fun NativeSettingsField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, modifier = modifier, singleLine = true, label = { Text(label, fontSize = 10.sp) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeSettingsFieldColors())
}

@Composable
private fun NativeSettingsNumberField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, enabled = enabled, modifier = modifier, singleLine = true, label = { Text(label, fontSize = 9.sp) }, textStyle = TextStyle(fontSize = 11.sp), colors = nativeSettingsFieldColors())
}

/** 选择 label 对应站点配置的 value；options 限定可选值，enabled 控制交互，onChange 仅更新父级草稿。 */
@Composable
private fun NativeSettingsMenuField(label: String, value: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    // 菜单只回传候选值，不在组件中保存站点配置。
    var expanded by remember(value) { mutableStateOf(false) }
    Column {
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text(value, fontSize = 11.sp); Text("⌄", modifier = Modifier.padding(start = 7.dp), fontSize = 12.sp) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) { options.forEach { option -> DropdownMenuItem(text = { Text(option, fontSize = 11.sp) }, onClick = { expanded = false; onChange(option) }) } }
        }
    }
}

@Composable
private fun NativeSettingsToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f), color = if (enabled) HlovetUi.foreground else HlovetUi.muted); HlovetSwitch(checked, onChange, enabled = enabled) }
}

@Composable
private fun NativeSettingsAssetPicker(label: String, currentPath: String, assets: List<NativeAdminSiteAsset>, enabled: Boolean, onSelect: (String) -> Unit, onUpload: () -> Unit, onDelete: (NativeAdminSiteAsset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(currentPath.ifBlank { nativePhrase("使用内置默认资源", "Using built-in default asset") }, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUpload, enabled = enabled) { Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("上传", "Upload"), fontSize = 10.sp) }
            assets.forEach { asset ->
                TextButton(onClick = { onSelect("/api${asset.url}") }, enabled = enabled) { Text(asset.originalName, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = { onDelete(asset) }, enabled = enabled, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除 ${asset.originalName}", "Delete ${asset.originalName}"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(15.dp)) }
            }
        }
    }
}

@Composable
private fun NativeBackgroundRow(item: NativeAdminBackground, enabled: Boolean, onPreview: () -> Unit, onUse: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).clickable(onClick = onPreview), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = if (item.isActive) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) { Text(item.originalName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (item.isActive) nativePhrase("当前全站背景", "Active site background") else formatNativeBytes(item.sizeBytes), color = if (item.isActive) HlovetUi.accent else HlovetUi.muted, fontSize = 9.sp) }
            TextButton(onClick = onUse, enabled = enabled && !item.isActive) { Text(if (item.isActive) nativePhrase("使用中", "Active") else nativePhrase("使用", "Use"), fontSize = 10.sp) }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(29.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除", "Delete"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun NativeTaxonomyRow(item: NativeAdminTaxonomy, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = parseNativeColor(item.color, HlovetUi.accent), fontSize = 13.sp); Spacer(Modifier.width(6.dp)); Text(item.name, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(if (item.enabled) nativePhrase("启用", "Enabled") else nativePhrase("停用", "Disabled"), color = HlovetUi.muted, fontSize = 9.sp); IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.size(29.dp)) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑", "Edit"), tint = HlovetUi.accent, modifier = Modifier.size(15.dp)) }; IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(29.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除", "Delete"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun NativeTaxonomyDialog(item: NativeAdminTaxonomy, enabled: Boolean, onDismiss: () -> Unit, onSave: (NativeAdminTaxonomy) -> Unit) {
    var draft by remember(item.id, item.kind) { mutableStateOf(item) }
    HlovetAlertDialog(onDismissRequest = onDismiss, title = { Text(if (item.id == 0) nativePhrase("新增${if (item.kind == "tag") "标签" else "分类"}", "Add ${if (item.kind == "tag") "tag" else "category"}") else nativePhrase("编辑${if (item.kind == "tag") "标签" else "分类"}", "Edit ${if (item.kind == "tag") "tag" else "category"}")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { NativeSettingsField(nativePhrase("名称", "Name"), draft.name, enabled) { draft = draft.copy(name = it) }; NativeSettingsField(nativePhrase("颜色（#RRGGBB）", "Color (#RRGGBB)"), draft.color, enabled) { draft = draft.copy(color = normalizeHex(it)) }; NativeSettingsNumberField(nativePhrase("排序", "Sort order"), draft.sortOrder.toString(), enabled) { draft = draft.copy(sortOrder = it.toIntOrNull() ?: draft.sortOrder) }; NativeSettingsToggle(nativePhrase("启用", "Enabled"), draft.enabled, enabled) { draft = draft.copy(enabled = it) } } }, confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = enabled && draft.name.isNotBlank()) { Text(nativePhrase("保存", "Save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(nativePhrase("取消", "Cancel")) } })
}

@Composable
private fun NativeNotificationSettingsCard(current: NativeAdminNotificationSettings, enabled: Boolean, onChange: (NativeAdminNotificationSettings) -> Unit) {
    NativeSettingsCard(nativePhrase("通知与模板", "Notifications and templates"), Icons.Filled.Visibility) {
        NativeSettingsToggle(nativePhrase("文章被点赞", "Article liked"), current.notifyArticleLiked, enabled) { onChange(current.copy(notifyArticleLiked = it)) }
        NativeSettingsToggle(nativePhrase("文章被收藏", "Article bookmarked"), current.notifyArticleFavorited, enabled) { onChange(current.copy(notifyArticleFavorited = it)) }
        NativeSettingsToggle(nativePhrase("文章收到评论", "Article commented"), current.notifyArticleCommented, enabled) { onChange(current.copy(notifyArticleCommented = it)) }
        NativeSettingsToggle(nativePhrase("评论收到回复", "Comment replied to"), current.notifyCommentReplied, enabled) { onChange(current.copy(notifyCommentReplied = it)) }
        NativeSettingsToggle(nativePhrase("作者订阅", "Author subscribed"), current.notifyAuthorSubscribed, enabled) { onChange(current.copy(notifyAuthorSubscribed = it)) }
        NativeSettingsToggle(nativePhrase("订阅文章发布", "Subscribed article published"), current.notifySubscriptionPublished, enabled) { onChange(current.copy(notifySubscriptionPublished = it)) }
        NativeSettingsToggle(nativePhrase("好友请求", "Friend request"), current.notifyFriendRequest, enabled) { onChange(current.copy(notifyFriendRequest = it)) }
        NativeSettingsToggle(nativePhrase("举报处理", "Report handled"), current.notifyCommentReport, enabled) { onChange(current.copy(notifyCommentReport = it)) }
        NativeSettingsToggle(nativePhrase("系统通知", "System notification"), current.notifySystem, enabled) { onChange(current.copy(notifySystem = it)) }
        Text(nativePhrase("中文通知模板", "Chinese notification templates"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        NativeSettingsField(nativePhrase("点赞", "Like"), current.articleLiked, enabled) { onChange(current.copy(articleLiked = it)) }
        NativeSettingsField(nativePhrase("收藏", "Favorite"), current.articleFavorited, enabled) { onChange(current.copy(articleFavorited = it)) }
        NativeSettingsField(nativePhrase("评论", "Comment"), current.articleCommented, enabled) { onChange(current.copy(articleCommented = it)) }
        NativeSettingsField(nativePhrase("回复", "Reply"), current.commentReplied, enabled) { onChange(current.copy(commentReplied = it)) }
        NativeSettingsField(nativePhrase("订阅", "Subscription"), current.authorSubscribed, enabled) { onChange(current.copy(authorSubscribed = it)) }
        NativeSettingsField(nativePhrase("订阅发布", "Published"), current.subscriptionPublished, enabled) { onChange(current.copy(subscriptionPublished = it)) }
        NativeSettingsField(nativePhrase("好友请求", "Friend request"), current.friendRequest, enabled) { onChange(current.copy(friendRequest = it)) }
        NativeSettingsField(nativePhrase("举报处理", "Report handled"), current.commentReportHandled, enabled) { onChange(current.copy(commentReportHandled = it)) }
        NativeSettingsField(nativePhrase("违规处理", "Moderation"), current.commentAuthorModerated, enabled) { onChange(current.copy(commentAuthorModerated = it)) }
        Text(nativePhrase("English templates", "English templates"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        NativeSettingsField("Like", current.articleLikedEn, enabled) { onChange(current.copy(articleLikedEn = it)) }
        NativeSettingsField("Favorite", current.articleFavoritedEn, enabled) { onChange(current.copy(articleFavoritedEn = it)) }
        NativeSettingsField("Comment", current.articleCommentedEn, enabled) { onChange(current.copy(articleCommentedEn = it)) }
        NativeSettingsField("Reply", current.commentRepliedEn, enabled) { onChange(current.copy(commentRepliedEn = it)) }
        NativeSettingsField("Subscription", current.authorSubscribedEn, enabled) { onChange(current.copy(authorSubscribedEn = it)) }
        NativeSettingsField("Published", current.subscriptionPublishedEn, enabled) { onChange(current.copy(subscriptionPublishedEn = it)) }
        NativeSettingsField("Friend request", current.friendRequestEn, enabled) { onChange(current.copy(friendRequestEn = it)) }
        NativeSettingsField("Report handled", current.commentReportHandledEn, enabled) { onChange(current.copy(commentReportHandledEn = it)) }
        NativeSettingsField("Moderation", current.commentAuthorModeratedEn, enabled) { onChange(current.copy(commentAuthorModeratedEn = it)) }
    }
}

@Composable
private fun NativeSettingsMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isError) Icons.Filled.Security else Icons.Filled.Storage, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Close"), fontSize = 10.sp) } } } }
}

@Composable
private fun nativeSettingsFieldColors() = HlovetUi.fieldColors()

private fun normalizeHex(value: String): String = if (value.trim().startsWith("#")) value.trim().uppercase() else "#${value.trim().uppercase()}"
private fun parseNativeColor(value: String, fallback: Color): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)
private fun formatNativeBytes(value: Long): String = if (value < 1024 * 1024) "${value / 1024} KB" else "${"%.1f".format(value.toDouble() / 1024 / 1024)} MB"
