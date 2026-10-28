package xyz.hlovet.portal.prototype

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

/** 核对授权目标身份及系统结果边界，避免空回调或旧来电决定引发未经确认的通话。 */
class NativeCallPermissionTest {
    /** 生成编号为 id 的来电夹具，保持同一会话以证明不能仅按会话匹配。 */
    private fun incoming(id: Int = 10, type: String = "voice"): NativeCallSession {
        // 固定参与者和会话，编号、类型及阶段由各场景明确改变。
        return NativeCallSession(id, 7, type, "ringing", 1, 2, 1, "来电用户", "caller", null)
    }

    /** 发起授权只在原账号且没有插入另一通电话时继续。 */
    @Test fun outgoingGrantCannotReplaceExistingCallOrCrossAccount() {
        // 用户等待系统窗口时收到来电或退出账号，旧主动呼叫不应覆盖当前状态。
        val request = NativeCallPermissionRequest("owner", 7, "voice")
        assertTrue(request.matchesCurrentCall("owner", null, null, 0))
        assertFalse(request.matchesCurrentCall("another-owner", null, null, 0))
        assertFalse(request.matchesCurrentCall("owner", incoming(), "incoming", 0))
    }

    /** 接听授权必须仍对应原始编号，同一会话的新来电不能沿用旧决定。 */
    @Test fun incomingGrantIsBoundToExactCallAndOwner() {
        // 来电身份使用稳定编号而非昵称或会话名称。
        val request = NativeCallPermissionRequest("owner", 7, "voice", incomingCallId = 10)
        assertTrue(request.matchesCurrentCall("owner", incoming(), "incoming", 0))
        assertFalse(request.matchesCurrentCall("owner", incoming(11), "incoming", 0))
        assertFalse(request.matchesCurrentCall("another-owner", incoming(), "incoming", 0))
        assertFalse(request.matchesCurrentCall("owner", null, null, 0))
    }

    /** 原来电结束、被其他设备接听或类型改变后，即使编号相同也不能续接旧授权。 */
    @Test fun endedClaimedOrChangedIncomingCallCannotResume() {
        // 同时防守 UI 结束标志早于 clearCall 执行，以及服务端更新尚未卸载面板的窗口。
        val request = NativeCallPermissionRequest("owner", 7, "voice", incomingCallId = 10)
        assertFalse(request.matchesCurrentCall("owner", incoming(), "incoming", 10))
        assertFalse(request.matchesCurrentCall("owner", incoming(), "connecting", 0))
        assertFalse(request.matchesCurrentCall("owner", incoming(type = "video"), "incoming", 0))
        assertFalse(request.matchesCurrentCall("owner", incoming().copy(conversationId = 8), "incoming", 0))
    }

    /** 空结果或不属于本次通话的权限回调不是成功，纵使系统已有授权也不能自动接听。 */
    @Test fun emptyAndUnrelatedResultsAreNotConsent() {
        // 验证 all(empty)=true 不再导致用户关闭系统窗口后继续通话。
        assertFalse(nativeCallPermissionsGranted("voice", emptyMap()) { true })
        assertFalse(nativeCallPermissionsGranted("voice", mapOf(Manifest.permission.CAMERA to true)) { true })
    }

    /** 用户拒绝或系统实际状态仍为拒绝时不能信任回调 true。 */
    @Test fun deniedOrMissingSystemGrantCannotResume() {
        // 回调与系统回读都必须成立，不能用过时的 UI 结果启动设备。
        assertFalse(nativeCallPermissionsGranted("voice", mapOf(Manifest.permission.RECORD_AUDIO to false)) { true })
        assertFalse(nativeCallPermissionsGranted("voice", mapOf(Manifest.permission.RECORD_AUDIO to true)) { false })
    }

    /** 视频必须具有两项权限，但允许只为本次尚缺少的摄像头弹窗。 */
    @Test fun videoRequiresBothPermissionsIncludingPreviouslyGrantedMicrophone() {
        // 已授予麦克风可不在 result 中，但必须从实际系统状态读取为 true。
        val cameraResult = mapOf(Manifest.permission.CAMERA to true)
        assertTrue(nativeCallPermissionsGranted("video", cameraResult) { true })
        assertFalse(nativeCallPermissionsGranted("video", cameraResult) { it == Manifest.permission.CAMERA })
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), nativeCallRequiredPermissions("voice"))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), nativeCallRequiredPermissions("video"))
    }
}
