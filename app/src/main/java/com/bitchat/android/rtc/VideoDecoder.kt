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
 * MediaCodec-backed decoder (AVC/HEVC depending on [codecType]).
 */
class MediaCodecVideoDecoder(
    private val codecType: String = AppConstants.VideoCall.DEFAULT_CODEC,
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT
) : VideoDecoder {

    private var codec: MediaCodec? = null
    private val timeoutUs: Long = 10_000

    private var cachedCodecConfig: ByteArray? = null
    private var hasProducedOutput: Boolean = false

    init {
        setupCodec()
    }

    private fun setupCodec() {
        try {
            val format = MediaFormat.createVideoFormat(codecType, width, height)
            codec = MediaCodec.createDecoderByType(codecType)
            codec?.configure(format, null, null, 0)
            codec?.start()
            Log.d(TAG, "Decoder started: $codecType ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup decoder ($codecType): ${e.message}", e)
            codec = null
        }
    }

    /**
     * Cache codec config (VPS/SPS/PPS for HEVC, SPS/PPS for AVC).
     */
    fun setCodecConfig(config: ByteArray) {
        cachedCodecConfig = config
        Log.d(TAG, "Cached codec config bytes=${config.size} for codec=$codecType")
    }

    override fun decode(
        encoded: ByteArray,
        presentationTimeUs: Long,
        onFrameDecoded: ((DecodedVideoFrame) -> Unit)?
    ) {
        val c = codec ?: return
        if (encoded.isEmpty()) return

        try {
            // Heuristic: seq=0 packets are sent as codec config from the sender (see VideoStream).
            // VideoStream strips the 2-byte seq header before calling decoder, so we detect config
            // by checking for common Annex-B start codes + small size, OR by letting caller explicitly
            // call setCodecConfig. Here we accept both:
            val looksLikeConfig = !hasProducedOutput && (encoded.size < 512) && (
                (encoded.size >= 4 && encoded[0] == 0.toByte() && encoded[1] == 0.toByte() && encoded[2] == 0.toByte() && encoded[3] == 1.toByte()) ||
                    (encoded.size >= 3 && encoded[0] == 0.toByte() && encoded[1] == 0.toByte() && encoded[2] == 1.toByte())
                )

            if (looksLikeConfig) {
                // Cache and do not queue immediately; we'll prepend to the next frame.
                cachedCodecConfig = encoded
                Log.d(TAG, "Received in-band codec config bytes=${encoded.size} (codec=$codecType)")
                return
            }

            // Some devices require VPS/SPS/PPS (or SPS/PPS) to be prepended in-band.
            val config = cachedCodecConfig
            val accessUnit: ByteArray = if (!hasProducedOutput && config != null) {
                // Prepend once until decoder starts producing output.
                ByteArray(config.size + encoded.size).also { merged ->
                    System.arraycopy(config, 0, merged, 0, config.size)
                    System.arraycopy(encoded, 0, merged, config.size, encoded.size)
                }
            } else {
                encoded
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "decode: codec=$codecType encodedBytes=${encoded.size} queuedBytes=${accessUnit.size} ptsUs=$presentationTimeUs")
            }

            val inputIndex = c.dequeueInputBuffer(timeoutUs)
            if (inputIndex >= 0) {
                val inBuf = c.getInputBuffer(inputIndex)
                inBuf?.clear()
                inBuf?.put(accessUnit)
                c.queueInputBuffer(inputIndex, 0, accessUnit.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outIndex >= 0) {
                val outBuf = c.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)

                    val outData = ByteArray(bufferInfo.size)
                    outBuf.get(outData)

                    hasProducedOutput = true

                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "decoded output bytes=${outData.size} flags=${bufferInfo.flags} ptsUs=${bufferInfo.presentationTimeUs}")
                    }

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
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error ($codecType): ${e.message}", e)
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
        private const val TAG = "MediaCodecVideoDecoder"
    }
}

/**
 * Backwards-compatible type alias for old name.
 * Existing references to H264VideoDecoder will now resolve to MediaCodecVideoDecoder.
 */
typealias H264VideoDecoder = MediaCodecVideoDecoder
