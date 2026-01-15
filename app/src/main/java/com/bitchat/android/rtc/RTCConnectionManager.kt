package com.bitchat.android.rtc

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants

/**
 * Real-time voice call manager.
 *
 * This class now coordinates a [VoiceStream] which owns the actual audio pipeline.
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
    private val audioDecoder: AudioDecoder = OpusDecoder(sampleRate, channels)
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

    // Convenience constructor that takes BluetoothMeshService and uses it to stream audio
    constructor(
        context: Context? = null,
        meshService: BluetoothMeshService,
        sampleRate: Int = AppConstants.VoiceCall.DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = AppConstants.VoiceCall.DEFAULT_CHANNEL_COUNT,
        bitrate: Int = AppConstants.VoiceCall.MIN_BITRATE_BPS
    ) : this(context, sampleRate, channels, bitrate) {
        this.meshServiceRef = meshService
        this.voiceStream.attachMeshService(meshService)
    }

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshServiceRef = meshService
        voiceStream.attachMeshService(meshService)
    }

    fun startCall(senderId: String, recipientId: String?) {
        Log.d(TAG, "📞 startCall: senderId=$senderId recipientId=$recipientId")
        voiceStream.startSending(senderId = senderId, recipientId = recipientId)
    }

    fun stopCall() {
        Log.d(TAG, "stopCall: stopping voice stream")
        voiceStream.stop()
    }

    fun handleIncomingAudio(packet: BitchatPacket) {
        voiceStream.handleIncomingAudio(packet)
    }

    fun handleVoiceAck(packet: BitchatPacket) {
        voiceStream.handleVoiceAck(packet)
    }
}