package xyz.hlovet.portal.prototype

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 使用 context 管理单个本地播放器，video 决定是否等待画面；文件已经过认证下载，不自行联网或保存令牌。 */
internal class NativeInlineMediaPlayback(context: Context, private val video: Boolean) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // 其它播放器、通话或系统提示取得音频焦点后暂停，不在焦点恢复时自动播放。
        if (change < AudioManager.AUDIOFOCUS_GAIN) pause()
    }
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(true).build() else null
    private var player: MediaPlayer? = null
    private var surface: SurfaceHolder? = null
    private var requestedPlay = false
    private var foreground = false
    private var hasAudioFocus = false
    private var transferringSurface = false
    var released = false
        private set
    var ready by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var surfaceVersion by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 从 file 异步准备真实媒体；失败可再次准备，离开页面后的对象不可重新启用。 */
    fun prepare(file: File) {
        // 每次准备都先释放旧解码器，防止重试叠加播放或保留上一份附件。
        if (released) return
        clearPlayer()
        error = null
        requestedPlay = true
        val candidate = MediaPlayer()
        player = candidate
        preparing = true
        try {
            candidate.setAudioAttributes(audioAttributes)
            if (video) candidate.setDisplay(surface)
            candidate.setDataSource(file.absolutePath)
            candidate.setOnPreparedListener {
                // 迟到回调不能恢复已经释放或替换的媒体。
                if (released || player !== candidate) return@setOnPreparedListener
                durationMs = candidate.duration.coerceAtLeast(0)
                videoWidth = candidate.videoWidth.coerceAtLeast(0)
                videoHeight = candidate.videoHeight.coerceAtLeast(0)
                preparing = false
                ready = true
                if (video) candidate.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                if (requestedPlay) start()
            }
            candidate.setOnCompletionListener {
                // 播完停留在末尾，用户再次点击播放会从头开始。
                if (player !== candidate) return@setOnCompletionListener
                positionMs = durationMs
                playing = false
                requestedPlay = false
                abandonFocus()
            }
            candidate.setOnVideoSizeChangedListener { _, width, height ->
                // 编码器迟到确认的真实尺寸也参与画幅，竖屏视频不能继续按横屏拉伸。
                if (player === candidate) {
                    videoWidth = width.coerceAtLeast(0)
                    videoHeight = height.coerceAtLeast(0)
                }
            }
            candidate.setOnErrorListener { _, _, _ ->
                // 损坏或设备不支持的格式留在原消息中提示，避免跳到未知系统查看器。
                if (player === candidate) fail()
                true
            }
            candidate.prepareAsync()
        } catch (_: Exception) {
            // 文件被淘汰或解码器初始化失败时释放资源，保留重试入口。
            fail()
        }
    }

    /** 绑定 version 对应的视频 holder，null 表示当前画面离开；过期窗口不能修改新输出。 */
    fun attachSurface(holder: SurfaceHolder?, version: Int = surfaceVersion) {
        // 全屏切换时暂停解码输出，普通移出屏幕仍撤销播放意图。
        if (released || version != surfaceVersion) return
        val changed = surface !== holder
        surface = holder
        if (holder == null && !transferringSurface) pause()
        runCatching { player?.setDisplay(holder) }
        // 新窗口需要重新输出暂停帧；同一 holder 的尺寸回调不能反复拖动进度。
        if (holder != null) {
            transferringSurface = false
            if (changed && ready) runCatching {
                // 播完时输出最后一帧，不能把新的窗口定位到无画面的流末尾。
                seekPlayer(positionMs.coerceAtMost((durationMs - 1).coerceAtLeast(0)))
            }.onFailure { fail() }
        }
        if (holder != null && ready && requestedPlay) start()
    }

    /** 只分离实际持有画面的 holder，防止旧窗口迟到的销毁回调暂停新窗口。 */
    fun detachSurface(holder: SurfaceHolder) {
        // 全屏和行内视图可能在不同帧销毁，身份不匹配的回调没有权限改当前输出。
        if (surface === holder) attachSurface(null)
    }

    /** 用户切换行内/全屏时短暂停止输出，保留原来的播放意图与位置。 */
    fun beginSurfaceTransfer() {
        // 暂停中的媒体不会因全屏自动开始；播放中的媒体在新 Surface 可用后继续。
        if (!video || released) return
        val continuePlaying = playing || requestedPlay
        pause()
        requestedPlay = continuePlaying
        transferringSurface = true
        surfaceVersion++
    }

    /** 根据 active 更新页面可见状态；失去前台时立即暂停，恢复时只允许手动继续。 */
    fun setForeground(active: Boolean) {
        // 包括下载和异步准备阶段，离开页面后也不能迟到自动播放。
        foreground = active && !released
        if (!foreground) {
            transferringSurface = false
            pause()
        }
    }

    /** 切换已准备媒体的播放状态；未准备或已释放时不触碰原生播放器。 */
    fun toggle() {
        // 完成状态从头播放，正常暂停则保留当前位置。
        if (!ready || released) return
        if (playing) pause() else {
            if (durationMs > 0 && positionMs >= durationMs) seek(0)
            requestedPlay = true
            start()
        }
    }

    /** 将播放位置调整到 milliseconds，越界值限制在当前媒体范围内。 */
    fun seek(milliseconds: Int) {
        // 拖动仅操作已解码的本地媒体，不触发额外网络请求。
        if (!ready || released) return
        val target = milliseconds.coerceIn(0, durationMs)
        runCatching { seekPlayer(target); positionMs = target }.onFailure { fail() }
    }

    /** 将解码器定位到 milliseconds，支持的系统使用精确帧而非只退到前一个关键帧。 */
    private fun seekPlayer(milliseconds: Int) {
        // 同时用于用户拖动和全屏窗口重绘，旧 Android 保留其系统支持的跳转方式。
        if (Build.VERSION.SDK_INT >= 26) player?.seekTo(milliseconds.toLong(), MediaPlayer.SEEK_CLOSEST)
        else player?.seekTo(milliseconds)
    }

    /** 读取原生播放进度，暂停或释放状态不调用非法的 MediaPlayer API。 */
    fun poll() {
        // 播放期间由界面定时读取，不在全部消息上创建常驻计时器。
        if (!ready || !playing || released) return
        runCatching { positionMs = (player?.currentPosition ?: 0).coerceIn(0, durationMs) }.onFailure { fail() }
    }

    /** 暂停播放并归还音频焦点，保留已准备的文件和当前位置。 */
    fun pause() {
        // 同时撤销尚未执行的播放意图，避免后台异步准备完后开始播放。
        requestedPlay = false
        if (ready && playing) runCatching { player?.pause(); positionMs = player?.currentPosition ?: positionMs }
        playing = false
        abandonFocus()
    }

    /** 永久释放本播放器，离开消息、切换账号或组件销毁时调用；重复调用无副作用。 */
    fun release() {
        // 标记先于释放，确保迟到监听不能写回可播放状态。
        if (released) return
        released = true
        clearPlayer()
        surface = null
    }

    /** 在前台且视频 Surface 可用时开始播放，音频焦点被拒绝则给出可重试反馈。 */
    private fun start() {
        // 视频不能只播放声音；尚未挂载时保留本次手动播放意图。
        if (!foreground || !ready || released || playing || (video && surface == null)) return
        val granted = if (Build.VERSION.SDK_INT >= 26) audioManager.requestAudioFocus(requireNotNull(focusRequest)) else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = nativePhrase("音频正在被其它功能使用，请稍后重试。", "Audio is in use. Try again shortly.")
            requestedPlay = false
            return
        }
        // 焦点成功后才启动，失败仍归还焦点并销毁非法解码状态。
        hasAudioFocus = true
        error = null
        runCatching { player?.start(); playing = true }.onFailure { fail() }
    }

    /** 释放当前原生解码器和焦点，重置进度，不删除共享缓存文件。 */
    private fun clearPlayer() {
        // 摘除监听后再释放，准备中的播放器也使用相同的销毁路径。
        player?.let { old ->
            old.setOnPreparedListener(null)
            old.setOnCompletionListener(null)
            old.setOnErrorListener(null)
            old.setOnVideoSizeChangedListener(null)
            runCatching { old.release() }
        }
        player = null
        requestedPlay = false
        ready = false
        preparing = false
        playing = false
        durationMs = 0
        positionMs = 0
        videoWidth = 0
        videoHeight = 0
        abandonFocus()
    }

    /** 回收已取得的音频焦点，兼容最低支持的 Android 版本。 */
    private fun abandonFocus() {
        // 未取得焦点时不影响其它消息或系统播放器。
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= 26) audioManager.abandonAudioFocusRequest(requireNotNull(focusRequest)) else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        hasAudioFocus = false
    }

    /** 将解码异常转成用户可理解的提示，并允许下一次点击重新准备缓存文件。 */
    private fun fail() {
        // 不把原生错误码直接展示给用户，也不留下占用音频焦点的坏播放器。
        clearPlayer()
        error = nativePhrase("此媒体无法播放，可重试或下载后打开。", "This media cannot be played. Retry or download it.")
    }
}

