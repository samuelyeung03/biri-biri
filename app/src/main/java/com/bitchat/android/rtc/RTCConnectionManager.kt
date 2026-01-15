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

    fun handleIncomingVideo(packet: BitchatPacket) {
        videoStream.handleIncomingVideo(packet)
    }

    fun handleVideoAck(packet: BitchatPacket) {
        videoStream.handleVideoAck(packet)
    }
}