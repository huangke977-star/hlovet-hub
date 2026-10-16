@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NativePortalCategoryDraft(
    val kind: String = "navigation",
    val name: String = "",
    val description: String = "",
    val sortOrder: String = "0",
    val status: String = "active",
)

private data class NativePortalEntryDraft(
    val categoryId: Int,
    val title: String = "",
    val description: String = "",
    val url: String = "",
    val iconPath: String = "",
    val openInNewTab: Boolean = true,
    val visibility: String = "public",
    val sortOrder: String = "0",
    val status: String = "active",
    val isFeatured: Boolean = false,
    val featuredSortOrder: String = "0",
    val roleCodes: List<String> = emptyList(),
)

private data class NativePortalCategoryEditor(
    val id: Int?,
    val draft: NativePortalCategoryDraft,
)

private data class NativePortalEntryEditor(
    val id: Int?,
    val draft: NativePortalEntryDraft,
)

private data class NativePortalDeleteAction(
    val kind: String,
    val id: Int,
    val title: String,
)

@Composable
internal fun NativeAdminPortalContentScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canManage = !token.isNullOrBlank() && (session?.isAdministrator == true || session?.isSuperAdmin == true)
    val scope = rememberCoroutineScope()
    var content by remember(token) { mutableStateOf<NativePortalContent?>(null) }
    var roles by remember(token) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var selectedCategoryId by remember(token) { mutableIntStateOf(0) }
    var kindFilter by remember(token) { mutableStateOf("all") }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var saving by remember(token) { mutableStateOf(false) }
    var busyKey by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var formError by remember(token) { mutableStateOf<String?>(null) }
    var categoryEditor by remember(token) { mutableStateOf<NativePortalCategoryEditor?>(null) }
    var entryEditor by remember(token) { mutableStateOf<NativePortalEntryEditor?>(null) }
    var deleteAction by remember(token) { mutableStateOf<NativePortalDeleteAction?>(null) }

    LaunchedEffect(token, canManage, reloadKey) {
        if (!canManage || token.isNullOrBlank()) {
            content = null
            roles = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val loaded = NativePortalApi.listAdminContent(token)
                val loadedRoles = NativeRoleApi.listRoles()
                loaded to loadedRoles
            }
        }.onSuccess { (loaded, loadedRoles) ->
            content = loaded
            roles = loadedRoles
            val available = loaded.categories.filter { it.kind != "server" || session?.isSuperAdmin == true }
            if (selectedCategoryId == 0 || available.none { it.id == selectedCategoryId }) {
                selectedCategoryId = available.firstOrNull()?.id ?: 0
            }
        }.onFailure { exception ->
            error = exception.message ?: nativePhrase("门户内容加载失败，请稍后重试。", "Could not load portal content. Please try again.")
        }
        loading = false
    }

    val allCategories = content?.categories.orEmpty().filter { it.kind != "server" || session?.isSuperAdmin == true }
    val visibleCategories = if (kindFilter == "all") allCategories else allCategories.filter { it.kind == kindFilter }
    val selectedCategory = visibleCategories.firstOrNull { it.id == selectedCategoryId }
        ?: allCategories.firstOrNull { it.id == selectedCategoryId }
        ?: visibleCategories.firstOrNull()
        ?: allCategories.firstOrNull()
    val availableKinds = buildList {
        add("navigation")
        add("tool")
        add("custom_page")
        if (session?.isSuperAdmin == true) add("server")
    }

    fun openNewEntry(category: NativePortalCategory?) {
        if (category == null) return
        entryEditor = NativePortalEntryEditor(null, NativePortalEntryDraft(categoryId = category.id))
        formError = null
    }

    fun runMutation(action: suspend () -> Unit, success: String) {
        if (!canManage || saving) return
        saving = true
        formError = null
        notice = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess {
                    notice = success
                    reloadKey++
                    categoryEditor = null
                    entryEditor = null
                    deleteAction = null
                }
                .onFailure { exception -> formError = exception.message ?: nativePhrase("保存失败，请稍后重试。", "Save failed. Please try again.") }
            saving = false
            busyKey = null
        }
    }

    if (!canManage || token.isNullOrBlank()) {
        NativeAdminPortalGate(onBack = onBack, signedIn = !token.isNullOrBlank())
        return
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
                        Text(nativePhrase("内容管理", "Portal content"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("管理导航、工具和站内门户内容", "Manage navigation, tools, and portal content"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = !loading && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
                    IconButton(onClick = {
                        categoryEditor = NativePortalCategoryEditor(null, NativePortalCategoryDraft(kind = if (kindFilter == "all") "navigation" else kindFilter))
                        formError = null
                    }, enabled = !saving) { Icon(Icons.Filled.Folder, contentDescription = nativePhrase("新建分类", "New category"), tint = HlovetUi.accent) }
                    IconButton(onClick = {
                        openNewEntry(selectedCategory)
                    }, enabled = !saving && selectedCategory != null) { Icon(Icons.Filled.Add, contentDescription = nativePhrase("新建条目", "New entry"), tint = HlovetUi.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (error.isNullOrBlank()) Icons.Filled.Check else Icons.Filled.Info, contentDescription = null, tint = if (error.isNullOrBlank()) HlovetUi.accent else HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error ?: notice.orEmpty(), color = if (error.isNullOrBlank()) HlovetUi.foreground else HlovetUi.secondaryAccent, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { error = null; notice = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(15.dp)) }
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                (listOf("all") + availableKinds).forEach { kind ->
                    NativePortalFilterChip(label = portalKindLabel(kind), selected = kindFilter == kind, onClick = { kindFilter = kind })
                }
            }
        }
        when {
            loading -> item { NativePortalMessage(nativePhrase("正在读取门户内容…", "Loading portal content..."), Icons.Filled.Refresh) }
            allCategories.isEmpty() -> item { NativePortalMessage(nativePhrase("当前还没有门户分类。点击右上角文件夹图标创建。", "No portal categories yet. Tap the folder icon above to create one."), Icons.Filled.Folder) }
            else -> {
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(nativePhrase("分类", "Categories"), color = HlovetUi.muted, fontSize = 10.sp)
                                    Text(nativePhrase("${visibleCategories.size} 个分类", "${visibleCategories.size} categories"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(nativePhrase("点击分类查看条目", "Select a category to view entries"), color = HlovetUi.muted, fontSize = 10.sp)
                            }
                            if (visibleCategories.isEmpty()) {
                                Text(nativePhrase("当前筛选没有分类。", "No categories match the current filter."), color = HlovetUi.muted, fontSize = 11.sp)
                            } else {
                                visibleCategories.forEach { category ->
                                    NativePortalCategoryRow(
                                        category = category,
                                        selected = category.id == selectedCategory?.id,
                                        busy = busyKey == "category-${category.id}",
                                        onSelect = { selectedCategoryId = category.id },
                                        onEdit = { formError = null; categoryEditor = NativePortalCategoryEditor(category.id, category.toDraft()) },
                                        onDelete = { formError = null; deleteAction = NativePortalDeleteAction("category", category.id, category.name) },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(nativePhrase("条目", "Entries"), color = HlovetUi.muted, fontSize = 10.sp)
                                    Text(selectedCategory?.name ?: nativePhrase("请选择分类", "Select a category"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                if (selectedCategory?.kind == "server") Text(nativePhrase("仅超级管理员", "Super administrators only"), color = HlovetUi.secondaryAccent, fontSize = 10.sp)
                                IconButton(onClick = {
                                    openNewEntry(selectedCategory)
                                }, enabled = !saving && selectedCategory != null, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, contentDescription = nativePhrase("新建条目", "New entry"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
                            }
                            if (selectedCategory?.entries.isNullOrEmpty()) {
                                Text(nativePhrase("当前分类还没有条目。", "This category has no entries yet."), color = HlovetUi.muted, fontSize = 11.sp)
                            } else {
                                selectedCategory?.entries.orEmpty().forEach { entry ->
                                    NativePortalEntryRow(
                                        entry = entry,
                                        busy = busyKey == "entry-${entry.id}",
                                        onEdit = { formError = null; entryEditor = NativePortalEntryEditor(entry.id, entry.toDraft()) },
                                        onDelete = { formError = null; deleteAction = NativePortalDeleteAction("entry", entry.id, entry.title) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    categoryEditor?.let { editor ->
        NativePortalCategoryDialog(
            editor = editor,
            kinds = availableKinds,
            saving = saving,
            error = formError,
            onChange = { categoryEditor = editor.copy(draft = it) },
            onDismiss = { if (!saving) categoryEditor = null },
            onSave = {
                val draft = editor.draft
                val normalizedName = draft.name.trim()
                if (normalizedName.isBlank()) {
                    formError = nativePhrase("分类名称不能为空。", "Category name is required.")
                } else {
                    runMutation({
                        if (editor.id == null) NativePortalApi.createCategory(token, draft.kind, normalizedName, draft.description.trim(), draft.sortOrder.toIntOrNull() ?: 0, draft.status)
                        else NativePortalApi.updateCategory(token, editor.id, draft.kind, normalizedName, draft.description.trim(), draft.sortOrder.toIntOrNull() ?: 0, draft.status)
                    }, if (editor.id == null) nativePhrase("分类已创建。", "Category created.") else nativePhrase("分类已更新。", "Category updated."))
                }
            },
        )
    }

    entryEditor?.let { editor ->
        NativePortalEntryDialog(
            editor = editor,
            categories = allCategories,
            roles = roles,
            saving = saving,
            error = formError,
            onChange = { entryEditor = editor.copy(draft = it) },
            onDismiss = { if (!saving) entryEditor = null },
            onSave = {
                val draft = editor.draft
                val category = allCategories.firstOrNull { it.id == draft.categoryId }
                val title = draft.title.trim()
                if (category == null || title.isBlank()) {
                    formError = if (category == null) nativePhrase("请选择有效分类。", "Select a valid category.") else nativePhrase("条目标题不能为空。", "Entry title is required.")
                } else if (category.kind != "server" && draft.visibility == "role_restricted" && draft.roleCodes.isEmpty()) {
                    formError = nativePhrase("指定角色可见时至少选择一个角色。", "Select at least one role for role-restricted visibility.")
                } else {
                    val isServer = category.kind == "server"
                    runMutation({
                        val common = {
                            NativePortalApi.createEntry(token, draft.categoryId, title, draft.description.trim(), draft.url.trim().ifBlank { null }, draft.iconPath.trim().ifBlank { null }, draft.openInNewTab, if (isServer) "authenticated" else draft.visibility, draft.sortOrder.toIntOrNull() ?: 0, draft.status, if (isServer) false else draft.isFeatured, draft.featuredSortOrder.toIntOrNull() ?: 0, if (isServer) emptyList() else draft.roleCodes)
                        }
                        if (editor.id == null) common() else NativePortalApi.updateEntry(token, editor.id, draft.categoryId, title, draft.description.trim(), draft.url.trim().ifBlank { null }, draft.iconPath.trim().ifBlank { null }, draft.openInNewTab, if (isServer) "authenticated" else draft.visibility, draft.sortOrder.toIntOrNull() ?: 0, draft.status, if (isServer) false else draft.isFeatured, draft.featuredSortOrder.toIntOrNull() ?: 0, if (isServer) emptyList() else draft.roleCodes)
                    }, if (editor.id == null) nativePhrase("条目已创建。", "Entry created.") else nativePhrase("条目已更新。", "Entry updated."))
                }
            },
        )
    }

    deleteAction?.let { action ->
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) deleteAction = null },
            title = { Text(if (action.kind == "category") nativePhrase("删除分类", "Delete category") else nativePhrase("删除条目", "Delete entry")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("确定删除“${action.title}”吗？删除后无法恢复。", "Delete “${action.title}”? This cannot be undone."), fontSize = 13.sp, lineHeight = 19.sp)
                    HlovetFormFeedback(formError)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    busyKey = "${action.kind}-${action.id}"
                    runMutation({
                        if (action.kind == "category") NativePortalApi.deleteCategory(token, action.id) else NativePortalApi.deleteEntry(token, action.id)
                    }, if (action.kind == "category") nativePhrase("分类已删除。", "Category deleted.") else nativePhrase("条目已删除。", "Entry deleted."))
                }, enabled = !saving) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) }
            },
            dismissButton = { TextButton(onClick = { deleteAction = null }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun NativeAdminPortalGate(onBack: () -> Unit, signedIn: Boolean) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(nativePhrase("内容管理", "Portal content"), fontSize = 19.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        NativePortalMessage(if (signedIn) nativePhrase("当前账号没有内容管理权限。", "This account cannot manage portal content.") else nativePhrase("登录后查看内容管理。", "Sign in to view portal content."), Icons.Filled.Security)
    }
}

@Composable
private fun NativePortalFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = HlovetUi.rowShape,
        color = if (selected) HlovetUi.accentSoft else HlovetUi.glassFallback,
    ) { Text(label, color = if (selected) HlovetUi.accent else HlovetUi.foreground, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
}

@Composable
private fun NativePortalCategoryRow(
    category: NativePortalCategory,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = HlovetUi.rowShape, color = if (selected) HlovetUi.accentSoft else HlovetUi.surfaceLow, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(portalKindLabel(category.kind), color = HlovetUi.muted, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(category.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(nativePhrase("${category.entries.size} 项 · ${if (category.status == "active") "启用" else "停用"} · 排序 ${category.sortOrder}", "${category.entries.size} entries · ${if (category.status == "active") "Active" else "Disabled"} · Order ${category.sortOrder}"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑分类", "Edit category"), tint = HlovetUi.accent, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onDelete, enabled = !busy && category.entries.isEmpty(), modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除分类", "Delete category"), tint = if (category.entries.isEmpty()) HlovetUi.secondaryAccent else HlovetUi.muted, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun NativePortalEntryRow(entry: NativePortalEntry, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow, modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = HlovetUi.rowShape, color = HlovetUi.accentSoft, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(entry.title.takeLast(2), color = HlovetUi.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.isFeatured) Icon(Icons.Filled.Star, contentDescription = nativePhrase("首页推荐", "Featured"), tint = HlovetUi.secondaryAccent, modifier = Modifier.padding(start = 4.dp).size(13.dp))
                    }
                    Text(entry.description.ifBlank { entry.url ?: nativePhrase("暂无说明", "No description") }, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Text(nativePhrase("${portalVisibilityLabel(entry.visibility)} · ${if (entry.status == "active") "启用" else "停用"} · 排序 ${entry.sortOrder}${if (entry.isFeatured) " · 推荐 ${entry.featuredSortOrder}" else ""}", "${portalVisibilityLabel(entry.visibility)} · ${if (entry.status == "active") "Active" else "Disabled"} · Order ${entry.sortOrder}${if (entry.isFeatured) " · Featured ${entry.featuredSortOrder}" else ""}"), color = HlovetUi.muted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                }
                if (!entry.url.isNullOrBlank()) Icon(Icons.Filled.Link, contentDescription = nativePhrase("有链接", "Has link"), tint = HlovetUi.muted, modifier = Modifier.size(14.dp))
            }
        }
        IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑条目", "Edit entry"), tint = HlovetUi.accent, modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除条目", "Delete entry"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun NativePortalMessage(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NativePortalCategoryDialog(
    editor: NativePortalCategoryEditor,
    kinds: List<String>,
    saving: Boolean,
    error: String?,
    onChange: (NativePortalCategoryDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.imePadding().fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 18.dp), shape = HlovetUi.cardShape, color = HlovetUi.dialogSurface) {
            Column(Modifier.fillMaxSize().padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NativePortalDialogHeader(if (editor.id == null) nativePhrase("新建分类", "New category") else nativePhrase("编辑分类", "Edit category"), onDismiss, saving)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NativePortalChoiceField(nativePhrase("类型", "Type"), editor.draft.kind, kinds.map { it to portalKindLabel(it) }, saving) { onChange(editor.draft.copy(kind = it)) }
                    NativePortalTextField(nativePhrase("分类名称", "Category name"), editor.draft.name, saving) { onChange(editor.draft.copy(name = it)) }
                    NativePortalTextField(nativePhrase("分类说明", "Category description"), editor.draft.description, saving, singleLine = false, minLines = 3) { onChange(editor.draft.copy(description = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NativePortalTextField(nativePhrase("排序", "Order"), editor.draft.sortOrder, saving, modifier = Modifier.weight(1f), number = true) { onChange(editor.draft.copy(sortOrder = it)) }
                        NativePortalChoiceField(nativePhrase("状态", "Status"), editor.draft.status, listOf("active" to nativePhrase("启用", "Active"), "disabled" to nativePhrase("停用", "Disabled")), saving, modifier = Modifier.weight(1f)) { onChange(editor.draft.copy(status = it)) }
                    }
                }
                HlovetFormFeedback(error)
                NativePortalDialogActions(saving, onSave, onDismiss)
            }
        }
    }
}

@Composable
private fun NativePortalEntryDialog(
    editor: NativePortalEntryEditor,
    categories: List<NativePortalCategory>,
    roles: List<NativeRole>,
    saving: Boolean,
    error: String?,
    onChange: (NativePortalEntryDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val category = categories.firstOrNull { it.id == editor.draft.categoryId }
    val isServer = category?.kind == "server"
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.imePadding().fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 14.dp), shape = HlovetUi.cardShape, color = HlovetUi.dialogSurface) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                NativePortalDialogHeader(if (editor.id == null) nativePhrase("新建条目", "New entry") else nativePhrase("编辑条目", "Edit entry"), onDismiss, saving)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    NativePortalChoiceField(nativePhrase("所属分类", "Category"), editor.draft.categoryId.toString(), categories.map { it.id.toString() to "${portalKindLabel(it.kind)} · ${it.name}" }, saving) { value ->
                        val next = categories.firstOrNull { it.id.toString() == value }
                        onChange(editor.draft.copy(categoryId = value.toIntOrNull() ?: editor.draft.categoryId, visibility = if (next?.kind == "server") "authenticated" else editor.draft.visibility, roleCodes = if (next?.kind == "server") emptyList() else editor.draft.roleCodes))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NativePortalTextField(nativePhrase("标题", "Title"), editor.draft.title, saving, modifier = Modifier.weight(1f)) { onChange(editor.draft.copy(title = it)) }
                        NativePortalTextField(nativePhrase("排序", "Order"), editor.draft.sortOrder, saving, modifier = Modifier.weight(1f), number = true) { onChange(editor.draft.copy(sortOrder = it)) }
                    }
                    NativePortalTextField(nativePhrase("说明", "Description"), editor.draft.description, saving, singleLine = false, minLines = 3) { onChange(editor.draft.copy(description = it)) }
                    NativePortalTextField(nativePhrase("链接地址", "Link URL"), editor.draft.url, saving, placeholder = "https://example.com 或 /站内路径") { onChange(editor.draft.copy(url = it)) }
                    NativePortalTextField(nativePhrase("图标地址", "Icon URL"), editor.draft.iconPath, saving, placeholder = "/logo.png 或 https://example.com/icon.png") { onChange(editor.draft.copy(iconPath = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isServer) {
                            NativePortalTextField(nativePhrase("可见范围", "Visibility"), nativePhrase("仅超级管理员", "Super administrators only"), true, modifier = Modifier.weight(1f)) {}
                        } else {
                            NativePortalChoiceField(nativePhrase("可见范围", "Visibility"), editor.draft.visibility, listOf("public" to nativePhrase("公开", "Public"), "authenticated" to nativePhrase("登录可见", "Signed-in users"), "role_restricted" to nativePhrase("指定角色", "Selected roles")), saving, modifier = Modifier.weight(1f)) { onChange(editor.draft.copy(visibility = it, roleCodes = if (it == "role_restricted") editor.draft.roleCodes else emptyList())) }
                        }
                        NativePortalChoiceField(nativePhrase("状态", "Status"), editor.draft.status, listOf("active" to nativePhrase("启用", "Active"), "disabled" to nativePhrase("停用", "Disabled")), saving, modifier = Modifier.weight(1f)) { onChange(editor.draft.copy(status = it)) }
                    }
                    if (!isServer && editor.draft.visibility == "role_restricted") {
                        Text(nativePhrase("允许查看的角色", "Allowed roles"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (roles.isEmpty()) Text(nativePhrase("暂无可用角色。", "No roles available."), color = HlovetUi.muted, fontSize = 10.sp)
                        roles.forEach { role ->
                            Row(Modifier.fillMaxWidth().clickable { if (!saving) onChange(editor.draft.copy(roleCodes = if (editor.draft.roleCodes.contains(role.code)) editor.draft.roleCodes - role.code else editor.draft.roleCodes + role.code)) }, verticalAlignment = Alignment.CenterVertically) {
                                HlovetCheckbox(checked = editor.draft.roleCodes.contains(role.code), onCheckedChange = null, enabled = !saving, modifier = Modifier.size(34.dp))
                                Text("${role.name} (${role.code})", fontSize = 11.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HlovetCheckbox(checked = editor.draft.openInNewTab, onCheckedChange = { onChange(editor.draft.copy(openInNewTab = it)) }, enabled = !saving && editor.draft.url.isNotBlank(), modifier = Modifier.size(34.dp))
                        Text(nativePhrase("在新标签页打开", "Open in a new tab"), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(16.dp))
                    }
                    if (!isServer) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            HlovetSwitch(checked = editor.draft.isFeatured, onCheckedChange = { onChange(editor.draft.copy(isFeatured = it)) }, enabled = !saving)
                            Text(nativePhrase("首页推荐", "Featured on home"), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.Star, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp))
                        }
                        NativePortalTextField(nativePhrase("推荐排序", "Featured order"), editor.draft.featuredSortOrder, saving || !editor.draft.isFeatured, number = true) { onChange(editor.draft.copy(featuredSortOrder = it)) }
                    }
                }
                HlovetFormFeedback(error)
                NativePortalDialogActions(saving, onSave, onDismiss)
            }
        }
    }
}

@Composable
private fun NativePortalDialogHeader(title: String, onDismiss: () -> Unit, saving: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(nativePhrase("保存后立即同步到站内门户", "Changes sync to the portal after saving"), color = HlovetUi.muted, fontSize = 10.sp)
        }
        IconButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun NativePortalDialogActions(saving: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onSave, enabled = !saving) { Text(if (saving) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save"), color = HlovetUi.accent) }
        TextButton(onClick = onDismiss, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) }
    }
}

@Composable
private fun NativePortalTextField(
    label: String,
    value: String,
    disabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (number) it.filter { char -> char == '-' || char.isDigit() }.take(8) else it) },
        modifier = modifier,
        enabled = !disabled,
        singleLine = singleLine,
        minLines = minLines,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = placeholder?.let { { Text(it, fontSize = 10.sp) } },
        colors = nativePortalFieldColors(),
    )
}

/** 展示 label 对应的只读选择框；value/options 使用代码文案对，disabled 禁用交互，onChange 回传选项，modifier 控制布局。 */
@Composable
private fun NativePortalChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    disabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    // 只读输入框负责回显，覆盖点击区打开同一锚点弹层，不允许手填无效代码。
    var expanded by remember(value, options) { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value = options.firstOrNull { it.first == value }?.second ?: value, onValueChange = {}, modifier = Modifier.fillMaxWidth(), readOnly = true, enabled = !disabled, singleLine = true, label = { Text(label, fontSize = 11.sp) }, colors = nativePortalFieldColors())
        Box(Modifier.matchParentSize().clickable(enabled = !disabled) { expanded = true })
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
            options.forEach { (option, text) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text, fontSize = 12.sp) }, onClick = { expanded = false; onChange(option) }, leadingIcon = if (option == value) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null)
            }
        }
    }
}

@Composable
private fun nativePortalFieldColors() = HlovetUi.fieldColors()

private fun NativePortalCategory.toDraft() = NativePortalCategoryDraft(kind, name, description, sortOrder.toString(), status)

private fun NativePortalEntry.toDraft() = NativePortalEntryDraft(categoryId = categoryId, title = title, description = description, url = url.orEmpty(), iconPath = iconPath.orEmpty(), openInNewTab = openInNewTab, visibility = visibility, sortOrder = sortOrder.toString(), status = status, isFeatured = isFeatured, featuredSortOrder = featuredSortOrder.toString(), roleCodes = allowedRoleCodes)

private fun portalKindLabel(kind: String): String = when (kind) {
    "navigation" -> nativePhrase("导航", "Navigation")
    "tool" -> nativePhrase("工具", "Tools")
    "server" -> nativePhrase("服务器入口", "Server entry")
    "custom_page" -> nativePhrase("自定义页面", "Custom page")
    "all" -> nativePhrase("全部", "All")
    else -> kind
}

private fun portalVisibilityLabel(visibility: String): String = when (visibility) {
    "authenticated" -> nativePhrase("登录可见", "Signed-in users")
    "role_restricted" -> nativePhrase("指定角色", "Selected roles")
    else -> nativePhrase("公开", "Public")
}
