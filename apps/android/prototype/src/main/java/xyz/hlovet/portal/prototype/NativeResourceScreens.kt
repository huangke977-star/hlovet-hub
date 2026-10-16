@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ResourceAccent: Color get() = HlovetUi.accent
private val ResourceCoral: Color get() = HlovetUi.secondaryAccent
private val ResourceMuted: Color get() = HlovetUi.muted
private val ResourcePrimary: Color get() = HlovetUi.foreground
private val ResourceLow: Color get() = HlovetUi.surfaceLow

@Composable
private fun ResourceEmptyState(message: String) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.rowShape,
    ) {
        Text(message, color = ResourceMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun ResourceMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, shape = HlovetUi.rowShape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(icon, contentDescription = null, tint = ResourceAccent, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = ResourceMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ResourceCenterScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
    onOpenPoints: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenEarnings: () -> Unit,
) {
    val token = session?.accessToken
    var query by remember(token) { mutableStateOf("") }
    var sort by remember(token) { mutableStateOf("latest") }
    var page by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var catalog by remember(token) { mutableStateOf<NativeResourceCatalog?>(null) }
    var summary by remember(token) { mutableStateOf<NativeResourceSummary?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }

    LaunchedEffect(token, query, sort, page, reloadKey) {
        if (query.isNotBlank()) delay(260)
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val nextCatalog = NativeResourceApi.listCatalog(token, query, sort, page, 12)
                val nextSummary = token?.let { NativeResourceApi.getSummary(it) }
                nextCatalog to nextSummary
            }
        }.onSuccess { (nextCatalog, nextSummary) ->
            catalog = nextCatalog
            summary = nextSummary
            }.onFailure { error = it.message ?: nativePhrase("资源中心加载失败，请稍后重试。", "Could not load the resource center. Please try again.") }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("资源中心", "Resource center"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("用积分解锁文章中的专属内容", "Unlock exclusive article content with points"), color = ResourceMuted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { page = 1; reloadKey += 1 }) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = ResourceAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (session == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = ResourceAccent)
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("登录后查看个人积分和兑换状态", "Sign in to view your points and redemption status"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(nativePhrase("公开资源仍然可以直接浏览。", "Public resources remain available to browse."), color = ResourceMuted, fontSize = 11.sp)
                        }
                        TextButton(onClick = onLogin) { Text(nativePhrase("登录", "Sign in"), fontSize = 12.sp) }
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResourceMetric(nativePhrase("已兑换区域", "Unlocked blocks"), summary?.purchasedBlocks?.toString() ?: "—", Icons.Filled.Download, Modifier.weight(1f))
                    ResourceMetric(nativePhrase("已售区域", "Sold blocks"), summary?.soldBlocks?.toString() ?: "—", Icons.Filled.LibraryBooks, Modifier.weight(1f))
                    ResourceMetric(nativePhrase("待入账积分", "Pending points"), summary?.pendingPoints?.toString() ?: "—", Icons.Filled.History, Modifier.weight(1f))
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(onClick = onOpenPoints, modifier = Modifier.weight(1f)) { Text(nativePhrase("积分明细", "Points"), fontSize = 12.sp) }
                    TextButton(onClick = onOpenOrders, modifier = Modifier.weight(1f)) { Text(nativePhrase("兑换记录", "Redemptions"), fontSize = 12.sp) }
                    TextButton(onClick = onOpenEarnings, modifier = Modifier.weight(1f)) { Text(nativePhrase("资源收益", "Earnings"), fontSize = 12.sp) }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; page = 1 },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索", "Search"), tint = ResourceMuted) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = ""; page = 1 }) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), tint = ResourceMuted) } }
                } else null,
                placeholder = { Text(nativePhrase("搜索资源、作者或分类", "Search resources, authors, or categories"), color = ResourceMuted, fontSize = 12.sp) },
                colors = resourceFieldColors(),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(listOf("latest" to nativePhrase("最新发布", "Latest"), "popular" to nativePhrase("综合热度", "Popularity"), "price" to nativePhrase("所需积分", "Points required"))) { (value, label) ->
                    FilterChip(
                        selected = sort == value,
                        onClick = { sort = value; page = 1 },
                        label = { Text(label, fontSize = 11.sp) },
                        border = null,
                    )
                }
            }
        }
        if (loading) {
            item { ResourceEmptyState(nativePhrase("正在读取积分资源…", "Loading point resources...")) }
        } else if (!error.isNullOrBlank()) {
            item { ResourceEmptyState(error!!) }
        } else {
            val resourceItems = catalog?.items.orEmpty()
            if (resourceItems.isEmpty()) {
                item { ResourceEmptyState(if (query.isBlank()) nativePhrase("暂时没有可兑换的资源。", "No redeemable resources yet.") else nativePhrase("没有找到匹配的积分资源。", "No matching point resources found.")) }
            } else {
                items(resourceItems, key = { it.article.id }) { resource ->
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onOpenArticle(resource.article) },
                        shape = HlovetUi.cardShape,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Surface(shape = HlovetUi.rowShape, color = ResourceAccent.copy(alpha = .12f)) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ResourceAccent, modifier = Modifier.padding(10.dp).size(21.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(resource.article.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${resource.article.authorName} · ${resource.article.category.ifBlank { nativePhrase("未分类", "Uncategorized") }}",
                                    color = ResourceMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(nativePhrase("${resource.blockCount} 个资源区块 · ${resource.exchangeCount} 次兑换", "${resource.blockCount} resource blocks · ${resource.exchangeCount} redemptions"), color = ResourceMuted, fontSize = 10.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(nativePhrase("${resource.minimumPointCost} 积分", "${resource.minimumPointCost} points"), color = ResourceAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Icon(Icons.Filled.ChevronRight, contentDescription = nativePhrase("打开文章", "Open article"), tint = ResourceMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                catalog?.let { pageData ->
                    if (pageData.totalPages > 1) {
                        item { ResourcePagination(page, pageData.totalPages, onPrevious = { page -= 1 }, onNext = { page += 1 }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun resourceFieldColors() = HlovetUi.fieldColors(
    focusedBorderColor = ResourceAccent,
    focusedLabelColor = ResourceAccent,
    cursorColor = ResourceAccent,
    unfocusedBorderColor = HlovetUi.divider,
)

@Composable
private fun ResourcePagination(page: Int, totalPages: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = page > 1) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("上一页", "Previous page")) }
        Text("$page / $totalPages", color = ResourceMuted, fontSize = 12.sp)
        IconButton(onClick = onNext, enabled = page < totalPages) { Icon(Icons.Filled.ChevronRight, contentDescription = nativePhrase("下一页", "Next page")) }
    }
}

/** 展示 session 的真实积分余额、待入账和流水，onBack 返回上一页。 */
@Composable
internal fun PointsScreen(session: NativeSession?, onBack: () -> Unit) {
    // 每个账号独立加载服务端余额和当前页流水，不拼接其它作者的收益。
    val token = session?.accessToken
    var summary by remember(token) { mutableStateOf<NativeReputationSummary?>(null) }
    var ledger by remember(token) { mutableStateOf<NativeReputationPage?>(null) }
    var page by remember(token) { mutableIntStateOf(1) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }

    // 翻页时按服务器分页范围读取，未登录和加载失败均明确反馈。
    LaunchedEffect(token, page) {
        val currentToken = token ?: run {
            loading = false
            error = nativePhrase("请先登录后查看积分明细。", "Sign in to view your points.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeResourceApi.getReputation(currentToken) to NativeResourceApi.getReputationLedger(currentToken, page, 20)
            }
        }.onSuccess { (nextSummary, nextLedger) ->
            summary = nextSummary
            ledger = nextLedger
        }.onFailure { error = it.message ?: nativePhrase("积分明细加载失败，请稍后重试。", "Could not load points. Please try again.") }
        loading = false
    }

    // 使用稳定列表标识支持长流水实际滚动验收，所有行沿用统一玻璃组件。
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("native-points-list"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("积分明细", "Points"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading) {
            item { ResourceEmptyState(nativePhrase("正在读取积分明细…", "Loading points...")) }
        } else if (!error.isNullOrBlank()) {
            item { ResourceEmptyState(error!!) }
        } else {
            summary?.let { current ->
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.points.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ResourceAccent)
                                Text(nativePhrase("当前积分", "Current points"), color = ResourceMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                                Spacer(Modifier.weight(1f))
                                Text("Lv.${current.level.level} ${current.level.name}", color = ResourceMuted, fontSize = 12.sp)
                            }
                            Text(nativePhrase("经验值 ${current.experience} · 待入账 ${current.pendingPoints}", "XP ${current.experience} · Pending ${current.pendingPoints}"), color = ResourceMuted, fontSize = 11.sp)
                            Box(Modifier.fillMaxWidth().height(7.dp).background(HlovetUi.divider, HlovetUi.rowShape)) {
                                Box(Modifier.fillMaxWidth(current.progressPercent.coerceIn(0, 100) / 100f).height(7.dp).background(ResourceAccent, HlovetUi.rowShape))
                            }
                            current.nextLevel?.let { next -> Text(nativePhrase("距离 ${next.name} 还需 ${current.experienceToNext} 点经验", "${current.experienceToNext} XP to ${next.name}"), color = ResourceMuted, fontSize = 10.sp) }
                        }
                    }
                }
            }
            item { Text(nativePhrase("积分流水", "Point activity"), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
            val rows = ledger?.items.orEmpty()
            if (rows.isEmpty()) {
                item { ResourceEmptyState(nativePhrase("暂无积分记录。", "No point activity yet.")) }
            } else {
                item {
                    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                        Column {
                            rows.forEachIndexed { index, row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Surface(shape = CircleShape, color = if (row.pointDelta < 0) ResourceCoral.copy(alpha = .12f) else ResourceAccent.copy(alpha = .12f)) {
                                        Icon(Icons.Filled.History, contentDescription = null, tint = if (row.pointDelta < 0) ResourceCoral else ResourceAccent, modifier = Modifier.padding(8.dp).size(17.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(row.description.ifBlank { row.reason }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(nativeResourceTime(row.createdAt), color = ResourceMuted, fontSize = 10.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val delta = if (row.pointDelta != 0) row.pointDelta else row.pendingPointDelta
                                        Text(if (delta > 0) "+$delta" else delta.toString(), color = if (delta < 0) ResourceCoral else ResourceAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                         Text(if (row.pendingPointDelta > 0 && row.settledAt.isNullOrBlank()) nativePhrase("待入账", "Pending") else nativePhrase("已入账", "Settled"), color = ResourceMuted, fontSize = 10.sp)
                                    }
                                }
                                if (index < rows.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 15.dp))
                            }
                        }
                    }
                }
            }
            ledger?.let { pageData -> if (pageData.totalPages > 1) item { ResourcePagination(page, pageData.totalPages, { page -= 1 }, { page += 1 }) } }
        }
    }
}

@Composable
internal fun ResourceOrdersScreen(session: NativeSession?, onBack: () -> Unit) {
    val token = session?.accessToken
    var page by remember(token) { mutableIntStateOf(1) }
    var deliveries by remember(token) { mutableStateOf<NativeResourceDeliveryPage?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var busyId by remember(token) { mutableIntStateOf(0) }
    var expandedId by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        val currentToken = token ?: run {
            loading = false
            error = nativePhrase("请先登录后查看兑换记录。", "Sign in to view redemptions.")
            return
        }
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { NativeResourceApi.listDeliveries(currentToken, page, 20) } }
                .onSuccess { deliveries = it }
                .onFailure { error = it.message ?: nativePhrase("兑换记录加载失败，请稍后重试。", "Could not load redemptions. Please try again.") }
            loading = false
        }
    }

    LaunchedEffect(token, page) { load() }

    fun action(item: NativeResourceDelivery, retry: Boolean) {
        val currentToken = token ?: return
        if (busyId != 0) return
        scope.launch {
            busyId = item.id
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    if (retry) NativeResourceApi.retryDelivery(currentToken, item.id) else NativeResourceApi.downloadDelivery(currentToken, item.id)
                }
            }.onSuccess {
                notice = if (retry) nativePhrase("已重新尝试交付。", "Delivery retried.") else nativePhrase("已记录下载。", "Download recorded.")
                load()
            }.onFailure { error = it.message ?: nativePhrase("资源操作失败，请稍后重试。", "Resource operation failed. Please try again.") }
            busyId = 0
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("兑换记录", "Redemptions"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = ::load) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = ResourceAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading) {
            item { ResourceEmptyState(nativePhrase("正在读取兑换记录…", "Loading redemptions...")) }
        } else if (!error.isNullOrBlank() && deliveries == null) {
            item { ResourceEmptyState(error!!) }
        } else if (deliveries?.items.isNullOrEmpty()) {
            item { ResourceEmptyState(nativePhrase("暂无兑换记录。兑换积分资源后，记录会显示在这里。", "No redemptions yet. Unlock a point resource and it will appear here.")) }
        } else {
            if (!error.isNullOrBlank()) item { Text(error!!, color = ResourceCoral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            if (!notice.isNullOrBlank()) item { Text(notice!!, color = ResourceAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            items(deliveries!!.items, key = { it.id }) { item ->
                ResourceDeliveryCard(
                    item = item,
                    expanded = expandedId == item.id,
                    busy = busyId == item.id,
                    onToggleExpanded = { expandedId = if (expandedId == item.id) 0 else item.id },
                    onDownload = { action(item, false) },
                    onRetry = { action(item, true) },
                )
            }
            deliveries?.let { pageData -> if (pageData.totalPages > 1) item { ResourcePagination(page, pageData.totalPages, { page -= 1 }, { page += 1 }) } }
        }
    }
}

@Composable
private fun ResourceDeliveryCard(
    item: NativeResourceDelivery,
    expanded: Boolean,
    busy: Boolean,
    onToggleExpanded: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = when (item.status) {
        "downloaded" -> nativePhrase("已下载", "Downloaded")
        "failed" -> nativePhrase("交付失败", "Delivery failed")
        "refunded" -> nativePhrase("已退款", "Refunded")
        else -> nativePhrase("已解锁", "Unlocked")
    }
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = HlovetUi.rowShape, color = ResourceAccent.copy(alpha = .12f)) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = ResourceAccent, modifier = Modifier.padding(9.dp).size(19.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.articleTitle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                     Text(nativePhrase("资源区块 · ${item.pointCost} 积分 · ${nativeResourceTime(item.createdAt)}", "Resource block · ${item.pointCost} points · ${nativeResourceTime(item.createdAt)}"), color = ResourceMuted, fontSize = 10.sp)
                }
                Text(status, color = if (item.status == "failed") ResourceCoral else ResourceAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!item.lastError.isNullOrBlank()) Text(item.lastError!!, color = ResourceCoral, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.status == "unlocked" || item.status == "downloaded") {
                    TextButton(onClick = onDownload, enabled = !busy) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                         Text(nativePhrase("下载", "Download"), fontSize = 11.sp)
                    }
                }
                if (item.status == "failed") {
                    TextButton(onClick = onRetry, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                         Text(if (busy) nativePhrase("重试中…", "Retrying...") else nativePhrase("重试", "Retry"), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                 TextButton(onClick = onToggleExpanded) { Text(if (expanded) nativePhrase("收起记录", "Hide delivery log") else nativePhrase("交付记录", "Delivery log"), fontSize = 11.sp) }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                     if (item.events.isEmpty()) Text(nativePhrase("暂无事件记录。", "No delivery events."), color = ResourceMuted, fontSize = 11.sp)
                    else item.events.forEach { event -> Text("${event.type} · ${event.detail ?: "-"} · ${nativeResourceTime(event.createdAt)}", color = ResourceMuted, fontSize = 10.sp) }
                }
            }
        }
    }
}

/** 展示 session 作者的真实收益、资源汇总与最近兑换，onBack 返回，onOpenArticle 打开对应文章。 */
@Composable
internal fun ResourceEarningsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenArticle: (RemoteArticle) -> Unit,
) {
    // 收益仅按当前认证作者查询，不从公开目录或买方订单推算。
    val token = session?.accessToken
    var earnings by remember(token) { mutableStateOf<NativeResourceEarnings?>(null) }
    var loading by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }

    // 重新进入页面时读取服务端结算与退款结果，不在客户端快进 72 小时结算。
    LaunchedEffect(token) {
        val currentToken = token ?: run {
            loading = false
            error = nativePhrase("请先登录后查看资源收益。", "Sign in to view resource earnings.")
            return@LaunchedEffect
        }
        loading = true
        runCatching { withContext(Dispatchers.IO) { NativeResourceApi.getCreatorEarnings(currentToken) } }
            .onSuccess { earnings = it }
            .onFailure { error = it.message ?: nativePhrase("资源收益加载失败，请稍后重试。", "Could not load resource earnings. Please try again.") }
        loading = false
    }

    // 空状态、汇总与最近兑换共用统一玻璃表面，时间沿用交付响应中的权威字段。
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("资源收益", "Earnings"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading) {
            item { ResourceEmptyState(nativePhrase("正在读取资源收益…", "Loading earnings...")) }
        } else if (!error.isNullOrBlank()) {
            item { ResourceEmptyState(error!!) }
        } else {
            earnings?.let { current ->
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         ResourceMetric(nativePhrase("累计兑换积分", "Gross points"), current.total.toString(), Icons.Filled.AutoAwesome, Modifier.weight(1f).testTag("native-earnings-total"))
                         ResourceMetric(nativePhrase("待入账", "Pending"), current.pending.toString(), Icons.Filled.History, Modifier.weight(1f).testTag("native-earnings-pending"))
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         ResourceMetric(nativePhrase("已到账", "Settled"), current.settled.toString(), Icons.Filled.Download, Modifier.weight(1f).testTag("native-earnings-settled"))
                         ResourceMetric(nativePhrase("已退款", "Refunded"), current.refunded.toString(), Icons.Filled.Refresh, Modifier.weight(1f).testTag("native-earnings-refunded"))
                    }
                }
                item { Text(nativePhrase("按文章与资源区块汇总", "By article and resource block"), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (current.aggregates.isEmpty()) {
                    item { ResourceEmptyState(nativePhrase("暂无资源收益。", "No resource earnings yet.")) }
                } else {
                    item {
                        GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                            Column {
                                current.aggregates.forEachIndexed { index, aggregate ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            if (aggregate.articleSlug.isNotBlank()) {
                                                onOpenArticle(resourceArticleTarget(aggregate.articleId, aggregate.articleTitle, aggregate.articleSlug))
                                            }
                                        }.padding(horizontal = 15.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(aggregate.articleTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                         Text(nativePhrase("区块 ${aggregate.blockKey} · ${aggregate.redemptionCount} 次兑换", "Block ${aggregate.blockKey} · ${aggregate.redemptionCount} redemptions"), color = ResourceMuted, fontSize = 10.sp)
                                         Text(nativePhrase("${aggregate.grossPoints} 积分 · 待入账 ${aggregate.pendingPoints} · 已到账 ${aggregate.settledPoints} · 已退款 ${aggregate.refundedPoints}", "${aggregate.grossPoints} points · Pending ${aggregate.pendingPoints} · Settled ${aggregate.settledPoints} · Refunded ${aggregate.refundedPoints}"), color = ResourceAccent, fontSize = 11.sp)
                                    }
                                    if (index < current.aggregates.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 15.dp))
                                }
                            }
                        }
                    }
                }
                item { Text(nativePhrase("最近兑换明细", "Recent redemptions"), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (current.items.isEmpty()) {
                    item { ResourceEmptyState(nativePhrase("暂无兑换明细。", "No redemption details yet.")) }
                } else {
                    items(current.items, key = { it.id }) { item ->
                        GlassCard(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("native-earnings-delivery-${item.id}").clickable {
                                if (item.articleSlug.isNotBlank()) {
                                    onOpenArticle(resourceArticleTarget(item.articleId, item.articleTitle, item.articleSlug))
                                }
                            },
                            shape = HlovetUi.rowShape,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.articleTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.buyer.nickname} · ${nativeResourceTime(item.createdAt)}", color = ResourceMuted, fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                     Text(nativePhrase("${item.pointCost} 积分", "${item.pointCost} points"), color = ResourceAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                     Text(
                                         if (item.status == "refunded") nativePhrase("已退款", "Refunded")
                                         else if (item.settledAt.isNullOrBlank()) nativePhrase("预计 ${nativeResourceTime(item.availableAt)} 到账", "Expected ${nativeResourceTime(item.availableAt)}")
                                         else nativePhrase("已于 ${nativeResourceTime(item.settledAt)} 到账", "Settled ${nativeResourceTime(item.settledAt)}"),
                                         color = ResourceMuted, fontSize = 10.sp,
                                     )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nativeResourceTime(value: String): String = value
    .replace("T", " ")
    .replace(Regex("\\.\\d+Z?$"), "")
    .removeSuffix("Z")
    .take(16)

private fun resourceArticleTarget(id: Int, title: String, slug: String): RemoteArticle = RemoteArticle(
    id = id,
    title = title,
    slug = slug,
    summary = "",
    content = "",
    category = "",
    tags = emptyList(),
    viewCount = 0,
    likeCount = 0,
    favoriteCount = 0,
    commentCount = 0,
    authorName = nativePhrase("站内作者", "Site author"),
    authorUsername = "",
)
