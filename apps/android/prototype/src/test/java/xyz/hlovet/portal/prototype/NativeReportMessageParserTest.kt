package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 核对群举报沿用消息解析时保留头像、群昵称及原认证附件地址。 */
class NativeReportMessageParserTest {
    /** 展示群昵称不能替代账号标识，头像和附件来源不被客户端重写。 */
    @Test fun aliasAvatarAndAttachmentArePreserved() {
        // 使用服务端消息结构，避免为举报入口另建字段语义。
        val message = NativeSocialApi.parseChatMessage(JSONObject()
            .put("id", 91).put("conversationId", 82).put("senderDisplayName", "群内昵称")
            .put("sender", JSONObject().put("username", "sender").put("nickname", "原昵称").put("avatarUrl", "/auth/users/35/avatar"))
            .put("attachments", JSONArray().put(JSONObject().put("id", 71).put("kind", "image")
                .put("downloadUrl", "/social/attachments/71/download").put("thumbnailUrl", "/social/attachments/71/thumbnail"))))
        // 发送者展示字段和附件授权 URL 分别核对，不能用名称参与权限判断。
        assertEquals("群内昵称", message.senderName)
        assertEquals("sender", message.senderUsername)
        assertEquals("昵称", message.senderInitials)
        assertEquals("/auth/users/35/avatar", message.senderAvatarUrl)
        assertEquals("/social/attachments/71/download", message.attachments.single().downloadUrl)
    }

    /** 未设置头像的缺省、空串及 JSON null 都保持空值，交给统一头像缺省样式。 */
    @Test fun missingAvatarDoesNotBecomeLiteralNull() {
        // 覆盖接口的三种空值形态，不生成字符串 null 的网络请求。
        for (avatar in listOf<Any?>(null, "", JSONObject.NULL)) {
            val sender = JSONObject().put("username", "sender").put("nickname", "发送者")
            if (avatar != null) sender.put("avatarUrl", avatar)
            val message = NativeSocialApi.parseChatMessage(JSONObject().put("sender", sender))
            assertNull(message.senderAvatarUrl)
            assertEquals("送者", message.senderInitials)
        }
    }
}
