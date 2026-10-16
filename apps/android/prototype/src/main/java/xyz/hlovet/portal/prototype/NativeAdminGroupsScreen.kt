@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private enum class NativeAdminGroupsTab { Groups, Reports }

private data class NativeAdminGroupBanTarget(
    val group: NativeGroupSummary,
    val lift: Boolean = false,
)

/** 基于 session 的后台身份管理群聊及举报；onBack 返回管理入口，换号销毁旧媒体状态。 */
@Composable
internal fun NativeAdminGroupsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 页面作用域随账号变化而销毁，旧请求不能重新打开另一账号的举报弹窗。
    key(session?.userId, session?.accessToken) {
        NativeAdminGroupsContent(session, onBack)
    }
}

/** 加载 session 的群聊与举报，onBack 只负责导航，媒体仍使用既有认证下载接口。 */
@Composable
private fun NativeAdminGroupsContent(session: NativeSession?, onBack: () -> Unit) {
    // 分页筛选及弹窗状态局限于当前页面身份。
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var tab by remember(token) { mutableStateOf(NativeAdminGroupsTab.Groups) }
    var groups by remember(token) { mutableStateOf<List<NativeGroupSummary>>(emptyList()) }
    var reports by remember(token) { mutableStateOf<List<NativeAdminGroupReport>>(emptyList()) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var reportStatus by remember(token) { mutableStateOf("pending") }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableStateOf(0) }
    var busyKey by remember(token) { mutableStateOf("") }
    var banTarget by remember(token) { mutableStateOf<NativeAdminGroupBanTarget?>(null) }
    var permanent by remember(token) { mutableStateOf(false) }
    var durationMinutes by remember(token) { mutableStateOf("60") }
    var reason by remember(token) { mutableStateOf("") }
    var membersTarget by remember(token) { mutableStateOf<Pair<NativeGroupSummary, NativeGroupDetail>?>(null) }
    var selectedReport by remember(token) { mutableStateOf<NativeAdminGroupReport?>(null) }

    LaunchedEffect(searchDraft) {
        delay(300)
        val next = searchDraft.trim()
        if (next != search) search = next
    }

    LaunchedEffect(token, search, reportStatus, reloadKey) {
        if (token.isNullOrBlank()) {
            groups = emptyList()
            reports = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val groupResult = withContext(Dispatchers.IO) { NativeGroupApi.listAdmin(token, search) }
            val reportResult = withContext(Dispatchers.IO) { NativeGroupApi.listAdminReports(token, reportStatus) }
            groups = groupResult
            reports = reportResult
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("群聊管理数据读取失败。", "Could not load group management data.")
        } finally {
            loading = false
        }
    }

    fun openBan(group: NativeGroupSummary) {
        banTarget = NativeAdminGroupBanTarget(group, lift = false)
        permanent = false
        durationMinutes = "60"
        reason = group.banReason.orEmpty()
        error = null
    }

    fun runBan() {
        val accessToken = token ?: return
        val target = banTarget ?: return
        if (busyKey.isNotBlank()) return
        if (!target.lift && reason.trim().length < 2) {
            error = nativePhrase("请填写至少 2 个字的处理原因。", "Enter at least 2 characters for the reason.")
            return
        }
        val duration = durationMinutes.toIntOrNull()
        if (!target.lift && !permanent && (duration == null || duration < 1)) {
            error = nativePhrase("临时限制时长必须是正整数分钟。", "Temporary restriction duration must be a positive number of minutes.")
            return
        }
        busyKey = "ban:${target.group.id}"
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    if (target.lift) NativeGroupApi.liftAdminGroupBan(accessToken, target.group.id)
                    else NativeGroupApi.banAdminGroup(accessToken, target.group.id, permanent, duration, reason)
                }
                groups = groups.map { if (it.id == updated.id) updated else it }
                banTarget = null
                notice = if (target.lift) nativePhrase("已解除群聊限制。", "Group restriction removed.") else nativePhrase("已更新群聊限制。", "Group restriction updated.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("群聊限制操作失败。", "Could not update the group restriction.")
            } finally {
                busyKey = ""
            }
        }
    }

    fun openMembers(group: NativeGroupSummary) {
        val accessToken = token ?: return
        if (busyKey.isNotBlank()) return
        busyKey = "members:${group.id}"
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { NativeGroupApi.get(accessToken, group.id) }
                membersTarget = group to detail
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("群成员读取失败。", "Could not load group members.")
            } finally {
                busyKey = ""
            }
        }
    }

    fun handleReport(report: NativeAdminGroupReport, nextStatus: String) {
        val accessToken = token ?: return
        if (busyKey.isNotBlank()) return
        busyKey = "report:${report.id}"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NativeGroupApi.handleAdminGroupReport(
                        accessToken,
                        report.id,
                        nextStatus,
                        if (nextStatus == "resolved") nativePhrase("管理员已处理该群消息举报。", "The administrator handled this group-message report.") else nativePhrase("管理员审核后未发现违规。", "The administrator found no violation after review."),
                        deleteMessage = nextStatus == "resolved",
                    )
                }
                reports = reports.filterNot { it.id == report.id }
                selectedReport = null
                notice = if (nextStatus == "resolved") nativePhrase("举报已处理，消息已按规则删除。", "Report handled and message deleted by policy.") else nativePhrase("举报已驳回。", "Report rejected.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("举报处理失败。", "Could not handle the report.")
            } finally {
                busyKey = ""
            }
        }
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
                        Text(nativePhrase("群聊管理", "Group management"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("群聊状态、成员和举报处理", "Groups, members, and reports"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++ }, enabled = !loading && busyKey.isBlank()) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) item { NativeAdminGroupsMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        item {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it.take(60) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                placeholder = { Text(if (tab == NativeAdminGroupsTab.Groups) nativePhrase("搜索群名称或公告", "Search group names or announcements") else nativePhrase("搜索举报内容", "Search reports"), fontSize = 11.sp) },
                colors = nativeAdminGroupsFieldColors(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = tab == NativeAdminGroupsTab.Groups, onClick = { tab = NativeAdminGroupsTab.Groups }, label = { Text(nativePhrase("群聊", "Groups"), fontSize = 10.sp) })
                FilterChip(selected = tab == NativeAdminGroupsTab.Reports, onClick = { tab = NativeAdminGroupsTab.Reports }, label = { Text(nativePhrase("举报", "Reports") + if (reportStatus == "pending") " ${reports.size}" else "", fontSize = 10.sp) })
                if (tab == NativeAdminGroupsTab.Reports) {
                    FilterChip(selected = reportStatus == "pending", onClick = { reportStatus = "pending" }, label = { Text(nativePhrase("待处理", "Pending"), fontSize = 10.sp) })
                    FilterChip(selected = reportStatus == "resolved", onClick = { reportStatus = "resolved" }, label = { Text(nativePhrase("已处理", "Resolved"), fontSize = 10.sp) })
                    FilterChip(selected = reportStatus == "rejected", onClick = { reportStatus = "rejected" }, label = { Text(nativePhrase("已驳回", "Rejected"), fontSize = 10.sp) })
                }
            }
        }
        if (token.isNullOrBlank()) {
            item { NativeAdminGroupsMessage(nativePhrase("登录后查看群聊管理。", "Sign in to view group management."), false, null) }
        } else if (session?.isAdministrator != true && session?.isSuperAdmin != true) {
            item { NativeAdminGroupsMessage(nativePhrase("当前账号没有群聊管理权限。", "This account does not have group management permission."), true, null) }
        } else if (loading && groups.isEmpty() && reports.isEmpty()) {
            item { NativeAdminGroupsMessage(nativePhrase("正在读取群聊管理数据…", "Loading group management data..."), false, null) }
        } else if (tab == NativeAdminGroupsTab.Groups) {
            if (groups.isEmpty()) item { NativeAdminGroupsMessage(nativePhrase("暂无符合条件的群聊。", "No groups match the current filters."), false, null) }
            else items(groups, key = { "group-${it.id}" }) { group ->
                NativeAdminGroupRow(group, busyKey.isBlank(), onMembers = { openMembers(group) }, onBan = { openBan(group) }, onLift = { banTarget = NativeAdminGroupBanTarget(group, lift = true) })
            }
        } else {
            val filtered = reports.filter { report ->
                val q = search.trim().lowercase()
                q.isBlank() || report.group.name.lowercase().contains(q) || report.message.body.lowercase().contains(q) || report.message.senderName.lowercase().contains(q) || report.reporter.nickname.lowercase().contains(q)
            }
            if (filtered.isEmpty()) item { NativeAdminGroupsMessage(nativePhrase("暂无符合条件的群聊举报。", "No group reports match the current filters."), false, null) }
            else items(filtered, key = { "report-${it.id}" }) { report -> NativeAdminGroupReportRow(report, busyKey == "report:${report.id}", onOpen = { selectedReport = report }, onReject = { handleReport(report, "rejected") }, onResolve = { handleReport(report, "resolved") }) }
        }
    }

    banTarget?.let { target ->
        if (target.lift) {
            HlovetAlertDialog(
                onDismissRequest = { if (busyKey.isBlank()) banTarget = null },
                title = { Text(nativePhrase("解除群聊限制", "Remove group restriction")) },
                text = { Text(nativePhrase("确定解除“${target.group.name}”的站点限制吗？", "Remove the site restriction from “${target.group.name}”?"), fontSize = 13.sp) },
                confirmButton = { TextButton(onClick = ::runBan, enabled = busyKey.isBlank()) { Text(nativePhrase("确认解除", "Remove restriction"), color = HlovetUi.accent) } },
                dismissButton = { TextButton(onClick = { banTarget = null }, enabled = busyKey.isBlank()) { Text(nativePhrase("取消", "Cancel")) } },
            )
        } else {
            Dialog(onDismissRequest = { if (busyKey.isBlank()) banTarget = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(.70f).padding(horizontal = 18.dp), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Block, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (target.group.isBanned) nativePhrase("调整群聊限制", "Update group restriction") else nativePhrase("限制群聊", "Restrict group"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(target.group.name, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { if (busyKey.isBlank()) banTarget = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
                        }
                        FilterChip(selected = permanent, onClick = { permanent = !permanent }, label = { Text(nativePhrase("永久限制", "Permanent restriction"), fontSize = 11.sp) })
                        if (!permanent) OutlinedTextField(durationMinutes, { durationMinutes = it.filter(Char::isDigit).take(6) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("临时限制时长（分钟）", "Temporary duration (minutes)")) }, colors = nativeAdminGroupsFieldColors())
                        OutlinedTextField(reason, { reason = it.take(300) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, label = { Text(nativePhrase("处理原因", "Reason")) }, colors = nativeAdminGroupsFieldColors())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { banTarget = null }, enabled = busyKey.isBlank()) { Text(nativePhrase("取消", "Cancel")) }
                            TextButton(onClick = ::runBan, enabled = busyKey.isBlank()) { Text(if (busyKey.isNotBlank()) nativePhrase("处理中…", "Working...") else nativePhrase("确认限制", "Confirm restriction"), color = HlovetUi.secondaryAccent) }
                        }
                    }
                }
            }
        }
    }

    membersTarget?.let { (group, detail) ->
        Dialog(onDismissRequest = { membersTarget = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 18.dp), shape = HlovetUi.cardShape, color = HlovetUi.glassFallback) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(nativePhrase("${group.name} · 成员", "${group.name} · Members"), fontSize = 17.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("${detail.members.count { it.status == "active" }} 人", "${detail.members.count { it.status == "active" }} members"), color = HlovetUi.muted, fontSize = 10.sp) }
                        IconButton(onClick = { membersTarget = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        detail.members.filter { it.status == "active" }.forEach { member ->
                            Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    NativeAdminGroupAvatar(member.user.nickname.ifBlank { member.user.username })
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) { Text(member.alias ?: member.user.nickname, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text("@${member.user.username} · ${nativeAdminGroupRole(member.role)}", color = HlovetUi.muted, fontSize = 10.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedReport?.let { report ->
        HlovetAlertDialog(
            onDismissRequest = { if (busyKey.isBlank()) selectedReport = null },
            title = { Text(nativePhrase("群聊举报详情", "Group report details")) },
            text = {
                // 长举报内容在统一弹窗内滚动，处理按钮始终留在内容区外。
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${report.group.name} · ${nativeAdminGroupReportReason(report.reason)}", color = HlovetUi.muted, fontSize = 11.sp)
                    NativeGroupReportMessagePreview(report, session)
                    if (!report.detail.isNullOrBlank()) Text(nativePhrase("补充说明：${report.detail}", "Additional details: ${report.detail}"), color = HlovetUi.muted, fontSize = 11.sp)
                }
            },
            confirmButton = { if (report.status == "pending") TextButton(onClick = { handleReport(report, "resolved") }, enabled = busyKey.isBlank()) { Text(nativePhrase("处理并删除", "Handle and delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { if (report.status == "pending") TextButton(onClick = { handleReport(report, "rejected") }, enabled = busyKey.isBlank()) { Text(nativePhrase("驳回", "Reject")) } else TextButton(onClick = { selectedReport = null }) { Text(nativePhrase("关闭", "Close")) } },
        )
    }
}

@Composable
private fun NativeAdminGroupRow(group: NativeGroupSummary, enabled: Boolean, onMembers: () -> Unit, onBan: () -> Unit, onLift: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeAdminGroupAvatar(group.name)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text(group.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(nativePhrase("群主 @${group.owner.username} · ${group.memberCount} 人", "Owner @${group.owner.username} · ${group.memberCount} members"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(if (group.isBanned) nativePhrase("已限制", "Restricted") else nativePhrase("正常", "Normal"), color = if (group.isBanned) HlovetUi.secondaryAccent else HlovetUi.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            if (group.announcement.isNotBlank()) Text(group.announcement, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMembers, enabled = enabled) { Text(nativePhrase("成员", "Members"), fontSize = 11.sp) }
                if (group.isBanned) TextButton(onClick = onLift, enabled = enabled) { Text(nativePhrase("解除限制", "Remove restriction"), fontSize = 11.sp, color = HlovetUi.accent) }
                else TextButton(onClick = onBan, enabled = enabled) { Text(nativePhrase("限制群聊", "Restrict group"), fontSize = 11.sp, color = HlovetUi.secondaryAccent) }
            }
        }
    }
}

/** 展示 report 的处理摘要；busy 禁用写操作，onOpen/onReject/onResolve 分别打开、驳回或处理。 */
@Composable
private fun NativeAdminGroupReportRow(report: NativeAdminGroupReport, busy: Boolean, onOpen: () -> Unit, onReject: () -> Unit, onResolve: () -> Unit) {
    // 列表只展示摘要，完整媒体在统一有界详情弹窗中加载。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-group-report-${report.id}"), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(report.group.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(nativePhrase("${nativeAdminGroupReportReason(report.reason)} · 举报人 @${report.reporter.username}", "${nativeAdminGroupReportReason(report.reason)} · Reporter @${report.reporter.username}"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(nativeAdminGroupReportStatus(report.status), color = if (report.status == "pending") HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 9.sp)
            }
            Text(report.message.body.ifBlank { nativePhrase("附件消息", "Attachment message") }, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen, enabled = !busy) { Text(nativePhrase("查看详情", "View details"), fontSize = 10.sp) }
                if (report.status == "pending") {
                    TextButton(onClick = onReject, enabled = !busy) { Text(nativePhrase("驳回", "Reject"), fontSize = 10.sp) }
                    TextButton(onClick = onResolve, enabled = !busy) { Text(nativePhrase("处理并删除", "Handle and delete"), fontSize = 10.sp, color = HlovetUi.secondaryAccent) }
                }
            }
        }
    }
}

@Composable
private fun NativeAdminGroupAvatar(label: String) {
    Surface(shape = HlovetUi.rowShape, color = HlovetUi.surfaceLow, modifier = Modifier.size(35.dp)) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(label.takeLast(2), color = HlovetUi.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun NativeAdminGroupsMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Shield, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            onDismiss?.let { IconButton(onClick = it, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(15.dp)) } }
        }
    }
}

@Composable
private fun nativeAdminGroupsFieldColors() = HlovetUi.fieldColors()

private fun nativeAdminGroupRole(role: String): String = when (role) {
    "owner" -> nativePhrase("群主", "Owner")
    "admin" -> nativePhrase("管理员", "Admin")
    else -> nativePhrase("成员", "Member")
}

private fun nativeAdminGroupReportReason(reason: String): String = when (reason) {
    "spam" -> nativePhrase("垃圾信息", "Spam")
    "harassment" -> nativePhrase("骚扰", "Harassment")
    "illegal" -> nativePhrase("违法内容", "Illegal content")
    "privacy" -> nativePhrase("隐私问题", "Privacy")
    "misinformation" -> nativePhrase("虚假信息", "Misinformation")
    else -> nativePhrase("其他", "Other")
}

private fun nativeAdminGroupReportStatus(status: String): String = when (status) {
    "resolved" -> nativePhrase("已处理", "Resolved")
    "rejected" -> nativePhrase("已驳回", "Rejected")
    else -> nativePhrase("待处理", "Pending")
}
