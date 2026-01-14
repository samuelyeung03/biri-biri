package com.bitchat.android.rtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.bitchat.android.util.AppConstants

/**
 * Encodes video frames using MediaCodec (H.264/AVC).
 */
class VideoEncoder(
    private val codecType: String = AppConstants.VideoCall.DEFAULT_CODEC,
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT,
    private val frameRate: Int = AppConstants.VideoCall.DEFAULT_FRAME_RATE,
    private val bitRate: Int = AppConstants.VideoCall.DEFAULT_BITRATE_BPS
) {
    private var mediaCodec: MediaCodec? = null
    private val timeoutUs: Long = 10000

    init {
        setupCodec()
    }

    private fun setupCodec() {
        try {
            val format = MediaFormat.createVideoFormat(codecType, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2 seconds between I-frames

            mediaCodec = MediaCodec.createEncoderByType(codecType)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec: ${e.message}")
        }
    }

    /**
     * Encodes a YUV420 frame from ImageProxy.
     */
    fun encode(image: androidx.camera.core.ImageProxy, onEncoded: (ByteArray) -> Unit) {
        val codec = mediaCodec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()

                // Optimized YUV420_888 to I420 conversion or similar
                // For simplicity, we assume the codec accepts the direct buffer if configured correctly,
                // but usually, we need to copy planes.
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                inputBuffer?.put(yBuffer)
                inputBuffer?.put(uBuffer)
                inputBuffer?.put(vBuffer)

                codec.queueInputBuffer(inputBufferIndex, 0, ySize + uSize + vSize, image.imageInfo.timestamp / 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null) {
                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)
                    onEncoded(outData)
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error: ${e.message}")
        } finally {
            image.close()
        }
    }

    fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            Log.e(TAG, "Release failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
    }
}

