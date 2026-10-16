@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SubscriptionAccent: Color get() = HlovetUi.accent
private val SubscriptionMuted: Color get() = HlovetUi.muted
private val SubscriptionCoral: Color get() = HlovetUi.secondaryAccent

private data class NativeSubscriptionScreenLoad(
    val feed: NativeSubscriptionFeed,
    val settings: NativeSubscriptionSettings,
    val contentSettings: NativeContentSubscriptionSettings,
    val emailSettings: NativeSubscriptionEmailSettings,
)

@Composable
internal fun SubscriptionFeedScreen(
    session: NativeSession?,
    initialSort: String = "latest",
    onBack: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
) {
    val token = session?.accessToken
    var sort by remember(token, initialSort) { mutableStateOf(initialSort) }
    var page by remember(token) { mutableIntStateOf(1) }
    var feed by remember(token) { mutableStateOf<NativeSubscriptionFeed?>(null) }
    var settings by remember(token) { mutableStateOf<NativeSubscriptionSettings?>(null) }
    var contentSettings by remember(token) { mutableStateOf<NativeContentSubscriptionSettings?>(null) }
    var emailSettings by remember(token) { mutableStateOf<NativeSubscriptionEmailSettings?>(null) }
    var settingsOpen by remember(token) { mutableStateOf(false) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(showLoading: Boolean = true) {
        val currentToken = token ?: run {
            loading = false
            error = nativePhrase("请先登录后查看订阅动态。", "Sign in to view subscription updates.")
            return
        }
        scope.launch {
            if (showLoading) loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSubscriptionScreenLoad(
                        feed = NativeDiscoveryApi.listSubscriptionFeed(currentToken, page, 12, sort),
                        settings = NativeDiscoveryApi.getSubscriptionSettings(currentToken),
                        contentSettings = NativeDiscoveryApi.getContentSubscriptionSettings(currentToken),
                        emailSettings = NativeDiscoveryApi.getSubscriptionEmailSettings(currentToken),
                    )
                }
            }.onSuccess { next ->
                feed = next.feed
                settings = next.settings
                contentSettings = next.contentSettings
                emailSettings = next.emailSettings
            }.onFailure { error = it.message ?: nativePhrase("订阅动态加载失败，请稍后重试。", "Subscription updates could not be loaded. Try again later.") }
            loading = false
        }
    }

    LaunchedEffect(token, page, sort) { load() }

    fun markRead(item: NativeSubscriptionFeedItem) {
        val currentToken = token ?: return
        if (item.readAt != null) {
            onOpenArticle(item.article)
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeDiscoveryApi.markSubscriptionRead(currentToken, item.article.id) } }
                .onSuccess { readAt ->
                    feed = feed?.copy(
                        unread = (feed?.unread ?: 0).coerceAtLeast(1) - 1,
                        items = feed?.items.orEmpty().map { if (it.article.id == item.article.id) it.copy(readAt = readAt) else it },
                    )
                }
            onOpenArticle(item.article)
        }
    }

    fun markAllRead() {
        val currentToken = token ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeDiscoveryApi.markAllSubscriptionsRead(currentToken) } }
                .onSuccess { readAt ->
                    feed = feed?.copy(unread = 0, items = feed?.items.orEmpty().map { it.copy(readAt = it.readAt ?: readAt) })
                    notice = nativePhrase("订阅动态已全部标记为已读。", "All subscription updates are marked as read.")
                }
                .onFailure { error = it.message ?: nativePhrase("全部已读操作失败。", "Could not mark all updates as read.") }
        }
    }

    fun updateSettings(action: suspend (String) -> Unit, success: String) {
        val currentToken = token ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action(currentToken) } }
                .onSuccess { notice = success; load(false) }
                .onFailure { error = it.message ?: nativePhrase("订阅设置更新失败。", "Subscription settings could not be updated.") }
        }
    }

    fun updateEmailDigest(enabled: Boolean) {
        val currentToken = token ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.updateSubscriptionEmailSettings(currentToken, enabled)
                }
            }.onSuccess { next ->
                emailSettings = next
                notice = if (enabled) nativePhrase("已开启邮件订阅日报。", "Email subscription digest enabled.") else nativePhrase("已关闭邮件订阅日报。", "Email subscription digest disabled.")
            }.onFailure { error = it.message ?: nativePhrase("邮件日报设置更新失败。", "Email digest settings could not be updated.") }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("我的订阅", "My subscriptions"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("关注的作者、专题和标签动态", "Updates from followed authors, topics, and tags"), color = SubscriptionMuted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Filled.Tune, contentDescription = nativePhrase("订阅管理", "Subscription settings"), tint = SubscriptionAccent) }
                    IconButton(onClick = { page = 1; load() }) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = SubscriptionAccent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf("latest" to nativePhrase("最新", "Latest"), "unread" to nativePhrase("未读 ${feed?.unread ?: 0}", "Unread ${feed?.unread ?: 0}"), "popular" to nativePhrase("热度", "Popular")).forEach { (value, label) ->
                    FilterChip(
                        selected = sort == value,
                        onClick = { sort = value; page = 1 },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.padding(end = 7.dp),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SubscriptionAccent.copy(alpha = .18f)),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = ::markAllRead, enabled = (feed?.unread ?: 0) > 0) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(nativePhrase("全部已读", "Mark all read"), fontSize = 11.sp)
                }
            }
        }
        if (loading) {
            item { subscriptionEmpty(nativePhrase("正在读取订阅动态…", "Loading subscription updates…")) }
        } else if (!error.isNullOrBlank() && feed == null) {
            item { subscriptionEmpty(error!!) }
        } else if (feed?.items.isNullOrEmpty()) {
            item { subscriptionEmpty(nativePhrase("还没有订阅动态。订阅作者、专题或标签后，新内容会显示在这里。", "No subscription updates yet. New content from followed authors, topics, and tags will appear here.")) }
        } else {
            if (!error.isNullOrBlank()) item { Text(error!!, color = SubscriptionCoral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            if (!notice.isNullOrBlank()) item { Text(notice!!, color = SubscriptionAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(feed!!.items, key = { it.article.id }) { item ->
                SubscriptionArticleRow(item = item, onClick = { markRead(item) })
            }
            feed?.let { pageData ->
                if (pageData.totalPages > 1) {
                    item {
                        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page -= 1 }, enabled = page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                            Text("${pageData.page} / ${pageData.totalPages}", color = SubscriptionMuted, fontSize = 11.sp)
                        TextButton(onClick = { page += 1 }, enabled = page < pageData.totalPages) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        SubscriptionSettingsDialog(
            settings = settings,
            contentSettings = contentSettings,
            emailSettings = emailSettings,
            onDismiss = { settingsOpen = false },
            onToggleDigest = { enabled -> updateSettings({ NativeDiscoveryApi.updateSubscriptionDigest(it, enabled) }, if (enabled) nativePhrase("已开启订阅日报。", "Subscription digest enabled.") else nativePhrase("已关闭订阅日报。", "Subscription digest disabled.")) },
            onToggleEmailDigest = ::updateEmailDigest,
            onToggleAuthor = { author, enabled -> updateSettings({ NativeDiscoveryApi.updateAuthorSubscription(it, author.authorId, notifyNewArticles = enabled) }, nativePhrase("作者推送设置已更新。", "Author notification settings updated.")) },
            onAuthorFrequency = { author, frequency -> updateSettings({ NativeDiscoveryApi.updateAuthorSubscription(it, author.authorId, frequency = frequency) }, nativePhrase("作者推送频率已更新。", "Author notification frequency updated.")) },
            onTopicFrequency = { topic, frequency -> updateSettings({ NativeDiscoveryApi.updateTopicSubscriptionFrequency(it, topic.id, frequency) }, nativePhrase("专题推送频率已更新。", "Topic notification frequency updated.")) },
            onTagFrequency = { tag, frequency -> updateSettings({ NativeDiscoveryApi.updateTagSubscriptionFrequency(it, tag.tag, frequency) }, nativePhrase("标签推送频率已更新。", "Tag notification frequency updated.")) },
            onRemoveAuthor = { author -> updateSettings({ NativeDiscoveryApi.unsubscribeAuthor(it, author.authorId) }, nativePhrase("已取消作者订阅。", "Author subscription removed.")) },
            onRemoveTopic = { topic -> updateSettings({ NativeDiscoveryApi.unsubscribeTopic(it, topic.id) }, nativePhrase("已取消专题订阅。", "Topic subscription removed.")) },
            onRemoveTag = { tag -> updateSettings({ NativeDiscoveryApi.unsubscribeTag(it, tag.tag) }, nativePhrase("已取消标签订阅。", "Tag subscription removed.")) },
            onRemoveCollection = { collection -> updateSettings({ NativeDiscoveryApi.unsubscribeCollection(it, collection.id) }, nativePhrase("已取消合集订阅。", "Collection subscription removed.")) },
        )
    }
}

@Composable
private fun SubscriptionArticleRow(item: NativeSubscriptionFeedItem, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = HlovetUi.cardShape,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = SubscriptionAccent.copy(alpha = .14f),
                modifier = Modifier.size(28.dp),
            ) {
                Text(item.article.authorName.takeLast(2), color = SubscriptionAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.article.title, fontSize = 14.sp, fontWeight = if (item.readAt == null) FontWeight.Bold else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.article.authorName} · ${item.article.category.ifBlank { nativePhrase("未分类", "Uncategorized") }}", color = SubscriptionMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.readAt == null) Text(nativePhrase("未读", "Unread"), color = SubscriptionAccent, fontSize = 10.sp)
            }
            Text(nativePhrase("打开", "Open"), color = SubscriptionMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SubscriptionSettingsDialog(
    settings: NativeSubscriptionSettings?,
    contentSettings: NativeContentSubscriptionSettings?,
    emailSettings: NativeSubscriptionEmailSettings?,
    onDismiss: () -> Unit,
    onToggleDigest: (Boolean) -> Unit,
    onToggleEmailDigest: (Boolean) -> Unit,
    onToggleAuthor: (NativeAuthorSubscriptionSetting, Boolean) -> Unit,
    onAuthorFrequency: (NativeAuthorSubscriptionSetting, String) -> Unit,
    onTopicFrequency: (NativeTopicSubscriptionSetting, String) -> Unit,
    onTagFrequency: (NativeTagSubscriptionSetting, String) -> Unit,
    onRemoveAuthor: (NativeAuthorSubscriptionSetting) -> Unit,
    onRemoveTopic: (NativeTopicSubscriptionSetting) -> Unit,
    onRemoveTag: (NativeTagSubscriptionSetting) -> Unit,
    onRemoveCollection: (NativeCollectionSubscriptionSetting) -> Unit,
) {
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = SubscriptionAccent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text(nativePhrase("订阅管理", "Subscription settings"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                emailSettings?.let { current ->
                    SubscriptionSettingHeading(nativePhrase("邮件日报", "Email digest"), Icons.Filled.Mail)
                    val detail = when {
                        !current.available -> nativePhrase("邮件服务尚未启用", "Email delivery is not enabled")
                        current.enabled -> nativePhrase("每天发送当前可见的新订阅内容", "Send visible subscription updates every day")
                        else -> nativePhrase("已暂停邮件日报", "Email digest is paused")
                    }
                    FrequencyToggleRow(
                        nativePhrase("每日邮件汇总", "Daily email digest"),
                        detail,
                        current.enabled,
                        clickable = current.available,
                        onClick = { onToggleEmailDigest(!current.enabled) },
                    )
                    current.deliveries.firstOrNull()?.let { delivery ->
                        Text(
                            nativePhrase("最近投递：${emailDeliveryStatusLabel(delivery.status)}${if (delivery.itemCount > 0) " · ${delivery.itemCount} 条" else ""}", "Latest delivery: ${emailDeliveryStatusLabel(delivery.status)}${if (delivery.itemCount > 0) " · ${delivery.itemCount} items" else ""}"),
                            color = SubscriptionMuted,
                            fontSize = 10.sp,
                        )
                    }
                    HorizontalDivider(color = HlovetUi.divider)
                }
                settings?.let { current ->
                    SubscriptionSettingHeading(nativePhrase("订阅日报", "Subscription digest"), Icons.Filled.NotificationsActive)
                    FrequencyToggleRow(nativePhrase("每日汇总提醒", "Daily digest reminder"), if (current.digestEnabled) nativePhrase("已开启", "Enabled") else nativePhrase("已关闭", "Disabled"), current.digestEnabled, onClick = { onToggleDigest(!current.digestEnabled) })
                    HorizontalDivider(color = HlovetUi.divider)
                    SubscriptionSettingHeading(nativePhrase("作者推送", "Author notifications"), Icons.Filled.NotificationsActive)
                    if (current.items.isEmpty()) Text(nativePhrase("还没有订阅作者。", "No authors subscribed yet."), color = SubscriptionMuted, fontSize = 12.sp)
                    current.items.forEach { author ->
                        SubscriptionAuthorRow(author, onToggle = { onToggleAuthor(author, !author.notifyNewArticles) }, onFrequency = { onAuthorFrequency(author, it) }, onRemove = { onRemoveAuthor(author) })
                    }
                }
                contentSettings?.let { current ->
                    HorizontalDivider(color = HlovetUi.divider)
                    SubscriptionSettingHeading(nativePhrase("专题", "Topics"), Icons.Filled.Article)
                    if (current.topics.isEmpty()) Text(nativePhrase("还没有订阅专题。", "No topics subscribed yet."), color = SubscriptionMuted, fontSize = 12.sp)
                    current.topics.forEach { topic ->
                        SubscriptionContentRow(topic.title, nativePhrase("${topic.articleCount} 篇 · ${topic.subscriberCount} 人订阅", "${topic.articleCount} articles · ${topic.subscriberCount} subscribers"), topic.frequency, onFrequency = { onTopicFrequency(topic, it) }, onRemove = { onRemoveTopic(topic) })
                    }
                    HorizontalDivider(color = HlovetUi.divider)
                    SubscriptionSettingHeading(nativePhrase("标签", "Tags"), Icons.Filled.Tag)
                    if (current.tags.isEmpty()) Text(nativePhrase("还没有订阅标签。", "No tags subscribed yet."), color = SubscriptionMuted, fontSize = 12.sp)
                    current.tags.forEach { tag ->
                        SubscriptionContentRow("#${tag.tag}", "", tag.frequency, onFrequency = { onTagFrequency(tag, it) }, onRemove = { onRemoveTag(tag) })
                    }
                    HorizontalDivider(color = HlovetUi.divider)
                    SubscriptionSettingHeading(nativePhrase("合集", "Collections"), Icons.Filled.FolderOpen)
                    if (current.collections.isEmpty()) Text(nativePhrase("还没有订阅合集。", "No collections subscribed yet."), color = SubscriptionMuted, fontSize = 12.sp)
                    current.collections.forEach { collection ->
                        SubscriptionContentRow(collection.name, nativePhrase("${collection.ownerName} · ${collection.articleCount} 篇", "${collection.ownerName} · ${collection.articleCount} articles"), null, onFrequency = null, onRemove = { onRemoveCollection(collection) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(nativePhrase("完成", "Done")) } },
    )
}

@Composable
private fun SubscriptionSettingHeading(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = SubscriptionAccent, modifier = Modifier.size(16.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun FrequencyToggleRow(
    label: String,
    detail: String,
    enabled: Boolean,
    clickable: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp)
            Text(detail, color = SubscriptionMuted, fontSize = 10.sp)
        }
        IconButton(onClick = onClick, enabled = clickable, modifier = Modifier.size(34.dp)) {
            Icon(if (enabled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone, contentDescription = nativePhrase("切换", "Toggle"), tint = if (enabled) SubscriptionAccent else SubscriptionMuted)
        }
    }
}

private fun emailDeliveryStatusLabel(status: String): String = when (status) {
    "sent" -> nativePhrase("成功", "Sent")
    "failed" -> nativePhrase("失败", "Failed")
    "sending" -> nativePhrase("处理中", "Sending")
    "pending" -> nativePhrase("等待发送", "Pending")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

@Composable
private fun SubscriptionAuthorRow(
    author: NativeAuthorSubscriptionSetting,
    onToggle: () -> Unit,
    onFrequency: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(author.nickname, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("@${author.username}", color = SubscriptionMuted, fontSize = 10.sp)
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) { Icon(if (author.notifyNewArticles) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone, contentDescription = nativePhrase("推送", "Notifications"), tint = if (author.notifyNewArticles) SubscriptionAccent else SubscriptionMuted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消订阅", "Unsubscribe"), tint = SubscriptionCoral, modifier = Modifier.size(17.dp)) }
        }
        FrequencyChips(author.frequency, onFrequency)
    }
}

@Composable
private fun SubscriptionContentRow(
    label: String,
    detail: String,
    frequency: String?,
    onFrequency: ((String) -> Unit)?,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Text(detail, color = SubscriptionMuted, fontSize = 10.sp)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消订阅", "Unsubscribe"), tint = SubscriptionCoral, modifier = Modifier.size(17.dp)) }
        }
        if (frequency != null && onFrequency != null) FrequencyChips(frequency, onFrequency)
    }
}

@Composable
private fun FrequencyChips(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("instant" to nativePhrase("即时", "Instant"), "daily" to nativePhrase("日报", "Daily"), "muted" to nativePhrase("静音", "Muted")).forEach { (value, label) ->
            FilterChip(selected = current == value, onClick = { onChange(value) }, label = { Text(label, fontSize = 10.sp) }, border = null)
        }
    }
}

@Composable
private fun subscriptionEmpty(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = SubscriptionMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}
