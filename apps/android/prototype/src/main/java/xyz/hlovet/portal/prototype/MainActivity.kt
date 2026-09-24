package xyz.hlovet.portal.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF0B111A)
private val Panel = Color(0xFF141E2A)
private val PanelRaised = Color(0xFF1B2836)
private val TextPrimary = Color(0xFFF1F6FA)
private val TextMuted = Color(0xFF97A8B8)
private val Accent = Color(0xFF6ED8C1)
private val AccentSoft = Color(0xFF183B3A)
private val Coral = Color(0xFFFFB19D)

private val articles = listOf(
    ArticlePreview("把复杂的事情，写成清晰的路径", "nice3", "8 分钟阅读", "产品与思考"),
    ArticlePreview("一份适合长期维护的系统设计清单", "hlovet", "12 分钟阅读", "工程实践"),
    ArticlePreview("从草稿到发布：我的内容工作流", "maria", "6 分钟阅读", "创作方法")
)

private val chats = listOf(
    ChatPreview("项目讨论组", "今天的首页草稿已经更新了", "刚刚", "P"),
    ChatPreview("nice3", "我看到了你 @ 的消息", "12:36", "N"),
    ChatPreview("内容共创", "附件：移动端设计方向.pdf", "昨天", "C")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Night.toArgbCompat()
        window.navigationBarColor = Night.toArgbCompat()
        setContent { HlovetPreviewTheme { HlovetMobilePreview() } }
    }
}

@Composable
private fun HlovetMobilePreview() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Night,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101923), tonalElevation = 0.dp) {
                val items = listOf(
                    Triple("首页", Icons.Filled.Home, 0),
                    Triple("发现", Icons.Filled.Explore, 1),
                    Triple("写作", Icons.Filled.AddCircleOutline, 2),
                    Triple("消息", Icons.Filled.ChatBubbleOutline, 3),
                    Triple("我的", Icons.Filled.PersonOutline, 4)
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> DiscoverScreen()
                2 -> WriteScreen()
                3 -> MessagesScreen()
                else -> ProfileScreen()
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = TextMuted, fontSize = 12.sp)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun HomeScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppHeader(
                title = "HLOVET",
                subtitle = "今天，也为值得留下的内容留一点空间。",
                action = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.NotificationsNone, "通知", tint = TextPrimary) }
                }
            )
        }
        item { HomeFeature() }
        item { SectionHeading("为你推荐", "更多", Icons.Filled.ArrowForward) }
        items(articles) { article -> ArticleRow(article) }
        item { SectionHeading("正在关注", "查看订阅", Icons.Filled.ChevronRight) }
        item { FollowingStrip() }
    }
}

