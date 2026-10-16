package xyz.hlovet.portal.prototype

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** 按稳定编号 items 编辑文章顺序；enabled 控制操作，标题/说明回调展示内容，排序与移除回调交由父页面保存。 */
@Composable
internal fun NativeReorderableArticleRows(
    items: List<Int>,
    enabled: Boolean,
    titleFor: (Int) -> String,
    supportingFor: (Int) -> String? = { null },
    onReordered: (List<Int>) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // 拖动中的临时顺序只在手势结束时交给父页面，避免移动过程中触发持久化。
    var workingOrder by remember(items) { mutableStateOf(items) }
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragCenter by remember { mutableStateOf(0f) }
    val rowCenters = remember { mutableStateMapOf<Int, Float>() }
    val confirmedOrder by rememberUpdatedState(items)
    val currentOrder by rememberUpdatedState(workingOrder)
    val currentOnReordered by rememberUpdatedState(onReordered)

    /** 将 id 对应文章移动 delta 个位置，越界或正在拖动时不改变顺序。 */
    fun moveBy(id: Int, delta: Int) {
        // 箭头微调与拖动共用同一编号顺序，不按文章标题重新查找。
        if (!enabled || draggingId != null) return
        val index = workingOrder.indexOf(id)
        val nextIndex = index + delta
        if (index < 0 || nextIndex !in workingOrder.indices) return
        workingOrder = workingOrder.toMutableList().also { values ->
            val value = values.removeAt(index)
            values.add(nextIndex, value)
        }
        currentOnReordered(workingOrder)
    }

    // 每行保留独立操作语义，中文和英文界面使用同一组件及布局。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            nativePhrase("长按左侧把手拖动排序，也可以用箭头微调", "Drag the left handle or use the arrows to reorder"),
            color = HlovetUi.muted,
            fontSize = 10.sp,
        )
        workingOrder.forEachIndexed { index, id ->
            // 拖动换位时保留文章自己的手势协程，不能按列表位置复用另一篇文章的捕获状态。
            key(id) {
                val isDragging = draggingId == id
                val center = rowCenters[id]
                val translation = if (isDragging && center != null) dragCenter - center else 0f
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reorder-article-$id")
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = translation }
                        .onGloballyPositioned { coordinates ->
                            // 布局中心保持稳定，手指移动只作用于视觉层。
                            if (draggingId != id) rowCenters[id] = coordinates.boundsInParent().center.y
                        },
                    shape = HlovetUi.rowShape,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .pointerInput(enabled) {
                                    // 记录本次手势起点；取消恢复父页面顺序，结束才回传最终结果。
                                    if (!enabled) return@pointerInput
                                    var gestureOrder: List<Int>? = null
                                    var pointerCenter = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            gestureOrder = currentOrder
                                            pointerCenter = rowCenters[id] ?: 0f
                                            dragCenter = pointerCenter
                                            draggingId = id
                                        },
                                        onDragCancel = {
                                            draggingId = null
                                            dragCenter = 0f
                                            gestureOrder = null
                                            workingOrder = confirmedOrder
                                        },
                                        onDragEnd = {
                                            val result = gestureOrder ?: currentOrder
                                            draggingId = null
                                            dragCenter = 0f
                                            gestureOrder = null
                                            if (result != confirmedOrder) currentOnReordered(result)
                                        },
                                        onDrag = { change, amount ->
                                            pointerCenter += amount.y
                                            dragCenter = pointerCenter
                                            val order = gestureOrder ?: currentOrder
                                            val currentIndex = order.indexOf(id)
                                            if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                            val targetIndex = when {
                                                currentIndex < order.lastIndex && pointerCenter > (rowCenters[order[currentIndex + 1]] ?: Float.POSITIVE_INFINITY) -> currentIndex + 1
                                                currentIndex > 0 && pointerCenter < (rowCenters[order[currentIndex - 1]] ?: Float.NEGATIVE_INFINITY) -> currentIndex - 1
                                                else -> currentIndex
                                            }
                                            if (targetIndex == currentIndex) return@detectDragGesturesAfterLongPress
                                            val targetId = order[targetIndex]
                                            val targetCenter = rowCenters[targetId] ?: pointerCenter
                                            val nextOrder = order.toMutableList().also { values ->
                                                val value = values.removeAt(currentIndex)
                                                values.add(targetIndex, value)
                                            }
                                            gestureOrder = nextOrder
                                            workingOrder = nextOrder
                                            rowCenters[id] = targetCenter
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = nativePhrase("长按拖动排序", "Drag to reorder"),
                                tint = if (isDragging) HlovetUi.accent else HlovetUi.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            "${index + 1}",
                            color = HlovetUi.accent,
                            fontSize = 11.sp,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                titleFor(id),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            supportingFor(id)?.takeIf(String::isNotBlank)?.let { supporting ->
                                Text(supporting, color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { moveBy(id, -1) }, enabled = enabled && !isDragging && index > 0, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = nativePhrase("上移", "Move up"), modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = { moveBy(id, 1) }, enabled = enabled && !isDragging && index < workingOrder.lastIndex, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = nativePhrase("下移", "Move down"), modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = { onRemove(id) }, enabled = enabled && !isDragging, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = nativePhrase("移除文章", "Remove article"), tint = HlovetUi.secondaryAccent, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}
