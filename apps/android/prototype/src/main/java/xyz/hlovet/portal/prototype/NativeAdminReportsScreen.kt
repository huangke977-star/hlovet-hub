@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存当前举报及目标处理状态，不将文章与评论的同号举报混为一条。 */
private data class NativeAdminReportAction(
    val report: NativeModerationReport,
    val status: String,
)

/** 以 session 的现有后台权限展示举报队列和治理操作；onBack 返回管理入口，媒体请求沿用该会话。 */
@Composable
internal fun NativeAdminReportsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 账号或权限变化时销毁旧协程和弹窗，迟到的旧操作不能覆盖新身份页面。
    key(session?.userId, session?.accessToken, session?.isAdministrator, session?.isSuperAdmin) {
        NativeAdminReportsContent(session, onBack)
    }
}

/** 读取 session 的举报队列与治理设置；onBack 仅负责导航，所有写操作仍由原接口授权。 */
@Composable
private fun NativeAdminReportsContent(session: NativeSession?, onBack: () -> Unit) {
    // 队列、筛选和操作弹窗局限于当前身份，不为普通用户发出后台读取请求。
    val token = session?.accessToken
    val canManage = session?.isAdministrator == true || session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var data by remember(token) { mutableStateOf<NativeModerationReportPage?>(null) }
    var summary by remember(token) { mutableStateOf<NativeModerationReportSummary?>(null) }
    var status by remember(token) { mutableStateOf("pending") }
    var source by remember(token) { mutableStateOf("all") }
    var page by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var busyKey by remember(token) { mutableStateOf<String?>(null) }
    var selectedKeys by remember(token) { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember(token) { mutableStateOf<NativeModerationReport?>(null) }
    var action by remember(token) { mutableStateOf<NativeAdminReportAction?>(null) }
    var batchStatus by remember(token) { mutableStateOf<String?>(null) }
    var resolution by remember(token) { mutableStateOf("") }
    var actionError by remember(token) { mutableStateOf<String?>(null) }
    val actionScroll = rememberScrollState()
    var resolutionMode by remember(token) { mutableStateOf("keep") }
    var responseTemplateId by remember(token) { mutableStateOf("manual") }
    var moderationView by remember(token) { mutableStateOf("queue") }
    var overview by remember(token) { mutableStateOf<NativeModerationOverview?>(null) }
    var ruleHits by remember(token) { mutableStateOf<NativeModerationRuleHitPage?>(null) }
    var moderationRules by remember(token) { mutableStateOf<List<NativeModerationRule>>(emptyList()) }
    var moderationTemplates by remember(token) { mutableStateOf<List<NativeModerationTemplate>>(emptyList()) }
    var moderationSettings by remember(token) { mutableStateOf<NativeModerationSettings?>(null) }
    var governanceLoading by remember(token) { mutableStateOf(false) }
    var governanceReload by remember(token) { mutableIntStateOf(0) }
    var governancePage by remember(token) { mutableIntStateOf(1) }
    var ruleEditorOpen by remember(token) { mutableStateOf(false) }
    var editingRule by remember(token) { mutableStateOf<NativeModerationRule?>(null) }
    var templateEditorOpen by remember(token) { mutableStateOf(false) }
    var editingTemplate by remember(token) { mutableStateOf<NativeModerationTemplate?>(null) }
    var deleteRuleTarget by remember(token) { mutableStateOf<NativeModerationRule?>(null) }
    var deleteTemplateTarget by remember(token) { mutableStateOf<NativeModerationTemplate?>(null) }

    LaunchedEffect(action, batchStatus, actionError) {
        // 新操作或提交失败后回到弹窗顶部，让错误立即可见而不是留在已滚过的位置。
        if (action != null || batchStatus != null) actionScroll.scrollTo(0)
    }

    LaunchedEffect(token, status, source, page, reloadKey) {
        // 当前筛选直接作用于举报分页，汇总和处理模板由原有后台接口独立读取。
        if (token.isNullOrBlank() || !canManage) {
            loading = false
            data = null
            summary = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { NativeReportsApi.listAdmin(token, page, status = status, source = source) }
            data = result
            // 选择只对当前页仍待处理的举报有效，刷新、换页或单条处理后不保留失效选择。
            selectedKeys = selectedKeys.intersect(result.items.filter { it.status == "pending" }.map { it.key }.toSet())
            summary = withContext(Dispatchers.IO) { NativeReportsApi.summary(token) }
            moderationTemplates = withContext(Dispatchers.IO) { NativeReportsApi.templates(token) }
            if (result.page != page) page = result.page
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("举报中心读取失败。", "Could not load the reports center.")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(token, moderationView, governanceReload, governancePage) {
        // 仅在治理视图读取统计或规则配置，不让它们替代队列本身的分页结果。
        if (token.isNullOrBlank() || !canManage || moderationView == "queue") return@LaunchedEffect
        governanceLoading = true
        error = null
        try {
            when (moderationView) {
                "insights" -> {
                    val loaded = withContext(Dispatchers.IO) { NativeReportsApi.overview(token) to NativeReportsApi.ruleHits(token, governancePage) }
                    overview = loaded.first
                    ruleHits = loaded.second
                }
                "settings" -> {
                    val loaded = withContext(Dispatchers.IO) {
                        Triple(NativeReportsApi.rules(token), NativeReportsApi.templates(token), NativeReportsApi.settings(token))
                    }
                    moderationRules = loaded.first
                    moderationTemplates = loaded.second
                    moderationSettings = loaded.third
                }
            }
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("内容治理数据读取失败。", "Could not load content governance data.")
        } finally {
            governanceLoading = false
        }
    }

    /** 打开 report 的 nextStatus 操作，清空前次反馈和错误，群消息默认明确执行删除。 */
    fun openAction(report: NativeModerationReport, nextStatus: String) {
        // 只允许仍待处理的记录进入写操作弹窗。
        if (report.status != "pending" || busyKey != null) return
        action = NativeAdminReportAction(report, nextStatus)
        actionError = null
        resolution = ""
        responseTemplateId = "manual"
        resolutionMode = if (report.source == "group_message" && nextStatus == "resolved") "delete" else "keep"
    }

    /** 提交当前单条操作；反馈必填，失败保留处理方式及文字并在弹窗内提示。 */
    fun submitAction() {
        // 与 PC 一致要求明确反馈，不为用户自动补写处理结论。
        val accessToken = token ?: return
        val target = action ?: return
        if (busyKey != null) return
        val feedback = resolution.trim()
        if (feedback.isBlank()) {
            actionError = nativePhrase("请填写处理反馈。", "Enter a resolution message.")
            return
        }
        actionError = null
        busyKey = target.report.key
        scope.launch {
            try {
                // 来源决定原有处理接口，内容状态仅在已处理时按用户选择提交。
                withContext(Dispatchers.IO) {
                    val contentStatus = if (target.status == "resolved") when (resolutionMode) {
                        "block" -> "blocked"
                        "delete" -> "deleted"
                        else -> null
                    } else null
                    when (target.report.source) {
                        "article" -> NativeReportsApi.moderateArticleReport(accessToken, target.report.id, target.status, feedback, contentStatus)
                        "comment" -> NativeReportsApi.moderateCommentReport(accessToken, target.report.id, target.status, feedback, contentStatus)
                        else -> NativeReportsApi.moderateGroupReport(accessToken, target.report.id, target.status, feedback, target.status == "resolved" && resolutionMode == "delete")
                    }
                }
                action = null
                selectedKeys = selectedKeys - target.report.key
                notice = if (target.status == "resolved") nativePhrase("举报已处理。", "Report handled.") else nativePhrase("举报已驳回。", "Report rejected.")
                reloadKey++
            } catch (exception: Exception) {
                // 错误留在仍打开的操作弹窗顶部，不藏到后方列表。
                actionError = exception.message ?: nativePhrase("举报处理失败。", "Report action failed.")
            } finally {
                busyKey = null
            }
        }
    }

    /** 批量提交当前页同来源待处理举报；部分失败保留失败选择、反馈及逐条错误。 */
    fun submitBatch() {
        // 在提交前重新核对待处理范围，避免把已刷新移除的选择交给批量接口。
        val accessToken = token ?: return
        if (busyKey != null) return
        val selected = data?.items.orEmpty().filter { it.key in selectedKeys && it.status == "pending" }
        val selectedSource = selected.firstOrNull()?.source
        val targetStatus = batchStatus
        if (selectedSource.isNullOrBlank() || targetStatus.isNullOrBlank()) {
            actionError = nativePhrase("请选择仍待处理的举报。", "Select reports that are still pending.")
            return
        }
        if (selected.any { it.source != selectedSource }) {
            actionError = nativePhrase("批量处理只能选择同一种举报来源。", "Bulk actions require reports from the same source.")
            return
        }
        val feedback = resolution.trim()
        if (feedback.isBlank()) {
            actionError = nativePhrase("请填写处理反馈。", "Enter a resolution message.")
            return
        }
        val ids = selected.map { it.id }
        actionError = null
        busyKey = "batch"
        scope.launch {
            try {
                // 成功项从选择中移除，失败项保持可重试；批量处理按 PC 规则保留原内容。
                val result = withContext(Dispatchers.IO) { NativeReportsApi.bulkModerate(accessToken, selectedSource, ids, targetStatus, feedback) }
                val failedIds = result.failed.map { it.id }.toSet()
                selectedKeys = selected.filter { it.id in failedIds }.map { it.key }.toSet()
                if (result.failed.isEmpty()) {
                    batchStatus = null
                    resolution = ""
                    responseTemplateId = "manual"
                } else {
                    actionError = result.failed.joinToString("\n") { "#${it.id} · ${it.message}" }
                }
                notice = if (result.failed.isEmpty()) nativePhrase("已批量处理 ${result.succeeded.size} 条举报。", "${result.succeeded.size} reports handled in bulk.") else nativePhrase("已处理 ${result.succeeded.size} 条，${result.failed.size} 条未处理。", "${result.succeeded.size} handled; ${result.failed.size} could not be handled.")
                reloadKey++
            } catch (exception: Exception) {
                // 整体请求失败时不清空选择或反馈，用户可在当前弹窗直接重试。
                actionError = exception.message ?: nativePhrase("批量处理失败。", "Bulk action failed.")
            } finally {
                busyKey = null
            }
        }
    }

    /** 切换 report 的选择；只接受当前页待处理且来源一致的业务记录。 */
    fun toggleSelected(report: NativeModerationReport) {
        // 选择控件和提交校验共同约束范围，不能批量处理已完成记录。
        if (report.status != "pending" || busyKey != null) return
        val current = data?.items.orEmpty().filter { it.key in selectedKeys }
        if (report.key in selectedKeys) selectedKeys = selectedKeys - report.key
        else if (current.isNotEmpty() && current.any { it.source != report.source }) error = nativePhrase("批量处理一次只能选择同一种举报来源。", "Bulk actions require reports from the same source.")
        else selectedKeys = selectedKeys + report.key
    }

    fun saveModerationRule(input: NativeModerationRuleInput) {
        val accessToken = token ?: return
        val id = editingRule?.id
        busyKey = "moderation-rule"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeReportsApi.saveRule(accessToken, id, input) }
                ruleEditorOpen = false
                governanceReload++
                notice = nativePhrase("治理规则已保存。", "Governance rule saved.")
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("规则保存失败。", "Could not save the rule.") }
            finally { busyKey = null }
        }
    }

    fun saveModerationTemplate(input: NativeModerationTemplateInput) {
        val accessToken = token ?: return
        val id = editingTemplate?.id
        busyKey = "moderation-template"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeReportsApi.saveTemplate(accessToken, id, input) }
                templateEditorOpen = false
                governanceReload++
                notice = nativePhrase("回复模板已保存。", "Response template saved.")
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("模板保存失败。", "Could not save the template.") }
            finally { busyKey = null }
        }
    }

    fun removeModerationRule(rule: NativeModerationRule) {
        val accessToken = token ?: return
        busyKey = "moderation-rule-${rule.id}"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeReportsApi.deleteRule(accessToken, rule.id) }
                deleteRuleTarget = null
                governanceReload++
                notice = nativePhrase("治理规则已删除。", "Governance rule deleted.")
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("规则删除失败。", "Could not delete the rule.") }
            finally { busyKey = null }
        }
    }

    fun removeModerationTemplate(template: NativeModerationTemplate) {
        val accessToken = token ?: return
        busyKey = "moderation-template-${template.id}"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeReportsApi.deleteTemplate(accessToken, template.id) }
                deleteTemplateTarget = null
                governanceReload++
                notice = nativePhrase("回复模板已删除。", "Response template deleted.")
            } catch (exception: Exception) { error = exception.message ?: nativePhrase("模板删除失败。", "Could not delete the template.") }
            finally { busyKey = null }
        }
    }

    // 队列与治理区共用原生列表和玻璃组件，写权限仍由服务器最终校验。
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("admin-reports-list"),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("举报中心", "Reports"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("处理文章、评论和群消息举报", "Process article, comment, and group-message reports"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = { IconButton(onClick = { reloadKey++; governanceReload++ }, enabled = !loading && busyKey == null) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeAdminReportFilter(nativePhrase("举报队列", "Report queue"), moderationView == "queue") { moderationView = "queue" }
                NativeAdminReportFilter(nativePhrase("规则统计", "Rule insights"), moderationView == "insights") { moderationView = "insights"; governancePage = 1 }
                if (session?.isSuperAdmin == true) NativeAdminReportFilter(nativePhrase("规则与自动化", "Rules and automation"), moderationView == "settings") { moderationView = "settings" }
            }
        }
        if (moderationView == "queue") item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeAdminReportFilter(nativePhrase("待处理", "Pending"), status == "pending") { status = "pending"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("已处理", "Resolved"), status == "resolved") { status = "resolved"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("已驳回", "Rejected"), status == "rejected") { status = "rejected"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("全部", "All"), status == "all") { status = "all"; page = 1; selectedKeys = emptySet() }
            }
        }
        if (moderationView == "queue") item {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeAdminReportFilter(nativePhrase("全部来源", "All sources"), source == "all") { source = "all"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("文章", "Articles"), source == "article") { source = "article"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("评论", "Comments"), source == "comment") { source = "comment"; page = 1; selectedKeys = emptySet() }
                NativeAdminReportFilter(nativePhrase("群消息", "Group messages"), source == "group_message") { source = "group_message"; page = 1; selectedKeys = emptySet() }
            }
        }
        if (moderationView == "queue") item {
            Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                NativeAdminReportMetric(nativePhrase("待处理", "Pending"), summary?.pending ?: 0, HlovetUi.secondaryAccent)
                NativeAdminReportMetric(nativePhrase("全部", "All"), summary?.total ?: 0, HlovetUi.accent)
                Text(nativePhrase("文章 ${summary?.article ?: 0} · 评论 ${summary?.comment ?: 0} · 群消息 ${summary?.groupMessage ?: 0}", "Articles ${summary?.article ?: 0} · Comments ${summary?.comment ?: 0} · Groups ${summary?.groupMessage ?: 0}"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.weight(1f).align(Alignment.CenterVertically), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (!error.isNullOrBlank() && (data != null || moderationView != "queue")) item { Text(error!!, color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (token.isNullOrBlank()) item { NativeAdminReportsMessage(nativePhrase("登录后查看举报中心。", "Sign in to view reports."), Icons.Filled.Lock) }
        else if (session?.isAdministrator != true && session?.isSuperAdmin != true) item { NativeAdminReportsMessage(nativePhrase("当前账号没有访问举报中心的权限。", "This account cannot access reports."), Icons.Filled.Shield, error = true) }
        else if (moderationView == "queue" && loading && data == null) item { NativeAdminReportsMessage(nativePhrase("正在读取举报队列…", "Loading report queue…"), Icons.Filled.Info) }
        else if (moderationView == "queue" && !error.isNullOrBlank() && data == null) item { NativeAdminReportsMessage(error!!, Icons.Filled.Error, error = true) }
        else if (moderationView == "queue" && data?.items.isNullOrEmpty()) item { NativeAdminReportsMessage(nativePhrase("当前筛选下没有举报记录。", "No reports match the current filters."), Icons.Filled.CheckCircle) }
        else if (moderationView == "queue") {
            if (selectedKeys.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(nativePhrase("已选择 ${selectedKeys.size} 条同类举报", "${selectedKeys.size} reports selected"), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { batchStatus = "rejected"; resolution = ""; responseTemplateId = "manual"; actionError = null }, enabled = busyKey == null) { Text(nativePhrase("批量驳回", "Reject all"), fontSize = 11.sp) }
                    TextButton(onClick = { batchStatus = "resolved"; resolution = ""; responseTemplateId = "manual"; actionError = null }, enabled = busyKey == null) { Text(nativePhrase("批量处理", "Resolve all"), color = HlovetUi.accent, fontSize = 11.sp) }
                    IconButton(onClick = { selectedKeys = emptySet() }, enabled = busyKey == null, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消选择", "Clear selection"), modifier = Modifier.size(16.dp)) }
                }
            }
            items(data!!.items, key = { it.key }) { report ->
                NativeAdminReportRow(report, selected = report.key in selectedKeys, selectable = selectedKeys.isEmpty() || data!!.items.filter { it.key in selectedKeys }.all { it.source == report.source }, busy = busyKey != null, onSelect = { toggleSelected(report) }, onOpen = { detail = report }, onResolve = { openAction(report, "resolved") }, onReject = { openAction(report, "rejected") })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = !loading && page > 1 && busyKey == null) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                    Text(nativePhrase("第 ${data?.page ?: page} / ${data?.totalPages ?: 1} 页", "Page ${data?.page ?: page} / ${data?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 11.sp)
                    TextButton(onClick = { page++ }, enabled = !loading && page < (data?.totalPages ?: 1) && busyKey == null) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                }
            }
        }
        else if (moderationView == "insights" && governanceLoading && overview == null) item { NativeAdminReportsMessage(nativePhrase("正在读取治理统计…", "Loading governance insights…"), Icons.Filled.Info) }
        else if (moderationView == "insights" && overview != null) {
            val stats = overview!!
            item { NativeModerationOverviewCard(stats) }
            item { Text(nativePhrase("最近规则命中 · 近 30 日 ${stats.ruleHitsLast30Days} 次", "Recent rule hits · ${stats.ruleHitsLast30Days} in the last 30 days"), color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            if (ruleHits?.items.isNullOrEmpty()) item { NativeAdminReportsMessage(nativePhrase("暂无规则命中记录。", "No rule hits."), Icons.Filled.CheckCircle) }
            else items(ruleHits!!.items, key = { "rule-hit-${it.id}" }) { hit -> NativeModerationRuleHitRow(hit) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { governancePage--; governanceReload++ }, enabled = !governanceLoading && governancePage > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
                    Text(nativePhrase("第 ${ruleHits?.page ?: governancePage} / ${ruleHits?.totalPages ?: 1} 页", "Page ${ruleHits?.page ?: governancePage} / ${ruleHits?.totalPages ?: 1}"), color = HlovetUi.muted, fontSize = 11.sp)
                    TextButton(onClick = { governancePage++; governanceReload++ }, enabled = !governanceLoading && governancePage < (ruleHits?.totalPages ?: 1)) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
                }
            }
        }
        else if (moderationView == "settings" && session?.isSuperAdmin != true) item { NativeAdminReportsMessage(nativePhrase("只有超级管理员可以修改治理规则和提醒设置。", "Only super administrators can edit governance rules and reminder settings."), Icons.Filled.Shield, error = true) }
        else if (moderationView == "settings" && governanceLoading && moderationSettings == null) item { NativeAdminReportsMessage(nativePhrase("正在读取治理配置…", "Loading governance settings…"), Icons.Filled.Info) }
        else if (moderationView == "settings" && moderationSettings != null) {
            item {
                NativeModerationSettingsCard(moderationSettings!!, busy = busyKey == "moderation-settings", onSave = { value ->
                    busyKey = "moderation-settings"
                    scope.launch {
                        try {
                            moderationSettings = withContext(Dispatchers.IO) { NativeReportsApi.updateSettings(token, value) }
                            notice = nativePhrase("处理时限与提醒设置已保存。", "Deadline and reminder settings saved.")
                        } catch (exception: Exception) { error = exception.message ?: nativePhrase("设置保存失败。", "Could not save the settings.") }
                        finally { busyKey = null }
                        }
                })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nativePhrase("自动治理规则", "Automated moderation rules"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingRule = null; ruleEditorOpen = true }, enabled = busyKey == null) { Text(nativePhrase("新增规则", "Add rule"), color = HlovetUi.accent, fontSize = 11.sp) }
                }
            }
            if (moderationRules.isEmpty()) item { NativeAdminReportsMessage(nativePhrase("尚未配置自动治理规则。", "No automated moderation rules configured."), Icons.Filled.Info) }
            else items(moderationRules, key = { "moderation-rule-${it.id}" }) { rule ->
                NativeModerationRuleRow(rule, onEdit = { editingRule = rule; ruleEditorOpen = true }, onDelete = { deleteRuleTarget = rule })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nativePhrase("处理回复模板", "Response templates"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingTemplate = null; templateEditorOpen = true }, enabled = busyKey == null) { Text(nativePhrase("新增模板", "Add template"), color = HlovetUi.accent, fontSize = 11.sp) }
                }
            }
            if (moderationTemplates.isEmpty()) item { NativeAdminReportsMessage(nativePhrase("尚未配置回复模板。", "No response templates configured."), Icons.Filled.Info) }
            else items(moderationTemplates, key = { "moderation-template-${it.id}" }) { template ->
                NativeModerationTemplateRow(template, onEdit = { editingTemplate = template; templateEditorOpen = true }, onDelete = { deleteTemplateTarget = template })
            }
        }
    }

    // 详情把当前会话传入共享媒体组件，下载和播放器与普通聊天使用相同权限路径。
    detail?.let { report -> NativeAdminReportDetailDialog(report, session) { detail = null } }

    action?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (busyKey == null) action = null },
            title = { Text(if (target.status == "resolved") nativePhrase("处理举报", "Resolve report") else nativePhrase("驳回举报", "Reject report")) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(actionScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actionError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.testTag("admin-report-action-error")) }
                    Text("${adminReportSourceLabel(target.report.source)} · ${adminReportTitle(target.report)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (target.status == "resolved") {
                        Text(nativePhrase("处理方式", "Action"), color = HlovetUi.muted, fontSize = 11.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            NativeAdminReportFilter(nativePhrase("保留内容", "Keep content"), resolutionMode == "keep") { resolutionMode = "keep" }
                            NativeAdminReportFilter(if (target.report.source == "group_message") nativePhrase("删除消息", "Delete message") else nativePhrase("删除内容", "Delete content"), resolutionMode == "delete") { resolutionMode = "delete" }
                            if (target.report.source != "group_message") NativeAdminReportFilter(nativePhrase("屏蔽内容", "Block content"), resolutionMode == "block") { resolutionMode = "block" }
                        }
                    }
                    NativeReportTemplatePicker(moderationTemplates, target.status, responseTemplateId, onSelect = { template -> responseTemplateId = template?.id?.toString() ?: "manual"; resolution = template?.content.orEmpty() })
                    OutlinedTextField(resolution, { resolution = it.take(500); responseTemplateId = "manual" }, enabled = busyKey == null, modifier = Modifier.fillMaxWidth().testTag("admin-report-resolution"), minLines = 3, maxLines = 6, label = { Text(nativePhrase("处理反馈", "Resolution message")) }, colors = nativeAdminReportsFieldColors())
                }
            },
            confirmButton = { TextButton(onClick = ::submitAction, enabled = busyKey == null) { Text(if (busyKey != null) nativePhrase("处理中…", "Working…") else nativePhrase("确认", "Confirm"), color = if (target.status == "resolved") HlovetUi.accent else HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { action = null }, enabled = busyKey == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    batchStatus?.let { targetStatus ->
        HlovetAlertDialog(
            onDismissRequest = { if (busyKey == null) batchStatus = null },
            title = { Text(if (targetStatus == "resolved") nativePhrase("批量处理举报", "Resolve reports in bulk") else nativePhrase("批量驳回举报", "Reject reports in bulk")) },
            text = {
                // 部分失败的逐条反馈与草稿一起保留在有界滚动区域。
                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(actionScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actionError?.let { Text(it, color = HlovetUi.secondaryAccent, fontSize = 12.sp, modifier = Modifier.testTag("admin-report-action-error")) }
                    Text(nativePhrase("将对当前选中的 ${selectedKeys.size} 条同类举报执行操作，批量处理保留原内容。", "This will act on the ${selectedKeys.size} selected reports of the same source. Bulk resolution keeps the reported content."), fontSize = 13.sp, lineHeight = 18.sp)
                    NativeReportTemplatePicker(moderationTemplates, targetStatus, responseTemplateId, onSelect = { template -> responseTemplateId = template?.id?.toString() ?: "manual"; resolution = template?.content.orEmpty() })
                    OutlinedTextField(resolution, { resolution = it.take(500); responseTemplateId = "manual" }, enabled = busyKey == null, modifier = Modifier.fillMaxWidth().testTag("admin-report-resolution"), minLines = 3, maxLines = 5, label = { Text(nativePhrase("统一处理反馈", "Shared resolution message")) }, colors = nativeAdminReportsFieldColors())
                }
            },
            confirmButton = { TextButton(onClick = ::submitBatch, enabled = busyKey == null) { Text(if (busyKey != null) nativePhrase("处理中…", "Working…") else nativePhrase("确认", "Confirm"), color = HlovetUi.accent) } },
            dismissButton = { TextButton(onClick = { batchStatus = null }, enabled = busyKey == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }

    if (ruleEditorOpen) NativeModerationRuleDialog(
        initial = editingRule,
        busy = busyKey == "moderation-rule",
        onDismiss = { if (busyKey == null) ruleEditorOpen = false },
        onSave = ::saveModerationRule,
    )
    if (templateEditorOpen) NativeModerationTemplateDialog(
        initial = editingTemplate,
        busy = busyKey == "moderation-template",
        onDismiss = { if (busyKey == null) templateEditorOpen = false },
        onSave = ::saveModerationTemplate,
    )
    deleteRuleTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (busyKey == null) deleteRuleTarget = null },
            title = { Text(nativePhrase("删除治理规则？", "Delete moderation rule?")) },
            text = { Text(nativePhrase("“${target.name}”删除后不再参与后续内容检查。", "After “${target.name}” is deleted, it will no longer check new content.")) },
            confirmButton = { TextButton(onClick = { removeModerationRule(target) }, enabled = busyKey == null) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { deleteRuleTarget = null }, enabled = busyKey == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
    deleteTemplateTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (busyKey == null) deleteTemplateTarget = null },
            title = { Text(nativePhrase("删除回复模板？", "Delete response template?")) },
            text = { Text(nativePhrase("“${target.name}”删除后将不能在举报处理时快速套用。", "After “${target.name}” is deleted, it will no longer be available for quick use when handling reports.")) },
            confirmButton = { TextButton(onClick = { removeModerationTemplate(target) }, enabled = busyKey == null) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent) } },
            dismissButton = { TextButton(onClick = { deleteTemplateTarget = null }, enabled = busyKey == null) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

/** 展示 report 摘要；selected/selectable 约束同来源选择，busy 禁用写操作，回调执行对应命令。 */
@Composable
private fun NativeAdminReportRow(
    report: NativeModerationReport,
    selected: Boolean,
    selectable: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onResolve: () -> Unit,
    onReject: () -> Unit,
) {
    // 选择复用统一复选框，已完成和其它来源记录不可加入当前批次。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("admin-report-${report.key}").clickable(onClick = onOpen), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HlovetCheckbox(checked = selected, onCheckedChange = { onSelect() }, enabled = !busy && report.status == "pending" && (selectable || selected), modifier = Modifier.size(30.dp).testTag("admin-report-select-${report.key}"))
                Spacer(Modifier.width(4.dp))
                Icon(adminReportIcon(report.source), contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(adminReportTitle(report), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(adminReportStatusLabel(report.status), color = adminReportStatusColor(report.status), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${adminReportSourceLabel(report.source)} · ${adminReportReason(report.reason)} · ${adminReportTime(report.createdAt)}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(nativePhrase("举报人：${report.reporter.nickname} · 被举报：${report.targetUser?.nickname ?: "—"}", "Reporter: ${report.reporter.nickname} · Target: ${report.targetUser?.nickname ?: "—"}"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (report.status == "pending") {
                    TextButton(onClick = onResolve, enabled = !busy) { Text(nativePhrase("处理", "Resolve"), color = HlovetUi.accent, fontSize = 11.sp) }
                    TextButton(onClick = onReject, enabled = !busy) { Text(nativePhrase("驳回", "Reject"), color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
                }
            }
        }
    }
}

/** 展示 report 的处理上下文；session 沿用后台附件权限，onDismiss 关闭当前详情。 */
@Composable
private fun NativeAdminReportDetailDialog(report: NativeModerationReport, session: NativeSession?, onDismiss: () -> Unit) {
    // 详情使用统一有界滚动弹窗，长消息不推挤关闭操作。
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(adminReportIcon(report.source), null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(adminReportSourceLabel(report.source)) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeAdminReportDetailLine(nativePhrase("状态", "Status"), adminReportStatusLabel(report.status))
                NativeAdminReportDetailLine(nativePhrase("提交时间", "Submitted"), adminReportTime(report.createdAt))
                report.handledAt?.let { NativeAdminReportDetailLine(nativePhrase("处理时间", "Handled"), adminReportTime(it)) }
                NativeAdminReportDetailLine(nativePhrase("举报原因", "Reason"), adminReportReason(report.reason))
                NativeAdminReportDetailLine(nativePhrase("举报人", "Reporter"), "${report.reporter.nickname} (@${report.reporter.username})")
                report.targetUser?.let { NativeAdminReportDetailLine(nativePhrase("涉及用户", "Target user"), "${it.nickname} (@${it.username})") }
                report.article?.let { NativeAdminReportDetailLine(nativePhrase("文章", "Article"), it.title) }
                report.comment?.let { NativeAdminReportDetailLine(nativePhrase("被举报评论", "Reported comment"), it.body.ifBlank { nativePhrase("（评论内容为空）", "(Empty comment)") }) }
                report.group?.let { NativeAdminReportDetailLine(nativePhrase("群聊", "Group"), it.name) }
                report.message?.let {
                    Text(nativePhrase("被举报消息", "Reported message"), color = HlovetUi.muted, fontSize = 10.sp)
                    NativeReportMessagePreview(report, session)
                }
                report.detail?.takeIf(String::isNotBlank)?.let { NativeAdminReportDetailLine(nativePhrase("补充说明", "Additional details"), it) }
                report.resolution?.takeIf(String::isNotBlank)?.let { NativeAdminReportDetailLine(nativePhrase("处理反馈", "Resolution"), it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(nativePhrase("关闭", "Close"), color = HlovetUi.accent) } },
    )
}

/** 展示 label 对应的完整 value，空值显示占位；正文由外层统一弹窗滚动，不截断举报证据。 */
@Composable
private fun NativeAdminReportDetailLine(label: String, value: String) {
    // 长评论与处理反馈必须完整可读，不能在第五行省略关键上下文。
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
        Text(value.ifBlank { "—" }, fontSize = 12.sp)
    }
}

@Composable
private fun NativeAdminReportFilter(label: String, selected: Boolean, onClick: () -> Unit) { FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 10.sp) }, border = null) }

/** 展示 label 对应的 value 汇总，color 区分统计层级，业务标签限定完整指标而非任意同名文字。 */
@Composable
private fun NativeAdminReportMetric(label: String, value: Int, color: Color) {
    // 数值和标签归属于同一指标，避免与筛选按钮、列表状态混淆。
    Column(Modifier.testTag("admin-report-metric-$label"), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value.toString(), color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = HlovetUi.muted, fontSize = 10.sp)
    }
}

@Composable
private fun NativeAdminReportsMessage(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = if (error) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = HlovetUi.muted, fontSize = 12.sp) } } }

