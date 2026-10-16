package xyz.hlovet.portal.prototype

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject

/** Small Socket.IO adapter for the production chat gateway. */
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
    private val onCallClaimed: (NativeCallSession) -> Unit = {},
    private val onCallSignal: (callId: Int, signal: JSONObject) -> Unit = { _, _ -> },
    private val onCallActive: (NativeCallSession) -> Unit = {},
    private val onCallEnded: (NativeCallSession) -> Unit = {},
    private val onReauthenticate: (((String?) -> Unit) -> Unit) = { complete -> complete(null) },
) {
    private var accessToken: String = accessToken
    private var socket: Socket? = null
    private var reauthenticationInProgress = false

    private val socketOrigin: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').let { base ->
            base.substringBefore("/api").ifBlank { "https://5200918.xyz" }
        }

    fun connect() {
        if (socket != null) return
        val options = IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            reconnection = true
            reconnectionAttempts = 5
            reconnectionDelay = 1_000
            reconnectionDelayMax = 8_000
            forceNew = true
            auth = mapOf("token" to accessToken)
        }
        val instance = runCatching { IO.socket("$socketOrigin/chat", options) }
            .getOrElse {
                onError("聊天连接初始化失败。")
                return
            }
        socket = instance
        instance.on(Socket.EVENT_CONNECT) {
            onConnectionChanged(true)
        }
        instance.on(Socket.EVENT_DISCONNECT) {
            onConnectionChanged(false)
        }
        instance.on(Socket.EVENT_CONNECT_ERROR) { args ->
            onConnectionChanged(false)
            val message = args.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            onError(message ?: "聊天连接失败，请检查网络后重试。")
        }
        instance.on("chat:error") { args ->
            val message = (args.firstOrNull() as? JSONObject)?.optString("message")
            onError(message?.takeIf { it.isNotBlank() } ?: "聊天连接出现问题。")
        }
        instance.on("chat:reauthenticate") {
            if (reauthenticationInProgress) return@on
            reauthenticationInProgress = true
            onReauthenticate { refreshedToken ->
                if (refreshedToken.isNullOrBlank()) {
                    reauthenticationInProgress = false
                    onError(nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again."))
                } else {
                    reauthenticate(refreshedToken) { ok, message ->
                        reauthenticationInProgress = false
                        if (!ok) onError(message ?: nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again."))
                    }
                }
            }
        }
        instance.on("chat:message") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseChatMessage(json) }
                .onSuccess(onMessage)
                .onFailure { onError("收到的消息格式无法识别。") }
        }
        instance.on("chat:read") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            onRead(json.optInt("conversationId"), json.optString("readAt"))
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
            if (ids.isNotEmpty()) onMessagesDeleted(json.optInt("conversationId"), ids)
        }
        instance.on("chat:conversation-cleared") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            onConversationCleared(json.optInt("conversationId"))
        }
        instance.on("chat:conversation-deleted") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            onConversationDeleted(json.optInt("conversationId"))
        }
        instance.on("call:incoming") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess(onCallIncoming)
                .onFailure { onError("收到的通话邀请格式无法识别。") }
        }
        instance.on("call:accepted") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess(onCallAccepted)
                .onFailure { onError("通话接听状态无法识别。") }
        }
        instance.on("call:claimed") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val call = payload.optJSONObject("call") ?: return@on
            runCatching { NativeSocialApi.parseCallSession(call) }
                .onSuccess(onCallClaimed)
                .onFailure { onError("通话接听状态无法识别。") }
        }
        instance.on("call:signal") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val signal = payload.optJSONObject("signal") ?: return@on
            onCallSignal(payload.optInt("callId"), signal)
        }
        instance.on("call:active") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess(onCallActive)
                .onFailure { onError("通话连接状态无法识别。") }
        }
        instance.on("call:ended") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { NativeSocialApi.parseCallSession(json) }
                .onSuccess(onCallEnded)
                .onFailure { onError("通话结束状态无法识别。") }
        }
        instance.connect()
    }

    /** Rotating an access token must not recreate the owning screen or drop an active call. */
    fun updateAccessToken(nextToken: String) {
        val normalized = nextToken.trim()
        if (normalized.isBlank() || normalized == accessToken) return
        accessToken = normalized
        val previous = socket
        if (previous == null) return
        previous.off()
        previous.disconnect()
        socket = null
        connect()
    }

    /** Refreshes authentication in-place so an active chat/call is not dropped. */
    private fun reauthenticate(nextToken: String, onResult: (Boolean, String?) -> Unit) {
        val instance = socket
        if (instance == null || !instance.connected()) {
            onResult(false, nativePhrase("聊天连接已断开，请稍后重试。", "The chat connection is disconnected. Please try again."))
            return
        }
        instance.emit(
            "chat:authenticate",
            JSONObject().put("token", nextToken),
            Ack { args ->
                val response = args.firstOrNull() as? JSONObject
                val ok = response?.optBoolean("ok") == true
                if (ok) accessToken = nextToken
                onResult(
                    ok,
                    response?.optString("error")?.takeIf(String::isNotBlank)
                        ?: nativePhrase("聊天连接重新认证失败。", "Chat reauthentication failed."),
                )
            },
        )
    }

    fun send(
        conversationId: Int,
        body: String,
        attachmentIds: List<Int> = emptyList(),
        quotedMessageId: Int? = null,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val instance = socket
        if (instance == null || !instance.connected()) {
            onResult(false, nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again."))
            return
        }
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("body", body)
            put("attachmentIds", org.json.JSONArray(attachmentIds))
            quotedMessageId?.takeIf { it > 0 }?.let { put("quotedMessageId", it) }
        }
        instance.emit("chat:send", payload, Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok") == true
            onResult(ok, if (ok) null else response?.optString("error").orEmpty().ifBlank { nativePhrase("消息发送失败。", "Message could not be sent.") })
        })
    }

    fun mutate(
        event: String,
        payload: JSONObject,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val instance = socket
        if (instance == null || !instance.connected()) {
            onResult(false, nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again."))
            return
        }
        instance.emit(event, payload, Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok") == true
            onResult(ok, if (ok) null else response?.optString("error").orEmpty().ifBlank { nativePhrase("消息操作失败。", "Message operation failed.") })
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

    private fun emitCallAck(event: String, payload: JSONObject, onResult: (JSONObject?) -> Unit) {
        val instance = socket
        if (instance == null || !instance.connected()) {
            onResult(JSONObject().put("ok", false).put("error", nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Please try again.")))
            return
        }
        instance.emit(event, payload, Ack { args ->
            onResult(args.firstOrNull() as? JSONObject)
        })
    }

    fun close() {
        reauthenticationInProgress = false
        socket?.off()
        socket?.disconnect()
        socket = null
        onConnectionChanged(false)
    }
}
