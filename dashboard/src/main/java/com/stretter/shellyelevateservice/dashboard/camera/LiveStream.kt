package com.stretter.shellyelevateservice.dashboard.camera

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.util.Log
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.hass.HassException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * libwebrtc for the whole `:dashboard` process, created on the first live view (loading the
 * native library takes ~100 ms) and kept: one EGL context shared by the hardware decoder and
 * every renderer, receive-only audio (no microphone, so no permission), played as media.
 */
internal class WebRtcEngine private constructor(context: Context) {
    val egl: EglBase = EglBase.create()
    val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        audio.release() // the factory holds its own reference
    }

    companion object {
        @Volatile private var instance: WebRtcEngine? = null

        fun get(context: Context): WebRtcEngine =
            instance ?: synchronized(this) { instance ?: WebRtcEngine(context.applicationContext).also { instance = it } }

        /** The shared EGL context once the engine exists (renderers need it). */
        val eglContext: EglBase.Context? get() = instance?.egl?.eglBaseContext
    }
}

/**
 * Live streams of the process, one per camera entity, shared by every view that shows it (a
 * dashboard card and a popup showing the same camera use one session: cameras like the BTicino
 * intercom only have a few peer slots). A stream stops [LINGER_MS] after its last view is gone.
 */
class LiveStreamHub private constructor(private val context: Context, private val hass: HassClient) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val streams = HashMap<String, LiveStream>()
    private val throttle = OfferThrottle()

    fun acquire(entityId: String): LiveStream = synchronized(streams) {
        val stream = streams.getOrPut(entityId) { LiveStream(entityId, context, hass, scope, throttle) }
        stream.retain()
        stream
    }

    fun release(stream: LiveStream) {
        synchronized(streams) { if (!stream.releaseRef()) return }
        scope.launch {
            delay(LINGER_MS)
            val stop = synchronized(streams) {
                if (stream.refs == 0 && streams[stream.entityId] === stream) {
                    streams.remove(stream.entityId)
                    true
                } else false
            }
            if (stop) stream.stop()
        }
    }

    companion object {
        const val LINGER_MS = 1_500L

        @Volatile private var instance: LiveStreamHub? = null

        fun get(context: Context, hass: HassClient): LiveStreamHub =
            instance?.takeIf { it.hass === hass }
                ?: synchronized(this) {
                    instance?.takeIf { it.hass === hass } ?: LiveStreamHub(context.applicationContext, hass).also { instance = it }
                }
    }
}

/**
 * One camera's WebRTC session, following `ha-web-rtc-player`: ICE servers from
 * `camera/webrtc/get_client_config`, a receive-only offer (audio + video transceivers, reduced
 * by [SdpOffer]) through the `camera/webrtc/offer` subscription, trickled candidates. Fails over
 * to [LiveState.Failed] after [RetryPolicy.maxAttempts]; the view then refreshes still images.
 */
