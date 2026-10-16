@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class NativeAnonymousTopicAccess {
    Public,
    Management,
    Denied,
}

internal fun nativeAnonymousTopicAccess(management: Boolean, isAdministrator: Boolean): NativeAnonymousTopicAccess = when {
    !management -> NativeAnonymousTopicAccess.Public
    isAdministrator -> NativeAnonymousTopicAccess.Management
    else -> NativeAnonymousTopicAccess.Denied
}

@Composable
internal fun NativeAnonymousTopicsScreen(
    session: NativeSession?,
    management: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access = nativeAnonymousTopicAccess(
        management = management,
        isAdministrator = session?.isSuperAdmin == true || session?.isAdministrator == true,
    )
    val manager = access == NativeAnonymousTopicAccess.Management
    val managementDenied = access == NativeAnonymousTopicAccess.Denied
    var page by remember { mutableIntStateOf(1) }
    var pageTotal by remember { mutableIntStateOf(1) }
    var items by remember { mutableStateOf<List<NativeAnonymousTopicSummary>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("time") }
    var visibility by remember { mutableStateOf("visible") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf<NativeAnonymousTopicDetail?>(null) }
    var topicLoading by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var identityOpen by remember { mutableStateOf(false) }
    var identityCreate by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var nicknameDraft by remember { mutableStateOf("") }
    var passwordDraft by remember { mutableStateOf("") }
    var identityNickname by remember { mutableStateOf("") }
    var identityPassword by remember { mutableStateOf("") }
    var messageDraft by remember { mutableStateOf("") }
    var pendingFiles by remember { mutableStateOf<List<NativeUploadFile>>(emptyList()) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (nativeAttachmentSelectionExceedsLimit(pendingFiles.size, uris.size)) {
            error = nativePhrase(
                "一条匿名发言最多包含 $NATIVE_CHAT_MAX_ATTACHMENTS 个附件。",
                "An anonymous message can include up to $NATIVE_CHAT_MAX_ATTACHMENTS attachments.",
            )
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.map { uri -> readNativeChatUploadFile(context, uri) }
                }
            }.onSuccess { files ->
                if (nativeAttachmentSelectionExceedsLimit(pendingFiles.size, files.size)) {
                    error = nativePhrase(
                        "一条匿名发言最多包含 $NATIVE_CHAT_MAX_ATTACHMENTS 个附件。",
                        "An anonymous message can include up to $NATIVE_CHAT_MAX_ATTACHMENTS attachments.",
                    )
                } else if ((pendingFiles + files).sumOf { it.bytes.size.toLong() } > NATIVE_CHAT_MAX_BATCH_BYTES) {
                    error = nativePhrase("附件总大小不能超过 50 MB。", "Attachments cannot exceed 50 MB in total.")
                } else {
                    pendingFiles += files
                }
            }.onFailure { error = it.message ?: nativePhrase("附件读取失败。", "Could not read the attachment.") }
        }
    }

    fun loadTopics(reset: Boolean = true) {
        if (loading) return
        if (managementDenied) {
            error = nativePhrase("需要管理员权限才能打开匿名话题管理。", "Administrator access is required to manage anonymous topics.")
            loading = false
            return
        }
        loading = true
        error = null
        val targetPage = if (reset) 1 else page + 1
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (manager && session?.accessToken?.isNotBlank() == true) {
                        NativeAnonymousTopicApi.listAdmin(session.accessToken, targetPage, 12, query, sort, visibility)
                    } else {
                        NativeAnonymousTopicApi.list(context, targetPage, 12, query, sort)
                    }
                }
            }.onSuccess { result ->
                items = if (reset) result.items else (items + result.items).distinctBy { it.id }
                page = result.page
                pageTotal = result.totalPages
            }.onFailure { error = it.message ?: nativePhrase("匿名话题加载失败，请稍后重试。", "Could not load anonymous topics. Please try again later.") }
            loading = false
        }
    }

    fun loadTopic(id: Int, beforeSequence: Int? = null) {
        if (managementDenied) {
            error = nativePhrase("需要管理员权限才能打开匿名话题管理。", "Administrator access is required to manage anonymous topics.")
            return
        }
        topicLoading = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (manager && session?.accessToken?.isNotBlank() == true) {
                        NativeAnonymousTopicApi.getAdmin(session.accessToken, id, beforeSequence)
                    } else {
                        NativeAnonymousTopicApi.get(context, id, beforeSequence)
                    }
                }
            }.onSuccess { result ->
                topic = if (beforeSequence != null && topic?.summary?.id == id) {
                    val known = topic!!.messages.map { it.id }.toSet()
                    result.copy(messages = result.messages.filterNot { it.id in known } + topic!!.messages)
                } else result
            }.onFailure { error = it.message ?: nativePhrase("无法打开匿名话题。", "Could not open the anonymous topic.") }
            topicLoading = false
        }
    }

    LaunchedEffect(query, sort, visibility, manager, management, session?.accessToken) {
        delay(if (query.isBlank()) 0 else 220)
        loadTopics()
    }

    LaunchedEffect(topic?.summary?.id, manager, management, session?.accessToken) {
        val id = topic?.summary?.id ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (!topicLoading) loadTopic(id)
        }
    }

    fun openCreate() {
        titleDraft = ""
        nicknameDraft = ""
        passwordDraft = ""
        createOpen = true
    }

    fun createTopic() {
        if (saving || titleDraft.isBlank() || nicknameDraft.isBlank() || passwordDraft.length < 6) return
        saving = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeAnonymousTopicApi.create(context, titleDraft, nicknameDraft, passwordDraft) }
            }.onSuccess { created ->
                NativeAnonymousTopicApi.saveIdentity(context, created.summary.id, created.identity)
                createOpen = false
                items = emptyList()
                loadTopics()
                loadTopic(created.summary.id)
            }.onFailure { error = it.message ?: nativePhrase("发起匿名话题失败。", "Could not create the anonymous topic.") }
            saving = false
        }
    }

    fun claimIdentity() {
        val current = topic ?: return
        if (saving || identityPassword.length < 6) return
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeAnonymousTopicApi.claimIdentity(
                        context = context,
                        topicId = current.summary.id,
                        password = identityPassword,
                        nickname = identityNickname.takeIf { identityCreate },
                        create = identityCreate,
                    )
                }
            }.onSuccess { identity ->
                NativeAnonymousTopicApi.saveIdentity(context, current.summary.id, identity)
                identityOpen = false
                identityPassword = ""
                identityNickname = ""
            }.onFailure { error = it.message ?: nativePhrase("无法设置或恢复昵称。", "Could not set or restore the nickname.") }
            saving = false
        }
    }

    fun sendMessage() {
        val current = topic ?: return
        if (saving || (messageDraft.isBlank() && pendingFiles.isEmpty())) return
        saving = true
        scope.launch {
            runCatching {
                val identity = NativeAnonymousTopicApi.readIdentity(context, current.summary.id)
                withContext(Dispatchers.IO) {
                    NativeAnonymousTopicApi.sendMessage(context, current.summary.id, messageDraft, identity?.identityToken, pendingFiles)
                }
            }.onSuccess {
                messageDraft = ""
                pendingFiles = emptyList()
                loadTopic(current.summary.id)
                loadTopics()
            }.onFailure { error = it.message ?: nativePhrase("匿名发言失败。", "Could not send the anonymous message.") }
            saving = false
        }
    }

    fun react(message: NativeAnonymousTopicMessage, value: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeAnonymousTopicApi.react(context, message.id, value) }
            }.onSuccess { updated ->
                topic = topic?.copy(messages = topic!!.messages.map { if (it.id == updated.id) updated else it })
                loadTopics()
            }.onFailure { error = it.message ?: nativePhrase("操作失败。", "Action failed.") }
        }
    }

    fun favorite(item: NativeAnonymousTopicSummary) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeAnonymousTopicApi.favorite(context, item.id) }
            }.onSuccess { value ->
                val next = items.map { known ->
                    if (known.id == item.id) known.copy(favorited = value.optBoolean("favorited"), favoriteCount = value.optInt("favoriteCount", known.favoriteCount)) else known
                }
                items = next
                if (topic?.summary?.id == item.id) topic = topic?.copy(summary = topic!!.summary.copy(favorited = value.optBoolean("favorited"), favoriteCount = value.optInt("favoriteCount", topic!!.summary.favoriteCount)))
            }.onFailure { error = it.message ?: nativePhrase("无法更新话题喜欢状态。", "Could not update the topic favorite.") }
        }
    }

    fun changeTopicStatus() {
        val current = topic ?: return
        val nextStatus = if (current.summary.status == "active") "closed" else "active"
        val identity = NativeAnonymousTopicApi.readIdentity(context, current.summary.id)
        if (session?.accessToken.isNullOrBlank() && !identity?.isCreator.orFalse()) {
            error = nativePhrase("只有话题创建者或管理员可以操作话题状态。", "Only the topic creator or an administrator can change its status.")
            return
        }
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (manager && !session?.accessToken.isNullOrBlank()) {
                        NativeAnonymousTopicApi.updateTopic(session!!.accessToken, current.summary.id, status = nextStatus)
                    } else {
                        NativeAnonymousTopicApi.updateTopicAsCreator(context, current.summary.id, nextStatus, identity!!.identityToken)
                    }
                }
            }.onSuccess { updated ->
                topic = topic?.copy(summary = updated)
                loadTopics()
            }.onFailure { error = it.message ?: "话题状态更新失败。" }
            saving = false
        }
    }

    fun toggleTopicHidden() {
        val current = topic ?: return
        val token = session?.accessToken
        if (!manager || token.isNullOrBlank()) return
        saving = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAnonymousTopicApi.updateTopic(token, current.summary.id, isHidden = !current.summary.isHidden) } }
                .onSuccess { updated -> topic = topic?.copy(summary = updated); loadTopics() }
                .onFailure { error = it.message ?: "话题可见性更新失败。" }
            saving = false
        }
    }

    fun toggleMessageHidden(message: NativeAnonymousTopicMessage) {
        val token = session?.accessToken
        if (!manager || token.isNullOrBlank()) return
        saving = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAnonymousTopicApi.updateMessage(token, message.id, !message.isHidden) } }
                .onSuccess { updated -> topic = topic?.copy(messages = topic!!.messages.map { if (it.id == updated.id) updated else it }); loadTopics() }
                .onFailure { error = it.message ?: "消息可见性更新失败。" }
            saving = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (management) nativePhrase("匿名话题管理", "Anonymous topic management") else nativePhrase("匿名话题", "Anonymous topics"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    if (!managementDenied) {
                        IconButton(onClick = { loadTopics() }, enabled = !loading && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh")) }
                        IconButton(onClick = ::openCreate, enabled = !saving) { Icon(Icons.Filled.Add, contentDescription = nativePhrase("发起匿名话题", "Create anonymous topic")) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (managementDenied) {
                item {
                    Text(nativePhrase("当前账号没有匿名话题管理权限。", "This account cannot manage anonymous topics."), color = HlovetUi.secondaryAccent, fontSize = 13.sp)
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(nativePhrase("搜索匿名话题", "Search anonymous topics")) },
                        leadingIcon = { Icon(Icons.Filled.Message, contentDescription = null) },
                        colors = loginFieldColors(),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("time" to nativePhrase("最近活跃", "Recently active"), "participation" to nativePhrase("参与最多", "Most participation"), "likes" to nativePhrase("点赞最多", "Most liked"), "favorites" to nativePhrase("喜欢最多", "Most favorited")).forEach { (value, label) ->
                        FilterChip(
                            selected = sort == value,
                            onClick = { sort = value },
                            label = { Text(label, fontSize = 10.sp) },
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HlovetUi.accent, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            if (manager) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("visible" to nativePhrase("正常", "Visible"), "hidden" to nativePhrase("已隐藏", "Hidden"), "all" to nativePhrase("全部", "All")).forEach { (value, label) ->
                            FilterChip(selected = visibility == value, onClick = { visibility = value }, label = { Text(label, fontSize = 10.sp) }, border = null)
                        }
                    }
                }
            }
            if (!error.isNullOrBlank()) item { Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp) }
            if (loading && items.isEmpty()) item { Text(nativePhrase("正在读取匿名话题…", "Loading anonymous topics..."), color = HlovetUi.muted, fontSize = 12.sp) }
            if (!managementDenied && !loading && items.isEmpty()) item { Text(nativePhrase("暂时没有匿名话题。发起一个让大家聊聊。", "No anonymous topics yet. Start one for the community."), color = HlovetUi.muted, fontSize = 12.sp) }
            items(items, key = { it.id }) { item ->
                AnonymousTopicListRow(item = item, onOpen = { loadTopic(item.id) }, onFavorite = { favorite(item) })
            }
            if (page < pageTotal) item {
                TextButton(onClick = { loadTopics(reset = false) }, enabled = !loading) {
                    Text(if (loading) nativePhrase("读取中…", "Loading...") else nativePhrase("加载更多话题", "Load more topics"))
                }
            }
        }
    }

    if (createOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) createOpen = false },
            title = { Text(nativePhrase("发起匿名话题", "Create anonymous topic")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(titleDraft, { titleDraft = it.take(120) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("话题", "Topic")) }, colors = loginFieldColors())
                    OutlinedTextField(nicknameDraft, { nicknameDraft = it.take(32) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("昵称", "Nickname")) }, colors = loginFieldColors())
                    OutlinedTextField(passwordDraft, { passwordDraft = it.take(128) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("密码（至少 6 位，用于恢复昵称）", "Password (at least 6 characters, used to restore the nickname)")) }, colors = loginFieldColors())
                }
            },
            confirmButton = { TextButton(onClick = ::createTopic, enabled = !saving && titleDraft.isNotBlank() && nicknameDraft.isNotBlank() && passwordDraft.length >= 6) { Text(if (saving) nativePhrase("创建中…", "Creating...") else nativePhrase("创建并进入", "Create and open")) } },
            dismissButton = { TextButton(onClick = { createOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (identityOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) identityOpen = false },
            title = { Text(if (identityCreate) nativePhrase("创建昵称", "Create nickname") else nativePhrase("获取昵称", "Restore nickname")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (identityCreate) nativePhrase("昵称和密码只在当前话题内绑定，创建后不能修改。", "The nickname and password only apply to this topic and cannot be changed later.") else nativePhrase("输入已绑定的密码，即可恢复这个话题中的昵称。", "Enter the linked password to restore this topic nickname."), color = HlovetUi.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HlovetCheckbox(identityCreate, { identityCreate = it }, enabled = !saving)
                        Text(nativePhrase("创建新昵称", "Create a new nickname"), fontSize = 12.sp)
                    }
                    if (identityCreate) OutlinedTextField(identityNickname, { identityNickname = it.take(32) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("昵称", "Nickname")) }, colors = loginFieldColors())
                    OutlinedTextField(identityPassword, { identityPassword = it.take(128) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("密码", "Password")) }, colors = loginFieldColors())
                }
            },
            confirmButton = { TextButton(onClick = ::claimIdentity, enabled = !saving && identityPassword.length >= 6 && (!identityCreate || identityNickname.isNotBlank())) { Text(if (saving) nativePhrase("处理中…", "Working...") else nativePhrase("确认", "Confirm")) } },
            dismissButton = { TextButton(onClick = { identityOpen = false }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    topic?.let { selected ->
        AnonymousTopicDialog(
            context = context,
            topic = selected,
            manager = manager,
            loading = topicLoading,
            saving = saving,
            message = messageDraft,
            pendingFiles = pendingFiles,
            onMessageChange = { messageDraft = it },
            onClose = { topic = null; identityOpen = false; pendingFiles = emptyList() },
            onLoadOlder = { selected.messages.firstOrNull()?.sequence?.let { loadTopic(selected.summary.id, it) } },
            onOpenIdentity = {
                val identity = NativeAnonymousTopicApi.readIdentity(context, selected.summary.id)
                identityCreate = false
                identityNickname = identity?.nickname.orEmpty()
                identityPassword = ""
                identityOpen = true
            },
            onPickFiles = { filePicker.launch(arrayOf("image/*", "video/*", "audio/*", "text/*", "application/*")) },
            onRemoveFile = { file -> pendingFiles = pendingFiles - file },
            onSend = ::sendMessage,
            onReact = { message, value -> react(message, value) },
            onToggleMessageHidden = ::toggleMessageHidden,
            onToggleTopicHidden = ::toggleTopicHidden,
            onChangeTopicStatus = ::changeTopicStatus,
        )
    }
}

@Composable
private fun AnonymousTopicListRow(
    item: NativeAnonymousTopicSummary,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(formatAnonymousTime(item.updatedAt))
                        append(" · ")
                        append(if (item.status == "closed") nativePhrase("已关闭", "Closed") else nativePhrase("${item.messageCount} 条发言", "${item.messageCount} messages"))
                        if (item.isHidden) append(" · ${nativePhrase("已隐藏", "Hidden")}")
                    },
                    color = HlovetUi.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.topLikedMessage?.let { Text("👍 #${it.sequence}: ${it.body}", color = HlovetUi.accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                Icon(if (item.favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (item.favorited) nativePhrase("取消喜欢", "Unfavorite") else nativePhrase("喜欢话题", "Favorite topic"), tint = if (item.favorited) HlovetUi.secondaryAccent else HlovetUi.muted, modifier = Modifier.size(18.dp))
            }
            Text("${item.favoriteCount}", color = HlovetUi.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AnonymousTopicDialog(
    context: Context,
    topic: NativeAnonymousTopicDetail,
    manager: Boolean,
    loading: Boolean,
    saving: Boolean,
    message: String,
    pendingFiles: List<NativeUploadFile>,
    onMessageChange: (String) -> Unit,
    onClose: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenIdentity: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveFile: (NativeUploadFile) -> Unit,
    onSend: () -> Unit,
    onReact: (NativeAnonymousTopicMessage, String) -> Unit,
    onToggleMessageHidden: (NativeAnonymousTopicMessage) -> Unit,
    onToggleTopicHidden: () -> Unit,
    onChangeTopicStatus: () -> Unit,
) {
    val identity = NativeAnonymousTopicApi.readIdentity(context, topic.summary.id)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.94f).heightIn(min = 420.dp, max = 730.dp), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Message, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(topic.summary.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                        if (identity != null) append(nativePhrase("昵称：${identity.nickname} · ", "Nickname: ${identity.nickname} · "))
                                append(if (topic.summary.isHidden) nativePhrase("已隐藏", "Hidden") else if (topic.summary.status == "closed") nativePhrase("已关闭", "Closed") else nativePhrase("${topic.summary.messageCount} 条发言", "${topic.summary.messageCount} messages"))
                            },
                            color = HlovetUi.muted,
                            fontSize = 10.sp,
                        )
                    }
                    if (manager) {
                        IconButton(onClick = onToggleTopicHidden, enabled = !saving, modifier = Modifier.size(32.dp)) { Icon(if (topic.summary.isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (topic.summary.isHidden) nativePhrase("恢复话题", "Restore topic") else nativePhrase("隐藏话题", "Hide topic"), modifier = Modifier.size(17.dp)) }
                    }
                    if (manager || identity?.isCreator == true) {
                        IconButton(onClick = onChangeTopicStatus, enabled = !saving, modifier = Modifier.size(32.dp)) { Icon(if (topic.summary.status == "active") Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = if (topic.summary.status == "active") nativePhrase("关闭话题", "Close topic") else nativePhrase("重新开放话题", "Reopen topic"), modifier = Modifier.size(17.dp)) }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(18.dp)) }
                }
                if (identity == null) {
                    TextButton(onClick = onOpenIdentity, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(nativePhrase("获取昵称后可在其他设备恢复", "Restore this nickname on another device"), fontSize = 11.sp)
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (topic.hasMore) TextButton(onClick = onLoadOlder, enabled = !loading) { Text(if (loading) nativePhrase("读取中…", "Loading...") else nativePhrase("加载更早消息", "Load earlier messages"), fontSize = 11.sp) }
                    if (topic.messages.isEmpty()) Text(nativePhrase("还没有匿名发言。", "No anonymous messages yet."), color = HlovetUi.muted, fontSize = 12.sp)
                    topic.messages.forEach { item ->
                        AnonymousMessageRow(item, manager, onReact, onToggleMessageHidden)
                    }
                }
                if (pendingFiles.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        pendingFiles.forEach { file ->
                            FilterChip(selected = true, onClick = { onRemoveFile(file) }, label = { Text(file.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { Icon(Icons.Filled.Close, contentDescription = nativePhrase("移除附件", "Remove attachment"), modifier = Modifier.size(13.dp)) }, border = null)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onPickFiles, enabled = !saving, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.AttachFile, contentDescription = nativePhrase("添加附件", "Add attachment"), tint = HlovetUi.muted) }
                    OutlinedTextField(value = message, onValueChange = onMessageChange, modifier = Modifier.weight(1f), minLines = 1, maxLines = 4, label = { Text(nativePhrase("说点什么", "Say something"), fontSize = 11.sp) }, colors = loginFieldColors())
                    IconButton(onClick = onSend, enabled = !saving && (message.isNotBlank() || pendingFiles.isNotEmpty()), modifier = Modifier.size(40.dp)) { Icon(if (saving) Icons.Filled.Done else Icons.Filled.Send, contentDescription = nativePhrase("发送", "Send"), tint = HlovetUi.accent) }
                }
            }
        }
    }
}

