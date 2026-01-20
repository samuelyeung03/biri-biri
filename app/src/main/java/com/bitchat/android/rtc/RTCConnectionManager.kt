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

    /**
     * Optional callback for surfacing call-control events (invite/accept/hangup) without UI wiring.
     */
    var onCallControlEvent: ((CallControlEvent) -> Unit)? = null

    sealed class CallControlEvent {
        data class Invite(val fromPeerId: String, val callType: RTCSync.CallType, val mode: RTCSync.Mode) : CallControlEvent()
        data class Accept(val fromPeerId: String, val callType: RTCSync.CallType, val mode: RTCSync.Mode) : CallControlEvent()
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
                onCallControlEvent?.invoke(CallControlEvent.Invite(fromPeerId, sync.callType, sync.mode))

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
                        // VIDEO handshake rule (per desired behavior):
                        // - Always send ACCEPT on receiving an INVITE (both ONE_WAY and TWO_WAY)
                        // - UI decides when to start camera/streaming
                        //   * ONE_WAY: only inviter starts after receiving ACCEPT
                        //   * TWO_WAY: both sides start after ACCEPT boundary
                        Log.w(
                            TAG,
                            "Received VIDEO invite from $fromPeerId; sending ACCEPT and deferring camera start to UI"
                        )

                        meshServiceRef?.sendRTCSync(
                            recipientPeerID = fromPeerId,
                            syncType = RTCSync.SyncType.ACCEPT,
                            callType = sync.callType,
                            mode = sync.mode
                        )
                    }
                }
            }

            RTCSync.SyncType.ACCEPT -> {
                Log.d(TAG, "✅ Received RTC_ACCEPT from $fromPeerId callType=${sync.callType} mode=${sync.mode}")
                onCallControlEvent?.invoke(CallControlEvent.Accept(fromPeerId, sync.callType, sync.mode))
            }

            RTCSync.SyncType.HANGUP -> {
                Log.d(TAG, "🛑 Received RTC_HANGUP from $fromPeerId")
                when (sync.callType) {
                    RTCSync.CallType.VOICE -> stopCall()
                    RTCSync.CallType.VIDEO -> stopVideo()
                }
                onCallControlEvent?.invoke(CallControlEvent.Hangup(fromPeerId, sync.callType))
            }
        }
    }

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshServiceRef = meshService
        voiceStream.attachMeshService(meshService)
        videoStream.attachMeshService(meshService)
    }

    fun startCall(senderId: String, recipientId: String?) {
        Log.d(TAG, "📞 startCall: senderId=$senderId recipientId=$recipientId")
        voiceStream.startSending(senderId = senderId, recipientId = recipientId)
    }

    fun stopCall() {
        Log.d(TAG, "stopCall: stopping voice/video streams")
        voiceStream.stop()
        videoStream.stop()
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
        videoStream.setOnFrameDecodedCallback(onDecodedFrame)

        val ctx = context
        if (ctx == null) {
            Log.w(TAG, "startVideo: context is null; cannot start camera")
            return
        }

        videoStream.startCamera(context = ctx, lifecycleOwner = lifecycleOwner, recipientId = recipientId)
        Log.d(TAG, "🎥 startVideo(camera): recipientId=$recipientId")
    }

    fun stopVideo() {
        videoStream.stop()
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
        mode: RTCSync.Mode = RTCSync.Mode.TWO_WAY
    ) {
        val ms = meshServiceRef
        if (ms == null) {
            Log.w(TAG, "startOutgoingVideoCall: no mesh service attached")
            return
        }

        // Send invite first
        try {
            ms.sendRTCSync(
                recipientPeerID = peerId,
                syncType = RTCSync.SyncType.INVITE,
                callType = RTCSync.CallType.VIDEO,
                mode = mode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send VIDEO INVITE to $peerId: ${e.message}")
        }

        // IMPORTANT: Do NOT start camera here.
        // Camera start should be triggered after the ACCEPT boundary:
        // - ONE_WAY: inviter starts only after receiving ACCEPT.
        // - TWO_WAY: inviter starts after receiving ACCEPT; invitee starts after sending ACCEPT.
        Log.d(TAG, "startOutgoingVideoCall: invite sent; camera start deferred to ACCEPT event")

        // lifecycleOwner kept for API compatibility; unused intentionally.
        @Suppress("UNUSED_VARIABLE")
        val _unused = lifecycleOwner
    }
}