class LiveStream internal constructor(
    val entityId: String,
    private val context: Context,
    private val hass: HassClient,
    private val scope: CoroutineScope,
    private val throttle: OfferThrottle,
    private val policy: RetryPolicy = RetryPolicy(),
) {
    private val _state = MutableStateFlow<LiveState>(LiveState.Connecting(1))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Size of the last frame (rotation applied), null before the first frame. */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    /** The answer accepted an audio stream. */
    private val _hasAudio = MutableStateFlow(false)
    val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    /** libwebrtc is loaded ([WebRtcEngine.eglContext] exists), so renderers can be created. */
    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    /** Remote audio is muted by default (like `hui-image`'s `muted` camera stream). */
    val muted = MutableStateFlow(true)

    internal var refs = 0
        private set
    private var job: Job? = null
    private val sinks = CopyOnWriteArraySet<VideoSink>()
    private val createdAt = SystemClock.elapsedRealtime()

    @Volatile private var lastFrameAt = 0L
    @Volatile private var firstFrame: CompletableDeferred<Unit>? = null

    private val fanOut = VideoSink { frame ->
        lastFrameAt = SystemClock.elapsedRealtime()
        val size = frame.rotatedWidth to frame.rotatedHeight
        if (_videoSize.value != size) _videoSize.value = size
        firstFrame?.complete(Unit)
        for (sink in sinks) sink.onFrame(frame)
    }

    fun addSink(sink: VideoSink) { sinks += sink }

    fun removeSink(sink: VideoSink) { sinks -= sink }

    internal fun retain() {
        refs++
        if (job == null) job = scope.launch { run() }
    }

    /** True when the last reference is gone. */
    internal fun releaseRef(): Boolean {
        refs = (refs - 1).coerceAtLeast(0)
        return refs == 0
    }

    internal suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun run() {
        val supported = try {
            WebRtcSignaling.supportsWebRtc(hass.command(WebRtcSignaling.capabilitiesMessage(entityId)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) throw e
            Log.w(TAG, "$entityId: camera/capabilities failed: ${e.message}")
            false
        }
        if (!supported) {
            fail("camera does not support WebRTC")
            return
        }
        var attempt = 1
        while (true) {
            val wait = throttle.waitBefore(entityId, SystemClock.elapsedRealtime())
            if (wait > 0) delay(wait)
            throttle.onStarted(entityId, SystemClock.elapsedRealtime())
            _state.value = LiveState.Connecting(attempt)
            val started = SystemClock.elapsedRealtime()
            val outcome = try {
                session()
            } catch (e: PermanentFailure) {
                fail(e.message ?: "failed")
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is TimeoutCancellationException) "timeout" else throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            // A stream that played for a while starts over with a fresh attempt budget.
            if (_state.value == LiveState.Playing && SystemClock.elapsedRealtime() - started > RESET_AFTER_PLAYING_MS) attempt = 0
            val next = attempt + 1
            val pause = policy.delayBefore(next.coerceAtLeast(2))
            if (pause == null) {
                fail(outcome)
                return
            }
            Log.i(TAG, "$entityId: attempt $attempt ended ($outcome), retrying in ${pause / 1000} s")
            _state.value = LiveState.Retrying(attempt, pause, outcome)
            delay(pause)
            attempt = next.coerceAtLeast(1)
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, "$entityId: live view unavailable, showing stills: $reason")
        _state.value = LiveState.Failed(reason)
    }

    /** One negotiation and playback; returns (or throws) when the session is over. */
    private suspend fun session(): String = coroutineScope {
        val engine = WebRtcEngine.get(context)
        _engineReady.value = true
        val clientConfig = hass.command(WebRtcSignaling.clientConfigMessage(entityId), timeoutMs = 15_000)
        val iceServers = WebRtcSignaling.parseIceServers(clientConfig).map { spec ->
            PeerConnection.IceServer.builder(spec.urls)
                .apply { spec.username?.let(::setUsername); spec.credential?.let(::setPassword) }
                .createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val ended = CompletableDeferred<String>()
        val local = LocalCandidateQueue()
        val remote = RemoteCandidateQueue()
        var localSdp = ""
        val frame = CompletableDeferred<Unit>().also { firstFrame = it }

        fun sendCandidates(candidates: List<CandidateSpec>) {
            val session = local.sessionId ?: return
            for (c in candidates) {
                launch {
                    try {
                        hass.command(WebRtcSignaling.candidateMessage(entityId, session, c), timeoutMs = 10_000)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) throw e
                        Log.d(TAG, "$entityId: candidate not delivered: ${e.message}")
                    }
                }
            }
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "$entityId: local candidate ${candidate.sdp}")
                sendCandidates(local.onCandidate(CandidateSpec(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "$entityId: ICE $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> pc?.let(::logSelectedPair)
                    PeerConnection.IceConnectionState.FAILED -> ended.complete("ICE failed")
                    PeerConnection.IceConnectionState.CLOSED -> ended.complete("closed")
                    PeerConnection.IceConnectionState.DISCONNECTED -> launch {
                        delay(DISCONNECT_GRACE_MS)
                        if (pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) ended.complete("disconnected")
                    }
                    else -> Unit
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }

        pc = engine.factory.createPeerConnection(rtcConfig, observer) ?: throw IOException("cannot create peer connection")
        val peer = pc!!
        var videoTrack: VideoTrack? = null
        val subscription: Job
        try {
            val audioTransceiver = peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            val videoTransceiver = peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            WebRtcSignaling.parseDataChannel(clientConfig)?.let { peer.createDataChannel(it, DataChannel.Init()) }
            videoTrack = (videoTransceiver.receiver.track() as? VideoTrack)?.also { it.addSink(fanOut) }
            val audioTrack = audioTransceiver.receiver.track() as? AudioTrack
            // Muted: no playout at all (the ADM would otherwise mix silence 100 times a second).
            launch {
                muted.collect {
                    audioTrack?.setEnabled(!it)
                    peer.setAudioPlayout(!it)
                }
            }

            val offer = peer.awaitCreateOffer()
            localSdp = SdpOffer.restrict(offer.description)
            val size = SdpOffer.sizeOf(localSdp)
            if (size > SdpOffer.MAX_OFFER_BYTES) throw PermanentFailure("offer too large ($size bytes)")
            if (!localSdp.contains(" H264/")) Log.w(TAG, "$entityId: no H264 decoder; offering ${offer.description.length} bytes of other codecs")
            peer.awaitSetLocal(SessionDescription(SessionDescription.Type.OFFER, localSdp))
            val offeredAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "$entityId: sending offer ($size bytes, ${offeredAt - createdAt} ms after open)")

            subscription = launch {
                try {
                    hass.subscribe(WebRtcSignaling.offerMessage(entityId, localSdp), resubscribe = false).collect { raw ->
                        when (val event = WebRtcSignaling.parseEvent(raw)) {
                            is SignalingEvent.Session -> sendCandidates(local.onSession(event.sessionId))
                            is SignalingEvent.Answer -> {
                                Log.i(TAG, "$entityId: answer after ${SystemClock.elapsedRealtime() - offeredAt} ms")
                                try {
                                    peer.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, event.sdp))
                                } catch (e: IOException) {
                                    ended.complete("answer rejected: ${e.message}")
                                    return@collect
                                }
                                _hasAudio.value = audioTransceiver.currentDirection == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                                remote.onRemoteDescriptionSet().forEach { addRemote(peer, it, localSdp) }
                            }
                            is SignalingEvent.Candidate -> {
                                Log.d(TAG, "$entityId: remote candidate ${event.candidate.candidate}")
                                remote.onCandidate(event.candidate).forEach { addRemote(peer, it, localSdp) }
                            }
                            is SignalingEvent.Error -> {
                                if (isPermanentWebRtcError(event.code)) ended.completeExceptionally(PermanentFailure("${event.code}: ${event.message}"))
                                else ended.complete("${event.code}: ${event.message}")
                            }
                            null -> Unit
                        }
                    }
                    ended.complete("subscription ended")
                } catch (e: HassException) {
                    ended.completeExceptionally(PermanentFailure("${e.code}: ${e.message}"))
                } catch (e: IOException) {
                    ended.complete("connection to Home Assistant lost")
                }
            }

            // Wait for the first frame, then watch the stream until it ends.
            launch {
                try {
                    withTimeout(FIRST_FRAME_TIMEOUT_MS) { frame.await() }
                } catch (e: TimeoutCancellationException) {
                    ended.complete("no video within ${FIRST_FRAME_TIMEOUT_MS / 1000} s")
                    return@launch
                }
                Log.i(TAG, "$entityId: first frame ${SystemClock.elapsedRealtime() - createdAt} ms after open, ${SystemClock.elapsedRealtime() - offeredAt} ms after offer")
                _state.value = LiveState.Playing
                while (true) {
                    delay(STALL_CHECK_MS)
                    if (SystemClock.elapsedRealtime() - lastFrameAt > STALL_TIMEOUT_MS) {
                        ended.complete("no frames for ${STALL_TIMEOUT_MS / 1000} s")
                        return@launch
                    }
                }
            }
            val reason = ended.await()
            subscription.cancel()
            coroutineContext.cancelChildren() // mute collector, frame watchdog, pending candidates
            reason
        } finally {
            firstFrame = null
            videoTrack?.removeSink(fanOut)
            pc = null
            // dispose() closes the connection and frees the native objects; unsubscribing (above,
            // or by the cancelled scope) makes HA close the session on the camera.
            peer.dispose()
            _hasAudio.value = false
        }
    }

    @Volatile private var pc: PeerConnection? = null

    private fun addRemote(peer: PeerConnection, c: CandidateSpec, localSdp: String) {
        if (c.candidate.isBlank()) return // end of candidates
        val index = c.sdpMLineIndex ?: 0
        val mid = c.sdpMid ?: SdpOffer.midOf(localSdp, index) ?: "0"
        if (!peer.addIceCandidate(IceCandidate(mid, index, c.candidate))) Log.d(TAG, "$entityId: remote candidate rejected: ${c.candidate}")
    }

    /** Logs which candidate types ICE picked (host/srflx/relay), for diagnosing slow starts. */
    private fun logSelectedPair(peer: PeerConnection) {
        peer.getStats { report ->
            val stats = report.statsMap
            val pair = stats.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                ?: return@getStats
            fun type(id: Any?) = stats[id as? String]?.members?.get("candidateType")
            Log.i(TAG, "$entityId: ICE pair local=${type(pair.members["localCandidateId"])} remote=${type(pair.members["remoteCandidateId"])}")
        }
    }

    private class PermanentFailure(message: String) : Exception(message)

    private companion object {
        const val TAG = "CameraLive"
        const val FIRST_FRAME_TIMEOUT_MS = 25_000L
        const val STALL_CHECK_MS = 2_000L
        const val STALL_TIMEOUT_MS = 10_000L
        const val DISCONNECT_GRACE_MS = 8_000L
        const val RESET_AFTER_PLAYING_MS = 60_000L
    }
}

private suspend fun PeerConnection.awaitCreateOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
        override fun onCreateFailure(error: String?) = cont.resumeWithException(IOException("createOffer: $error"))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    }, MediaConstraints())
}

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = suspendCancellableCoroutine { cont ->
    setLocalDescription(setObserver(cont, "setLocalDescription"), sdp)
}

private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = suspendCancellableCoroutine { cont ->
    setRemoteDescription(setObserver(cont, "setRemoteDescription"), sdp)
}

private fun setObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>, what: String) = object : SdpObserver {
    override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
    override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IOException("$what: $error")) }
    override fun onCreateSuccess(sdp: SessionDescription?) = Unit
    override fun onCreateFailure(error: String?) = Unit
}
