@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val Background = HlovetUi.background
private val SurfaceLow = HlovetUi.surfaceLow
private val SurfaceAccent = HlovetUi.surfaceAccent
private val TextPrimary = HlovetUi.foreground
private val TextMuted = HlovetUi.muted
private val Accent = HlovetUi.accent
private val Coral = HlovetUi.secondaryAccent

private sealed interface DetailTarget {
    data class Article(val article: ArticlePreview) : DetailTarget
    data class Topic(val title: String, val meta: String, val color: Color) : DetailTarget
}

/** Keeps the existing page code on one shared glass surface implementation. */
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = HlovetUi.cardShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier, shape = shape, content = content)
}

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
        window.statusBarColor = Background.toArgbCompat()
        window.navigationBarColor = Background.toArgbCompat()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        setContent { HlovetTheme { HlovetMobilePreview() } }
    }
}

@Composable
private fun HlovetMobilePreview() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var detailTarget by remember { mutableStateOf<DetailTarget?>(null) }
    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHlovetHaze provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.hlovet_city_lights),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState)
            )
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .34f)))
            if (detailTarget != null) {
                DetailScreen(target = detailTarget!!, onBack = { detailTarget = null })
            } else Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.hazeEffect(state = hazeState, style = NavigationGlassStyle),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
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
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    indicatorColor = HlovetUi.accentSoft,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                when (selectedTab) {
                    0 -> HomeScreen(padding, onArticleClick = { detailTarget = DetailTarget.Article(it) })
                    1 -> DiscoverScreen(
                        padding,
                        onTopicClick = { title, meta, color -> detailTarget = DetailTarget.Topic(title, meta, color) },
                        onArticleClick = { detailTarget = DetailTarget.Article(it) }
                    )
                    2 -> WriteScreen(padding)
                    3 -> MessagesScreen(padding)
                    else -> ProfileScreen(padding)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
private fun DetailScreen(target: DetailTarget, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        text = when (target) {
                            is DetailTarget.Article -> "文章详情"
                            is DetailTarget.Topic -> "专题与合集"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
        when (target) {
            is DetailTarget.Article -> {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(target.article.author.takeLast(2), Coral)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(target.article.author, fontWeight = FontWeight.SemiBold)
                                    Text(target.article.readTime, color = TextMuted, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(target.article.title, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(target.article.category, fontSize = 12.sp) },
                                border = null,
                                colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.accentSoft)
                            )
                        }
                    }
                }
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("把复杂的事情拆开，先看清路径，再决定速度。好的内容不急着给答案，而是帮助人把问题放到正确的位置。", fontSize = 16.sp, lineHeight = 27.sp)
                            Text("当一个想法可以被复述、被验证，也能在下一次遇到类似问题时继续使用，它才真正从记录变成了内容。", color = TextMuted, fontSize = 15.sp, lineHeight = 25.sp)
                            Text("这是一段原生端详情页预览，后续接入真实文章接口后，正文、图片、附件和评论都会沿用同一套玻璃内容容器。", color = TextMuted, fontSize = 15.sp, lineHeight = 25.sp)
                        }
                    }
                }
            }
            is DetailTarget.Topic -> {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Surface(shape = HlovetUi.rowShape, color = target.color.copy(alpha = .15f)) {
                                Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = target.color, modifier = Modifier.padding(12.dp).size(26.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(target.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(target.meta, color = TextMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("围绕一个主题持续整理，让每一篇文章都能成为下一篇文章的入口。", color = TextMuted, fontSize = 14.sp, lineHeight = 23.sp)
                        }
                    }
                }
                item { SectionHeading("收录文章", "查看全部") }
                item { ArticleGroup(articles) }
            }
        }
    }
}

@Composable
private fun HomeScreen(scaffoldPadding: PaddingValues, onArticleClick: (ArticlePreview) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppHeader(
                title = "HLOVET",
                subtitle = "今天，也为值得留下的内容留一点空间。",
                action = { IconButton(onClick = {}) { Icon(Icons.Filled.NotificationsNone, "通知") } }
            )
        }
        item { FeaturedEntry() }
        item { SectionHeading("为你推荐", "更多") }
        item { ArticleGroup(articles, onArticleClick = onArticleClick) }
        item { SectionHeading("正在关注", "查看订阅") }
        item { FollowingStrip() }
    }
}

@Composable
private fun FeaturedEntry() {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text("从一篇好文章开始", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            supportingContent = { Text("把想法整理成可以继续使用的知识。", color = TextMuted, fontSize = 12.sp) },
            leadingContent = { Avatar("H", Accent, large = true) },
            trailingContent = { Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Accent) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {}
        )
    }
}

