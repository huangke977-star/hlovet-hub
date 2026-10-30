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
import androidx.compose.ui.platform.testTag
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

/** 区分公开讨论和管理员审核入口，权限不足时不得退回公开模式。 */
internal enum class NativeAnonymousTopicAccess {
    Public,
    Management,
    Denied,
}

/** 根据 management 请求和 isAdministrator 权限返回入口类型，Denied 表示不可加载管理数据。 */
internal fun nativeAnonymousTopicAccess(management: Boolean, isAdministrator: Boolean): NativeAnonymousTopicAccess = when {
    // 公开入口不因当前账号有管理权限而自动升级为审核模式。
    !management -> NativeAnonymousTopicAccess.Public
    isAdministrator -> NativeAnonymousTopicAccess.Management
    else -> NativeAnonymousTopicAccess.Denied
}

/** 展示 session 对应的匿名列表与讨论弹窗；management 仅用于审核入口，onBack 返回宿主。 */
@Composable
internal fun NativeAnonymousTopicsScreen(
    session: NativeSession?,
    management: Boolean = false,
    onBack: () -> Unit,
) {
    // 话题、编辑草稿和匿名身份留在本页面，公开与管理请求沿用同一套组件及服务端权限。
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

    // 系统多选文件返回后统一检查数量和总大小，拒绝时保留现有附件与正文。
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

    /** 按 reset 重读第一页或追加下一页，仅请求当前公开/管理范围内的话题。 */
    fun loadTopics(reset: Boolean = true) {
        // 列表请求互斥；管理权限拒绝不能降级成公开列表。
        if (loading) return
        if (managementDenied) {
            error = nativePhrase("需要管理员权限才能打开匿名话题管理。", "Administrator access is required to manage anonymous topics.")
            loading = false
            return
        }
        loading = true
        error = null
        val targetPage = if (reset) 1 else page + 1
        // 网络在 IO 线程执行，成功后以稳定编号合并；失败保留当前列表并反馈原因。
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

    /** 读取 id 的最新消息，或按 beforeSequence 前插历史，失败不清空正在阅读的内容。 */
    fun loadTopic(id: Int, beforeSequence: Int? = null) {
        // 详情与列表使用相同角色范围，管理权限不足时不请求任何详情。
        if (managementDenied) {
            error = nativePhrase("需要管理员权限才能打开匿名话题管理。", "Administrator access is required to manage anonymous topics.")
            return
        }
        topicLoading = true
        // 历史消息按服务端编号去重，最新窗口由服务端重新核对可见性。
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

    // 搜索输入短暂防抖；筛选和会话变化时重新读取真实列表。
    LaunchedEffect(query, sort, visibility, manager, management, session?.accessToken) {
        delay(if (query.isBlank()) 0 else 220)
        loadTopics()
    }

    // 仅详情打开时定期同步，页面销毁后由 Compose 取消轮询。
    LaunchedEffect(topic?.summary?.id, manager, management, session?.accessToken) {
        val id = topic?.summary?.id ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (!topicLoading) loadTopic(id)
        }
    }

    /** 打开全新的创建草稿，同时清理前一次操作的错误反馈。 */
    fun openCreate() {
        // 每次发起都从空草稿开始，不带入其它话题的昵称和密码。
        titleDraft = ""
        nicknameDraft = ""
        passwordDraft = ""
        error = null
        createOpen = true
    }

    /** 提交当前话题草稿，成功后保存创建者身份并打开详情；失败保留弹窗输入。 */
    fun createTopic() {
        // 防止重复创建和无效草稿进入网络请求。
        if (saving || titleDraft.isBlank() || nicknameDraft.isBlank() || passwordDraft.length < 6) return
        saving = true
        error = null
        // 只有服务端创建成功才绑定新身份，不能在失败时丢失用户输入。
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

    /** 通过当前话题密码创建或恢复昵称，成功才覆盖该话题的本地身份。 */
    fun claimIdentity() {
        // 本地身份只属于当前话题，重复提交期间不得启动第二次恢复。
        val current = topic ?: return
        if (saving || identityPassword.length < 6) return
        saving = true
        error = null
        // 失败将具体错误保留在身份弹窗，成功后清除本次密码草稿。
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

    /** 发送正文及待上传附件，服务端接受后才清空输入并刷新消息。 */
    fun sendMessage() {
        // 空消息和重复提交不触发上传；错误时保留正文与附件以便重试。
        val current = topic ?: return
        if (saving || (messageDraft.isBlank() && pendingFiles.isEmpty())) return
        saving = true
        error = null
        // 使用该话题的匿名凭据发言，登录账号本身不等于匿名创建者身份。
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

    /** 对 message 执行 value 点赞/踩切换，以服务端计数替换同编号消息。 */
    fun react(message: NativeAnonymousTopicMessage, value: String) {
        // 服务端负责单访客反应互斥，本地不自行叠加计数。
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeAnonymousTopicApi.react(context, message.id, value) }
            }.onSuccess { updated ->
                topic = topic?.copy(messages = topic!!.messages.map { if (it.id == updated.id) updated else it })
                loadTopics()
            }.onFailure { error = it.message ?: nativePhrase("操作失败。", "Action failed.") }
        }
    }

    /** 切换 item 的访客喜欢状态，同步列表和已打开详情的计数。 */
    fun favorite(item: NativeAnonymousTopicSummary) {
        // 接口确认结果后再更新，避免失败被误显示为已喜欢。
        error = null
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

    /** 由创建者或当前管理入口切换话题关闭状态，不把普通登录身份当作权限。 */
    fun changeTopicStatus() {
        // 权限取决于管理入口或该话题创建者凭据，与是否登录无关。
        val current = topic ?: return
        val nextStatus = if (current.summary.status == "active") "closed" else "active"
        val identity = NativeAnonymousTopicApi.readIdentity(context, current.summary.id)
        if (!manager && identity?.isCreator != true) {
            error = nativePhrase("只有话题创建者或管理员可以操作话题状态。", "Only the topic creator or an administrator can change its status.")
            return
        }
        saving = true
        error = null
        // 管理员走登录鉴权，创建者走话题凭据；服务端确认后保留访客收藏状态。
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
                topic = topic?.let { currentTopic -> currentTopic.copy(summary = updated.copy(favorited = currentTopic.summary.favorited)) }
                loadTopics()
            }.onFailure { error = it.message ?: "话题状态更新失败。" }
            saving = false
        }
    }

    /** 管理员切换当前话题可见性，失败保留详情和现有审核状态。 */
    fun toggleTopicHidden() {
        // 公开模式即使有登录令牌也不能调用审核写接口。
        val current = topic ?: return
        val token = session?.accessToken
        if (!manager || token.isNullOrBlank()) return
        saving = true
        error = null
        // 隐藏/恢复由服务端执行，返回后刷新列表对应的筛选范围。
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAnonymousTopicApi.updateTopic(token, current.summary.id, isHidden = !current.summary.isHidden) } }
                .onSuccess { updated -> topic = topic?.copy(summary = updated); loadTopics() }
                .onFailure { error = it.message ?: "话题可见性更新失败。" }
            saving = false
        }
    }

    /** 管理员切换 message 可见性并刷新话题列表计数，使用稳定消息编号匹配。 */
    fun toggleMessageHidden(message: NativeAnonymousTopicMessage) {
        // 无管理员令牌时直接拒绝，不把公开身份传给管理接口。
        val token = session?.accessToken
        if (!manager || token.isNullOrBlank()) return
        saving = true
        error = null
        // 成功替换目标行，失败在当前详情内反馈而不是悄悄修改本地状态。
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeAnonymousTopicApi.updateMessage(token, message.id, !message.isHidden) } }
                .onSuccess { updated -> topic = topic?.copy(messages = topic!!.messages.map { if (it.id == updated.id) updated else it }); loadTopics() }
                .onFailure { error = it.message ?: "消息可见性更新失败。" }
            saving = false
        }
    }

    // 列表和全部弹窗共用同一错误状态，错误必须出现在用户当前操作的最上层。
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (management) nativePhrase("匿名话题管理", "Anonymous topic management") else nativePhrase("匿名话题", "Anonymous topics"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    if (!managementDenied) {
                        IconButton(onClick = { loadTopics() }, enabled = !loading && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh")) }
                        IconButton(onClick = ::openCreate, enabled = !saving, modifier = Modifier.testTag("anonymous-topic-create")) { Icon(Icons.Filled.Add, contentDescription = nativePhrase("发起匿名话题", "Create anonymous topic")) }
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
                        modifier = Modifier.fillMaxWidth().testTag("anonymous-topic-search"),
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
                    OutlinedTextField(titleDraft, { titleDraft = it.take(120) }, modifier = Modifier.fillMaxWidth().testTag("anonymous-topic-create-title"), singleLine = true, label = { Text(nativePhrase("话题", "Topic")) }, colors = loginFieldColors())
                    OutlinedTextField(nicknameDraft, { nicknameDraft = it.take(32) }, modifier = Modifier.fillMaxWidth().testTag("anonymous-topic-create-nickname"), singleLine = true, label = { Text(nativePhrase("昵称", "Nickname")) }, colors = loginFieldColors())
                    OutlinedTextField(passwordDraft, { passwordDraft = it.take(128) }, modifier = Modifier.fillMaxWidth().testTag("anonymous-topic-create-password"), singleLine = true, label = { Text(nativePhrase("密码（至少 6 位，用于恢复昵称）", "Password (at least 6 characters, used to restore the nickname)")) }, colors = loginFieldColors())
                    if (!error.isNullOrBlank()) Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.testTag("anonymous-create-error"))
                }
            },
            confirmButton = { TextButton(onClick = ::createTopic, enabled = !saving && titleDraft.isNotBlank() && nicknameDraft.isNotBlank() && passwordDraft.length >= 6, modifier = Modifier.testTag("anonymous-topic-create-submit")) { Text(if (saving) nativePhrase("创建中…", "Creating...") else nativePhrase("创建并进入", "Create and open")) } },
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
                    OutlinedTextField(identityPassword, { identityPassword = it.take(128) }, modifier = Modifier.fillMaxWidth().testTag("anonymous-identity-password"), singleLine = true, label = { Text(nativePhrase("密码", "Password")) }, colors = loginFieldColors())
                    if (!error.isNullOrBlank()) Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.testTag("anonymous-identity-error"))
                }
            },
            confirmButton = { TextButton(onClick = ::claimIdentity, enabled = !saving && identityPassword.length >= 6 && (!identityCreate || identityNickname.isNotBlank()), modifier = Modifier.testTag("anonymous-identity-submit")) { Text(if (saving) nativePhrase("处理中…", "Working...") else nativePhrase("确认", "Confirm")) } },
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
            error = error,
            onMessageChange = { messageDraft = it },
            onClose = { topic = null; identityOpen = false; pendingFiles = emptyList() },
            onLoadOlder = { selected.messages.firstOrNull()?.sequence?.let { loadTopic(selected.summary.id, it) } },
            onOpenIdentity = {
                val identity = NativeAnonymousTopicApi.readIdentity(context, selected.summary.id)
                identityCreate = false
                identityNickname = identity?.nickname.orEmpty()
                identityPassword = ""
                error = null
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
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("anonymous-topic-${item.id}"), shape = HlovetUi.rowShape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen).testTag("anonymous-topic-open-${item.id}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp).testTag("anonymous-topic-favorite-${item.id}")) {
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
    error: String?,
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
        Surface(modifier = Modifier.fillMaxWidth(.94f).heightIn(min = 420.dp, max = 730.dp).testTag("anonymous-topic-dialog-${topic.summary.id}"), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
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
                        IconButton(onClick = onChangeTopicStatus, enabled = !saving, modifier = Modifier.size(32.dp).testTag("anonymous-topic-status")) { Icon(if (topic.summary.status == "active") Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = if (topic.summary.status == "active") nativePhrase("关闭话题", "Close topic") else nativePhrase("重新开放话题", "Reopen topic"), modifier = Modifier.size(17.dp)) }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp).testTag("anonymous-topic-close")) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(18.dp)) }
                }
                if (!error.isNullOrBlank()) {
                    Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.testTag("anonymous-topic-error"))
                }
                if (identity == null) {
                    TextButton(onClick = onOpenIdentity, modifier = Modifier.testTag("anonymous-topic-identity-open"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
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
                    OutlinedTextField(value = message, onValueChange = onMessageChange, modifier = Modifier.weight(1f).testTag("anonymous-topic-message-input"), minLines = 1, maxLines = 4, label = { Text(nativePhrase("说点什么", "Say something"), fontSize = 11.sp) }, colors = loginFieldColors())
                    IconButton(onClick = onSend, enabled = !saving && (message.isNotBlank() || pendingFiles.isNotEmpty()), modifier = Modifier.size(40.dp).testTag("anonymous-topic-send")) { Icon(if (saving) Icons.Filled.Done else Icons.Filled.Send, contentDescription = nativePhrase("发送", "Send"), tint = HlovetUi.accent) }
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
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("anonymous-message-${message.id}"), shape = HlovetUi.rowShape) {
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
                TextButton(onClick = { onReact(message, "up") }, modifier = Modifier.testTag("anonymous-message-like-${message.id}"), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Icon(Icons.Filled.ThumbUp, contentDescription = nativePhrase("点赞", "Like"), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text(message.likeCount.toString(), fontSize = 10.sp) }
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
