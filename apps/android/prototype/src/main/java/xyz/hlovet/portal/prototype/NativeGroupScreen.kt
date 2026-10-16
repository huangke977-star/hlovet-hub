@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NativeGroupView { Mine, Approvals, Discover }

/** 以 session 管理群聊；初始编号用于深链定位，导航回调返回上一页或打开会话。 */
@Composable
internal fun NativeGroupScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenConversation: (NativeConversationPreview) -> Unit,
    initialGroupId: Int? = null,
    initialView: String = "mine",
    initialReportId: Int? = null,
) {
    // 所有请求与媒体归属当前账号组合，换号时不能展示旧群或迟到的旧下载。
    key(session?.userId, session?.accessToken) {
        NativeGroupContent(session, onBack, onOpenConversation, initialGroupId, initialView, initialReportId)
    }
}

/** 加载 session 的群列表和详情；初始参数只指定入口，导航回调不改变服务器权限。 */
@Composable
private fun NativeGroupContent(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenConversation: (NativeConversationPreview) -> Unit,
    initialGroupId: Int?,
    initialView: String,
    initialReportId: Int?,
) {
    // 未登录保留原有门槛，不以公开接口读取管理举报。
    val accessToken = session?.accessToken
    if (accessToken.isNullOrBlank()) {
        Column(Modifier.fillMaxSize()) {
            GroupTopBar(nativePhrase("群聊", "Groups"), onBack = onBack)
            GroupEmptyState(nativePhrase("登录后管理群聊。", "Sign in to manage groups."))
        }
        return
    }

    var view by remember(accessToken, initialView) {
        mutableStateOf(if (initialView == "approvals") NativeGroupView.Approvals else NativeGroupView.Mine)
    }
    var groups by remember(accessToken) { mutableStateOf<List<NativeGroupSummary>>(emptyList()) }
    var approvals by remember(accessToken) { mutableStateOf(NativeGroupApprovals(emptyList(), emptyList())) }
    var selected by remember(accessToken) { mutableStateOf<NativeGroupDetail?>(null) }
    var joinRequests by remember(accessToken) { mutableStateOf<List<NativeGroupJoinRequest>>(emptyList()) }
    var reports by remember(accessToken) { mutableStateOf<List<NativeAdminGroupReport>>(emptyList()) }
    var searchResults by remember(accessToken) { mutableStateOf<List<NativeGroupSummary>>(emptyList()) }
    var searchQuery by remember(accessToken) { mutableStateOf("") }
    var loading by remember(accessToken) { mutableStateOf(true) }
    var busy by remember(accessToken) { mutableStateOf(false) }
    var error by remember(accessToken) { mutableStateOf<String?>(null) }
    var notice by remember(accessToken) { mutableStateOf<String?>(null) }
    var createVisible by remember(accessToken) { mutableStateOf(false) }
    var editVisible by remember(accessToken) { mutableStateOf(false) }
    var joinVisible by remember(accessToken) { mutableStateOf<NativeGroupSummary?>(null) }
    var inviteVisible by remember(accessToken) { mutableStateOf(false) }
    var inviteQuery by remember(accessToken) { mutableStateOf("") }
    var inviteCandidates by remember(accessToken) { mutableStateOf<List<NativeGroupUser>>(emptyList()) }
    var inviteSelectedIds by remember(accessToken) { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmAction by remember(accessToken) { mutableStateOf<String?>(null) }
    var createName by remember(accessToken) { mutableStateOf("") }
    var createTemporary by remember(accessToken) { mutableStateOf(false) }
    var createJoinMode by remember(accessToken) { mutableStateOf("approval") }
    var editName by remember(accessToken) { mutableStateOf("") }
    var editAnnouncement by remember(accessToken) { mutableStateOf("") }
    var editMembersCanInvite by remember(accessToken) { mutableStateOf(true) }
    var alias by remember(accessToken) { mutableStateOf("") }
    var joinNote by remember(accessToken) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val groupId = selected?.summary?.id ?: return@rememberLauncherForActivityResult
        val selectedUri = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, selectedUri, 8L * 1024L * 1024L) }
                withContext(Dispatchers.IO) { NativeGroupApi.uploadAvatar(accessToken, groupId, file) }
            }.onSuccess {
                selected = it
                notice = nativePhrase("群头像已更新。", "Group avatar updated.")
            }.onFailure { error = it.message ?: nativePhrase("群头像上传失败。", "Unable to upload the group avatar.") }
            busy = false
        }
    }

    fun refreshAll() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeGroupApi.listMine(accessToken) to NativeGroupApi.listApprovals(accessToken)
                }
            }.onSuccess { (loadedGroups, loadedApprovals) ->
                groups = loadedGroups
                approvals = loadedApprovals
            }.onFailure { error = it.message ?: nativePhrase("群聊加载失败。", "Unable to load groups.") }
            loading = false
        }
    }

    fun openGroup(groupId: Int) {
        scope.launch {
            busy = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val detail = NativeGroupApi.get(accessToken, groupId)
                    val requests = if (detail.summary.canManage) NativeGroupApi.listJoinRequests(accessToken, groupId) else emptyList()
                    val loadedReports = if (detail.summary.canManage) NativeGroupApi.listAdminReports(accessToken, groupId = groupId) else emptyList()
                    val banRecords = if (detail.summary.canManage) NativeGroupApi.listBanRecords(accessToken, groupId) else detail.banRecords
                    Triple(detail.copy(banRecords = banRecords), requests, loadedReports)
                }
            }.onSuccess { (detail, requests, loadedReports) ->
                selected = detail
                joinRequests = requests
                reports = loadedReports
                alias = detail.summary.currentAlias.orEmpty()
                if (initialReportId != null && loadedReports.any { it.id == initialReportId }) {
                    notice = nativePhrase("已打开待处理举报。", "Opened the pending report.")
                }
            }.onFailure { error = it.message ?: nativePhrase("群聊详情加载失败。", "Unable to load group details.") }
            busy = false
        }
    }

    fun mutate(successMessage: String, block: suspend () -> Unit, after: (() -> Unit)? = null) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    notice = successMessage
                    after?.invoke()
                    refreshAll()
                }
                .onFailure { error = it.message ?: nativePhrase("群聊操作失败。", "Unable to complete the group action.") }
            busy = false
        }
    }

    fun mutateDetail(successMessage: String, block: suspend () -> NativeGroupDetail, after: (NativeGroupDetail) -> Unit) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    notice = successMessage
                    after(it)
                    refreshAll()
                }
                .onFailure { error = it.message ?: nativePhrase("群聊操作失败。", "Unable to complete the group action.") }
            busy = false
        }
    }

    LaunchedEffect(accessToken, initialGroupId, initialView) {
        refreshAll()
        if (initialView != "approvals") initialGroupId?.takeIf { it > 0 }?.let(::openGroup)
    }

    if (selected != null) {
        NativeGroupDetailScreen(
            detail = selected!!,
            joinRequests = joinRequests,
            reports = reports,
            busy = busy,
            error = error,
            notice = notice,
            session = requireNotNull(session),
            alias = alias,
            onAliasChange = { alias = it },
            onBack = { selected = null; joinRequests = emptyList(); reports = emptyList(); refreshAll() },
            onOpenConversation = {
                onOpenConversation(
                    NativeConversationPreview(
                        id = selected!!.summary.conversationId,
                        title = selected!!.summary.name,
                        initials = selected!!.summary.name.takeLast(2),
                        message = selected!!.summary.announcement.ifBlank { nativePhrase("群聊", "Group") },
                        time = "",
                        unreadCount = 0,
                        kind = "group",
                    ),
                )
            },
            onSaveAlias = {
                val group = selected ?: return@NativeGroupDetailScreen
                mutateDetail(nativePhrase("群昵称已更新。", "Group nickname updated."), { NativeGroupApi.updateAlias(accessToken, group.summary.id, alias) }) { updated ->
                    selected = updated
                    openGroup(group.summary.id)
                }
            },
            onEdit = {
                val group = selected ?: return@NativeGroupDetailScreen
                editName = group.summary.name
                editAnnouncement = group.summary.announcement
                editMembersCanInvite = group.summary.membersCanInvite
                editVisible = true
            },
            onInvite = {
                inviteVisible = true
                inviteQuery = ""
                inviteCandidates = emptyList()
                inviteSelectedIds = emptySet()
            },
            onUploadAvatar = { avatarPicker.launch(arrayOf("image/*")) },
            onApproveRequest = { requestId ->
                val group = selected ?: return@NativeGroupDetailScreen
                mutate(nativePhrase("入群申请已同意。", "Join request approved."), { NativeGroupApi.respondJoinRequest(accessToken, group.summary.id, requestId, "approved") }, { openGroup(group.summary.id) })
            },
            onRejectRequest = { requestId ->
                val group = selected ?: return@NativeGroupDetailScreen
                mutate(nativePhrase("入群申请已拒绝。", "Join request rejected."), { NativeGroupApi.respondJoinRequest(accessToken, group.summary.id, requestId, "rejected") }, { openGroup(group.summary.id) })
            },
            onResolveReport = { report ->
                val group = selected ?: return@NativeGroupDetailScreen
                mutate(nativePhrase("举报已处理，消息已删除。", "Report resolved and message deleted."), {
                    NativeGroupApi.handleAdminGroupReport(
                        accessToken,
                        report.id,
                        "resolved",
                        nativePhrase("群管理员已删除消息", "Group administrator deleted the message"),
                        deleteMessage = true,
                    )
                }, { openGroup(group.summary.id) })
            },
            onRejectReport = { report ->
                val group = selected ?: return@NativeGroupDetailScreen
                mutate(nativePhrase("举报已驳回。", "Report rejected."), {
                    NativeGroupApi.handleAdminGroupReport(
                        accessToken,
                        report.id,
                        "rejected",
                        nativePhrase("未发现违规", "No violation found"),
                        deleteMessage = false,
                    )
                }, { openGroup(group.summary.id) })
            },
            onToggleRole = { member ->
                val group = selected ?: return@NativeGroupDetailScreen
                val nextRole = if (member.role == "admin") "member" else "admin"
                mutateDetail(if (nextRole == "admin") nativePhrase("已设为管理员。", "Member promoted to administrator.") else nativePhrase("已取消管理员。", "Administrator role removed."), { NativeGroupApi.updateMember(accessToken, group.summary.id, member.user.id, role = nextRole) }) { updated ->
                    selected = updated
                    openGroup(group.summary.id)
                }
            },
            onToggleMute = { member ->
                val group = selected ?: return@NativeGroupDetailScreen
                val minutes = if (member.mutedUntil.isNullOrBlank()) 60 else 0
                mutateDetail(if (minutes == 0) nativePhrase("已解除禁言。", "Member unmuted.") else nativePhrase("已禁言 1 小时。", "Member muted for 1 hour."), { NativeGroupApi.updateMember(accessToken, group.summary.id, member.user.id, mutedMinutes = minutes) }) { updated ->
                    selected = updated
                    openGroup(group.summary.id)
                }
            },
            onRemoveMember = { member ->
                val group = selected ?: return@NativeGroupDetailScreen
                confirmAction = "remove:${group.summary.id}:${member.user.id}"
            },
            onToggleBlock = { member ->
                val group = selected ?: return@NativeGroupDetailScreen
                confirmAction = "${if (member.status == "blocked") "unblock" else "block"}:${group.summary.id}:${member.user.id}"
            },
            onTransferOwner = { member ->
                val group = selected ?: return@NativeGroupDetailScreen
                confirmAction = "transfer:${group.summary.id}:${member.user.id}"
            },
            onLeave = {
                val group = selected ?: return@NativeGroupDetailScreen
                confirmAction = "leave:${group.summary.id}"
            },
            onDissolve = {
                val group = selected ?: return@NativeGroupDetailScreen
                confirmAction = "dissolve:${group.summary.id}"
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            GroupTopBar(
                title = nativePhrase("群聊", "Groups"),
                onBack = onBack,
                action = {
                    IconButton(onClick = ::refreshAll, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新群聊", "Refresh groups"))
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GroupTab(nativePhrase("我的群聊", "My groups"), view == NativeGroupView.Mine) { view = NativeGroupView.Mine }
                GroupTab(nativePhrase("邀请与申请", "Invitations and requests"), view == NativeGroupView.Approvals) { view = NativeGroupView.Approvals }
                GroupTab(nativePhrase("查找群聊", "Find groups"), view == NativeGroupView.Discover) { view = NativeGroupView.Discover }
            }
            if (!error.isNullOrBlank()) {
                Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (!notice.isNullOrBlank()) {
                Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            when {
                loading -> GroupEmptyState(nativePhrase("正在读取群聊…", "Loading groups..."))
                view == NativeGroupView.Mine -> NativeMyGroups(
                    groups = groups,
                    busy = busy,
                    accessToken = accessToken,
                    onCreate = { createVisible = true },
                    onOpen = ::openGroup,
                )
                view == NativeGroupView.Approvals -> NativeGroupApprovalsScreen(
                    approvals = approvals,
                    busy = busy,
                    accessToken = accessToken,
                    onAccept = { invitation -> mutate(nativePhrase("已加入群聊。", "Joined the group."), { NativeGroupApi.respondInvitationByGroup(accessToken, invitation.group.id, "accepted"); Unit }) },
                    onDecline = { invitation -> mutate(nativePhrase("已拒绝群邀请。", "Group invitation declined."), { NativeGroupApi.respondInvitationByGroup(accessToken, invitation.group.id, "declined"); Unit }) },
                    onOpenGroup = ::openGroup,
                )
                else -> NativeGroupDiscovery(
                    query = searchQuery,
                    results = searchResults,
                    busy = busy,
                    accessToken = accessToken,
                    onQueryChange = { searchQuery = it },
                    onSearch = {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { withContext(Dispatchers.IO) { NativeGroupApi.search(accessToken, searchQuery) } }
                                .onSuccess { searchResults = it }
                                .onFailure { error = it.message ?: nativePhrase("群聊搜索失败。", "Unable to search groups.") }
                            busy = false
                        }
                    },
                    onJoin = { group -> joinVisible = group },
                )
            }
        }
    }

    if (createVisible) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) createVisible = false },
            title = { Text(nativePhrase("创建群聊", "Create group")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(createName, { createName = it }, label = { Text(nativePhrase("群聊名称", "Group name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = HlovetUi.fieldColors())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HlovetCheckbox(checked = createTemporary, onCheckedChange = { createTemporary = it })
                        Text(nativePhrase("临时群聊（创建后可设置有效期）", "Temporary group (expiration can be configured after creation)"), fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = createJoinMode == "approval", onClick = { createJoinMode = "approval" }, label = { Text(nativePhrase("允许申请", "Allow requests")) })
                        FilterChip(selected = createJoinMode == "invite_only", onClick = { createJoinMode = "invite_only" }, label = { Text(nativePhrase("仅限邀请", "Invitation only")) })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && createName.trim().length >= 2,
                    onClick = {
                        val name = createName.trim()
                        mutateDetail(nativePhrase("群聊已创建。", "Group created."), { NativeGroupApi.create(accessToken, name, createTemporary, 7, createJoinMode) }) { created ->
                            selected = created
                            createVisible = false
                            createName = ""
                            openGroup(created.summary.id)
                        }
                    },
                ) { Text(if (busy) nativePhrase("创建中…", "Creating...") else nativePhrase("创建", "Create")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { createVisible = false }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (editVisible && selected != null) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) editVisible = false },
            title = { Text(nativePhrase("编辑群聊", "Edit group")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(editName, { editName = it }, label = { Text(nativePhrase("群聊名称", "Group name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = HlovetUi.fieldColors())
                    OutlinedTextField(editAnnouncement, { editAnnouncement = it }, label = { Text(nativePhrase("群公告", "Announcement")) }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(), colors = HlovetUi.fieldColors())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HlovetCheckbox(checked = editMembersCanInvite, onCheckedChange = { editMembersCanInvite = it })
                        Text(nativePhrase("允许普通成员邀请好友", "Allow regular members to invite friends"), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && editName.trim().length >= 2,
                    onClick = {
                        val groupId = selected!!.summary.id
                        mutateDetail(nativePhrase("群聊信息已更新。", "Group information updated."), {
                            NativeGroupApi.update(accessToken, groupId, editName, editAnnouncement, membersCanInvite = editMembersCanInvite)
                        }) { updated ->
                            selected = updated
                            editVisible = false
                            openGroup(groupId)
                        }
                    },
                ) { Text(if (busy) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { editVisible = false }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    joinVisible?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) joinVisible = null },
            title = { Text(nativePhrase("申请加入群聊", "Request to join group")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        nativePhrase(
                            "${target.memberCount}/${target.memberLimit} 人 · ${if (target.joinMode == "approval") "需要管理员审批" else "仅限邀请"}",
                            "${target.memberCount}/${target.memberLimit} members · ${if (target.joinMode == "approval") "Administrator approval required" else "Invitation only"}",
                        ),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(joinNote, { joinNote = it }, label = { Text(nativePhrase("申请说明（可选）", "Request note (optional)")) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), colors = HlovetUi.fieldColors())
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val group = target
                    mutate(nativePhrase("入群申请已提交。", "Join request submitted."), { NativeGroupApi.requestJoin(accessToken, group.id, joinNote) }, {
                        joinVisible = null
                        joinNote = ""
                    })
                }) { Text(if (busy) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交申请", "Submit request")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { joinVisible = null }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (inviteVisible && selected != null) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) inviteVisible = false },
            title = { Text(nativePhrase("邀请成员", "Invite members")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inviteQuery,
                            onValueChange = { inviteQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(nativePhrase("搜索用户名或昵称", "Search username or nickname")) },
                            colors = HlovetUi.fieldColors(),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching { withContext(Dispatchers.IO) { NativeGroupApi.searchUsers(accessToken, inviteQuery) } }
                                        .onSuccess { inviteCandidates = it }
                                        .onFailure { error = it.message ?: nativePhrase("用户搜索失败。", "Unable to search users.") }
                                    busy = false
                                }
                            },
                            enabled = !busy && inviteQuery.trim().isNotBlank(),
                        ) { Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索成员", "Search members")) }
                    }
                    if (inviteCandidates.isEmpty()) {
                        Text(nativePhrase("搜索后选择要邀请的成员。", "Search, then select members to invite."), color = HlovetUi.muted, fontSize = 12.sp)
                    } else {
                        inviteCandidates.forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    inviteSelectedIds = if (candidate.id in inviteSelectedIds) inviteSelectedIds - candidate.id else inviteSelectedIds + candidate.id
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HlovetCheckbox(
                                    checked = candidate.id in inviteSelectedIds,
                                    onCheckedChange = { checked ->
                                        inviteSelectedIds = if (checked) inviteSelectedIds + candidate.id else inviteSelectedIds - candidate.id
                                    },
                                )
                                GroupAvatar(candidate.nickname, path = candidate.avatarUrl, accessToken = accessToken)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(candidate.nickname, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("@${candidate.username}", color = HlovetUi.muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && inviteSelectedIds.isNotEmpty(),
                    onClick = {
                        val groupId = selected!!.summary.id
                        val ids = inviteSelectedIds.toList()
                        mutate(nativePhrase("群邀请已发送。", "Group invitation sent."), { NativeGroupApi.invite(accessToken, groupId, ids) }, {
                            inviteVisible = false
                            inviteSelectedIds = emptySet()
                            openGroup(groupId)
                        })
                    },
                ) {
                    Text(
                        if (busy) nativePhrase("发送中…", "Sending...") else nativePhrase("发送邀请（${inviteSelectedIds.size}）", "Send invitations (${inviteSelectedIds.size})"),
                    )
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { inviteVisible = false }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    confirmAction?.let { action ->
        val parts = action.split(":")
        val title = when (parts.firstOrNull()) {
            "remove" -> nativePhrase("移除成员", "Remove member")
            "block" -> nativePhrase("拉黑成员", "Block member")
            "unblock" -> nativePhrase("解除拉黑", "Unblock member")
            "transfer" -> nativePhrase("转让群主", "Transfer ownership")
            "leave" -> nativePhrase("退出群聊", "Leave group")
            else -> nativePhrase("解散群聊", "Dissolve group")
        }
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) confirmAction = null },
            title = { Text(title) },
            text = {
                Text(
                    when (parts.firstOrNull()) {
                        "remove" -> nativePhrase("确定要将这名成员移出群聊吗？", "Remove this member from the group?")
                        "block" -> nativePhrase("拉黑后该成员将不能继续加入或发言，确定继续吗？", "Blocked members cannot rejoin or send messages. Continue?")
                        "unblock" -> nativePhrase("解除拉黑后该成员会被移出当前群聊，之后可再次受邀加入。继续吗？", "Unblocking removes this member from the current group, but they can be invited again later. Continue?")
                        "transfer" -> nativePhrase("转让后你将成为管理员，确定把群主身份交给这名成员吗？", "After transferring ownership, you will become an administrator. Transfer ownership to this member?")
                        "leave" -> nativePhrase("退出后需要重新加入才能查看群消息。", "You will need to rejoin before viewing group messages again.")
                        else -> nativePhrase("解散后群聊和历史消息将不再对成员开放。此操作不可撤回。", "The group and its message history will become unavailable to members. This cannot be undone.")
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val operation = parts.firstOrNull()
                    val groupId = parts.getOrNull(1)?.toIntOrNull() ?: return@TextButton
                    when (operation) {
                        "remove" -> {
                            val userId = parts.getOrNull(2)?.toIntOrNull() ?: return@TextButton
                            mutate(nativePhrase("成员已移出群聊。", "Member removed from the group."), { NativeGroupApi.removeMember(accessToken, groupId, userId) }, { confirmAction = null; openGroup(groupId) })
                        }
                        "block" -> {
                            val userId = parts.getOrNull(2)?.toIntOrNull() ?: return@TextButton
                            mutateDetail(nativePhrase("成员已拉黑。", "Member blocked."), { NativeGroupApi.blockMember(accessToken, groupId, userId) }) { updated ->
                                confirmAction = null
                                selected = updated
                                openGroup(groupId)
                            }
                        }
                        "unblock" -> {
                            val userId = parts.getOrNull(2)?.toIntOrNull() ?: return@TextButton
                            mutateDetail(nativePhrase("已解除成员拉黑。", "Member unblocked."), { NativeGroupApi.unblockMember(accessToken, groupId, userId) }) { updated ->
                                confirmAction = null
                                selected = updated
                                openGroup(groupId)
                            }
                        }
                        "transfer" -> {
                            val userId = parts.getOrNull(2)?.toIntOrNull() ?: return@TextButton
                            mutateDetail(nativePhrase("群主已转让。", "Group ownership transferred."), { NativeGroupApi.transferOwner(accessToken, groupId, userId) }) { updated ->
                                confirmAction = null
                                selected = updated
                                openGroup(groupId)
                            }
                        }
                        "leave" -> mutate(nativePhrase("已退出群聊。", "Left the group."), { NativeGroupApi.leave(accessToken, groupId) }, { confirmAction = null; selected = null; refreshAll() })
                        else -> mutate(nativePhrase("群聊已解散。", "Group dissolved."), { NativeGroupApi.dissolve(accessToken, groupId) }, { confirmAction = null; selected = null; refreshAll() })
                    }
                }) { Text(if (busy) nativePhrase("处理中…", "Processing...") else nativePhrase("确认", "Confirm")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmAction = null }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun GroupTopBar(title: String, onBack: () -> Unit, action: @Composable (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun GroupTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = HlovetUi.accentSoft,
            selectedLabelColor = HlovetUi.accent,
        ),
    )
}

@Composable
private fun NativeMyGroups(
    groups: List<NativeGroupSummary>,
    busy: Boolean,
    accessToken: String,
    onCreate: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                    Text(nativePhrase("创建群聊", "Create group"))
            }
        }
        if (groups.isEmpty()) item { GroupEmptyState(nativePhrase("你还没有加入群聊。可以创建一个，或在“查找群聊”中申请加入。", "You have not joined any groups. Create one or request to join from Find groups.")) }
        items(groups, key = { it.id }) { group ->
            GroupSummaryCard(group, accessToken = accessToken, onClick = { onOpen(group.id) })
        }
    }
}

@Composable
private fun NativeGroupApprovalsScreen(
    approvals: NativeGroupApprovals,
    busy: Boolean,
    accessToken: String,
    onAccept: (NativeGroupInvitation) -> Unit,
    onDecline: (NativeGroupInvitation) -> Unit,
    onOpenGroup: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(nativePhrase("群邀请", "Group invitations"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
        if (approvals.invitations.isEmpty()) item { Text(nativePhrase("暂无待处理群邀请。", "No pending group invitations."), color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
        items(approvals.invitations, key = { "invite-${it.id}" }) { invitation ->
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(invitation.group.name, path = invitation.group.avatarUrl, accessToken = accessToken)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(invitation.group.name, fontWeight = FontWeight.SemiBold)
                            Text(nativePhrase("${invitation.inviter.nickname} 邀请你加入", "${invitation.inviter.nickname} invited you"), color = HlovetUi.muted, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { onAccept(invitation) }) { Text(nativePhrase("接受", "Accept")) }
                        TextButton(enabled = !busy, onClick = { onDecline(invitation) }) { Text(nativePhrase("拒绝", "Decline")) }
                    }
                }
            }
        }
        item { Text(nativePhrase("待处理入群申请", "Pending join requests"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
        if (approvals.joinRequests.isEmpty()) item { Text(nativePhrase("暂无待处理入群申请。", "No pending join requests."), color = HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
        items(approvals.joinRequests, key = { "request-${it.id}" }) { request ->
            GlassCard(modifier = Modifier.fillMaxWidth().clickable { onOpenGroup(request.groupId) }, shape = HlovetUi.rowShape) {
                ListItem(
                    headlineContent = { Text(request.user.nickname, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${request.groupName} · ${request.note ?: nativePhrase("未填写申请说明", "No request note")}", color = HlovetUi.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { GroupAvatar(request.user.nickname, path = request.user.avatarUrl, accessToken = accessToken) },
                    trailingContent = { Icon(Icons.Filled.Group, contentDescription = nativePhrase("打开群聊", "Open group")) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun NativeGroupDiscovery(
    query: String,
    results: List<NativeGroupSummary>,
    busy: Boolean,
    accessToken: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onJoin: (NativeGroupSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(query, onQueryChange, modifier = Modifier.weight(1f), singleLine = true, label = { Text(nativePhrase("搜索群聊", "Search groups")) }, colors = HlovetUi.fieldColors())
            IconButton(onClick = onSearch, enabled = !busy && query.trim().isNotBlank()) { Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索", "Search")) }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (results.isEmpty()) item { GroupEmptyState(if (busy) nativePhrase("正在搜索…", "Searching...") else nativePhrase("输入群聊名称后搜索可申请加入的群聊。", "Enter a group name to find groups you can request to join.")) }
            items(results, key = { it.id }) { group ->
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupAvatar(group.name, path = group.avatarUrl, accessToken = accessToken)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    nativePhrase(
                                        "${group.memberCount}/${group.memberLimit} 人 · ${if (group.joinMode == "approval") "可申请加入" else "仅限邀请"}",
                                        "${group.memberCount}/${group.memberLimit} members · ${if (group.joinMode == "approval") "Requests allowed" else "Invitation only"}",
                                    ),
                                    color = HlovetUi.muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (group.announcement.isNotBlank()) Text(group.announcement, color = HlovetUi.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onJoin(group) }, enabled = !busy && group.joinMode == "approval") {
                            Icon(Icons.Filled.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (group.joinMode == "approval") nativePhrase("申请加入", "Request to join") else nativePhrase("仅限邀请", "Invitation only"))
                        }
                    }
                }
            }
        }
    }
}

/** 展示 detail、joinRequests 和 reports；session 提供媒体身份，busy/error/notice 展示操作状态。
 * alias/onAliasChange 管理群昵称草稿，onBack 返回列表，其余 on 回调交由上层执行群管理操作。
 */
@Composable
private fun NativeGroupDetailScreen(
    detail: NativeGroupDetail,
    joinRequests: List<NativeGroupJoinRequest>,
    reports: List<NativeAdminGroupReport>,
    busy: Boolean,
    error: String?,
    notice: String?,
    session: NativeSession,
    alias: String,
    onAliasChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
    onSaveAlias: () -> Unit,
    onEdit: () -> Unit,
    onInvite: () -> Unit,
    onUploadAvatar: () -> Unit,
    onApproveRequest: (Int) -> Unit,
    onRejectRequest: (Int) -> Unit,
    onResolveReport: (NativeAdminGroupReport) -> Unit,
    onRejectReport: (NativeAdminGroupReport) -> Unit,
    onToggleRole: (NativeGroupMember) -> Unit,
    onToggleMute: (NativeGroupMember) -> Unit,
    onRemoveMember: (NativeGroupMember) -> Unit,
    onToggleBlock: (NativeGroupMember) -> Unit,
    onTransferOwner: (NativeGroupMember) -> Unit,
    onLeave: () -> Unit,
    onDissolve: () -> Unit,
) {
    // 群权限来自服务端摘要，附件继续使用当前会话而非群主或举报人的身份。
    val group = detail.summary
    val accessToken = session.accessToken
    Column(Modifier.fillMaxSize()) {
        GroupTopBar(
            title = group.name,
            onBack = onBack,
            action = {
                IconButton(onClick = onOpenConversation) { Icon(Icons.Filled.Group, contentDescription = nativePhrase("进入群聊", "Open conversation")) }
                IconButton(onClick = onEdit, enabled = group.canManage) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑群聊", "Edit group")) }
            },
        )
        if (!error.isNullOrBlank()) Text(error, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (!notice.isNullOrBlank()) Text(notice, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        // 管理信息和举报媒体处于同一懒加载列表，长内容不会挤压页面返回入口。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupAvatar(group.name, path = group.avatarUrl, accessToken = accessToken, large = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(nativePhrase("${group.memberCount}/${group.memberLimit} 人 · ${groupRoleLabel(group.currentMemberRole)}", "${group.memberCount}/${group.memberLimit} members · ${groupRoleLabel(group.currentMemberRole)}"), color = HlovetUi.muted, fontSize = 12.sp)
                        if (group.temporary && !group.expiresAt.isNullOrBlank()) Text(nativePhrase("临时群聊 · ${group.expiresAt}", "Temporary group · ${group.expiresAt}"), color = HlovetUi.muted, fontSize = 11.sp)
                            }
                            if (group.canManage) {
                                IconButton(onClick = onUploadAvatar, enabled = !busy) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = nativePhrase("更换群头像", "Change group avatar"), tint = HlovetUi.accent)
                                }
                            }
                        }
                        if (group.announcement.isNotBlank()) Text(group.announcement, color = HlovetUi.muted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenConversation) { Text(nativePhrase("进入群聊", "Open conversation")) }
                    if (group.currentMemberRole == "owner") TextButton(onClick = onDissolve, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("解散", "Dissolve")) }
                    else TextButton(onClick = onLeave, enabled = !busy) { Icon(Icons.Filled.DoorBack, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("退出", "Leave")) }
                    if (group.canInvite) TextButton(onClick = onInvite, enabled = !busy) { Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(nativePhrase("邀请", "Invite")) }
                        }
                    }
                }
            }
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("我的群昵称", "My group nickname"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(alias, onAliasChange, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(nativePhrase("使用默认昵称", "Use default nickname")) }, colors = HlovetUi.fieldColors())
                        TextButton(onClick = onSaveAlias, enabled = !busy) { Text(nativePhrase("保存", "Save")) }
                        }
                    }
                }
            }
            if (group.canManage && joinRequests.isNotEmpty()) {
            item { Text(nativePhrase("入群申请 · ${joinRequests.size}", "Join requests · ${joinRequests.size}"), color = HlovetUi.muted, fontSize = 12.sp) }
                items(joinRequests, key = { "detail-request-${it.id}" }) { request ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GroupAvatar(request.user.nickname, path = request.user.avatarUrl, accessToken = accessToken)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(request.user.nickname, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(request.note ?: nativePhrase("未填写申请说明", "No request note"), color = HlovetUi.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onApproveRequest(request.id) }, enabled = !busy) { Icon(Icons.Filled.Check, contentDescription = nativePhrase("同意", "Approve")) }
                            IconButton(onClick = { onRejectRequest(request.id) }, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("拒绝", "Reject"), tint = HlovetUi.secondaryAccent) }
                        }
                    }
                }
            }
            if (group.canManage && reports.isNotEmpty()) {
                item { Text(nativePhrase("待处理举报 · ${reports.size}", "Pending reports · ${reports.size}"), color = HlovetUi.muted, fontSize = 12.sp) }
                items(reports, key = { "report-${it.id}" }) { report ->
                    GlassCard(modifier = Modifier.fillMaxWidth().testTag("group-management-report-${report.id}"), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                nativePhrase(
                                    "${report.reporter.nickname} 举报了 ${report.message.senderName}",
                                    "${report.reporter.nickname} reported ${report.message.senderName}",
                                ),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                            // 各举报入口共享原图、播放器和系统文件操作，不再丢弃附件信息。
                            NativeGroupReportMessagePreview(report, session)
                            Text(
                                report.detail?.takeIf { it.isNotBlank() } ?: report.reason,
                                color = HlovetUi.muted,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onResolveReport(report) }, enabled = !busy) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(nativePhrase("处理并删除", "Resolve and delete"), fontSize = 12.sp)
                                }
                                TextButton(onClick = { onRejectReport(report) }, enabled = !busy) {
                                    Text(nativePhrase("驳回", "Reject"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        item { Text(nativePhrase("群成员 · ${detail.members.count { it.status == "active" }}", "Members · ${detail.members.count { it.status == "active" }}"), color = HlovetUi.muted, fontSize = 12.sp) }
            items(detail.members.filter { it.status == "active" }, key = { it.user.id }) { member ->
                GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(member.alias ?: member.user.nickname, path = member.user.avatarUrl, accessToken = accessToken)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member.alias?.takeIf { it.isNotBlank() } ?: member.user.nickname, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            nativePhrase(
                                "@${member.user.username} · ${groupRoleLabel(member.role)}${if (!member.mutedUntil.isNullOrBlank()) " · 已禁言" else ""}",
                                "@${member.user.username} · ${groupRoleLabel(member.role)}${if (!member.mutedUntil.isNullOrBlank()) " · Muted" else ""}",
                            ),
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        }
                        if (group.canManage && !member.isSelf && member.role != "owner") {
                        IconButton(onClick = { onToggleRole(member) }, enabled = !busy) { Icon(Icons.Filled.Shield, contentDescription = if (member.role == "admin") nativePhrase("取消管理员", "Remove administrator") else nativePhrase("设为管理员", "Make administrator"), tint = HlovetUi.accent) }
                        IconButton(onClick = { onToggleMute(member) }, enabled = !busy) { Icon(if (member.mutedUntil.isNullOrBlank()) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = if (member.mutedUntil.isNullOrBlank()) nativePhrase("禁言", "Mute") else nativePhrase("解除禁言", "Unmute"), tint = HlovetUi.muted) }
                        IconButton(onClick = { onRemoveMember(member) }, enabled = !busy) { Icon(Icons.Filled.PersonRemove, contentDescription = nativePhrase("移除成员", "Remove member"), tint = HlovetUi.secondaryAccent) }
                        IconButton(onClick = { onToggleBlock(member) }, enabled = !busy) { Icon(Icons.Filled.Block, contentDescription = nativePhrase("拉黑成员", "Block member"), tint = HlovetUi.secondaryAccent) }
                            if (group.currentMemberRole == "owner") {
                            IconButton(onClick = { onTransferOwner(member) }, enabled = !busy) { Icon(Icons.Filled.SwapHoriz, contentDescription = nativePhrase("转让群主", "Transfer ownership"), tint = HlovetUi.accent) }
                            }
                        }
                    }
                }
            }
            val blockedMembers = detail.members.filter { it.status == "blocked" }
            if (group.canManage && blockedMembers.isNotEmpty()) {
        item { Text(nativePhrase("群黑名单 · ${blockedMembers.size}", "Blocked members · ${blockedMembers.size}"), color = HlovetUi.muted, fontSize = 12.sp) }
                items(blockedMembers, key = { "blocked-${it.user.id}" }) { member ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            GroupAvatar(member.alias ?: member.user.nickname, path = member.user.avatarUrl, accessToken = accessToken)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(member.alias?.takeIf { it.isNotBlank() } ?: member.user.nickname, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(nativePhrase("@${member.user.username} · 已拉黑", "@${member.user.username} · Blocked"), color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                    IconButton(onClick = { onToggleBlock(member) }, enabled = !busy) { Icon(Icons.Filled.Check, contentDescription = nativePhrase("解除拉黑", "Unblock"), tint = HlovetUi.accent) }
                        }
                    }
                }
            }
            if (group.canManage && detail.banRecords.isNotEmpty()) {
        item { Text(nativePhrase("封禁记录 · ${detail.banRecords.size}", "Ban records · ${detail.banRecords.size}"), color = HlovetUi.muted, fontSize = 12.sp) }
                items(detail.banRecords, key = { "ban-${it.id}" }) { record ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(record.reason, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                        nativePhrase(
                            "${record.actor.nickname} · ${record.startsAt.take(16).replace('T', ' ')}${record.expiresAt?.let { " · 至 ${it.take(16).replace('T', ' ')}" } ?: " · 永久"}${if (record.liftedAt != null) " · 已解除" else ""}",
                            "${record.actor.nickname} · ${record.startsAt.take(16).replace('T', ' ')}${record.expiresAt?.let { " · Until ${it.take(16).replace('T', ' ')}" } ?: " · Permanent"}${if (record.liftedAt != null) " · Lifted" else ""}",
                        ),
                                color = HlovetUi.muted,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSummaryCard(group: NativeGroupSummary, accessToken: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = HlovetUi.rowShape) {
        ListItem(
            headlineContent = { Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    nativePhrase(
                        "${group.memberCount}/${group.memberLimit} 人 · ${groupRoleLabel(group.currentMemberRole)}${if (group.pendingJoinRequestCount > 0) " · ${group.pendingJoinRequestCount} 条申请" else ""}",
                        "${group.memberCount}/${group.memberLimit} members · ${groupRoleLabel(group.currentMemberRole)}${if (group.pendingJoinRequestCount > 0) " · ${group.pendingJoinRequestCount} request(s)" else ""}",
                    ),
                    color = HlovetUi.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { GroupAvatar(group.name, path = group.avatarUrl, accessToken = accessToken) },
            trailingContent = { Icon(Icons.Filled.MoreVert, contentDescription = nativePhrase("群聊详情", "Group details"), tint = HlovetUi.muted) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun GroupAvatar(
    label: String,
    path: String? = null,
    accessToken: String? = null,
    large: Boolean = false,
) {
    NativeCoverImage(
        path = path,
        modifier = Modifier.size(if (large) 58.dp else 40.dp),
        fallbackText = label.ifBlank { nativePhrase("群", "G") },
        tint = HlovetUi.accent,
        accessToken = accessToken,
        cacheOwner = "group-avatar",
    )
}

@Composable
private fun GroupEmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = HlovetUi.muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun groupRoleLabel(role: String?): String = when (role) {
    "owner" -> nativePhrase("群主", "Owner")
    "admin" -> nativePhrase("管理员", "Administrator")
    "member" -> nativePhrase("成员", "Member")
    else -> nativePhrase("未加入", "Not joined")
}
