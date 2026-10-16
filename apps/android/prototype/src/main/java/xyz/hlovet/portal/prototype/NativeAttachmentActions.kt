package xyz.hlovet.portal.prototype

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 携带已授权下载的本地文件及展示元数据，不保存令牌，也不重新请求服务器。 */
internal data class NativeAttachmentFile(val file: File, val originalName: String, val mimeType: String)

/** 将 originalName 规范为单个可展示文件名；缺失名称返回 attachment，不向外部应用暴露缓存摘要。 */
internal fun nativeAttachmentDisplayName(originalName: String): String {
    // 只保留末段文件名并移除控制字符，路径信息不能进入系统保存对话框。
    return originalName.replace('\\', '/').substringAfterLast('/')
        .filterNot { it.isISOControl() }.trim().take(240).ifBlank { "attachment" }
}

/** 将 mimeType 规范为不含参数的 MIME 类型；无效或通配类型返回通用二进制类型。 */
internal fun nativeAttachmentMimeType(mimeType: String): String {
    // 系统保存合同需要确定的文件类型，不传递 image/* 等选择器通配符。
    val value = mimeType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
    return value.takeIf { it.matches(Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) }
        ?: "application/octet-stream"
}

/** 用 context 为 attachment 构建系统打开请求，只授予指定内容 URI 的临时读取权限。 */
internal fun nativeAttachmentOpenIntent(context: Context, attachment: NativeAttachmentFile): Intent {
    // FileProvider 限制为既有媒体目录，原文件名作为查询元数据传递，不复制或公开私有目录。
    check(attachment.file.isFile) { nativePhrase("附件缓存已失效，请重新下载。", "The cached attachment is unavailable. Download it again.") }
    val name = nativeAttachmentDisplayName(attachment.originalName)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", attachment.file, name)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, nativeAttachmentMimeType(attachment.mimeType))
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** 使用 context 启动 attachment 的系统查看器；无匹配应用时由调用页面展示错误并保留另存为入口。 */
internal fun openNativeAttachment(context: Context, attachment: NativeAttachmentFile) {
    // 直接由系统解析，不用受包可见性限制的预查询判断是否存在查看器。
    context.startActivity(nativeAttachmentOpenIntent(context, attachment))
}

/** 将 attachment 有界复制到用户选择的 destination，返回写入字节数；取消或失败不显示保存成功。 */
internal suspend fun saveNativeAttachment(context: Context, attachment: NativeAttachmentFile, destination: Uri): Long = withContext(Dispatchers.IO) {
    // 系统合同只应返回可写内容 URI；源文件仍遵守移动端 50 MiB 的下载上限。
    require(destination.scheme == "content") { nativePhrase("保存位置无效。", "The save location is invalid.") }
    val limit = 50L * 1024L * 1024L
    check(attachment.file.isFile && attachment.file.length() in 1..limit) {
        nativePhrase("附件缓存不存在、为空或超过 50 MB，请重新下载。", "The cached attachment is missing, empty, or exceeds 50 MB. Download it again.")
    }
    // 复用 FileProvider 的目录白名单，只打开指定文档，不读取任意私有文件或取得长期存储权限。
    val source = requireNotNull(nativeAttachmentOpenIntent(context, attachment).data)
    var total = 0L
    val input = context.contentResolver.openInputStream(source)
        ?: error(nativePhrase("无法读取附件缓存。", "The cached attachment could not be read."))
    input.use {
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error(nativePhrase("无法写入所选位置。", "The selected location could not be written."))
        output.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= limit) { nativePhrase("附件超过 50 MB，已停止保存。", "The attachment exceeds 50 MB. Saving stopped.") }
                output.write(buffer, 0, count)
            }
        }
    }
    // 流完全关闭后才发布成功；失败不擅自删除用户选择位置中的已有文件。
    check(total > 0) { nativePhrase("附件为空，未完成保存。", "The attachment is empty. Saving did not complete.") }
    total
}

/** 为 attachment 提供统一玻璃面板；onDismiss 关闭当前文件，系统保存取消后保留面板以便重试。 */
@Composable
internal fun NativeAttachmentActionsDialog(attachment: NativeAttachmentFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(attachment) { mutableStateOf(false) }
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    var notice by remember(attachment) { mutableStateOf<String?>(null) }
    val name = nativeAttachmentDisplayName(attachment.originalName)
    // 只在用户确定保存目标后复制现有缓存，取消既不创建第二次下载，也不触发成功反馈。
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(nativeAttachmentMimeType(attachment.mimeType))) { destination ->
        busy = false
        if (destination == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                saveNativeAttachment(context, attachment, destination)
                notice = nativePhrase("附件已保存到所选位置。", "The attachment was saved to the selected location.")
            } catch (cancelled: CancellationException) {
                // 页面或账号切换取消协程，不把取消误报为保存失败或成功。
                throw cancelled
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("附件保存失败，请重试。", "Saving failed. Try again.")
            } finally {
                busy = false
            }
        }
    }
    // 所有附件入口共用已有账号外观及紧凑按钮，错误留在面板中，不挡住其它可用操作。
    HlovetAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HlovetFormFeedback(error = error, notice = notice)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = null
                notice = null
                try {
                    busy = true
                    savePicker.launch(name)
                } catch (exception: Exception) {
                    busy = false
                    error = nativePhrase("无法打开系统保存窗口。", "The system save dialog could not be opened.")
                }
            }) {
                Icon(Icons.Filled.SaveAlt, null)
                Spacer(Modifier.width(6.dp))
                Text(if (busy) nativePhrase("保存中…", "Saving...") else nativePhrase("另存为", "Save as"), fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                error = null
                try {
                    openNativeAttachment(context, attachment)
                } catch (exception: ActivityNotFoundException) {
                    error = nativePhrase("设备上没有可以打开此附件的应用，仍可另存为。", "No viewer is available for this attachment. You can still save it.")
                } catch (exception: Exception) {
                    error = exception.message ?: nativePhrase("附件打开失败，请重试。", "Opening failed. Try again.")
                }
            }) {
                Icon(Icons.Filled.FolderOpen, null)
                Spacer(Modifier.width(6.dp))
                Text(nativePhrase("打开", "Open"), fontSize = 13.sp)
            }
        },
    )
}
