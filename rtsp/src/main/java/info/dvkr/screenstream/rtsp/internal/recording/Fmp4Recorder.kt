package info.dvkr.screenstream.rtsp.internal

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

internal class Fmp4Recorder(
    context: Context,
    private val encodedWidth: Int,
    private val encodedHeight: Int,
    expectAudioTrack: Boolean
) {

    private sealed class OutputTarget {
        data class SharedPath(val file: File) : OutputTarget()
        data class MediaStoreItem(
            val uri: Uri,
            val fileDescriptor: ParcelFileDescriptor,
            val contentResolver: ContentResolver,
        ) : OutputTarget()
    }

    private val lock = Any()
    private val targetFileName: String = buildFileName()
    private val outputTarget: OutputTarget = createOutputTarget(context, targetFileName)
    private var muxer: MediaMuxer? = when (val target = outputTarget) {
        is OutputTarget.SharedPath -> MediaMuxer(target.file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        is OutputTarget.MediaStoreItem -> {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "MediaStore file-descriptor muxing requires API 26+" }
            MediaMuxer(target.fileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var started: Boolean = false

    private var pendingVideoFormat: MediaFormat? = null
    private var pendingAudioFormat: MediaFormat? = null
    private var requireAudioTrack: Boolean = expectAudioTrack

    internal val outputPath: String
        get() = "Movies/StraGla/$targetFileName"

    internal fun setVideoParams(video: VideoParams) {
        synchronized(lock) {
            pendingVideoFormat = buildVideoFormat(video)
            startMuxerIfReadyLocked()
        }
    }

    internal fun setAudioParams(audio: AudioParams?) {
        synchronized(lock) {
            if (!requireAudioTrack) return
            if (audio == null) {
                requireAudioTrack = false
                startMuxerIfReadyLocked()
                return
            }
            pendingAudioFormat = buildAudioFormat(audio)
            startMuxerIfReadyLocked()
        }
    }

    internal fun onFrame(frame: MediaFrame) {
        synchronized(lock) {
            try {
                val activeMuxer = muxer
                if (!started || activeMuxer == null) return

                val trackIndex = when (frame) {
                    is MediaFrame.VideoFrame -> videoTrackIndex
                    is MediaFrame.AudioFrame -> audioTrackIndex
                }
                if (trackIndex < 0) return

                val sampleData = frame.data.duplicate().apply {
                    position(frame.info.offset)
                    limit(frame.info.offset + frame.info.size)
                }.slice()

                val sampleInfo = MediaCodec.BufferInfo().apply {
                    set(
                        0,
                        frame.info.size,
                        frame.info.timestamp,
                        if (frame.info.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                }
                activeMuxer.writeSampleData(trackIndex, sampleData, sampleInfo)
            } catch (error: Throwable) {
                XLog.w(getLog("onFrame", "Failed to write sample"), error)
            } finally {
                frame.release()
            }
        }
    }

    internal fun stop() {
        synchronized(lock) {
            val activeMuxer = muxer ?: return
            muxer = null
            val closeResult = runCatching {
                if (started) {
                    activeMuxer.stop()
                }
                activeMuxer.release()
            }
            closeResult.onFailure {
                XLog.w(getLog("stop", "Failed to close recorder"), it)
            }

            finalizeTarget(success = closeResult.isSuccess)

            started = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            pendingVideoFormat = null
            pendingAudioFormat = null
            requireAudioTrack = false
        }
    }

    private fun startMuxerIfReadyLocked() {
        if (started) return

        val activeMuxer = muxer ?: return
        val videoFormat = pendingVideoFormat ?: return
        val audioFormat = pendingAudioFormat
        if (requireAudioTrack && audioFormat == null) return

        videoTrackIndex = activeMuxer.addTrack(videoFormat)
        if (audioFormat != null) {
            audioTrackIndex = activeMuxer.addTrack(audioFormat)
        }
        activeMuxer.start()
        started = true
    }

    private fun buildVideoFormat(video: VideoParams): MediaFormat {
        val format = MediaFormat.createVideoFormat(video.codec.mimeType, encodedWidth, encodedHeight)
        when (video.codec) {
            Codec.Video.H264 -> {
                format.setByteBuffer("csd-0", withStartCode(video.sps))
                video.pps?.let { format.setByteBuffer("csd-1", withStartCode(it)) }
            }

            Codec.Video.H265 -> {
                val vps = video.vps
                val pps = video.pps
                if (vps != null && pps != null) {
                    val csd = ByteArray(vps.size + video.sps.size + pps.size + 12)
                    var cursor = 0
                    cursor = appendNalWithStartCode(csd, cursor, vps)
                    cursor = appendNalWithStartCode(csd, cursor, video.sps)
                    appendNalWithStartCode(csd, cursor, pps)
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                } else {
                    format.setByteBuffer("csd-0", withStartCode(video.sps))
                }
            }

            Codec.Video.AV1 -> {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(video.sps))
            }
        }
        return format
    }

    private fun buildAudioFormat(audio: AudioParams): MediaFormat =
        MediaFormat.createAudioFormat(
            Codec.Audio.AAC.mimeType,
            audio.sampleRate,
            if (audio.isStereo) 2 else 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            buildAacAudioSpecificConfig(audio.sampleRate, if (audio.isStereo) 2 else 1)?.let {
                setByteBuffer("csd-0", ByteBuffer.wrap(it))
            }
        }

    private fun createOutputFile(context: Context): File {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val recordingsDir = File(baseDir, "StraGla")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        return File(recordingsDir, targetFileName)
    }

    private fun buildFileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        return "${timestamp}.fmp4"
    }

    private fun createOutputTarget(context: Context, fileName: String): OutputTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StraGla")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
                "Failed to create MediaStore item"
            }
            val pfd = requireNotNull(resolver.openFileDescriptor(uri, "rw")) {
                "Failed to open MediaStore file descriptor"
            }
            return OutputTarget.MediaStoreItem(uri = uri, fileDescriptor = pfd, contentResolver = resolver)
        }

        return OutputTarget.SharedPath(createOutputFile(context))
    }

    private fun finalizeTarget(success: Boolean) {
        when (val target = outputTarget) {
            is OutputTarget.SharedPath -> Unit
            is OutputTarget.MediaStoreItem -> {
                runCatching {
                    target.fileDescriptor.close()
                }.onFailure {
                    XLog.w(getLog("stop", "Failed to close MediaStore file descriptor"), it)
                }

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (success) {
                            target.contentResolver.update(
                                target.uri,
                                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                                null,
                                null
                            )
                        } else {
                            target.contentResolver.delete(target.uri, null, null)
                        }
                    }
                }.onFailure {
                    XLog.w(getLog("stop", "Failed to finalize MediaStore recording"), it)
                }
            }
        }
    }

    private fun withStartCode(nal: ByteArray): ByteBuffer {
        val out = ByteArray(nal.size + 4)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return ByteBuffer.wrap(out)
    }

    private fun appendNalWithStartCode(destination: ByteArray, offset: Int, nal: ByteArray): Int {
        val end = min(destination.size, offset + 4 + nal.size)
        if (end - offset < 4) return offset
        destination[offset] = 0
        destination[offset + 1] = 0
        destination[offset + 2] = 0
        destination[offset + 3] = 1
        val bytesToCopy = end - (offset + 4)
        if (bytesToCopy > 0) {
            System.arraycopy(nal, 0, destination, offset + 4, bytesToCopy)
        }
        return offset + 4 + bytesToCopy
    }

    private fun buildAacAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray? {
        val samplingFrequencies = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
        val sampleRateIndex = samplingFrequencies.indexOf(sampleRate)
        if (sampleRateIndex < 0) return null

        val channelConfig = channels.coerceIn(1, 2)
        val audioObjectType = 2 // AAC LC
        val config = ByteArray(2)
        config[0] = ((audioObjectType shl 3) or (sampleRateIndex shr 1)).toByte()
        config[1] = (((sampleRateIndex and 0x01) shl 7) or (channelConfig shl 3)).toByte()
        return config
    }
}

