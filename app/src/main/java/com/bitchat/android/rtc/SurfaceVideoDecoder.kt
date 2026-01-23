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

    private var configuredWithCsd: Boolean = false

    // Debug counters/state
    private var inFrames: Long = 0
    private var droppedNoCodec: Long = 0
    private var dequeueInBusy: Long = 0
    private var outFrames: Long = 0
    private var lastLogMs: Long = 0
    private var lastOutputMs: Long = 0

    init {
        setup()
    }

    private fun setup() {
        try {
            configuredWithCsd = false
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
        configuredWithCsd = false
        Log.d(TAG, "Surface decoder cached codec config bytes=${config.size}")
    }

    fun decode(encoded: ByteArray, presentationTimeUs: Long) {
        val c = codec
        if (c == null) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "decode: codec not ready; dropping frame bytes=${encoded.size}")
            }
            return
        }
        if (encoded.isEmpty()) return

        inFrames++

        // NOTE: For HEVC on many devices, passing a big "config" blob in-band can stall decoding.
        // Prefer configuring MediaCodec with CSD (csd-0/1/2) once we have VPS/SPS/PPS.
        if (!configuredWithCsd) {
            val cfg = cachedCodecConfig
            if (cfg != null) {
                val csd = tryParseHevcCsd(cfg)
                if (csd != null) {
                    Log.d(TAG, "HEVC CSD parsed: vps=${csd.vps.size} sps=${csd.sps.size} pps=${csd.pps.size} (reconfiguring decoder)")
                    reconfigureWithCsd(csd)
                } else {
                    // If config looks like a full access unit / huge blob, don't blindly prepend it forever.
                    if (cfg.size > 2048) {
                        Log.w(TAG, "Cached codec config is large (${cfg.size} bytes) and not parseable as HEVC CSD; will NOT prepend it")
                        configuredWithCsd = true // prevents prepend path below
                    }
                }
            }
        }

        val config = cachedCodecConfig
        val shouldPrependConfig = !hasRenderedOutput && !configuredWithCsd && config != null

        // Prepend config until we render at least one frame (legacy path).
        if (!hasRenderedOutput && !configuredWithCsd && config == null) {
            droppedNoCodec++
            maybeLogStats(reason = "drop_no_codec", ptsUs = presentationTimeUs)
            return
        }

        val accessUnit: ByteArray = if (shouldPrependConfig) {
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
                if (inBuf == null) {
                    Log.w(TAG, "decode: input buffer null (idx=$inIndex)")
                } else {
                    inBuf.clear()
                    inBuf.put(accessUnit)
                    c.queueInputBuffer(inIndex, 0, accessUnit.size, presentationTimeUs, 0)
                }
            } else {
                dequeueInBusy++
                maybeLogStats(reason = "no_input_buffer", ptsUs = presentationTimeUs)
            }

            val info = MediaCodec.BufferInfo()
            val outIndex = c.dequeueOutputBuffer(info, timeoutUs)

            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = runCatching { c.outputFormat }.getOrNull()
                    Log.d(TAG, "Surface decoder output format changed: ${fmt ?: "(null)"}")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output right now.
                }
                else -> {
                    if (outIndex < 0) {
                        // Unknown negative status.
                        Log.w(TAG, "Surface decoder dequeueOutputBuffer returned status=$outIndex")
                    }
                }
            }

            var renderedThisCall = 0
            var idx = outIndex
            while (idx >= 0) {
                c.releaseOutputBuffer(idx, true)
                hasRenderedOutput = true
                outFrames++
                renderedThisCall++
                lastOutputMs = android.os.SystemClock.elapsedRealtime()
                idx = c.dequeueOutputBuffer(info, 0)
            }

            if (renderedThisCall == 0) {
                maybeLogStats(reason = "no_output", ptsUs = presentationTimeUs)
            } else {
                maybeLogStats(reason = "rendered", ptsUs = presentationTimeUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Surface decode error ($codecType): ${e.message}", e)
        }
    }

    private class HevcCsd(val vps: ByteArray, val sps: ByteArray, val pps: ByteArray)

    /**
     * Tries to parse VPS/SPS/PPS NAL units from Annex-B HEVC config bytes.
     * Returns null if we can't find all 3.
     */
    private fun tryParseHevcCsd(bytes: ByteArray): HevcCsd? {
        // Find all NAL units delimited by Annex-B start codes.
        val nals = splitAnnexBNals(bytes)
        if (nals.isEmpty()) return null

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {
            val nalType = hevcNalType(nal)
            when (nalType) {
                32 -> if (vps == null) vps = withStartCode(nal)
                33 -> if (sps == null) sps = withStartCode(nal)
                34 -> if (pps == null) pps = withStartCode(nal)
            }
            if (vps != null && sps != null && pps != null) break
        }

        val vv = vps ?: return null
        val ss = sps ?: return null
        val pp = pps ?: return null
        return HevcCsd(vv, ss, pp)
    }

    private fun reconfigureWithCsd(csd: HevcCsd) {
        // Recreate codec and configure with csd buffers.
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        try {
            val format = MediaFormat.createVideoFormat(codecType, width, height)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.vps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd.sps))
            format.setByteBuffer("csd-2", java.nio.ByteBuffer.wrap(csd.pps))

            codec = MediaCodec.createDecoderByType(codecType)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            configuredWithCsd = true
            // Once we configure with CSD, do not prepend config in-band.
            Log.d(TAG, "Surface decoder reconfigured with HEVC csd-0/1/2")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconfigure surface decoder with CSD: ${e.message}", e)
            // Fallback to original codec state.
            codec = null
            configuredWithCsd = true
        }
    }

    private fun splitAnnexBNals(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < bytes.size) {
            val sc3 = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()
            val sc4 = i + 4 <= bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
            if (sc4) {
                starts.add(i)
                i += 4
                continue
            }
            if (sc3) {
                starts.add(i)
                i += 3
                continue
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()

        val out = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val start = starts[idx]
            val next = if (idx + 1 < starts.size) starts[idx + 1] else bytes.size
            // Skip the start code bytes.
            val scLen = if (start + 3 < bytes.size && bytes[start] == 0.toByte() && bytes[start + 1] == 0.toByte() && bytes[start + 2] == 1.toByte()) 3 else 4
            val nalStart = start + scLen
            if (nalStart >= next) continue
            out.add(bytes.copyOfRange(nalStart, next))
        }
        return out
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        // Use 0x00000001 prefix.
        val out = ByteArray(nal.size + 4)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun hevcNalType(nal: ByteArray): Int {
        if (nal.isEmpty()) return -1
        // HEVC: nal_unit_type is bits 1..6 of first byte.
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private fun maybeLogStats(reason: String, ptsUs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Log at most once every ~2s to avoid logcat spam.
        if (now - lastLogMs < 2000) return
        lastLogMs = now

        val sinceLastOut = if (lastOutputMs == 0L) -1 else (now - lastOutputMs)

        Log.d(
            TAG,
            "stats[$reason]: inFrames=$inFrames outFrames=$outFrames hasRendered=$hasRenderedOutput " +
                "cachedConfig=${cachedCodecConfig?.size ?: 0} droppedNoCodec=$droppedNoCodec " +
                "noInput=$dequeueInBusy ptsUs=$ptsUs sinceLastOutMs=$sinceLastOut"
        )
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
