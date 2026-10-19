@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 保存当前编辑的公告草稿；本地时间在提交时按设备时区转换，未发布内容允许不完整。 */
private data class NativeAdminAnnouncementDraft(
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val audience: String = "public",
    val publishMode: String = "immediate",
    val isPinned: Boolean = false,
    val pinOrder: Int = 0,
    val pushEnabled: Boolean = true,
    val scheduledAt: String = "",
    val expiresAt: String = "",
    val roleCodes: List<String> = emptyList(),
)

/** 保留服务端草稿 ID；保存成功但发布失败时重试不能再次新建公告。 */
private data class NativeAdminAnnouncementEditor(
    val id: Int?,
    val status: String,
    val draft: NativeAdminAnnouncementDraft,
    val detail: NativeAdminAnnouncementDetail? = null,
)

/** 保存确认时的目标与动作，失败后继续留在同一确认框重试。 */
private data class NativeAdminAnnouncementAction(
    val id: Int,
    val kind: String,
    val title: String,
)

private val nativeAnnouncementDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 按 session 身份和管理权限隔离页面；onBack 返回后台，身份变化会取消旧协程及草稿。 */
@Composable
internal fun NativeAdminAnnouncementsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 令牌轮换或降权后销毁旧组合，避免旧账号结果进入新账号页面。
    key(session?.userId, session?.accessToken, session?.isAdministrator, session?.isSuperAdmin) {
        NativeAdminAnnouncementsContent(session, onBack)
    }
}

