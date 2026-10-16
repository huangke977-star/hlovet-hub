package xyz.hlovet.portal.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMessagesRouteTest {
    @Test
    fun conversationRouteAcceptsConversationIdAliasAndKeepsFocus() {
        val route = parseNativeMessagesRouteParameters(mapOf("conversationId" to "12", "messageId" to "88"))

        assertEquals(NativeMessagesRouteKind.Conversation, route.kind)
        assertEquals(12, route.conversationId)
        assertEquals(88, route.messageId)
    }

    @Test
    fun conversationRouteWinsWhenOtherNotificationParametersArePresent() {
        val route = parseNativeMessagesRouteParameters(mapOf("conversation" to "12", "friendshipId" to "3", "groupApproval" to "9"))

        assertEquals(NativeMessagesRouteKind.Conversation, route.kind)
        assertEquals(12, route.conversationId)
    }

    @Test
    fun friendshipRouteOpensPeoplePage() {
        val route = parseNativeMessagesRouteParameters(mapOf("friendshipId" to "3"))

        assertEquals(NativeMessagesRouteKind.Friends, route.kind)
        assertEquals(3, route.friendshipId)
    }

    @Test
    fun groupApprovalRoutePreservesJoinRequestAndReportTargets() {
        val route = parseNativeMessagesRouteParameters(mapOf("groupApproval" to "9", "joinRequest" to "31", "report" to "44"))

        assertEquals(NativeMessagesRouteKind.GroupManager, route.kind)
        assertEquals(9, route.groupId)
        assertEquals(31, route.joinRequestId)
        assertEquals(44, route.reportId)
    }

    @Test
    fun deletedMessageMarkerIsRetainedBeforeRouting() {
        val route = parseNativeMessagesRouteParameters(mapOf("conversation" to "12", "messageId" to "88", "messageDeleted" to "1"))

        assertTrue(route.messageDeleted)
        assertEquals(NativeMessagesRouteKind.Conversation, route.kind)
    }
}
