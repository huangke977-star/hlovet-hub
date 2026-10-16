package xyz.hlovet.portal.prototype

import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * One native WebRTC media session. Socket.IO remains responsible for call
 * state and signaling; this class only owns local/remote media and SDP/ICE.
 */
internal class NativeWebRtcCall(
    context: Context,
    private val type: String,
    iceServers: List<NativeIceServer>,
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
            }
            peer = currentFactory.createPeerConnection(configuration, observer)
                ?: throw IllegalStateException("WebRTC 对等连接初始化失败。")
            localAudioTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
            localVideoTrack?.let { peer?.addTrack(it, listOf("hlovet-stream")) }
        } catch (exception: Exception) {
            reportError(exception.message ?: "无法初始化音视频设备。")
            close()
        }
    }

    fun createOffer() {
        val currentPeer = peer ?: return reportError("通话连接尚未准备好。")
        currentPeer.createOffer(descriptionObserver { description ->
            if (description != null) setLocalDescriptionAndSend(description)
        }, MediaConstraints())
    }

    fun handleSignal(signal: JSONObject) {
        val currentPeer = peer ?: return reportError("通话连接尚未准备好。")
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

    fun close() {
        if (closed) return
        closed = true
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturer?.dispose()
        cameraCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        peer?.close()
        peer?.dispose()
        peer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        remoteVideoTrack?.dispose()
        remoteVideoTrack = null
        localStream?.dispose()
        localStream = null
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
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
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
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                mainHandler.post(onConnected)
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

    private fun setLocalDescriptionAndSend(description: SessionDescription) {
        val currentPeer = peer ?: return
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
        if (!closed) mainHandler.post { onSignal(signal) }
    }

    private fun reportError(message: String) {
        if (!closed) mainHandler.post { onError(message) }
    }
}
