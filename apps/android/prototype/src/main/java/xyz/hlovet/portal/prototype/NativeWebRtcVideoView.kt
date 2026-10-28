package xyz.hlovet.portal.prototype

import android.view.ViewOutlineProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** 显示 track 视频轨道；eglContext 决定 Surface 生命周期，mirror 控制镜像，overlay 将本地预览置于远端 Surface 之上。 */
@Composable
internal fun NativeWebRtcVideoView(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    overlay: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 共享 EGL 或 Surface 层级改变时重建 View；层级必须在 View 附着窗口前确定。
    key(eglContext, overlay) {
        var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        AndroidView(
            // SurfaceView 会在宿主画布挖出透明区域；显式裁剪，避免旧系统清空整张圆角卡片。
            modifier = modifier.clipToBounds(),
            factory = { viewContext ->
                SurfaceViewRenderer(viewContext).also {
                    it.outlineProvider = ViewOutlineProvider.BOUNDS
                    it.clipToOutline = true
                    it.setZOrderMediaOverlay(overlay)
                    it.init(eglContext, null)
                    it.setEnableHardwareScaler(true)
                    it.setMirror(mirror)
                    it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    renderer = it
                }
            },
            update = { it.setMirror(mirror) },
            // 原生 View 真正移除时才释放；factory 写入 renderer 引发的重组不能销毁画面。
            onRelease = { it.release() },
        )
        DisposableEffect(track, renderer) {
            // 换轨只移除旧 sink 并添加新 sink，渲染器继续服务当前仍挂载的 Surface。
            val currentRenderer = renderer
            if (track != null && currentRenderer != null) track.addSink(currentRenderer)
            onDispose {
                if (track != null && currentRenderer != null) track.removeSink(currentRenderer)
            }
        }
    }
}
