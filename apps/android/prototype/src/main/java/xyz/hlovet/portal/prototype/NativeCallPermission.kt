package xyz.hlovet.portal.prototype

import android.Manifest

/** 绑定一次系统授权的账号、会话及原始来电；incomingCallId 为空表示用户主动发起通话。 */
internal data class NativeCallPermissionRequest(
    val sessionKey: String,
    val conversationId: Int,
    val type: String,
    val incomingCallId: Int? = null,
    val connectionVersion: Long = 0,
) {
    /** 对照 owner、call、phase、结束编号和当前 connectionVersion，只允许同一连接代次的原始授权继续。 */
    fun matchesCurrentCall(owner: String, call: NativeCallSession?, phase: String?, terminatedCallId: Int, connectionVersion: Long = 0): Boolean {
        // 系统窗口可能跨越退出登录、来电结束和下一通邀请，不能把旧决定用于新目标。
        if (sessionKey != owner || this.connectionVersion != connectionVersion) return false
        if (incomingCallId == null) return call == null
        return call?.id == incomingCallId && call.id != terminatedCallId &&
            call.conversationId == conversationId && call.type == type && phase == "incoming"
    }
}

/** 按 type 返回通话必须具备的权限；视频同时需要摄像头与麦克风，语音只需要麦克风。 */
internal fun nativeCallRequiredPermissions(type: String): List<String> {
    // 保持与服务端 voice/video 合约一致，不因已授权其中一项而跳过另一项。
    return if (type == "video") listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    else listOf(Manifest.permission.RECORD_AUDIO)
}

/** 核对 type 的 result 与当前系统 isGranted 状态；空回调、拒绝或只授予部分权限均不能续接。 */
internal fun nativeCallPermissionsGranted(type: String, result: Map<String, Boolean>, isGranted: (String) -> Boolean): Boolean {
    // ActivityResult 只包含本次申请项，已授权项仍须从系统回读；空结果不是 all=true 的成功。
    val required = nativeCallRequiredPermissions(type)
    return result.keys.any { it in required } && result.values.all { it } && required.all(isGranted)
}
