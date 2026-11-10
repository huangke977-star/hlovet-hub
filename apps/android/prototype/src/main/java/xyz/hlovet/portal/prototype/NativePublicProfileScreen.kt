@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 判断公开主页请求是否仍属于当前用户名和登录会话，防止迟到回包污染新页面。 */
internal fun isCurrentPublicProfileRequest(
    requestId: Int,
    activeRequestId: Int,
    requestUsername: String,
    activeUsername: String,
    requestAccessToken: String?,
    activeAccessToken: String?,
): Boolean = requestId == activeRequestId && requestUsername == activeUsername && requestAccessToken == activeAccessToken

@Composable
internal fun NativePublicProfileScreen(
    username: String,
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
    onOpenCollection: (RemoteCollection) -> Unit,
    onOpenConversation: (NativeConversationPreview) -> Unit,
    onOpenExternalFeed: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    var profile by remember(username, session?.accessToken) { mutableStateOf<NativePublicProfile?>(null) }
    var showcase by remember(username, session?.accessToken) { mutableStateOf<NativeProfileShowcase?>(null) }
    var articles by remember(username, session?.accessToken) { mutableStateOf<NativeArticleList?>(null) }
    var loading by remember(username, session?.accessToken) { mutableStateOf(true) }
    var loadingMore by remember(username, session?.accessToken) { mutableStateOf(false) }
    var error by remember(username, session?.accessToken) { mutableStateOf<String?>(null) }
    var articleRequestId by remember { mutableIntStateOf(0) }
    var acting by remember(username, session?.accessToken) { mutableStateOf(false) }
    var friendDialog by remember { mutableStateOf(false) }
    var messageDialog by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    var friendNote by remember { mutableStateOf("") }
    var messageBody by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(username, session?.accessToken) {
        // 记录本次请求的会话来源，避免匿名请求或旧令牌的迟到响应覆盖已登录关系状态。
        val requestId = articleRequestId + 1
        articleRequestId = requestId
        val requestUsername = username
        val requestAccessToken = session?.accessToken
        loading = true
        error = null
        try {
            // 同时读取主页、代表内容和公开文章，三个结果必须来自同一查看身份。
            val loaded = withContext(Dispatchers.IO) {
                var loadedProfile = NativeProfileApi.loadPublic(requestAccessToken, username)
                // 已登录但既不能发起好友申请、又没有关系记录时，复核一次避免会话切换瞬间的旧响应覆盖关系状态。
                if (!requestAccessToken.isNullOrBlank() && !loadedProfile.isSelf && loadedProfile.relationship == null && !loadedProfile.canRequestFriend) {
                    loadedProfile = NativeProfileApi.loadPublic(requestAccessToken, username)
                }
                val loadedShowcase = NativeProfileApi.loadShowcase(requestAccessToken, username)
                val loadedArticles = NativeArticleApi.listByAuthor(requestAccessToken, username)
                Triple(loadedProfile, loadedShowcase, loadedArticles)
            }
            // 网络请求可能在取消后才返回，先确认协程仍有效，再提交页面状态。
            currentCoroutineContext().ensureActive()
            if (!isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken)) return@LaunchedEffect
            profile = loaded.first
            showcase = loaded.second
            articles = loaded.third
        } catch (cancelled: CancellationException) {
            // 会话或目标用户变化时保留新请求接管页面，不能把取消当成错误提示。
            throw cancelled
        } catch (failure: Exception) {
            // 只有当前会话仍然有效时才展示本次请求的错误，避免旧请求污染新页面。
            if (isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken)) {
                error = failure.message ?: nativePhrase("用户主页暂时无法查看。", "This profile is temporarily unavailable.")
            }
        } finally {
            // 加载状态同样按当前会话归属更新，旧请求结束不能提前关闭新请求的加载提示。
            if (isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken)) loading = false
        }
    }

    fun requireToken(): String? {
        val token = session?.accessToken
        if (token.isNullOrBlank()) onLogin()
        return token
    }

    fun runAction(action: suspend (String) -> Unit) {
        val token = requireToken() ?: return
        if (acting) return
        scope.launch {
            acting = true
            error = null
            runCatching { action(token) }
                .onFailure { error = it.message ?: nativePhrase("操作失败，请稍后重试。", "The operation failed. Please try again.") }
            acting = false
        }
    }

    fun toggleSubscription() {
        val current = profile ?: return
        runAction { token ->
            val result = withContext(Dispatchers.IO) {
                NativeFriendsApi.setAuthorSubscription(token, current.id, !current.subscribed)
            }
            profile = current.copy(subscribed = result.subscribed, subscriberCount = result.subscriberCount)
            onNotice(if (result.subscribed) nativePhrase("已订阅 @${current.username}。", "Subscribed to @${current.username}.") else nativePhrase("已取消订阅。", "Subscription cancelled."))
        }
    }

    fun sendFriendRequest() {
        val current = profile ?: return
        runAction { token ->
            withContext(Dispatchers.IO) { NativeFriendsApi.requestFriend(token, current.id, friendNote.trim().ifBlank { null }) }
            profile = current.copy(
                relationship = NativeProfileRelationship(0, "pending", "outgoing", friendNote.trim().ifBlank { null }),
                canRequestFriend = false,
            )
            friendNote = ""
            friendDialog = false
            onNotice(nativePhrase("好友申请已发送。", "Friend request sent."))
        }
    }

    fun respondFriend(status: String) {
        val current = profile ?: return
        val relationship = current.relationship ?: return
        runAction { token ->
            withContext(Dispatchers.IO) { NativeFriendsApi.respondFriendRequest(token, relationship.id, status) }
            profile = current.copy(
                relationship = if (status == "accepted") relationship.copy(status = "accepted", direction = "accepted") else null,
                canRequestFriend = false,
            )
            onNotice(if (status == "accepted") nativePhrase("已接受好友申请。", "Friend request accepted.") else nativePhrase("已拒绝好友申请。", "Friend request declined."))
        }
    }

    fun sendMessageRequest() {
        val current = profile ?: return
        val body = messageBody.trim()
        if (body.isBlank()) {
            error = nativePhrase("请先输入消息内容。", "Enter a message first.")
            return
        }
        runAction { token ->
            withContext(Dispatchers.IO) { NativeFriendsApi.createStrangerRequest(token, current.id, body) }
            messageBody = ""
            messageDialog = false
            onNotice(nativePhrase("消息请求已发送。", "Message request sent."))
        }
    }

    fun openConversation() {
        val current = profile ?: return
        runAction { token ->
            val conversation = withContext(Dispatchers.IO) { NativeFriendsApi.getOrCreateConversation(token, current.id) }
            onOpenConversation(conversation)
        }
    }

    fun blockUser() {
        val current = profile ?: return
        runAction { token ->
            withContext(Dispatchers.IO) { NativeFriendsApi.blockUser(token, current.id) }
            blockDialog = false
            onNotice(nativePhrase("已加入黑名单。", "User blocked."))
            onBack()
        }
    }

    fun loadMore() {
        val current = articles ?: return
        if (loadingMore || current.page >= current.totalPages) return
        loadingMore = true
        val nextPage = current.page + 1
        val requestId = articleRequestId
        val requestUsername = username
        val requestAccessToken = session?.accessToken
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.listByAuthor(requestAccessToken, requestUsername, nextPage)
                }
            }.onSuccess { next ->
                // 只有同一主页、同一会话且当前页仍未变化时，才允许追加分页结果。
                val activeArticles = articles
                if (isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken) && activeArticles?.page == current.page) {
                    articles = next.copy(items = (activeArticles.items + next.items).distinctBy { it.id })
                }
            }.onFailure { failure ->
                // 旧用户的网络错误不能覆盖新主页的内容或错误提示。
                if (isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken)) {
                    error = failure.message ?: nativePhrase("更多文章加载失败。", "Could not load more articles.")
                }
            }
            // 旧分页结束时不能提前关闭新用户的加载状态。
            if (isCurrentPublicProfileRequest(requestId, articleRequestId, requestUsername, username, requestAccessToken, session?.accessToken)) loadingMore = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("个人主页", "Profile"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, nativePhrase("返回", "Back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        when {
            loading -> item { PublicProfileMessage(nativePhrase("正在读取个人资料…", "Loading profile...")) }
            error != null && profile == null -> item { PublicProfileMessage(error!!, isError = true) }
            profile != null -> {
                val current = profile!!
                item {
                    PublicProfileHeader(
                        profile = current,
                        visitCount = showcase?.visitCount,
                        acting = acting,
                        onSubscribe = ::toggleSubscription,
                        onFriend = { friendDialog = true },
                        onAcceptFriend = { respondFriend("accepted") },
                        onDeclineFriend = { respondFriend("declined") },
                        onMessage = ::openConversation,
                        onMessageRequest = { messageDialog = true },
                        onBlock = { blockDialog = true },
                        onOpenExternalFeed = { onOpenExternalFeed(current.username) },
                    )
                }
                if (error != null) item { PublicProfileMessage(error!!, isError = true) }
                val featured = showcase
                if (featured != null && featured.settings.showPinnedContent && (featured.pinnedArticle != null || featured.pinnedCollection != null)) {
                    item {
                        FeaturedContentCard(
                            article = featured.pinnedArticle,
                            collection = featured.pinnedCollection,
                            onOpenArticle = onOpenArticle,
                            onOpenCollection = onOpenCollection,
                        )
                    }
                }
                val collections = featured?.collections.orEmpty()
                if (collections.isNotEmpty()) {
                    item { ProfileSectionTitle(nativePhrase("文章合集", "Collections"), collections.size) }
                    items(collections, key = { "collection-${it.id}" }) { collection ->
                        ProfileCollectionRow(collection, onClick = { onOpenCollection(collection) })
                    }
                }
                val articlePage = articles
                item { ProfileSectionTitle(nativePhrase("公开文章", "Public articles"), articlePage?.total ?: 0) }
                if (articlePage?.items.isNullOrEmpty()) {
                    item { PublicProfileMessage(nativePhrase("暂时没有可见的公开文章。", "No public articles are visible.")) }
                } else {
                    items(articlePage!!.items, key = { "article-${it.id}" }) { article ->
                        PublicArticleRow(article, onClick = { onOpenArticle(article) })
                    }
                    if (articlePage.page < articlePage.totalPages) {
                        item {
                            Button(
                                onClick = ::loadMore,
                                enabled = !loadingMore,
                                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = HlovetUi.accent),
                            ) { Text(if (loadingMore) nativePhrase("正在加载…", "Loading...") else nativePhrase("加载更多文章", "Load more articles")) }
                        }
                    }
                }
            }
        }
    }

    if (friendDialog) {
        ProfileInputDialog(
            title = nativePhrase("添加 ${profile?.nickname.orEmpty()} 为好友", "Add ${profile?.nickname.orEmpty()} as a friend"),
            label = nativePhrase("申请备注", "Request note"),
            value = friendNote,
            placeholder = nativePhrase("简单介绍一下自己，可不填", "Briefly introduce yourself (optional)"),
            submitLabel = nativePhrase("发送申请", "Send request"),
            enabled = !acting,
            onValueChange = { friendNote = it.take(120) },
            onDismiss = { friendDialog = false; friendNote = "" },
            onSubmit = ::sendFriendRequest,
        )
    }
    if (messageDialog) {
        ProfileInputDialog(
            title = nativePhrase("发送消息请求", "Send message request"),
            label = nativePhrase("首条消息", "First message"),
            value = messageBody,
            placeholder = nativePhrase("说明来意，对方接受后会成为第一条聊天消息", "Explain why you are reaching out; it becomes the first chat message after acceptance"),
            submitLabel = nativePhrase("发送消息请求", "Send message request"),
            enabled = !acting,
            required = true,
            onValueChange = { messageBody = it.take(500) },
            onDismiss = { messageDialog = false; messageBody = "" },
            onSubmit = ::sendMessageRequest,
        )
    }
    if (blockDialog) {
        ProfileConfirmDialog(
            title = nativePhrase("加入黑名单", "Block user"),
            message = nativePhrase("双方将不能查看主页、添加好友、私信、订阅或互相发送群聊邀请。", "You will no longer be able to view each other's profiles, add friends, message, subscribe, or send group invitations."),
            confirmLabel = nativePhrase("确认拉黑", "Block"),
            danger = true,
            enabled = !acting,
            onDismiss = { blockDialog = false },
            onConfirm = ::blockUser,
        )
    }
}

