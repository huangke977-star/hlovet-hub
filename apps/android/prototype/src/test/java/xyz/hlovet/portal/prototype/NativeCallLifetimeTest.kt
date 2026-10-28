package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 验证代次覆盖断网、重新发起及旧系统授权，避免迟到结果影响后续通话。 */
class NativeCallLifetimeTest {
    /** 断网清理必须同时作废配置请求、通话回执和媒体监听器所持有的旧代次。 */
    @Test fun clearingInvalidatesAllOldResults() {
        // 多个业务阶段共用同一次尝试，任意阶段清理均不得继续使用原结果。
        val lifetime = NativeCallLifetime()
        val attempt = lifetime.begin()
        assertTrue(lifetime.isCurrent(attempt))
        lifetime.invalidate()
        assertFalse(lifetime.isCurrent(attempt))
    }

    /** 多次清理后重新发起也不能让旧编号恢复有效，避免断网重连后的 ABA 状态。 */
    @Test fun newAttemptCannotBeClearedByOldCallback() {
        // 新来电与旧通话可以使用相同好友会话，隔离依据只能是操作代次。
        val lifetime = NativeCallLifetime()
        val old = lifetime.begin()
        repeat(3) { lifetime.invalidate() }
        val fresh = lifetime.begin()
        assertFalse(lifetime.isCurrent(old))
        assertTrue(lifetime.isCurrent(fresh))
        assertNotEquals(old, fresh)
    }

    /** 系统权限窗口跨越断网恢复时，不能把旧的主动呼叫决定应用到新连接。 */
    @Test fun pendingPermissionCannotCrossConnectionLoss() {
        // 即便账号相同且当前没有来电，连接代次变化也必须拒绝续接旧授权。
        val lifetime = NativeCallLifetime()
        val request = NativeCallPermissionRequest("owner", 7, "voice", connectionVersion = lifetime.version)
        assertTrue(request.matchesCurrentCall("owner", null, null, 0, lifetime.version))
        lifetime.invalidate()
        assertFalse(request.matchesCurrentCall("owner", null, null, 0, lifetime.version))
    }
}
