package com.bitchat.android.rtc

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.bitchat.android.util.AppConstants

/**
 * Decodes video frames using MediaCodec (H.264/AVC).
 *
 * This is the receiving-side counterpart to [VideoEncoder].
 *
 * Note: we don't render yet. The caller can provide a decode callback to receive
 * decoded frames, or leave it null to just validate/decode the stream.
 */
interface VideoDecoder {
    /**
     * Feed an encoded access unit (typically an H.264 NAL unit or AVCC frame).
     */
    fun decode(encoded: ByteArray, presentationTimeUs: Long = 0L, onFrameDecoded: ((DecodedVideoFrame) -> Unit)? = null)

    fun release()
}

/**
 * Simple container for decoded output.
 *
 * For now we expose the raw decoded bytes + format metadata. Rendering will be wired later.
 */
class DecodedVideoFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long
)

/**
 * MediaCodec-backed decoder.
 */
class H264VideoDecoder(
    private val codecType: String = AppConstants.VideoCall.DEFAULT_CODEC,
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT
) : VideoDecoder {

    private var codec: MediaCodec? = null
    private val timeoutUs: Long = 10_000

    init {
        setupCodec()
    }

    private fun setupCodec() {
        try {
            val format = MediaFormat.createVideoFormat(codecType, width, height)
            codec = MediaCodec.createDecoderByType(codecType)
            codec?.configure(format, null, null, 0)
            codec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup decoder: ${e.message}", e)
            codec = null
        }
    }

    override fun decode(
        encoded: ByteArray,
        presentationTimeUs: Long,
        onFrameDecoded: ((DecodedVideoFrame) -> Unit)?
    ) {
        val c = codec ?: return
        if (encoded.isEmpty()) return

        try {
            val inputIndex = c.dequeueInputBuffer(timeoutUs)
            if (inputIndex >= 0) {
                val inBuf = c.getInputBuffer(inputIndex)
                inBuf?.clear()
                inBuf?.put(encoded)
                c.queueInputBuffer(inputIndex, 0, encoded.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outIndex >= 0) {
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    val outData = ByteArray(bufferInfo.size)
                    outBuf.get(outData)
                    onFrameDecoded?.invoke(
                        DecodedVideoFrame(
                            data = outData,
                            width = width,
                            height = height,
                            presentationTimeUs = bufferInfo.presentationTimeUs
                        )
                    )
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}", e)
        }
    }

    override fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    private companion object {
        private const val TAG = "H264VideoDecoder"
    }
}