@Composable
private fun PublicProfileHeader(
    profile: NativePublicProfile,
    visitCount: Int?,
    acting: Boolean,
    onSubscribe: () -> Unit,
    onFriend: () -> Unit,
    onAcceptFriend: () -> Unit,
    onDeclineFriend: () -> Unit,
    onMessage: () -> Unit,
    onMessageRequest: () -> Unit,
    onBlock: () -> Unit,
    onOpenExternalFeed: () -> Unit,
) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.cardShape) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeCoverImage(
                    path = profile.avatarUrl,
                    modifier = Modifier.size(70.dp).clip(androidx.compose.foundation.shape.CircleShape),
                    fallbackText = profile.nickname.ifBlank { profile.username },
                    tint = HlovetUi.accent,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile.nickname.ifBlank { profile.username }, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@${profile.username}", color = HlovetUi.muted, fontSize = 12.sp)
                    if (profile.roleName.isNotBlank()) {
                        Text(
                            buildString {
                                append(profile.roleName)
                                 if (profile.isSuperAdmin) append(nativePhrase(" · 超级管理员", " · Super admin")) else if (profile.isAdministrator) append(nativePhrase(" · 管理员", " · Admin"))
                            },
                            color = HlovetUi.accent,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (profile.visibleFields.bio && !profile.bio.isNullOrBlank()) {
                Text(profile.bio!!, color = HlovetUi.muted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (profile.visibleFields.joinedAt && !profile.createdAt.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(nativePhrase("加入于 ${profile.createdAt!!.take(10)}", "Joined ${profile.createdAt!!.take(10)}"), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            if (profile.visibleFields.stats) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PublicProfileStat(nativePhrase("公开文章", "Articles"), profile.publicArticleCount)
                    PublicProfileStat(nativePhrase("获赞", "Likes"), profile.receivedLikeCount)
                    PublicProfileStat(nativePhrase("阅读", "Views"), profile.publicViewCount)
                    PublicProfileStat(nativePhrase("订阅者", "Subscribers"), profile.subscriberCount)
                    if (profile.visibleFields.followingCount) PublicProfileStat(nativePhrase("已订阅", "Following"), profile.followingCount)
                }
            }
            if (visitCount != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(nativePhrase("主页访问 $visitCount", "$visitCount profile visits"), color = HlovetUi.muted, fontSize = 11.sp)
                }
            }
            if (!profile.isSelf) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileActionButton(nativePhrase("站外订阅", "External feed"), Icons.Filled.Link, false, !acting, Modifier.weight(1f), onOpenExternalFeed)
                    ProfileActionButton(if (profile.subscribed) nativePhrase("已订阅", "Subscribed") else nativePhrase("订阅", "Subscribe"), Icons.Filled.RssFeed, profile.subscribed, !acting, Modifier.weight(1f), onSubscribe)
                    when {
                        profile.relationship?.direction == "incoming" -> {
                            IconButton(
                                onClick = onAcceptFriend,
                                enabled = !acting,
                                modifier = Modifier.semantics {
                                    contentDescription = nativePhrase("接受好友申请", "Accept friend request")
                                },
                            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = HlovetUi.accent) }
                            IconButton(
                                onClick = onDeclineFriend,
                                enabled = !acting,
                                modifier = Modifier.semantics {
                                    contentDescription = nativePhrase("拒绝好友申请", "Decline friend request")
                                },
                            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = HlovetUi.secondaryAccent) }
                        }
                        profile.relationship?.direction == "outgoing" -> Text(nativePhrase("等待确认", "Pending"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        // 已接受关系以服务端 status 为准，direction 仅作为兼容旧接口返回的辅助字段。
                        profile.relationship?.status == "accepted" || profile.relationship?.direction == "accepted" -> Text(nativePhrase("已是好友", "Friends"), color = HlovetUi.accent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        profile.canRequestFriend -> ProfileActionButton(nativePhrase("加好友", "Add friend"), Icons.Filled.PersonAdd, false, !acting, Modifier.weight(1f), onFriend)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    when (profile.messageAccess) {
                        "conversation" -> ProfileActionButton(nativePhrase("发消息", "Message"), Icons.Filled.ChatBubbleOutline, false, !acting, Modifier.weight(1f), onMessage)
                        "request" -> ProfileActionButton(nativePhrase("消息请求", "Message request"), Icons.Filled.Send, false, !acting, Modifier.weight(1f), onMessageRequest)
                    }
                    IconButton(
                        onClick = onBlock,
                        enabled = !acting,
                        modifier = Modifier.semantics {
                            contentDescription = nativePhrase("加入黑名单", "Block user")
                        },
                    ) { Icon(Icons.Filled.Block, contentDescription = null, tint = HlovetUi.secondaryAccent) }
                }
            } else {
                ProfileActionButton(nativePhrase("站外订阅", "External feed"), Icons.Filled.Link, false, !acting, Modifier.fillMaxWidth(), onOpenExternalFeed)
            }
        }
    }
}

@Composable
private fun ProfileActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) HlovetUi.accentSoft else HlovetUi.surfaceLow,
            contentColor = if (active) HlovetUi.accent else HlovetUi.foreground,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturedContentCard(
    article: RemoteArticle?,
    collection: RemoteCollection?,
    onOpenArticle: (RemoteArticle) -> Unit,
    onOpenCollection: (RemoteCollection) -> Unit,
) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nativePhrase("代表内容", "Featured content"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            article?.let { ProfileContentRow(Icons.Filled.Article, it.title, nativePhrase("${it.viewCount} 阅读 · ${it.commentCount} 评论", "${it.viewCount} views · ${it.commentCount} comments")) { onOpenArticle(it) } }
            collection?.let { ProfileContentRow(Icons.Filled.FolderOpen, it.name, nativePhrase("${it.articleCount} 篇文章 · ${it.subscriberCount} 人订阅", "${it.articleCount} articles · ${it.subscriberCount} subscribers")) { onOpenCollection(it) } }
        }
    }
}

