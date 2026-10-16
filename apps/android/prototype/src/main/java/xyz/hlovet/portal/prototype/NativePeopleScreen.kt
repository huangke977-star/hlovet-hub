@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativePeopleScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenConversation: (NativeConversationPreview) -> Unit,
    onNotice: (String) -> Unit,
) {
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var friendships by remember(token) { mutableStateOf<NativeFriendshipLists?>(null) }
    var strangerRequests by remember(token) { mutableStateOf<NativeStrangerRequestLists?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var tab by remember(token) { mutableIntStateOf(0) }
    var query by remember(token) { mutableStateOf("") }
    var searchResults by remember(token) { mutableStateOf<List<NativeSocialSearchUser>>(emptyList()) }
    var searchLoading by remember(token) { mutableStateOf(false) }
    var actionKey by remember(token) { mutableStateOf<String?>(null) }
    var actionError by remember(token) { mutableStateOf<String?>(null) }
    var friendNoteTarget by remember { mutableStateOf<NativeSocialSearchUser?>(null) }
    var friendNote by remember { mutableStateOf("") }
    var strangerTarget by remember { mutableStateOf<NativeSocialSearchUser?>(null) }
    var strangerBody by remember { mutableStateOf("") }

    fun refresh() {
        val accessToken = token ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeFriendsSnapshot(
                        friendships = NativeFriendsApi.listFriendships(accessToken),
                        strangerRequests = NativeFriendsApi.listStrangerRequests(accessToken),
                    )
                }
            }.onSuccess { snapshot ->
                friendships = snapshot.friendships
                strangerRequests = snapshot.strangerRequests
            }.onFailure { exception ->
                error = exception.message ?: nativePhrase("好友和消息请求读取失败。", "Could not load friends and message requests.")
            }
            loading = false
        }
    }

    fun runAction(key: String, successMessage: String, action: suspend () -> Unit) {
        val accessToken = token ?: return
        actionKey = key
        actionError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess {
                    onNotice(successMessage)
                    refresh()
                }
                .onFailure { exception -> actionError = exception.message ?: nativePhrase("操作失败，请稍后重试。", "The operation failed. Please try again.") }
            actionKey = null
        }
    }

    fun search() {
        val accessToken = token ?: return
        if (query.trim().length < 2) {
            actionError = nativePhrase("搜索关键词至少需要 2 个字符。", "Search needs at least 2 characters.")
            return
        }
        searchLoading = true
        actionError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeFriendsApi.searchUsers(accessToken, query) } }
                .onSuccess { searchResults = it }
                .onFailure { exception -> actionError = exception.message ?: nativePhrase("用户搜索失败。", "User search failed.") }
            searchLoading = false
        }
    }

    LaunchedEffect(token) { refresh() }

    val tabLabels = listOf(
        nativePhrase("好友${friendships?.friends?.size?.let { " · $it" }.orEmpty()}", "Friends${friendships?.friends?.size?.let { " · $it" }.orEmpty()}"),
        nativePhrase("好友申请${friendships?.incoming?.size?.let { " · $it" }.orEmpty()}", "Friend requests${friendships?.incoming?.size?.let { " · $it" }.orEmpty()}"),
        nativePhrase("已发申请${friendships?.outgoing?.size?.let { " · $it" }.orEmpty()}", "Sent requests${friendships?.outgoing?.size?.let { " · $it" }.orEmpty()}"),
        nativePhrase("消息请求${strangerRequests?.incoming?.size?.let { " · $it" }.orEmpty()}", "Message requests${strangerRequests?.incoming?.size?.let { " · $it" }.orEmpty()}"),
        nativePhrase("黑名单${friendships?.blocked?.size?.let { " · $it" }.orEmpty()}", "Blocked users${friendships?.blocked?.size?.let { " · $it" }.orEmpty()}"),
    )

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("好友与请求", "Friends and requests"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("管理好友关系和陌生人消息", "Manage friendships and stranger messages"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::refresh) { Icon(Icons.Filled.Refresh, nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabLabels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index; actionError = null },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HlovetUi.accentSoft,
                            selectedLabelColor = HlovetUi.accent,
                        ),
                    )
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; actionError = null },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(nativePhrase("搜索用户名或昵称", "Search username or nickname"), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                        colors = HlovetUi.fieldColors(),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = ::search, enabled = !searchLoading) {
                        Icon(Icons.Filled.PersonAdd, nativePhrase("搜索并添加好友", "Search and add friend"), tint = HlovetUi.accent)
                    }
                }
            }
        }
        if (searchResults.isNotEmpty()) {
            item { Text(nativePhrase("搜索结果", "Search results"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
            items(searchResults, key = { "search-${it.user.id}" }) { result ->
                PeopleSearchRow(
                    item = result,
                    busy = actionKey == "search-${result.user.id}",
                    onAddFriend = {
                        friendNoteTarget = result
                        friendNote = ""
                    },
                    onMessage = {
                        strangerTarget = result
                        strangerBody = ""
                    },
                )
            }
        }
        if (loading && friendships == null) {
            item { PeopleEmpty(nativePhrase("正在读取好友和请求…", "Loading friends and requests...")) }
        } else if (!error.isNullOrBlank() && friendships == null) {
            item { PeopleEmpty(error!!) }
            item { TextButton(onClick = ::refresh, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重新加载", "Reload")) } }
        } else {
            when (tab) {
                0 -> {
                    val rows = friendships?.friends.orEmpty()
                    if (rows.isEmpty()) item { PeopleEmpty(nativePhrase("还没有好友。可以搜索用户并发送好友申请。", "No friends yet. Search for users and send a friend request.")) }
                    items(rows, key = { "friend-${it.id}" }) { friendship ->
                        FriendshipRow(
                            friendship = friendship,
                            actions = {
                                TextButton(
                                    onClick = {
                                        val accessToken = token ?: return@TextButton
                                        actionKey = "chat-${friendship.id}"
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { NativeFriendsApi.getOrCreateConversation(accessToken, friendship.user.id) } }
                                                .onSuccess(onOpenConversation)
                                                .onFailure { actionError = it.message ?: nativePhrase("聊天会话打开失败。", "Could not open the chat.") }
                                            actionKey = null
                                        }
                                    },
                                    enabled = actionKey == null,
                                ) { Text(nativePhrase("打开聊天", "Open chat"), fontSize = 11.sp) }
                                TextButton(
                                    onClick = { runAction("remove-${friendship.id}", nativePhrase("已删除好友关系。", "Friendship removed.")) { NativeFriendsApi.removeFriendship(token!!, friendship.id) } },
                                    enabled = actionKey == null,
                                ) { Icon(Icons.Filled.PersonRemove, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(nativePhrase("删除", "Remove"), fontSize = 11.sp) }
                                TextButton(
                                    onClick = { runAction("block-${friendship.id}", nativePhrase("已拉黑该用户。", "User blocked.")) { NativeFriendsApi.blockFriendship(token!!, friendship.id) } },
                                    enabled = actionKey == null,
                                ) { Icon(Icons.Filled.Shield, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(nativePhrase("拉黑", "Block"), fontSize = 11.sp) }
                            },
                        )
                    }
                }
                1 -> {
                    val rows = friendships?.incoming.orEmpty()
                    if (rows.isEmpty()) item { PeopleEmpty(nativePhrase("暂时没有待处理的好友申请。", "No pending friend requests.")) }
                    items(rows, key = { "incoming-${it.id}" }) { friendship ->
                        FriendshipRow(friendship, actions = {
                            TextButton(
                                onClick = { runAction("accept-${friendship.id}", nativePhrase("已接受好友申请。", "Friend request accepted.")) { NativeFriendsApi.respondFriendRequest(token!!, friendship.id, "accepted") } },
                                enabled = actionKey == null,
                            ) { Icon(Icons.Filled.Check, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(nativePhrase("接受", "Accept"), fontSize = 11.sp) }
                            TextButton(
                                onClick = { runAction("decline-${friendship.id}", nativePhrase("已拒绝好友申请。", "Friend request declined.")) { NativeFriendsApi.respondFriendRequest(token!!, friendship.id, "declined") } },
                                enabled = actionKey == null,
                            ) { Icon(Icons.Filled.Close, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(nativePhrase("拒绝", "Decline"), fontSize = 11.sp) }
                        })
                    }
                }
                2 -> {
                    val rows = friendships?.outgoing.orEmpty()
                    if (rows.isEmpty()) item { PeopleEmpty(nativePhrase("暂时没有已发出的好友申请。", "No sent friend requests.")) }
                    items(rows, key = { "outgoing-${it.id}" }) { FriendshipRow(it, actions = { Text(nativePhrase("等待对方处理", "Waiting for a response"), color = HlovetUi.muted, fontSize = 11.sp) }) }
                }
                3 -> {
                    val incoming = strangerRequests?.incoming.orEmpty()
                    val outgoing = strangerRequests?.outgoing.orEmpty()
                    if (incoming.isEmpty() && outgoing.isEmpty()) item { PeopleEmpty(nativePhrase("暂时没有陌生人消息请求。", "No stranger message requests.")) }
                    if (incoming.isNotEmpty()) item { Text(nativePhrase("收到的请求", "Received requests"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
                    items(incoming, key = { "stranger-in-${it.id}" }) { request ->
                        StrangerRequestRow(request, actions = {
                            TextButton(
                                onClick = { runAction("stranger-accept-${request.id}", nativePhrase("已接受消息请求。", "Message request accepted.")) { NativeFriendsApi.respondStrangerRequest(token!!, request.id, "accepted") } },
                                enabled = actionKey == null,
                            ) { Text(nativePhrase("接受", "Accept"), fontSize = 11.sp) }
                            TextButton(
                                onClick = { runAction("stranger-decline-${request.id}", nativePhrase("已拒绝消息请求。", "Message request declined.")) { NativeFriendsApi.respondStrangerRequest(token!!, request.id, "declined") } },
                                enabled = actionKey == null,
                            ) { Text(nativePhrase("拒绝", "Decline"), fontSize = 11.sp) }
                        })
                    }
                    if (outgoing.isNotEmpty()) item { Text(nativePhrase("发出的请求", "Sent requests"), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
                    items(outgoing, key = { "stranger-out-${it.id}" }) { StrangerRequestRow(it, actions = { Text(nativePhrase("等待对方处理", "Waiting for a response"), color = HlovetUi.muted, fontSize = 11.sp) }) }
                }
                else -> {
                    val rows = friendships?.blocked.orEmpty()
                    if (rows.isEmpty()) item { PeopleEmpty(nativePhrase("暂时没有屏蔽用户。", "No blocked users.")) }
                    items(rows, key = { "blocked-${it.id}" }) { friendship ->
                        FriendshipRow(friendship, actions = {
                            TextButton(
                                onClick = { runAction("unblock-${friendship.id}", nativePhrase("已解除屏蔽。", "User unblocked.")) { NativeFriendsApi.unblockFriendship(token!!, friendship.id) } },
                                enabled = actionKey == null,
                            ) { Text(nativePhrase("解除屏蔽", "Unblock"), fontSize = 11.sp) }
                        })
                    }
                }
            }
        }
        actionError?.let { message -> item { Text(message, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
    }

    friendNoteTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (actionKey == null) friendNoteTarget = null },
            title = { Text(nativePhrase("发送好友申请", "Send friend request")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("向 ${target.user.nickname} 发送申请", "Send a request to ${target.user.nickname}"), fontSize = 13.sp)
                    OutlinedTextField(
                        value = friendNote,
                        onValueChange = { friendNote = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = { Text(nativePhrase("申请备注，可不填", "Optional note"), fontSize = 12.sp) },
                        colors = HlovetUi.fieldColors(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { friendNoteTarget = null }, enabled = actionKey == null) { Text(nativePhrase("取消", "Cancel")) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accessToken = token ?: return@TextButton
                        actionKey = "search-${target.user.id}"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { NativeFriendsApi.requestFriend(accessToken, target.user.id, friendNote) } }
                                .onSuccess {
                                    friendNoteTarget = null
                                    onNotice(nativePhrase("好友申请已发送。", "Friend request sent."))
                                    searchResults = searchResults.map { item -> if (item.user.id == target.user.id) item.copy(canRequest = false, relationshipStatus = "pending", relationshipDirection = "outgoing") else item }
                                }
                                .onFailure { actionError = it.message ?: nativePhrase("好友申请发送失败。", "Could not send friend request.") }
                            actionKey = null
                        }
                    },
                    enabled = actionKey == null,
                ) { Text(nativePhrase("发送", "Send")) }
            },
        )
    }

    strangerTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (actionKey == null) strangerTarget = null },
            title = { Text(nativePhrase("发送消息请求", "Send message request")) },
            text = {
                OutlinedTextField(
                    value = strangerBody,
                    onValueChange = { strangerBody = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text(nativePhrase("输入想发送的消息", "Enter a message"), fontSize = 12.sp) },
                    colors = HlovetUi.fieldColors(),
                )
            },
            dismissButton = { TextButton(onClick = { strangerTarget = null }, enabled = actionKey == null) { Text(nativePhrase("取消", "Cancel")) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accessToken = token ?: return@TextButton
                        actionKey = "message-${target.user.id}"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { NativeFriendsApi.createStrangerRequest(accessToken, target.user.id, strangerBody) } }
                                .onSuccess { strangerTarget = null; onNotice(nativePhrase("消息请求已发送。", "Message request sent.")) }
                                .onFailure { actionError = it.message ?: nativePhrase("消息请求发送失败。", "Could not send message request.") }
                            actionKey = null
                        }
                    },
                    enabled = actionKey == null && strangerBody.trim().isNotBlank(),
                ) { Text(nativePhrase("发送", "Send")) }
            },
        )
    }
}

private data class NativeFriendsSnapshot(
    val friendships: NativeFriendshipLists,
    val strangerRequests: NativeStrangerRequestLists,
)

/** 展示 friendship 对应的关系身份与申请说明，actions 由当前关系状态提供合法操作。 */
@Composable
private fun FriendshipRow(friendship: NativeFriendship, actions: @Composable () -> Unit) {
    // 以稳定关系 ID 标记整张卡片，重复昵称或其它待办不会影响操作目标核对。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("friendship-${friendship.id}"), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PeopleIdentity(friendship.user, friendship.note)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
private fun StrangerRequestRow(request: NativeStrangerMessageRequest, actions: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PeopleIdentity(request.user, null)
            Text(request.body.ifBlank { nativePhrase("（对方没有附加文字）", "(No additional text)") }, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
private fun PeopleSearchRow(
    item: NativeSocialSearchUser,
    busy: Boolean,
    onAddFriend: () -> Unit,
    onMessage: () -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PeopleIdentity(item.user, item.relationshipStatus?.let { nativePhrase("关系：$it", "Relationship: $it") })
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.canRequest) TextButton(onClick = onAddFriend, enabled = !busy) { Text(nativePhrase("加好友", "Add friend"), fontSize = 11.sp) }
                TextButton(onClick = onMessage, enabled = !busy) { Text(nativePhrase("发消息请求", "Message request"), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun PeopleIdentity(user: NativeSocialUser, note: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        PeopleAvatar(user.nickname.takeLast(2))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(user.nickname, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${user.username}", color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.takeIf { it.isNotBlank() }?.let { Text(it, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        user.roleName.takeIf { it.isNotBlank() }?.let { Text(it, color = HlovetUi.accent, fontSize = 10.sp) }
    }
}

@Composable
private fun PeopleAvatar(initials: String) {
    androidx.compose.material3.Surface(
        modifier = Modifier.size(40.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = HlovetUi.accentSoft,
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(initials.ifBlank { nativePhrase("用户", "User") }, color = HlovetUi.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PeopleEmpty(message: String) {
    Text(message, color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}
