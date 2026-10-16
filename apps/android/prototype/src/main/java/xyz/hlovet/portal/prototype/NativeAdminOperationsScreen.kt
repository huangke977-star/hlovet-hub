@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private sealed interface NativeOperationsConfirmAction {
    data object AlertCheck : NativeOperationsConfirmAction
    data object DependencyReview : NativeOperationsConfirmAction
    data object QualityCheck : NativeOperationsConfirmAction
    data object BrowserAcceptance : NativeOperationsConfirmAction
    data object AuditCleanup : NativeOperationsConfirmAction
    data class Recovery(val provider: String, val label: String) : NativeOperationsConfirmAction
    data class LoadTest(val paths: List<String>, val concurrency: Int, val durationSeconds: Int) : NativeOperationsConfirmAction
    data class AlertAction(val alertId: Int, val resolve: Boolean) : NativeOperationsConfirmAction
}

@Composable
internal fun NativeAdminOperationsScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    key(session?.accessToken, session?.isSuperAdmin) { NativeAdminOperationsContent(session, onBack) }
}

@Composable
private fun NativeAdminOperationsContent(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val context = LocalContext.current
    var overview by remember(token) { mutableStateOf<NativeOperationsOverview?>(null) }
    var quality by remember(token) { mutableStateOf<NativeQualityOverview?>(null) }
    var browserRun by remember(token) { mutableStateOf<NativeP22BrowserAcceptanceResult?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var qualityError by remember { mutableStateOf<String?>(null) }
    var qualityLoading by remember { mutableStateOf(false) }
    var qualityReloadKey by remember { mutableIntStateOf(0) }
    var runsError by remember { mutableStateOf<String?>(null) }
    var feedbackKey by remember { mutableStateOf<String?>(null) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var busy by remember(token) { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var confirmAction by remember { mutableStateOf<NativeOperationsConfirmAction?>(null) }
    var selectedRun by remember { mutableStateOf<NativeOperationalRun?>(null) }
    var selectedPaths by remember { mutableStateOf(setOf("/health")) }
    var concurrencyText by remember { mutableStateOf("2") }
    var durationText by remember { mutableStateOf("10") }
    var cleanupEnabled by remember(token) { mutableStateOf(true) }
    var businessDaysText by remember(token) { mutableStateOf("180") }
    var securityDaysText by remember(token) { mutableStateOf("365") }
    var serverDaysText by remember(token) { mutableStateOf("90") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            overview = null
            quality = null
            browserRun = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { NativeAdminOperationsApi.getOverview(token) }
        }.onSuccess { currentOverview ->
            val previousForm = overview?.auditPolicy?.toForm()
            val currentForm = NativeAuditPolicyForm(cleanupEnabled, businessDaysText, securityDaysText, serverDaysText)
            overview = currentOverview
            selectedPaths = selectedPaths.intersect(currentOverview.loadTestTargets.map { it.path }.toSet())
            if (previousForm == null || currentForm == previousForm) {
                cleanupEnabled = currentOverview.auditPolicy.cleanupEnabled
                businessDaysText = currentOverview.auditPolicy.businessDays.toString()
                securityDaysText = currentOverview.auditPolicy.securityDays.toString()
                serverDaysText = currentOverview.auditPolicy.serverDays.toString()
            }
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            loadError = exception.message ?: nativePhrase("运营状态读取失败，请重试。", "Could not load operations data. Please retry.")
        }
        loading = false
    }

    LaunchedEffect(token, canAccess, reloadKey, qualityReloadKey) {
        if (token.isNullOrBlank() || !canAccess) return@LaunchedEffect
        qualityLoading = true; qualityError = null
        try { quality = withContext(Dispatchers.IO) { NativeAdminOperationsApi.getQualityOverview(token) } }
        catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { qualityError = exception.message ?: nativePhrase("生产质量读取失败，请重试。", "Could not load production quality. Please retry.") }
        finally { qualityLoading = false }
    }

    val pollingRequired = overview?.runs?.any { it.status == "running" } == true
    LaunchedEffect(token, canAccess, pollingRequired) {
        if (token.isNullOrBlank() || !canAccess || !pollingRequired) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2_000)
            try {
                val runs = withContext(Dispatchers.IO) { NativeAdminOperationsApi.listRuns(token) }
                overview = overview?.copy(runs = runs)
                selectedRun?.let { selected -> runs.find { it.id == selected.id }?.let { selectedRun = it } }
                runsError = null
                if (runs.none { it.status == "running" }) { reloadKey++; break }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                runsError = exception.message ?: nativePhrase("运行进度读取失败，将继续重试。", "Could not refresh run progress. Retrying.")
                if (exception is NativeAdminOperationsHttpException && exception.statusCode in listOf(401, 403)) break
            }
        }
    }

    fun recordRun(run: NativeOperationalRun) {
        overview = overview?.let { it.copy(runs = listOf(run) + it.runs.filterNot { previous -> previous.id == run.id }) }
        if (run.kind == "quality_check") quality = quality?.copy(latestRun = run)
    }

    fun execute(action: NativeOperationsConfirmAction) {
        val currentToken = token ?: return
        if (!canAccess || busy != null || loading) return
        feedbackKey = when (action) {
            NativeOperationsConfirmAction.AlertCheck, NativeOperationsConfirmAction.DependencyReview -> "checks"
            NativeOperationsConfirmAction.QualityCheck, NativeOperationsConfirmAction.BrowserAcceptance -> "quality"
            NativeOperationsConfirmAction.AuditCleanup -> "audit"
            is NativeOperationsConfirmAction.Recovery -> "recovery"
            is NativeOperationsConfirmAction.LoadTest -> "load-test"
            is NativeOperationsConfirmAction.AlertAction -> "alerts"
        }
        busy = when (action) {
            NativeOperationsConfirmAction.AlertCheck -> "alert-check"
            NativeOperationsConfirmAction.DependencyReview -> "dependency-review"
            NativeOperationsConfirmAction.QualityCheck -> "quality-check"
            NativeOperationsConfirmAction.BrowserAcceptance -> "browser-acceptance"
            NativeOperationsConfirmAction.AuditCleanup -> "audit-cleanup"
            is NativeOperationsConfirmAction.Recovery -> "recovery:${action.provider}"
            is NativeOperationsConfirmAction.LoadTest -> "load-test"
            is NativeOperationsConfirmAction.AlertAction -> "alert:${action.alertId}"
        }
        error = null
        notice = null
        scope.launch {
            try {
                when (action) {
                    NativeOperationsConfirmAction.AlertCheck -> {
                        val run = withContext(Dispatchers.IO) { NativeAdminOperationsApi.runAlertCheck(currentToken) }
                        recordRun(run)
                        notice = nativePhrase("告警检查${operationRunResultText(run)}。", "Alert check ${operationRunResultText(run)}.")
                    }
                    NativeOperationsConfirmAction.DependencyReview -> {
                        val run = withContext(Dispatchers.IO) { NativeAdminOperationsApi.runDependencyReview(currentToken) }
                        recordRun(run)
                        notice = nativePhrase("依赖评估${operationRunResultText(run)}。", "Dependency review ${operationRunResultText(run)}.")
                    }
                    NativeOperationsConfirmAction.QualityCheck -> {
                        val run = withContext(Dispatchers.IO) { NativeAdminOperationsApi.runQualityCheck(currentToken) }
                        recordRun(run)
                        notice = nativePhrase("质量检查${operationRunResultText(run)}。", "Quality check ${operationRunResultText(run)}.")
                    }
                    NativeOperationsConfirmAction.BrowserAcceptance -> {
                        val result = NativeP22BrowserAcceptance.run(context)
                        browserRun = result
                        notice = if (result.passed) {
                            nativePhrase("浏览器验收全部通过。", "Browser acceptance passed.")
                        } else {
                            nativePhrase("浏览器验收发现问题，请查看逐项结果。", "Browser acceptance found issues; review the individual results.")
                        }
                    }
                    NativeOperationsConfirmAction.AuditCleanup -> {
                        val result = withContext(Dispatchers.IO) { NativeAdminOperationsApi.cleanupAuditLogs(currentToken) }
                        val previous = overview?.auditPolicy?.toForm()
                        overview = overview?.copy(auditPolicy = result.policy)
                        if (NativeAuditPolicyForm(cleanupEnabled, businessDaysText, securityDaysText, serverDaysText) == previous) {
                            cleanupEnabled = result.policy.cleanupEnabled; businessDaysText = result.policy.businessDays.toString(); securityDaysText = result.policy.securityDays.toString(); serverDaysText = result.policy.serverDays.toString()
                        }
                        notice = nativePhrase("审计日志清理完成，共删除 ${result.deletedCount} 条记录。", "Audit log cleanup completed; ${result.deletedCount} records deleted.")
                    }
                    is NativeOperationsConfirmAction.Recovery -> {
                        val run = withContext(Dispatchers.IO) { NativeAdminOperationsApi.startRecoveryDrill(currentToken, action.provider) }
                        recordRun(run)
                        notice = nativePhrase("${action.label}${operationRunResultText(run)}。", "${action.label} ${operationRunResultText(run)}.")
                    }
                    is NativeOperationsConfirmAction.LoadTest -> {
                        val run = withContext(Dispatchers.IO) {
                            NativeAdminOperationsApi.startLoadTest(currentToken, action.paths, action.concurrency, action.durationSeconds)
                        }
                        recordRun(run)
                        notice = nativePhrase("只读压测${operationRunResultText(run)}，记录 #${run.id}。", "Read-only load test ${operationRunResultText(run)}; run #${run.id}.")
                    }
                    is NativeOperationsConfirmAction.AlertAction -> {
                        val alert = withContext(Dispatchers.IO) {
                            if (action.resolve) {
                                NativeAdminOperationsApi.resolveAlert(currentToken, action.alertId)
                            } else {
                                NativeAdminOperationsApi.acknowledgeAlert(currentToken, action.alertId)
                            }
                        }
                        overview = overview?.let { current -> current.copy(alerts = current.alerts.map { if (it.id == alert.id) alert else it }.filterNot { it.status == "resolved" }) }
                        notice = if (action.resolve) nativePhrase("告警已标记为已解决。", "Alert marked as resolved.") else nativePhrase("告警已确认，记录仍会保留。", "Alert acknowledged; the record remains available.")
                        if (alert.status == "resolved") notice = nativePhrase("告警已标记为已解决。", "Alert marked as resolved.")
                    }
                }
                confirmAction = null
                if (action !is NativeOperationsConfirmAction.AlertAction && action != NativeOperationsConfirmAction.BrowserAcceptance) reloadKey += 1
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("操作执行失败，请稍后重试。", "Operation failed. Please try again.")
            } finally {
                busy = null
            }
        }
    }

    fun saveAuditPolicy() {
        val currentToken = token ?: return
        if (!canAccess || busy != null || loading) return
        feedbackKey = "audit"; error = null; notice = null
        val values = try {
            Triple(nativeOperationsNumber(businessDaysText, nativePhrase("业务留存天数", "Business retention days"), 7, 3650), nativeOperationsNumber(securityDaysText, nativePhrase("安全留存天数", "Security retention days"), 7, 3650), nativeOperationsNumber(serverDaysText, nativePhrase("服务器留存天数", "Server retention days"), 7, 3650))
        } catch (exception: IllegalArgumentException) { error = exception.message; return }
        val submittedCleanupEnabled = cleanupEnabled
        busy = "audit-policy"
        error = null
        notice = null
        scope.launch {
            try {
                val policy = withContext(Dispatchers.IO) {
                    NativeAdminOperationsApi.updateAuditPolicy(
                        accessToken = currentToken,
                        cleanupEnabled = submittedCleanupEnabled,
                        businessDays = values.first,
                        securityDays = values.second,
                        serverDays = values.third,
                    )
                }
                overview = overview?.copy(auditPolicy = policy)
                cleanupEnabled = policy.cleanupEnabled
                businessDaysText = policy.businessDays.toString()
                securityDaysText = policy.securityDays.toString()
                serverDaysText = policy.serverDays.toString()
                notice = nativePhrase("审计留存策略已保存。", "Audit retention policy saved.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("审计留存策略保存失败。", "Could not save the audit retention policy.")
            } finally {
                busy = null
            }
        }
    }

    fun prepareLoadTest() {
        if (!canAccess || loading || busy != null) return
        feedbackKey = "load-test"; error = null; notice = null
        try {
            confirmAction = NativeOperationsConfirmAction.LoadTest(
                nativeOperationsPaths(selectedPaths, overview?.loadTestTargets.orEmpty()),
                nativeOperationsNumber(concurrencyText, nativePhrase("并发", "Concurrency"), 1, 50),
                nativeOperationsNumber(durationText, nativePhrase("秒数", "Seconds"), 1, 300),
            )
        } catch (exception: IllegalArgumentException) { error = exception.message }
    }

    fun openConfirmation(action: NativeOperationsConfirmAction) { if (canAccess && !loading && busy == null) { error = null; notice = null; confirmAction = action } }
    val controlsBusy = busy ?: if (loading) "loading" else null

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("operations-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("运营与质量", "Operations and quality"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("运营韧性与生产质量", "Operational resilience and production quality"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }, enabled = canAccess && !loading && busy == null && !token.isNullOrBlank()) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = if (loading) HlovetUi.muted else HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (token.isNullOrBlank()) {
            item(key = "access") { OperationsMessageCard(nativePhrase("登录后查看运营与质量数据。", "Sign in to view operations and quality data."), Icons.Filled.Shield) }
        } else if (!canAccess) {
            item(key = "access") { OperationsMessageCard(nativePhrase("运营与质量仅超级管理员可访问。", "Only super administrators can manage operations and quality."), Icons.Filled.Shield) }
        } else {
            if (loading && overview == null) item(key = "loading") { OperationsMessageCard(nativePhrase("正在读取运营与质量状态…", "Loading operations and quality status..."), Icons.Filled.Info) }
            if (loadError != null) item(key = "load-error") {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Text(loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        TextButton(onClick = { reloadKey++ }, enabled = busy == null && !loading) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                    }
                }
            }
            overview?.let { current ->
                item(key = "checks") { OperationsActionCard(busy = controlsBusy, onAction = ::openConfirmation, error = error.takeIf { feedbackKey == "checks" }, notice = notice.takeIf { feedbackKey == "checks" }) }
                item(key = "storage") { ExternalStorageCard(current.externalStorage) }
                item(key = "recovery") {
                    RecoveryTargetsCard(
                        targets = current.recoveryTargets,
                        storage = current.externalStorage,
                        busy = controlsBusy,
                        onAction = ::openConfirmation,
                        error = error.takeIf { feedbackKey == "recovery" }, notice = notice.takeIf { feedbackKey == "recovery" },
                    )
                }
                item(key = "load-test") {
                    LoadTestCard(
                        targets = current.loadTestTargets,
                        selectedPaths = selectedPaths,
                        onTogglePath = { path -> selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path },
                        concurrencyText = concurrencyText,
                        onConcurrencyChange = { concurrencyText = it },
                        durationText = durationText,
                        onDurationChange = { durationText = it },
                        enabled = controlsBusy == null && current.runs.none { it.kind == "load_test" && it.status == "running" },
                        onStart = ::prepareLoadTest,
                        error = error.takeIf { feedbackKey == "load-test" }, notice = notice.takeIf { feedbackKey == "load-test" },
                    )
                }
                item(key = "alerts") { AlertsCard(current.alerts, controlsBusy, onAction = ::openConfirmation, error = error.takeIf { feedbackKey == "alerts" }, notice = notice.takeIf { feedbackKey == "alerts" }) }
                item(key = "dependencies") { DependencyCard(current.dependencyItems) }
                item(key = "audit") {
                    AuditRetentionCard(
                        policy = current.auditPolicy,
                        cleanupEnabled = cleanupEnabled,
                        onCleanupEnabledChange = { cleanupEnabled = it },
                        businessDays = businessDaysText,
                        onBusinessDaysChange = { businessDaysText = it },
                        securityDays = securityDaysText,
                        onSecurityDaysChange = { securityDaysText = it },
                        serverDays = serverDaysText,
                        onServerDaysChange = { serverDaysText = it },
                        busy = controlsBusy,
                        onSave = ::saveAuditPolicy,
                        onCleanup = { openConfirmation(NativeOperationsConfirmAction.AuditCleanup) },
                        error = error.takeIf { feedbackKey == "audit" }, notice = notice.takeIf { feedbackKey == "audit" },
                    )
                }
                item(key = "runs") { RunsCard(current.runs, onOpen = { selectedRun = it }, error = runsError) }
            }
            item(key = "quality") {
                val current = quality
                if (current != null) {
                    QualityCard(
                        quality = current,
                        busy = controlsBusy ?: if (qualityLoading) "loading" else null,
                        browserRun = browserRun,
                        onRun = { openConfirmation(NativeOperationsConfirmAction.QualityCheck) },
                        onBrowserRun = { openConfirmation(NativeOperationsConfirmAction.BrowserAcceptance) },
                        error = error.takeIf { feedbackKey == "quality" } ?: qualityError, notice = notice.takeIf { feedbackKey == "quality" },
                    )
                } else {
                    OperationsPanel(Icons.Filled.Security, nativePhrase("生产质量", "Production quality"), nativePhrase("待检查", "Not checked"), false, modifier = Modifier.testTag("operations-quality-card")) {
                        if (qualityLoading) Text(nativePhrase("正在读取生产质量…", "Loading production quality..."), color = HlovetUi.muted, fontSize = 11.sp)
                        HlovetFormFeedback(qualityError)
                        if (qualityError != null) TextButton(onClick = { qualityReloadKey++ }, enabled = !qualityLoading) { Text(nativePhrase("重试", "Retry"), fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    confirmAction?.let { action ->
        OperationsConfirmDialog(
            action = action,
            busy = busy != null,
            error = error,
            onDismiss = { if (busy == null) confirmAction = null },
            onConfirm = { execute(action) },
        )
    }

    selectedRun?.let { run ->
        OperationalRunDialog(run = run, error = runsError, onDismiss = { selectedRun = null })
    }
}

@Composable
private fun OperationsActionCard(
    busy: String?,
    onAction: (NativeOperationsConfirmAction) -> Unit,
    error: String?, notice: String?,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("operations-checks-card"), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
            Text(nativePhrase("运营检查", "Operations checks"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(nativePhrase("所有操作会记录运行结果", "All actions record a run result"), color = HlovetUi.muted, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onAction(NativeOperationsConfirmAction.AlertCheck) }, enabled = busy == null) { Text(if (busy == "alert-check") nativePhrase("检查中…", "Checking…") else nativePhrase("检查告警", "Check alerts"), fontSize = 11.sp) }
                TextButton(onClick = { onAction(NativeOperationsConfirmAction.DependencyReview) }, enabled = busy == null) { Text(if (busy == "dependency-review") nativePhrase("评估中…", "Reviewing…") else nativePhrase("依赖评估", "Dependencies"), fontSize = 11.sp) }
                TextButton(onClick = { onAction(NativeOperationsConfirmAction.QualityCheck) }, enabled = busy == null) { Text(if (busy == "quality-check") nativePhrase("检查中…", "Checking…") else nativePhrase("质量检查", "Quality check"), fontSize = 11.sp) }
            }
            HlovetFormFeedback(error, notice)
        }
    }
}

@Composable
private fun ExternalStorageCard(storage: NativeExternalStorage) {
    OperationsPanel(Icons.Filled.Cloud, nativePhrase("外部存储条件", "External storage"), if (storage.encryptionConfigured) nativePhrase("可评估", "Ready to assess") else nativePhrase("待配置", "Needs setup"), storage.encryptionConfigured) {
        OperationsStatusGrid(
            listOf(
                nativePhrase("备份加密", "Backup encryption") to if (storage.encryptionConfigured) nativePhrase("已配置", "Configured") else nativePhrase("未配置", "Not configured"),
                "Aliyun OSS" to if (storage.ossConfigured) nativePhrase("已配置", "Configured") else nativePhrase("未配置", "Not configured"),
                "Cloudflare R2" to if (storage.r2Configured) nativePhrase("已配置", "Configured") else nativePhrase("未配置", "Not configured"),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(nativePhrase(storage.message, if (storage.encryptionConfigured) "Configure remote storage in the backup policy. Unconfigured providers remain blocked." else "BACKUP_ENCRYPTION_KEY is missing; remote backup and recovery drills are unavailable."), color = HlovetUi.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun RecoveryTargetsCard(
    targets: List<NativeRecoveryTarget>,
    storage: NativeExternalStorage,
    busy: String?,
    onAction: (NativeOperationsConfirmAction) -> Unit,
    error: String?, notice: String?,
) {
    OperationsPanel(Icons.Filled.Restore, nativePhrase("恢复目标", "Recovery targets"), nativePhrase("可演练", "Ready for drill"), true, modifier = Modifier.testTag("operations-recovery-card")) {
        targets.forEach { target ->
            Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(target.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(target.current, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(target.target, color = HlovetUi.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(target.measurement, color = HlovetUi.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(nativePhrase("恢复演练", "Recovery drill"), color = HlovetUi.muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onAction(NativeOperationsConfirmAction.Recovery("local", nativePhrase("本地恢复演练", "Local recovery drill"))) },
                enabled = busy == null,
            ) { Text(if (busy == "recovery:local") nativePhrase("演练中…", "Running…") else nativePhrase("本地", "Local"), fontSize = 11.sp) }
            TextButton(
                onClick = { onAction(NativeOperationsConfirmAction.Recovery("oss", nativePhrase("OSS 恢复演练", "OSS recovery drill"))) },
                enabled = busy == null && storage.ossConfigured,
            ) { Text("OSS", fontSize = 11.sp) }
            TextButton(
                onClick = { onAction(NativeOperationsConfirmAction.Recovery("r2", nativePhrase("R2 恢复演练", "R2 recovery drill"))) },
                enabled = busy == null && storage.r2Configured,
            ) { Text("R2", fontSize = 11.sp) }
        }
        if (!storage.ossConfigured || !storage.r2Configured) {
            Text(nativePhrase("OSS/R2 未完成配置时按钮会保持禁用，不会伪造远端成功。", "OSS/R2 actions stay disabled until configured; remote success is never simulated."), color = HlovetUi.muted, fontSize = 9.sp)
        }
        HlovetFormFeedback(error, notice)
    }
}

@Composable
private fun LoadTestCard(
    targets: List<NativeLoadTestTarget>,
    selectedPaths: Set<String>,
    onTogglePath: (String) -> Unit,
    concurrencyText: String,
    onConcurrencyChange: (String) -> Unit,
    durationText: String,
    onDurationChange: (String) -> Unit,
    enabled: Boolean,
    onStart: () -> Unit,
    error: String?, notice: String?,
) {
    OperationsPanel(Icons.Filled.Settings, nativePhrase("只读压测", "Read-only load test"), nativePhrase("白名单", "Allowlist"), true, modifier = Modifier.testTag("operations-load-card")) {
        Text(nativePhrase("只允许探活、站点地图和 RSS 等公开只读接口；单次最多 50 并发、300 秒。", "Only public read-only endpoints such as health, sitemap, and RSS are allowed; each run supports up to 50 concurrent requests for 300 seconds."), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 15.sp)
        targets.forEach { target ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag("operations-target-${target.path}").clickable(enabled = enabled) { onTogglePath(target.path) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HlovetCheckbox(checked = target.path in selectedPaths, onCheckedChange = { if (enabled) onTogglePath(target.path) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase(target.label, target.labelEn), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(target.path, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = concurrencyText,
                onValueChange = onConcurrencyChange,
                label = { Text(nativePhrase("并发", "Concurrency")) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = HlovetUi.fieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
            OutlinedTextField(
                value = durationText,
                onValueChange = onDurationChange,
                label = { Text(nativePhrase("秒数", "Seconds")) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = HlovetUi.fieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
        }
        TextButton(onClick = onStart, enabled = enabled && selectedPaths.isNotEmpty()) {
            Text(nativePhrase("开始只读压测", "Start read-only load test"), fontSize = 12.sp)
        }
        HlovetFormFeedback(error, notice)
    }
}

@Composable
private fun AlertsCard(
    alerts: List<NativeOperationalAlert>,
    busy: String?,
    onAction: (NativeOperationsConfirmAction) -> Unit,
    error: String?, notice: String?,
) {
    OperationsPanel(Icons.Filled.Warning, nativePhrase("待处理告警", "Open alerts"), if (alerts.isEmpty()) nativePhrase("无异常", "No issues") else nativePhrase("${alerts.size} 项", "${alerts.size} items"), alerts.isEmpty(), modifier = Modifier.testTag("operations-alerts-card")) {
        HlovetFormFeedback(error, notice)
        if (alerts.isEmpty()) {
            Text(nativePhrase("当前没有未解决告警。", "There are no unresolved alerts."), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            alerts.forEach { alert ->
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("operations-alert-${alert.id}")) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (alert.severity == "critical") Icons.Filled.Error else Icons.Filled.Warning, contentDescription = null, tint = operationSeverityColor(alert.severity), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(alert.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(operationAlertStatus(alert.status), color = operationSeverityColor(alert.severity), fontSize = 10.sp)
                        }
                        Text(alert.message, color = HlovetUi.muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(nativePhrase("发生 ${alert.occurrenceCount} 次 · 最近 ${operationTime(alert.lastSeenAt)}", "${alert.occurrenceCount} occurrences · Last seen ${operationTime(alert.lastSeenAt)}"), color = HlovetUi.muted, fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (alert.status == "open") {
                                TextButton(onClick = { onAction(NativeOperationsConfirmAction.AlertAction(alert.id, false)) }, enabled = busy == null) { Text(nativePhrase("确认", "Acknowledge"), fontSize = 11.sp) }
                            }
                            if (alert.status != "resolved") {
                                TextButton(onClick = { onAction(NativeOperationsConfirmAction.AlertAction(alert.id, true)) }, enabled = busy == null) { Text(nativePhrase("解决", "Resolve"), fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DependencyCard(items: List<NativeDependencyItem>) {
    OperationsPanel(Icons.Filled.Build, nativePhrase("依赖评估", "Dependency review"), if (items.isEmpty()) nativePhrase("未读取", "Not read") else nativePhrase("${items.size} 项", "${items.size} items"), items.isNotEmpty(), modifier = Modifier.testTag("operations-dependencies-card")) {
        if (items.isEmpty()) {
            Text(nativePhrase("尚未读取依赖版本。点击上方“依赖评估”后再刷新。", "Dependency versions have not been read. Run Dependency review above, then refresh."), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("${item.category} · ${item.note}", color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.current.ifBlank { nativePhrase("未读取", "Not read") }, fontSize = 10.sp)
                        Text(operationDependencyStatus(item.status), color = operationDependencyColor(item.status), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRetentionCard(
    policy: NativeAuditPolicy,
    cleanupEnabled: Boolean,
    onCleanupEnabledChange: (Boolean) -> Unit,
    businessDays: String,
    onBusinessDaysChange: (String) -> Unit,
    securityDays: String,
    onSecurityDaysChange: (String) -> Unit,
    serverDays: String,
    onServerDaysChange: (String) -> Unit,
    busy: String?,
    onSave: () -> Unit,
    onCleanup: () -> Unit,
    error: String?, notice: String?,
) {
    OperationsPanel(Icons.Filled.Storage, nativePhrase("审计留存", "Audit retention"), if (cleanupEnabled) nativePhrase("自动清理已启用", "Automatic cleanup on") else nativePhrase("自动清理已关闭", "Automatic cleanup off"), cleanupEnabled, modifier = Modifier.testTag("operations-audit-card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HlovetCheckbox(checked = cleanupEnabled, onCheckedChange = { if (busy == null) onCleanupEnabledChange(it) }, enabled = busy == null)
            Text(nativePhrase("启用自动清理", "Enable automatic cleanup"), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AuditDaysField(nativePhrase("业务", "Business"), businessDays, onBusinessDaysChange, busy == null, Modifier.weight(1f))
            AuditDaysField(nativePhrase("安全", "Security"), securityDays, onSecurityDaysChange, busy == null, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AuditDaysField(nativePhrase("服务器", "Server"), serverDays, onServerDaysChange, busy == null, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        Text(
            nativePhrase("上次清理：${policy.lastCleanupAt?.let(::operationTime) ?: "尚未执行"} · 删除 ${policy.lastCleanupCount} 条", "Last cleanup: ${policy.lastCleanupAt?.let(::operationTime) ?: "Not run"} · ${policy.lastCleanupCount} deleted"),
            color = HlovetUi.muted,
            fontSize = 10.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton(onClick = onCleanup, enabled = busy == null) { Text(if (busy == "audit-cleanup") nativePhrase("清理中…", "Cleaning…") else nativePhrase("立即清理", "Clean now"), fontSize = 11.sp) }
            TextButton(onClick = onSave, enabled = busy == null) { Text(if (busy == "audit-policy") nativePhrase("保存中…", "Saving…") else nativePhrase("保存策略", "Save policy"), fontSize = 11.sp) }
        }
        HlovetFormFeedback(error, notice)
    }
}

@Composable
private fun AuditDaysField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        colors = HlovetUi.fieldColors(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
    )
}

@Composable
private fun RunsCard(
    runs: List<NativeOperationalRun>,
    onOpen: (NativeOperationalRun) -> Unit,
    error: String?,
) {
    OperationsPanel(Icons.Filled.Info, nativePhrase("最近运行记录", "Recent runs"), if (runs.isEmpty()) nativePhrase("暂无", "None") else nativePhrase("${runs.size} 条", "${runs.size} items"), true, modifier = Modifier.testTag("operations-runs-card")) {
        HlovetFormFeedback(error)
        if (runs.isEmpty()) {
            Text(nativePhrase("执行告警检查、恢复演练或质量检查后，结果会显示在这里。", "Results appear here after alert checks, recovery drills, or quality checks."), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            runs.forEach { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("operations-run-${run.id}").clickable { onOpen(run) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${operationRunKind(run.kind)} #${run.id}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(run.summary, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(operationTime(run.createdAt), color = HlovetUi.muted, fontSize = 9.sp)
                    }
                    Text(operationRunStatus(run.status), color = operationRunColor(run.status), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QualityCard(
    quality: NativeQualityOverview,
    busy: String?,
    browserRun: NativeP22BrowserAcceptanceResult?,
    onRun: () -> Unit,
    onBrowserRun: () -> Unit,
    error: String?, notice: String?,
) {
    OperationsPanel(Icons.Filled.Security, nativePhrase("生产质量", "Production quality"), quality.latestRun?.status?.let(::operationRunStatus) ?: nativePhrase("待检查", "Not checked"), quality.latestRun?.status == "passed", modifier = Modifier.testTag("operations-quality-card")) {
        HlovetFormFeedback(error, notice)
        Text(nativePhrase("检查数据库、Redis、迁移、媒体完整性和远端备份条件；未配置 OSS/R2 会明确标记为阻塞。", "Checks the database, Redis, migrations, media integrity, and remote backup prerequisites; missing OSS/R2 configuration is marked as blocked."), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(7.dp))
        quality.checks.forEach { check ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(operationQualityIcon(check.status), contentDescription = null, tint = operationQualityColor(check.status), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase(check.label, check.labelEn), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(nativePhrase(check.detail, check.detailEn), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(operationQualityStatus(check.status), color = operationQualityColor(check.status), fontSize = 9.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRun, enabled = busy == null) { Text(if (busy == "quality-check") nativePhrase("检查中…", "Checking...") else nativePhrase("重新执行质量检查", "Run quality check again"), fontSize = 11.sp) }
            TextButton(onClick = onBrowserRun, enabled = busy == null) { Text(if (busy == "browser-acceptance") nativePhrase("验收中…", "Checking...") else nativePhrase("浏览器验收", "Browser acceptance"), fontSize = 11.sp) }
        }
        browserRun?.let { result ->
            NativeP22BrowserAcceptanceCard(result)
        }
        if (quality.browserChecks.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(nativePhrase("浏览器验收脚本", "Browser acceptance scripts"), color = HlovetUi.muted, fontSize = 10.sp)
            quality.browserChecks.forEach { check ->
                Text("${nativePhrase(check.label, check.labelEn)} · ${check.command}", color = HlovetUi.muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun NativeP22BrowserAcceptanceCard(result: NativeP22BrowserAcceptanceResult) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            nativePhrase("最近一次浏览器验收", "Latest browser acceptance"),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Text(
            nativePhrase(
                if (result.passed) "全部通过 · ${result.generatedAt}" else "存在失败项 · ${result.generatedAt}",
                if (result.passed) "All passed · ${result.generatedAt}" else "Some checks failed · ${result.generatedAt}",
            ),
            color = if (result.passed) HlovetUi.accent else Color(0xFFB42318),
            fontSize = 10.sp,
        )
        NativeP22BrowserAcceptanceGroup(nativePhrase("发布契约", "Release contract"), result.contract)
        NativeP22BrowserAcceptanceGroup(nativePhrase("公开页面（桌面与移动）", "Public pages (desktop and mobile)"), result.pages)
    }
}

@Composable
private fun NativeP22BrowserAcceptanceGroup(
    title: String,
    checks: List<NativeP22BrowserCheckResult>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = HlovetUi.muted, fontSize = 10.sp)
        checks.forEach { check ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    operationQualityIcon(check.status),
                    contentDescription = null,
                    tint = operationQualityColor(check.status),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase(check.label, check.labelEn), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(nativePhrase(check.detail, check.detailEn), color = HlovetUi.muted, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text("${check.latencyMs} ms", color = HlovetUi.muted, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun OperationsStatusGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(label, color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.width(78.dp))
                Text(value, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OperationsPanel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.background(operationStatusColor(ok).copy(alpha = .12f), HlovetUi.rowShape).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = operationStatusColor(ok), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(status, color = operationStatusColor(ok), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun OperationsMessageCard(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OperationsConfirmDialog(
    action: NativeOperationsConfirmAction,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title: String
    val message: String
    val confirm: String
    when (action) {
        NativeOperationsConfirmAction.AlertCheck -> {
            title = nativePhrase("检查告警", "Check alerts")
            message = nativePhrase("将读取备份、磁盘和媒体存储状态，并更新告警记录。", "Backup, disk, and media storage status will be read and alert records updated.")
            confirm = nativePhrase("开始检查", "Start check")
        }
        NativeOperationsConfirmAction.DependencyReview -> {
            title = nativePhrase("依赖评估", "Dependency review")
            message = nativePhrase("将读取当前 API、Web 和容器依赖版本，并生成一条运行记录。", "Current API, Web, and container dependency versions will be read and a run record created.")
            confirm = nativePhrase("开始评估", "Start review")
        }
        NativeOperationsConfirmAction.QualityCheck -> {
            title = nativePhrase("生产质量检查", "Production quality check")
            message = nativePhrase("将检查数据库、Redis、迁移、媒体完整性和远端备份条件。", "The database, Redis, migrations, media integrity, and remote backup prerequisites will be checked.")
            confirm = nativePhrase("开始检查", "Start check")
        }
        NativeOperationsConfirmAction.BrowserAcceptance -> {
            title = nativePhrase("浏览器验收", "Browser acceptance")
            message = nativePhrase("将以只读方式检查公开契约和桌面/移动页面，不会写入站内业务数据。", "Read-only checks will cover public contracts and desktop/mobile pages without writing portal data.")
            confirm = nativePhrase("开始验收", "Start acceptance")
        }
        NativeOperationsConfirmAction.AuditCleanup -> {
            title = nativePhrase("立即清理审计日志", "Clean audit logs now")
            message = nativePhrase("系统会按当前业务、安全和服务器留存天数删除过期审计日志。删除后不能恢复，但不会影响文章、用户和聊天数据。", "Expired audit logs will be deleted according to the current business, security, and server retention periods. This cannot be undone and does not affect articles, users, or chats.")
            confirm = nativePhrase("确认清理", "Confirm cleanup")
        }
        is NativeOperationsConfirmAction.Recovery -> {
            title = action.label
            message = if (action.provider == "local") {
                nativePhrase("这是非破坏性演练：只创建并校验备份，不覆盖生产数据库。", "This is a non-destructive drill: it creates and verifies a backup without overwriting the production database.")
            } else {
                nativePhrase("当前远端演练会读取并校验归档结果，不会覆盖生产数据库。没有完成配置时会记录为阻塞。", "The remote drill reads and verifies the archive without overwriting the production database. Missing configuration is recorded as blocked.")
            }
            confirm = nativePhrase("确认演练", "Confirm drill")
        }
        is NativeOperationsConfirmAction.LoadTest -> {
            title = nativePhrase("开始只读压测", "Start read-only load test")
            message = nativePhrase("将对 ${action.paths.size} 个只读接口执行 ${action.concurrency} 并发、${action.durationSeconds} 秒请求。请确认当前服务器允许这次测试。", "${action.paths.size} read-only endpoints will receive ${action.concurrency} concurrent requests for ${action.durationSeconds} seconds. Confirm that the server can handle this test.")
            confirm = nativePhrase("确认启动", "Confirm start")
        }
        is NativeOperationsConfirmAction.AlertAction -> {
            title = if (action.resolve) nativePhrase("解决告警", "Resolve alert") else nativePhrase("确认告警", "Acknowledge alert")
            message = if (action.resolve) nativePhrase("解决后该告警会从未解决列表中移出，但运行记录仍会保留。请先确认已经按处理流程检查。", "This alert will leave the unresolved list after resolution, while its run record remains. Confirm that the handling procedure has been completed.") else nativePhrase("确认后告警会标记为已确认，记录仍会保留，后续可以继续解决。", "The alert will be acknowledged and retained; it can still be resolved later.")
            confirm = if (action.resolve) nativePhrase("确认解决", "Confirm resolution") else nativePhrase("确认", "Acknowledge")
        }
    }
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message, fontSize = 13.sp, lineHeight = 18.sp); HlovetFormFeedback(error) } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) nativePhrase("执行中…", "Running…") else confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
    )
}

@Composable
private fun OperationalRunDialog(run: NativeOperationalRun, error: String?, onDismiss: () -> Unit) {
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${operationRunKind(run.kind)} #${run.id}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                HlovetFormFeedback(error)
                Text(nativePhrase("状态：${operationRunStatus(run.status)}", "Status: ${operationRunStatus(run.status)}"), color = operationRunColor(run.status), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(run.summary, fontSize = 13.sp, lineHeight = 18.sp)
                Text(nativePhrase("提供商：${run.provider ?: "-"}", "Provider: ${run.provider ?: "-"}"), color = HlovetUi.muted, fontSize = 11.sp)
                Text(nativePhrase("开始：${operationTime(run.startedAt)}", "Started: ${operationTime(run.startedAt)}"), color = HlovetUi.muted, fontSize = 11.sp)
                Text(nativePhrase("完成：${run.completedAt?.let(::operationTime) ?: "执行中"}", "Completed: ${run.completedAt?.let(::operationTime) ?: "Running"}"), color = HlovetUi.muted, fontSize = 11.sp)
                if (run.detail.isNotBlank()) {
                    Text(nativePhrase("详细信息", "Details"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(prettyOperationJson(run.detail), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
                if (run.metrics.isNotBlank()) {
                    Text(nativePhrase("指标", "Metrics"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(prettyOperationJson(run.metrics), color = HlovetUi.muted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(nativePhrase("关闭", "Close")) } },
    )
}

private fun operationRunResultText(run: NativeOperationalRun): String = when (run.status) {
    "passed" -> nativePhrase("已完成", "completed")
    "blocked" -> nativePhrase("被阻塞", "blocked")
    "failed" -> nativePhrase("失败", "failed")
    else -> nativePhrase("已启动", "started")
}

private fun operationRunKind(kind: String): String = when (kind) {
    "recovery_drill" -> nativePhrase("恢复演练", "Recovery drill")
    "alert_check" -> nativePhrase("告警检查", "Alert check")
    "dependency_review" -> nativePhrase("依赖评估", "Dependency review")
    "load_test" -> nativePhrase("只读压测", "Read-only load test")
    "quality_check" -> nativePhrase("质量检查", "Quality check")
    "retention_cleanup" -> nativePhrase("审计清理", "Audit cleanup")
    else -> kind.ifBlank { nativePhrase("运营任务", "Operations task") }
}

private fun operationRunStatus(status: String): String = when (status) {
    "running" -> nativePhrase("执行中", "Running")
    "passed" -> nativePhrase("通过", "Passed")
    "blocked" -> nativePhrase("已阻塞", "Blocked")
    "failed" -> nativePhrase("失败", "Failed")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun operationRunColor(status: String): Color = when (status) {
    "passed" -> HlovetUi.accent
    "blocked" -> HlovetUi.secondaryAccent
    "failed" -> Color(0xFFB42318)
    else -> HlovetUi.muted
}

private fun operationAlertStatus(status: String): String = when (status) {
    "open" -> nativePhrase("待处理", "Open")
    "acknowledged" -> nativePhrase("已确认", "Acknowledged")
    "resolved" -> nativePhrase("已解决", "Resolved")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun operationSeverityColor(severity: String): Color = if (severity == "critical") Color(0xFFB42318) else HlovetUi.secondaryAccent

private fun operationDependencyStatus(status: String): String = when (status) {
    "pinned" -> nativePhrase("已固定", "Pinned")
    "range" -> nativePhrase("范围版本", "Version range")
    "not-found" -> nativePhrase("未读取", "Not read")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun operationDependencyColor(status: String): Color = when (status) {
    "pinned" -> HlovetUi.accent
    "range" -> HlovetUi.secondaryAccent
    else -> Color(0xFFB42318)
}

private fun operationQualityStatus(status: String): String = when (status) {
    "passed" -> nativePhrase("通过", "Passed")
    "warning" -> nativePhrase("需关注", "Needs attention")
    "blocked" -> nativePhrase("待配置", "Needs setup")
    "failed" -> nativePhrase("失败", "Failed")
    else -> status.ifBlank { nativePhrase("未执行", "Not run") }
}

private fun operationQualityColor(status: String): Color = when (status) {
    "passed" -> HlovetUi.accent
    "blocked", "warning" -> HlovetUi.secondaryAccent
    else -> Color(0xFFB42318)
}

private fun operationQualityIcon(status: String): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    "passed" -> Icons.Filled.CheckCircle
    "blocked", "warning" -> Icons.Filled.Warning
    else -> Icons.Filled.Error
}

private fun operationStatusColor(ok: Boolean): Color = if (ok) HlovetUi.accent else HlovetUi.secondaryAccent

private fun operationTime(value: String): String = value
    .replace('T', ' ')
    .replace(Regex("\\.\\d+Z$"), "")
    .replace("Z", "")
    .takeLast(19)

private fun prettyOperationJson(value: String): String {
    if (value.isBlank()) return nativePhrase("暂无", "None")
    return runCatching { JSONObject(value).toString(2) }.getOrElse {
        runCatching { JSONArray(value).toString(2) }.getOrElse { value }
    }
}
