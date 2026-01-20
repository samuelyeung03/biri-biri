package com.bitchat.android.rtc

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants

/**
 * Real-time voice/video call manager.
 *
 * This class coordinates [VoiceStream] and [VideoStream] which own the actual media pipelines.
 */
class RTCConnectionManager(
    private val context: Context? = null,
    private val sampleRate: Int = AppConstants.VoiceCall.DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = AppConstants.VoiceCall.DEFAULT_CHANNEL_COUNT,
    private val bitrate: Int = AppConstants.VoiceCall.DEFAULT_BITRATE_BPS,
    private val frameSamples: Int = AppConstants.VoiceCall.FRAME_SAMPLES_60_MS,
    private val encoderFactory: () -> AudioEncoder = { OpusEncoder(sampleRate, channels, bitrate) },
    private val inputDeviceFactory: () -> AudioInputDevice = { AudioInputDevice(sampleRate, channels, frameSamples) },
    private val audioOutputDevice: AudioOutputDevice = AudioOutputDevice(sampleRate, channels),
    private val audioDecoder: AudioDecoder = OpusDecoder(sampleRate, channels),
    private val videoWidth: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val videoHeight: Int = AppConstants.VideoCall.DEFAULT_HEIGHT,
    private val videoEncoderFactory: () -> VideoEncoder = { VideoEncoder(width = videoWidth, height = videoHeight) },
    private val videoDecoder: VideoDecoder = H264VideoDecoder(width = videoWidth, height = videoHeight)
) {
    companion object {
        private const val TAG = "RTCConnectionManager"
    }

    // --- Call state tracking (single source of truth) ---
    private data class CallSession(
        val peerId: String,
        var voiceActive: Boolean = false,
        var videoActive: Boolean = false
    )

    private val sessionsByPeer = mutableMapOf<String, CallSession>()

    private fun session(peerId: String): CallSession {
        return sessionsByPeer.getOrPut(peerId) { CallSession(peerId) }
    }

    /**
     * True if we have an active voice call pipeline for this peer.
     */
    fun isVoiceCallActive(peerId: String): Boolean = sessionsByPeer[peerId]?.voiceActive == true

    /**
     * True if we have an active video call pipeline for this peer.
     */
    fun isVideoCallActive(peerId: String): Boolean = sessionsByPeer[peerId]?.videoActive == true

    /**
     * True if we have any active media pipeline for this peer.
     */
    fun isAnyCallActive(peerId: String): Boolean = isVoiceCallActive(peerId) || isVideoCallActive(peerId)

    fun getActiveVoicePeers(): Set<String> = sessionsByPeer.values.filter { it.voiceActive }.map { it.peerId }.toSet()
    fun getActiveVideoPeers(): Set<String> = sessionsByPeer.values.filter { it.videoActive }.map { it.peerId }.toSet()

    /**
     * Stop an active voice call with a specific peer.
     */
    fun stopVoiceCallWithPeer(peerId: String) {
        if (!isVoiceCallActive(peerId)) return
        // This implementation still uses a single VoiceStream instance.
        // If you later support multiple concurrent calls, this becomes per-peer.
        runCatching { voiceStream.stop() }
        sessionsByPeer[peerId]?.voiceActive = false
        cleanupSessionIfIdle(peerId)
    }

    /**
     * Stop an active video call with a specific peer.
     */
    fun stopVideoCallWithPeer(peerId: String) {
        if (!isVideoCallActive(peerId)) return
        runCatching { videoStream.stop() }
        sessionsByPeer[peerId]?.videoActive = false
        cleanupSessionIfIdle(peerId)
    }

    private fun cleanupSessionIfIdle(peerId: String) {
        val s = sessionsByPeer[peerId] ?: return
        if (!s.voiceActive && !s.videoActive) {
            sessionsByPeer.remove(peerId)
        }
    }

    /**
     * Optional callback for surfacing call-control events (invite/accept/hangup) without UI wiring.
     */
    var onCallControlEvent: ((CallControlEvent) -> Unit)? = null

    sealed class CallControlEvent {
        data class Invite(
            val fromPeerId: String,
            val callType: RTCSync.CallType,
            val mode: RTCSync.Mode,
            val videoParams: RTCSync.VideoParams? = null
        ) : CallControlEvent()

        data class Accept(
            val fromPeerId: String,
            val callType: RTCSync.CallType,
            val mode: RTCSync.Mode,
            val videoParams: RTCSync.VideoParams? = null
        ) : CallControlEvent()

        data class Hangup(val fromPeerId: String, val callType: RTCSync.CallType) : CallControlEvent()
        data class Invalid(val fromPeerId: String) : CallControlEvent()
    }

    private var meshServiceRef: BluetoothMeshService? = null

    private val voiceStream: VoiceStream = VoiceStream(
        context = context,
        sampleRate = sampleRate,
        channels = channels,
        bitrate = bitrate,
        frameSamples = frameSamples,
        encoderFactory = encoderFactory,
        inputDeviceFactory = inputDeviceFactory,
        audioOutputDevice = audioOutputDevice,
        audioDecoder = audioDecoder,
        meshService = null
    )

    private val videoStream: VideoStream = VideoStream(
        width = videoWidth,
        height = videoHeight,
        encoderFactory = videoEncoderFactory,
        decoder = videoDecoder,
        meshService = null
    )

    // Convenience constructor that takes BluetoothMeshService and uses it to stream audio/video
    constructor(
        context: Context? = null,
        meshService: BluetoothMeshService,
        sampleRate: Int = AppConstants.VoiceCall.DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = AppConstants.VoiceCall.DEFAULT_CHANNEL_COUNT,
        bitrate: Int = AppConstants.VoiceCall.MIN_BITRATE_BPS
    ) : this(context, sampleRate, channels, bitrate) {
        this.meshServiceRef = meshService
        this.voiceStream.attachMeshService(meshService)
        this.videoStream.attachMeshService(meshService)
    }

    /**
     * Handle incoming RTC_SYNC control packet.
     *
     * This used to live in BluetoothMeshService, but is now centralized here.
     */
    fun handleRTCSync(packet: BitchatPacket, fromPeerId: String) {
        val sync = RTCSync.decode(packet.payload)
        if (sync == null) {
            Log.w(TAG, "Received RTC_SYNC with empty/invalid payload from $fromPeerId")
            onCallControlEvent?.invoke(CallControlEvent.Invalid(fromPeerId))
            return
        }

        when (sync.syncType) {
            RTCSync.SyncType.INVITE -> {
                Log.d(TAG, "📨 Received RTC_INVITE from $fromPeerId callType=${sync.callType} mode=${sync.mode}")
                onCallControlEvent?.invoke(CallControlEvent.Invite(fromPeerId, sync.callType, sync.mode, sync.videoParams))

                when (sync.callType) {
                    RTCSync.CallType.VOICE -> {
                        if (sync.mode == RTCSync.Mode.TWO_WAY) {
                            startCall(senderId = meshServiceRef?.myPeerID ?: "", recipientId = fromPeerId)
                            // Let the inviter know we're ready
                            meshServiceRef?.sendRTCSync(
                                recipientPeerID = fromPeerId,
                                syncType = RTCSync.SyncType.ACCEPT,
                                callType = sync.callType,
                                mode = sync.mode
                            )
                        } else {
                            Log.d(TAG, "Received one-way RTC_INVITE from $fromPeerId (no auto-answer)")
                        }
                    }

                    RTCSync.CallType.VIDEO -> {
                        // Persist requested params (if any) as the negotiated params for now.
                        sync.videoParams?.let { negotiatedVideoParamsByPeer[fromPeerId] = it }

                        Log.w(TAG, "Received VIDEO invite from $fromPeerId; sending ACCEPT and deferring camera start to UI")

                        meshServiceRef?.sendRTCSync(
                            recipientPeerID = fromPeerId,
                            syncType = RTCSync.SyncType.ACCEPT,
                            callType = sync.callType,
                            mode = sync.mode,
                            videoParams = sync.videoParams
                        )
                        session(fromPeerId).videoActive = true
                    }
                }
            }

            RTCSync.SyncType.ACCEPT -> {
                Log.d(TAG, "✅ Received RTC_ACCEPT from $fromPeerId callType=${sync.callType} mode=${sync.mode}")
                if (sync.callType == RTCSync.CallType.VIDEO) {
                    sync.videoParams?.let { negotiatedVideoParamsByPeer[fromPeerId] = it }
                }
                onCallControlEvent?.invoke(CallControlEvent.Accept(fromPeerId, sync.callType, sync.mode, sync.videoParams))

                if (sync.callType == RTCSync.CallType.VOICE) {
                    session(fromPeerId).voiceActive = true
                }
                if (sync.callType == RTCSync.CallType.VIDEO) {
                    session(fromPeerId).videoActive = true
                }
            }

            RTCSync.SyncType.HANGUP -> {
                Log.d(TAG, "🛑 Received RTC_HANGUP from $fromPeerId")
                when (sync.callType) {
                    RTCSync.CallType.VOICE -> {
                        stopVoiceCallWithPeer(fromPeerId)
                    }
                    RTCSync.CallType.VIDEO -> {
                        stopVideoCallWithPeer(fromPeerId)
                    }
                }
                onCallControlEvent?.invoke(CallControlEvent.Hangup(fromPeerId, sync.callType))
                negotiatedVideoParamsByPeer.remove(fromPeerId)
            }
        }
    }

    private val negotiatedVideoParamsByPeer = mutableMapOf<String, RTCSync.VideoParams>()

    fun getNegotiatedVideoParams(peerId: String): RTCSync.VideoParams? = negotiatedVideoParamsByPeer[peerId]

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshServiceRef = meshService
        voiceStream.attachMeshService(meshService)
        videoStream.attachMeshService(meshService)
    }

    fun startCall(senderId: String, recipientId: String?) {
        Log.d(TAG, " startCall: senderId=$senderId recipientId=$recipientId")
        if (recipientId != null) {
            session(recipientId).voiceActive = true
        }
        voiceStream.startSending(senderId = senderId, recipientId = recipientId)
    }

    fun stopCall() {
        Log.d(TAG, "stopCall: stopping voice/video streams")
        voiceStream.stop()
        videoStream.stop()
        sessionsByPeer.clear()
        negotiatedVideoParamsByPeer.clear()
    }

    /**
     * Start video capture+send using CameraX inside [VideoStream].
     * (Still no UI rendering.)
     */
    fun startVideo(
        lifecycleOwner: LifecycleOwner,
        recipientId: String?,
        onDecodedFrame: ((DecodedVideoFrame) -> Unit)? = null
    ) {
        if (recipientId != null) {
            session(recipientId).videoActive = true
        }

        videoStream.setOnFrameDecodedCallback(onDecodedFrame)

        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "startVideo: context is null; cannot start camera")
            return
        }

        // Apply negotiated params to encoder if provided.
        if (recipientId != null) {
            negotiatedVideoParamsByPeer[recipientId]?.let { vp ->
                try { videoStream.updateSendConfig(vp) } catch (_: Exception) {}
            }
        }

        videoStream.startCamera(context = ctx, lifecycleOwner = lifecycleOwner, recipientId = recipientId)
        Log.d(TAG, "🎥 startVideo(camera): recipientId=$recipientId")
    }

    fun stopVideo() {
        videoStream.stop()
        // If called without a peer, clear all video sessions.
        sessionsByPeer.values.forEach { it.videoActive = false }
        // Remove idle.
        sessionsByPeer.keys.toList().forEach { cleanupSessionIfIdle(it) }
        negotiatedVideoParamsByPeer.clear()
    }

    /**
     * Low-level hook (optional): if you already have ImageProxy frames from elsewhere,
     * you can still feed them directly.
     */
    fun sendVideoFrame(image: ImageProxy, recipientId: String?) {
        videoStream.sendFrame(image, recipientId)
    }

    fun handleIncomingAudio(packet: BitchatPacket) {
        voiceStream.handleIncomingAudio(packet)
    }

    fun handleVoiceAck(packet: BitchatPacket) {
        voiceStream.handleVoiceAck(packet)
    }

    fun handleVideoAck(packet: BitchatPacket) {
        videoStream.handleVideoAck(packet)
    }

    fun handleIncomingVideo(packet: BitchatPacket) {
        // Preserve existing behavior for non-rendering path
        videoStream.handleIncomingVideo(packet)

        // Also feed the surface renderer if present.
        val payload = packet.payload
        if (payload.size < 2) return
        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)
        if (data.isEmpty()) return

        val surfaceDecoder = remoteSurfaceDecoder ?: return
        if (seq == 0) {
            surfaceDecoder.setCodecConfig(data)
            return
        }

        surfaceDecoder.decode(data, presentationTimeUs = packet.timestamp.toLong() * 1000L)
    }

    // Remote rendering
    private var remoteSurfaceDecoder: SurfaceVideoDecoder? = null
    private var remoteSurface: android.view.Surface? = null

    fun setRemoteVideoSurface(surface: android.view.Surface) {
        remoteSurface = surface
        // Recreate decoder to bind to this Surface.
        try {
            remoteSurfaceDecoder?.release()
        } catch (_: Exception) {
        }
        remoteSurfaceDecoder = SurfaceVideoDecoder(surface = surface)
        Log.d(TAG, "🎬 Remote video surface set; SurfaceVideoDecoder created")
    }

    fun clearRemoteVideoSurface() {
        remoteSurface = null
        try {
            remoteSurfaceDecoder?.release()
        } catch (_: Exception) {
        }
        remoteSurfaceDecoder = null
        Log.d(TAG, "🎬 Remote video surface cleared; SurfaceVideoDecoder released")
    }

    /**
     * Start an outgoing video call to [peerId].
     *
     * Option B: pass the [lifecycleOwner] explicitly rather than storing it.
     */
    fun startOutgoingVideoCall(
        peerId: String,
        lifecycleOwner: LifecycleOwner? = null,
        mode: RTCSync.Mode = RTCSync.Mode.TWO_WAY,
        videoParams: RTCSync.VideoParams? = null
    ) {
        session(peerId).videoActive = true

        // Persist desired params as negotiated starting point.
        if (videoParams != null) {
            negotiatedVideoParamsByPeer[peerId] = videoParams
        }

        val ms = meshServiceRef
        if (ms == null) {
            Log.w(TAG, "startOutgoingVideoCall: no mesh service attached")
            return
        }

        try {
            ms.sendRTCSync(
                recipientPeerID = peerId,
                syncType = RTCSync.SyncType.INVITE,
                callType = RTCSync.CallType.VIDEO,
                mode = mode,
                videoParams = videoParams
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send VIDEO INVITE to $peerId: ${e.message}")
        }

        Log.d(TAG, "startOutgoingVideoCall: invite sent; camera start deferred to ACCEPT event")

        @Suppress("UNUSED_VARIABLE")
        val _unused = lifecycleOwner
    }
}