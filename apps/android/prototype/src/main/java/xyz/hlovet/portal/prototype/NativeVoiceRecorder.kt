package xyz.hlovet.portal.prototype

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Records a short AAC/M4A clip into the app cache before it is uploaded as a chat attachment. */
internal class NativeVoiceRecorder(context: Context) {
    private val cacheDir = context.cacheDir
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        check(recorder == null) { "录音已经在进行中。" }
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val instance = MediaRecorder()
        try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioEncodingBitRate(96_000)
            instance.setAudioSamplingRate(44_100)
            instance.setOutputFile(file.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            outputFile = file
        } catch (error: Exception) {
            runCatching { instance.reset() }
            instance.release()
            file.delete()
            throw error
        }
    }

    fun stop(): File? {
        val instance = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val stopped = runCatching { instance.stop() }.isSuccess
        instance.release()
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancel() {
        val instance = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { instance.reset() }
        instance.release()
        file?.delete()
    }
}
