package xyz.hlovet.portal.prototype

import org.junit.Assert.*
import org.junit.Test

/** 核对通话接听事件的连接身份，避免 Compose 更新晚于实时事件时误结束本机通话。 */
class NativeCallClaimTest {
    /** 同一非空 Socket 才是本机接听；其它连接、缺失编号及空字符串都不是。 */
    @Test fun claimUsesActualSocketIdentityInsteadOfMediaSnapshot() {
        // 规则不读取媒体是否就绪，即便 UI 仍持有空媒体快照，本机 accepted 也必须保留。
        assertTrue(nativeCallClaimMatchesSocket("callee-socket", "callee-socket"))
        assertFalse(nativeCallClaimMatchesSocket("callee-socket", "another-device"))
        assertFalse(nativeCallClaimMatchesSocket(null, null))
        assertFalse(nativeCallClaimMatchesSocket("", ""))
        assertFalse(nativeCallClaimMatchesSocket("callee-socket", null))
    }
}