@Composable
private fun ProfileCollectionRow(collection: RemoteCollection, onClick: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick), HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeCoverImage(collection.coverPath, Modifier.size(52.dp), collection.name, HlovetUi.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(collection.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nativePhrase("${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅", "${collection.articleCount} articles · ${collection.subscriberCount} subscribers"), color = HlovetUi.muted, fontSize = 11.sp)
                if (collection.description.isNotBlank()) Text(collection.description, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.FolderOpen, contentDescription = nativePhrase("打开合集", "Open collection"), tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun PublicArticleRow(article: RemoteArticle, onClick: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onClick), HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeCoverImage(null, Modifier.size(44.dp), article.authorName, HlovetUi.secondaryAccent)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (article.category.isNotBlank()) append(article.category).append(" · ")
                        append(nativePhrase("${article.viewCount} 阅读 · ${article.commentCount} 评论", "${article.viewCount} views · ${article.commentCount} comments"))
                    },
                    color = HlovetUi.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (article.tags.isNotEmpty()) Text(article.tags.take(3).joinToString(" · "), color = HlovetUi.accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.Article, contentDescription = nativePhrase("打开文章", "Open article"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProfileContentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = HlovetUi.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ProfileSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(count.toString(), color = HlovetUi.muted, fontSize = 12.sp)
    }
}

@Composable
private fun PublicProfileStat(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.toString() ?: "—", color = HlovetUi.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun PublicProfileMessage(message: String, isError: Boolean = false) {
    GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), HlovetUi.rowShape) {
        Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun ProfileInputDialog(
    title: String,
    label: String,
    value: String,
    placeholder: String,
    submitLabel: String,
    enabled: Boolean,
    required: Boolean = false,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 24.dp), HlovetUi.cardShape) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, minLines = 3, maxLines = 5, enabled = enabled, colors = HlovetUi.fieldColors())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = enabled) { Text(nativePhrase("取消", "Cancel")) }
                    Button(onClick = onSubmit, enabled = enabled && (!required || value.trim().isNotBlank())) { Text(submitLabel) }
                }
            }
        }
    }
}

@Composable
private fun ProfileConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 24.dp), HlovetUi.cardShape) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(message, color = HlovetUi.muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = enabled) { Text(nativePhrase("取消", "Cancel")) }
                    Button(onClick = onConfirm, enabled = enabled, colors = if (danger) ButtonDefaults.buttonColors(containerColor = HlovetUi.secondaryAccent) else ButtonDefaults.buttonColors()) { Text(confirmLabel) }
                }
            }
        }
    }
}
