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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val nativeSettingThemes = listOf("cloud-blue", "sakura-mist", "night-purple", "custom")
private val nativeSettingVisibilities = listOf("public", "authenticated", "role_restricted", "private")

/** 按账号、令牌和权限隔离站点设置，避免切换身份后复用旧管理员的草稿、弹窗或请求。 */
@Composable
internal fun NativeAdminSettingsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 权限变化销毁整个页面作用域，让未完成的旧请求不能填充新身份的页面。
    key(session?.userId, session?.accessToken, session?.isAdministrator, session?.isSuperAdmin) {
        NativeAdminSettingsContent(session, onBack)
    }
}

/** 编辑 session 的全站配置；onBack 返回上级，配置及资源操作仍由服务器校验超级管理员权限。 */
@Composable
private fun NativeAdminSettingsContent(session: NativeSession?, onBack: () -> Unit) {
    // 管理权限既限制界面，也限制读取和修改请求；页面状态仅属于当前身份。
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember(token) { mutableStateOf<NativeAdminSiteSettings?>(null) }
    var assets by remember(token) { mutableStateOf<List<NativeAdminSiteAsset>>(emptyList()) }
    var backgrounds by remember(token) { mutableStateOf<List<NativeAdminBackground>>(emptyList()) }
    var roles by remember(token) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var rolesLoading by remember(token) { mutableStateOf(false) }
    var rolesError by remember(token) { mutableStateOf<String?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var uploadKind by remember(token) { mutableStateOf<String?>(null) }
    var taxonomyDialog by remember(token) { mutableStateOf<NativeAdminTaxonomy?>(null) }
    var taxonomyError by remember(token) { mutableStateOf<String?>(null) }
    var confirmReset by remember(token) { mutableStateOf(false) }
    var confirmDeleteAsset by remember(token) { mutableStateOf<NativeAdminSiteAsset?>(null) }
    var confirmDeleteBackground by remember(token) { mutableStateOf<NativeAdminBackground?>(null) }
    var previewBackground by remember(token) { mutableStateOf<NativeAdminBackground?>(null) }
    var backgroundPickerBusy by remember(token) { mutableStateOf(false) }

    // 系统资源选择返回后使用当前身份上传；取消选择不修改配置。
    val siteAssetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val kind = uploadKind ?: return@rememberLauncherForActivityResult
        uploadKind = null
        if (uri == null || busy || backgroundPickerBusy || !canAccess) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            try {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, 5L * 1024L * 1024L) }
                val uploaded = withContext(Dispatchers.IO) { NativeAdminSettingsApi.uploadAsset(token ?: error(nativePhrase("登录状态已失效", "The sign-in session has expired")), kind, file) }
                assets = listOf(uploaded) + assets
                val next = settings?.copy(
                    logoPath = if (kind == "logo") nativeSettingsAssetPath(uploaded.url) else settings!!.logoPath,
                    pwaIconPath = if (kind == "pwa_icon") nativeSettingsAssetPath(uploaded.url) else settings!!.pwaIconPath,
                )
                settings = next
                // 上传后由服务端回读最终设置；应用失败时保留已上传资源，允许用户明确重试保存。
                if (next != null) settings = withContext(Dispatchers.IO) { NativeAdminSettingsApi.update(requireNotNull(token), next) }
                notice = if (kind == "logo") nativePhrase("Logo 已上传并应用。", "Logo uploaded and applied.") else nativePhrase("PWA 图标已上传并应用。", "PWA icon uploaded and applied.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点资源上传失败。", "Could not upload the site asset.")
            } finally {
                busy = false
            }
        }
    }

    // 背景沿用服务器的五张、单张 30 MB 限制，不照搬 PC 端过宽的选择数量常量。
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty() || busy || backgroundPickerBusy || !canAccess) return@rememberLauncherForActivityResult
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
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景图片上传失败。", "Could not upload the background image.")
            } finally {
                backgroundPickerBusy = false
            }
        }
    }

    /** 为当前身份读取设置、资源和背景；无令牌或无权限时不发送请求。 */
    fun load() {
        // 三类结果作为同一次页面读取发布，失败时保留可重试提示。
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
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点设置读取失败。", "Could not load site settings.")
            } finally {
                loading = false
            }
        }
    }

    // 角色来自服务端候选，失败只禁用角色选择，不用手填代码替代权限配置。
    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) return@LaunchedEffect
        rolesLoading = true
        rolesError = null
        roles = emptyList()
        try {
            roles = withContext(Dispatchers.IO) { NativeRoleApi.listRoles() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            rolesError = nativePhrase("角色列表读取失败，请刷新重试。", "Could not load roles. Refresh to retry.")
        } finally {
            rolesLoading = false
        }
    }

    // 页面入口先处理登录与权限，刷新仅重新读取当前身份的数据。
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

    /** 保存当前配置快照，成功后采用服务端规范化结果并显示反馈。 */
    fun save() {
        // 资源上传期间不允许整页保存，避免旧快照覆盖刚上传的配置。
        val accessToken = token ?: return
        val current = settings ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                settings = withContext(Dispatchers.IO) { NativeAdminSettingsApi.update(accessToken, current) }
                notice = nativePhrase("站点设置已保存。", "Site settings saved.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点设置保存失败。", "Could not save site settings.")
            } finally {
                busy = false
            }
        }
    }

    /** 经确认恢复设置值；保留已上传资源、背景与分类标签。 */
    fun reset() {
        // 同一身份的写操作串行执行，不在上传期间重置配置。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        confirmReset = false
        busy = true
        scope.launch {
            try {
                settings = withContext(Dispatchers.IO) { NativeAdminSettingsApi.reset(accessToken) }
                notice = nativePhrase("站点设置已恢复默认；资源、背景、分类和标签未删除。", "Site settings restored to defaults; assets, backgrounds, categories, and tags were kept.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("恢复默认设置失败。", "Could not restore default settings.")
            } finally {
                busy = false
            }
        }
    }

    /** 启用 item 对应背景，并按服务端返回的 ID 更新当前活动状态。 */
    fun activateBackground(item: NativeAdminBackground) {
        // 已启用项及资源上传期间不重复写入。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess || item.isActive) return
        busy = true
        scope.launch {
            try {
                val active = withContext(Dispatchers.IO) { NativeAdminSettingsApi.activateBackground(accessToken, item.id) }
                backgrounds = backgrounds.map { it.copy(isActive = it.id == active.id) }
                settings = settings?.copy(defaultBackgroundUrl = nativeSettingsAssetPath(active.url))
                notice = nativePhrase("全站背景已切换。", "Site background changed.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景切换失败。", "Could not change the background.")
            } finally {
                busy = false
            }
        }
    }

    /** 删除已确认的 item 背景；只有服务端成功后才移出本地列表。 */
    fun deleteBackground(item: NativeAdminBackground) {
        // 使用确认时的 ID，不让其他上传或写入与删除交错。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        confirmDeleteBackground = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteBackground(accessToken, item.id) }
                backgrounds = backgrounds.filterNot { it.id == item.id }
                if (item.isActive) settings = settings?.copy(defaultBackgroundUrl = "")
                notice = nativePhrase("背景图片已删除。", "Background image deleted.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("背景图片删除失败。", "Could not delete the background image.")
            } finally {
                busy = false
            }
        }
    }

    /** 删除已确认的 item 站点资源，失败保留列表以便再次处理。 */
    fun deleteAsset(item: NativeAdminSiteAsset) {
        // 只提交资源稳定 ID，服务器继续阻止删除正在使用的资源。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        confirmDeleteAsset = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteAsset(accessToken, item.id) }
                assets = assets.filterNot { it.id == item.id }
                notice = nativePhrase("站点资源已删除。", "Site asset deleted.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("站点资源删除失败。", "Could not delete the site asset.")
            } finally {
                busy = false
            }
        }
    }

    /** 保存 item 分类或标签；失败保留弹窗和输入，成功后才关闭并更新当前列表。 */
    fun saveTaxonomy(item: NativeAdminTaxonomy) {
        // 与后端名称、颜色和排序约束一致，校验失败不发送请求。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        taxonomyError = when {
            item.name.isBlank() -> nativePhrase("名称不能为空。", "Name is required.")
            item.name.trim().length > 80 -> nativePhrase("名称不能超过 80 个字符。", "Name cannot exceed 80 characters.")
            !Regex("^#[0-9a-fA-F]{6}$").matches(item.color) -> nativePhrase("颜色必须为 #RRGGBB 格式。", "Use a #RRGGBB color.")
            item.sortOrder !in 0..9999 -> nativePhrase("排序必须在 0 到 9999 之间。", "Sort order must be between 0 and 9999.")
            else -> null
        }
        if (taxonomyError != null) return
        busy = true
        notice = null
        scope.launch {
            try {
                // 新增和编辑使用不同接口，网络失败不能通过自动重试产生重复记录。
                val saved = withContext(Dispatchers.IO) {
                    if (item.id == 0) NativeAdminSettingsApi.createTaxonomy(accessToken, item.kind, item.name.trim(), item.color, item.sortOrder, item.enabled)
                    else NativeAdminSettingsApi.updateTaxonomy(accessToken, item.copy(name = item.name.trim()))
                }
                check(saved.id > 0 && saved.kind == item.kind) { nativePhrase("服务器未返回有效的分类或标签，请刷新后确认。", "The server did not return a valid category or tag. Refresh to confirm.") }
                val current = settings ?: return@launch
                val next = if (saved.kind == "tag") current.copy(tags = (current.tags.filterNot { it.id == saved.id } + saved).sortedBy { it.sortOrder }) else current.copy(categories = (current.categories.filterNot { it.id == saved.id } + saved).sortedBy { it.sortOrder })
                settings = next
                taxonomyDialog = null
                notice = if (item.id == 0) nativePhrase("分类或标签已新增。", "Category or tag added.") else nativePhrase("分类或标签已更新。", "Category or tag updated.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                taxonomyError = exception.message ?: nativePhrase("分类或标签保存失败。", "Could not save the category or tag.")
            } finally {
                busy = false
            }
        }
    }

    /** 删除 item 分类或标签，成功后按种类移出对应列表，不影响另一类配置。 */
    fun deleteTaxonomy(item: NativeAdminTaxonomy) {
        // 使用当前身份及稳定 ID，禁止与上传和其它配置写入并发。
        val accessToken = token ?: return
        if (busy || backgroundPickerBusy || !canAccess) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAdminSettingsApi.deleteTaxonomy(accessToken, item.id) }
                settings = settings?.let { current -> if (item.kind == "tag") current.copy(tags = current.tags.filterNot { it.id == item.id }) else current.copy(categories = current.categories.filterNot { it.id == item.id }) }
                notice = nativePhrase("分类或标签已删除。", "Category or tag deleted.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("分类或标签删除失败。", "Could not delete the category or tag.")
            } finally {
                busy = false
            }
        }
    }

    // 配置复用现有玻璃组件；长页面由单一懒加载列表滚动，弹窗另行限制内容高度。
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("admin-site-settings-list"), contentPadding = PaddingValues(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("站点设置", "Site settings"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("站点身份、外观、资源和内容规则", "Site identity, appearance, assets, and content rules"), color = HlovetUi.muted, fontSize = 10.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    // 无权限或上传期间不能触发刷新和恢复，避免入口仍可操作造成误导。
                    IconButton(onClick = { reloadKey++ }, enabled = canAccess && !token.isNullOrBlank() && !loading && !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
                    IconButton(onClick = { confirmReset = true }, enabled = canAccess && !token.isNullOrBlank() && !loading && !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Restore, contentDescription = nativePhrase("恢复默认", "Restore defaults"), tint = HlovetUi.secondaryAccent) }
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
                    NativeSettingsAssetPicker("Logo", "logo", current.logoPath, assets.filter { it.kind == "logo" }, !busy && !backgroundPickerBusy, onSelect = { settings = current.copy(logoPath = it) }, onUpload = { uploadKind = "logo"; siteAssetPicker.launch(arrayOf("image/*")) }, onDelete = { confirmDeleteAsset = it })
                    NativeSettingsAssetPicker(nativePhrase("PWA 图标", "PWA icon"), "pwa_icon", current.pwaIconPath, assets.filter { it.kind == "pwa_icon" }, !busy && !backgroundPickerBusy, onSelect = { settings = current.copy(pwaIconPath = it) }, onUpload = { uploadKind = "pwa_icon"; siteAssetPicker.launch(arrayOf("image/*")) }, onDelete = { confirmDeleteAsset = it })
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
                    NativeSettingsMenuField(
                        nativePhrase("新用户默认角色", "Default role for new users"), current.defaultRoleCode,
                        roles.map { it.code }, !busy && !backgroundPickerBusy && !rolesLoading && roles.isNotEmpty(),
                        modifier = Modifier.testTag("site-default-role"),
                        optionLabel = { code -> roles.firstOrNull { it.code == code }?.name ?: code },
                    ) { settings = current.copy(defaultRoleCode = it) }
                    rolesError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
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
                            !busy && !backgroundPickerBusy,
                            onPreview = { previewBackground = item },
                            onUse = { activateBackground(item) },
                            onDelete = { confirmDeleteBackground = item },
                        )
                    }
                }
            }
            item {
                NativeSettingsCard(nativePhrase("文章分类", "Article categories"), Icons.Filled.Folder) {
                    TextButton(onClick = { taxonomyError = null; taxonomyDialog = NativeAdminTaxonomy(0, "category", "", "#0284C7", 0, true, "", "") }, enabled = !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("新增分类", "Add category"), fontSize = 11.sp) }
                    current.categories.forEach { taxonomy -> NativeTaxonomyRow(taxonomy, !busy && !backgroundPickerBusy, onEdit = { taxonomyError = null; taxonomyDialog = taxonomy }, onDelete = { deleteTaxonomy(taxonomy) }) }
                    if (current.categories.isEmpty()) Text(nativePhrase("暂无分类。", "No categories."), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            item {
                NativeSettingsCard(nativePhrase("文章标签", "Article tags"), Icons.Filled.Folder) {
                    TextButton(onClick = { taxonomyError = null; taxonomyDialog = NativeAdminTaxonomy(0, "tag", "", "#0284C7", 0, true, "", "") }, enabled = !busy && !backgroundPickerBusy) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp)); Text(nativePhrase("新增标签", "Add tag"), fontSize = 11.sp) }
                    current.tags.forEach { taxonomy -> NativeTaxonomyRow(taxonomy, !busy && !backgroundPickerBusy, onEdit = { taxonomyError = null; taxonomyDialog = taxonomy }, onDelete = { deleteTaxonomy(taxonomy) }) }
                    if (current.tags.isEmpty()) Text(nativePhrase("暂无标签。", "No tags."), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            item {
                NativeNotificationSettingsCard(current.notifications, !busy) { next -> settings = current.copy(notifications = next) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::save, enabled = !busy && !backgroundPickerBusy) { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存全部设置", "Save all settings"), fontSize = 12.sp) }
                }
            }
        }
    }

    // 分类或标签的输入、校验和服务错误保持在同一弹窗；请求期间禁止关闭与重复提交。
    taxonomyDialog?.let { draft -> NativeTaxonomyDialog(draft, !busy, taxonomyError, onDismiss = { if (!busy) { taxonomyDialog = null; taxonomyError = null } }, onSave = ::saveTaxonomy) }
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

