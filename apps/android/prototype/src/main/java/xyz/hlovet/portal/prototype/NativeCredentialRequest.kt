package xyz.hlovet.portal.prototype

import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** 统一承载原生凭据读取，确保协程取消时同时关闭系统凭据选择页。 */
internal suspend fun requestNativeCredential(
    credentialManager: CredentialManager,
    context: Context,
    request: GetCredentialRequest,
    timeoutMillis: Long = 20_000L,
): GetCredentialResponse = withTimeout(timeoutMillis) {
    // 使用可取消的异步接口，避免 suspend 包装只取消协程而留下 Android 15 系统页面。
    suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation {
            // 超时、页面退出或宿主销毁时，通知 Credential Manager 结束提供方 Activity。
            cancellationSignal.cancel()
        }
        credentialManager.getCredentialAsync(
            context = context,
            request = request,
            cancellationSignal = cancellationSignal,
            executor = ContextCompat.getMainExecutor(context),
            callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                /** 接收系统凭据提供方返回的凭据。 */
                override fun onResult(result: GetCredentialResponse) {
                    // 回调可能晚于超时到达，已取消的协程不能再次恢复。
                    if (continuation.isActive) continuation.resume(result)
                }

                /** 接收系统凭据提供方返回的失败原因。 */
                override fun onError(e: GetCredentialException) {
                    // 将原生异常传回调用方，沿用现有的中文和英文提示映射。
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
            },
        )
    }
}