@Composable
private fun nativeAdminReportsFieldColors() = HlovetUi.fieldColors()

private fun adminReportTitle(report: NativeModerationReport): String = when (report.source) { "article" -> report.article?.title ?: nativePhrase("文章内容", "Article content"); "comment" -> report.comment?.body?.ifBlank { nativePhrase("评论内容", "Comment") } ?: nativePhrase("评论内容", "Comment"); else -> report.message?.body?.ifBlank { report.message.attachments.firstOrNull()?.originalName ?: nativePhrase("群消息内容", "Group message") } ?: nativePhrase("群消息内容", "Group message") }
private fun adminReportSourceLabel(source: String): String = when (source) { "article" -> nativePhrase("文章举报", "Article report"); "comment" -> nativePhrase("评论举报", "Comment report"); "group_message" -> nativePhrase("群消息举报", "Group-message report"); else -> nativePhrase("举报记录", "Report") }
private fun adminReportReason(reason: String): String = when (reason) { "spam" -> nativePhrase("垃圾广告", "Spam"); "harassment" -> nativePhrase("辱骂骚扰", "Harassment"); "illegal" -> nativePhrase("违法违规", "Illegal content"); "privacy" -> nativePhrase("隐私泄露", "Privacy leak"); "misinformation" -> nativePhrase("不实内容", "Misinformation"); "other" -> nativePhrase("其他", "Other"); else -> reason.ifBlank { nativePhrase("其他", "Other") } }
private fun adminReportStatusLabel(status: String): String = when (status) { "resolved" -> nativePhrase("已处理", "Resolved"); "rejected" -> nativePhrase("已驳回", "Rejected"); else -> nativePhrase("待处理", "Pending") }
private fun adminReportStatusColor(status: String): Color = when (status) { "resolved" -> HlovetUi.accent; "rejected" -> HlovetUi.secondaryAccent; else -> Color(0xFFB7791F) }
private fun adminReportIcon(source: String) = when (source) { "comment", "group_message" -> Icons.Filled.ChatBubbleOutline; else -> Icons.Filled.Report }
private fun adminReportTime(value: String): String = when { value.length >= 16 -> value.substring(0, 16).replace('T', ' '); value.isBlank() -> ""; else -> value }