/** 显示 attachment 的统一行内播放器；accessToken/cacheOwner 隔离下载，onDownload 保留原系统文件操作。 */
@Composable
internal fun NativeInlineMediaAttachment(
    attachment: NativeChatAttachmentPreview,
    accessToken: String?,
    cacheOwner: String,
    onDownload: () -> Unit,
) {
    // 播放状态随附件、地址和账号整体重建，不复用旧身份的下载或解码器。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playback = remember(attachment.id, attachment.downloadUrl, accessToken, cacheOwner) {
        NativeInlineMediaPlayback(context.applicationContext, attachment.kind == "video")
    }
    var request by remember(playback) { mutableIntStateOf(0) }
    var loading by remember(playback) { mutableStateOf(false) }
    var downloadError by remember(playback) { mutableStateOf<String?>(null) }
    var fullscreen by remember(playback) { mutableStateOf(false) }

    /** 用户点击播放时复用已准备播放器，未下载时才递增手动请求。 */
    fun playOrLoad() {
        // 全屏与行内共享同一份文件和请求，切换窗口不产生第二次下载。
        if (playback.ready) playback.toggle() else if (!loading && !playback.preparing) request++
    }

    /** 按 active 切换视频窗口，先保留播放意图再改变可见 Surface。 */
    fun changeFullscreen(active: Boolean) {
        // 账号变更会重建整份状态，音频不显示或响应全屏入口。
        if (attachment.kind != "video" || fullscreen == active) return
        playback.beginSurfaceTransfer()
        fullscreen = active
    }

    /** 退出全屏并暂停后打开原系统文件面板，避免文件弹窗被全屏窗口挡住。 */
    fun downloadMedia() {
        // 下载是独立操作，不让后台视频继续播放，也不创建播放器专用下载路径。
        playback.pause()
        changeFullscreen(false)
        onDownload()
    }

    // 不自动预取所有消息媒体，只在用户点击播放后下载一份有界认证文件。
    LaunchedEffect(playback, request) {
        if (request == 0) return@LaunchedEffect
        loading = true
        downloadError = null
        try {
            val file = withContext(Dispatchers.IO) {
                NativeOfflineCache.loadMediaFile(context, attachment.downloadUrl, accessToken, cacheOwner, attachment.name, attachment.mimeType)
            } ?: throw IllegalStateException(nativePhrase("媒体读取失败，请检查网络后重试。", "Unable to load media. Check your connection and retry."))
            playback.prepare(file)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 地址或账号变化后停止消费旧请求，不能将旧媒体交给新播放器。
            throw cancelled
        } catch (exception: Exception) {
            // 网络失败仍显示原消息及下载入口，下次手动播放可以重试。
            downloadError = exception.message ?: nativePhrase("媒体读取失败。", "Unable to load media.")
        } finally {
            loading = false
        }
    }
    DisposableEffect(playback, lifecycleOwner) {
        // 退到后台、打开其它页面或账号切换都必须释放或暂停，不允许无可见入口的播放。
        playback.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            playback.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playback.release()
        }
    }
    LaunchedEffect(playback, playback.playing) {
        // 只为正在播放的媒体更新进度，停止播放后协程自然取消。
        while (playback.playing) { playback.poll(); delay(200) }
    }

    // 行内窗口高度有界，真实画幅在容器内等比显示；全屏时保留原卡片几何。
    Surface(color = HlovetUi.surfaceLow, shape = HlovetUi.rowShape, modifier = Modifier.fillMaxWidth().testTag("native-inline-media-${attachment.id}")) {
        Column {
            if (attachment.kind == "video") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val height = (maxWidth / nativeInlineVideoRatio(playback.videoWidth, playback.videoHeight)).coerceIn(120.dp, 320.dp)
                    val frameModifier = Modifier.fillMaxWidth().height(height).clip(HlovetUi.rowShape)
                    if (fullscreen) Box(frameModifier.background(Color.Black)) else NativeInlineVideoFrame(playback, frameModifier)
                }
            }
            NativeInlineMediaControls(attachment, playback, loading, downloadError, false, ::playOrLoad, ::downloadMedia, { changeFullscreen(true) },
                modifier = if (fullscreen) Modifier.clearAndSetSemantics {} else Modifier)
        }
    }
    // 全屏只扩展媒体工作区，不再包裹玻璃卡片；系统返回与退出图标使用相同切换路径。
    if (fullscreen) Dialog(onDismissRequest = { changeFullscreen(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).testTag("native-video-fullscreen-${attachment.id}")) {
            NativeInlineVideoFrame(playback, Modifier.weight(1f).fillMaxWidth(), overlay = true)
            NativeInlineMediaControls(attachment, playback, loading, downloadError, true, ::playOrLoad, ::downloadMedia, { changeFullscreen(false) })
        }
    }
}

