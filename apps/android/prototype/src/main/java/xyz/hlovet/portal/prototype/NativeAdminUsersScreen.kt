@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存确认时的目标快照；成功前保留弹窗，服务端仍负责最终权限校验。 */
private data class NativeAdminUserAction(
    val kind: String,
    val user: NativeAdminUser,
)

/** 按账号、令牌和管理权限隔离页面及协程；session 变化后不继承旧列表或密码草稿。 */
@Composable
internal fun NativeAdminUsersScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 权限降级即销毁旧组合，取消尚未完成的管理员请求和表单操作。
    key(session?.userId, session?.accessToken, session?.isAdministrator, session?.isSuperAdmin) {
        NativeAdminUsersContent(session, onBack)
    }
}

/** 展示 session 可管理的账号；onBack 返回上级，所有写操作串行且失败反馈留在当前弹窗。 */
@Composable
private fun NativeAdminUsersContent(session: NativeSession?, onBack: () -> Unit) {
    // 管理权限决定是否发请求，而不仅仅决定页面是否显示列表。
    val token = session?.accessToken
    val canAccess = session?.isAdministrator == true || session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var searchDraft by remember(token) { mutableStateOf("") }
    var search by remember(token) { mutableStateOf("") }
    var page by remember(token) { mutableIntStateOf(1) }
    var pageSize by remember { mutableIntStateOf(20) }
    var pageSizeOpen by remember { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var data by remember(page, pageSize, search, reloadKey) { mutableStateOf<NativeAdminUserPage?>(null) }
    var loading by remember(page, pageSize, search, reloadKey) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var error by remember(page, pageSize, search, reloadKey) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingAction by remember(token) { mutableStateOf<NativeAdminUserAction?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var passwordTarget by remember(token) { mutableStateOf<NativeAdminUser?>(null) }
    var password by remember(token) { mutableStateOf("") }
    var passwordConfirmation by remember(token) { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchDraft) {
        // 搜索防抖后回到第一页；新查询有独立结果，不能操作上一查询的旧行。
        delay(300)
        val normalized = searchDraft.trim()
        if (normalized != search) {
            search = normalized
            page = 1
        }
    }

    LaunchedEffect(token, canAccess, page, pageSize, search, reloadKey) {
        // 普通账号和未登录状态不向用户管理接口发送请求。
        if (token.isNullOrBlank() || !canAccess) {
            data = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            // 服务端分页与统计为权威结果，写操作后也重新读取，避免幂等操作累加统计。
            data = withContext(Dispatchers.IO) { NativeAdminUsersApi.list(token, page = page, pageSize = pageSize, search = search) }
                .also { result -> if (result.page != page) page = result.page }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("用户列表读取失败。", "Could not load the user list.")
        } finally {
            loading = false
        }
    }

    /** 对 action 目标执行已确认操作；全页互斥，成功才关闭弹窗并刷新实际分页和统计。 */
    fun runAction(action: NativeAdminUserAction) {
        // 二次检查当前权限，防止事件重复触发或操作超出管理范围的目标。
        val accessToken = token ?: return
        val permitted = when (action.kind) {
            "administrator" -> canNativeChangeAdministrator(session, action.user)
            "status" -> canNativeChangeStatus(session, action.user)
            "nickname" -> canNativeResetNickname(session, action.user)
            "totp" -> canNativeResetTotp(session, action.user)
            else -> false
        }
        if (busy || loading || !canAccess || !permitted) return
        busy = true
        actionError = null
        notice = null
        scope.launch {
            try {
                // 写请求使用确认时快照；返回消息基于服务器响应，不在本地猜测用户状态。
                when (action.kind) {
                    "administrator" -> {
                        val updated = withContext(Dispatchers.IO) { NativeAdminUsersApi.setAdministrator(accessToken, action.user.id, !action.user.isAdministrator) }
                        notice = if (updated.isAdministrator) nativePhrase("已授予 @${updated.username} 管理员身份。", "Administrator access granted to @${updated.username}.") else nativePhrase("已取消 @${updated.username} 管理员身份。", "Administrator access removed from @${updated.username}.")
                    }
                    "status" -> {
                        val next = if (action.user.status == "active") "disabled" else "active"
                        val updated = withContext(Dispatchers.IO) { NativeAdminUsersApi.setStatus(accessToken, action.user.id, next) }
                        notice = if (updated.status == "active") nativePhrase("已启用 @${updated.username}。", "@${updated.username} enabled.") else nativePhrase("已停用 @${updated.username}。", "@${updated.username} disabled.")
                    }
                    "nickname" -> {
                        val updated = withContext(Dispatchers.IO) { NativeAdminUsersApi.resetNickname(accessToken, action.user.id) }
                        notice = nativePhrase("已将 @${updated.username} 的昵称重置为用户名。", "@${updated.username}'s nickname was reset to the username.")
                    }
                    "totp" -> {
                        val result = withContext(Dispatchers.IO) { NativeAdminUsersApi.resetTotp(accessToken, action.user.id) }
                        notice = nativePhrase("已解除 @${action.user.username} 的双因素认证，并撤销 ${result.revokedSessions} 个会话。", "Two-factor authentication was removed from @${action.user.username}; ${result.revokedSessions} session(s) revoked.")
                    }
                }
                pendingAction = null
                reloadKey++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // 弹窗保持开启，失败原因对用户可见，允许修正或重试。
                actionError = exception.message ?: nativePhrase("用户管理操作失败。", "User management action failed.")
            } finally {
                busy = false
            }
        }
    }

    /** 校验并保存当前目标的新密码；不记录密码，失败不清空输入，也不关闭表单。 */
    fun savePassword() {
        // 本地检查与权限保护先于写请求，重复点击不能并发更新密码。
        val accessToken = token ?: return
        val target = passwordTarget ?: return
        if (busy || loading || !canNativeChangePassword(session, target)) return
        if (password.length < 8) {
            passwordError = nativePhrase("新密码至少需要 8 位。", "The new password must be at least 8 characters.")
            return
        }
        if (password != passwordConfirmation) {
            passwordError = nativePhrase("两次输入的密码不一致。", "The passwords do not match.")
            return
        }
        val submittedPassword = password
        busy = true
        passwordError = null
        notice = null
        scope.launch {
            try {
                // 密码只作为单次请求参数传递，成功后清理两次输入并刷新管理列表。
                val updated = withContext(Dispatchers.IO) { NativeAdminUsersApi.updatePassword(accessToken, target.id, submittedPassword) }
                passwordTarget = null
                password = ""
                passwordConfirmation = ""
                notice = nativePhrase("已更新 @${updated.username} 的密码。", "Password updated for @${updated.username}.")
                reloadKey++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                passwordError = exception.message ?: nativePhrase("密码更新失败。", "Could not update the password.")
            } finally {
                busy = false
            }
        }
    }

    // 页级反馈和服务器分页列表复用现有玻璃组件，所有按钮保持无边框。
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("admin-users-list"),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("用户管理", "User management"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("账号、权限和安全状态", "Accounts, permissions, and security status"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = canAccess && !loading && !busy) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (canAccess) item {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it.take(64) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-users-search"),
                enabled = !busy,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (searchDraft.isNotBlank()) ({ IconButton(onClick = { searchDraft = "" }) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), modifier = Modifier.size(17.dp)) } }) else null,
                placeholder = { Text(nativePhrase("搜索昵称或用户名", "Search nickname or username"), fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 12.sp),
                colors = HlovetUi.fieldColors(),
            )
        }
        if (canAccess) item {
            Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeAdminUserMetric(nativePhrase("账号", "Accounts"), data?.total ?: 0)
                NativeAdminUserMetric(nativePhrase("启用", "Active"), data?.activeCount ?: 0)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { pageSizeOpen = true }, enabled = !busy && !loading, modifier = Modifier.testTag("admin-users-page-size")) {
                        Text(nativePhrase("每页 $pageSize 条", "$pageSize per page"), fontSize = 11.sp)
                        Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = pageSizeOpen, onDismissRequest = { pageSizeOpen = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                        listOf(10, 20, 50).forEach { size ->
                            DropdownMenuItem(text = { Text(nativePhrase("$size 条", "$size items"), fontSize = 12.sp) }, onClick = { pageSizeOpen = false; page = 1; pageSize = size })
                        }
                    }
                }
            }
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (token.isNullOrBlank()) {
            item { NativeAdminUsersMessage(nativePhrase("登录后查看用户管理。", "Sign in to view user management."), Icons.Filled.Lock) }
        } else if (session?.isAdministrator != true && session?.isSuperAdmin != true) {
            item { NativeAdminUsersMessage(nativePhrase("当前账号没有访问用户管理的权限。", "This account cannot access user management."), Icons.Filled.Lock, error = true) }
        } else if (loading) {
            item { NativeAdminUsersMessage(nativePhrase("正在读取用户列表…", "Loading users..."), Icons.Filled.Info) }
        } else if (!error.isNullOrBlank()) {
            item {
                NativeAdminUsersMessage(error!!, Icons.Filled.Error, error = true)
                TextButton(onClick = { reloadKey++ }, modifier = Modifier.padding(horizontal = 20.dp)) { Text(nativePhrase("重试", "Retry")) }
            }
        } else if (data?.items.isNullOrEmpty()) {
            item { NativeAdminUsersMessage(if (search.isBlank()) nativePhrase("暂无用户。", "No users.") else nativePhrase("没有找到匹配的用户。", "No matching users found."), Icons.Filled.PersonOutline) }
        } else {
            items(data!!.items, key = { it.id }) { user ->
                NativeAdminUserRow(
                    actor = session,
                    user = user,
                    busy = busy || loading,
                    onAction = { pendingAction = it; actionError = null },
                    onPassword = { passwordTarget = user; password = ""; passwordConfirmation = ""; passwordError = null },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = !loading && page > 1 && !busy) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                    Text(nativePhrase("第 ${data?.page ?: page} / ${data?.totalPages ?: 1} 页", "Page ${data?.page ?: page} / ${data?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 11.sp)
                    TextButton(onClick = { page++ }, enabled = !loading && page < (data?.totalPages ?: 1) && !busy) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                }
            }
        }
    }

    // 确认框内部展示失败原因；提交期间不允许关闭或切换目标。
    pendingAction?.let { action ->
        val title = when (action.kind) {
            "administrator" -> if (action.user.isAdministrator) nativePhrase("取消管理员身份", "Remove administrator access") else nativePhrase("授予管理员身份", "Grant administrator access")
            "status" -> if (action.user.status == "active") nativePhrase("停用账号", "Disable account") else nativePhrase("启用账号", "Enable account")
            "nickname" -> nativePhrase("重置昵称", "Reset nickname")
            else -> nativePhrase("解除双因素认证", "Remove two-factor authentication")
        }
        val message = when (action.kind) {
            "administrator" -> nativePhrase("确定要变更 @${action.user.username} 的站点管理员身份吗？", "Change site administrator access for @${action.user.username}?")
            "status" -> if (action.user.status == "active") nativePhrase("停用后该账号不能继续登录，确定继续吗？", "The account will no longer be able to sign in after disabling. Continue?") else nativePhrase("确定重新启用 @${action.user.username} 吗？", "Enable @${action.user.username} again?")
            "nickname" -> nativePhrase("将把昵称恢复为 @${action.user.username}，确定继续吗？", "Reset the nickname to @${action.user.username}?")
            else -> nativePhrase("解除后会撤销该账号的现有登录会话，确定继续吗？", "Existing sign-in sessions for this account will be revoked. Continue?")
        }
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) pendingAction = null },
            title = { Text(title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(message, fontSize = 13.sp, lineHeight = 19.sp); HlovetFormFeedback(actionError) } },
            confirmButton = { TextButton(onClick = { runAction(action) }, enabled = !busy) { Text(if (busy) nativePhrase("处理中…", "Working…") else nativePhrase("确认", "Confirm"), color = if (action.kind == "status" && action.user.status == "active" || action.kind == "totp") HlovetUi.secondaryAccent else HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    // 密码表单使用统一对话框，正文限高内滚动，标题和提交按钮始终独立可见。
    passwordTarget?.let { target ->
        val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * .45f).dp
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) { passwordTarget = null; password = ""; passwordConfirmation = "" } },
            title = { Text(nativePhrase("修改密码", "Change password"), fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(max = maxBodyHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text(nativePhrase("目标账号：@${target.username}", "Target account: @${target.username}"), color = HlovetUi.muted, fontSize = 11.sp)
                    OutlinedTextField(password, { password = it.take(128); passwordError = null }, modifier = Modifier.fillMaxWidth().testTag("admin-users-password"), enabled = !busy, singleLine = true, label = { Text(nativePhrase("新密码", "New password"), fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp), visualTransformation = PasswordVisualTransformation(), colors = HlovetUi.fieldColors(), isError = passwordError != null)
                    OutlinedTextField(passwordConfirmation, { passwordConfirmation = it.take(128); passwordError = null }, modifier = Modifier.fillMaxWidth().testTag("admin-users-password-confirmation"), enabled = !busy, singleLine = true, label = { Text(nativePhrase("确认密码", "Confirm password"), fontSize = 12.sp) }, textStyle = TextStyle(fontSize = 13.sp), visualTransformation = PasswordVisualTransformation(), colors = HlovetUi.fieldColors(), isError = passwordError != null)
                    HlovetFormFeedback(passwordError)
                }
            },
            confirmButton = { TextButton(onClick = ::savePassword, enabled = !busy) { Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存", "Save")) } },
            dismissButton = { TextButton(onClick = { passwordTarget = null; password = ""; passwordConfirmation = "" }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

/** 按 actor 的逐项权限展示 user；busy 时所有目标均不可操作，回调只提出操作请求。 */
@Composable
private fun NativeAdminUserRow(
    actor: NativeSession?,
    user: NativeAdminUser,
    busy: Boolean,
    onAction: (NativeAdminUserAction) -> Unit,
    onPassword: () -> Unit,
) {
    // 行标识使用账号 ID，昵称或搜索变化不会改变业务身份。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-user-${user.id}"), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeAdminUserAvatar(user.nickname.ifBlank { user.username })
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(user.nickname, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${user.username}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                NativeAdminUserStatus(user.status)
            }
            Text(user.email, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${user.roleName.ifBlank { user.roleCode }} · Lv.${user.roleLevel}", color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (user.isSuperAdmin) Text(nativePhrase("超级管理员", "Super administrator"), color = HlovetUi.secondaryAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                else if (user.isAdministrator) Text(nativePhrase("站点管理员", "Site administrator"), color = HlovetUi.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                else Text(nativePhrase("普通用户", "User"), color = HlovetUi.muted, fontSize = 10.sp)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (canNativeChangeAdministrator(actor, user)) NativeAdminUserActionButton(Icons.Filled.Shield, nativePhrase("管理员身份", "Administrator access"), busy) { onAction(NativeAdminUserAction("administrator", user)) }
                if (canNativeChangeStatus(actor, user)) NativeAdminUserActionButton(if (user.status == "active") Icons.Filled.Lock else Icons.Filled.Check, if (user.status == "active") nativePhrase("停用账号", "Disable account") else nativePhrase("启用账号", "Enable account"), busy) { onAction(NativeAdminUserAction("status", user)) }
                if (canNativeChangePassword(actor, user)) NativeAdminUserActionButton(Icons.Filled.Key, nativePhrase("修改密码", "Change password"), busy, onClick = onPassword)
                if (canNativeResetNickname(actor, user)) NativeAdminUserActionButton(Icons.Filled.PersonOutline, nativePhrase("重置昵称", "Reset nickname"), busy) { onAction(NativeAdminUserAction("nickname", user)) }
                if (canNativeResetTotp(actor, user)) NativeAdminUserActionButton(Icons.Filled.Visibility, nativePhrase("解除双因素认证", "Remove two-factor authentication"), busy) { onAction(NativeAdminUserAction("totp", user)) }
            }
        }
    }
}

/** 用 icon 与 description 展示无边框操作；busy 禁止点击，onClick 不直接访问接口。 */
@Composable
private fun NativeAdminUserActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, busy: Boolean, onClick: () -> Unit) {
    // 沿用全站图标按钮尺寸与颜色，不增加独立底色或边框。
    IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(34.dp)) { Icon(icon, contentDescription = description, tint = HlovetUi.accent, modifier = Modifier.size(17.dp)) }
}

/** 无头像时使用 text 后两字，与其它账号列表的占位头像规则一致。 */
@Composable
private fun NativeAdminUserAvatar(text: String) {
    // 固定尺寸避免不同昵称导致列表行抖动。
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = HlovetUi.accentSoft, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(text.takeLast(2), color = HlovetUi.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}

/** 将服务端 status 映射成状态文案；注销待处理不能误标为停用。 */
@Composable
private fun NativeAdminUserStatus(status: String) {
    // 已知状态逐项展示，未知值原样保留，避免掩盖服务端新增状态。
    val label = when (status) {
        "active" -> nativePhrase("启用", "Enabled")
        "disabled" -> nativePhrase("停用", "Disabled")
        "deletion_pending" -> nativePhrase("注销待处理", "Deletion pending")
        else -> status
    }
    Text(label, color = if (status == "active") HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}

/** 展示 label 对应的服务器统计 value，不在组件中推算账号数量。 */
@Composable
private fun NativeAdminUserMetric(label: String, value: Int) {
    // 标签作为测试与辅助功能定位范围，避免混淆账号数和启用数。
    Column(Modifier.testTag("admin-users-metric-$label"), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value.toString(), color = HlovetUi.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
    }
}

/** 用统一玻璃行展示 message；icon 表达状态，error 仅影响强调色。 */
@Composable
private fun NativeAdminUsersMessage(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    // 空态与错误共享同一版式，不通过额外卡片层级扩大页面间距。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (error) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

/** actor 仅超级管理员能变更非超级管理员 target 的管理员身份。 */
private fun canNativeChangeAdministrator(actor: NativeSession?, target: NativeAdminUser): Boolean = actor?.isSuperAdmin == true && !target.isSuperAdmin

/** actor 只能在服务端允许的管理层级内启停 target，任何人不能启停超级管理员。 */
private fun canNativeChangeStatus(actor: NativeSession?, target: NativeAdminUser): Boolean =
    !target.isSuperAdmin && (actor?.isSuperAdmin == true || (actor?.isAdministrator == true && !target.isAdministrator))

/** actor 仅超级管理员可改 target 密码，且不能修改其他超级管理员。 */
private fun canNativeChangePassword(actor: NativeSession?, target: NativeAdminUser): Boolean =
    actor?.isSuperAdmin == true && (!target.isSuperAdmin || target.id == actor.userId)

/** target 昵称非用户名时才提供重置；actor 仍须满足相应管理层级。 */
private fun canNativeResetNickname(actor: NativeSession?, target: NativeAdminUser): Boolean =
    !target.isSuperAdmin && target.nickname != target.username && (actor?.isSuperAdmin == true || (actor?.isAdministrator == true && !target.isAdministrator))

/** actor 仅超级管理员可解除非超级管理员 target 已绑定的双因素认证。 */
private fun canNativeResetTotp(actor: NativeSession?, target: NativeAdminUser): Boolean =
    actor?.isSuperAdmin == true && !target.isSuperAdmin && target.totpEnabled
