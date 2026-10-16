package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.json.JSONObject

class NativeSocialNotificationTest {
    @Test
    fun actionableNotificationKindsMatchPcInlineActions() {
        assertEquals(
            NativeNotificationActionType.FriendRequest,
            nativeNotificationActionType("friend_request", actionable = true),
        )
        assertEquals(
            NativeNotificationActionType.StrangerMessageRequest,
            nativeNotificationActionType("stranger_message_request", actionable = true),
        )
        assertEquals(
            NativeNotificationActionType.GroupInvitation,
            nativeNotificationActionType("group_invitation", actionable = true),
        )
        assertEquals(
            NativeNotificationActionType.GroupJoinRequest,
            nativeNotificationActionType("group_join_request", actionable = true),
        )
        assertEquals(
            NativeNotificationActionType.GroupReport,
            nativeNotificationActionType("group_report", actionable = true),
        )
    }

    @Test
    fun nonActionableOrUnknownNotificationsDoNotShowActionButtons() {
        assertEquals(
            NativeNotificationActionType.None,
            nativeNotificationActionType("friend_request", actionable = false),
        )
        assertEquals(
            NativeNotificationActionType.None,
            nativeNotificationActionType("article_comment", actionable = true),
        )
        assertEquals(
            NativeNotificationActionType.None,
            nativeNotificationActionType(null, actionable = true),
        )
    }

    @Test
    fun notificationContextRetainsPcRequestAndReportDetails() {
        val friend = NativeSocialApi.parseNativeNotification(
            JSONObject(
                """
                {
                  "id": 11,
                  "title": "新的好友申请",
                  "body": "有人申请添加你为好友",
                  "context": {
                    "kind": "friend_request",
                    "actionable": true,
                    "requestNote": "想和你交流文章写作"
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("想和你交流文章写作", friend.requestNote)
        assertEquals(NativeNotificationActionType.FriendRequest, friend.actionType)

        val report = NativeSocialApi.parseNativeNotification(
            JSONObject(
                """
                {
                  "id": 12,
                  "title": "群消息待处理举报",
                  "body": "有一条群消息举报待处理",
                  "context": {
                    "kind": "group_report",
                    "groupId": 7,
                    "reportId": 19,
                    "actionable": true,
                    "group": {"name": "产品讨论组"},
                    "message": {"id": 32, "conversationId": 44, "body": "需要审核的群消息", "type": "text"}
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("产品讨论组", report.groupName)
        assertEquals(19, report.reportId)
        assertEquals(NativeNotificationActionType.GroupReport, report.actionType)
        assertNotNull(report.reportMessage)
        assertEquals("需要审核的群消息", report.reportMessage?.body)
    }
}
