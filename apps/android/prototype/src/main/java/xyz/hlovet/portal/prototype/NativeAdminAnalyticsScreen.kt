@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NativeAnalyticsSeries(
    val key: String,
    val label: String,
    val color: Color,
)

@Composable
internal fun NativeAdminAnalyticsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    val token = session?.accessToken
    var analytics by remember(token) { mutableStateOf<NativeAdminAnalytics?>(null) }
    var range by remember(token) { mutableIntStateOf(30) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var busy by remember(token) { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var rebuildDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token, range, reloadKey) {
        if (token.isNullOrBlank()) {
            analytics = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { NativeAdminDataApi.getAnalytics(token, range) } }
            .onSuccess { analytics = it }
            .onFailure { error = it.message ?: nativePhrase("运营数据加载失败，请稍后重试。", "Failed to load analytics. Try again.") }
        loading = false
    }

    fun rebuild() {
        val currentToken = token ?: return
        if (busy) return
        rebuildDialog = false
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativeAdminDataApi.rebuildAnalytics(currentToken, range) }
                notice = if (result.days > 0) nativePhrase("最近 ${result.days} 天的运营数据已补算完成。", "Analytics for the last ${result.days} days has been rebuilt.") else nativePhrase("运营数据补算任务已提交。", "Analytics rebuild has been queued.")
                reloadKey += 1
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("运营数据补算失败。", "Analytics rebuild failed.")
            } finally {
                busy = false
            }
        }
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
                        Text(nativePhrase("运营数据", "Operations analytics"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("按中国时区聚合的站点统计", "Site statistics aggregated in China Standard Time"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = !loading && !busy && !token.isNullOrBlank()) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = if (loading) HlovetUi.muted else HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(7, 30, 90).forEach { value ->
                    AnalyticsRangeButton(value, selected = range == value, enabled = !loading && !busy) { range = value }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { rebuildDialog = true }, enabled = !loading && !busy && !token.isNullOrBlank()) {
                    Text(if (busy) nativePhrase("补算中…", "Rebuilding...") else nativePhrase("补算", "Rebuild"), fontSize = 11.sp)
                }
            }
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (token.isNullOrBlank()) {
            item { AnalyticsMessage(nativePhrase("登录后查看运营数据。", "Sign in to view operations analytics."), Icons.Filled.Shield) }
        } else if (loading && analytics == null) {
            item { AnalyticsMessage(nativePhrase("正在读取运营数据…", "Loading operations analytics..."), Icons.Filled.Info) }
        } else if (!error.isNullOrBlank() && analytics == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        TextButton(onClick = { reloadKey++ }, enabled = !busy) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                    }
                }
            }
        } else {
            if (!error.isNullOrBlank()) item { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            analytics?.let { current ->
                item { AnalyticsSummaryCard(current) }
                item {
                    AnalyticsConversionCard(
                        readRate = current.notificationReadRate,
                        openRate = current.notificationOpenRate,
                        onboardingCompleted = current.onboardingCompleted,
                        onboardingRate = current.onboardingCompletionRate,
                    )
                }
                item {
                    AnalyticsChartCard(
                        title = nativePhrase("用户与内容", "Users and content"),
                        trend = current.trend,
                        series = listOf(
                            NativeAnalyticsSeries("newUsers", nativeAnalyticsMetricLabel("newUsers"), Color(0xFF4B78D1)),
                            NativeAnalyticsSeries("activeUsers", nativeAnalyticsMetricLabel("activeUsers"), Color(0xFF2F9378)),
                            NativeAnalyticsSeries("articles", nativeAnalyticsMetricLabel("articles"), Color(0xFFA46CBD)),
                        ),
                    )
                }
                item {
                    AnalyticsChartCard(
                        title = nativePhrase("访问与交流", "Visits and communication"),
                        trend = current.trend,
                        series = listOf(
                            NativeAnalyticsSeries("comments", nativeAnalyticsMetricLabel("comments"), Color(0xFF4F86A8)),
                            NativeAnalyticsSeries("messages", nativeAnalyticsMetricLabel("messages"), Color(0xFF7359A8)),
                            NativeAnalyticsSeries("views", nativeAnalyticsMetricLabel("views"), Color(0xFF2F9378)),
                        ),
                    )
                }
                item {
                    AnalyticsChartCard(
                        title = nativePhrase("风险与异常", "Risks and failures"),
                        trend = current.trend,
                        series = listOf(
                            NativeAnalyticsSeries("reports", nativeAnalyticsMetricLabel("reports"), Color(0xFFD15F79)),
                            NativeAnalyticsSeries("loginRisks", nativeAnalyticsMetricLabel("loginRisks"), Color(0xFFC07B31)),
                            NativeAnalyticsSeries("failedJobs", nativeAnalyticsMetricLabel("failedJobs"), Color(0xFF8D5961)),
                        ),
                    )
                }
                item {
                    AnalyticsChartCard(
                        title = nativePhrase("匿名话题", "Anonymous topics"),
                        trend = current.trend,
                        series = listOf(
                            NativeAnalyticsSeries("anonymousTopics", nativeAnalyticsMetricLabel("anonymousTopics"), Color(0xFF3F7F9B)),
                            NativeAnalyticsSeries("anonymousMessages", nativeAnalyticsMetricLabel("anonymousMessages"), Color(0xFF6D75B8)),
                            NativeAnalyticsSeries("anonymousLikes", nativeAnalyticsMetricLabel("anonymousLikes"), Color(0xFFB15C76)),
                            NativeAnalyticsSeries("anonymousFavorites", nativeAnalyticsMetricLabel("anonymousFavorites"), Color(0xFFC08338)),
                        ),
                    )
                }
                item {
                    AnalyticsChartCard(
                        title = nativePhrase("资源交易", "Resource exchange"),
                        trend = current.trend,
                        series = listOf(
                            NativeAnalyticsSeries("resourceExchanges", nativeAnalyticsMetricLabel("resourceExchanges"), Color(0xFF497F9A)),
                            NativeAnalyticsSeries("resourcePointsSpent", nativeAnalyticsMetricLabel("resourcePointsSpent"), Color(0xFFB56B46)),
                            NativeAnalyticsSeries("resourcePointsSettled", nativeAnalyticsMetricLabel("resourcePointsSettled"), Color(0xFF37876D)),
                        ),
                    )
                }
                item { AnalyticsRankingCard(nativePhrase("热门作者", "Top authors"), current.rankings["authors"].orEmpty(), onOpenArticle = {}) }
                item { AnalyticsRankingCard(nativePhrase("热门文章", "Top articles"), current.rankings["articles"].orEmpty(), onOpenArticle = onOpenArticle) }
                item { AnalyticsRankingCard(nativePhrase("热门搜索", "Top searches"), current.rankings["searches"].orEmpty(), onOpenArticle = {}) }
                item { AnalyticsRankingCard(nativePhrase("订阅增长", "Subscription growth"), current.rankings["subscriptionGrowth"].orEmpty(), onOpenArticle = {}) }
                item { AnalyticsRankingCard(nativePhrase("热门匿名话题", "Popular anonymous topics"), current.rankings["anonymousTopics"].orEmpty(), onOpenArticle = {}) }
                item { AnalyticsDefinitionsCard(current.definitions) }
                item {
                    Text(
                        nativePhrase("最近聚合：${current.latestAggregateAt?.let(::analyticsTime) ?: "尚未生成"} · 页面读取：${analyticsTime(current.generatedAt)}", "Latest aggregate: ${current.latestAggregateAt?.let(::analyticsTime) ?: "Not generated"} · Page read: ${analyticsTime(current.generatedAt)}"),
                        color = HlovetUi.muted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }
        }
    }

    if (rebuildDialog) {
        HlovetAlertDialog(
            onDismissRequest = { if (!busy) rebuildDialog = false },
            title = { Text(nativePhrase("补算运营数据", "Rebuild operations analytics")) },
            text = { Text(nativePhrase("将按中国时区重新计算最近 $range 天的聚合数据，并写入运营统计表。该操作不会修改文章、用户或聊天内容。", "Recalculate the last $range days in China Standard Time and write the results to the analytics table. Articles, users, and chats are not modified."), fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = { TextButton(onClick = ::rebuild, enabled = !busy) { Text(nativePhrase("确认补算", "Rebuild")) } },
            dismissButton = { TextButton(onClick = { rebuildDialog = false }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun AnalyticsRangeButton(value: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(nativePhrase("${value}天", "${value} days"), color = if (selected) HlovetUi.accent else HlovetUi.muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun AnalyticsSummaryCard(data: NativeAdminAnalytics) {
    val metrics = nativeAnalyticsSummaryMetricKeys().map { key -> nativeAnalyticsMetricLabel(key) to key }
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BarChart, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(nativePhrase("最近 ${data.range} 天概览", "Last ${data.range} days"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (label, key) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(formatAnalyticsNumber(data.summary[key] ?: 0L), color = HlovetUi.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(label, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsConversionCard(readRate: Double, openRate: Double, onboardingCompleted: Long, onboardingRate: Double) {
    AnalyticsPanel(Icons.Filled.TrendingUp, nativePhrase("转化指标", "Conversion metrics")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AnalyticsMetric(nativePhrase("通知已读率", "Notification read rate"), "${formatAnalyticsDecimal(readRate)}%")
            AnalyticsMetric(nativePhrase("通知打开率", "Notification open rate"), "${formatAnalyticsDecimal(openRate)}%")
            AnalyticsMetric(nativePhrase("兴趣引导完成", "Interest onboarding"), formatAnalyticsNumber(onboardingCompleted))
            AnalyticsMetric(nativePhrase("完成率", "Completion rate"), "${formatAnalyticsDecimal(onboardingRate)}%")
        }
    }
}

@Composable
private fun AnalyticsMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(73.dp)) {
        Text(value, color = HlovetUi.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnalyticsChartCard(title: String, trend: List<NativeAnalyticsTrendPoint>, series: List<NativeAnalyticsSeries>) {
    AnalyticsPanel(Icons.Filled.BarChart, title) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            series.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(7.dp).then(Modifier.padding(end = 0.dp)))
                    Text("●", color = item.color, fontSize = 9.sp)
                    Spacer(Modifier.width(3.dp))
                    Text(item.label, color = HlovetUi.muted, fontSize = 9.sp)
                }
            }
        }
        AnalyticsTrendCanvas(trend, series)
    }
}

@Composable
private fun AnalyticsTrendCanvas(trend: List<NativeAnalyticsTrendPoint>, series: List<NativeAnalyticsSeries>) {
    // Keep the selected 7/30/90-day range visible, matching the PC chart instead of silently truncating 90 days.
    val visible = trend
    Canvas(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 2.dp)) {
        val values = series.flatMap { item -> visible.map { point -> point.metrics[item.key] ?: 0L } }
        val maxValue = maxOf(1L, values.maxOrNull() ?: 0L).toFloat()
        val left = 8f
        val right = size.width - 8f
        val top = 10f
        val bottom = size.height - 16f
        drawLine(HlovetUi.divider, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(right, bottom), strokeWidth = 1f)
        series.forEach { item ->
            val path = Path()
            visible.forEachIndexed { index, point ->
                val x = if (visible.size <= 1) left else left + (right - left) * index / (visible.lastIndex.toFloat())
                val value = (point.metrics[item.key] ?: 0L).toFloat()
                val y = bottom - (bottom - top) * (value / maxValue)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (visible.isNotEmpty()) drawPath(path, item.color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        }
    }
    if (visible.size > 1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(visible.first().date.takeLast(5), color = HlovetUi.muted, fontSize = 9.sp)
            Text(visible.last().date.takeLast(5), color = HlovetUi.muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun AnalyticsRankingCard(title: String, items: List<NativeAnalyticsRankingItem>, onOpenArticle: (String) -> Unit) {
    AnalyticsPanel(Icons.Filled.Info, title) {
        if (items.isEmpty()) {
            Text(nativePhrase("暂无数据", "No data"), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            items.take(5).forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().then(if (!item.slug.isNullOrBlank()) Modifier.clickable { onOpenArticle(item.slug!!) } else Modifier).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", color = HlovetUi.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label.ifBlank { nativePhrase("未命名", "Untitled") }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.secondary, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatAnalyticsDecimal(item.score), color = HlovetUi.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsDefinitionsCard(definitions: List<NativeAnalyticsDefinition>) {
    AnalyticsPanel(Icons.Filled.Info, nativePhrase("统计口径", "Definitions")) {
        definitions.take(12).forEach { definition ->
            Column(Modifier.padding(bottom = 7.dp)) {
                Text(definition.label.ifBlank { definition.key }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(nativeAnalyticsDefinitionText(definition), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun AnalyticsPanel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AnalyticsMessage(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

private fun formatAnalyticsNumber(value: Long): String = "% ,d".replace(" ", "").format(value)

private fun formatAnalyticsDecimal(value: Double): String = if (!value.isFinite()) "0" else if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

private fun analyticsTime(value: String): String = value.replace('T', ' ').replace(Regex("\\.\\d+Z$"), "").replace("Z", "").takeLast(19)

private fun nativeAnalyticsMetricLabel(key: String): String = when (key) {
    "newUsers" -> nativePhrase("新增用户", "New users")
    "activeUsers" -> nativePhrase("活跃用户", "Active users")
    "articles" -> nativePhrase("发布文章", "Published articles")
    "comments" -> nativePhrase("评论", "Comments")
    "messages" -> nativePhrase("聊天消息", "Chat messages")
    "views" -> nativePhrase("文章阅读", "Article views")
    "likes" -> nativePhrase("点赞", "Likes")
    "favorites" -> nativePhrase("收藏", "Favorites")
    "subscriptions" -> nativePhrase("订阅增长", "Subscription growth")
    "reports" -> nativePhrase("举报", "Reports")
    "disabledUsers" -> nativePhrase("封禁", "Disabled accounts")
    "loginRisks" -> nativePhrase("登录风险", "Sign-in risks")
    "failedJobs" -> nativePhrase("异常任务", "Failed jobs")
    "anonymousTopics" -> nativePhrase("匿名话题", "Anonymous topics")
    "anonymousMessages" -> nativePhrase("匿名发言", "Anonymous messages")
    "anonymousLikes" -> nativePhrase("点评获赞", "Message likes")
    "anonymousFavorites" -> nativePhrase("话题喜欢", "Topic favorites")
    "notifications" -> nativePhrase("通知创建", "Notifications created")
    "notificationReads" -> nativePhrase("通知已读", "Notifications read")
    "notificationOpens" -> nativePhrase("通知打开", "Notifications opened")
    "onboardingCompleted" -> nativePhrase("兴趣引导完成", "Onboarding completed")
    "resourceExchanges" -> nativePhrase("资源兑换", "Resource unlocks")
    "resourcePointsSpent" -> nativePhrase("兑换积分", "Points spent")
    "resourcePointsPending" -> nativePhrase("待入账积分", "Pending points")
    "resourcePointsSettled" -> nativePhrase("已到账积分", "Settled points")
    else -> key
}

internal fun nativeAnalyticsSummaryMetricKeys(): List<String> = listOf(
    "newUsers",
    "activeUsers",
    "articles",
    "comments",
    "messages",
    "views",
    "likes",
    "favorites",
    "subscriptions",
    "reports",
    "disabledUsers",
    "loginRisks",
    "failedJobs",
    "anonymousTopics",
    "anonymousMessages",
    "anonymousLikes",
    "anonymousFavorites",
    "notifications",
    "notificationReads",
    "notificationOpens",
    "onboardingCompleted",
    "resourceExchanges",
    "resourcePointsSpent",
    "resourcePointsPending",
    "resourcePointsSettled",
)

private fun nativeAnalyticsDefinitionText(definition: NativeAnalyticsDefinition): String = when (definition.key) {
    "newUsers" -> nativePhrase("当天完成注册并写入用户表的账号数量。", "Accounts that completed registration that day.")
    "activeUsers" -> nativePhrase("当天发生登录、文章阅读、评论或发送聊天消息的去重账号。", "Distinct accounts that signed in, read, commented, or sent a chat message that day.")
    "articles" -> nativePhrase("当天首次进入已发布状态的文章数量。", "Articles that entered the published state for the first time that day.")
    "comments" -> nativePhrase("当天新建的文章评论和回复数量。", "Article comments and replies created that day.")
    "messages" -> nativePhrase("当天发送的私聊、群聊和通知会话消息数量。", "Direct, group, and notification conversation messages sent that day.")
    "views" -> nativePhrase("当天产生的文章阅读记录数量。", "Article view records created that day.")
    "likes" -> nativePhrase("当天新增的文章点赞记录数量。", "Article likes added that day.")
    "favorites" -> nativePhrase("当天新增的文章收藏记录数量。", "Article favorites added that day.")
    "subscriptions" -> nativePhrase("当天新增的作者订阅关系数量。", "New author subscriptions created that day.")
    "reports" -> nativePhrase("当天新建的文章、评论和群消息举报总数。", "Article, comment, and group-message reports created that day.")
    "disabledUsers" -> nativePhrase("当天被更新为停用状态的账号数量。", "Accounts changed to disabled that day.")
    "loginRisks" -> nativePhrase("当天记录的中风险和高风险登录安全事件。", "Medium- and high-risk sign-in security events recorded that day.")
    "failedJobs" -> nativePhrase("当天失败的邮件、存储扫描、媒体备份和运营后台任务总数。", "Failed mail, storage scan, media backup, and operations jobs that day.")
    "anonymousTopics" -> nativePhrase("当天新发起的匿名话题数量。", "Anonymous topics started that day.")
    "anonymousMessages" -> nativePhrase("当天在匿名话题中发送的点评数量。", "Messages posted in anonymous topics that day.")
    "anonymousLikes" -> nativePhrase("当天新增且当前仍有效的匿名点评点赞记录数量。", "Anonymous message likes added that day and still active.")
    "anonymousFavorites" -> nativePhrase("当天新增且当前仍有效的话题喜欢记录数量。", "Topic favorites added that day and still active.")
    "notifications" -> nativePhrase("当天创建的站内通知数量。", "In-app notifications created that day.")
    "notificationReads" -> nativePhrase("当天被标记为已读的站内通知数量。", "In-app notifications marked read that day.")
    "notificationOpens" -> nativePhrase("当天打开并进入关联内容的站内通知数量。", "In-app notifications opened to linked content that day.")
    "onboardingCompleted" -> nativePhrase("当天提交兴趣专题和作者选择的账号数量。", "Accounts that completed topic and author selection that day.")
    "resourceExchanges" -> nativePhrase("当天完成的文章资源区域兑换次数。", "Article resource sections unlocked that day.")
    "resourcePointsSpent" -> nativePhrase("当天用户兑换文章资源实际扣除的积分总数。", "Points deducted for article resource unlocks that day.")
    "resourcePointsPending" -> nativePhrase("当天新产生、将在 72 小时后向作者入账的资源收益积分。", "New resource income created that day and due to settle to authors after 72 hours.")
    "resourcePointsSettled" -> nativePhrase("当天完成 72 小时结算并入账给作者的资源收益积分。", "Resource income settled to authors after the 72-hour hold that day.")
    else -> definition.definition
}
