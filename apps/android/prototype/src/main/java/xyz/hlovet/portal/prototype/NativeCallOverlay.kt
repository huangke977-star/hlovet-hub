package xyz.hlovet.portal.prototype

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * 呈现 call 的完整/最小化面板；phase/preparing 决定可用动作，媒体轨道和 eglContext 仍由全局宿主持有。
 * muted/cameraEnabled 回显媒体状态，canSwitchCamera 表示实际存在备用相机；回调交给宿主，收起或恢复不重建媒体。
 */
@Composable
internal fun NativeCallOverlay(
    call: NativeCallSession,
    phase: String,
    preparing: Boolean,
    localVideoTrack: VideoTrack?,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    muted: Boolean,
    cameraEnabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    canSwitchCamera: Boolean = false,
) {
    // 展示状态以通话编号隔离；下一通电话不能继承上一通的最小化状态或计时。
    var minimized by remember(call.id) { mutableStateOf(false) }
    val acceptedAt = remember(call.id, call.acceptedAt) { nativeCallAcceptedAtMillis(call.acceptedAt) }
    val clockOrigin = remember(call.id, acceptedAt) {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        acceptedAt?.let { elapsedNow - (wallNow - it) } ?: elapsedNow
    }
    var now by remember(call.id, acceptedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.id, acceptedAt, phase) {
        // 初始时长来自服务器；后续使用单调时钟，切换面板或设备校时不会让计时倒退。
        if (phase == "active" && acceptedAt != null) while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val duration = if (phase == "active" && acceptedAt != null) nativeCallDuration(now - clockOrigin) else null

    // 小窗不获取焦点、不拦截窗外点击和返回键，用户可在保留通话的同时继续操作原生页面。
    if (minimized && phase != "incoming") {
        val density = LocalDensity.current
        val navigationClearance = with(density) { 80.dp.roundToPx() }
        val keyboardClearance = WindowInsets.ime.getBottom(density) + with(density) { 12.dp.roundToPx() }
        val position = remember(navigationClearance, keyboardClearance) {
            NativeCallMiniPosition(maxOf(navigationClearance, keyboardClearance))
        }
        Popup(
            popupPositionProvider = position,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            NativeCallMini(call, phase, duration, onRestore = { minimized = false }, onEnd = onEnd)
        }
    } else {
        // 来电仍需明确接听/拒绝；已发起或已接通时，系统返回等价于收起，不等价于挂断。
        Dialog(
            onDismissRequest = { if (phase != "incoming") minimized = true },
            properties = DialogProperties(dismissOnBackPress = phase != "incoming", dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                    NativeCallPanel(
                        call = call, phase = phase, preparing = preparing, duration = duration,
                        localVideoTrack = localVideoTrack, remoteVideoTrack = remoteVideoTrack, eglContext = eglContext,
                        muted = muted, cameraEnabled = cameraEnabled,
                        onAccept = onAccept, onDecline = onDecline, onEnd = onEnd,
                        onToggleMute = onToggleMute, onToggleCamera = onToggleCamera, onSwitchCamera = onSwitchCamera,
                        onMinimize = { minimized = true },
                        canSwitchCamera = canSwitchCamera,
                    )
                }
            }
        }
    }
}

/** 根据窗口尺寸定位通话小窗；bottomClearance 为导航栏或键盘预留像素，不依赖调用处的零尺寸锚点。 */
internal class NativeCallMiniPosition(private val bottomClearance: Int) : PopupPositionProvider {
    /** 将 popupContentSize 放在 windowSize 的阅读末端；窄屏或旋转时限制在可见窗口内，忽略页面滚动锚点。 */
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // Popup 内容自身包含边距；与根页面滚动无关，避免跨页或软键盘出现后落到屏幕外。
        val x = if (layoutDirection == LayoutDirection.Rtl) 0 else (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (windowSize.height - popupContentSize.height - bottomClearance).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** 最小化后显示 call 的对方和 phase/duration；整行恢复通话，独立挂断按钮只调用 onEnd。 */
@Composable
private fun NativeCallMini(call: NativeCallSession, phase: String, duration: String?, onRestore: () -> Unit, onEnd: () -> Unit) {
    // 复用账号玻璃卡片及现有字号；限制最大宽度，长名称单行省略，不挤占挂断触控区域。
    Box(Modifier.padding(12.dp).widthIn(max = 280.dp).fillMaxWidth()) {
        GlassCard {
            Row(
                Modifier.fillMaxWidth().testTag("native-call-mini")
                    .semantics { contentDescription = nativePhrase("返回通话", "Return to call") }
                    .clickable(role = Role.Button, onClick = onRestore).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (call.type == "video") Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(call.userName.ifBlank { nativePhrase("聊天用户", "Chat user") }, modifier = Modifier.testTag("native-call-mini-name"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        duration ?: when (phase) {
                            "outgoing" -> nativePhrase("等待接听", "Waiting for answer")
                            "active" -> nativePhrase("通话中", "In call")
                            else -> nativePhrase("连接中", "Connecting")
                        },
                        modifier = if (duration != null) Modifier.testTag("native-call-duration") else Modifier,
                        fontSize = 11.sp, color = HlovetUi.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEnd, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.CallEnd, contentDescription = nativePhrase("结束通话", "End call"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
