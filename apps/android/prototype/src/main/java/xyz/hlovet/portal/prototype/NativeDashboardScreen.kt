@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class NativeDashboardData(
    val article: NativeArticleMineSummary,
    val home: NativePortalHomeSummary,
    val inbox: NativeInbox,
    val groups: List<NativeGroupSummary>,
    val moderation: NativeModerationReportSummary?,
)

@Composable
internal fun NativeDashboardScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onWrite: () -> Unit,
    onOpenMyArticles: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenManagementReports: () -> Unit,
    onOpenAdminHome: () -> Unit,
    onOpenAdminSystem: () -> Unit,
) {
    val token = session?.accessToken
    var data by remember(token) { mutableStateOf<NativeDashboardData?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, reloadKey) {
        if (token.isNullOrBlank()) {
            loading = false
            data = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            coroutineScope {
                val article = async(Dispatchers.IO) { NativeArticleApi.mineSummary(token) }
                val home = async(Dispatchers.IO) { NativePortalApi.getHomeSummary(token) }
                val inbox = async(Dispatchers.IO) { NativeSocialApi.loadInbox(token) }
                val groups = async(Dispatchers.IO) { NativeGroupApi.listMine(token) }
                val moderation = if (session?.isSuperAdmin == true || session?.isAdministrator == true) {
                    async(Dispatchers.IO) { NativeReportsApi.summary(token) }
                } else {
                    null
                }
                NativeDashboardData(
                    article = article.await(),
                    home = home.await(),
                    inbox = inbox.await(),
                    groups = groups.await(),
                    moderation = moderation?.await(),
                )
            }
        }.onSuccess { data = it }
            .onFailure { error = it.message ?: nativePhrase("工作台加载失败，请稍后重试。", "Could not load the dashboard. Please try again later.") }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("个人工作台", "Dashboard"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    if (session != null) {
                        IconButton(onClick = { reloadKey += 1 }, enabled = !loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        if (session == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(28.dp))
                        Text(nativePhrase("登录后查看个人工作台", "Sign in to view your dashboard"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(nativePhrase("文章状态、消息待办、群聊和内容数据会集中显示在这里。", "Articles, tasks, groups, and content metrics are collected here."), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = onLogin) { Text(nativePhrase("登录", "Sign in"), fontSize = 13.sp) }
                    }
                }
            }
        } else {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        NativeCoverImage(path = session.avatarUrl, modifier = Modifier.size(48.dp), fallbackText = session.nickname.ifBlank { session.username }, tint = HlovetUi.accent)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.nickname.ifBlank { session.username }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "@${session.username}${if (session.isSuperAdmin || session.isAdministrator) " · ${nativePhrase("站点管理成员", "Site staff")}" else ""}",
                                color = HlovetUi.muted,
                                fontSize = 11.sp,
                            )
                        }
                        TextButton(onClick = onWrite) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(nativePhrase("写文章", "Write"), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (loading && data == null) {
                item { Text(nativePhrase("正在读取个人工作台…", "Loading dashboard..."), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            }
            data?.let { current ->
                item {
                    DashboardPanel(
                        icon = Icons.Filled.Edit,
                        title = nativePhrase("我的创作", "My writing"),
                        subtitle = nativePhrase("文章状态与写作入口", "Article status and writing"),
                        total = current.article.total,
                    ) {
                        DashboardActionRow(nativePhrase("草稿", "Drafts"), current.article.draft, onOpenMyArticles)
                        DashboardActionRow(nativePhrase("已发布", "Published"), current.article.published, onOpenMyArticles)
                        DashboardActionRow(nativePhrase("已下架", "Unpublished"), current.article.unpublished, onOpenMyArticles)
                        DashboardActionRow(nativePhrase("受限与回收站", "Restricted and trash"), current.article.blocked + current.article.deleted, onOpenMyArticles)
                    }
                }
                item {
                    val notificationCount = current.inbox.unreadMessages + current.inbox.pendingFriendRequests + current.inbox.pendingStrangerRequests + current.inbox.unreadNotifications
                    DashboardPanel(
                        icon = Icons.Filled.ChatBubbleOutline,
                        title = nativePhrase("消息待办", "Inbox tasks"),
                        subtitle = nativePhrase("聊天、好友请求和通知", "Chats, friend requests, and notifications"),
                        total = notificationCount,
                    ) {
                        DashboardActionRow(nativePhrase("未读消息", "Unread messages"), current.inbox.unreadMessages, onOpenMessages)
                        DashboardActionRow(nativePhrase("好友申请", "Friend requests"), current.inbox.pendingFriendRequests, onOpenPeople)
                        DashboardActionRow(nativePhrase("陌生人消息请求", "Message requests"), current.inbox.pendingStrangerRequests, onOpenPeople)
                        DashboardActionRow(nativePhrase("未读通知", "Unread notifications"), current.inbox.unreadNotifications, onOpenMessages)
                    }
                }
                item {
                    val pendingGroups = current.groups.sumOf { it.pendingJoinRequestCount }
                    DashboardPanel(
                        icon = Icons.Filled.Groups,
                        title = nativePhrase("我的群聊", "My groups"),
                        subtitle = nativePhrase("群聊列表和入群审批", "Groups and join requests"),
                        total = current.groups.size,
                    ) {
                        DashboardActionRow(nativePhrase("群聊数量", "Groups"), current.groups.size, onOpenGroups)
                        DashboardActionRow(nativePhrase("待处理入群申请", "Pending join requests"), pendingGroups, onOpenGroups)
                    }
                }
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                        Column(Modifier.padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(nativePhrase("内容表现", "Content performance"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                DashboardMetric(nativePhrase("阅读", "Views"), current.home.articleViews)
                                DashboardMetric(nativePhrase("获赞", "Likes"), current.home.likeCount)
                                DashboardMetric(nativePhrase("收藏", "Favorites"), current.home.favoriteCount)
                                DashboardMetric(nativePhrase("评论", "Comments"), current.home.commentCount)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                DashboardMetric(nativePhrase("订阅者", "Subscribers"), current.home.subscriberCount)
                                DashboardMetric(nativePhrase("好友", "Friends"), current.home.friendCount)
                                DashboardMetric(nativePhrase("群聊", "Groups"), current.home.groupCount)
                                DashboardMetric(nativePhrase("已发布", "Published"), current.article.published)
                            }
                        }
                    }
                }
                current.moderation?.let { moderation ->
                    item {
                        DashboardPanel(
                            icon = Icons.Filled.Shield,
                            title = nativePhrase("管理待办", "Moderation tasks"),
                            subtitle = nativePhrase("待处理举报和内容治理", "Reports and content moderation"),
                            total = moderation.pending,
                        ) {
                            DashboardActionRow(nativePhrase("后台管理", "Admin center"), null, onOpenAdminHome)
                            DashboardActionRow(nativePhrase("系统概况", "System overview"), null, onOpenAdminSystem)
                            DashboardActionRow(nativePhrase("文章举报", "Article reports"), moderation.article, onOpenManagementReports)
                            DashboardActionRow(nativePhrase("评论举报", "Comment reports"), moderation.comment, onOpenManagementReports)
                            DashboardActionRow(nativePhrase("群消息举报", "Group message reports"), moderation.groupMessage, onOpenManagementReports)
                        }
                    }
                } ?: item {
                    DashboardPanel(
                        icon = Icons.Filled.AutoAwesome,
                        title = nativePhrase("我的建议", "My feedback"),
                        subtitle = nativePhrase("查看和继续提交站点建议", "Review and submit site feedback"),
                        total = null,
                    ) {
                        DashboardActionRow(nativePhrase("打开意见建议", "Open feedback"), null, onOpenSuggestions)
                    }
                }
            }
            error?.let { message ->
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        TextButton(onClick = { reloadKey += 1 }, enabled = !loading) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardPanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    total: Int?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(subtitle, color = HlovetUi.muted, fontSize = 10.sp)
                }
                total?.let { Text(it.toString(), color = HlovetUi.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun DashboardActionRow(label: String, count: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        count?.let { Text(it.toString(), color = HlovetUi.muted, fontSize = 11.sp) }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(17.dp))
    }
    HorizontalDivider(color = HlovetUi.divider)
}

@Composable
private fun DashboardMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Text(value.toString(), color = HlovetUi.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
    }
}