/** 在 modifier 约束内等比显示 playback；overlay 用于全屏窗口，防止画面被其黑色装饰层遮挡。 */
@Composable
private fun NativeInlineVideoFrame(playback: NativeInlineMediaPlayback, modifier: Modifier, overlay: Boolean = false) {
    // 横竖屏均按真实画幅留边，避免把竖屏视频拉宽或裁掉内容。
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = nativeInlineVideoRatio(playback.videoWidth, playback.videoHeight)
        val frame = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
        val version = playback.surfaceVersion
        AndroidView(factory = { viewContext ->
            SurfaceView(viewContext).apply {
                // 仅全屏视频层放到自身窗口内容上方，画面范围仍避开底部按钮且不改变行内层级。
                setZOrderOnTop(overlay)
                holder.addCallback(object : SurfaceHolder.Callback {
                    /** 新窗口画面可用后绑定现有播放器，不重复准备文件。 */
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // 解码与播放进度由共享播放器管理，窗口仅提供真实 Surface。
                        playback.attachSurface(holder, version)
                    }
                    /** 同一窗口尺寸改变时维持进度，仅更新画面输出。 */
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // 横竖屏布局变化不重新下载或重置播放位置。
                        playback.attachSurface(holder, version)
                    }
                    /** 当前画面消失时暂停，旧窗口迟到销毁不能影响新全屏窗口。 */
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // 仅匹配当前输出身份时才分离，避免全屏切换的回调竞态。
                        playback.detachSurface(holder)
                    }
                })
            }
        }, onRelease = { view -> playback.detachSurface(view.holder) }, modifier = frame.testTag("native-video-surface"))
    }
}

