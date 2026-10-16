@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 区分补发与违规扣分，提交时复用各自的幂等账本接口。 */
private enum class NativeAdminAdjustmentKind { TopUp, Violation }

/** 保存待确认的交付与动作，不在打开弹窗时提前修改交付状态。 */
private data class NativeAdminDeliveryAction(
    val item: NativeResourceDelivery,
    val kind: String,
)

/** 展示 session 权限内的交付和积分记录，写操作仅超级管理员可用；onBack 返回上一页。 */
@Composable
internal fun NativeAdminResourcesScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    // 读取权限与写入权限分别对齐服务器守卫，普通管理员只读。
    val token = session?.accessToken
    val canManage = !token.isNullOrBlank() && (session?.isAdministrator == true || session?.isSuperAdmin == true)
    val canWrite = canManage && session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var deliveries by remember(token) { mutableStateOf<NativeResourceDeliveryPage?>(null) }
    var adjustments by remember(token) { mutableStateOf<NativeAdminPointAdjustmentPage?>(null) }
    var deliveryPage by remember(token) { mutableIntStateOf(1) }
    var adjustmentPage by remember(token) { mutableIntStateOf(1) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank()) }
    var busy by remember(token) { mutableStateOf(false) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var formError by remember(token) { mutableStateOf<String?>(null) }
    var pendingDeliveryAction by remember(token) { mutableStateOf<NativeAdminDeliveryAction?>(null) }
    var failureReason by remember(token) { mutableStateOf("") }
    var adjustmentOpen by remember(token) { mutableStateOf(false) }
    var adjustmentKind by remember(token) { mutableStateOf(NativeAdminAdjustmentKind.TopUp) }
    var username by remember(token) { mutableStateOf("") }
    var points by remember(token) { mutableStateOf("") }
    var eventKey by remember(token) { mutableStateOf("") }
    var note by remember(token) { mutableStateOf("") }

    // 翻页与操作后重新读取当前页，不用本地推算替代服务器账本或交付结果。
    LaunchedEffect(token, canManage, deliveryPage, adjustmentPage, reloadKey) {
        if (!canManage || token.isNullOrBlank()) {
            deliveries = null
            adjustments = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val deliveryResult = withContext(Dispatchers.IO) { NativeResourceApi.listAdminDeliveries(token, deliveryPage) }
            val adjustmentResult = withContext(Dispatchers.IO) { NativeResourceApi.listAdminPointAdjustments(token, adjustmentPage) }
            deliveries = deliveryResult
            adjustments = adjustmentResult
            if (deliveryResult.page != deliveryPage) deliveryPage = deliveryResult.page
            if (adjustmentResult.page != adjustmentPage) adjustmentPage = adjustmentResult.page
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("资源与积分记录读取失败。", "Could not load resource and point records.")
        } finally {
            loading = false
        }
    }

    /** 清空已完成的积分调整输入，不修改当前列表与已写入的账本。 */
    fun clearAdjustmentForm() {
        // 关闭成功表单后避免旧账号与事件键被意外带入下一次调整。
        username = ""
        points = ""
        eventKey = ""
        note = ""
    }

    /** 按 action 确认退款或用 failure 记录失败；成功后刷新，失败留在原弹窗。 */
    fun runDeliveryAction(action: NativeAdminDeliveryAction, failure: String = "") {
        // 写入必须满足超级管理员权限，旧弹窗也不能在会话降权后继续提交。
        val accessToken = token ?: return
        if (!canWrite || busy) return
        busy = true
        formError = null
        scope.launch {
            try {
                // 请求成功后再关闭弹窗并刷新交付，避免只更新提示而记录保持旧状态。
                withContext(Dispatchers.IO) {
                    if (action.kind == "refund") {
                        NativeResourceApi.refundAdminDelivery(accessToken, action.item.id)
                    } else {
                        NativeResourceApi.markAdminDeliveryFailed(accessToken, action.item.id, failure)
                    }
                }
                pendingDeliveryAction = null
                notice = if (action.kind == "refund") nativePhrase("已退款并写入积分账本。", "Refunded and recorded in the points ledger.") else nativePhrase("已记录资源交付失败。", "Resource delivery failure recorded.")
                reloadKey++
            } catch (exception: Exception) {
                // 网络或权限错误在当前弹窗反馈，保留用户填写的失败原因。
                formError = exception.message ?: nativePhrase("资源操作失败。", "Resource operation failed.")
            } finally {
                // 所有结果都释放提交锁，允许失败后重试。
                busy = false
            }
        }
    }

    /** 校验并提交当前积分调整，按服务器 applied 区分新增入账与重复事件。 */
    fun submitAdjustment() {
        // 用户名、正整数数量和事件键共同决定调整目标，校验失败不发送请求。
        val accessToken = token ?: return
        if (!canWrite || busy) return
        val parsedPoints = points.trim().toIntOrNull()
        if (username.trim().removePrefix("@").isBlank() || parsedPoints == null || parsedPoints < 1 || eventKey.trim().isBlank()) {
            formError = nativePhrase("请填写有效的 @用户名、正整数积分和唯一事件键。", "Enter a valid @username, positive point amount, and unique event key.")
            return
        }
        busy = true
        formError = null
        scope.launch {
            try {
                // 同一个事件键重复提交成功也可能没有新增记录，使用接口明确结果展示提示。
                val applied = withContext(Dispatchers.IO) {
                    if (adjustmentKind == NativeAdminAdjustmentKind.TopUp) {
                        NativeResourceApi.topUpAdminPoints(accessToken, username, parsedPoints, eventKey, note)
                    } else {
                        NativeResourceApi.applyAdminViolationPenalty(accessToken, username, parsedPoints, eventKey, note)
                    }
                }
                adjustmentOpen = false
                clearAdjustmentForm()
                notice = if (!applied) nativePhrase("该事件键已经处理过。", "This event key was already processed.")
                    else nativePhrase("积分账本已更新。", "Points ledger updated.")
                reloadKey++
            } catch (exception: Exception) {
                // 失败时不清空输入，便于修正账号或网络恢复后重试。
                formError = exception.message ?: nativePhrase("积分调整失败。", "Point adjustment failed.")
            } finally {
                // 防重复锁只覆盖本次请求，不阻止已完成后核对相同事件键的幂等结果。
                busy = false
            }
        }
    }

    // 列表沿用统一玻璃组件，写入按钮与表单使用同一权限来源。
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("native-admin-resources-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("资源与积分", "Resources and points"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("交付记录、退款和可审计积分调整", "Delivery records, refunds, and auditable point adjustments"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                actions = {
                    if (canWrite) {
                        IconButton(onClick = { formError = null; adjustmentOpen = true }, enabled = !busy) {
                            Icon(Icons.Filled.Add, contentDescription = nativePhrase("积分调整", "Point adjustment"), tint = HlovetUi.accent)
                        }
                    }
                    IconButton(onClick = { reloadKey++ }, enabled = canManage && !loading && !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) {
            item { NativeAdminResourcesMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        }
        if (token.isNullOrBlank()) {
            item { NativeAdminResourcesMessage(nativePhrase("登录后查看资源与积分管理。", "Sign in to view resource and point management."), false, null) }
        } else if (session?.isAdministrator != true && session?.isSuperAdmin != true) {
            item { NativeAdminResourcesMessage(nativePhrase("当前账号没有资源与积分管理权限。", "This account does not have resource and point management permission."), true, null) }
        } else if (loading && deliveries == null && adjustments == null) {
            item { NativeAdminResourcesMessage(nativePhrase("正在读取资源记录…", "Loading resource records..."), false, null) }
        } else {
            item { NativeAdminResourcesSectionTitle(nativePhrase("最近资源交付", "Recent resource deliveries"), deliveries?.total ?: 0) }
            if (deliveries?.items.isNullOrEmpty()) {
                item { NativeAdminResourcesMessage(nativePhrase("暂无资源交付记录。", "No resource delivery records."), false, null) }
            } else {
                items(deliveries!!.items, key = { "delivery-${it.id}" }) { item ->
                    NativeAdminDeliveryRow(
                        item = item,
                        enabled = !busy,
                        canWrite = canWrite,
                        onRefund = { formError = null; pendingDeliveryAction = NativeAdminDeliveryAction(item, "refund") },
                        onFail = {
                            formError = null
                            failureReason = item.lastError.orEmpty()
                            pendingDeliveryAction = NativeAdminDeliveryAction(item, "fail")
                        },
                    )
                }
                item {
                    NativeAdminResourcesPager(
                        page = deliveries?.page ?: deliveryPage,
                        totalPages = deliveries?.totalPages ?: 1,
                        enabled = !loading && !busy,
                        onPrevious = { deliveryPage-- },
                        onNext = { deliveryPage++ },
                    )
                }
            }
            item { NativeAdminResourcesSectionTitle(nativePhrase("积分调整记录", "Point adjustment records"), adjustments?.total ?: 0) }
            if (adjustments?.items.isNullOrEmpty()) {
                item { NativeAdminResourcesMessage(nativePhrase("暂无积分调整记录。", "No point adjustment records."), false, null) }
            } else {
                items(adjustments!!.items, key = { "adjustment-${it.id}" }) { item -> NativeAdminAdjustmentRow(item) }
                item {
                    NativeAdminResourcesPager(
                        page = adjustments?.page ?: adjustmentPage,
                        totalPages = adjustments?.totalPages ?: 1,
                        enabled = !loading && !busy,
                        onPrevious = { adjustmentPage-- },
                        onNext = { adjustmentPage++ },
                    )
                }
            }
        }
    }

    pendingDeliveryAction?.let { action ->
        if (action.kind == "refund") {
            HlovetAlertDialog(
                onDismissRequest = { if (!busy) pendingDeliveryAction = null },
                title = { Text(nativePhrase("确认退款", "Confirm refund")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(nativePhrase("确定退款《${action.item.articleTitle}》的这次兑换吗？退款会回写买方积分并扣回作者待结算收益。", "Refund this redemption of “${action.item.articleTitle}”? The buyer's points will be returned and the author's pending earnings deducted."), fontSize = 13.sp, lineHeight = 19.sp)
                        HlovetFormFeedback(formError)
                    }
                },
                confirmButton = { TextButton(onClick = { runDeliveryAction(action) }, enabled = !busy) { Text(nativePhrase("确认退款", "Confirm refund"), color = HlovetUi.secondaryAccent) } },
                dismissButton = { TextButton(onClick = { pendingDeliveryAction = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) } },
            )
        } else {
            Dialog(onDismissRequest = { if (!busy) pendingDeliveryAction = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.imePadding().fillMaxWidth().fillMaxHeight(.58f).padding(horizontal = 18.dp), shape = HlovetUi.cardShape, color = HlovetUi.dialogSurface) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nativePhrase("记录交付失败", "Record delivery failure"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(action.item.articleTitle, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { if (!busy) pendingDeliveryAction = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            OutlinedTextField(
                                value = failureReason,
                                onValueChange = { failureReason = it.take(1000) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6,
                                label = { Text(nativePhrase("失败原因", "Failure reason")) },
                                colors = nativeAdminResourcesFieldColors(),
                            )
                        }
                        HlovetFormFeedback(formError)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { pendingDeliveryAction = null }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) }
                            TextButton(onClick = { if (failureReason.trim().isBlank()) formError = nativePhrase("请填写失败原因。", "Enter a failure reason.") else runDeliveryAction(action, failureReason) }, enabled = !busy) { Text(if (busy) nativePhrase("处理中…", "Working...") else nativePhrase("确认记录", "Record"), color = HlovetUi.secondaryAccent) }
                        }
                    }
                }
            }
        }
    }

    if (adjustmentOpen) {
        Dialog(onDismissRequest = { if (!busy) adjustmentOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.imePadding().fillMaxWidth().fillMaxHeight(.76f).padding(horizontal = 18.dp), shape = HlovetUi.cardShape, color = HlovetUi.dialogSurface) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AttachMoney, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("积分调整", "Point adjustment"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(nativePhrase("通过唯一事件键保证重复提交不会重复记账。", "A unique event key prevents duplicate ledger entries."), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        IconButton(onClick = { if (!busy) adjustmentOpen = false }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(17.dp)) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(selected = adjustmentKind == NativeAdminAdjustmentKind.TopUp, enabled = !busy, onClick = { adjustmentKind = NativeAdminAdjustmentKind.TopUp }, label = { Text(nativePhrase("补发积分", "Top up points"), fontSize = 11.sp) })
                            FilterChip(selected = adjustmentKind == NativeAdminAdjustmentKind.Violation, enabled = !busy, onClick = { adjustmentKind = NativeAdminAdjustmentKind.Violation }, label = { Text(nativePhrase("违规扣分", "Violation penalty"), fontSize = 11.sp) })
                        }
                        OutlinedTextField(username, { username = it.take(80) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("目标 @username", "Target @username")) }, placeholder = { Text("例如 @nice3 / e.g. @nice3") }, colors = nativeAdminResourcesFieldColors())
                        OutlinedTextField(points, { points = it.filter(Char::isDigit).take(9) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("积分数量", "Point amount")) }, colors = nativeAdminResourcesFieldColors())
                        OutlinedTextField(eventKey, { eventKey = it.take(160) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(nativePhrase("唯一事件键", "Unique event key")) }, placeholder = { Text("例如 case-20261001-01 / e.g. case-20261001-01") }, colors = nativeAdminResourcesFieldColors())
                        OutlinedTextField(note, { note = it.take(500) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text(nativePhrase("备注（可选）", "Note (optional)")) }, colors = nativeAdminResourcesFieldColors())
                    }
                    HlovetFormFeedback(formError)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { if (!busy) adjustmentOpen = false }, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) }
                        TextButton(onClick = ::submitAdjustment, enabled = !busy) { Text(if (busy) nativePhrase("提交中…", "Submitting...") else nativePhrase("确认提交", "Submit"), color = HlovetUi.accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAdminResourcesSectionTitle(title: String, count: Int) {
    Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(nativePhrase("$count 条", "$count items"), color = HlovetUi.muted, fontSize = 10.sp)
    }
}

@Composable
/** 展示 item 的交付状态；enabled 控制忙碌，canWrite 控制超级管理员的 onRefund/onFail 动作。 */
private fun NativeAdminDeliveryRow(
    item: NativeResourceDelivery,
    enabled: Boolean,
    canWrite: Boolean,
    onRefund: () -> Unit,
    onFail: () -> Unit,
) {
    // 同一行保留稳定业务 ID，权限不足时完全隐藏写操作而非点击后等待服务器拒绝。
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("native-admin-delivery-${item.id}"), shape = HlovetUi.rowShape) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.articleTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(nativeAdminResourceStatus(item.status), color = nativeAdminResourceStatusColor(item.status), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(nativePhrase("买方 @${item.buyer.username} · 作者 @${item.author.username}", "Buyer @${item.buyer.username} · Author @${item.author.username}"), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nativePhrase("${item.pointCost} 积分 · ${item.blockKey}", "${item.pointCost} points · ${item.blockKey}"), color = HlovetUi.muted, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (canWrite && item.status != "refunded") {
                    IconButton(onClick = onFail, enabled = enabled && item.status != "failed", modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Warning, contentDescription = nativePhrase("记录失败", "Record failure"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp)) }
                    IconButton(onClick = onRefund, enabled = enabled, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Replay, contentDescription = nativePhrase("退款", "Refund"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(16.dp)) }
                }
            }
            if (!item.lastError.isNullOrBlank()) Text("${item.lastError}", color = HlovetUi.secondaryAccent, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NativeAdminAdjustmentRow(item: NativeAdminPointAdjustment) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.pointDelta >= 0) Icons.Filled.Add else Icons.Filled.Warning, contentDescription = null, tint = if (item.pointDelta >= 0) HlovetUi.accent else HlovetUi.secondaryAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(nativePhrase("@${item.user.username} · ${if (item.pointDelta >= 0) "补发积分" else "违规扣分"}", "@${item.user.username} · ${if (item.pointDelta >= 0) "Point top-up" else "Violation penalty"}"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.description.ifBlank { item.eventKey }, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (item.pointDelta >= 0) "+${item.pointDelta}" else item.pointDelta.toString(), color = if (item.pointDelta >= 0) HlovetUi.accent else HlovetUi.secondaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NativeAdminResourcesPager(page: Int, totalPages: Int, enabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious, enabled = enabled && page > 1) { Text(nativePhrase("上一页", "Previous"), fontSize = 11.sp) }
        Text(nativePhrase("第 $page / $totalPages 页", "Page $page / $totalPages"), color = HlovetUi.muted, fontSize = 10.sp)
        TextButton(onClick = onNext, enabled = enabled && page < totalPages) { Text(nativePhrase("下一页", "Next"), fontSize = 11.sp) }
    }
}

@Composable
private fun NativeAdminResourcesMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Shield, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            onDismiss?.let { IconButton(onClick = it, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭", "Close"), modifier = Modifier.size(15.dp)) } }
        }
    }
}

@Composable
private fun nativeAdminResourcesFieldColors() = HlovetUi.fieldColors()

private fun nativeAdminResourceStatus(status: String): String = when (status) {
    "unlocked" -> nativePhrase("待交付", "Ready for delivery")
    "downloaded" -> nativePhrase("已下载", "Downloaded")
    "failed" -> nativePhrase("交付失败", "Delivery failed")
    "refunded" -> nativePhrase("已退款", "Refunded")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun nativeAdminResourceStatusColor(status: String): Color = when (status) {
    "downloaded" -> HlovetUi.accent
    "refunded", "failed" -> HlovetUi.secondaryAccent
    else -> HlovetUi.muted
}
