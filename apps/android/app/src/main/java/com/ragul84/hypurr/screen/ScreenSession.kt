package com.ragul84.hypurr.screen

import android.content.Context
import com.ragul84.hypurr.model.ScreenConnection
import com.ragul84.hypurr.model.ScreenDisplay
import com.ragul84.hypurr.net.HostClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
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
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Where a remote screen session is (kit ScreenSession.Phase). */
sealed interface ScreenPhase {
    data object Connecting : ScreenPhase
    data object Live : ScreenPhase
    data object Reconnecting : ScreenPhase
    data class Failed(val message: String) : ScreenPhase
    data object Closed : ScreenPhase
}

/**
 * One live view of the computer's screen (kit `ScreenSession.swift`): a WebRTC peer that receives the
 * display as video and sends input over two data channels (`input` reliable, `input-fast` unordered for
 * pointer moves). Signaling goes through the host API, non-trickle: offer and answer carry every candidate.
 * ICE servers come from `screenPrepare` (TURN only when the channel uses the cloud relay).
 */
class ScreenSession(
    context: Context,
    private val client: HostClient,
    /** The phone reaches the computer through the cloud relay: ask the host for TURN servers. */
    private val relay: Boolean,
    private val scope: CoroutineScope,
    val display: ScreenDisplay?,
) {
    private val _phase = MutableStateFlow<ScreenPhase>(ScreenPhase.Connecting)
    val phase: StateFlow<ScreenPhase> = _phase.asStateFlow()
    private val _track = MutableStateFlow<VideoTrack?>(null)
    val track: StateFlow<VideoTrack?> = _track.asStateFlow()
    /** Text the computer copied, waiting for the user to take it. */
    val remoteClipboard = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }
    private var pc: PeerConnection? = null
    private var reliable: DataChannel? = null
    private var fast: DataChannel? = null
    private var session: String? = null
    private var gathered: CompletableDeferred<Unit>? = null
    private var jobs = listOf<Job>()
    private var generation = 0

    fun start() {
        _phase.value = ScreenPhase.Connecting
        scope.launch {
            try {
                connect()
            } catch (e: Exception) {
                if (_phase.value == ScreenPhase.Closed) return@launch
                close()
                _phase.value = ScreenPhase.Failed(e.message ?: "Couldn't connect to the remote screen.")
            }
        }
    }

    private suspend fun connect() {
        val connection: ScreenConnection = client.screenPrepare(relay)
        // Reserve the replacement before closing the old viewer so a renewal keeps the session going.
        val old = session
        teardown()
        val current = generation
        session = connection.session
        old?.let { runCatching { client.screenClose(it) } }
        val config = PeerConnection.RTCConfiguration(connection.iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val done = CompletableDeferred<Unit>()
        gathered = done
        val peer = withContext(Dispatchers.Main) { factory.createPeerConnection(config, Events(current)) }
            ?: throw IllegalStateException("Couldn't start the video connection.")
        pc = peer
        val video = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        _track.value = video.receiver.track() as? VideoTrack
        reliable = peer.createDataChannel("input", DataChannel.Init()).also { it.registerObserver(Incoming()) }
        fast = peer.createDataChannel("input-fast", DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        })

        val offer = peer.await { o -> createOffer(o, MediaConstraints()) }
        peer.awaitSet { o -> setLocalDescription(o, offer) }
        // Non-trickle: wait for every candidate (at most 10 s, as on the iPhone).
        withTimeoutOrNull(10_000) { done.await() }
        check(generation == current && _phase.value != ScreenPhase.Closed) { "closed" }
        val sdp = peer.localDescription?.description ?: offer.description
        val (id, answer) = client.screenOffer(sdp, session, display?.id)
        check(generation == current && _phase.value != ScreenPhase.Closed) { "closed" }
        session = id
        peer.awaitSet { o -> setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, answer)) }

        // Fresh credentials five minutes before they expire (an idle screen too).
        val renewIn = (connection.expiresAt - System.currentTimeMillis() - 300_000).coerceAtLeast(1_000)
        jobs = listOf(
            scope.launch {
                delay(renewIn)
                if (generation == current && _phase.value != ScreenPhase.Closed) recover()
            },
            scope.launch {
                delay(30_000)
                if (generation == current && _phase.value != ScreenPhase.Live) {
                    close()
                    _phase.value = ScreenPhase.Failed("Couldn't connect to the remote screen. Check the computer's connection and try again.")
                }
            },
        )
    }

    private fun recover() {
        _phase.value = ScreenPhase.Reconnecting
        scope.launch {
            repeat(3) { attempt ->
                try {
                    connect()
                    return@launch
                } catch (e: Exception) {
                    if (_phase.value == ScreenPhase.Closed) return@launch
                    if (attempt == 2) {
                        close()
                        _phase.value = ScreenPhase.Failed(e.message ?: "The remote screen disconnected.")
                        return@launch
                    }
                    delay(2_000L * (attempt + 1))
                }
            }
        }
    }

    private fun iceChanged(state: PeerConnection.IceConnectionState, gen: Int) {
        if (gen != generation || _phase.value == ScreenPhase.Closed) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                _phase.value = ScreenPhase.Live
                lastError.value = null
            }
            PeerConnection.IceConnectionState.FAILED -> recover()
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                // Often recovers by itself (Wi-Fi ↔ cellular); reconnect if it doesn't.
                _phase.value = ScreenPhase.Reconnecting
                scope.launch {
                    delay(2_000)
                    if (gen == generation && _phase.value == ScreenPhase.Reconnecting) recover()
                }
            }
            else -> Unit
        }
    }

    fun close() {
        _phase.value = ScreenPhase.Closed
        session?.let { id -> scope.launch { withContext(NonCancellable) { runCatching { client.screenClose(id) } } } }
        session = null
        teardown()
    }

    /** Frees the native video resources; call once when the viewer goes away. */
    fun release() {
        close()
        egl.release()
    }

    private fun teardown() {
        generation++
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        gathered?.complete(Unit)
        reliable?.dispose()
        fast?.dispose()
        pc?.dispose()
        reliable = null
        fast = null
        pc = null
        _track.value = null
    }

    // Input: display points (ScreenInput builds the events).

    fun send(event: JsonObject) {
        val move = event["type"]?.jsonPrimitive?.content == "move"
        val channel = (if (move && fast?.state() == DataChannel.State.OPEN) fast else reliable) ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray()), false))
    }

    private inner class Incoming : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val msg = runCatching { Json.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: return
            when (msg["type"]?.jsonPrimitive?.content) {
                "clipboard" -> remoteClipboard.value = msg["text"]?.jsonPrimitive?.content
                "error" -> lastError.value = msg["message"]?.jsonPrimitive?.content
            }
        }
    }

    /** WebRTC callbacks arrive on its signaling thread; state goes through flows. */
    private inner class Events(private val gen: Int) : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            scope.launch { iceChanged(state, gen) }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE && gen == generation) gathered?.complete(Unit)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }
}

private suspend fun PeerConnection.await(block: PeerConnection.(SdpObserver) -> Unit): SessionDescription = suspendCoroutine { cont ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
        override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "Couldn't start the video connection."))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    })
}

private suspend fun PeerConnection.awaitSet(block: PeerConnection.(SdpObserver) -> Unit): Unit = suspendCoroutine { cont ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = cont.resume(Unit)
        override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "The screen helper's answer didn't fit."))
    })
}