/** 展示当前 session 有权管理的公告，所有保存和状态变更互斥，onBack 返回上级。 */
@Composable
private fun NativeAdminAnnouncementsContent(session: NativeSession?, onBack: () -> Unit) {
    // 权限必须在发请求前检查，不能仅依靠页面隐藏管理按钮。
    val context = LocalContext.current
    val token = session?.accessToken
    val canManage = !token.isNullOrBlank() && (session?.isAdministrator == true || session?.isSuperAdmin == true)
    val scope = rememberCoroutineScope()
    var data by remember(token) { mutableStateOf<NativeAdminAnnouncementPage?>(null) }
    var roles by remember(token) { mutableStateOf<List<NativeRole>>(emptyList()) }
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var status by remember(token) { mutableStateOf("all") }
    var page by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var formError by remember(token) { mutableStateOf<String?>(null) }
    var formNotice by remember(token) { mutableStateOf<String?>(null) }
    var editor by remember(token) { mutableStateOf<NativeAdminAnnouncementEditor?>(null) }
    var editorLoading by remember(token) { mutableStateOf(false) }
    var saving by remember(token) { mutableStateOf(false) }
    var busyId by remember(token) { mutableIntStateOf(0) }
    var pendingAction by remember(token) { mutableStateOf<NativeAdminAnnouncementAction?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchDraft) {
        // 防抖后回到第一页，筛选与服务端分页使用相同查询条件。
        delay(300)
        val normalized = searchDraft.trim()
        if (normalized != search) {
            search = normalized
            page = 1
        }
    }

    LaunchedEffect(token, canManage) {
        // 普通账号不读取管理辅助数据；取消继续传播，不伪装为角色列表为空。
        if (!canManage) {
            roles = emptyList()
            return@LaunchedEffect
        }
        try {
            roles = withContext(Dispatchers.IO) { NativeRoleApi.listRoles() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            roles = emptyList()
        }
    }

    LaunchedEffect(token, canManage, page, search, status, reloadKey) {
        // 每次筛选销毁旧列表，不允许用户对旧查询的行继续操作。
        if (!canManage || token.isNullOrBlank()) {
            data = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        data = null
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                NativeAdminAnnouncementsApi.list(token, page = page, search = search, status = status)
            }
            data = result
            if (result.page != page) page = result.page
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("公告列表加载失败，请稍后重试。", "Could not load announcements. Try again.")
        } finally {
            loading = false
        }
    }

    /** 新建空草稿；有进行中的写操作或详情读取时不能打开另一份编辑器。 */
    fun openNew() {
        // 全页共享操作锁，避免保存与行内发布同时执行。
        if (!canManage || saving || editorLoading || busyId != 0) return
        notice = null
        error = null
        formError = null
        formNotice = null
        editor = NativeAdminAnnouncementEditor(id = null, status = "draft", draft = NativeAdminAnnouncementDraft())
    }

    /** 读取 id 的实时详情并打开编辑器，失败保留列表，取消不显示业务错误。 */
    fun openExisting(id: Int) {
        // 列表筛选、写操作和详情读取互斥，避免打开已失效的行快照。
        val currentToken = token ?: return
        if (!canManage || editorLoading || saving || busyId != 0 || loading) return
        editorLoading = true
        error = null
        formError = null
        formNotice = null
        scope.launch {
            try {
                // 服务端状态为准，列表展示后已发布的公告不能继续修改。
                val detail = withContext(Dispatchers.IO) { NativeAdminAnnouncementsApi.get(currentToken, id) }
                if (detail.summary.status == "published") {
                    error = nativePhrase("公告已发布，请归档后再编辑。", "Archive the published announcement before editing.")
                } else {
                    editor = NativeAdminAnnouncementEditor(detail.summary.id, detail.summary.status, detail.toDraft(), detail)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("公告详情读取失败。", "Could not load the announcement details.")
            } finally {
                editorLoading = false
            }
        }
    }

    /** 更新 next 草稿；保存中忽略编辑，防止保存返回覆盖用户刚输入的内容。 */
    fun updateDraft(next: NativeAdminAnnouncementDraft) {
        // 同步保留未保存输入，仅清理旧成功提示。
        if (saving) return
        val current = editor ?: return
        editor = current.copy(draft = next)
        formNotice = null
    }

    /** 保存当前草稿；publishAfterSave 为真时另发发布请求，失败保留已保存 ID 和正文。 */
    fun saveEditor(publishAfterSave: Boolean) {
        // 两阶段保存不承诺事务回滚，发布失败时草稿依然存在并允许重试。
        val currentToken = token ?: return
        val currentEditor = editor ?: return
        if (!canManage || saving || busyId != 0 || editorLoading) return
        saving = true
        formError = null
        formNotice = null
        scope.launch {
            try {
                // 先保存内容和投放条件，成功后再更新编辑器身份。
                val input = currentEditor.draft.toServerInput()
                val saved = withContext(Dispatchers.IO) {
                    if (currentEditor.id == null) NativeAdminAnnouncementsApi.create(currentToken, input)
                    else NativeAdminAnnouncementsApi.update(currentToken, currentEditor.id, input)
                }
                // 发布独立于保存，失败时保留草稿 ID，重试通过更新而不是再次创建。
                editor = NativeAdminAnnouncementEditor(saved.summary.id, saved.summary.status, saved.toDraft(), saved)
                reloadKey++
                if (publishAfterSave) {
                    val published = withContext(Dispatchers.IO) { NativeAdminAnnouncementsApi.publish(currentToken, saved.summary.id) }
                    editor = null
                    notice = if (published.summary.status == "scheduled") nativePhrase("公告已安排定时发布。", "The announcement was scheduled.") else nativePhrase("公告已发布。", "The announcement was published.")
                } else {
                    formNotice = nativePhrase("公告草稿已保存。", "The announcement draft was saved.")
                }
                if (publishAfterSave) reloadKey++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // 错误放在编辑弹窗内，正文和草稿身份均保留。
                formError = exception.message ?: if (publishAfterSave) nativePhrase("公告发布失败。", "Announcement publishing failed.") else nativePhrase("公告保存失败。", "Announcement saving failed.")
            } finally {
                saving = false
            }
        }
    }

    /** 执行 action 指定的确认操作；权限再校验，成功后才关闭确认框并刷新列表。 */
    fun runAction(action: NativeAdminAnnouncementAction) {
        // 普通管理员只能删除草稿，超级管理员与 PC 一样可以删除其它状态。
        val currentToken = token ?: return
        if (!canManage || busyId != 0 || saving || editorLoading || loading) return
        val target = data?.items?.firstOrNull { it.id == action.id }
        if (target == null) {
            actionError = nativePhrase("公告列表已变化，请关闭弹窗并刷新后重试。", "The list changed. Close this dialog and refresh before retrying.")
            return
        }
        val permitted = when (action.kind) {
            "publish" -> canNativePublishAnnouncement(target)
            "archive" -> target.status == "published"
            "delete" -> session?.isSuperAdmin == true || target.status == "draft"
            else -> false
        }
        if (!permitted) {
            actionError = nativePhrase("公告状态或发布时间已变化，请先编辑或刷新后重试。", "The announcement state or publish time changed. Edit or refresh it before retrying.")
            return
        }
        busyId = action.id
        actionError = null
        error = null
        scope.launch {
            try {
                // 不做乐观移除，服务端拒绝时保留目标与确认上下文。
                withContext(Dispatchers.IO) {
                    when (action.kind) {
                        "publish" -> NativeAdminAnnouncementsApi.publish(currentToken, action.id)
                        "archive" -> NativeAdminAnnouncementsApi.archive(currentToken, action.id)
                        "delete" -> NativeAdminAnnouncementsApi.delete(currentToken, action.id)
                        else -> Unit
                    }
                }
                notice = when (action.kind) {
                    "publish" -> nativePhrase("公告已发布或安排定时发布。", "The announcement was published or scheduled.")
                    "archive" -> nativePhrase("公告已归档。", "The announcement was archived.")
                    else -> nativePhrase("公告已删除。", "The announcement was deleted.")
                }
                pendingAction = null
                reloadKey++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                actionError = exception.message ?: nativePhrase("公告操作失败。", "Announcement action failed.")
            } finally {
                busyId = 0
            }
        }
    }

    /** 将 current 本地时间预填系统选择器，onSelected 接收设备时区下的新时间文本。 */
    fun pickDate(current: String, onSelected: (String) -> Unit) {
        // 日期与时间分两步选择，取消任一步都不改动原草稿。
        val fallback = LocalDateTime.now().plusHours(1)
        val initial = runCatching { LocalDateTime.parse(current, nativeAnnouncementDateFormatter) }.getOrDefault(fallback)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onSelected(LocalDateTime.of(year, month + 1, day, hour, minute).format(nativeAnnouncementDateFormatter)) },
                    initial.hour,
                    initial.minute,
                    true,
                ).show()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("admin-announcements-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("公告管理", "Announcements"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("发布、定时下线和投放范围", "Publish, schedule expiry, and choose an audience"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = canManage && !loading && busyId == 0 && !saving) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
                    IconButton(onClick = ::openNew, enabled = canManage && !saving && !editorLoading && busyId == 0) { Icon(Icons.Filled.NotificationsActive, contentDescription = nativePhrase("新建公告", "New announcement"), tint = HlovetUi.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (token.isNullOrBlank()) {
            item { NativeAdminAnnouncementMessage(nativePhrase("登录后查看公告管理。", "Sign in to manage announcements."), Icons.Filled.Shield) }
        } else if (session?.isAdministrator != true && session?.isSuperAdmin != true) {
            item { NativeAdminAnnouncementMessage(nativePhrase("当前账号没有公告管理权限。", "This account cannot manage announcements."), Icons.Filled.Shield, error = true) }
        } else {
            item {
                OutlinedTextField(
                    value = searchDraft,
                    onValueChange = { searchDraft = it.take(100) },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-announcements-search"),
                    singleLine = true,
                    label = { Text(nativePhrase("搜索公告", "Search announcements"), fontSize = 11.sp) },
                    placeholder = { Text(nativePhrase("标题、摘要或正文", "Title, summary, or body"), fontSize = 12.sp) },
                    colors = nativeAdminAnnouncementFieldColors(),
                )
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    listOf(
                        "all" to nativePhrase("全部", "All"),
                        "draft" to nativePhrase("草稿", "Drafts"),
                        "scheduled" to nativePhrase("待发布", "Scheduled"),
                        "published" to nativePhrase("已发布", "Published"),
                        "expired" to nativePhrase("已下线", "Expired"),
                        "archived" to nativePhrase("已归档", "Archived"),
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = status == value,
                            onClick = { status = value; page = 1 },
                            label = { Text(label, fontSize = 10.sp) },
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HlovetUi.accentSoft),
                        )
                    }
                }
            }
            if (loading && data == null) {
                item { NativeAdminAnnouncementMessage(nativePhrase("正在读取公告…", "Loading announcements…"), Icons.Filled.Info) }
            } else if (!error.isNullOrBlank() && data == null) {
                item { NativeAdminAnnouncementMessage(error!!, Icons.Filled.Error, error = true) }
                item { TextButton(onClick = { reloadKey++ }, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重新加载", "Reload")) } }
            } else {
                if (!error.isNullOrBlank()) item { Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                item { Text(nativePhrase("共 ${data?.total ?: 0} 条公告", "${data?.total ?: 0} announcements"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                if (data?.items.isNullOrEmpty()) {
                    item { NativeAdminAnnouncementMessage(nativePhrase("当前没有匹配的公告。", "No matching announcements."), Icons.Filled.NotificationsNone) }
                } else {
                    items(data?.items.orEmpty(), key = { it.id }) { item ->
                        NativeAdminAnnouncementRow(
                            item = item,
                            busy = busyId != 0 || saving || editorLoading || loading,
                            canDelete = session?.isSuperAdmin == true || item.status == "draft",
                            onEdit = { openExisting(item.id) },
                            onPublish = { actionError = null; pendingAction = NativeAdminAnnouncementAction(item.id, "publish", item.title) },
                            onArchive = { actionError = null; pendingAction = NativeAdminAnnouncementAction(item.id, "archive", item.title) },
                            onDelete = { actionError = null; pendingAction = NativeAdminAnnouncementAction(item.id, "delete", item.title) },
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(nativePhrase("第 ${data?.page ?: page} / ${data?.totalPages ?: 1} 页", "Page ${data?.page ?: page} / ${data?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 10.sp)
                            Row {
                                TextButton(onClick = { page-- }, enabled = !loading && page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                                TextButton(onClick = { page++ }, enabled = !loading && page < (data?.totalPages ?: 1)) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorLoading) {
        HlovetAlertDialog(onDismissRequest = {}, title = { Text(nativePhrase("正在读取公告", "Loading announcement")) }, text = { Text(nativePhrase("请稍候…", "Please wait…"), color = HlovetUi.muted) }, confirmButton = {})
    }

    editor?.let { current ->
        NativeAdminAnnouncementEditorDialog(
            editor = current,
            roles = roles,
            saving = saving,
            error = formError,
            notice = formNotice,
            onDismiss = { if (!saving) editor = null },
            onDraftChange = ::updateDraft,
            onSave = { saveEditor(false) },
            onPublish = { saveEditor(true) },
            onPickDate = ::pickDate,
        )
    }

    pendingAction?.let { action ->
        val (title, message, confirm) = when (action.kind) {
            "publish" -> Triple(nativePhrase("发布公告", "Publish announcement"), nativePhrase("确定发布“${action.title}”吗？发布后会按投放范围生成站内通知。", "Publish “${action.title}”? In-app notifications will be created for the selected audience."), nativePhrase("发布", "Publish"))
            "archive" -> Triple(nativePhrase("归档公告", "Archive announcement"), nativePhrase("归档后公告不再对用户展示，但会保留管理记录。", "Archived announcements are hidden from users but retained in the management record."), nativePhrase("归档", "Archive"))
            else -> Triple(nativePhrase("删除公告", "Delete announcement"), nativePhrase("删除后无法恢复，确定删除“${action.title}”吗？", "Announcements cannot be restored after deletion. Delete “${action.title}”?"), nativePhrase("删除", "Delete"))
        }
        HlovetAlertDialog(
            onDismissRequest = { if (busyId == 0) pendingAction = null },
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, fontSize = 13.sp, lineHeight = 19.sp)
                    HlovetFormFeedback(actionError, null)
                }
            },
            confirmButton = { TextButton(onClick = { runAction(action) }, enabled = busyId == 0 && !loading) { Text(confirm, color = if (action.kind == "delete") HlovetUi.secondaryAccent else HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = busyId == 0) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

/** 绘制 item 公告；busy 禁用整页行操作，canDelete 遵循当前身份权限，其余回调只打开确认。 */
@Composable
private fun NativeAdminAnnouncementRow(
    item: NativeAdminAnnouncementSummary,
    busy: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onPublish: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    // 卡片以稳定公告 ID 标识，状态和投放统计完全来自服务端。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-announcement-${item.id}"), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.isPinned) Icons.Filled.PushPin else Icons.Filled.NotificationsNone, contentDescription = null, tint = if (item.isPinned) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                NativeAdminAnnouncementStatus(item.status)
            }
            Text(item.summary.ifBlank { item.title }, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${announcementAudienceLabel(item.audience)} · ${announcementPublishModeLabel(item.publishMode)} · ${announcementTimeLabel(item)}${if (item.isPinned) nativePhrase(" · 置顶 ${item.pinOrder}", " · Pinned ${item.pinOrder}") else ""}",
                color = HlovetUi.muted,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(nativePhrase("阅读 ${item.viewCount} · 确认 ${item.confirmedCount}/${item.recipientCount}", "Views ${item.viewCount} · Confirmed ${item.confirmedCount}/${item.recipientCount}"), color = HlovetUi.muted, fontSize = 9.sp)
                Row {
                    if (item.status != "published") {
                        IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑", "Edit"), tint = HlovetUi.accent, modifier = Modifier.size(17.dp)) }
                    }
                    if (item.status != "published" && item.status != "scheduled") IconButton(onClick = onPublish, enabled = !busy && canNativePublishAnnouncement(item), modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Send, contentDescription = nativePhrase("发布", "Publish"), tint = HlovetUi.accent, modifier = Modifier.size(17.dp)) }
                    if (item.status == "published") IconButton(onClick = onArchive, enabled = !busy, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Archive, contentDescription = nativePhrase("归档", "Archive"), tint = HlovetUi.muted, modifier = Modifier.size(17.dp)) }
                    if (canDelete) IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除", "Delete"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NativeAdminAnnouncementStatus(status: String) {
    val (label, color) = when (status) {
        "published" -> nativePhrase("已发布", "Published") to HlovetUi.accent
        "scheduled" -> nativePhrase("待发布", "Scheduled") to Color(0xFF9A6700)
        "expired" -> nativePhrase("已下线", "Expired") to HlovetUi.muted
        "archived" -> nativePhrase("已归档", "Archived") to HlovetUi.muted
        else -> nativePhrase("草稿", "Draft") to HlovetUi.secondaryAccent
    }
    Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NativeAdminAnnouncementMessage(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (error) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

/** 编辑 editor 草稿；roles 供投放选择，saving 锁定编辑，错误与成功反馈留在弹窗，回调交给页面执行。 */
@Composable
private fun NativeAdminAnnouncementEditorDialog(
    editor: NativeAdminAnnouncementEditor,
    roles: List<NativeRole>,
    saving: Boolean,
    error: String?,
    notice: String?,
    onDismiss: () -> Unit,
    onDraftChange: (NativeAdminAnnouncementDraft) -> Unit,
    onSave: () -> Unit,
    onPublish: () -> Unit,
    onPickDate: (String, (String) -> Unit) -> Unit,
) {
    // 头部和操作栏固定在有界弹窗内，长正文与投放配置单独滚动。
    val draft = editor.draft
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.imePadding().fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 14.dp),
            shape = HlovetUi.cardShape,
            color = HlovetUi.dialogSurface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editor.id == null) nativePhrase("新建公告", "New announcement") else nativePhrase("编辑公告", "Edit announcement"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(if (editor.id == null) nativePhrase("保存为草稿后再发布", "Save as a draft before publishing") else nativePhrase("状态：${announcementStatusLabel(editor.status)}", "Status: ${announcementStatusLabel(editor.status)}"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(18.dp)) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    OutlinedTextField(draft.title, { value -> onDraftChange(draft.copy(title = value.take(120))) }, modifier = Modifier.fillMaxWidth().testTag("admin-announcement-title"), enabled = !saving, singleLine = true, label = { Text(nativePhrase("标题", "Title")) }, colors = nativeAdminAnnouncementFieldColors())
                    OutlinedTextField(draft.summary, { value -> onDraftChange(draft.copy(summary = value.take(300))) }, modifier = Modifier.fillMaxWidth().testTag("admin-announcement-summary"), enabled = !saving, singleLine = true, label = { Text(nativePhrase("摘要（可选）", "Summary (optional)")) }, colors = nativeAdminAnnouncementFieldColors())
                    OutlinedTextField(draft.content, { value -> onDraftChange(draft.copy(content = value.take(20_000))) }, modifier = Modifier.fillMaxWidth().testTag("admin-announcement-body"), enabled = !saving, minLines = 8, maxLines = 16, label = { Text(nativePhrase("正文", "Body")) }, colors = nativeAdminAnnouncementFieldColors())
                    NativeAnnouncementChoiceRow(
                        label = nativePhrase("投放范围", "Audience"),
                        options = listOf("public" to nativePhrase("所有访客", "All visitors"), "authenticated" to nativePhrase("登录用户", "Signed-in users"), "role_restricted" to nativePhrase("指定角色", "Selected roles")),
                        selected = draft.audience,
                        onSelected = { value -> onDraftChange(draft.copy(audience = value, roleCodes = if (value == "role_restricted") draft.roleCodes else emptyList())) },
                    )
                    NativeAnnouncementChoiceRow(
                        label = nativePhrase("发布方式", "Publish mode"),
                        options = listOf("immediate" to nativePhrase("立即发布", "Publish now"), "scheduled" to nativePhrase("定时发布", "Schedule")),
                        selected = draft.publishMode,
                        onSelected = { value -> onDraftChange(draft.copy(publishMode = value)) },
                    )
                    NativeAnnouncementDateField(nativePhrase("发布时间", "Publish time"), draft.scheduledAt, enabled = draft.publishMode == "scheduled", onPick = { onPickDate(draft.scheduledAt) { value -> onDraftChange(draft.copy(scheduledAt = value)) } }, onClear = { onDraftChange(draft.copy(scheduledAt = "")) }, modifier = Modifier.testTag("admin-announcement-scheduled-at"))
                    NativeAnnouncementDateField(nativePhrase("自动下线时间", "Expiry time"), draft.expiresAt, enabled = true, onPick = { onPickDate(draft.expiresAt) { value -> onDraftChange(draft.copy(expiresAt = value)) } }, onClear = { onDraftChange(draft.copy(expiresAt = "")) }, modifier = Modifier.testTag("admin-announcement-expires-at"))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = draft.isPinned, onClick = { onDraftChange(draft.copy(isPinned = !draft.isPinned)) }, label = { Text(nativePhrase("置顶公告", "Pin announcement"), fontSize = 10.sp) }, leadingIcon = if (draft.isPinned) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) } } else null, border = null)
                        FilterChip(selected = draft.pushEnabled, onClick = { onDraftChange(draft.copy(pushEnabled = !draft.pushEnabled)) }, label = { Text(nativePhrase("浏览器推送", "Browser push"), fontSize = 10.sp) }, leadingIcon = if (draft.pushEnabled) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) } } else null, border = null)
                    }
                    if (draft.isPinned) {
                        OutlinedTextField(draft.pinOrder.toString(), { value -> onDraftChange(draft.copy(pinOrder = value.toIntOrNull()?.coerceAtLeast(0) ?: 0)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("置顶顺序", "Pin order")) }, colors = nativeAdminAnnouncementFieldColors())
                    }
                    if (draft.audience == "role_restricted") {
                        Text(nativePhrase("可见角色", "Visible roles"), color = HlovetUi.muted, fontSize = 11.sp)
                        if (roles.isEmpty()) Text(nativePhrase("暂时读取不到角色列表，请先确认站点角色配置。", "No roles are available. Check the site role configuration."), color = HlovetUi.secondaryAccent, fontSize = 11.sp)
                        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            roles.forEach { role ->
                                val selected = role.code in draft.roleCodes
                                FilterChip(selected = selected, onClick = { onDraftChange(draft.copy(roleCodes = if (selected) draft.roleCodes - role.code else draft.roleCodes + role.code)) }, label = { Text(role.name, fontSize = 10.sp) }, leadingIcon = if (selected) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) } } else null, border = null)
                            }
                        }
                    }
                    editor.detail?.let { detail ->
                        GlassCard(Modifier.fillMaxWidth(), HlovetUi.rowShape) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(nativePhrase("投放与阅读", "Delivery and reading"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(nativePhrase("收件人 ${detail.summary.recipientCount} · 阅读 ${detail.summary.viewCount} · 确认 ${detail.summary.confirmedCount}", "Recipients ${detail.summary.recipientCount} · Views ${detail.summary.viewCount} · Confirmed ${detail.summary.confirmedCount}"), color = HlovetUi.muted, fontSize = 10.sp)
                                Text(nativePhrase("投递尝试 ${detail.deliveryAttempts} 次 · ${if (detail.deliveredAt.isNullOrBlank()) "尚未完成投递" else "已完成投递"}", "Delivery attempts ${detail.deliveryAttempts} · ${if (detail.deliveredAt.isNullOrBlank()) "Not delivered yet" else "Delivered"}"), color = HlovetUi.muted, fontSize = 10.sp)
                                detail.deliveryError?.takeIf(String::isNotBlank)?.let { Text(nativePhrase("投递错误：$it", "Delivery error: $it"), color = HlovetUi.secondaryAccent, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                HlovetFormFeedback(error, notice, Modifier.padding(horizontal = 18.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSave, enabled = !saving, modifier = Modifier.testTag("admin-announcement-save")) { Text(if (saving) nativePhrase("保存中…", "Saving…") else nativePhrase("保存草稿", "Save draft"), fontSize = 12.sp) }
                    TextButton(onClick = onPublish, enabled = !saving && draftCanPublish(draft), modifier = Modifier.testTag("announcement-publish")) { Text(if (draft.publishMode == "scheduled") nativePhrase("安排发布", "Schedule") else nativePhrase("立即发布", "Publish now"), color = HlovetUi.accent, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun NativeAnnouncementChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { (value, text) -> FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(text, fontSize = 10.sp) }, border = null) }
        }
    }
}

/** 展示 label/value 对应的只读时间字段；enabled 控制选择，回调修改草稿，modifier 提供布局与稳定定位。 */
@Composable
private fun NativeAnnouncementDateField(label: String, value: String, enabled: Boolean, onPick: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    // 公告只覆盖禁用态文案与配色，选择、清除和无障碍交互均复用共享时间字段。
    HlovetDateTimeField(
        label = label,
        value = value,
        onPick = onPick,
        onClear = onClear,
        modifier = modifier,
        enabled = enabled,
        placeholder = if (enabled) nativePhrase("点击选择时间", "Tap to choose a time") else nativePhrase("立即发布无需设置", "Not needed when publishing now"),
        colors = nativeAdminAnnouncementFieldColors(),
    )
}

@Composable
private fun nativeAdminAnnouncementFieldColors() = HlovetUi.fieldColors(
    disabledContainerColor = HlovetUi.glassFallback.copy(alpha = .55f),
    disabledBorderColor = HlovetUi.outline.copy(alpha = .5f),
)

private fun NativeAdminAnnouncementDetail.toDraft(): NativeAdminAnnouncementDraft = NativeAdminAnnouncementDraft(
    title = summary.title,
    summary = summary.summary,
    content = content,
    audience = summary.audience,
    publishMode = summary.publishMode,
    isPinned = summary.isPinned,
    pinOrder = summary.pinOrder,
    pushEnabled = summary.pushEnabled,
    scheduledAt = summary.scheduledAt.toLocalAnnouncementTime(),
    expiresAt = summary.expiresAt.toLocalAnnouncementTime(),
    roleCodes = summary.roleCodes,
)

private fun NativeAdminAnnouncementDraft.toServerInput(): NativeAdminAnnouncementInput = NativeAdminAnnouncementInput(
    title = title,
    summary = summary,
    content = content,
    audience = audience,
    publishMode = publishMode,
    isPinned = isPinned,
    pinOrder = pinOrder,
    pushEnabled = pushEnabled,
    scheduledAt = scheduledAt.toAnnouncementInstantOrNull(),
    expiresAt = expiresAt.toAnnouncementInstantOrNull(),
    roleCodes = if (audience == "role_restricted") roleCodes else emptyList(),
)

private fun String?.toLocalAnnouncementTime(): String {
    if (this.isNullOrBlank()) return ""
    return runCatching { Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime().format(nativeAnnouncementDateFormatter) }.getOrDefault(this)
}

private fun String.toAnnouncementInstantOrNull(): String? {
    if (isBlank()) return null
    return LocalDateTime.parse(trim(), nativeAnnouncementDateFormatter).atZone(ZoneId.systemDefault()).toInstant().toString()
}

/** 判断 item 是否可再次发布，now 用于一致的时间边界；正文完整性仍由服务端发布校验。 */
internal fun canNativePublishAnnouncement(item: NativeAdminAnnouncementSummary, now: Instant = Instant.now()): Boolean {
    // 已发布和已安排的定时公告不重复发布；归档或下线公告允许编辑后重新投放。
    if (item.status == "published" || item.status == "scheduled") return false
    val publishAt = if (item.publishMode == "scheduled") {
        runCatching { Instant.parse(item.scheduledAt) }.getOrNull()?.takeIf { it.isAfter(now) } ?: return false
    } else now
    // 下线时间必须晚于本次发布时间，无法解析的非空时间不能绕过校验。
    val expiry = if (item.expiresAt.isNullOrBlank()) null else runCatching { Instant.parse(item.expiresAt) }.getOrNull() ?: return false
    return expiry == null || expiry.isAfter(publishAt)
}

private fun draftCanPublish(draft: NativeAdminAnnouncementDraft): Boolean {
    if (draft.title.isBlank() || draft.content.isBlank()) return false
    val publishAt = if (draft.publishMode == "scheduled") runCatching { LocalDateTime.parse(draft.scheduledAt, nativeAnnouncementDateFormatter).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() ?: return false else Instant.now()
    val expiry = if (draft.expiresAt.isBlank()) null else runCatching { LocalDateTime.parse(draft.expiresAt, nativeAnnouncementDateFormatter).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() ?: return false
    return (draft.publishMode != "scheduled" || publishAt.isAfter(Instant.now())) && (expiry == null || expiry.isAfter(publishAt)) && (draft.audience != "role_restricted" || draft.roleCodes.isNotEmpty())
}

private fun announcementStatusLabel(status: String): String = when (status) {
    "published" -> nativePhrase("已发布", "Published")
    "scheduled" -> nativePhrase("待发布", "Scheduled")
    "expired" -> nativePhrase("已下线", "Expired")
    "archived" -> nativePhrase("已归档", "Archived")
    else -> nativePhrase("草稿", "Draft")
}

private fun announcementAudienceLabel(value: String): String = when (value) {
    "authenticated" -> nativePhrase("登录用户", "Signed-in users")
    "role_restricted" -> nativePhrase("指定角色", "Selected roles")
    else -> nativePhrase("所有访客", "All visitors")
}

private fun announcementPublishModeLabel(value: String): String = if (value == "scheduled") nativePhrase("定时发布", "Scheduled") else nativePhrase("立即发布", "Publish now")

private fun announcementTimeLabel(item: NativeAdminAnnouncementSummary): String = when {
    item.status == "scheduled" && !item.scheduledAt.isNullOrBlank() -> nativePhrase("发布 ${nativeAdminAnnouncementTime(item.scheduledAt!!)}", "Publish ${nativeAdminAnnouncementTime(item.scheduledAt!!)}")
    !item.publishedAt.isNullOrBlank() -> nativePhrase("发布 ${nativeAdminAnnouncementTime(item.publishedAt!!)}", "Published ${nativeAdminAnnouncementTime(item.publishedAt!!)}")
    else -> nativePhrase("更新 ${nativeAdminAnnouncementTime(item.updatedAt)}", "Updated ${nativeAdminAnnouncementTime(item.updatedAt)}")
}

/** 将服务端 value 转为 zoneId 的完整年月日与分钟；无效非空值保留原文，空值显示未知。 */
internal fun nativeAdminAnnouncementTime(value: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
    // 先按时间解析再格式化，不能截取字符串尾部，否则年份和时区信息都会被破坏。
    val normalized = value.trim()
    if (normalized.isEmpty()) return nativePhrase("时间未知", "Unknown time")
    return runCatching { Instant.parse(normalized).atZone(zoneId).format(nativeAnnouncementDateFormatter) }
        .getOrDefault(normalized)
}
