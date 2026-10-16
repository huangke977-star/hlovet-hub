package xyz.hlovet.portal.prototype

import android.net.Uri

internal enum class NativeMessagesRouteKind {
    Inbox,
    Conversation,
    Friends,
    GroupManager,
}

internal data class NativeMessagesRoute(
    val kind: NativeMessagesRouteKind,
    val conversationId: Int? = null,
    val messageId: Int? = null,
    val friendshipId: Int? = null,
    val groupId: Int? = null,
    val joinRequestId: Int? = null,
    val reportId: Int? = null,
    val messageDeleted: Boolean = false,
)

internal fun parseNativeMessagesRoute(uri: Uri): NativeMessagesRoute = parseNativeMessagesRouteParameters(
    buildMap {
        uri.queryParameterNames.forEach { key ->
            uri.getQueryParameter(key)?.let { put(key, it) }
        }
    },
)

internal fun parseNativeMessagesRouteParameters(parameters: Map<String, String>): NativeMessagesRoute {
    val conversationId = (parameters["conversation"]
        ?: parameters["conversationId"])
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    val messageId = parameters["messageId"]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    val friendshipId = parameters["friendshipId"]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    val groupId = parameters["groupApproval"]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    val joinRequestId = parameters["joinRequest"]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
    val reportId = parameters["report"]
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    val kind = when {
        conversationId != null -> NativeMessagesRouteKind.Conversation
        friendshipId != null -> NativeMessagesRouteKind.Friends
        groupId != null -> NativeMessagesRouteKind.GroupManager
        else -> NativeMessagesRouteKind.Inbox
    }
    return NativeMessagesRoute(
        kind = kind,
        conversationId = conversationId,
        messageId = messageId,
        friendshipId = friendshipId,
        groupId = groupId,
        joinRequestId = joinRequestId,
        reportId = reportId,
        messageDeleted = parameters["messageDeleted"] == "1",
    )
}
