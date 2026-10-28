package xyz.hlovet.portal.prototype

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 核对通话对方身份的服务端字段保留及空值边界，不通过名称推断参与者或网络地址。 */
class NativeCallSessionParserTest {
    /** 昵称、账号名、头像与双方编号分别保留，头像不能错误读取发起者或当前账号。 */
    @Test fun counterpartIdentityAndAvatarArePreserved() {
        // 接口中的 user 已经按当前查看者选好对方，不在客户端再次换算双方身份。
        val call = NativeSocialApi.parseCallSession(JSONObject()
            .put("id", 12).put("conversationId", 34).put("callerId", 56).put("calleeId", 78)
            .put("type", "video").put("status", "active").put("acceptedAt", "2026-10-26T13:00:00Z")
            .put("user", JSONObject().put("id", 56).put("nickname", "对方昵称")
                .put("username", "counterpart").put("avatarUrl", "/users/56/avatar")))
        // 名称、头像、时间及稳定编号独立断言，不能由界面有一个缺省头像替代字段验收。
        assertEquals(12, call.id)
        assertEquals(34, call.conversationId)
        assertEquals(56, call.userId)
        assertEquals(78, call.calleeId)
        assertEquals("对方昵称", call.userName)
        assertEquals("counterpart", call.userUsername)
        assertEquals("/users/56/avatar", call.userAvatarUrl)
        assertEquals("2026-10-26T13:00:00Z", call.acceptedAt)
    }

    /** 缺省、空白和 JSON null 昵称均回退到账号名，缺省头像不发起字符串 null 请求。 */
    @Test fun nullNicknameFallsBackToUsernameWithoutInventingAvatar() {
        // 同时覆盖字段不存在、JSON null 和历史兼容字符串，保留普通账号名。
        for (missing in listOf<Any?>(null, "", "  ", JSONObject.NULL, "null")) {
            val user = JSONObject().put("username", "fallback_user")
            if (missing != null) user.put("nickname", missing).put("avatarUrl", missing)
            val call = NativeSocialApi.parseCallSession(JSONObject().put("user", user))
            assertEquals("fallback_user", call.userName)
            assertNull(call.userAvatarUrl)
        }
    }

    /** 完全缺失身份时不在解析期写死中文，让存活通话切换语言后立即更新缺省名称。 */
    @Test fun absentCounterpartKeepsNameEmptyForLocalizedPresentation() {
        // 参与者信息缺省时仍保留通话状态，展示层不能把字符串 null 当作用户名。
        for (user in listOf<Any?>(null, JSONObject.NULL, JSONObject().put("nickname", JSONObject.NULL).put("username", JSONObject.NULL))) {
            val payload = JSONObject().put("id", 9)
            if (user != null) payload.put("user", user)
            val call = NativeSocialApi.parseCallSession(payload)
            assertEquals(9, call.id)
            assertEquals("", call.userName)
            assertEquals("", call.userUsername)
            assertNull(call.userAvatarUrl)
        }
    }
}
