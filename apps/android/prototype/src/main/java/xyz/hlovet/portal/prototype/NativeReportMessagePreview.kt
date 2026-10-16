package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 在本人举报与后台举报中展示 report 的群消息，session 仅用于既有附件权限与缓存隔离。 */
@Composable
internal fun NativeReportMessagePreview(report: NativeModerationReport, session: NativeSession?) {
    // 举报中心模型转换为已有消息模型，所有入口共用同一套媒体组件。
    val message = report.message ?: return
    val conversationId = report.group?.conversationId ?: 0
    val content = NativeChatMessage(
        id = message.id, conversationId = conversationId, body = message.body, type = message.type,
        senderName = message.sender.nickname, senderUsername = message.sender.username,
        senderInitials = message.sender.nickname.ifBlank { message.sender.username }.takeLast(2),
        quote = null, isPinned = false, createdAt = message.createdAt,
        attachments = message.attachments.map { attachment ->
            NativeChatAttachmentPreview(attachment.id, conversationId, attachment.kind, attachment.originalName,
                attachment.mimeType, attachment.sizeBytes, attachment.downloadUrl, attachment.thumbnailUrl, message.createdAt)
        },
        senderAvatarUrl = message.sender.avatarUrl,
    )
    NativeReportMessageContent(content, session, report.key)
}

/** 在群内管理和后台群聊管理展示 report；session 维持当前账号原有媒体访问权限。 */
@Composable
internal fun NativeGroupReportMessagePreview(report: NativeAdminGroupReport, session: NativeSession?) {
    // 群举报已经包含标准消息模型，不构造虚假的治理举报或重新请求消息正文。
    NativeReportMessageContent(report.message, session, "group-report-${report.id}")
}

/** 展示 message 的正文与媒体；previewKey 隔离不同举报，session 隔离账号及下载生命周期。 */
@Composable
private fun NativeReportMessageContent(message: NativeChatMessage, session: NativeSession?, previewKey: String) {
    // 切换账号或举报后销毁图片、下载及播放器，不复用旧账号的媒体状态。
    key(session?.userId, session?.accessToken, previewKey) {
        val owner = NativeOfflineOwner.forSession(session)
        var preview by remember { mutableStateOf<NativeChatAttachmentPreview?>(null) }
        Column(Modifier.fillMaxWidth().testTag("report-message-${message.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeCoverImage(message.senderAvatarUrl, Modifier.size(30.dp).clip(HlovetUi.rowShape),
                    fallbackText = message.senderInitials, accessToken = session?.accessToken, cacheOwner = owner)
                Column(Modifier.weight(1f)) {
                    Text("${message.senderName} (@${message.senderUsername})", color = HlovetUi.muted, fontSize = 11.sp)
                    Text(message.createdAt.replace('T', ' ').take(16), color = HlovetUi.muted, fontSize = 10.sp)
                }
            }
            // 消息正文保留完整换行，附件复用聊天中的缩略图、行内音视频和系统文件操作。
            if (message.body.isNotBlank()) Text(message.body, fontSize = 13.sp)
            if (session == null && message.attachments.isNotEmpty()) {
                Text(nativePhrase("请登录后查看附件。", "Sign in to view attachments."), color = HlovetUi.muted, fontSize = 12.sp)
            } else {
                message.attachments.forEach { attachment ->
                    key(attachment.id) {
                        Column(Modifier.fillMaxWidth().testTag("report-attachment-${attachment.id}")) {
                            NativeChatAttachmentItem(attachment, session?.accessToken, owner, onPreview = { preview = attachment })
                        }
                    }
                }
            }
        }
        // 图片弹窗保留当前举报在背景，关闭后继续查看同一条消息。
        preview?.let { NativeChatImagePreviewDialog(it, session?.accessToken, owner, onDismiss = { preview = null }) }
    }
}
