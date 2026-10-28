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
    private val onNotice: (String) -> Unit,
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
    var canSwitchCamera: Boolean = false
        private set
    private var localAudioTrack: AudioTrack? = null
    @Volatile
    private var closed = false

    var localVideoTrack: VideoTrack? = null
        private set
    var remoteVideoTrack: VideoTrack? = null
        private set

    val eglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    /** 初始化 WebRTC 工厂、音频轨道与对等连接；轨道通过 Unified Plan 直接加入 PeerConnection。 */
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
                ?: throw IllegalStateException(nativePhrase("WebRTC 图形上下文初始化失败。", "WebRTC graphics context initialization failed."))
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(currentEglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(currentEglContext))
                .createPeerConnectionFactory()
            val currentFactory = factory ?: throw IllegalStateException(nativePhrase("WebRTC 工厂初始化失败。", "WebRTC factory initialization failed."))
            audioSource = currentFactory.createAudioSource(MediaConstraints())
            localAudioTrack = currentFactory.createAudioTrack("hlovet-audio", audioSource)
            if (type == "video") prepareCamera(currentFactory, currentEglContext)

            val configuration = PeerConnection.RTCConfiguration(peerIceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // 测试强制 relay 用于证明中继端口可达；普通通话仍按 WebRTC 默认候选策略连接。
                if (forceRelay) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            peer = currentFactory.createPeerConnection(configuration, observer)
                ?: throw IllegalStateException(nativePhrase("WebRTC 对等连接初始化失败。", "WebRTC peer connection initialization failed."))
            Log.i(LOG_TAG, "peer connection created")
            localAudioTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
            localVideoTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
        } catch (exception: Exception) {
            reportError(nativePhrase("无法初始化音视频设备。", "Unable to initialize audio and video devices."))
            close()
        }
    }

    /** 创建并发送主叫 SDP offer，媒体候选通过同一个 Socket 信令通道继续交换。 */
    fun createOffer() {
        val currentPeer = peer ?: return reportError(nativePhrase("通话连接尚未准备好。", "The call connection is not ready."))
        Log.i(LOG_TAG, "create offer")
        currentPeer.createOffer(descriptionObserver { description ->
            if (description != null) setLocalDescriptionAndSend(description)
        }, MediaConstraints())
    }

    /** 处理对端 SDP 或 ICE 候选；只记录信令类型，不记录内容和网络凭据。 */
    fun handleSignal(signal: JSONObject) {
        val currentPeer = peer ?: return reportError(nativePhrase("通话连接尚未准备好。", "The call connection is not ready."))
        Log.i(LOG_TAG, "handle signal type=${signal.optString("type")}")
        when (signal.optString("type")) {
            "offer" -> {
                val sdp = signal.optString("sdp").takeIf(String::isNotBlank)
                    ?: return reportError(nativePhrase("收到的通话邀请无效。", "The received call offer is invalid."))
                currentPeer.setRemoteDescription(descriptionObserver {
                    flushCandidates()
                    currentPeer.createAnswer(descriptionObserver { answer ->
                        if (answer != null) setLocalDescriptionAndSend(answer)
                    }, MediaConstraints())
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "answer" -> {
                val sdp = signal.optString("sdp").takeIf(String::isNotBlank)
                    ?: return reportError(nativePhrase("收到的通话应答无效。", "The received call answer is invalid."))
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

    /** 仅在存在备用摄像头时切换；切换错误交给 onNotice 提示，不终止仍可用的音视频会话。 */
    fun switchCamera() {
        // 单摄设备没有备用采集源，不能调用 WebRTC 切换接口制造致命错误。
        if (closed || !canSwitchCamera) return
        val capturer = cameraCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            /** 记录实际采集器切换完成及朝向，不记录设备标识或用户媒体内容。 */
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                // 真正完成切换后才输出成功日志，供双端真实媒体验收核对。
                Log.i(LOG_TAG, "camera switch complete front=$isFrontCamera")
            }
            /** 切换失败不代表当前通话失效；保留连接并通过主线程提示用户。 */
            override fun onCameraSwitchError(errorDescription: String?) {
                // 只显示稳定的本地化提示，避免底层英文异常泄露到界面或误触发挂断。
                Log.w(LOG_TAG, "camera switch failed")
                mainHandler.post {
                    if (!closed) onNotice(nativePhrase("摄像头切换失败，请重试。", "Unable to switch cameras. Try again."))
                }
            }
        })
    }

    /** 释放通话媒体资源；Unified Plan 轨道只由 PeerConnection 与本类持有，避免重复释放。 */
    fun close() {
        if (closed) return
        closed = true
        // 先停止摄像头采集和纹理线程，避免释放轨道后仍有采集回调写入底层对象。
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturer?.dispose()
        cameraCapturer = null
        canSwitchCamera = false
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        // Unified Plan 的 addTrack(streamIds) 已建立媒体归属，不额外构造 MediaStream 管理同一音轨。
        // 先关闭 PeerConnection，再由本类释放轨道，避免 stream.dispose() 再次 removeTrack 已失效轨道。
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

    /** 使用 currentFactory/currentEglContext 创建首选前置采集源，并按真实设备数量决定能否切换。 */
    private fun prepareCamera(currentFactory: PeerConnectionFactory, currentEglContext: EglBase.Context) {
        // 以系统实际可见设备为准，不假设手机、模拟器或外接摄像头一定具备前后双摄。
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(applicationContext)) {
            Camera2Enumerator(applicationContext)
        } else {
            Camera1Enumerator(true)
        }
        val devices = enumerator.deviceNames.distinct()
        canSwitchCamera = devices.size > 1
        val deviceName = devices.firstOrNull(enumerator::isFrontFacing)
            ?: devices.firstOrNull()
            ?: throw IllegalStateException(nativePhrase("没有找到可用摄像头。", "No usable camera was found."))
        // 只建立本次使用的采集器，备用摄像头不提前打开，避免占用系统相机资源。
        cameraCapturer = enumerator.createCapturer(deviceName, cameraEventsHandler)
            ?: throw IllegalStateException(nativePhrase("摄像头初始化失败。", "Camera initialization failed."))
        videoSource = currentFactory.createVideoSource(cameraCapturer?.isScreencast == true)
        surfaceTextureHelper = SurfaceTextureHelper.create("HlovetVideoCapture", currentEglContext)
        cameraCapturer?.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        cameraCapturer?.startCapture(1280, 720, 24)
        localVideoTrack = currentFactory.createVideoTrack("hlovet-video", videoSource)
    }

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = reportError(nativePhrase("摄像头发生错误。", "The camera reported an error."))
        override fun onCameraDisconnected() = reportError(nativePhrase("摄像头连接已断开。", "The camera connection was lost."))
        override fun onCameraFreezed(error: String?) = reportError(nativePhrase("摄像头画面被冻结。", "The camera preview has frozen."))
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
                reportError(nativePhrase("ICE 媒体连接失败，请检查网络或 TURN 配置。", "ICE media connection failed. Check the network or TURN configuration."))
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
                reportError(nativePhrase("媒体连接失败，请检查网络或 TURN 配置。", "The media connection failed. Check the network or TURN configuration."))
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
        override fun onCreateFailure(error: String) = reportError(nativePhrase("媒体描述创建失败。", "Failed to create the media description."))
        override fun onSetFailure(error: String) = reportError(nativePhrase("媒体描述设置失败。", "Failed to set the media description."))
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
