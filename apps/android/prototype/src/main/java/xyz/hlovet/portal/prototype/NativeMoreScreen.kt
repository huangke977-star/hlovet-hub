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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** “更多”页只负责组织低频入口，不承载具体业务请求或权限判断。 */
@Composable
internal fun NativeMoreScreen(
    scaffoldPadding: PaddingValues,
    session: NativeSession?,
    onLogin: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenWrite: () -> Unit,
    onOpenDiscoveryMode: (String) -> Unit,
    onOpenTarget: (AccountTarget) -> Unit,
) {
    // 公开入口与登录后入口分组，避免匿名用户看到点击后才发现的空白业务页。
    val publicEntries = listOf(
        NativeMoreEntry("topics", nativePhrase("专题", "Topics"), nativePhrase("按主题浏览文章", "Browse articles by topic"), Icons.Filled.Topic),
        NativeMoreEntry("collections", nativePhrase("合集", "Collections"), nativePhrase("查看整理好的文章集合", "View curated article collections"), Icons.Filled.FolderOpen),
        NativeMoreEntry("announcements", nativePhrase("站点公告", "Announcements"), nativePhrase("查看站点更新和重要通知", "View site updates and notices"), Icons.Filled.NotificationsNone),
        NativeMoreEntry("navigation", nativePhrase("站内导航", "Site navigation"), nativePhrase("查找站点配置的入口", "Find configured site entries"), Icons.Filled.LibraryBooks),
        NativeMoreEntry("tools", nativePhrase("工具中心", "Tools"), nativePhrase("搜索和整理常用工具", "Search and organize useful tools"), Icons.Filled.Build),
        NativeMoreEntry("feedback", nativePhrase("问题反馈", "Report a problem"), nativePhrase("提交问题并查看处理进度", "Report issues and track progress"), Icons.Filled.Feedback),
        NativeMoreEntry("suggestions", nativePhrase("意见建议", "Suggestions"), nativePhrase("提出建议并参与改进", "Share ideas and help improve the site"), Icons.Filled.Lightbulb),
        NativeMoreEntry("resources", nativePhrase("资源中心", "Resource center"), nativePhrase("浏览资源并查看积分兑换", "Browse resources and manage points"), Icons.Filled.AccountBalanceWallet),
    )
    val accountEntries = listOf(
        NativeMoreEntry("write", nativePhrase("写文章", "Write"), nativePhrase("创建文章草稿并继续编辑", "Create and edit an article draft"), Icons.Filled.Edit),
        NativeMoreEntry("my-articles", nativePhrase("我的文章", "My articles"), nativePhrase("管理草稿、发布和文章数据", "Manage drafts, publishing, and article data"), Icons.Filled.LibraryBooks),
        NativeMoreEntry("subscriptions", nativePhrase("我的订阅", "Subscriptions"), nativePhrase("查看作者、专题和合集更新", "View author, topic, and collection updates"), Icons.Filled.Topic),
        NativeMoreEntry("offline", nativePhrase("离线阅读", "Offline reading"), nativePhrase("打开已保存的文章和专题", "Open saved articles and topics"), Icons.Filled.Download),
        NativeMoreEntry("ai", nativePhrase("AI 助手", "AI assistant"), nativePhrase("对话、检索和写作工具", "Chat, retrieval, and writing tools"), Icons.Filled.AutoAwesome),
    )
    val adminEntries = if (session?.isAdministrator == true || session?.isSuperAdmin == true) {
        listOf(NativeMoreEntry("admin", nativePhrase("后台管理", "Administration"), nativePhrase("管理内容、用户和站点设置", "Manage content, users, and site settings"), Icons.Filled.Settings))
    } else {
        emptyList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("更多", "More"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("公告、专题、工具和个人功能", "Announcements, topics, tools, and account features"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = nativePhrase("全站搜索", "Site search"), tint = HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        item {
            Text(
                nativePhrase("把低频入口集中在这里，发现页专注浏览内容。", "Less frequent destinations live here so Discover can stay focused on content."),
                color = HlovetUi.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        moreEntryGroup("${nativePhrase("浏览", "Browse")} · ${publicEntries.size}", publicEntries) { key ->
            // 专题和合集继续回到发现页的既有筛选，不复制列表和订阅逻辑。
            when (key) {
                "topics" -> onOpenDiscoveryMode("专题")
                "collections" -> onOpenDiscoveryMode("合集")
                "announcements" -> onOpenTarget(AccountTarget.Announcements)
                "navigation" -> onOpenTarget(AccountTarget.Navigation)
                "tools" -> onOpenTarget(AccountTarget.Tools)
                "feedback" -> onOpenTarget(AccountTarget.Feedback)
                "suggestions" -> onOpenTarget(AccountTarget.Suggestions)
                "resources" -> onOpenTarget(AccountTarget.ResourceCenter)
            }
        }
        if (session == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Text(
                        nativePhrase("登录后可使用写作、离线阅读、订阅和 AI 功能。", "Sign in to use writing, offline reading, subscriptions, and AI features."),
                        color = HlovetUi.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp).clickable(onClick = onLogin),
                    )
                }
            }
        } else {
            moreEntryGroup(nativePhrase("我的功能", "My features"), accountEntries) { key ->
                // 所有受保护入口统一交给宿主处理登录态和 accountTarget，页面不自行绕过认证。
                when (key) {
                    "write" -> onOpenWrite()
                    "my-articles" -> onOpenTarget(AccountTarget.MyArticles)
                    "subscriptions" -> onOpenTarget(AccountTarget.SubscriptionFeed)
                    "offline" -> onOpenTarget(AccountTarget.Offline)
                    "ai" -> onOpenTarget(AccountTarget.AiAssistant)
                }
            }
            if (adminEntries.isNotEmpty()) {
                moreEntryGroup(nativePhrase("站点管理", "Site management"), adminEntries) { key ->
                    // 管理入口仅在会话资料标记为管理员时展示，最终权限仍由后台页面复核。
                    if (key == "admin") onOpenTarget(AccountTarget.AdminHome)
                }
            }
        }
    }
}

/** “更多”页的入口描述，统一标题、说明、图标和宿主分发键。 */
private data class NativeMoreEntry(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

/** 渲染一组紧凑的玻璃入口行，保持不同功能页使用同一行高和交互反馈。 */
private fun androidx.compose.foundation.lazy.LazyListScope.moreEntryGroup(
    title: String,
    entries: List<NativeMoreEntry>,
    onOpen: (String) -> Unit,
) {
    item {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp))
    }
    item {
        GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column {
                entries.forEachIndexed { index, entry ->
                    NativeMoreEntryRow(entry = entry, onClick = { onOpen(entry.key) })
                    if (index < entries.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(start = 58.dp))
                    }
                }
            }
        }
    }
}

/** 统一入口行的图标、文本和箭头布局，避免入口在不同页面出现不同样式。 */
@Composable
private fun NativeMoreEntryRow(entry: NativeMoreEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.description, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
    }
}