/** 为 label 选择 value；options 限定服务端值，optionLabel 提供显示名，onChange 仅更新父级草稿。 */
@Composable
private fun NativeSettingsMenuField(label: String, value: String, options: List<String>, enabled: Boolean, modifier: Modifier = Modifier, optionLabel: (String) -> String = { it }, onChange: (String) -> Unit) {
    // 使用有界、统一背景的菜单及固定图标，不以手填代码替代候选项。
    var expanded by remember(value) { mutableStateOf(false) }
    Column {
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = modifier.fillMaxWidth()) {
                Text(optionLabel(value), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp), shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { expanded = false; onChange(option) }) }
            }
        }
    }
}

@Composable
private fun NativeSettingsToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f), color = if (enabled) HlovetUi.foreground else HlovetUi.muted); HlovetSwitch(checked, onChange, enabled = enabled) }
}

/** 为 label/kind 选择内置或上传资源；currentPath 为草稿值，上传与删除由父级串行执行。 */
@Composable
private fun NativeSettingsAssetPicker(label: String, kind: String, currentPath: String, assets: List<NativeAdminSiteAsset>, enabled: Boolean, onSelect: (String) -> Unit, onUpload: () -> Unit, onDelete: (NativeAdminSiteAsset) -> Unit) {
    // 候选与 PC 内置资源一致，选择区不再随上传资源数量横向增长。
    val builtins = if (kind == "logo") listOf(
        "/favicon.svg" to nativePhrase("默认 SVG", "Default SVG"), "/logo.svg" to "HLOVET Logo",
        "/tab-icon.svg" to nativePhrase("页签图标", "Tab icon"), "/logo.png" to "PNG Logo",
    ) else listOf(
        "/pwa-logo.png" to nativePhrase("Logo 图标", "Logo icon"), "/icon-192.png" to nativePhrase("192 图标", "192 icon"),
        "/icon-512.png" to nativePhrase("512 图标", "512 icon"), "/apple-touch-icon.png" to nativePhrase("Apple 图标", "Apple icon"),
    )
    val choices = builtins + assets.map { nativeSettingsAssetPath(it.url) to it.originalName }
    var expanded by remember(currentPath) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("site-asset-$kind"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onUpload, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Upload, contentDescription = nativePhrase("上传 $label", "Upload $label"), modifier = Modifier.size(17.dp))
            }
        }
        // 当前值保留为草稿，只有保存全页后才应用到站点；不擅自回退未知的已配置路径。
        Box(Modifier.fillMaxWidth()) {
            TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("site-asset-select-$kind")) {
                Text(choices.firstOrNull { it.first == currentPath }?.second ?: currentPath.ifBlank { nativePhrase("内置默认资源", "Built-in default asset") }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp), shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                choices.forEach { (path, name) ->
                    DropdownMenuItem(text = { Text(name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { expanded = false; onSelect(path) }, trailingIcon = { if (path == currentPath) Icon(Icons.Filled.Check, contentDescription = nativePhrase("当前选择", "Selected"), modifier = Modifier.size(16.dp)) })
                }
            }
        }
        // 已上传资源各占一行，文件名使用剩余宽度，删除按钮保持固定触摸区域。
        assets.forEach { asset ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(asset.originalName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { onDelete(asset) }, enabled = enabled, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除 ${asset.originalName}", "Delete ${asset.originalName}"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 将服务端相对资源 url 转为配置路径；已带 /api 前缀时不重复添加。 */
internal fun nativeSettingsAssetPath(url: String): String {
    // 仅转换站点资源的相对路径，不把文件名或显示名称作为资源标识。
    return if (url.startsWith("/api/")) url else "/api/${url.trimStart('/')}"
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

/** 编辑 item 的独立草稿；error 显示当前校验或请求失败，enabled=false 时不能修改、关闭或重复保存。 */
@Composable
private fun NativeTaxonomyDialog(item: NativeAdminTaxonomy, enabled: Boolean, error: String?, onDismiss: () -> Unit, onSave: (NativeAdminTaxonomy) -> Unit) {
    // 草稿在失败重组后继续保留；切换身份由外层 key 销毁，不跨管理员恢复输入。
    var draft by remember(item.id, item.kind) { mutableStateOf(item) }
    HlovetAlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(if (item.id == 0) nativePhrase("新增${if (item.kind == "tag") "标签" else "分类"}", "Add ${if (item.kind == "tag") "tag" else "category"}") else nativePhrase("编辑${if (item.kind == "tag") "标签" else "分类"}", "Edit ${if (item.kind == "tag") "tag" else "category"}")) },
        text = {
            // 表单独立限高并允许滚动，键盘或小屏压缩空间后仍能查看字段及失败反馈。
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeSettingsField(nativePhrase("名称", "Name"), draft.name, enabled) { draft = draft.copy(name = it) }
                NativeSettingsField(nativePhrase("颜色（#RRGGBB）", "Color (#RRGGBB)"), draft.color, enabled) { draft = draft.copy(color = normalizeHex(it)) }
                NativeSettingsNumberField(nativePhrase("排序", "Sort order"), draft.sortOrder.toString(), enabled) { draft = draft.copy(sortOrder = it.toIntOrNull() ?: draft.sortOrder) }
                NativeSettingsToggle(nativePhrase("启用", "Enabled"), draft.enabled, enabled) { draft = draft.copy(enabled = it) }
                error?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = enabled) { Text(if (enabled) nativePhrase("保存", "Save") else nativePhrase("保存中…", "Saving…")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text(nativePhrase("取消", "Cancel")) } },
    )
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
