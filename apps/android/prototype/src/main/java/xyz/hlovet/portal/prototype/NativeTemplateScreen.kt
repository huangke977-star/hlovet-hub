@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeTemplateManagementScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onUseTemplate: (NativeArticleTemplate) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var templates by remember(token) { mutableStateOf<List<NativeArticleTemplate>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<NativeArticleTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<NativeArticleTemplate?>(null) }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val editorSnapshot = remember { NativeEditorSnapshot() }
    var contentFormat by remember { mutableStateOf(NATIVE_DEFAULT_ARTICLE_CONTENT_FORMAT) }
    var category by remember { mutableStateOf(nativePhrase("随笔", "Notes")) }
    var tags by remember { mutableStateOf("") }
    var titleColor by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }
    var roleCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var roles by remember(token) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var writerSettings by remember(token) { mutableStateOf<NativeWriterSettings?>(null) }

    val configuredCategories = writerSettings?.categories?.map { it.name }.orEmpty()
    val configuredTags = writerSettings?.tags?.map { it.name }.orEmpty()
    val categoryOptions = (listOf(category) + configuredCategories).filter(String::isNotBlank).distinct()
    val selectedTags = tags.split(',', '，', '、').map(String::trim).filter(String::isNotBlank).distinct()
    val tagOptions = (configuredTags + selectedTags).filter(String::isNotBlank).distinct()

    fun load() {
        val currentToken = token
        if (currentToken.isNullOrBlank()) { loading = false; error = nativePhrase("请先登录后管理文章模板。", "Sign in to manage article templates."); return }
        scope.launch {
            loading = true; error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val loadedTemplates = NativeTemplateApi.list(currentToken)
                    val loadedRoles = runCatching { NativeRoleApi.listRoles() }.getOrDefault(emptyList())
                    val loadedSettings = runCatching { NativeSiteSettingsApi.loadWriterSettings() }.getOrNull()
                    Triple(loadedTemplates, loadedRoles, loadedSettings)
                }
            }
                .onSuccess { (loadedTemplates, loadedRoles, loadedSettings) ->
                    templates = loadedTemplates
                    roles = loadedRoles
                    writerSettings = loadedSettings
                }
                .onFailure { error = it.message ?: nativePhrase("文章模板读取失败，请稍后重试。", "Could not load article templates. Please try again.") }
            loading = false
        }
    }

    LaunchedEffect(token) { load() }

    fun openCreate() {
        editingId = null; name = ""; title = ""; summary = ""; content = ""; contentFormat = NATIVE_DEFAULT_ARTICLE_CONTENT_FORMAT; category = configuredCategories.firstOrNull() ?: nativePhrase("随笔", "Notes"); tags = ""; titleColor = ""; visibility = "public"; roleCodes = emptyList(); error = null; editorOpen = true
    }

    fun openEdit(template: NativeArticleTemplate) {
        editingId = template.id; name = template.name; title = template.title; summary = template.summary; content = template.content; contentFormat = template.contentFormat; category = template.category; tags = template.tags.joinToString(", "); titleColor = template.titleColor; visibility = template.visibility; roleCodes = template.roleCodes; error = null; editorOpen = true
    }

    fun save() {
        val currentToken = token ?: return
        if (name.isBlank() || saving) return
        if (visibility == "role_restricted" && roleCodes.isEmpty()) {
            error = nativePhrase("按角色可见时至少选择一个角色。", "Select at least one role for role-restricted visibility.")
            return
        }
        val normalizedTitleColor = normalizeNativeTitleColor(titleColor)
        if (normalizedTitleColor == null) {
            error = nativePhrase("标题颜色需要填写六位十六进制颜色，例如 #2B2530。", "Title color must be a six-digit hexadecimal color, such as #2B2530.")
            return
        }
        saving = true; error = null
        scope.launch {
            val liveContent = try {
                if (editorSnapshot.isAttached) editorSnapshot.capture() else content
            } catch (exception: CancellationException) {
                saving = false
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase(
                    "编辑器尚未准备好，请稍后重试。",
                    "The editor is not ready. Please try again.",
                )
                saving = false
                return@launch
            }
            if (liveContent.isBlank()) {
                error = nativePhrase("模板正文不能为空。", "Template body is required.")
                saving = false
                return@launch
            }
            content = liveContent
            val input = NativeArticleTemplateInput(name, title, summary, liveContent, contentFormat, category, tags, normalizedTitleColor, visibility, roleCodes)
            runCatching {
                withContext(Dispatchers.IO) {
                    editingId?.let { NativeTemplateApi.update(currentToken, it, input) } ?: NativeTemplateApi.create(currentToken, input)
                }
            }.onSuccess { updated ->
                templates = if (editingId == null) listOf(updated) + templates else templates.map { if (it.id == updated.id) updated else it }
                notice = if (editingId == null) nativePhrase("文章模板已创建。", "Article template created.") else nativePhrase("文章模板已更新。", "Article template updated.")
                editorOpen = false
            }.onFailure { error = it.message ?: nativePhrase("模板保存失败，请稍后重试。", "Could not save the template. Please try again.") }
            saving = false
        }
    }

    fun remove(template: NativeArticleTemplate) {
        val currentToken = token ?: return
        if (saving) return
        saving = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeTemplateApi.delete(currentToken, template.id) } }
                .onSuccess { templates = templates.filterNot { it.id == template.id }; deleteTarget = null; notice = nativePhrase("文章模板已删除。", "Article template deleted.") }
                .onFailure { error = it.message ?: nativePhrase("模板删除失败，请稍后重试。", "Could not delete the template. Please try again.") }
            saving = false
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(
                title = { Column { Text(nativePhrase("文章模板", "Article templates"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("保存常用的写作结构和内容格式", "Save reusable writing structures and formats"), color = HlovetUi.muted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }; IconButton(onClick = ::openCreate, enabled = !saving) { Icon(Icons.Filled.AddCircleOutline, nativePhrase("新建模板", "New template"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading) item { templateEmpty(nativePhrase("正在读取文章模板…", "Loading article templates...")) }
        else if (templates.isEmpty()) item { templateEmpty(nativePhrase("还没有文章模板。点击右上角加号创建第一个模板。", "No article templates yet. Tap the plus icon to create one.")) }
        else {
            item { Text(nativePhrase("共 ${templates.size} 个模板", "${templates.size} templates"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(templates, key = { it.id }) { template ->
                GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Article, null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { detail = template }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(template.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(template.title.ifBlank { nativePhrase("未设置文章标题", "No article title") }, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${template.category.ifBlank { nativePhrase("未分类", "Uncategorized") }} · ${template.contentFormat.uppercase()} · ${communityDate(template.updatedAt)}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { openEdit(template) }, enabled = !saving, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, nativePhrase("编辑模板", "Edit template"), tint = HlovetUi.accent, modifier = Modifier.size(17.dp)) }
                        IconButton(onClick = { deleteTarget = template }, enabled = !saving, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, nativePhrase("删除模板", "Delete template"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(17.dp)) }
                    }
                }
            }
        }
        error?.let { item { Text(it, color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
        notice?.let { item { Text(it, color = HlovetUi.accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    if (editorOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) editorOpen = false },
            title = { Text(if (editingId == null) nativePhrase("新建文章模板", "New article template") else nativePhrase("编辑文章模板", "Edit article template")) },
            text = {
                Column(Modifier.heightIn(max = 590.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("模板名称", "Template name")) }, colors = templateFieldColors())
                    OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("文章标题（可选）", "Article title (optional)")) }, colors = templateFieldColors())
                    OutlinedTextField(summary, { summary = it.take(300) }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text(nativePhrase("摘要（可选）", "Summary (optional)")) }, colors = templateFieldColors())
                    NativeTemplateContentEditor(
                        content = content,
                        contentFormat = contentFormat,
                        onContentChange = { content = it },
                        onContentFormatChange = { contentFormat = it },
                        editorSnapshot = editorSnapshot,
                    )
                    OutlinedTextField(category, { category = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("分类", "Category")) }, colors = templateFieldColors())
                    if (categoryOptions.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            categoryOptions.forEach { option ->
                                FilterChip(
                                    selected = category == option,
                                    onClick = { category = option },
                                    label = { Text(option, fontSize = 10.sp) },
                                    border = null,
                                )
                            }
                        }
                    }
                    OutlinedTextField(tags, { tags = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("标签（逗号分隔）", "Tags (comma separated)")) }, colors = templateFieldColors())
                    if (tagOptions.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            tagOptions.forEach { option ->
                                FilterChip(
                                    selected = option in selectedTags,
                                    onClick = {
                                        val next = if (option in selectedTags) selectedTags.filterNot { it == option } else selectedTags + option
                                        tags = next.joinToString(", ")
                                    },
                                    label = { Text("#$option", fontSize = 10.sp) },
                                    border = null,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("public" to nativePhrase("所有人可见", "Everyone"), "authenticated" to nativePhrase("仅登录", "Signed-in users"), "role_restricted" to nativePhrase("指定角色", "Selected roles"), "private" to nativePhrase("仅自己", "Only me")).forEach { (value, label) -> FilterChip(selected = visibility == value, onClick = { visibility = value; if (value != "role_restricted") roleCodes = emptyList() }, label = { Text(label, fontSize = 10.sp) }, border = null) }
                    }
                    OutlinedTextField(
                        titleColor,
                        { titleColor = it.filter { char -> char == '#' || char.isDigit() || char.lowercaseChar() in 'a'..'f' }.take(7) },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(nativePhrase("标题颜色（可选）", "Title color (optional)")) },
                        placeholder = { Text("#2B2530") },
                        colors = templateFieldColors(),
                    )
                    if (visibility == "role_restricted") {
                        Text(nativePhrase("可见角色", "Visible roles"), color = HlovetUi.muted, fontSize = 11.sp)
                        if (roles.isEmpty()) {
                            Text(nativePhrase("暂时没有可用角色，请先在站点设置中配置。", "No roles are available. Configure them in site settings first."), color = HlovetUi.muted, fontSize = 11.sp)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                roles.forEach { role ->
                                    FilterChip(
                                        selected = role.code in roleCodes,
                                        onClick = {
                                            roleCodes = if (role.code in roleCodes) roleCodes.filterNot { it == role.code } else roleCodes + role.code
                                        },
                                        label = { Text(role.name, fontSize = 10.sp) },
                                        border = null,
                                    )
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = ::save, enabled = !saving && name.isNotBlank()) { Text(if (saving) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save"), color = HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { editorOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    detail?.let { template ->
        HlovetAlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(template.name, fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(template.title.ifBlank { nativePhrase("未设置文章标题", "No article title") }, fontWeight = FontWeight.SemiBold)
                Text("${template.category.ifBlank { nativePhrase("未分类", "Uncategorized") }} · ${template.tags.joinToString("、").ifBlank { nativePhrase("无标签", "No tags") }}", color = HlovetUi.muted, fontSize = 11.sp)
                Text(
                    when (template.visibility) {
                        "authenticated" -> nativePhrase("仅登录用户", "Signed-in users")
                        "role_restricted" -> nativePhrase("指定角色${template.roleCodes.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "：$it" }.orEmpty()}", "Selected roles${template.roleCodes.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { ": $it" }.orEmpty()}")
                        "private" -> nativePhrase("仅自己", "Only me")
                        else -> nativePhrase("所有人可见", "Everyone")
                    },
                    color = HlovetUi.muted,
                    fontSize = 11.sp,
                )
                NativePreviewWebView(
                    title = template.title,
                    titleColor = template.titleColor,
                    summary = template.summary,
                    content = template.content.ifBlank { nativePhrase("模板没有正文内容。", "This template has no body content.") },
                    contentFormat = template.contentFormat,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 480.dp),
                )
            } },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { detail = null; onUseTemplate(template) }) { Text(nativePhrase("使用模板", "Use template"), color = HlovetUi.accent) }
                    TextButton(onClick = { detail = null; openEdit(template) }) { Text(nativePhrase("编辑", "Edit"), color = HlovetUi.accent) }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text(nativePhrase("关闭", "Close")) } },
        )
    }

    deleteTarget?.let { template ->
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) deleteTarget = null },
            title = { Text(nativePhrase("删除文章模板", "Delete article template")) },
            text = { Text(nativePhrase("确定删除“${template.name}”吗？删除后无法恢复。", "Delete “${template.name}”? This cannot be undone.")) },
            confirmButton = { TextButton(onClick = { remove(template) }, enabled = !saving) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun NativeTemplateContentEditor(
    content: String,
    contentFormat: String,
    onContentChange: (String) -> Unit,
    onContentFormatChange: (String) -> Unit,
    editorSnapshot: NativeEditorSnapshot,
) {
    val scope = rememberCoroutineScope()
    var visualEditor by remember { mutableStateOf(nativeStartsInVisualEditor(contentFormat)) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var resourceDialogVisible by remember { mutableStateOf(false) }
    var resourcePoints by remember { mutableStateOf("10") }
    var selectedHtml by remember { mutableStateOf("") }
    var visualInsertRequest by remember { mutableStateOf<NativeRichEditorInsertRequest?>(null) }
    var visualInsertNonce by remember { mutableStateOf(0) }

    suspend fun contentForAction(): String? {
        if (!visualEditor) return content
        return try {
            editorSnapshot.capture().also { onContentChange(it); editorError = null }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            editorError = exception.message
            null
        }
    }

    Text(nativePhrase("正文模板", "Body template"), color = HlovetUi.muted, fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("markdown" to "Markdown", "html" to "HTML").forEach { (value, label) ->
            FilterChip(
                selected = contentFormat == value,
                onClick = {
                    scope.launch {
                        val liveContent = contentForAction() ?: return@launch
                        if (value != contentFormat) {
                            onContentChange(
                                if (value == "markdown") nativeHtmlToMarkdown(liveContent)
                                else sanitizeNativeArticleHtml(previewMarkdownRenderer.render(previewMarkdownParser.parse(liveContent))),
                            )
                        }
                        onContentFormatChange(value)
                        if (value == "markdown") visualEditor = false
                    }
                },
                label = { Text(label, fontSize = 10.sp) },
                border = null,
            )
        }
        FilterChip(
            selected = visualEditor,
            onClick = {
                if (!visualEditor) {
                    if (contentFormat == "markdown") {
                        onContentChange(
                            sanitizeNativeArticleHtml(
                                previewMarkdownRenderer.render(previewMarkdownParser.parse(content)),
                            ),
                        )
                        onContentFormatChange("html")
                    } else {
                        onContentChange(sanitizeNativeArticleHtml(content))
                    }
                    visualEditor = true
                } else {
                    scope.launch {
                        if (contentForAction() != null) visualEditor = false
                    }
                }
            },
            label = { Text(if (contentFormat == "markdown") nativePhrase("可视编辑并转为 HTML", "Visual editor and convert to HTML") else nativePhrase("可视编辑", "Visual editor"), fontSize = 10.sp) },
            border = null,
        )
    }
    if (visualEditor) {
        NativeRichEditorWebView(
            content = content,
            onContentChange = onContentChange,
            onResourceRequest = {
                selectedHtml = it
                resourcePoints = "10"
                resourceDialogVisible = true
            },
            editorSnapshot = editorSnapshot,
            visualInsertRequest = visualInsertRequest,
            onVisualInsertHandled = { visualInsertRequest = null },
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
    } else {
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            minLines = 9,
            label = { Text(if (contentFormat == "html") nativePhrase("HTML 正文模板", "HTML body template") else nativePhrase("Markdown 正文模板", "Markdown body template")) },
            placeholder = { Text(if (contentFormat == "html") nativePhrase("输入 HTML 内容…", "Enter HTML content...") else nativePhrase("输入 Markdown 内容…", "Enter Markdown content...")) },
            colors = templateFieldColors(),
        )
    }
    editorError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
    if (resourceDialogVisible) {
        HlovetAlertDialog(
            onDismissRequest = { resourceDialogVisible = false },
            title = { Text(nativePhrase("插入积分资源", "Insert point resource")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("选中的内容会变成需要积分兑换的资源区块；没有选中内容时会插入占位内容。", "Selected content becomes a point-gated resource block; without a selection, placeholder content is inserted."), color = HlovetUi.muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = resourcePoints,
                        onValueChange = { resourcePoints = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(nativePhrase("兑换积分", "Points required")) },
                        colors = templateFieldColors(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { resourceDialogVisible = false }) { Text(nativePhrase("取消", "Cancel")) } },
            confirmButton = {
                TextButton(onClick = {
                    val points = resourcePoints.toIntOrNull()
                    if (points == null || points !in 1..10000) return@TextButton
                    val body = selectedHtml.trim().ifBlank { "<p>${nativePhrase("在这里输入资源内容", "Enter resource content here")}</p>" }
                    visualInsertNonce += 1
                    visualInsertRequest = NativeRichEditorInsertRequest(
                        nonce = visualInsertNonce,
                        html = "<resource-block data-points=\"$points\">${sanitizeNativeArticleHtml(body)}</resource-block>",
                    )
                    selectedHtml = ""
                    resourceDialogVisible = false
                }) { Text(nativePhrase("插入", "Insert"), color = HlovetUi.accent) }
            },
        )
    }
}

@Composable
private fun templateEmpty(message: String) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) { Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp)) }
}

@Composable
private fun templateFieldColors() = HlovetUi.fieldColors()
