package com.bitchat.android.rtc

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.toHexString

/**
 * Video stream implementation:
 * - capture frames via [Camera]
 * - encode via [VideoEncoder]
 * - send via [BluetoothMeshService]
 * - receive via [handleIncomingVideo] and decode via [VideoDecoder]
 *
 * This class is intentionally UI-agnostic: decoded frames are provided via callback.
 */
class VideoStream(
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT,
    private val encoderFactory: () -> VideoEncoder = { VideoEncoder(width = width, height = height) },
    private val decoder: VideoDecoder = H264VideoDecoder(width = width, height = height),
    private var meshService: BluetoothMeshService? = null,
    private var onFrameDecoded: ((DecodedVideoFrame) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VideoStream"
    }

    private var encoder: VideoEncoder? = null
    private var seqNumber: Int = 0

    // Camera is owned by VideoStream (capture -> encode -> send)
    private var camera: Camera? = null
    private var cameraRecipientId: String? = null

    private var sentCodecConfigForRecipient: String? = null

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshService = meshService
    }

    fun setOnFrameDecodedCallback(cb: ((DecodedVideoFrame) -> Unit)?) {
        onFrameDecoded = cb
    }

    /**
     * Start camera capture and feed frames into this VideoStream.
     *
     * No UI/use-case for preview is created here. Rendering will be wired later.
     */
    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        recipientId: String?
    ) {
        cameraRecipientId = recipientId

        val cam = camera ?: Camera(
            context = context,
            width = width,
            height = height,
            targetFps = AppConstants.VideoCall.DEFAULT_FRAME_RATE
        ).also { camera = it }

        cam.startCamera(lifecycleOwner) { imageProxy ->
            try {
                sendFrame(imageProxy, cameraRecipientId)
            } catch (e: Exception) {
                // If something throws before VideoEncoder closes it, close here.
                runCatching { imageProxy.close() }
                Log.e(TAG, "Failed to process camera frame: ${e.message}")
            }
        }

        Log.d(TAG, "startCamera: recipientId=$recipientId")
    }

    fun stopCamera() {
        try {
            camera?.stopCamera()
        } catch (_: Exception) {
        }
        camera = null
        cameraRecipientId = null
    }

    /**
     * Encodes and sends the given [image]. Can be called from CameraX analyzer.
     */
    fun sendFrame(image: ImageProxy, recipientId: String?) {
        if (recipientId == null) {
            Log.w(TAG, "sendFrame: recipientId is null; dropping video frame")
            image.close()
            return
        }

        val enc = encoder ?: encoderFactory.invoke().also { encoder = it }

        enc.encode(
            image,
            onEncoded = { encoded ->
                val seq = seqNumber and 0xFFFF

                val payload = ByteArray(encoded.size + 2)
                payload[0] = ((seq shr 8) and 0xFF).toByte()
                payload[1] = (seq and 0xFF).toByte()
                System.arraycopy(encoded, 0, payload, 2, encoded.size)

                seqNumber = (seq + 1) and 0xFFFF

                val ms = meshService
                if (ms == null) {
                    Log.w(TAG, "No BluetoothMeshService attached — call attachMeshService(meshService) before sending")
                    return@encode
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "sendFrame: seq=$seq encodedBytes=${encoded.size} payloadBytes=${payload.size} -> $recipientId")
                }

                try {
                    ms.sendVideo(recipientId, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send video frame seq=$seq: ${e.message}")
                }
            },
            onCodecConfig = { configBytes ->
                if (sentCodecConfigForRecipient == recipientId) return@encode
                sentCodecConfigForRecipient = recipientId

                val payload = ByteArray(configBytes.size + 2)
                payload[0] = 0
                payload[1] = 0
                System.arraycopy(configBytes, 0, payload, 2, configBytes.size)

                val ms = meshService
                if (ms == null) {
                    Log.w(TAG, "No BluetoothMeshService attached — cannot send codec config")
                    return@encode
                }

                Log.d(TAG, "Sending VIDEO codec config bytes=${configBytes.size} to $recipientId")

                try {
                    ms.sendVideo(recipientId, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send video codec config: ${e.message}")
                }
            }
        )
    }

    fun handleIncomingVideo(packet: BitchatPacket) {
        val payload = packet.payload
        if (payload.size < 2) {
            Log.w(TAG, "Received video payload too short, dropping")
            return
        }

        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        if (data.isEmpty()) {
            Log.w(TAG, "Received empty encoded video data seq=$seq")
            return
        }

        // Send VIDEO_ACK disbaled for now
        // meshService?.sendVideoAck(packet.senderID.toHexString(), seq)

        // seq=0 is reserved for codec config (VPS/SPS/PPS or SPS/PPS)
        if (seq == 0 && decoder is MediaCodecVideoDecoder) {
            Log.d(TAG, "Received VIDEO codec config seq=0 bytes=${data.size} from ${packet.senderID.toHexString()}")
            decoder.setCodecConfig(data)
            return
        }

        decoder.decode(data, presentationTimeUs = packet.timestamp.toLong() * 1000L, onFrameDecoded = onFrameDecoded)
    }

    fun handleVideoAck(packet: BitchatPacket) {
        val payload = packet.payload
        if (payload.size < 2) {
            Log.w(TAG, "Received video ack payload too short, dropping")
            return
        }
        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        Log.d(TAG, "Received VIDEO_ACK for seq=$seq from ${packet.senderID.toHexString()}")
    }

    fun stop() {
        // Stop the capture first to avoid analyzer threads feeding frames during teardown
        stopCamera()

        sentCodecConfigForRecipient = null

        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            decoder.release()
        } catch (_: Exception) {
        }
    }
}
