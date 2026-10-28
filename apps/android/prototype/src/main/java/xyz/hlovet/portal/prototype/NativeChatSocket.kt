package xyz.hlovet.portal.prototype

import android.os.Handler
import android.os.Looper
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import io.socket.thread.EventThread
import java.util.Timer
import java.util.TimerTask
import org.json.JSONObject

/** 管理 accessToken 对应的聊天信令；认证在事件线程串行，消息/通话与操作回执在主线程交回宿主。 */
internal class NativeChatSocket(
    accessToken: String,
    private val onMessage: (NativeChatMessage) -> Unit,
    private val onRead: (conversationId: Int, readAt: String) -> Unit,
    private val onError: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onMessagesDeleted: (conversationId: Int, messageIds: List<Int>) -> Unit = { _, _ -> },
    private val onConversationCleared: (conversationId: Int) -> Unit = {},
    private val onConversationDeleted: (conversationId: Int) -> Unit = {},
    private val onCallIncoming: (NativeCallSession) -> Unit = {},
    private val onCallAccepted: (NativeCallSession) -> Unit = {},
    private val onCallClaimed: (call: NativeCallSession, acceptedHere: Boolean) -> Unit = { _, _ -> },
    private val onCallSignal: (callId: Int, signal: JSONObject) -> Unit = { _, _ -> },
    private val onCallActive: (NativeCallSession) -> Unit = {},
    private val onCallEnded: (NativeCallSession) -> Unit = {},
    private val onReauthenticate: (((String?) -> Unit) -> Unit) = { complete -> complete(null) },
) {
    private var accessToken: String = accessToken
    private var socket: Socket? = null
    private var reauthenticationInProgress = false
    private val handshakeAuth = mutableMapOf("token" to accessToken)
    private var ready = false
    private var connectionReportedReady = false
    private var authenticatedToken: String? = null
    private var pendingAuthenticationToken: String? = null
    private var authenticationVersion = 0L
    private var tokenVersion = 0L
    private var refreshVersion = 0L
    private var authenticationTimer: Timer? = null
    private val callbackHandler = Handler(Looper.getMainLooper())
    @Volatile private var callbackGeneration = 0L

    private val socketOrigin: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').let { base ->
            base.substringBefore("/api").ifBlank { "https://5200918.xyz" }
        }

    /** 建立唯一的聊天连接；只有服务端完成鉴权后才通知可用，临时断网持续自动重试。 */
    fun connect() {
        // 所有认证状态与 Socket.IO 回调使用同一事件线程，避免主线程更新和 ACK 交错覆盖。
        EventThread.exec { connectOnEventThread() }
    }

    /** 在 Socket.IO 事件线程注册监听并建立连接，重连时继续使用共享的最新握手凭据。 */
    private fun connectOnEventThread() {
        // 同一适配器只持有一个 Socket，重连仍复用原实例，不创建重复的来电监听。
        socket?.let { existing ->
            if (!existing.connected()) existing.connect()
            return
        }
        val options = IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1_000
            reconnectionDelayMax = 8_000
            forceNew = true
            auth = handshakeAuth
        }
        val instance = runCatching { IO.socket("$socketOrigin/chat", options) }
            .getOrElse {
                deliverCallback { onError(nativePhrase("聊天连接初始化失败。", "Could not initialize the chat connection.")) }
                return
            }
        socket = instance
        // 底层 WebSocket 连通并不等于服务端 JWT 和会话校验完成，避免提前开放通话按钮。
        instance.on("chat:ready") {
            // ready 前可能已更新令牌，首次就绪后再次确认最新凭据，不猜测握手使用了哪个快照。
            if (socket !== instance) return@on
            ready = true
            authenticateLatest(instance)
        }
        instance.on(Socket.EVENT_DISCONNECT) { args ->
            // 服务端可能先断开再返回拒绝 ACK；仅该明确原因提示认证失败，普通断网不误报密码失效。
            val rejected = args.firstOrNull() == "io server disconnect" && pendingAuthenticationToken != null
            resetAuthentication()
            deliverCallback { onConnectionChanged(false) }
            if (rejected) deliverCallback {
                onError(nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again."))
            }
        }
        instance.on(Socket.EVENT_CONNECT_ERROR) {
            // 自动重试期间由宿主连接状态反馈，不留下 Java 异常或反复弹出相同错误。
            resetAuthentication()
            deliverCallback { onConnectionChanged(false) }
        }
        // 服务端主动发送的业务/认证错误仍需显示，不能把权限失效当作普通网络重试吞掉。
        instance.on("chat:error") { args ->
            val message = (args.firstOrNull() as? JSONObject)?.optString("message")
            deliverCallback { onError(message?.takeIf { it.isNotBlank() } ?: nativePhrase("聊天连接出现问题。", "The chat connection reported an error.")) }
        }
        instance.on("chat:reauthenticate") {
            requestReauthentication(instance)
        }
        // 普通消息、已读及删除事件按会话交回现有页面，避免重连时重复创建列表状态。
        instance.on("chat:message") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseChatMessage(json) }
                .onSuccess { message -> deliverCallback { onMessage(message) } }
                .onFailure { deliverCallback { onError(nativePhrase("收到的消息格式无法识别。", "The received message format is invalid.")) } }
        }
        instance.on("chat:read") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            deliverCallback { onRead(json.optInt("conversationId"), json.optString("readAt")) }
        }
        instance.on("chat:messages-deleted") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val ids = json.optJSONArray("messageIds")?.let { values ->
                buildList {
                    for (index in 0 until values.length()) {
                        values.optInt(index).takeIf { it > 0 }?.let(::add)
                    }
                }
            }.orEmpty()
            if (ids.isNotEmpty()) deliverCallback { onMessagesDeleted(json.optInt("conversationId"), ids) }
        }
        instance.on("chat:conversation-cleared") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            deliverCallback { onConversationCleared(json.optInt("conversationId")) }
        }
        instance.on("chat:conversation-deleted") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            deliverCallback { onConversationDeleted(json.optInt("conversationId")) }
        }
        // 通话事件由全局宿主持有；断线清理与在途操作失效由同一宿主负责。
        instance.on("call:incoming") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess { call -> deliverCallback { onCallIncoming(call) } }
                .onFailure { deliverCallback { onError(nativePhrase("收到的通话邀请格式无法识别。", "The received call invite format is invalid.")) } }
        }
        instance.on("call:accepted") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess { call -> deliverCallback { onCallAccepted(call) } }
                .onFailure { deliverCallback { onError(nativePhrase("通话接听状态无法识别。", "The call acceptance state is invalid.")) } }
        }
        instance.on("call:claimed") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val call = payload.optJSONObject("call") ?: return@on
            // 接听归属来自服务端 Socket 编号，不能由媒体是否已进入 Compose 快照推测。
            val acceptedHere = nativeCallClaimMatchesSocket(instance.id(), payload.optString("acceptedBySocketId"))
            runCatching { NativeSocialApi.parseCallSession(call) }
                .onSuccess { call -> deliverCallback { onCallClaimed(call, acceptedHere) } }
                .onFailure { deliverCallback { onError(nativePhrase("通话接听状态无法识别。", "The call acceptance state is invalid.")) } }
        }
        instance.on("call:signal") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val signal = payload.optJSONObject("signal") ?: return@on
            deliverCallback { onCallSignal(payload.optInt("callId"), signal) }
        }
        instance.on("call:active") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess { call -> deliverCallback { onCallActive(call) } }
                .onFailure { deliverCallback { onError(nativePhrase("通话连接状态无法识别。", "The call connection state is invalid.")) } }
        }
        instance.on("call:ended") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess { call -> deliverCallback { onCallEnded(call) } }
                .onFailure { deliverCallback { onError(nativePhrase("通话结束状态无法识别。", "The call end state is invalid.")) } }
        }
        // 完成全部监听后才开始握手，首个 ready 或来电事件不会因注册顺序丢失。
        instance.connect()
    }

    /** 用户显式重试时先请求宿主刷新凭据，再恢复当前 Socket；连续点击不重复刷新或重拨电话。 */
    fun reconnect() {
        // 已关闭适配器不能因旧页面点击复活；正常在线或正在等待 ACK 时不打断现有认证。
        EventThread.exec {
            val instance = socket ?: return@exec
            if (instance.connected() && (connectionReportedReady || pendingAuthenticationToken != null)) return@exec
            requestReauthentication(instance)
        }
    }

    /** 为 instance 合并宿主刷新请求；成功只更新本账号原连接，失败关闭连接并保留可重试入口。 */
    private fun requestReauthentication(instance: Socket) {
        // 服务端到期提示和用户重试共享一条刷新流程，避免同时轮换同一个 refreshToken。
        if (socket !== instance || reauthenticationInProgress) return
        reauthenticationInProgress = true
        val request = ++refreshVersion
        val startedTokenVersion = tokenVersion
        val wasConnected = instance.connected()
        deliverCallback {
            onReauthenticate { refreshedToken ->
                // 宿主刷新异步完成；关闭、断线或新的刷新已经作废的结果不得恢复旧连接。
                EventThread.exec refresh@ {
                    if (socket !== instance || refreshVersion != request) return@refresh
                    reauthenticationInProgress = false
                    if (refreshedToken.isNullOrBlank()) {
                        deliverCallback { onError(nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again.")) }
                        // 在线到期认证失败必须关闭；离线重试失败不能取消底层已有的网络自动恢复。
                        if (wasConnected) instance.disconnect()
                        return@refresh
                    }
                    // Compose 可能已同步了更新的令牌；迟到结果不能覆盖更新版本。
                    if (tokenVersion == startedTokenVersion || refreshedToken.trim() == accessToken) {
                        setLatestToken(refreshedToken.trim())
                    }
                    authenticatedToken = null
                    if (instance.connected()) authenticateLatest(instance) else instance.connect()
                }
            }
        }
    }

    /** 将 nextToken 更新为后续握手凭据，并在已鉴权的原连接上认证，不重新创建 Socket 或中断通话。 */
    fun updateAccessToken(nextToken: String) {
        val normalized = nextToken.trim()
        // 空值不改变当前登录态；更新操作与认证回执串行，同值更新不产生重复请求。
        if (normalized.isBlank()) return
        EventThread.exec {
            if (normalized != accessToken) {
                setLatestToken(normalized)
                socket?.let { instance ->
                    // 新的有效登录凭据也能恢复被主动断开的连接；正在刷新时由同一回调统一恢复。
                    if (!instance.connected() && !reauthenticationInProgress) instance.connect()
                    else authenticateLatest(instance)
                }
            }
        }
    }

    /** 保存 nextToken 到当前目标和共享握手 Map；Socket.IO 2.1 直接持有该 Map，不依赖私有字段反射修改。 */
    private fun setLatestToken(nextToken: String) {
        // 仅在事件线程写入；正在发送的旧认证回执不会回写 accessToken。
        if (nextToken == accessToken) return
        accessToken = nextToken
        handshakeAuth["token"] = nextToken
        tokenVersion += 1
    }

    /** 为 instance 串行认证最新令牌；在途期间的新值等当前 ACK 后继续提交，不丢失后续更新。 */
    private fun authenticateLatest(instance: Socket) {
        // 断网或尚未 ready 时只更新下一次握手，不把重连缓冲中的认证请求送给错误连接。
        if (socket !== instance || !ready || !instance.connected() || pendingAuthenticationToken != null || authenticatedToken == accessToken) return
        val sentToken = accessToken
        val request = ++authenticationVersion
        pendingAuthenticationToken = sentToken
        // 有界等待认证 ACK；超时必须关闭失去认证保障的连接，不能继续展示为可用。
        authenticationTimer = Timer("HlovetChatAuthentication", true).apply {
            schedule(object : TimerTask() {
                /** 超时回到事件线程核对请求版本，已完成或已换连接的旧计时器没有副作用。 */
                override fun run() {
                    // 不记录令牌或服务端底层异常，通话由真实 disconnect 走正常清理。
                    EventThread.exec {
                        if (socket === instance && authenticationVersion == request && pendingAuthenticationToken != null) {
                            resetAuthentication()
                            deliverCallback { onError(nativePhrase("聊天连接认证超时，请重新连接。", "Chat authentication timed out. Please reconnect.")) }
                            instance.disconnect()
                        }
                    }
                }
            }, 10_000)
        }
        instance.emit(
            "chat:authenticate",
            JSONObject().put("token", sentToken),
            Ack { args ->
                // 旧 Socket、旧超时或已作废请求的 ACK 不能覆盖当前认证状态。
                if (socket !== instance || authenticationVersion != request) return@Ack
                authenticationTimer?.cancel()
                authenticationTimer = null
                pendingAuthenticationToken = null
                val response = args.firstOrNull() as? JSONObject
                val ok = response?.optBoolean("ok") == true
                if (ok) {
                    authenticatedToken = sentToken
                    authenticateLatest(instance)
                    // 初次连接必须等最新凭据 ACK 成功才开放操作；在线轮换不重复通知或中断媒体。
                    if (sentToken == accessToken && !connectionReportedReady) {
                        connectionReportedReady = true
                        deliverCallback { onConnectionChanged(true) }
                    }
                } else {
                    // 拒绝重新认证时与服务端一致关闭，不让非法或其它账号的令牌维持旧连接。
                    deliverCallback { onError(nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again.")) }
                    instance.disconnect()
                }
            },
        )
    }

    /** 断线或释放时作废在途 ACK、超时和刷新回调，保留最新握手令牌供正常重连。 */
    private fun resetAuthentication() {
        // 生命周期失效只清认证过程，不清除当前账号凭据，也不补发旧通话操作。
        authenticationVersion += 1
        refreshVersion += 1
        authenticationTimer?.cancel()
        authenticationTimer = null
        pendingAuthenticationToken = null
        authenticatedToken = null
        ready = false
        connectionReportedReady = false
        reauthenticationInProgress = false
    }

    /** 将 callback 按事件顺序交给主线程；关闭前排队的旧消息和回执不再触发已销毁界面。 */
    private fun deliverCallback(callback: () -> Unit) {
        // 不能从 Socket 线程直接启动 Compose 宿主协程，测试调度器可能在发起线程同步恢复布局。
        val generation = callbackGeneration
        callbackHandler.post {
            if (callbackGeneration == generation) callback()
        }
    }

    /** 向 conversationId 发送 body、附件和引用；onResult 在主线程返回成功标志及失败说明。 */
    fun send(
        conversationId: Int,
        body: String,
        attachmentIds: List<Int> = emptyList(),
        quotedMessageId: Int? = null,
        onResult: (Boolean, String?) -> Unit,
    ) {
        // 尚未连接时直接反馈，不缓冲可能在切换账号后误发的消息。
        val instance = socket
        if (instance == null || !instance.connected()) {
            deliverCallback { onResult(false, nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again.")) }
            return
        }
        // 消息内容及稳定引用编号维持服务端既有接口，附件权限仍由服务端检查。
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("body", body)
            put("attachmentIds", org.json.JSONArray(attachmentIds))
            quotedMessageId?.takeIf { it > 0 }?.let { put("quotedMessageId", it) }
        }
        // ACK 在事件线程解析后交回主线程，避免宿主界面协程从后台触发布局。
        instance.emit("chat:send", payload, Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok") == true
            deliverCallback { onResult(ok, if (ok) null else response?.optString("error").orEmpty().ifBlank { nativePhrase("消息发送失败。", "Message could not be sent.") }) }
        })
    }

    /** 提交 event 对应的消息操作 payload；onResult 在主线程返回业务状态，不暴露内部异常。 */
    fun mutate(
        event: String,
        payload: JSONObject,
        onResult: (Boolean, String?) -> Unit,
    ) {
        // 未连接不保存操作，防止删除等命令在后续连接上意外补发。
        val instance = socket
        if (instance == null || !instance.connected()) {
            deliverCallback { onResult(false, nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again.")) }
            return
        }
        // 保留原服务端业务回执，界面回调统一走主线程。
        instance.emit(event, payload, Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok") == true
            deliverCallback { onResult(ok, if (ok) null else response?.optString("error").orEmpty().ifBlank { nativePhrase("消息操作失败。", "Message operation failed.") }) }
        })
    }

    fun markRead(conversationId: Int) {
        socket?.takeIf { it.connected() }?.emit(
            "chat:read",
            JSONObject().put("conversationId", conversationId),
        )
    }

    fun startCall(conversationId: Int, type: String, onResult: (Boolean, NativeCallSession?, String?) -> Unit) {
        emitCallAck(
            event = "call:start",
            payload = JSONObject().put("conversationId", conversationId).put("type", type),
        ) { response ->
            val call = response?.optJSONObject("call")?.let(NativeSocialApi::parseCallSession)
            onResult(response?.optBoolean("ok") == true, call, response?.optString("error"))
        }
    }

    fun respondCall(callId: Int, accepted: Boolean, onResult: (Boolean, NativeCallSession?, String?) -> Unit = { _, _, _ -> }) {
        emitCallAck(
            event = "call:respond",
            payload = JSONObject().put("callId", callId).put("accepted", accepted),
        ) { response ->
            val call = response?.optJSONObject("call")?.let(NativeSocialApi::parseCallSession)
            onResult(response?.optBoolean("ok") == true, call, response?.optString("error"))
        }
    }

    fun sendCallSignal(callId: Int, signal: JSONObject) {
        socket?.takeIf { it.connected() }?.emit(
            "call:signal",
            JSONObject().put("callId", callId).put("signal", signal),
        )
    }

    fun markCallConnected(callId: Int) {
        socket?.takeIf { it.connected() }?.emit("call:connected", JSONObject().put("callId", callId))
    }

    fun endCall(callId: Int, reason: String? = null) {
        val payload = JSONObject().put("callId", callId)
        if (!reason.isNullOrBlank()) payload.put("reason", reason)
        socket?.takeIf { it.connected() }?.emit("call:end", payload)
    }

    /** 提交通话 event/payload；onResult 在主线程返回原 JSON，缺失或拒绝由上层正常解释。 */
    private fun emitCallAck(event: String, payload: JSONObject, onResult: (JSONObject?) -> Unit) {
        // 未连接时返回同形失败，不在恢复网络后自动重拨旧电话。
        val instance = socket
        if (instance == null || !instance.connected()) {
            deliverCallback { onResult(JSONObject().put("ok", false).put("error", nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again."))) }
            return
        }
        // 通话媒体和 Compose 状态只能由主线程接收确认结果。
        instance.emit(event, payload, Ack { args ->
            deliverCallback { onResult(args.firstOrNull() as? JSONObject) }
        })
    }

    /** 释放本适配器及认证计时器，串行关闭监听后通知宿主清理，不影响其它聊天连接。 */
    fun close() {
        // 与更新和 ACK 使用同一线程，已关闭实例的迟到回调无法重新激活认证。
        EventThread.exec {
            callbackGeneration += 1
            resetAuthentication()
            socket?.off()
            socket?.disconnect()
            socket = null
            deliverCallback { onConnectionChanged(false) }
        }
    }
}
