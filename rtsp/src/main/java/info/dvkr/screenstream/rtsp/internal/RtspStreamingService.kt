package info.dvkr.screenstream.rtsp.internal

import android.Manifest
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Surface
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.util.toClosedRange
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.analytics.EntryPoint
import info.dvkr.screenstream.common.analytics.StartFailGroup
import info.dvkr.screenstream.common.analytics.StreamMode
import info.dvkr.screenstream.common.analytics.StreamingAnalytics
import info.dvkr.screenstream.common.analytics.StreamingSessionAnalyticsTracker
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.module.ProjectionCoordinator
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.RtspModuleService
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.adjustResizeFactor
import info.dvkr.screenstream.rtsp.internal.audio.AudioEncoder
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.video.VideoEncoder
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspClientStatus
import info.dvkr.screenstream.rtsp.ui.RtspError
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URISyntaxException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class RtspStreamingService(
    private val service: RtspModuleService,
    private val mutableRtspStateFlow: MutableStateFlow<RtspState>,
    private val rtspSettings: RtspSettings,
    @Suppress("UNUSED_PARAMETER") private val networkHelper: Any? = null,
    private val streamingAnalytics: StreamingAnalytics
) : HandlerThread("RTSP-HT", android.os.Process.THREAD_PRIORITY_DISPLAY), Handler.Callback {


    private val appVersion = service.getVersionName()
    private val projectionManager = service.application.getSystemService(MediaProjectionManager::class.java)
    private val handler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(looper, this) }
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val coroutineDispatcher: CoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) { handler.asCoroutineDispatcher("RTSP-HT_Dispatcher") }
    private val supervisorJob = SupervisorJob()
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { CoroutineScope(supervisorJob + coroutineDispatcher) }
    private val projectionCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        ProjectionCoordinator(
            tag = "RTSP",
            projectionManager = projectionManager,
            callbackHandler = handler,
            startForeground = { fgsType -> service.startForeground(fgsType) },
            onProjectionStopped = { generation ->
                XLog.i(getLog("ProjectionCoordinator.onStop", "g=$generation, mode=${rtspSettings.data.value.mode}, active=${projectionState.active != null}"))
                sendEvent(RtspEvent.Intentable.StopStream("ProjectionCoordinator.onStop[generation=$generation]"))
            }
        )
    }

    @MainThread
    internal fun tryStartProjectionForeground(): Throwable? {
        val settings = rtspSettings.data.value
        val audioPermissionGranted =
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantsAudio = settings.enableMic || settings.enableDeviceAudio
        if (!audioPermissionGranted && wantsAudio) {
            coroutineScope.launch {
                rtspSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) }
            }
        }
        val wantsMicrophoneForSession = audioPermissionGranted && settings.enableMic
        val wantsDeviceAudioForSession = audioPermissionGranted && settings.enableDeviceAudio
        val wantsAudioForegroundService = wantsMicrophoneForSession || wantsDeviceAudioForSession
        val foregroundStartError = projectionCoordinator.startForegroundForProjection(wantsAudioForegroundService)
        val audioMode = when {
            wantsMicrophoneForSession && wantsDeviceAudioForSession -> "both"
            wantsMicrophoneForSession -> "mic"
            wantsDeviceAudioForSession -> "device"
            else -> "none"
        }
        XLog.i(getLog("tryStartProjectionForeground", "SP_TRACE route=preflight_v1 stage=foreground_preflight mode=${settings.mode} audioMode=$audioMode hasAudioPermission=$audioPermissionGranted result=${foregroundStartError?.javaClass?.simpleName ?: "ok"}"))
        return foregroundStartError
    }

    private fun clearPreparedProjectionStartIfNeeded(foregroundStartProcessed: Boolean, foregroundStartError: Throwable?) {
        if (!foregroundStartProcessed || foregroundStartError != null) return
        projectionCoordinator.stop()
        service.stopForeground()
    }

    private val sessionAnalyticsTracker by lazy(LazyThreadSafetyMode.NONE) {
        StreamingSessionAnalyticsTracker(
            analytics = streamingAnalytics,
            streamModeProvider = { StreamMode.RTSP_CLIENT },
            nowElapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )
    }

    private class ActiveProjection(
        val mediaProjection: MediaProjection,
        val virtualDisplay: VirtualDisplay,
        val videoEncoder: VideoEncoder,
        var captureSurface: Surface,
        var audioEncoder: AudioEncoder? = null,
        var fileRecorder: Fmp4Recorder? = null,
        var deviceConfiguration: Configuration,
        val onVideoReconfigureStart: () -> Unit = {}
    ) {
        fun stop(projectionCallback: MediaProjection.Callback) {
            videoEncoder.stop()
            virtualDisplay.surface = null
            virtualDisplay.release()
            runCatching { captureSurface.release() }

            audioEncoder?.stop()
            fileRecorder?.stop()

            mediaProjection.unregisterCallback(projectionCallback)
        }

        fun reconfigureVideo(width: Int, height: Int, fps: Int, bitRate: Int, densityDpi: Int) {
            val oldSurface = captureSurface
            onVideoReconfigureStart()
            virtualDisplay.surface = null
            videoEncoder.stop()
            videoEncoder.prepare(width, height, fps, bitRate)
            val inputSurfaceTexture = videoEncoder.inputSurfaceTexture ?: throw IllegalStateException("VideoEncoder input surface is null")
            val newSurface = Surface(inputSurfaceTexture)
            virtualDisplay.resize(width, height, densityDpi)
            virtualDisplay.surface = newSurface
            captureSurface = newSurface
            runCatching { oldSurface.release() }
            videoEncoder.start()
        }
    }

    private class ProjectionState(
        var pendingStartAttemptId: String? = null,
        var waitingForPermission: Boolean = false,
        var cachedIntent: Intent? = null,
        var active: ActiveProjection? = null,
        var lastVideoParams: VideoParams? = null,
        var lastAudioParams: AudioParams? = null
    )

    // All vars must be read/write on this (RTSP_HT) thread
    private var selectedVideoEncoderInfo: VideoCodecInfo? = null
    private var selectedAudioEncoderInfo: AudioCodecInfo? = null
    private var projectionState: ProjectionState = ProjectionState()
    private var clientController: RtspClientController? = null

    private var currentError: RtspError? = null
    private var previousError: RtspError? = null
    private var audioCaptureDisabled: Boolean = false
    private var audioIssueToastShown: Boolean = false
    private var resizeActor: ResizeConflateActor? = null
    private var settingsLoaded: Boolean = false
    private var initializedMode: RtspSettings.Values.Mode? = null
    // All vars must be read/write on this (RTSP_HT) thread

    private inner class ResizeConflateActor(
        private val projection: ActiveProjection,
        initialEncodedWidth: Int,
        initialEncodedHeight: Int
    ) {
        private val resizeRequests = Channel<Pair<Int, Int>>(Channel.CONFLATED)
        private var encodedSize: Pair<Int, Int> = initialEncodedWidth to initialEncodedHeight
        private val job: Job = coroutineScope.launch {
            for (source in resizeRequests) {
                val activeProjection = projectionState.active
                if (activeProjection !== projection) continue

                val videoCapabilities = selectedVideoEncoderInfo?.capabilities?.videoCapabilities ?: continue
                val settings = rtspSettings.data.value
                val (_, targetWidth, targetHeight) = videoCapabilities.adjustResizeFactor(
                    source.first, source.second, settings.videoResizeFactor / 100
                )
                if (targetWidth == encodedSize.first && targetHeight == encodedSize.second) continue

                try {
                    activeProjection.reconfigureVideo(
                        width = targetWidth,
                        height = targetHeight,
                        fps = settings.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                        bitRate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange()),
                        densityDpi = service.resources.displayMetrics.densityDpi
                    )
                    encodedSize = targetWidth to targetHeight
                } catch (cause: Throwable) {
                    sendEvent(InternalEvent.Error(cause.toVideoReconfigureError()))
                    return@launch
                }
            }
        }

        fun offer(sourceWidth: Int, sourceHeight: Int) {
            if (sourceWidth <= 0 || sourceHeight <= 0) return
            resizeRequests.trySend(sourceWidth to sourceHeight)
        }

        fun close() {
            resizeRequests.close()
            job.cancel()
        }
    }


    private inner class RtspClientController() {
        var status: RtspClientStatus = RtspClientStatus.IDLE

        private var client: RtspClient? = null
        private var generation: Long = 0L
        private var reconnectAttempts: Long = 0L

        fun startClient(rtspUrl: RtspUrl, onlyVideo: Boolean) {
            currentError = null
            client = RtspClient(appVersion, ++generation, rtspUrl, rtspSettings.data.value.clientProtocol, onlyVideo) {
                XLog.d(getLog("RtspClient.sendEvent", it.toString()))
                sendEvent(it)
            }
        }

        fun connect() {
            currentError = null
            status = RtspClientStatus.STARTING
            client?.connect()
        }

        fun stop() {
            if (client != null) {
                generation++
                client?.destroy(); client = null
            }
            status = RtspClientStatus.IDLE
        }

        fun beginVideoReconfigure() {
            client?.beginVideoReconfigure()
        }

        fun onEvent(event: InternalEvent.RtspClient) {
            if (event.generation != generation) {
                XLog.d(getLog("RtspClient:${event::class.simpleName}", "Stale generation=${event.generation}. Ignoring."))
                return
            }

            when (event) {
                is InternalEvent.RtspClient.OnConnectionSuccess -> {
                    status = RtspClientStatus.ACTIVE
                    currentError = null
                    reconnectAttempts = 0L
                    service.updateStreamingBitrateNotification(bitsPerSecond = 0)
                }

                is InternalEvent.RtspClient.OnDisconnect -> {
                    handleTransportFailure(
                        stopReason = "RtspClientDisconnect",
                        error = RtspError.ClientError.Failed("Disconnected")
                    )
                }

                is InternalEvent.RtspClient.OnBitrate -> {
                    if (status == RtspClientStatus.ACTIVE) {
                        service.updateStreamingBitrateNotification(event.bitrate)
                    }
                }

                is InternalEvent.RtspClient.OnError -> {
                    handleTransportFailure(
                        stopReason = "RtspClientError",
                        error = event.error
                    )
                }
            }
        }

        private fun handleTransportFailure(stopReason: String, error: RtspError.ClientError) {
            if (!shouldKeepRecordingWhileRetryingRtsp()) {
                stopStream(stopServer = true, stopReason = stopReason)
                status = RtspClientStatus.ERROR
                currentError = error
                return
            }

            XLog.w(getLog("RtspClientController.handleTransportFailure", "$stopReason while local recording continues; scheduling RTSP retry"))
            stop()
            status = RtspClientStatus.ERROR
            currentError = error
            reconnectAttempts += 1
            service.updateStreamingBitrateNotification(bitsPerSecond = 0)
            sendEvent(InternalEvent.RetryRtspClient(reconnectAttempts), timeout = RTSP_RECONNECT_DELAY_MS)
        }

        fun setVideoParams(video: VideoParams) {
            client?.setVideoData(video.codec, video.sps, video.pps, video.vps)
        }

        fun setAudioParams(audio: AudioParams?) {
            client?.setAudioData(audio)
        }

        fun onFrame(frame: MediaFrame) {
            client?.enqueueFrame(frame) ?: frame.release()
        }
    }

    internal sealed class InternalEvent(priority: Int) : RtspEvent(priority) {
        data class InitState(val clearIntent: Boolean, val mode: RtspSettings.Values.Mode, val pendingStartAttemptId: String? = null) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnVideoCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class OnAudioCodecChange(val name: String?) : InternalEvent(Priority.DESTROY_IGNORE)
        data class ModeChanged(val mode: RtspSettings.Values.Mode) : InternalEvent(Priority.RECOVER_IGNORE)
        data class StartStream(val permissionEducationShown: Boolean) : InternalEvent(Priority.RECOVER_IGNORE)
        data class AudioCaptureError(val cause: Throwable) : InternalEvent(Priority.RECOVER_IGNORE)

        data class OnAudioParamsChange(val micMute: Boolean, val deviceMute: Boolean, val micVolume: Float, val deviceVolume: Float) :
            InternalEvent(Priority.DESTROY_IGNORE)

        data class ConfigurationChange(val newConfig: Configuration) : InternalEvent(Priority.RECOVER_IGNORE) {
            override fun toString(): String = "ConfigurationChange"
        }

        data class CapturedContentResize(val width: Int, val height: Int) : InternalEvent(Priority.RECOVER_IGNORE)
        data class RetryRtspClient(val attempt: Long) : InternalEvent(Priority.RETRY_RTSP)
        data class Error(val error: RtspError) : InternalEvent(Priority.RECOVER_IGNORE)
        data class Destroy(val destroyJob: CompletableJob) : InternalEvent(Priority.DESTROY_IGNORE)

        sealed class RtspClient(priority: Int) : InternalEvent(priority) {
            abstract val generation: Long

            data class OnConnectionSuccess(override val generation: Long) : RtspClient(Priority.RECOVER_IGNORE)
            data class OnDisconnect(override val generation: Long) : RtspClient(Priority.DESTROY_IGNORE)
            data class OnBitrate(override val generation: Long, val bitrate: Long) : RtspClient(Priority.DESTROY_IGNORE)
            data class OnError(override val generation: Long, val error: RtspError.ClientError) : RtspClient(Priority.RECOVER_IGNORE)
        }


        data class OnVideoFps(val fps: Int) : InternalEvent(Priority.DESTROY_IGNORE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private val componentCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sendEvent(InternalEvent.ConfigurationChange(newConfig))
        override fun onLowMemory() = Unit
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(this@RtspStreamingService.getLog("MediaProjection.Callback", "onStop (handled by coordinator)"))
        }

        // TODO https://android-developers.googleblog.com/2024/03/enhanced-screen-sharing-capabilities-in-android-14.html
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            XLog.d(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            XLog.d(this@RtspStreamingService.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
            sendEvent(InternalEvent.CapturedContentResize(width, height))
        }
    }

    init {
        XLog.d(getLog("init"))
    }

    private companion object {
        private const val RTSP_RECONNECT_DELAY_MS: Long = 3_000
    }

    @MainThread
    override fun start() {
        super.start()
        XLog.d(getLog("start"))

        mutableRtspStateFlow.value = buildViewState()

        fun <T> Flow<T>.listenForChange(scope: CoroutineScope, drop: Int = 0, action: suspend (T) -> Unit) =
            distinctUntilChanged().drop(drop).onEach { action(it) }.launchIn(scope)

        service.startListening(
            supervisorJob,
            onScreenOff = { if (rtspSettings.data.value.stopOnSleep) sendEvent(RtspEvent.Intentable.StopStream("ScreenOff")) },
            onConnectionChanged = { }
        )

        rtspSettings.data.map { it.videoCodecAutoSelect to it.videoCodec }.listenForChange(coroutineScope) {
            if (it.first) sendEvent(InternalEvent.OnVideoCodecChange(null))
            else sendEvent(InternalEvent.OnVideoCodecChange(it.second))
        }

        rtspSettings.data.map { it.audioCodecAutoSelect to it.audioCodec }.listenForChange(coroutineScope) {
            if (it.first) sendEvent(InternalEvent.OnAudioCodecChange(null))
            else sendEvent(InternalEvent.OnAudioCodecChange(it.second))
        }

        rtspSettings.data.map { InternalEvent.OnAudioParamsChange(it.muteMic, it.muteDeviceAudio, it.volumeMic, it.volumeDeviceAudio) }
            .listenForChange(coroutineScope) { sendEvent(it) }

        rtspSettings.data.map { it.mode }.listenForChange(coroutineScope, 1) { mode ->
            if (!settingsLoaded) {
                settingsLoaded = true
                sendEvent(InternalEvent.InitState(clearIntent = true, mode = mode))
            } else {
                sendEvent(InternalEvent.ModeChanged(mode))
            }
        }

        coroutineScope.launch {
            delay(250)
            if (settingsLoaded) return@launch

            settingsLoaded = true
            val mode = rtspSettings.data.value.mode
            sendEvent(InternalEvent.InitState(clearIntent = true, mode = mode))
        }
    }

    @MainThread
    suspend fun destroyService() {
        XLog.d(getLog("destroyService"))

        supervisorJob.cancel()

        val destroyJob = Job()
        sendEvent(InternalEvent.Destroy(destroyJob))
        withTimeoutOrNull(3000) { destroyJob.join() } ?: XLog.w(getLog("destroyService", "Timeout"))

        handler.removeCallbacksAndMessages(null)

        service.stopSelf()

        quit() // Only after everything else is destroyed
    }

    private var destroyPending: Boolean = false

    @AnyThread
    @Synchronized
    internal fun sendEvent(event: RtspEvent, timeout: Long = 0) {
        if (destroyPending) {
            when (event) {
                is InternalEvent.StartStream,
                is RtspEvent.CastPermissionsDenied,
                is RtspEvent.StartProjection -> sessionAnalyticsTracker.onStartAborted()
            }
            XLog.w(getLog("sendEvent", "Pending destroy: Ignoring event => $event"))
            return
        }
        if (event is InternalEvent.Destroy) destroyPending = true

        if (timeout > 0) XLog.d(getLog("sendEvent", "New event [Timeout: $timeout] => $event"))
        else XLog.v(getLog("sendEvent", "New event => $event"))

        if (event is RtspEvent.Intentable.RecoverError) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
            handler.removeMessages(RtspEvent.Priority.RETRY_RTSP)
        }
        if (event is InternalEvent.Destroy) {
            handler.removeMessages(RtspEvent.Priority.RESTART_IGNORE)
            handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
            handler.removeMessages(RtspEvent.Priority.DESTROY_IGNORE)
            handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
            handler.removeMessages(RtspEvent.Priority.RETRY_RTSP)
        }
        if (event is RtspEvent.StartProjection) {
            if (handler.hasMessages(RtspEvent.Priority.START_PROJECTION)) {
                XLog.i(getLog("sendEvent", "Replacing pending StartProjection"))
            }
            handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
        }
        if (event is InternalEvent.RetryRtspClient) {
            if (handler.hasMessages(RtspEvent.Priority.RETRY_RTSP)) {
                XLog.i(getLog("sendEvent", "Replacing pending RetryRtspClient"))
            }
            handler.removeMessages(RtspEvent.Priority.RETRY_RTSP)
        }

        val wasSent = handler.sendMessageDelayed(handler.obtainMessage(event.priority, event), timeout)
        if (!wasSent) XLog.e(getLog("sendEvent", "Failed to send event: $event"))
    }

    private fun buildViewState(): RtspState {
        val status = clientController?.status ?: RtspClientStatus.IDLE
        val audioEnabled = rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio
        val clientReady = clientController != null
        val videoReady = selectedVideoEncoderInfo != null
        val audioReady = audioEnabled.not() || selectedAudioEncoderInfo != null
        val readinessBusy = (clientReady && videoReady && audioReady).not()
        val errorBlocks = currentError != null && currentError !is RtspError.ClientError
        val isBusy = destroyPending || !settingsLoaded || initializedMode == null || projectionState.pendingStartAttemptId != null || errorBlocks || readinessBusy

        return RtspState(
            clientStatus = status,
            isBusy = isBusy,
            waitingCastPermission = projectionState.waitingForPermission,
            startAttemptId = projectionState.pendingStartAttemptId,
            isStreaming = projectionState.active != null,
            selectedVideoEncoder = selectedVideoEncoderInfo,
            selectedAudioEncoder = selectedAudioEncoderInfo,
            error = currentError
        )
    }

    override fun handleMessage(msg: Message): Boolean = runBlocking(Dispatchers.Unconfined) {
        val event: RtspEvent = msg.obj as RtspEvent
        try {
            processEvent(event)
        } catch (cause: Throwable) {
            XLog.e(this@RtspStreamingService.getLog("handleMessage.catch", cause.toString()), cause)

            sessionAnalyticsTracker.onStartFailedIfPending(StartFailGroup.UNKNOWN)
            projectionState.cachedIntent = null
            projectionState.pendingStartAttemptId = null
            projectionState.waitingForPermission = false
            stopStream(stopServer = true, stopReason = "HandleMessageException")

            currentError = cause as? RtspError ?: RtspError.UnknownError(cause)
            clientController?.status = RtspClientStatus.ERROR
        } finally {
            if (event is InternalEvent.Destroy) event.destroyJob.complete()
            sessionAnalyticsTracker.onActiveConsumersChanged(currentActiveConsumersCount())

            mutableRtspStateFlow.value = buildViewState()

            if (previousError != currentError) {
                previousError = currentError
                val notifyError = currentError?.takeUnless {
                    it is RtspError.ClientError || it is RtspError.NotificationPermissionRequired
                }
                notifyError?.let { service.showErrorNotification(it) } ?: service.hideErrorNotification()
            }
        }

        true
    }

    // On RTSP-HT only
    private fun processEvent(event: RtspEvent) {
        when (event) {
            is InternalEvent.InitState -> {
                clientController = RtspClientController()
                initializedMode = RtspSettings.Values.Mode.CLIENT
                projectionState = ProjectionState(
                    pendingStartAttemptId = event.pendingStartAttemptId,
                    waitingForPermission = false,
                    cachedIntent = if (event.clearIntent) null else projectionState.cachedIntent
                )
                resizeActor?.close()
                resizeActor = null
                currentError = null
                previousError = null
                audioCaptureDisabled = false
                audioIssueToastShown = false
            }

            is InternalEvent.OnVideoCodecChange -> {
                require(projectionState.active == null) { "Cannot change codec while streaming" }

                selectedVideoEncoderInfo = null
                val available = EncoderUtils.availableVideoEncoders
                selectedVideoEncoderInfo = when {
                    available.isEmpty() -> throw IllegalStateException("No suitable video encoders available")
                    // Auto select
                    event.name.isNullOrBlank() -> available.first()

                    // We have saved codec, checking if it's available
                    else -> available.firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: available.first()
                }
            }

            is InternalEvent.OnAudioCodecChange -> {
                require(projectionState.active == null) { "Cannot change codec while streaming" }

                selectedAudioEncoderInfo = null
                val available = EncoderUtils.availableAudioEncoders
                selectedAudioEncoderInfo = when {
                    available.isEmpty() -> {
                        if (rtspSettings.data.value.enableMic || rtspSettings.data.value.enableDeviceAudio) {
                            throw IllegalStateException("No suitable audio encoders available")
                        }
                        null
                    }
                    // Auto select
                    event.name.isNullOrBlank() -> available.first()

                    // We have saved codec, checking if it's available
                    else -> available.firstOrNull { it.name.equals(event.name, ignoreCase = true) } ?: available.first()
                }
            }

            is InternalEvent.ModeChanged -> {
                val forcedMode = RtspSettings.Values.Mode.CLIENT
                if (forcedMode == initializedMode) {
                    XLog.d(getLog("ModeChanged", "Already initialized for mode=$initializedMode. Ignoring."))
                    return
                }
                stopStream(stopServer = true, stopReason = "ModeChanged")
                sendEvent(InternalEvent.InitState(clearIntent = false, mode = forcedMode))
            }

            is InternalEvent.StartStream -> {
                if (!settingsLoaded || initializedMode == null) {
                    XLog.i(getLog("StartStream", "Settings are not initialized yet. Ignoring."))
                    return
                }
                if (projectionState.pendingStartAttemptId != null) {
                    XLog.i(getLog("StartStream", "Permission already pending id=${projectionState.pendingStartAttemptId ?: "none"}"))
                    return
                }
                if (projectionState.active != null) {
                    XLog.d(getLog("StartStream", "Already streaming. Ignoring."))
                    return
                }
                val settings = rtspSettings.data.value
                if (!settings.enableRtspOutput && !settings.enableFileSaveOutput) {
                    currentError = RtspError.ClientError.OutputsDisabled()
                    XLog.i(getLog("StartStream", "Both outputs disabled. Ignoring."))
                    return
                }

                val audioEnabled = settings.enableMic || settings.enableDeviceAudio
                val blockedByError = currentError != null && currentError !is RtspError.ClientError
                val notReady = blockedByError || clientController == null || selectedVideoEncoderInfo == null || (audioEnabled && selectedAudioEncoderInfo == null)
                if (notReady) {
                    XLog.i(
                        getLog(
                            "StartStream",
                            "Not ready. blockedByError=$blockedByError clientReady=${clientController != null} videoReady=${selectedVideoEncoderInfo != null} audioReady=${!audioEnabled || selectedAudioEncoderInfo != null}"
                        )
                    )
                    return
                }
                sessionAnalyticsTracker.onStartAttempt(
                    entryPoint = EntryPoint.BUTTON,
                    usedCachedPermission = projectionState.cachedIntent != null,
                    permissionEducationShown = event.permissionEducationShown
                )

                projectionState.pendingStartAttemptId = Uuid.random().toString()
                val startAttemptId = projectionState.pendingStartAttemptId!!

                projectionState.cachedIntent?.let {
                    check(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { "RtspEvent.StartStream: UPSIDE_DOWN_CAKE" }
                    projectionState.waitingForPermission = false
                    RtspModuleService.dispatchProjectionIntent(service, startAttemptId, it)
                } ?: run {
                    projectionState.waitingForPermission = true
                    XLog.i(getLog("Permission", "MP_UI request id=$startAttemptId source=button"))
                }
            }


            is RtspEvent.CastPermissionsDenied -> {
                val currentStartAttemptId = projectionState.pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("CastPermissionsDenied", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    return
                }
                projectionState.pendingStartAttemptId = null
                projectionState.waitingForPermission = false
                sessionAnalyticsTracker.onStartFailed(StartFailGroup.PERMISSION_DENIED)
            }

            is RtspEvent.StartProjection -> {
                val currentStartAttemptId = projectionState.pendingStartAttemptId
                if (currentStartAttemptId != event.startAttemptId) {
                    XLog.i(getLog("StartProjection", "MP_UI stale id=${event.startAttemptId} current=${currentStartAttemptId ?: "none"}"))
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    return
                }
                projectionState.waitingForPermission = false

                if (!settingsLoaded || initializedMode == null) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.i(getLog("StartProjection", "Settings are not initialized yet. Ignoring."))
                    return
                }

                if (projectionState.active != null) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartAborted()
                    XLog.d(getLog("StartProjection", "Already streaming. Ignoring."))
                    return
                }
                if (selectedVideoEncoderInfo == null) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    throw IllegalStateException("No video encoder selected")
                }

                val settings = rtspSettings.data.value
                val rtspOutputEnabled = settings.enableRtspOutput
                val fileSaveEnabled = settings.enableFileSaveOutput

                if (!rtspOutputEnabled && !fileSaveEnabled) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                    currentError = RtspError.ClientError.OutputsDisabled()
                    return
                }

                if (fileSaveEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val canWriteSharedStorage =
                        ContextCompat.checkSelfPermission(service, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    if (!canWriteSharedStorage) {
                        clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                        projectionState.pendingStartAttemptId = null
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                        currentError = RtspError.ClientError.SaveFailed(service.getString(R.string.rtsp_save_permission_required))
                        return
                    }
                }

                val audioEnabled = settings.enableMic || settings.enableDeviceAudio

                if (audioEnabled && selectedAudioEncoderInfo == null) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    throw IllegalStateException("No audio encoder selected")
                }

                val modeLocal = RtspSettings.Values.Mode.CLIENT
                val clientController = clientController ?: run {
                    XLog.w(getLog("StartProjection", "Client controller is null. Reinitializing."))
                    sendEvent(InternalEvent.InitState(clearIntent = false, mode = modeLocal, pendingStartAttemptId = event.startAttemptId))
                    sendEvent(event, timeout = 50)
                    return
                }

                val clientRtspUrl = if (rtspOutputEnabled) {
                    try {
                        RtspUrl.parse(settings.serverAddress)
                    } catch (e: URISyntaxException) {
                        clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                        projectionState.pendingStartAttemptId = null
                        XLog.w(getLog("StartProjection", "Bad RTSP URL: ${settings.serverAddress}"), e)
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                        stopStream(stopServer = true, stopReason = "StartProjectionInvalidRtspUrl")
                        currentError = RtspError.ClientError.Failed(e.reason ?: e.message)
                        clientController.status = RtspClientStatus.ERROR
                        return
                    }
                } else {
                    null
                }

                if (fileSaveEnabled && selectedVideoEncoderInfo?.codec !is Codec.Video.H264 && selectedVideoEncoderInfo?.codec !is Codec.Video.H265) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                    currentError = RtspError.ClientError.SaveUnsupportedVideoCodec()
                    return
                }

                if (fileSaveEnabled && audioEnabled && selectedAudioEncoderInfo?.codec !is Codec.Audio.AAC) {
                    clearPreparedProjectionStartIfNeeded(event.foregroundStartProcessed, event.foregroundStartError)
                    projectionState.pendingStartAttemptId = null
                    sessionAnalyticsTracker.onStartFailed(StartFailGroup.UNKNOWN)
                    currentError = RtspError.ClientError.SaveUnsupportedAudioCodec()
                    return
                }

                var fileRecorder: Fmp4Recorder? = null
                val setVideoParams: (VideoParams) -> Unit = { video ->
                    if (rtspOutputEnabled) clientController.setVideoParams(video)
                    fileRecorder?.setVideoParams(video)
                }
                val setAudioParams: (AudioParams?) -> Unit = { audio ->
                    if (rtspOutputEnabled) clientController.setAudioParams(audio)
                    fileRecorder?.setAudioParams(audio)
                }
                val onFrame: (MediaFrame) -> Unit = { frame ->
                    val recorder = fileRecorder
                    when {
                        rtspOutputEnabled && recorder != null -> {
                            val copy = frame.detachedCopy()
                            clientController.onFrame(frame)
                            recorder.onFrame(copy)
                        }

                        rtspOutputEnabled -> clientController.onFrame(frame)
                        recorder != null -> recorder.onFrame(frame)
                        else -> frame.release()
                    }
                }

                val audioPermissionGranted =
                    ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val wantsAudio = settings.enableMic || settings.enableDeviceAudio
                if (!audioPermissionGranted && wantsAudio) {
                    coroutineScope.launch {
                        rtspSettings.updateData { copy(enableMic = false, enableDeviceAudio = false) }
                    }
                }
                val wantsMicrophoneForSession = audioPermissionGranted && settings.enableMic
                val wantsDeviceAudioForSession = audioPermissionGranted && settings.enableDeviceAudio
                // Playback capture also records audio and shares the same audio FGS path on Android 14+.
                val wantsAudioForegroundService = wantsMicrophoneForSession || wantsDeviceAudioForSession
                val audioMode = when {
                    wantsMicrophoneForSession && wantsDeviceAudioForSession -> "both"
                    wantsMicrophoneForSession -> "mic"
                    wantsDeviceAudioForSession -> "device"
                    else -> "none"
                }
                projectionState.pendingStartAttemptId = null
                XLog.i(getLog("StartProjection", "SP_TRACE route=preflight_v1 stage=async_start startAttemptId=${event.startAttemptId} mode=$modeLocal audioMode=$audioMode settingsLoaded=$settingsLoaded cachedIntent=${projectionState.cachedIntent != null}"))
                val startProjection = {
                    projectionCoordinator.startProjection(event.intent) { _, mediaProjection, audioCaptureAllowed, isStartupStillValid ->
                        // TODO Starting from Android R, if your application requests the SYSTEM_ALERT_WINDOW permission, and the user has
                        //  not explicitly denied it, the permission will be automatically granted until the projection is stopped.
                        //  The permission allows your app to display user controls on top of the screen being captured.
                        mediaProjection.registerCallback(projectionCallback, handler)

                        MasterClock.reset()
                        MasterClock.ensureStarted()

                        var virtualDisplay: VirtualDisplay? = null
                        var captureSurface: Surface? = null
                        val deviceConfiguration = Configuration(service.resources.configuration)
                        val videoEncoderInfo = selectedVideoEncoderInfo!!
                        val videoCapabilities = videoEncoderInfo.capabilities.videoCapabilities!!
                        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                        val sourceWidth = bounds.width()
                        val sourceHeight = bounds.height()
                        val (_, encodedWidth, encodedHeight) = videoCapabilities.adjustResizeFactor(
                            sourceWidth, sourceHeight, settings.videoResizeFactor / 100
                        )

                        if (fileSaveEnabled) {
                            val recorder = try {
                                Fmp4Recorder(
                                    context = service,
                                    encodedWidth = encodedWidth,
                                    encodedHeight = encodedHeight,
                                    expectAudioTrack = wantsMicrophoneForSession || wantsDeviceAudioForSession
                                )
                            } catch (cause: Throwable) {
                                XLog.w(getLog("StartProjection", "Recorder init failed"), cause)
                                currentError = RtspError.ClientError.SaveFailed(cause.message)
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@startProjection false
                            }
                            fileRecorder = recorder
                            XLog.i(getLog("StartProjection", "File recording: ${recorder.outputPath}"))
                        }

                        val videoEncoder = VideoEncoder(
                            codecInfo = videoEncoderInfo,
                            onVideoInfo = { sps, pps, vps ->
                                val params = VideoParams(videoEncoderInfo.codec, sps, pps, vps)
                                projectionState.lastVideoParams = params
                                setVideoParams(params)
                            },
                            onVideoFrame = onFrame,
                            onFps = { sendEvent(InternalEvent.OnVideoFps(it)) },
                            onError = {
                                XLog.w(getLog("VideoEncoder.onError", it.message), it)
                                sendEvent(InternalEvent.Error(it.toVideoPipelineError()))
                            }
                        ).apply {
                            prepare(
                                encodedWidth,
                                encodedHeight,
                                fps = settings.videoFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange()),
                                bitRate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())
                            )
                            if (!isStartupStillValid()) {
                                XLog.i(getLog("StartProjection", "Startup invalidated before virtual display creation."))
                                stop()
                                fileRecorder?.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@startProjection false
                            }

                            this.inputSurfaceTexture?.let { surfaceTexture ->
                                captureSurface = Surface(surfaceTexture)
                                virtualDisplay = mediaProjection.createVirtualDisplay(
                                    "ScreenStreamVirtualDisplay",
                                    encodedWidth,
                                    encodedHeight,
                                    service.resources.displayMetrics.densityDpi,
                                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                    captureSurface,
                                    null,
                                    null
                                )
                            }

                            if (virtualDisplay == null || !isStartupStillValid()) {
                                val reason = if (virtualDisplay == null) "virtualDisplay is null" else "startup invalidated"
                                XLog.i(getLog("startDisplayCapture", "$reason. Stopping projection."))
                                stop()
                                fileRecorder?.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                runCatching { captureSurface?.release() }
                                return@startProjection false
                            }

                            start()
                        }

                        val microphoneEnabledForSession = wantsMicrophoneForSession && audioCaptureAllowed
                        val deviceAudioEnabledForSession = wantsDeviceAudioForSession
                        val audioEnabledForSession = microphoneEnabledForSession || deviceAudioEnabledForSession
                        var audioEncoder: AudioEncoder? = null
                        if (audioEnabledForSession) {
                            val audioEncoderInfo = selectedAudioEncoderInfo!!
                            audioEncoder = AudioEncoder(
                                codecInfo = audioEncoderInfo,
                                onAudioInfo = { params ->
                                    val audioParams = AudioParams(audioEncoderInfo.codec, params.sampleRate, params.isStereo)
                                    projectionState.lastAudioParams = audioParams
                                    setAudioParams(audioParams)
                                },
                                onAudioFrame = onFrame,
                                onAudioCaptureError = { sendEvent(InternalEvent.AudioCaptureError(it)) },
                                onError = {
                                    XLog.w(getLog("AudioEncoder.onError", it.message), it)
                                    sendEvent(InternalEvent.Error(it.toAudioPipelineError()))
                                }
                            ).apply {
                                val requestedBitrate = settings.audioBitrateBits
                                val requestedStereo = settings.stereoAudio
                                val paramsFromSettings = when (audioEncoderInfo.codec) {
                                    is Codec.Audio.G711 -> AudioSource.Params.DEFAULT_G711.copy(
                                        bitrate = 64 * 1000,
                                        echoCanceler = settings.audioEchoCanceller,
                                        noiseSuppressor = settings.audioNoiseSuppressor
                                    )

                                    is Codec.Audio.OPUS -> AudioSource.Params.DEFAULT_OPUS.copy(
                                        bitrate = requestedBitrate,
                                        echoCanceler = settings.audioEchoCanceller,
                                        noiseSuppressor = settings.audioNoiseSuppressor,
                                        isStereo = true
                                    )

                                    else -> AudioSource.Params.DEFAULT.copy(
                                        bitrate = requestedBitrate,
                                        isStereo = requestedStereo,
                                        echoCanceler = settings.audioEchoCanceller,
                                        noiseSuppressor = settings.audioNoiseSuppressor
                                    )
                                }

                                prepare(
                                    enableMic = microphoneEnabledForSession,
                                    enableDeviceAudio = deviceAudioEnabledForSession,
                                    dispatcher = Dispatchers.IO,
                                    audioParams = paramsFromSettings,
                                    audioSource = MediaRecorder.AudioSource.DEFAULT,
                                    mediaProjection = mediaProjection,
                                )

                                setMute(settings.muteMic, settings.muteDeviceAudio)
                                setVolume(settings.volumeMic, settings.volumeDeviceAudio)
                                start()
                            }
                        } else {
                            projectionState.lastAudioParams = null
                            setAudioParams(null)
                        }

                        if (!isStartupStillValid()) {
                            XLog.i(getLog("StartProjection", "Startup invalidated after encoder startup."))
                            videoEncoder.stop()
                            virtualDisplay?.surface = null
                            virtualDisplay?.release()
                            runCatching { captureSurface?.release() }
                            audioEncoder?.stop()
                            fileRecorder?.stop()
                            mediaProjection.unregisterCallback(projectionCallback)
                            return@startProjection false
                        }

                        projectionState.active = ActiveProjection(
                            mediaProjection = mediaProjection,
                            virtualDisplay = virtualDisplay!!,
                            videoEncoder = videoEncoder,
                            captureSurface = captureSurface ?: run {
                                XLog.i(getLog("StartProjection", "captureSurface is null. Stopping projection."))
                                videoEncoder.stop()
                                fileRecorder?.stop()
                                mediaProjection.unregisterCallback(projectionCallback)
                                return@startProjection false
                            },
                            audioEncoder = audioEncoder,
                            fileRecorder = fileRecorder,
                            deviceConfiguration = deviceConfiguration,
                            onVideoReconfigureStart = { clientController.beginVideoReconfigure() }
                        )
                        resizeActor?.close()
                        resizeActor = ResizeConflateActor(
                            projection = projectionState.active!!,
                            initialEncodedWidth = encodedWidth,
                            initialEncodedHeight = encodedHeight
                        )
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            projectionState.cachedIntent = event.intent
                            service.registerComponentCallbacks(componentCallback)
                        }
                        true
                    }
                }
                val startPhase: String
                val startResult = if (event.foregroundStartProcessed) {
                    val foregroundStartError = event.foregroundStartError
                    if (foregroundStartError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundStartError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                } else {
                    val foregroundError = projectionCoordinator.startForegroundForProjection(wantsAudioForegroundService)
                    if (foregroundError != null) {
                        startPhase = "foreground promotion"
                        projectionCoordinator.asForegroundStartResult(foregroundError)
                    } else {
                        startPhase = "projection startup"
                        startProjection()
                    }
                }
                when (startResult) {
                    is ProjectionCoordinator.StartResult.Started -> {
                        if (rtspOutputEnabled && clientRtspUrl != null) {
                            val microphoneEnabledForSession = wantsMicrophoneForSession && startResult.audioCaptureAllowed
                            val audioEnabledForSession = wantsDeviceAudioForSession || microphoneEnabledForSession
                            val onlyVideo = audioCaptureDisabled || !audioEnabledForSession
                            clientController.startClient(clientRtspUrl, onlyVideo)
                            projectionState.lastVideoParams?.let { clientController.setVideoParams(it) }
                            clientController.setAudioParams(projectionState.lastAudioParams)
                            clientController.connect()
                        } else {
                            clientController.status = RtspClientStatus.IDLE
                        }
                        currentError = null
                        sessionAnalyticsTracker.onStarted(currentActiveConsumersCount())
                        XLog.i(getLog("StartProjection", "SP_TRACE route=preflight_v1 stage=result status=started startAttemptId=${event.startAttemptId} mode=$modeLocal audioMode=$audioMode phase=$startPhase cachedIntent=${projectionState.cachedIntent != null}"))
                        XLog.i(getLog("StartProjection", "Started. mode=$modeLocal, intent=${projectionState.cachedIntent != null}, audioFgs=${startResult.audioCaptureAllowed}"))
                    }

                    is ProjectionCoordinator.StartResult.Interrupted -> {
                        if (startResult.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            projectionState.cachedIntent = null
                        }
                        sessionAnalyticsTracker.onStartAborted()
                        XLog.i(getLog("StartProjection", "SP_TRACE route=preflight_v1 stage=result status=interrupted startAttemptId=${event.startAttemptId} mode=$modeLocal audioMode=$audioMode phase=$startPhase cachedIntent=${projectionState.cachedIntent != null}"))
                        XLog.i(getLog("StartProjection", "Interrupted. mode=$modeLocal, intent=${startResult.cachedIntentAction}/${projectionState.cachedIntent != null}"), startResult.cause)
                        stopStream(stopServer = false, stopReason = "StartProjectionInterrupted")
                        currentError = RtspError.ClientError.StartInterrupted()
                    }

                    ProjectionCoordinator.StartResult.Busy -> {
                        sessionAnalyticsTracker.onStartFailed(StartFailGroup.BUSY)
                        XLog.i(getLog("StartProjection", "SP_TRACE route=preflight_v1 stage=result status=busy startAttemptId=${event.startAttemptId} mode=$modeLocal audioMode=$audioMode phase=$startPhase cachedIntent=${projectionState.cachedIntent != null}"))
                        XLog.w(getLog("StartProjection", "Busy during $startPhase. mode=$modeLocal, intent=${projectionState.cachedIntent != null}"))
                        currentError = RtspError.ClientError.StartBusy()
                    }

                    is ProjectionCoordinator.StartResult.Blocked, is ProjectionCoordinator.StartResult.Fatal -> {
                        val cause = startResult.cause ?: error("Missing cause for failed start result")
                        sessionAnalyticsTracker.onStartFailed(
                            if (startResult is ProjectionCoordinator.StartResult.Blocked) StartFailGroup.BLOCKED else StartFailGroup.FATAL
                        )
                        if (startResult.cachedIntentAction == ProjectionCoordinator.CachedIntentAction.INVALIDATE) {
                            projectionState.cachedIntent = null
                        }
                        val logMessage = if (startResult is ProjectionCoordinator.StartResult.Blocked) {
                            "Blocked during $startPhase. mode=$modeLocal, intent=${startResult.cachedIntentAction}/${projectionState.cachedIntent != null}"
                        } else {
                            "Fatal during $startPhase. mode=$modeLocal, intent=${startResult.cachedIntentAction}/${projectionState.cachedIntent != null}"
                        }
                        XLog.i(getLog("StartProjection", "SP_TRACE route=preflight_v1 stage=result status=${if (startResult is ProjectionCoordinator.StartResult.Blocked) "blocked" else "fatal"} startAttemptId=${event.startAttemptId} mode=$modeLocal audioMode=$audioMode phase=$startPhase cachedIntent=${projectionState.cachedIntent != null}"))
                        if (startResult is ProjectionCoordinator.StartResult.Blocked) {
                            XLog.w(getLog("StartProjection", logMessage), cause)
                            currentError = cause as? RtspError ?: RtspError.UnknownError(cause)
                        } else {
                            XLog.e(getLog("StartProjection", logMessage), cause)
                            stopStream(stopServer = true, stopReason = "StartProjectionFailed")
                            currentError = cause as? RtspError ?: RtspError.UnknownError(cause)
                            clientController.status = RtspClientStatus.ERROR
                        }
                    }
                }
            }

            is InternalEvent.OnAudioParamsChange -> {
                projectionState.active?.audioEncoder?.setVolume(event.micVolume, event.deviceVolume)
                projectionState.active?.audioEncoder?.setMute(event.micMute, event.deviceMute)
            }

            is InternalEvent.AudioCaptureError -> {
                if (audioCaptureDisabled) return

                audioCaptureDisabled = true
                projectionState.lastAudioParams = null
                projectionState.active?.audioEncoder?.stop()
                projectionState.active?.audioEncoder = null
                clientController?.setAudioParams(null)
                showAudioCaptureIssueToastOnce()
            }

            is InternalEvent.ConfigurationChange -> {
                val projection = projectionState.active ?: run {
                    XLog.d(getLog("ConfigurationChange", "Not streaming. Ignoring."))
                    return
                }

                if (rtspSettings.data.value.stopOnConfigurationChange) { //TODO Not yet exposed in UI
                    sendEvent(RtspEvent.Intentable.StopStream("ConfigurationChange"))
                    return
                }

                val newConfig = Configuration(event.newConfig)
                val configDiff = projection.deviceConfiguration.diff(newConfig)
                projection.deviceConfiguration = newConfig
                if (configDiff and ActivityInfo.CONFIG_ORIENTATION != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_LAYOUT != 0
                    || configDiff and ActivityInfo.CONFIG_SCREEN_SIZE != 0
                    || configDiff and ActivityInfo.CONFIG_DENSITY != 0
                ) {
                    val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(service).bounds
                    resizeActor?.offer(sourceWidth = bounds.width(), sourceHeight = bounds.height())
                } else {
                    XLog.d(getLog("ConfigurationChange", "No change relevant for streaming. Ignoring."))
                }
            }

            is InternalEvent.CapturedContentResize -> {
                if (projectionState.active == null) {
                    XLog.d(getLog("CapturedContentResize", "Not streaming. Ignoring."))
                    return
                }
                if (event.width <= 0 || event.height <= 0) {
                    XLog.e(
                        getLog("CapturedContentResize", "Invalid size: ${event.width} x ${event.height}. Ignoring."),
                        IllegalArgumentException("Invalid capture size: ${event.width} x ${event.height}")
                    )
                    return
                }
                resizeActor?.offer(sourceWidth = event.width, sourceHeight = event.height)
            }

            is InternalEvent.RetryRtspClient -> {
                if (!shouldKeepRecordingWhileRetryingRtsp()) {
                    XLog.d(getLog("RetryRtspClient", "No active local recording session. Ignoring attempt=${event.attempt}"))
                    return
                }

                val settings = rtspSettings.data.value
                if (!settings.enableRtspOutput) {
                    XLog.d(getLog("RetryRtspClient", "RTSP output disabled. Ignoring attempt=${event.attempt}"))
                    return
                }

                val rtspUrl = try {
                    RtspUrl.parse(settings.serverAddress)
                } catch (e: URISyntaxException) {
                    XLog.w(getLog("RetryRtspClient", "Bad RTSP URL during retry: ${settings.serverAddress}"), e)
                    currentError = RtspError.ClientError.Failed(e.reason ?: e.message)
                    return
                }

                val clientController = clientController ?: run {
                    XLog.w(getLog("RetryRtspClient", "Client controller is null. Reinitializing."))
                    sendEvent(InternalEvent.InitState(clearIntent = false, mode = RtspSettings.Values.Mode.CLIENT))
                    sendEvent(event, timeout = RTSP_RECONNECT_DELAY_MS)
                    return
                }

                if (clientController.status == RtspClientStatus.ACTIVE || clientController.status == RtspClientStatus.STARTING) {
                    XLog.d(getLog("RetryRtspClient", "Client already active/starting. Ignoring attempt=${event.attempt}"))
                    return
                }

                val onlyVideo = projectionState.lastAudioParams == null
                XLog.i(getLog("RetryRtspClient", "Retrying RTSP attempt=${event.attempt}, onlyVideo=$onlyVideo"))
                clientController.startClient(rtspUrl, onlyVideo)
                projectionState.lastVideoParams?.let { clientController.setVideoParams(it) }
                clientController.setAudioParams(projectionState.lastAudioParams)
                clientController.connect()
            }

            is RtspEvent.Intentable.StopStream -> stopStream(stopServer = false, stopReason = event.reason)

            is RtspEvent.Intentable.RecoverError -> {
                if (shouldKeepRecordingWhileRetryingRtsp()) {
                    currentError = null
                    sendEvent(InternalEvent.RetryRtspClient(attempt = 0L))
                    return
                }

                val stopReason = "RecoverError"
                stopStream(stopServer = true, stopReason = stopReason)
                handler.removeMessages(RtspEvent.Priority.RECOVER_IGNORE)
                handler.removeMessages(RtspEvent.Priority.START_PROJECTION)
                val mode = RtspSettings.Values.Mode.CLIENT
                sendEvent(InternalEvent.InitState(clearIntent = true, mode = mode))
            }

            is InternalEvent.Destroy,
            is InternalEvent.Error -> {
                val stopReason = when (event) {
                    is InternalEvent.Destroy -> "Destroy"
                    is InternalEvent.Error -> "InternalError"
                }
                if (event is InternalEvent.Destroy) {
                    sessionAnalyticsTracker.onStartAborted()
                }
                stopStream(stopServer = true, stopReason = stopReason)

                if (event is InternalEvent.Error) {
                    currentError = event.error
                    clientController?.status = RtspClientStatus.ERROR
                }

            }

            is InternalEvent.RtspClient -> {
                val clientController = clientController ?: run {
                    XLog.d(getLog("RtspClient:${event::class.simpleName}", "Controller is null. Ignoring."))
                    return
                }
                clientController.onEvent(event)
            }

            is InternalEvent.OnVideoFps -> Unit //TODO Skipp for now

            else -> throw IllegalArgumentException("Unknown RtspEvent: ${event::class.java}")
        }
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun stopStream(stopServer: Boolean, stopReason: String? = null) {
        val wasStreaming = projectionState.active != null
        val activeConsumersAtStop = currentActiveConsumersCount()
        if (wasStreaming) {
            XLog.i(
                getLog(
                    "stopStream",
                    "stop=$stopReason, mode=${rtspSettings.data.value.mode}, server=$stopServer, consumers=$activeConsumersAtStop, intent=${projectionState.cachedIntent != null}"
                )
            )
        } else {
            XLog.d(
                getLog(
                    "stopStream",
                    "skip. stop=$stopReason, mode=${rtspSettings.data.value.mode}, server=$stopServer, intent=${projectionState.cachedIntent != null}"
                )
            )
        }

        resizeActor?.close()
        resizeActor = null
        handler.removeMessages(RtspEvent.Priority.RETRY_RTSP)
        projectionState.pendingStartAttemptId = null
        projectionState.waitingForPermission = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { service.unregisterComponentCallbacks(componentCallback) }
        }

        audioCaptureDisabled = false
        audioIssueToastShown = false

        clientController?.stop()

        projectionState.active?.stop(projectionCallback)
        projectionState.active = null
        projectionState.lastVideoParams = null
        projectionState.lastAudioParams = null
        projectionCoordinator.stop()

        if (wasStreaming) {
            sessionAnalyticsTracker.onEnded(stopReason, activeConsumersAtStop)
        }

        service.stopForeground()
    }

    private fun shouldKeepRecordingWhileRetryingRtsp(): Boolean {
        val activeProjection = projectionState.active ?: return false
        return rtspSettings.data.value.enableRtspOutput && activeProjection.fileRecorder != null
    }

    // Inline Only
    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentActiveConsumersCount(): Int {
        return if (clientController?.status == RtspClientStatus.ACTIVE) 1 else 0
    }

    private fun showAudioCaptureIssueToastOnce() {
        if (audioIssueToastShown) return
        audioIssueToastShown = true
        mainHandler.post { Toast.makeText(service, R.string.rtsp_audio_capture_issue_detected, Toast.LENGTH_LONG).show() }
    }

    private fun Throwable.toVideoPipelineError(): RtspError.UnknownError =
        if (this is MediaCodec.CodecException) RtspError.VideoCodecError(this)
        else RtspError.VideoRendererError(this)

    private fun Throwable.toVideoReconfigureError(): RtspError.UnknownError = RtspError.VideoReconfigureError(this)

    private fun Throwable.toAudioPipelineError(): RtspError.UnknownError =
        if (this is MediaCodec.CodecException) RtspError.AudioCodecError(this)
        else RtspError.UnknownError(this)
}