@Composable
private fun SectionHeading(title: String, action: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(action, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ArticleGroup(
    displayArticles: List<ArticlePreview>,
    onArticleClick: (ArticlePreview) -> Unit = {}
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            displayArticles.forEachIndexed { index, article ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArticleClick(article) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(article.author.takeLast(2), Coral)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(article.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(article.author, color = TextMuted, fontSize = 11.sp)
                            Text(" · ", color = TextMuted, fontSize = 11.sp)
                            Text(article.readTime, color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.width(7.dp))
                            Text(article.category, color = Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Filled.BookmarkBorder, contentDescription = null, tint = TextMuted)
                }
                if (index < displayArticles.lastIndex) {
                    HorizontalDivider(color = HlovetUi.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun FollowingStrip() {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf("nice3", "hlovet", "maria", "工程笔记")) { name ->
            AssistChip(
                onClick = {},
                label = { Text(name, fontSize = 12.sp) },
                leadingIcon = { Avatar(name.takeLast(2), Accent, compact = true) },
                colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.surface),
                border = null
            )
        }
    }
}

@Composable
private fun DiscoverScreen(
    scaffoldPadding: PaddingValues,
    onTopicClick: (String, String, Color) -> Unit = { _, _, _ -> },
    onArticleClick: (ArticlePreview) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("发现", "找到正在发生的内容") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceLow)
            ) {
                ListItem(
                    headlineContent = { Text("搜索文章、用户、专题和合集", color = TextMuted, fontSize = 13.sp) },
                    leadingContent = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("推荐", "热门", "最新", "专题", "合集")) { label ->
                    FilterChip(
                        selected = label == "推荐",
                        onClick = {},
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = HlovetUi.onAccent,
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = null
                    )
                }
            }
        }
        item { SectionHeading("编辑精选", "全部") }
        item { TopicRow("长期主义的工作方法", "专题 · 18 篇文章 · 236 人订阅", Accent, onClick = onTopicClick) }
        item { TopicRow("把知识整理成系统", "合集 · 12 篇文章 · 89 人订阅", Coral, onClick = onTopicClick) }
        item { SectionHeading("最近更新", "查看全部") }
        item { ArticleGroup(articles.take(2), onArticleClick = onArticleClick) }
    }
}

@Composable
private fun TopicRow(
    title: String,
    meta: String,
    color: Color,
    onClick: (String, String, Color) -> Unit = { _, _, _ -> }
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onClick(title, meta, color) },
        shape = HlovetUi.rowShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(meta, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Surface(shape = HlovetUi.rowShape, color = color.copy(alpha = .14f)) {
                    Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = color, modifier = Modifier.padding(9.dp).size(20.dp))
                }
            },
            trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun WriteScreen(scaffoldPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("写作", "把想法留下来") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("从一个空白草稿开始", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text("离线保存、自动保存和 AI 辅助都在这里继续。", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.AddCircleOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("新建文章", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Text("最近草稿", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
        item { DraftGroup() }
    }
}

@Composable
private fun DraftGroup() {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        val drafts = listOf("移动端产品方向" to "刚刚自动保存", "AI 知识库实践记录" to "昨天编辑")
        Column {
            drafts.forEachIndexed { index, draft ->
                ListItem(
                    headlineContent = { Text(draft.first, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(draft.second, color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = Accent) },
                    trailingContent = { Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
                if (index < drafts.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun MessagesScreen(scaffoldPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("消息", "聊天、评论和提醒", action = { IconButton(onClick = {}) { Icon(Icons.Filled.Settings, "设置") } }) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                ListItem(
                    headlineContent = { Text("你有 3 条未读消息", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
                    supportingContent = { Text("包括 1 条 @提醒", color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = Accent) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
            }
        }
        item { ChatGroup() }
    }
}

@Composable
private fun ChatGroup() {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            chats.forEachIndexed { index, chat ->
                ListItem(
                    headlineContent = { Text(chat.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(chat.message, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(chat.initials, Accent) },
                    trailingContent = { Text(chat.time, color = TextMuted, fontSize = 10.sp) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
                if (index < chats.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun ProfileScreen(scaffoldPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("我的", "账号、内容和安全设置", action = { IconButton(onClick = {}) { Icon(Icons.Filled.MoreHoriz, "更多") } }) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent)
            ) {
                ListItem(
                    headlineContent = { Text("你的 HLOVET", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    supportingContent = { Text("@hlovet · 已发布 12 篇文章", color = TextMuted, fontSize = 11.sp) },
                    leadingContent = { Avatar("VT", Coral, large = true) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {}
                )
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
        Text(title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = HlovetUi.cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                rows.forEachIndexed { index, label ->
                    ListItem(
                        headlineContent = { Text(label, fontSize = 13.sp) },
                        leadingContent = { Icon(icon, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {}
                    )
                    if (index < rows.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun Avatar(text: String, color: Color, large: Boolean = false, compact: Boolean = false) {
    val size = when {
        large -> 54.dp
        compact -> 24.dp
        else -> 40.dp
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .14f), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text.takeLast(2), color = color, fontSize = if (large) 15.sp else 11.sp, fontWeight = FontWeight.Bold)
        }
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
