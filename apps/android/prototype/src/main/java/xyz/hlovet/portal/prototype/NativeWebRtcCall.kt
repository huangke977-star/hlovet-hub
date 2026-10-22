package xyz.hlovet.portal.prototype

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.VideoSource
import org.webrtc.audio.JavaAudioDeviceModule

private object NativeWebRtcRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions(),
        )
        initialized = true
    }
}

/** 原生 WebRTC 媒体会话；Socket.IO 负责通话状态和信令，本类负责本地/远端媒体及 SDP/ICE。 */
internal class NativeWebRtcCall(
    context: Context,
    private val type: String,
    iceServers: List<NativeIceServer>,
    private val forceRelay: Boolean = false,
    private val onSignal: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peerIceServers = iceServers.mapNotNull { server ->
        runCatching {
            val builder = PeerConnection.IceServer.builder(server.urls)
            server.username?.let(builder::setUsername)
            server.credential?.let(builder::setPassword)
            builder.createIceServer()
        }.getOrNull()
    }
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile
    private var connectionReported = false

    private var eglBase: EglBase? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    private var localStream: org.webrtc.MediaStream? = null
    @Volatile
    private var closed = false

    var localVideoTrack: VideoTrack? = null
        private set
    var remoteVideoTrack: VideoTrack? = null
        private set

    val eglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    fun start() {
        if (closed || peer != null) return
        try {
            // 记录本次媒体会话的类型和 ICE 服务数量，便于区分初始化失败与网络收敛失败。
            Log.i(LOG_TAG, "start type=$type iceServerCount=${peerIceServers.size}")
            NativeWebRtcRuntime.ensureInitialized(applicationContext)
            eglBase = EglBase.create()
            audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val currentEglContext = eglBase?.eglBaseContext
                ?: throw IllegalStateException("WebRTC 图形上下文初始化失败。")
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(currentEglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(currentEglContext))
                .createPeerConnectionFactory()
            val currentFactory = factory ?: throw IllegalStateException("WebRTC 工厂初始化失败。")
            audioSource = currentFactory.createAudioSource(MediaConstraints())
            localAudioTrack = currentFactory.createAudioTrack("hlovet-audio", audioSource)
            localStream = currentFactory.createLocalMediaStream("hlovet-stream")
            localAudioTrack?.let { localStream?.addTrack(it) }
            if (type == "video") prepareCamera(currentFactory, currentEglContext)

            val configuration = PeerConnection.RTCConfiguration(peerIceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // 测试强制 relay 用于证明中继端口可达；普通通话仍按 WebRTC 默认候选策略连接。
                if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            peer = currentFactory.createPeerConnection(configuration, observer)
                ?: throw IllegalStateException("WebRTC 对等连接初始化失败。")
            Log.i(LOG_TAG, "peer connection created")
            localAudioTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
            localVideoTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
        } catch (exception: Exception) {
            reportError(exception.message ?: "无法初始化音视频设备。")
            close()
        }
    }

    /** 创建并发送主叫 SDP offer，媒体候选通过同一个 Socket 信令通道继续交换。 */
    fun createOffer() {
        val currentPeer = peer ?: return reportError("通话连接尚未准备好。")
        Log.i(LOG_TAG, "create offer")
        currentPeer.createOffer(descriptionObserver { description ->
            if (description != null) setLocalDescriptionAndSend(description)
        }, MediaConstraints())
    }

    /** 处理对端 SDP 或 ICE 候选；只记录信令类型，不记录内容和网络凭据。 */
    fun handleSignal(signal: JSONObject) {
        val currentPeer = peer ?: return reportError("通话连接尚未准备好。")
        Log.i(LOG_TAG, "handle signal type=${signal.optString("type")}")
        when (signal.optString("type")) {
            "offer" -> {
                val sdp = signal.optString("sdp").takeIf(String::isNotBlank)
                    ?: return reportError("收到的通话邀请无效。")
                currentPeer.setRemoteDescription(descriptionObserver {
                    flushCandidates()
                    currentPeer.createAnswer(descriptionObserver { answer ->
                        if (answer != null) setLocalDescriptionAndSend(answer)
                    }, MediaConstraints())
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "answer" -> {
                val sdp = signal.optString("sdp").takeIf(String::isNotBlank)
                    ?: return reportError("收到的通话应答无效。")
                currentPeer.setRemoteDescription(descriptionObserver { flushCandidates() }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice-candidate" -> {
                val candidate = signal.optJSONObject("candidate") ?: return
                val sdp = candidate.optString("candidate").takeIf(String::isNotBlank) ?: return
                val sdpMid = candidate.optString("sdpMid").takeIf(String::isNotBlank) ?: return
                val sdpMLineIndex = candidate.optInt("sdpMLineIndex", -1)
                if (sdpMLineIndex < 0) return
                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                if (currentPeer.remoteDescription == null) pendingCandidates += iceCandidate
                else currentPeer.addIceCandidate(iceCandidate)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        val capturer = cameraCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) = Unit
            override fun onCameraSwitchError(errorDescription: String?) {
                reportError(errorDescription ?: "摄像头切换失败。")
            }
        })
    }

    /** 释放通话媒体资源，先销毁包含轨道的 MediaStream，再释放各个轨道，避免重复访问底层对象。 */
    fun close() {
        if (closed) return
        closed = true
        // 先停止摄像头采集和纹理线程，避免释放轨道后仍有采集回调写入底层对象。
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturer?.dispose()
        cameraCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        // MediaStream.dispose() 会调用 removeTrack，必须在 AudioTrack/VideoTrack.dispose() 之前执行。
        val currentStream = localStream
        localStream = null
        runCatching { currentStream?.dispose() }

        // 关闭 PeerConnection 后再释放轨道和远端画面，确保信令不会继续使用媒体对象。
        peer?.close()
        peer?.dispose()
        peer = null

        // 释放本地视频轨道和音频轨道，轨道可能为空表示当前是语音或尚未完成初始化。
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        // 远端轨道不属于本地 MediaStream，但仍需要在会话结束时释放其底层资源。
        runCatching { remoteVideoTrack?.dispose() }
        remoteVideoTrack = null

        // 释放媒体源、工厂和音频模块，最后释放 EGL 上下文并清空候选队列。
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        factory?.dispose()
        factory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase?.release()
        eglBase = null
        pendingCandidates.clear()
    }

    private fun prepareCamera(currentFactory: PeerConnectionFactory, currentEglContext: EglBase.Context) {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(applicationContext)) {
            Camera2Enumerator(applicationContext)
        } else {
            Camera1Enumerator(true)
        }
        val deviceName = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("没有找到可用摄像头。")
        cameraCapturer = enumerator.createCapturer(deviceName, cameraEventsHandler)
            ?: throw IllegalStateException("摄像头初始化失败。")
        videoSource = currentFactory.createVideoSource(cameraCapturer?.isScreencast == true)
        surfaceTextureHelper = SurfaceTextureHelper.create("HlovetVideoCapture", currentEglContext)
        cameraCapturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        cameraCapturer?.startCapture(1280, 720, 24)
        localVideoTrack = currentFactory.createVideoTrack("hlovet-video", videoSource)
    }

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = reportError(error ?: "摄像头发生错误。")
        override fun onCameraDisconnected() = reportError("摄像头连接已断开。")
        override fun onCameraFreezed(error: String?) = reportError(error ?: "摄像头画面被冻结。")
        override fun onCameraOpening(cameraName: String?) = Unit
        override fun onFirstFrameAvailable() = Unit
        override fun onCameraClosed() = Unit
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        /** 兼容 ICE 层较早到达的状态回调，连接成功时与总状态回调共用一次性通知。 */
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            // 仅记录连接阶段，不写入候选地址、SDP 或 TURN 凭据，便于诊断设备网络兼容性。
            Log.i(LOG_TAG, "ICE connection state=$state")
            if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                notifyConnectedOnce()
            } else if (state == PeerConnection.IceConnectionState.FAILED) {
                reportError("ICE 媒体连接失败，请检查网络或 TURN 配置。")
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        /** 记录候选类型以证明 TURN 中继是否参与，不记录候选 IP、端口、SDP 或凭据。 */
        override fun onIceCandidate(candidate: IceCandidate) {
            // 候选行只提取 typ 后的标准类型字段，日志不包含任何网络地址。
            val candidateType = candidate.sdp.substringAfter(" typ ", "unknown").substringBefore(' ')
            Log.i(LOG_TAG, "ICE candidate type=$candidateType")
            val candidateJson = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            emitSignal(JSONObject().put("type", "ice-candidate").put("candidate", candidateJson))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) {
            stream.videoTracks.firstOrNull()?.let(::setRemoteVideo)
        }
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        /** 处理 PeerConnection 汇总状态，并与 ICE 回调去重，避免重复更新服务端通话记录。 */
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            // 状态日志只包含 WebRTC 枚举值，不记录用户内容或网络凭据。
            Log.i(LOG_TAG, "peer connection state=$state")
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                notifyConnectedOnce()
            } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                reportError("媒体连接失败，请检查网络或 TURN 配置。")
            }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) setRemoteVideo(track)
        }
    }

    private fun setRemoteVideo(track: VideoTrack) {
        remoteVideoTrack = track
        mainHandler.post { onRemoteVideo(track) }
    }

    private fun flushCandidates() {
        val currentPeer = peer ?: return
        val candidates = pendingCandidates.toList()
        pendingCandidates.clear()
        candidates.forEach(currentPeer::addIceCandidate)
    }

    /** 通过线程安全的一次性门闩合并 ICE 与 PeerConnection 的成功回调。 */
    private fun notifyConnectedOnce() {
        // 两类回调可能由不同线程几乎同时触发，只有首次成功能更新调用状态。
        val shouldNotify = synchronized(this) {
            if (closed || connectionReported) {
                false
            } else {
                connectionReported = true
                true
            }
        }
        if (shouldNotify) mainHandler.post { if (!closed) onConnected() }
    }

    private fun setLocalDescriptionAndSend(description: SessionDescription) {
        val currentPeer = peer ?: return
        Log.i(LOG_TAG, "set local description type=${description.type.canonicalForm()}")
        currentPeer.setLocalDescription(descriptionObserver {
            val local = currentPeer.localDescription ?: return@descriptionObserver
            emitSignal(
                JSONObject()
                    .put("type", local.type.canonicalForm())
                    .put("sdp", local.description),
            )
        }, description)
    }

    private fun descriptionObserver(onSuccess: (SessionDescription?) -> Unit): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onSetSuccess() = onSuccess(null)
        override fun onCreateFailure(error: String) = reportError("媒体描述创建失败：$error")
        override fun onSetFailure(error: String) = reportError("媒体描述设置失败：$error")
    }

    private fun emitSignal(signal: JSONObject) {
        Log.i(LOG_TAG, "emit signal type=${signal.optString("type")}")
        if (!closed) mainHandler.post { onSignal(signal) }
    }

    private fun reportError(message: String) {
        Log.e(LOG_TAG, message)
        if (!closed) mainHandler.post { onError(message) }
    }

    private companion object {
        /** 限定 WebRTC 生命周期诊断日志标签，日志内容不包含 SDP 或凭据。 */
        const val LOG_TAG = "HlovetWebRtc"
    }
}