@Composable
private fun AnonymousMessageRow(
    message: NativeAnonymousTopicMessage,
    manager: Boolean,
    onReact: (NativeAnonymousTopicMessage, String) -> Unit,
    onToggleHidden: (NativeAnonymousTopicMessage) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${message.sequence}", color = HlovetUi.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(message.nickname ?: nativePhrase("匿名用户", "Anonymous user"), color = HlovetUi.muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(formatAnonymousTime(message.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
                if (manager) IconButton(onClick = { onToggleHidden(message) }, modifier = Modifier.size(25.dp)) { Icon(if (message.isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (message.isHidden) nativePhrase("恢复消息", "Restore message") else nativePhrase("隐藏消息", "Hide message"), modifier = Modifier.size(14.dp)) }
            }
            if (message.isHidden) Text(nativePhrase("此消息已隐藏", "This message is hidden"), color = HlovetUi.secondaryAccent, fontSize = 11.sp)
            else {
                if (message.body.isNotBlank()) Text(message.body, fontSize = 12.sp, lineHeight = 18.sp)
                message.attachments.forEach { attachment -> AnonymousAttachmentRow(attachment) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReact(message, "up") }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Icon(Icons.Filled.ThumbUp, contentDescription = nativePhrase("点赞", "Like"), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text(message.likeCount.toString(), fontSize = 10.sp) }
                TextButton(onClick = { onReact(message, "down") }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Icon(Icons.Filled.ThumbDown, contentDescription = nativePhrase("踩", "Dislike"), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text(message.dislikeCount.toString(), fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun AnonymousAttachmentRow(attachment: NativeArticleAttachment) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nativeMediaUrl(attachment.downloadUrl))))
            }.onFailure { if (it is ActivityNotFoundException) Unit }
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(attachment.originalName, color = HlovetUi.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(formatAnonymousBytes(attachment.sizeBytes), color = HlovetUi.muted, fontSize = 9.sp)
    }
}

private fun formatAnonymousTime(value: String): String = runCatching {
    val parsed = java.time.Instant.parse(value)
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date.from(parsed))
}.getOrElse { value.take(16).replace('T', ' ') }

private fun formatAnonymousBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "${value / (1024 * 1024)} MB"
    value >= 1024 -> "${value / 1024} KB"
    else -> "$value B"
}

private fun Boolean?.orFalse(): Boolean = this == true