@Composable
private fun HomeFeature() {
    Surface(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = PanelRaised
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Accent), contentAlignment = Alignment.Center) {
                    Text("H", color = Night, fontSize = 21.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("今日灵感", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("从一篇好文章开始", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("把零散的想法整理成可继续使用的知识，也把正在发生的事好好记录下来。", color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开始探索", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Filled.ArrowForward, null, tint = Accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, action: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(action, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(3.dp))
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ArticleRow(article: ArticlePreview) {
    Surface(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable {} ,
        shape = RoundedCornerShape(16.dp),
        color = Panel
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Avatar(article.author.takeLast(2), Coral)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(article.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(7.dp))
                Text("${article.author} · ${article.readTime}", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(article.category, color = Accent, fontSize = 11.sp)
            }
            Icon(Icons.Filled.BookmarkBorder, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FollowingStrip() {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        items(listOf("nice3", "hlovet", "maria", "工程笔记")) { name ->
            Surface(shape = RoundedCornerShape(12.dp), color = AccentSoft) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.takeLast(2), Accent)
                    Spacer(Modifier.width(7.dp))
                    Text(name, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DiscoverScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { AppHeader("发现", "找到正在发生的内容") }
        item {
            Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), RoundedCornerShape(14.dp), Panel) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = TextMuted, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("搜索文章、用户、专题和合集", color = TextMuted, fontSize = 13.sp)
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("推荐", "热门", "最新", "专题", "合集")) { label ->
                    Surface(shape = RoundedCornerShape(9.dp), color = if (label == "推荐") Accent else Panel) {
                        Text(label, color = if (label == "推荐") Night else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        }
        item { SectionHeading("编辑精选", "全部", Icons.Filled.ArrowForward) }
        item { TopicRow("长期主义的工作方法", "专题 · 18 篇文章 · 236 人订阅", Accent) }
        item { TopicRow("把知识整理成系统", "合集 · 12 篇文章 · 89 人订阅", Coral) }
        item { SectionHeading("最近更新", "查看全部", Icons.Filled.ArrowForward) }
        items(articles.take(2)) { article -> ArticleRow(article) }
    }
}

@Composable
private fun TopicRow(title: String, meta: String, color: Color) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), RoundedCornerShape(16.dp), Panel) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LibraryBooks, null, tint = color, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(meta, color = TextMuted, fontSize = 11.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
        }
    }
}

@Composable
private fun WriteScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppHeader("写作", "把想法留下来")
        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), PanelRaised) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("从一个空白草稿开始", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text("原生端会支持离线写作、自动保存和 AI 辅助，不打断你的思路。", color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                Surface(Modifier.fillMaxWidth().clickable {}, RoundedCornerShape(12.dp), Accent) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AddCircleOutline, null, tint = Night, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("新建文章", color = Night, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        Text("最近草稿", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        DraftRow("移动端产品方向", "刚刚自动保存")
        DraftRow("AI 知识库实践记录", "昨天编辑")
    }
}

@Composable
private fun DraftRow(title: String, status: String) {
    Surface(Modifier.fillMaxWidth().clickable {}, RoundedCornerShape(14.dp), Panel) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LibraryBooks, null, tint = Accent, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(status, color = TextMuted, fontSize = 11.sp)
            }
            Icon(Icons.Filled.MoreHoriz, null, tint = TextMuted)
        }
    }
}

@Composable
private fun MessagesScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { AppHeader("消息", "聊天、评论和提醒", action = { IconButton(onClick = {}) { Icon(Icons.Filled.Settings, "设置", tint = TextMuted) } }) }
        item {
            Surface(Modifier.padding(horizontal = 20.dp, vertical = 2.dp).fillMaxWidth(), RoundedCornerShape(15.dp), AccentSoft) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsNone, null, tint = Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("你有 3 条未读消息", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("包括 1 条 @提醒", color = TextMuted, fontSize = 11.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
                }
            }
        }
        items(chats) { chat -> ChatRow(chat) }
    }
}

@Composable
private fun ChatRow(chat: ChatPreview) {
    Row(Modifier.fillMaxWidth().clickable {}.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(chat.initials, Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(chat.message, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(chat.time, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun ProfileScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            AppHeader("我的", "账号、内容和安全设置", action = { IconButton(onClick = {}) { Icon(Icons.Filled.MoreHoriz, "更多", tint = TextMuted) } })
        }
        item {
            Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), RoundedCornerShape(20.dp), PanelRaised) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("VT", Coral, large = true)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("你的 HLOVET", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("@hlovet · 已发布 12 篇文章", color = TextMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
                }
            }
        }
        item { ProfileSection("内容管理", listOf("我的文章", "我的合集", "我的订阅"), Icons.Filled.LibraryBooks) }
        item { ProfileSection("账号与隐私", listOf("账号凭据", "双因素认证", "通行密钥"), Icons.Filled.Settings) }
        item { ProfileSection("AI 助手", listOf("对话记录", "我的用量", "帮助与反馈"), Icons.Filled.AutoAwesome) }
    }
}

@Composable
private fun ProfileSection(title: String, rows: List<String>, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), Panel) {
            Column {
                rows.forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().clickable {}.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(11.dp))
                        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(17.dp))
                    }
                    if (index < rows.lastIndex) Spacer(Modifier.height(1.dp).fillMaxWidth().background(Night))
                }
            }
        }
    }
}

@Composable
private fun Avatar(text: String, color: Color, large: Boolean = false) {
    Box(
        modifier = Modifier.size(if (large) 54.dp else 38.dp).clip(CircleShape).background(color.copy(alpha = .2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(text.takeLast(2), color = color, fontSize = if (large) 15.sp else 11.sp, fontWeight = FontWeight.Bold)
    }
}

private data class ArticlePreview(val title: String, val author: String, val readTime: String, val category: String)
private data class ChatPreview(val name: String, val message: String, val time: String, val initials: String)

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)

@Composable
private fun HlovetPreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night,
            surface = Panel,
            primary = Accent,
            onPrimary = Night,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        ),
        content = content
    )
}
