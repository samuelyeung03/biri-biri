package com.bitchat.android.rtc

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.LatencyLog
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
    private val codec: String = AppConstants.VideoCall.DEFAULT_CODEC,
    private val bitrateBps: Int = AppConstants.VideoCall.DEFAULT_BITRATE_BPS,
    private val encoderFactory: () -> VideoEncoder = { VideoEncoder(codecType = codec, width = width, height = height, bitRate = bitrateBps) },
    private val decoder: VideoDecoder = H264VideoDecoder(width = width, height = height),
    private var meshService: BluetoothMeshService? = null,
    private var onFrameDecoded: ((DecodedVideoFrame) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VideoStream"
    }

    private var currentWidth: Int = width
    private var currentHeight: Int = height
    private var currentCodec: String = codec
    private var currentBitrateBps: Int = bitrateBps

    private var lastContext: Context? = null
    private var lastLifecycleOwner: LifecycleOwner? = null

    private var encoder: VideoEncoder? = null
    private var seqNumber: Int = 0

    // Sender-only frame correlation ID.
    private var frameId: Long = 0L

    // Camera is owned by VideoStream (capture -> encode -> send)
    private var camera: Camera? = null
    private var cameraRecipientId: String? = null

    private var sentCodecConfigForRecipient: String? = null

    private var negotiatedParams: RTCSync.VideoParams? = null

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
        lastContext = context
        lastLifecycleOwner = lifecycleOwner

        val cam = camera ?: Camera(
            context = context,
            width = currentWidth,
            height = currentHeight,
            targetFps = AppConstants.VideoCall.DEFAULT_FRAME_RATE
        ).also { camera = it }

        cam.startCamera(lifecycleOwner) { imageProxy ->
            try {
                // Camera frame boundary (earliest point in this pipeline).
                val fid = frameId++
                LatencyLog.d(
                    ev = "cam_frame",
                    "fid" to fid,
                    "imgTsNs" to imageProxy.imageInfo.timestamp,
                    "w" to imageProxy.width,
                    "h" to imageProxy.height,
                    "rid" to recipientId
                )

                sendFrame(imageProxy, cameraRecipientId, fid)
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
        // Backwards-compatible entry point.
        sendFrame(image, recipientId, fid = null)
    }

    private fun sendFrame(image: ImageProxy, recipientId: String?, fid: Long?) {
        if (recipientId == null) {
            LatencyLog.d("cam_drop", "fid" to fid, "reason" to "recipient_null")
            Log.w(TAG, "sendFrame: recipientId is null; dropping video frame")
            image.close()
            return
        }

        val enc = encoder ?: run {
            sentCodecConfigForRecipient = null
            if (negotiatedParams == null) {
                encoderFactory().also { encoder = it }
            } else {
                VideoEncoder(
                    codecType = currentCodec,
                    width = currentWidth,
                    height = currentHeight,
                    bitRate = currentBitrateBps
                ).also { encoder = it }
            }
        }

        // Encode boundary (start).
        LatencyLog.d("enc_start", "fid" to fid, "rid" to recipientId)

        enc.encode(
            image,
            onEncoded = { encoded ->
                val seq = seqNumber and 0xFFFF

                // Encode boundary (have access unit bytes).
                LatencyLog.d("enc_out", "fid" to fid, "seq" to seq, "bytes" to encoded.size)

                val payload = ByteArray(encoded.size + 2)
                payload[0] = ((seq shr 8) and 0xFF).toByte()
                payload[1] = (seq and 0xFF).toByte()
                System.arraycopy(encoded, 0, payload, 2, encoded.size)

                // Payload constructed.
                LatencyLog.d("video_payload", "fid" to fid, "seq" to seq, "bytes" to payload.size)

                seqNumber = (seq + 1) and 0xFFFF

                val ms = meshService
                if (ms == null) {
                    LatencyLog.d("mesh_drop", "fid" to fid, "seq" to seq, "reason" to "mesh_null")
                    Log.w(TAG, "No BluetoothMeshService attached — call attachMeshService(meshService) before sending")
                    return@encode
                }

                LatencyLog.d("mesh_send_call", "fid" to fid, "seq" to seq, "rid" to recipientId)
                try {
                    ms.sendVideo(recipientId, payload)
                    LatencyLog.d("mesh_send_return", "fid" to fid, "seq" to seq)
                } catch (e: Exception) {
                    LatencyLog.d("mesh_send_err", "fid" to fid, "seq" to seq, "err" to (e.message ?: ""))
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
                    LatencyLog.d("mesh_drop", "fid" to fid, "seq" to 0, "reason" to "mesh_null")
                    Log.w(TAG, "No BluetoothMeshService attached — cannot send codec config")
                    return@encode
                }

                LatencyLog.d("codec_cfg_send", "fid" to fid, "rid" to recipientId, "bytes" to configBytes.size)

                try {
                    ms.sendVideo(recipientId, payload)
                } catch (e: Exception) {
                    LatencyLog.d("codec_cfg_err", "fid" to fid, "err" to (e.message ?: ""))
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

        // Receiver boundary: got a VIDEO packet routed to video engine.
        LatencyLog.d(
            "rx_video",
            "seq" to seq,
            "bytes" to data.size,
            "from" to packet.senderID.toHexString(),
            "pktTsMs" to packet.timestamp.toLong()
        )

        if (data.isEmpty()) {
            Log.w(TAG, "Received empty encoded video data seq=$seq")
            return
        }

        // seq=0 is reserved for codec config (VPS/SPS/PPS or SPS/PPS)
        if (seq == 0 && decoder is MediaCodecVideoDecoder) {
            LatencyLog.d("rx_codec_cfg", "seq" to 0, "bytes" to data.size, "from" to packet.senderID.toHexString())
            Log.d(TAG, "Received VIDEO codec config seq=0 bytes=${data.size} from ${packet.senderID.toHexString()}")
            decoder.setCodecConfig(data)
            return
        }

        LatencyLog.d("dec_in", "seq" to seq, "bytes" to data.size)
        decoder.decode(
            data,
            presentationTimeUs = packet.timestamp.toLong() * 1000L,
            onFrameDecoded = { frame ->
                // Render boundary: decoded frame delivered to UI callback.
                LatencyLog.d("render_cb", "seq" to seq, "w" to frame.width, "h" to frame.height)
                onFrameDecoded?.invoke(frame)
            }
        )
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

    fun updateSendConfig(params: RTCSync.VideoParams) {
        val prevWidth = currentWidth
        val prevHeight = currentHeight

        negotiatedParams = params
        currentWidth = params.width
        currentHeight = params.height
        currentCodec = params.codec
        currentBitrateBps = params.bitrateBps

        // Recreate encoder with new params next frame.
        runCatching { encoder?.release() }
        encoder = null
        sentCodecConfigForRecipient = null

        // If capture is running, restart camera to apply new resolution.
        if (camera != null && (prevWidth != currentWidth || prevHeight != currentHeight)) {
            val ctx = lastContext
            val owner = lastLifecycleOwner
            val recipient = cameraRecipientId
            stopCamera()
            if (ctx != null && owner != null) {
                startCamera(context = ctx, lifecycleOwner = owner, recipientId = recipient)
            }
        }
    }
}
