package com.bitchat.android.rtc

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.bitchat.android.util.AppConstants

/**
 * Decoder that renders directly to a Surface.
 *
 * This is the most reliable way to get visible output from MediaCodec:
 * many codecs/devices won't produce accessible YUV buffers unless configured
 * with a Surface.
 */
class SurfaceVideoDecoder(
    private val surface: Surface,
    private val codecType: String = AppConstants.VideoCall.DEFAULT_CODEC,
    private val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    private val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT
) {
    private var codec: MediaCodec? = null
    private val timeoutUs: Long = 10_000

    private var cachedCodecConfig: ByteArray? = null
    private var hasRenderedOutput: Boolean = false

    init {
        setup()
    }

    private fun setup() {
        try {
            val format = MediaFormat.createVideoFormat(codecType, width, height)
            codec = MediaCodec.createDecoderByType(codecType)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            Log.d(TAG, "Surface decoder started: $codecType ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup surface decoder ($codecType): ${e.message}", e)
            codec = null
        }
    }

    fun setCodecConfig(config: ByteArray) {
        cachedCodecConfig = config
        Log.d(TAG, "Surface decoder cached codec config bytes=${config.size}")
    }

    fun decode(encoded: ByteArray, presentationTimeUs: Long) {
        val c = codec ?: return
        if (encoded.isEmpty()) return

        // Prepend config until we render at least one frame.
        val config = cachedCodecConfig
        val accessUnit: ByteArray = if (!hasRenderedOutput && config != null) {
            ByteArray(config.size + encoded.size).also { merged ->
                System.arraycopy(config, 0, merged, 0, config.size)
                System.arraycopy(encoded, 0, merged, config.size, encoded.size)
            }
        } else {
            encoded
        }

        try {
            val inIndex = c.dequeueInputBuffer(timeoutUs)
            if (inIndex >= 0) {
                val inBuf = c.getInputBuffer(inIndex)
                inBuf?.clear()
                inBuf?.put(accessUnit)
                c.queueInputBuffer(inIndex, 0, accessUnit.size, presentationTimeUs, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, timeoutUs)
            while (outIndex >= 0) {
                // Render to Surface
                c.releaseOutputBuffer(outIndex, true)
                hasRenderedOutput = true
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Surface decode error ($codecType): ${e.message}", e)
        }
    }

    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private companion object {
        private const val TAG = "SurfaceVideoDecoder"
    }
}

