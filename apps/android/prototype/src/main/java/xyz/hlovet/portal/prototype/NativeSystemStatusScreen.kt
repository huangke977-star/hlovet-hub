@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NativeSystemStatusScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenOperations: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    key(session?.accessToken, session?.isSuperAdmin) {
        NativeSystemStatusContent(session, onBack, onOpenOperations, onOpenStorage, onOpenBackups)
    }
}

@Composable
private fun NativeSystemStatusContent(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenOperations: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true && !token.isNullOrBlank()
    var status by remember(token) { mutableStateOf<NativeSystemStatus?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var backupBusy by remember(token) { mutableStateOf(false) }
    var backupDialogOpen by remember(token) { mutableStateOf(false) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token, canAccess, reloadKey) {
        if (!canAccess) {
            status = null
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            status = withContext(Dispatchers.IO) { NativeSystemStatusApi.getStatus(token!!) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("系统概况加载失败，请稍后重试。", "Could not load system status. Please try again later.")
        } finally {
            loading = false
        }
    }

    fun createBackup() {
        val currentToken = token ?: return
        if (!canAccess || backupBusy || loading) return
        backupBusy = true
        backupError = null
        notice = null
        scope.launch {
            try {
                val backup = withContext(Dispatchers.IO) { NativeSystemStatusApi.createBackup(currentToken) }
                status = status?.let { it.copy(backups = it.backups.withCreatedBackup(backup)) }
                backupDialogOpen = false
                notice = nativePhrase("备份已生成：${backup.name}", "Backup generated: ${backup.name}")
                reloadKey++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                backupError = exception.message ?: nativePhrase("备份生成失败，请稍后重试。", "Could not generate the backup. Please try again later.")
            } finally {
                backupBusy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("system-list"),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(key = "header") {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("系统概况", "System overview"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("运行状态、存储与可靠性", "Runtime status, storage, and reliability"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenOperations, enabled = canAccess) {
                        Icon(Icons.Filled.Settings, contentDescription = nativePhrase("运营与质量", "Operations and quality"), tint = if (token.isNullOrBlank()) HlovetUi.muted else HlovetUi.accent)
                    }
                    IconButton(onClick = { reloadKey++ }, enabled = !loading && !backupBusy && canAccess) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = if (loading) HlovetUi.muted else HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!canAccess) {
            item(key = "permission") {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(30.dp))
                        Text(if (token.isNullOrBlank()) nativePhrase("登录后查看系统概况", "Sign in to view system status") else nativePhrase("系统概况仅超级管理员可访问。", "Only super administrators can view system status."), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(nativePhrase("此页面仅面向站点超级管理员。", "This page is for site super administrators only."), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        } else if (loading && status == null) {
            item(key = "loading") { SystemStatusMessage(nativePhrase("正在读取系统状态…", "Loading system status...")) }
        } else if (!error.isNullOrBlank() && status == null) {
            item(key = "load-error") {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        TextButton(onClick = { reloadKey++ }, enabled = !loading) { Text(nativePhrase("重新加载", "Reload"), fontSize = 12.sp) }
                    }
                }
            }
        } else {
            status?.let { current ->
                if (!notice.isNullOrBlank()) {
                    item(key = "notice") { Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                }
                item(key = "application") { ApplicationStatusCard(current.application, current.generatedAt) }
                item(key = "database") { DatabaseStatusCard(current.database) }
                item(key = "redis") { RedisStatusCard(current.redis) }
                item(key = "storage") { StorageStatusCard(current.storage, onOpenStorage) }
                item(key = "backups") {
                    BackupStatusCard(
                        backups = current.backups,
                        busy = backupBusy,
                        canCreate = !loading && !backupBusy,
                        onCreate = { backupError = null; backupDialogOpen = true },
                        onOpenBackups = onOpenBackups,
                    )
                }
                item(key = "reliability") { ReliabilityStatusCard(current.reliability) }
                item(key = "monitoring") { MonitoringStatusCard(current.monitoring) }
                item(key = "memory-trend") { SystemTrendCard(nativePhrase("API RSS 内存", "API RSS memory"), current.monitoring.memoryTrend.map { it.recordedAt to it.rssBytes.toDouble() }, null, { formatSystemBytes(it.toLong()) }, "system-memory-trend") }
                item(key = "disk-trend") { SystemTrendCard(nativePhrase("磁盘使用率", "Disk usage"), current.monitoring.diskTrend.map { it.recordedAt to it.usedPercent }, current.reliability.warningThresholdPercent, { "${formatSystemDecimal(it)}%" }, "system-disk-trend") }
                item(key = "container") { ContainerStatusCard(current.containerRuntime) }
                if (!error.isNullOrBlank()) {
                    item(key = "refresh-error") { Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
                }
            }
        }
    }

    if (canAccess && backupDialogOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!backupBusy) backupDialogOpen = false },
            title = { Text(nativePhrase("立即生成数据库备份", "Generate database backup")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("系统会按当前备份策略生成一份数据库备份，并在完成后刷新列表。该操作可能需要一些时间。", "A database backup will be generated using the current policy and the list will refresh when it finishes. This may take a moment."), fontSize = 13.sp)
                    backupError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        createBackup()
                    },
                    enabled = !backupBusy,
                ) { Text(if (backupBusy) nativePhrase("生成中…", "Generating...") else nativePhrase("确认生成", "Generate")) }
            },
            dismissButton = {
                TextButton(onClick = { backupDialogOpen = false }, enabled = !backupBusy) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun ApplicationStatusCard(application: NativeSystemApplication, generatedAt: String) {
    SystemPanel(icon = Icons.Filled.Settings, title = nativePhrase("应用服务", "Application service"), status = nativePhrase("运行中", "Running"), ok = true) {
        StatusGrid(
            listOf(
                nativePhrase("服务", "Service") to application.service,
                nativePhrase("环境", "Environment") to application.environment,
                "Node" to application.nodeVersion,
                nativePhrase("运行时长", "Uptime") to formatSystemDuration(application.uptimeSeconds),
                nativePhrase("进程内存", "Process memory") to nativePhrase("${formatSystemBytes(application.rssBytes)} · 堆 ${formatSystemBytes(application.heapUsedBytes)} / ${formatSystemBytes(application.heapTotalBytes)}", "${formatSystemBytes(application.rssBytes)} · heap ${formatSystemBytes(application.heapUsedBytes)} / ${formatSystemBytes(application.heapTotalBytes)}"),
                nativePhrase("读取时间", "Read at") to formatSystemTime(generatedAt),
            ),
        )
    }
}

@Composable
private fun DatabaseStatusCard(database: NativeSystemDatabase) {
    SystemPanel(Icons.Filled.Storage, "MySQL", if (database.connected) nativePhrase("正常", "Healthy") else nativePhrase("异常", "Error"), database.connected) {
        StatusGrid(listOf(
            nativePhrase("延迟", "Latency") to (database.latencyMs?.let { "${formatSystemDecimal(it)} ms" } ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("版本", "Version") to (database.version ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("数据库容量", "Database size") to formatSystemBytes(database.sizeBytes),
            nativePhrase("迁移数量", "Migrations") to (database.migrationCount?.toString() ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("最近迁移", "Latest migration") to (database.latestMigration ?: nativePhrase("暂无记录", "No records")),
        ))
        database.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

@Composable
private fun RedisStatusCard(redis: NativeSystemRedis) {
    SystemPanel(Icons.Filled.Storage, "Redis", if (redis.connected) nativePhrase("正常", "Healthy") else nativePhrase("异常", "Error"), redis.connected) {
        StatusGrid(listOf(
            nativePhrase("延迟", "Latency") to (redis.latencyMs?.let { "${formatSystemDecimal(it)} ms" } ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("版本", "Version") to (redis.version ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("缓存键", "Cache keys") to (redis.keyCount?.toString() ?: nativePhrase("无法读取", "Unavailable")),
            nativePhrase("内存", "Memory") to "${formatSystemBytes(redis.usedMemoryBytes)} / ${if (redis.maxMemoryBytes == 0L) nativePhrase("未设上限", "Unlimited") else formatSystemBytes(redis.maxMemoryBytes)}",
            nativePhrase("连接数", "Connections") to (redis.connectedClients?.toString() ?: nativePhrase("无法读取", "Unavailable")),
        ))
        redis.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

@Composable
private fun StorageStatusCard(storage: NativeSystemStorage, onOpenStorage: () -> Unit) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(nativePhrase("本地存储", "Local storage"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(nativePhrase("${storage.totalFiles} 个文件", "${storage.totalFiles} files"), color = HlovetUi.muted, fontSize = 10.sp)
                TextButton(onClick = onOpenStorage) { Text(nativePhrase("存储管理", "Storage"), fontSize = 10.sp) }
            }
            Spacer(Modifier.height(10.dp))
            StatusGrid(
                storage.items.map { item ->
                    item.label to nativePhrase("${formatSystemBytes(item.sizeBytes)} · ${item.fileCount} 个文件${if (!item.available) " · 不可用" else ""}", "${formatSystemBytes(item.sizeBytes)} · ${item.fileCount} files${if (!item.available) " · unavailable" else ""}")
                } + listOf(nativePhrase("合计", "Total") to formatSystemBytes(storage.totalBytes)),
            )
        }
    }
}

@Composable
private fun BackupStatusCard(
    backups: NativeSystemBackups,
    busy: Boolean,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    val latest = backups.latest
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(nativePhrase("备份", "Backups"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenBackups) { Text(nativePhrase("备份管理", "Manage backups"), fontSize = 10.sp) }
                TextButton(onClick = onCreate, enabled = canCreate) { Text(if (busy) nativePhrase("生成中…", "Generating...") else nativePhrase("立即备份", "Back up now"), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(5.dp))
            StatusGrid(
                listOf(
                    nativePhrase("状态", "Status") to (if (backups.available) nativePhrase("可用", "Available") else nativePhrase("暂无备份", "No backups")),
                    nativePhrase("备份文件", "Backup files") to nativePhrase("${backups.fileCount} 个 · ${formatSystemBytes(backups.totalBytes)}", "${backups.fileCount} · ${formatSystemBytes(backups.totalBytes)}"),
                    nativePhrase("最近备份", "Latest backup") to (latest?.let { "${it.name} · ${formatSystemTime(it.updatedAt)}" } ?: nativePhrase("暂无", "None")),
                    nativePhrase("校验状态", "Verification") to (latest?.let(::backupVerificationText) ?: nativePhrase("尚未生成", "Not generated")),
                    nativePhrase("媒体快照", "Media snapshot") to (latest?.let { if (it.mediaSnapshotAvailable) nativePhrase("已包含", "Included") else nativePhrase("未包含", "Not included") } ?: nativePhrase("暂无", "None")),
                ),
            )
            if (backups.items.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(nativePhrase("最近文件", "Recent files"), color = HlovetUi.muted, fontSize = 11.sp)
                backups.items.take(4).forEach { backup ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(backup.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(formatSystemBytes(backup.sizeBytes + (backup.mediaSnapshotSizeBytes ?: 0L)), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliabilityStatusCard(reliability: NativeSystemReliability) {
    val healthy = reliability.anomalyTotal == 0 && (reliability.diskUsedPercent == null || reliability.diskUsedPercent < reliability.warningThresholdPercent)
    SystemPanel(icon = Icons.Filled.Shield, title = nativePhrase("可靠性", "Reliability"), status = if (healthy) nativePhrase("无异常", "Healthy") else nativePhrase("需要关注", "Needs attention"), ok = healthy) {
        StatusGrid(
            listOf(
                nativePhrase("备份覆盖", "Backup coverage") to (reliability.coveragePercentage?.let { "${formatSystemDecimal(it)}%" } ?: nativePhrase("待生成目录", "Waiting for index")),
                nativePhrase("覆盖文件", "Covered files") to "${reliability.backedUpFiles} / ${reliability.totalFiles}",
                nativePhrase("未覆盖文件", "Uncovered files") to reliability.uncoveredFiles.toString(),
                nativePhrase("异常窗口", "Anomaly window") to nativePhrase("近 ${reliability.anomalyWindowHours} 小时 · ${reliability.anomalyTotal} 项", "${reliability.anomalyTotal} in the last ${reliability.anomalyWindowHours} hours"),
                nativePhrase("磁盘使用", "Disk usage") to (reliability.diskUsedPercent?.let { nativePhrase("${formatSystemDecimal(it)}% / 预警 ${formatSystemDecimal(reliability.warningThresholdPercent)}%", "${formatSystemDecimal(it)}% / warning ${formatSystemDecimal(reliability.warningThresholdPercent)}%") } ?: nativePhrase("无法读取", "Unavailable")),
                nativePhrase("最近扫描", "Last scan") to (reliability.latestScanAt?.let(::formatSystemTime) ?: nativePhrase("尚未扫描", "Not scanned")),
                nativePhrase("最近成功备份", "Last successful backup") to (reliability.lastSuccessfulBackupAt?.let(::formatSystemTime) ?: nativePhrase("暂无", "None")),
                nativePhrase("备份来源", "Backup source") to when (reliability.lastSuccessfulBackupSource) {
                    "media" -> nativePhrase("媒体备份", "Media backup")
                    "database" -> nativePhrase("数据库备份", "Database backup")
                    else -> nativePhrase("暂无", "None")
                },
                nativePhrase("备份失败", "Backup failures") to reliability.backupFailures.toString(),
                nativePhrase("磁盘预警", "Disk warnings") to reliability.diskPressure.toString(),
                nativePhrase("缺失文件", "Missing files") to reliability.missingFiles.toString(),
                nativePhrase("孤立文件", "Orphan files") to reliability.orphanFiles.toString(),
                nativePhrase("大小不一致", "Size mismatch") to reliability.metadataMismatches.toString(),
                nativePhrase("接口错误", "API errors") to reliability.recentApiErrors.toString(),
            ),
        )
    }
}

@Composable
private fun MonitoringStatusCard(monitoring: NativeSystemMonitoring) {
    SystemPanel(icon = Icons.Filled.Info, title = nativePhrase("运行监测", "Runtime monitoring"), status = nativePhrase("保留 ${monitoring.retentionMinutes} 分钟", "${monitoring.retentionMinutes}-minute retention"), ok = true) {
        StatusGrid(
            listOf(
                nativePhrase("慢请求", "Slow requests") to nativePhrase("${monitoring.slowRequests.size} 条 · 阈值 ${formatSystemDecimal(monitoring.slowRequestThresholdMs)} ms", "${monitoring.slowRequests.size} · threshold ${formatSystemDecimal(monitoring.slowRequestThresholdMs)} ms"),
                nativePhrase("接口错误", "API errors") to nativePhrase("${monitoring.recentErrors.size} 条", "${monitoring.recentErrors.size}"),
                nativePhrase("前端错误", "Client errors") to nativePhrase("${monitoring.recentClientErrors.size} 条", "${monitoring.recentClientErrors.size}"),
                nativePhrase("内存采样", "Memory samples") to nativePhrase("${monitoring.memoryTrend.size} 个点", "${monitoring.memoryTrend.size} points"),
                nativePhrase("磁盘采样", "Disk samples") to nativePhrase("${monitoring.diskTrend.size} 个点", "${monitoring.diskTrend.size} points"),
            ),
        )
        SystemHttpEvents(nativePhrase("慢接口", "Slow requests"), monitoring.slowRequests)
        SystemHttpEvents(nativePhrase("最近错误", "Recent errors"), monitoring.recentErrors)
        Text(nativePhrase("前端错误", "Client errors"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        if (monitoring.recentClientErrors.isEmpty()) Text(nativePhrase("暂无记录", "No records"), color = HlovetUi.muted, fontSize = 11.sp)
        monitoring.recentClientErrors.forEach { event ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(event.message, fontSize = 11.sp)
                Text("${event.source} · ${event.path} · ${formatSystemTime(event.occurredAt)}", color = HlovetUi.muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SystemHttpEvents(title: String, events: List<NativeSystemHttpEvent>) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    if (events.isEmpty()) Text(nativePhrase("暂无记录", "No records"), color = HlovetUi.muted, fontSize = 11.sp)
    events.forEach { event ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("${event.method} ${event.path}", fontSize = 11.sp)
            Text("${event.statusCode} · ${formatSystemDecimal(event.durationMs)} ms · ${formatSystemTime(event.occurredAt)}", color = HlovetUi.muted, fontSize = 10.sp)
            event.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun SystemTrendCard(title: String, points: List<Pair<String, Double>>, warning: Double?, formatter: (Double) -> String, tag: String) {
    val visible = points.filter { it.second.isFinite() && it.second >= 0 }.sortedBy { it.first }
    SystemPanel(Icons.Filled.Info, title, nativePhrase("每分钟采样", "Minute samples"), true) {
        if (visible.isEmpty()) {
            Text(nativePhrase("等待首次采样", "Waiting for first sample"), color = HlovetUi.muted, fontSize = 11.sp)
        } else {
            Text(nativePhrase("当前 ${formatter(visible.last().second)}", "Current ${formatter(visible.last().second)}"), fontSize = 11.sp)
            warning?.let { Text(nativePhrase("预警线 ${formatter(it)}", "Warning threshold ${formatter(it)}"), color = HlovetUi.secondaryAccent, fontSize = 10.sp) }
            Canvas(Modifier.fillMaxWidth().height(120.dp).testTag(tag)) {
                val maximum = maxOf(1.0, visible.maxOf { it.second }, warning ?: 0.0).toFloat()
                val bottom = size.height - 8.dp.toPx()
                val top = 8.dp.toPx()
                val left = 4.dp.toPx()
                val width = size.width - left * 2
                drawLine(HlovetUi.divider, Offset(left, bottom), Offset(left + width, bottom))
                warning?.let { val y = bottom - (bottom - top) * (it.toFloat() / maximum); drawLine(HlovetUi.secondaryAccent, Offset(left, y), Offset(left + width, y)) }
                val path = Path()
                visible.forEachIndexed { index, point ->
                    val x = left + if (visible.size == 1) width / 2 else width * index / visible.lastIndex
                    val y = bottom - (bottom - top) * (point.second.toFloat() / maximum)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    if (visible.size == 1) drawCircle(HlovetUi.accent, 3.dp.toPx(), Offset(x, y))
                }
                drawPath(path, HlovetUi.accent, style = Stroke(2.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatSystemTime(visible.first().first), color = HlovetUi.muted, fontSize = 9.sp)
                if (visible.size > 1) Text(formatSystemTime(visible.last().first), color = HlovetUi.muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ContainerStatusCard(container: NativeSystemContainerRuntime) {
    SystemPanel(icon = Icons.Filled.Info, title = nativePhrase("容器运行状态", "Container runtime"), status = if (container.connected) nativePhrase("已连接", "Connected") else nativePhrase("未读取", "Unavailable"), ok = container.connected) {
        Text(container.message, color = HlovetUi.muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SystemPanel(
    icon: ImageVector,
    title: String,
    status: String,
    ok: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.background(systemTone(ok).copy(alpha = .12f), HlovetUi.rowShape).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = systemTone(ok), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(status, color = systemTone(ok), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(label, color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.width(76.dp))
                Text(value, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SystemStatusMessage(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

private fun systemTone(ok: Boolean): Color = if (ok) HlovetUi.accent else HlovetUi.secondaryAccent

private fun formatSystemBytes(value: Long?): String {
    val number = value ?: return nativePhrase("无法读取", "Unavailable")
    if (number <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val index = minOf(units.lastIndex, (kotlin.math.log(number.toDouble(), 1024.0)).toInt())
    val amount = number / Math.pow(1024.0, index.toDouble())
    return "${if (amount >= 10 || index == 0) amount.toInt() else (amount * 10).toInt() / 10.0} ${units[index]}"
}

private fun formatSystemDecimal(value: Double): String =
    if (!value.isFinite()) "-" else if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

private fun formatSystemDuration(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> nativePhrase("${days}天 ${hours}小时", "${days}d ${hours}h")
        hours > 0 -> nativePhrase("${hours}小时 ${minutes}分", "${hours}h ${minutes}m")
        else -> nativePhrase("${minutes}分钟", "${minutes}m")
    }
}

private fun formatSystemTime(value: String): String = value
    .replace("T", " ")
    .replace(Regex("\\.\\d+Z$"), "")
    .replace("Z", "")
    .takeLast(19)

private fun backupVerificationText(backup: NativeSystemBackup): String = when (backup.verificationStatus) {
    "verified" -> nativePhrase("已校验", "Verified")
    "database_only" -> nativePhrase("仅数据库已校验", "Database verified only")
    "failed" -> nativePhrase("校验失败", "Verification failed")
    else -> nativePhrase("未校验", "Not verified")
}