@Composable
private fun NativeModerationOverviewCard(value: NativeModerationOverview) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeAdminReportMetric(nativePhrase("待处理", "Pending"), value.pending, HlovetUi.secondaryAccent)
                NativeAdminReportMetric(nativePhrase("已超时", "Overdue"), value.overdue, Color(0xFFB7791F))
                NativeAdminReportMetric(nativePhrase("已处理", "Resolved"), value.resolved, HlovetUi.accent)
                NativeAdminReportMetric(nativePhrase("近 7 日命中", "Hits in 7 days"), value.ruleHitsLast7Days, HlovetUi.accent)
            }
            Text(nativePhrase("共 ${value.total} 条 · 已驳回 ${value.rejected} · 平均处理 ${value.averageHandleMinutes?.let { "$it 分钟" } ?: "暂无"} · 近 30 日规则命中 ${value.ruleHitsLast30Days} 次", "${value.total} total · ${value.rejected} rejected · Average handling ${value.averageHandleMinutes?.let { "$it min" } ?: "n/a"} · ${value.ruleHitsLast30Days} rule hits in 30 days"), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 15.sp)
            value.sources.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(adminReportSourceLabel(source.source), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(nativePhrase("${source.pending} 待处理 · ${source.overdue} 超时 · ${source.total} 总数", "${source.pending} pending · ${source.overdue} overdue · ${source.total} total"), color = HlovetUi.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun NativeModerationRuleHitRow(hit: NativeModerationRuleHit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(moderationRuleTypeLabel(hit.ruleType), color = HlovetUi.accent, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(hit.ruleName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(adminReportTime(hit.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
            }
            Text("${nativePhrase("${hit.detail}：", "${hit.detail}: ")}${hit.contentPreview}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${hit.actorName} · @${hit.username} · ${adminReportSourceLabel(hit.source)} · ${if (hit.action == "block") nativePhrase("已拦截", "Blocked") else nativePhrase("记录放行", "Recorded")}", color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NativeModerationSettingsCard(value: NativeModerationSettings, busy: Boolean, onSave: (NativeModerationSettings) -> Unit) {
    var draft by remember(value.updatedAt) { mutableStateOf(value) }
    var deadlineText by remember(value.updatedAt) { mutableStateOf(value.deadlineHours.toString()) }
    var reminderText by remember(value.updatedAt) { mutableStateOf(value.reminderLeadHours.toString()) }
    val deadline = deadlineText.toIntOrNull()
    val reminderLead = reminderText.toIntOrNull()
    val valid = deadline != null && deadline in 1..168 && reminderLead != null && reminderLead >= 0 && reminderLead < deadline
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nativePhrase("处理时限与提醒", "Deadlines and reminders"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(nativePhrase("系统会依据待处理举报的创建时间标记超时；自动提醒每阶段只发送一次。", "Pending reports are marked overdue from their creation time; each reminder is sent once per stage."), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(deadlineText, { deadlineText = it.filter(Char::isDigit).take(3) }, modifier = Modifier.weight(1f), enabled = !busy, singleLine = true, label = { Text(nativePhrase("处理时限（小时）", "Deadline (hours)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeAdminReportsFieldColors())
                OutlinedTextField(reminderText, { reminderText = it.filter(Char::isDigit).take(3) }, modifier = Modifier.weight(1f), enabled = !busy, singleLine = true, label = { Text(nativePhrase("提前提醒（小时）", "Remind before (hours)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeAdminReportsFieldColors())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HlovetCheckbox(checked = draft.automaticRemindersEnabled, onCheckedChange = { draft = draft.copy(automaticRemindersEnabled = it) }, enabled = !busy)
                Text(nativePhrase("启用自动提醒", "Enable automatic reminders"), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (deadline != null && reminderLead != null) onSave(draft.copy(deadlineHours = deadline, reminderLeadHours = reminderLead)) }, enabled = !busy && valid) { Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存设置", "Save settings"), color = HlovetUi.accent) }
            }
            if (!valid) Text(nativePhrase("提前提醒时间必须小于处理时限。", "The reminder lead time must be shorter than the deadline."), color = HlovetUi.secondaryAccent, fontSize = 10.sp)
        }
    }
}

@Composable
private fun NativeModerationRuleRow(rule: NativeModerationRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rule.enabled) nativePhrase("启用", "Enabled") else nativePhrase("停用", "Disabled"), color = if (rule.enabled) HlovetUi.accent else HlovetUi.muted, fontSize = 10.sp)
            }
            Text("${moderationRuleTypeLabel(rule.type)} · ${if (rule.action == "block") nativePhrase("记录并拦截", "Record and block") else nativePhrase("记录放行", "Record only")} · ${rule.sources.joinToString(nativePhrase("、", ", ")) { adminReportSourceLabel(it).removeSuffix(nativePhrase("举报", " report")) } }", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (rule.type == "sensitive_word") Text(nativePhrase("关键词：", "Keywords: ") + rule.keywords, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (rule.type != "sensitive_word") Text(nativePhrase("阈值 ${rule.threshold} 次 / ${rule.windowSeconds} 秒", "Threshold ${rule.threshold} times / ${rule.windowSeconds} seconds"), color = HlovetUi.muted, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text(nativePhrase("编辑", "Edit"), fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun NativeModerationTemplateRow(template: NativeModerationTemplate, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${if (template.status == "resolved") nativePhrase("处理", "Resolve") else nativePhrase("驳回", "Reject")} · ${if (template.enabled) nativePhrase("启用", "Enabled") else nativePhrase("停用", "Disabled")}", color = HlovetUi.muted, fontSize = 10.sp)
            }
            Text(template.content, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text(nativePhrase("编辑", "Edit"), fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text(nativePhrase("删除", "Delete"), color = HlovetUi.secondaryAccent, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun NativeReportTemplatePicker(templates: List<NativeModerationTemplate>, status: String, selectedId: String, onSelect: (NativeModerationTemplate?) -> Unit) {
    val eligible = templates.filter { it.enabled && it.status == status }
    if (eligible.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(nativePhrase("处理模板", "Response templates"), color = HlovetUi.muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            eligible.forEach { template -> NativeAdminReportFilter(template.name, selectedId == template.id.toString()) { onSelect(template) } }
            NativeAdminReportFilter(nativePhrase("手动填写", "Write manually"), selectedId == "manual") { onSelect(null) }
        }
    }
}

@Composable
private fun NativeModerationRuleDialog(initial: NativeModerationRule?, busy: Boolean, onDismiss: () -> Unit, onSave: (NativeModerationRuleInput) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: "sensitive_word") }
    var action by remember(initial?.id) { mutableStateOf(initial?.action ?: "record") }
    var sources by remember(initial?.id) { mutableStateOf(initial?.sources?.toSet() ?: setOf("article", "comment")) }
    var keywords by remember(initial?.id) { mutableStateOf(initial?.keywords.orEmpty()) }
    var threshold by remember(initial?.id) { mutableStateOf((initial?.threshold ?: 1).toString()) }
    var windowSeconds by remember(initial?.id) { mutableStateOf((initial?.windowSeconds ?: 60).toString()) }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    val thresholdValue = threshold.toIntOrNull()
    val windowValue = windowSeconds.toIntOrNull()
    val valid = name.trim().length >= 2 && sources.isNotEmpty() && (type != "sensitive_word" || keywords.isNotBlank()) && (thresholdValue ?: 0) in 1..500 && (windowValue ?: 0) in 10..86_400
    HlovetAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) nativePhrase("新增治理规则", "Add moderation rule") else nativePhrase("编辑治理规则", "Edit moderation rule")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("规则名称", "Rule name")) }, enabled = !busy, colors = nativeAdminReportsFieldColors())
                Text(nativePhrase("规则类型", "Rule type"), color = HlovetUi.muted, fontSize = 10.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("sensitive_word", "link_rate", "duplicate_content", "high_frequency").forEach { candidate -> NativeAdminReportFilter(moderationRuleTypeLabel(candidate), type == candidate) { if (initial == null) type = candidate } }
                }
                Text(nativePhrase("命中动作", "Match action"), color = HlovetUi.muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NativeAdminReportFilter(nativePhrase("记录放行", "Record only"), action == "record") { action = "record" }
                    NativeAdminReportFilter(nativePhrase("记录并拦截", "Record and block"), action == "block") { action = "block" }
                }
                Text(nativePhrase("适用内容", "Applies to"), color = HlovetUi.muted, fontSize = 10.sp)
                listOf("article" to nativePhrase("文章", "Articles"), "comment" to nativePhrase("评论", "Comments"), "group_message" to nativePhrase("群消息", "Group messages")).forEach { (source, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HlovetCheckbox(checked = source in sources, onCheckedChange = { checked -> sources = if (checked) sources + source else sources - source }, enabled = !busy)
                        Text(label, fontSize = 11.sp)
                    }
                }
                if (type == "sensitive_word") OutlinedTextField(keywords, { keywords = it.take(4000) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text(nativePhrase("关键词（逗号或换行分隔）", "Keywords (comma or newline separated)")) }, enabled = !busy, colors = nativeAdminReportsFieldColors())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(threshold, { threshold = it.filter(Char::isDigit).take(3) }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(nativePhrase("阈值", "Threshold")) }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeAdminReportsFieldColors())
                    OutlinedTextField(windowSeconds, { windowSeconds = it.filter(Char::isDigit).take(5) }, modifier = Modifier.weight(1f), singleLine = true, label = { Text(nativePhrase("窗口（秒）", "Window (seconds)")) }, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeAdminReportsFieldColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) { HlovetCheckbox(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy); Text(nativePhrase("启用规则", "Enable rule"), fontSize = 11.sp) }
                if (!valid) Text(nativePhrase("名称、适用内容和阈值必须有效；敏感词规则还需要关键词。", "Name, scope, and thresholds must be valid; sensitive-word rules also need keywords."), color = HlovetUi.secondaryAccent, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(NativeModerationRuleInput(name, type, action, sources.toList(), keywords, thresholdValue ?: 1, windowValue ?: 60, enabled)) }, enabled = valid && !busy) { Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存", "Save"), color = HlovetUi.accent) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
    )
}

@Composable
private fun NativeModerationTemplateDialog(initial: NativeModerationTemplate?, busy: Boolean, onDismiss: () -> Unit, onSave: (NativeModerationTemplateInput) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var status by remember(initial?.id) { mutableStateOf(initial?.status ?: "resolved") }
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    val valid = name.trim().length >= 2 && content.trim().length >= 2
    HlovetAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) nativePhrase("新增处理模板", "Add response template") else nativePhrase("编辑处理模板", "Edit response template")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("模板名称", "Template name")) }, enabled = !busy, colors = nativeAdminReportsFieldColors())
                Text(nativePhrase("适用结果", "Applies to result"), color = HlovetUi.muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { NativeAdminReportFilter(nativePhrase("处理", "Resolve"), status == "resolved") { status = "resolved" }; NativeAdminReportFilter(nativePhrase("驳回", "Reject"), status == "rejected") { status = "rejected" } }
                OutlinedTextField(content, { content = it.take(500) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, label = { Text(nativePhrase("处理反馈内容", "Resolution message")) }, enabled = !busy, colors = nativeAdminReportsFieldColors())
                Row(verticalAlignment = Alignment.CenterVertically) { HlovetCheckbox(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy); Text(nativePhrase("启用模板", "Enable template"), fontSize = 11.sp) }
                if (!valid) Text(nativePhrase("模板名称和反馈内容至少填写 2 个字符。", "Template name and feedback must each contain at least 2 characters."), color = HlovetUi.secondaryAccent, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(NativeModerationTemplateInput(name, status, content, enabled)) }, enabled = valid && !busy) { Text(if (busy) nativePhrase("保存中…", "Saving…") else nativePhrase("保存", "Save"), color = HlovetUi.accent) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
    )
}

private fun moderationRuleTypeLabel(type: String): String = when (type) {
    "sensitive_word" -> nativePhrase("敏感词", "Sensitive words")
    "link_rate" -> nativePhrase("链接频率", "Link frequency")
    "duplicate_content" -> nativePhrase("重复内容", "Duplicate content")
    "high_frequency" -> nativePhrase("高频发言", "High-frequency posts")
    else -> type
}