/** 使用同一组按钮和进度展示 attachment/playback；fullscreen 只调整对比度和退出图标，其余回调复用原操作。 */
@Composable
private fun NativeInlineMediaControls(
    attachment: NativeChatAttachmentPreview,
    playback: NativeInlineMediaPlayback,
    loading: Boolean,
    downloadError: String?,
    fullscreen: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 玻璃背景使用账号颜色，全屏黑底使用高对比文字，所有图标保持无边框。
    val foreground = if (fullscreen) Color.White else HlovetUi.foreground
    val muted = if (fullscreen) Color.White.copy(alpha = .7f) else HlovetUi.muted
    val accent = if (fullscreen) Color.White else HlovetUi.accent
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay, enabled = !loading && !playback.preparing) {
                Icon(if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = nativePhrase(if (playback.playing) "暂停媒体" else "播放媒体", if (playback.playing) "Pause media" else "Play media"),
                    tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(attachment.name, color = foreground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (loading || playback.preparing) nativePhrase("读取中…", "Loading...") else "${nativeInlineMediaTime(playback.positionMs)} / ${nativeInlineMediaTime(playback.durationMs)}",
                    color = muted, fontSize = 10.sp)
            }
            if (attachment.kind == "video") IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = nativePhrase(if (fullscreen) "退出全屏" else "全屏播放", if (fullscreen) "Exit fullscreen" else "Fullscreen video"),
                    tint = accent, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = nativePhrase("下载附件", "Download attachment"), tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        // 拖动仍直接操作当前解码器，全屏和行内均不会发起第二次网络请求。
        Slider(value = playback.positionMs.toFloat(), onValueChange = { playback.seek(it.toInt()) },
            enabled = playback.ready && playback.durationMs > 0, valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = accent.copy(alpha = .2f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(28.dp))
        (downloadError ?: playback.error)?.let { message ->
            // 同一条错误在当前可见窗口展示，不被背景卡片遮挡。
            Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }
}

/** 根据 width/height 返回真实视频比例；元数据尚未就绪或非法时使用横屏占位比例。 */
internal fun nativeInlineVideoRatio(width: Int, height: Int): Float {
    // 不用固定横屏比例覆盖已确认的竖屏或方形媒体。
    return if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

/** 将 milliseconds 显示为分钟与两位秒数；不依赖当前语言的数字格式。 */
internal fun nativeInlineMediaTime(milliseconds: Int): String {
    // 负值视为尚无进度，超过一小时继续显示累计分钟。
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
