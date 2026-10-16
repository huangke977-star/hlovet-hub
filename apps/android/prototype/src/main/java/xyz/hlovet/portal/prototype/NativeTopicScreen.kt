package xyz.hlovet.portal.prototype

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOPIC_COVER_LIMIT_BYTES = 10L * 1024L * 1024L

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun NativeTopicManagementScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
) {
    val token = session?.accessToken
    val canManage = !token.isNullOrBlank() && (session?.isAdministrator == true || session?.isSuperAdmin == true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topics by remember(token) { mutableStateOf<List<RemoteTopic>>(emptyList()) }
    var articles by remember(token) { mutableStateOf<List<RemoteArticle>>(emptyList()) }
    var roles by remember(token) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var selectedId by remember(token) { mutableStateOf<Int?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var saving by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var formError by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var editorOpen by remember(token) { mutableStateOf(false) }
    var deleteTarget by remember(token) { mutableStateOf<RemoteTopic?>(null) }

    var editorTopic by remember(token) { mutableStateOf<RemoteTopic?>(null) }
    var editorTitle by remember(token) { mutableStateOf("") }
    var editorSlug by remember(token) { mutableStateOf("") }
    var editorDescription by remember(token) { mutableStateOf("") }
    var editorCoverPath by remember(token) { mutableStateOf("") }
    var editorVisibility by remember(token) { mutableStateOf("public") }
    var editorStatus by remember(token) { mutableStateOf("active") }
    var editorSortOrder by remember(token) { mutableStateOf("0") }
    var editorRoleCodes by remember(token) { mutableStateOf<List<String>>(emptyList()) }
    var editorArticleIds by remember(token) { mutableStateOf<List<Int>>(emptyList()) }
    var coverFile by remember(token) { mutableStateOf<NativeUploadFile?>(null) }
    var coverUri by remember(token) { mutableStateOf<Uri?>(null) }
    var coverLoading by remember(token) { mutableStateOf(false) }

    val selected = topics.firstOrNull { it.id == selectedId }

    fun loadData() {
        val currentToken = token
        if (!canManage || currentToken.isNullOrBlank()) {
            loading = false
            error = if (currentToken.isNullOrBlank()) nativePhrase("请先登录管理员账号。", "Sign in as an administrator first.") else nativePhrase("当前账号没有专题管理权限。", "This account cannot manage topics.")
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val topicResult = NativeTopicApi.list(currentToken)
                    val articleResult = NativeArticleApi.listMine(currentToken, page = 1, pageSize = 100).items
                    val roleResult = NativeRoleApi.listRoles()
                    Triple(topicResult, articleResult, roleResult)
                }
            }.onSuccess { (nextTopics, nextArticles, nextRoles) ->
                topics = nextTopics
                articles = nextArticles
                roles = nextRoles
                if (selectedId == null || nextTopics.none { it.id == selectedId }) selectedId = nextTopics.firstOrNull()?.id
            }.onFailure { error = it.message ?: nativePhrase("专题加载失败，请稍后重试。", "Could not load topics. Please try again.") }
            loading = false
        }
    }

    LaunchedEffect(token, canManage, reloadKey) { loadData() }

    fun openCreate() {
        if (!canManage || saving) return
        editorTopic = null
        editorTitle = ""
        editorSlug = ""
        editorDescription = ""
        editorCoverPath = ""
        editorVisibility = "public"
        editorStatus = "active"
        editorSortOrder = "0"
        editorRoleCodes = emptyList()
        editorArticleIds = emptyList()
        coverFile = null
        coverUri = null
        formError = null
        editorOpen = true
    }

    fun openEdit(topic: RemoteTopic) {
        if (!canManage || saving) return
        editorTopic = topic
        editorTitle = topic.title
        editorSlug = topic.slug
        editorDescription = topic.description
        editorCoverPath = topic.coverPath.orEmpty()
        editorVisibility = topic.visibility
        editorStatus = topic.status
        editorSortOrder = topic.sortOrder.toString()
        editorRoleCodes = topic.roleCodes
        editorArticleIds = topic.articles.map { it.id }
        coverFile = null
        coverUri = null
        formError = null
        editorOpen = true
    }

    fun toggleArticle(id: Int) {
        editorArticleIds = if (id in editorArticleIds) editorArticleIds.filterNot { it == id } else editorArticleIds + id
    }

    fun moveArticle(id: Int, delta: Int) {
        val index = editorArticleIds.indexOf(id)
        val nextIndex = index + delta
        if (index < 0 || nextIndex !in editorArticleIds.indices) return
        editorArticleIds = editorArticleIds.toMutableList().also {
            val value = it[index]
            it[index] = it[nextIndex]
            it[nextIndex] = value
        }
    }

    fun retainSavedTopic(saved: RemoteTopic) {
        // Each completed request is durable; retries must use the latest server state.
        editorTopic = saved
        topics = if (topics.none { it.id == saved.id }) topics + saved else topics.map { if (it.id == saved.id) saved else it }
        selectedId = saved.id
    }

    fun saveTopic() {
        val currentToken = token
        val title = editorTitle.trim()
        if (!canManage || currentToken.isNullOrBlank() || saving || coverLoading) return
        if (title.isBlank()) {
            formError = nativePhrase("专题名称不能为空。", "Topic name cannot be empty.")
            return
        }
        if (editorVisibility == "role_restricted" && editorRoleCodes.isEmpty()) {
            formError = nativePhrase("按角色可见时至少选择一个角色。", "Select at least one role for role-restricted visibility.")
            return
        }
        saving = true
        formError = null
        scope.launch {
            try {
                val existing = editorTopic
                var saved = withContext(Dispatchers.IO) {
                    if (existing == null) {
                        NativeTopicApi.create(
                            currentToken,
                            title,
                            editorSlug,
                            editorDescription,
                            editorCoverPath,
                            editorVisibility,
                            editorStatus,
                            editorSortOrder.toIntOrNull() ?: 0,
                            editorRoleCodes,
                            editorArticleIds,
                        )
                    } else {
                        NativeTopicApi.update(
                            currentToken,
                            existing.id,
                            title,
                            editorSlug,
                            editorDescription,
                            editorCoverPath,
                            editorVisibility,
                            editorStatus,
                            editorSortOrder.toIntOrNull() ?: 0,
                            editorRoleCodes,
                        )
                    }
                }
                retainSavedTopic(saved)
                if (existing != null) {
                    val currentIds = existing.articles.map { it.id }
                    currentIds.filterNot { it in editorArticleIds }.forEach { articleId ->
                        saved = withContext(Dispatchers.IO) { NativeTopicApi.removeArticle(currentToken, saved.id, articleId) }
                        retainSavedTopic(saved)
                    }
                    editorArticleIds.filterNot { it in currentIds }.forEach { articleId ->
                        saved = withContext(Dispatchers.IO) { NativeTopicApi.addArticle(currentToken, saved.id, articleId) }
                        retainSavedTopic(saved)
                    }
                    if (editorArticleIds != currentIds) {
                        saved = withContext(Dispatchers.IO) { NativeTopicApi.reorderArticles(currentToken, saved.id, editorArticleIds) }
                        retainSavedTopic(saved)
                    }
                }
                coverFile?.let { file ->
                    saved = withContext(Dispatchers.IO) { NativeTopicApi.uploadCover(currentToken, saved.id, file) }
                    retainSavedTopic(saved)
                    editorCoverPath = saved.coverPath.orEmpty()
                    coverFile = null
                }
                editorOpen = false
                notice = if (existing == null) nativePhrase("专题已创建。", "Topic created.") else nativePhrase("专题设置已保存。", "Topic settings saved.")
            } catch (exception: Exception) {
                formError = exception.message ?: nativePhrase("专题保存失败，请稍后重试。", "Could not save the topic. Please try again.")
            } finally {
                saving = false
            }
        }
    }

    fun deleteTopic(topic: RemoteTopic) {
        val currentToken = token ?: return
        if (!canManage || saving) return
        saving = true
        formError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeTopicApi.delete(currentToken, topic.id) }
                topics = topics.filterNot { it.id == topic.id }
                selectedId = topics.firstOrNull()?.id
                deleteTarget = null
                notice = nativePhrase("专题已删除，文章内容未受影响。", "Topic deleted. Article content was not affected.")
            } catch (exception: Exception) {
                formError = exception.message ?: nativePhrase("专题删除失败，请稍后重试。", "Could not delete the topic. Please try again.")
            } finally {
                saving = false
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coverUri = uri
        coverLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readUploadFile(context, uri) } }
                .onSuccess { file -> coverFile = file; editorCoverPath = "" }
                .onFailure { formError = it.message ?: nativePhrase("封面读取失败。", "Could not read the cover image.") }
            coverLoading = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(nativePhrase("专题管理", "Topic management"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = canManage && !loading && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh")) }
                    IconButton(onClick = ::openCreate, enabled = canManage && !saving) { Icon(Icons.Filled.Add, contentDescription = nativePhrase("新建专题", "New topic")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                item { TopicEmptyState(nativePhrase("正在读取专题…", "Loading topics...")) }
            } else if (topics.isEmpty() && error.isNullOrBlank()) {
                item { TopicEmptyState(nativePhrase("还没有专题。点击右上角加号创建一个专题。", "No topics yet. Tap the plus icon to create one.")) }
            } else {
                items(topics, key = { it.id }) { topic ->
                    TopicManagerRow(
                        topic = topic,
                        selected = topic.id == selectedId,
                        onClick = { selectedId = topic.id },
                        onEdit = { openEdit(topic) },
                        onDelete = { formError = null; deleteTarget = topic },
                        enabled = !saving,
                    )
                }
                selected?.let { topic ->
                    item {
                        TopicInspector(
                            topic = topic,
                            onEdit = { openEdit(topic) },
                            onOpenArticle = onOpenArticle,
                        )
                    }
                }
            }
            if (!error.isNullOrBlank()) item { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            notice?.let { item { Text(it, color = HlovetUi.accent, fontSize = 12.sp) } }
        }
    }

    if (editorOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) editorOpen = false },
            title = { Text(if (editorTopic == null) nativePhrase("新建专题", "New topic") else nativePhrase("编辑专题", "Edit topic")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    HlovetFormFeedback(formError)
                    Column(
                        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 660.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        OutlinedTextField(editorTitle, { editorTitle = it.take(80) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("专题名称", "Topic name")) }, colors = HlovetUi.fieldColors())
                        OutlinedTextField(editorSlug, { editorSlug = it.take(120) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("访问标识（可选）", "Slug (optional)")) }, colors = HlovetUi.fieldColors())
                        OutlinedTextField(editorDescription, { editorDescription = it.take(500) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text(nativePhrase("专题说明", "Topic description")) }, colors = HlovetUi.fieldColors())
                        OutlinedTextField(editorSortOrder, { editorSortOrder = it.filter(Char::isDigit).take(5) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("排序值", "Sort order")) }, colors = HlovetUi.fieldColors())
                        TopicChoiceRow(nativePhrase("可见范围", "Visibility"), listOf("public" to nativePhrase("公开", "Public"), "authenticated" to nativePhrase("登录可见", "Signed-in users"), "role_restricted" to nativePhrase("按角色", "Selected roles")), editorVisibility) { if (!saving) editorVisibility = it }
                        TopicChoiceRow(nativePhrase("状态", "Status"), listOf("active" to nativePhrase("启用", "Enabled"), "disabled" to nativePhrase("停用", "Disabled")), editorStatus) { if (!saving) editorStatus = it }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(nativePhrase("专题封面", "Topic cover"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/avif")) }, enabled = !saving && !coverLoading) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (coverLoading) nativePhrase("读取中", "Reading...") else nativePhrase("选择图片", "Choose image"), fontSize = 12.sp)
                            }
                        }
                        if (coverFile != null) Text(nativePhrase("已选择：${coverFile!!.name}", "Selected: ${coverFile!!.name}"), color = HlovetUi.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else if (coverUri == null && editorCoverPath.isNotBlank()) Text(nativePhrase("当前封面已保存，可重新选择替换。", "The current cover is saved; choose another image to replace it."), color = HlovetUi.muted, fontSize = 11.sp)
                        if (editorVisibility == "role_restricted") {
                            Text(nativePhrase("允许的角色", "Allowed roles"), color = HlovetUi.muted, fontSize = 12.sp)
                            if (roles.isEmpty()) Text(nativePhrase("暂时没有可用角色。", "No roles are available."), color = HlovetUi.muted, fontSize = 11.sp)
                            else roles.forEach { role ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { editorRoleCodes = toggleValue(editorRoleCodes, role.code) }, verticalAlignment = Alignment.CenterVertically) {
                                    HlovetCheckbox(role.code in editorRoleCodes, { checked -> editorRoleCodes = if (checked) editorRoleCodes + role.code else editorRoleCodes - role.code }, enabled = !saving)
                                    Text(role.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(role.code, color = HlovetUi.muted, fontSize = 10.sp)
                                }
                            }
                        }
                        Text(nativePhrase("已收录文章（${editorArticleIds.size}）", "Included articles (${editorArticleIds.size})"), color = HlovetUi.muted, fontSize = 12.sp)
                        NativeReorderableArticleRows(
                            items = editorArticleIds,
                            enabled = !saving,
                            titleFor = { articleId ->
                                (articles.firstOrNull { it.id == articleId } ?: editorTopic?.articles?.firstOrNull { it.id == articleId })?.title
                                    ?: nativePhrase("文章 #$articleId", "Article #$articleId")
                            },
                            supportingFor = { nativePhrase("已收录", "Included") },
                            onReordered = { editorArticleIds = it },
                            onRemove = ::toggleArticle,
                        )
                        articles.filterNot { it.id in editorArticleIds }.take(100).forEach { article ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { toggleArticle(article.id) }, verticalAlignment = Alignment.CenterVertically) {
                                HlovetCheckbox(false, { toggleArticle(article.id) }, enabled = !saving)
                                Text(article.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::saveTopic, enabled = !saving && !coverLoading && editorTitle.isNotBlank()) { Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (saving) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save")) } },
            dismissButton = { TextButton(onClick = { editorOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    deleteTarget?.let { topic ->
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) deleteTarget = null },
            title = { Text(nativePhrase("删除专题", "Delete topic")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("确定要删除“${topic.title}”吗？收录的文章本身不会被删除。", "Delete “${topic.title}”? Included articles will not be deleted."))
                    HlovetFormFeedback(formError)
                }
            },
            confirmButton = { TextButton(onClick = { deleteTopic(topic) }, enabled = !saving) { Text(nativePhrase("删除", "Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun TopicManagerRow(
    topic: RemoteTopic,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = HlovetUi.rowShape,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeCoverImage(
                path = topic.coverPath,
                modifier = Modifier.size(46.dp),
                fallbackText = topic.title,
                tint = HlovetUi.accent,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(topic.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    nativePhrase("${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅 · ${if (topic.status == "active") "启用" else "停用"}", "${topic.articleCount} articles · ${topic.subscriberCount} subscribers · ${if (topic.status == "active") "Enabled" else "Disabled"}"),
                    color = HlovetUi.muted,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑专题", "Edit topic"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除专题", "Delete topic"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun TopicInspector(topic: RemoteTopic, onEdit: () -> Unit, onOpenArticle: (RemoteArticle) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeCoverImage(
                    path = topic.coverPath,
                    modifier = Modifier.size(56.dp),
                    fallbackText = topic.title,
                    tint = HlovetUi.accent,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase("专题预览", "Topic preview"), color = HlovetUi.muted, fontSize = 11.sp)
                    Text(topic.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑专题", "Edit topic"), tint = HlovetUi.accent) }
            }
            if (topic.description.isNotBlank()) Text(topic.description, color = HlovetUi.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(nativePhrase("访问标识：${topic.slug} · ${topic.visibilityLabel()} · ${if (topic.status == "active") "启用" else "停用"}", "Slug: ${topic.slug} · ${topic.visibilityLabel()} · ${if (topic.status == "active") "Enabled" else "Disabled"}"), color = HlovetUi.muted, fontSize = 11.sp)
            if (topic.articles.isEmpty()) Text(nativePhrase("还没有收录文章。", "No included articles."), color = HlovetUi.muted, fontSize = 12.sp)
            else topic.articles.forEachIndexed { index, article ->
                ListItem(
                    headlineContent = { Text("${index + 1}. ${article.title}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(article.category, color = HlovetUi.muted, fontSize = 10.sp) },
                    leadingContent = { Text("${index + 1}", color = HlovetUi.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    trailingContent = { Icon(Icons.Filled.Visibility, contentDescription = nativePhrase("查看文章", "View article"), tint = HlovetUi.muted, modifier = Modifier.size(17.dp)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenArticle(article) },
                )
            }
        }
    }
}

@Composable
private fun TopicChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(text, fontSize = 11.sp) })
            }
        }
    }
}

@Composable
private fun TopicArticleEditorRow(
    article: RemoteArticle?,
    fallbackId: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(article?.title ?: nativePhrase("文章 #$fallbackId", "Article #$fallbackId"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nativePhrase("已收录", "Included"), color = HlovetUi.accent, fontSize = 10.sp)
        }
        IconButton(onClick = onUp, enabled = canMoveUp, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.ArrowUpward, contentDescription = nativePhrase("上移", "Move up"), modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onDown, enabled = canMoveDown, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.ArrowDownward, contentDescription = nativePhrase("下移", "Move down"), modifier = Modifier.size(16.dp)) }
        TextButton(onClick = onRemove, modifier = Modifier.height(30.dp)) { Text(nativePhrase("移除", "Remove"), fontSize = 11.sp, color = HlovetUi.secondaryAccent) }
    }
}

@Composable
private fun TopicEmptyState(text: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { Text(text, color = HlovetUi.muted, fontSize = 13.sp) }
    }
}

private fun RemoteTopic.visibilityLabel(): String = when (visibility) {
    "authenticated" -> nativePhrase("登录可见", "Signed-in users")
    "role_restricted" -> nativePhrase("按角色可见", "Selected roles")
    else -> nativePhrase("公开", "Public")
}

private fun toggleValue(values: List<String>, value: String): List<String> =
    if (value in values) values - value else values + value

private fun readUploadFile(context: Context, uri: Uri): NativeUploadFile {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { input -> input.readBytes() } ?: throw IllegalStateException(nativePhrase("无法读取所选图片。", "Could not read the selected image."))
    if (bytes.size > TOPIC_COVER_LIMIT_BYTES) throw IllegalStateException(nativePhrase("专题封面不能超过 10 MB。", "Topic cover cannot exceed 10 MB."))
    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "topic-cover"
    return NativeUploadFile(name, resolver.getType(uri).orEmpty(), bytes)
}
