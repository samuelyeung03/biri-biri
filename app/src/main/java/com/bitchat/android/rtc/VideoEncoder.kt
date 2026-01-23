package com.bitchat.android.rtc

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import com.bitchat.android.util.AppConstants

/**
 * Encodes video frames using MediaCodec (H.264/AVC).
 *
 * Note: CameraX provides YUV_420_888 with row/pixel strides; MediaCodec generally expects tightly-packed
 * I420/NV12/NV21 buffers. We convert to I420 (Y + U + V) before feeding the encoder.
 */
class VideoEncoder(
    private val codecType: String = AppConstants.VideoCall.DEFAULT_CODEC,
    val width: Int = AppConstants.VideoCall.DEFAULT_WIDTH,
    val height: Int = AppConstants.VideoCall.DEFAULT_HEIGHT,
    private val frameRate: Int = AppConstants.VideoCall.DEFAULT_FRAME_RATE,
    private val bitRate: Int = AppConstants.VideoCall.DEFAULT_BITRATE_BPS
) {
    private var mediaCodec: MediaCodec? = null
    private val timeoutUs: Long = 10000

    private var selectedColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    // Latest codec config (e.g., VPS/SPS/PPS for HEVC). Some decoders need this in-band.
    private var lastCodecConfig: ByteArray? = null

    // Scratch buffers reused across frames to avoid per-frame allocations.
    private var scratchY: ByteArray? = null
    private var scratchU: ByteArray? = null
    private var scratchV: ByteArray? = null

    init {
        setupCodec()
    }

    private fun setupCodec() {
        try {
            // Pick a concrete encoder and supported color format.
            val codecName = MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .firstOrNull { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(codecType, ignoreCase = true) }
                }
                ?.name

            if (codecName == null) {
                // Fallback: let framework pick.
                selectedColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                val format = MediaFormat.createVideoFormat(codecType, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

                mediaCodec = MediaCodec.createEncoderByType(codecType)
                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec?.start()
                Log.d(TAG, "MediaCodec encoder started: $codecType ${width}x${height} @${frameRate}fps (color=$selectedColorFormat)")
                return
            }

            val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first { it.name == codecName }
            val caps = info.getCapabilitiesForType(codecType)

            // Prefer flexible; otherwise prefer NV12/NV21 (SemiPlanar) then Planar.
            selectedColorFormat = when {
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }

            val format = MediaFormat.createVideoFormat(codecType, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // seconds

            mediaCodec = MediaCodec.createByCodecName(codecName)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            Log.d(TAG, "MediaCodec encoder started: $codecName ($codecType) ${width}x${height} @${frameRate}fps color=$selectedColorFormat")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaCodec: ${e.message}", e)
        }
    }

    /**
     * Encodes a YUV420 frame from ImageProxy.
     *
     * @param onEncoded called for regular encoded access units
     * @param onCodecConfig called when encoder outputs codec config (BUFFER_FLAG_CODEC_CONFIG)
     */
    fun encode(
        image: ImageProxy,
        onEncoded: (ByteArray) -> Unit,
        onCodecConfig: ((ByteArray) -> Unit)? = null
    ) {
        val codec = mediaCodec
        if (codec == null) {
            image.close()
            return
        }

        try {
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported ImageProxy format=${image.format}, expected YUV_420_888")
                return
            }

            val srcW = image.width
            val srcH = image.height

            // IMPORTANT: always feed MediaCodec frames that match encoder config (width,height).
            val dstW = width
            val dstH = height

            val expectedTightYuv420Dst = ((dstW * dstH * 3) + 1) / 2

            val needsScale = srcW != dstW || srcH != dstH

            val inputFrame: ByteArray = if (!needsScale) {
                // Sizes already match: keep existing conversion path.
                when (selectedColorFormat) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> yuv420888ToI420(image)
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> yuv420888ToNV12(image)
                    else -> yuv420888ToNV12(image)
                }
            } else {
                val (yDown, uDown, vDown) = downsampleToI420Planes(image, dstW, dstH)
                // Scaled path: build a frame that matches the encoder size.
                when (selectedColorFormat) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> buildI420Frame(dstW, dstH, yDown, uDown, vDown)
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> buildNV12Frame(dstW, dstH, yDown, uDown, vDown)
                    else -> buildNV12Frame(dstW, dstH, yDown, uDown, vDown)
                }
            }

            if (inputFrame.size != expectedTightYuv420Dst) {
                Log.e(
                    TAG,
                    "BUG: scaled/converted frame size mismatch. got=${inputFrame.size} expected=$expectedTightYuv420Dst " +
                        "src=${srcW}x${srcH} dst=${dstW}x${dstH} color=$selectedColorFormat"
                )
                return
            }

            // Diagnostic: show source vs encoder size.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "encode: src=${srcW}x${srcH} -> encoder=${dstW}x${dstH} bytes=${inputFrame.size}")
            }

            val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer == null) {
                    Log.w(TAG, "Encoder inputBuffer null")
                } else {
                    inputBuffer.clear()

                    if (inputBuffer.capacity() < inputFrame.size) {
                        Log.e(
                            TAG,
                            "Encoder input buffer too small (cap=${inputBuffer.capacity()} < frame=${inputFrame.size}). " +
                                "Dropping frame. color=$selectedColorFormat image=${srcW}x${srcH} encoder=${dstW}x${dstH}"
                        )
                        return
                    }

                    inputBuffer.put(inputFrame)
                    val ptsUs = image.imageInfo.timestamp / 1000L
                    codec.queueInputBuffer(inputBufferIndex, 0, inputFrame.size, ptsUs, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (isConfig) {
                        lastCodecConfig = outData
                        Log.d(TAG, "Encoder produced CODEC_CONFIG bytes=${outData.size} (codec=$codecType)")
                        onCodecConfig?.invoke(outData)
                    } else {
                        // High-signal debug: confirms encoder is producing bytes.
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Encoded frame bytes=${outData.size} flags=${bufferInfo.flags} ptsUs=${bufferInfo.presentationTimeUs}")
                        }

                        onEncoded(outData)
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    /**
     * Backwards-compatible encode signature.
     */
    fun encode(image: ImageProxy, onEncoded: (ByteArray) -> Unit) {
        encode(image, onEncoded, onCodecConfig = null)
    }

    /**
     * Convert CameraX ImageProxy in YUV_420_888 into tightly-packed I420:
     *  - Y plane: width*height
     *  - U plane: (width/2)*(height/2)
     *  - V plane: (width/2)*(height/2)
     */
    private fun yuv420888ToI420(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height

        // Tight size for I420 = width*height*3/2, rounded for odd products.
        val out = ByteArray(((w * h * 3) + 1) / 2)
        var outOffset = 0

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val chromaW = w / 2
        val chromaH = h / 2

        // Copy Y
        outOffset = copyPlane(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = w,
            height = h,
            output = out,
            outputOffset = outOffset
        )

        // Copy U then V as I420 expects.
        outOffset = copyPlane(
            buffer = uPlane.buffer,
            rowStride = uPlane.rowStride,
            pixelStride = uPlane.pixelStride,
            width = chromaW,
            height = chromaH,
            output = out,
            outputOffset = outOffset
        )

        copyPlane(
            buffer = vPlane.buffer,
            rowStride = vPlane.rowStride,
            pixelStride = vPlane.pixelStride,
            width = chromaW,
            height = chromaH,
            output = out,
            outputOffset = outOffset
        )

        return out
    }

    /**
     * Convert YUV_420_888 -> NV12 (Y + interleaved UV).
     * This is widely accepted by encoders when using COLOR_FormatYUV420SemiPlanar.
     */
    private fun yuv420888ToNV12(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height

        val out = ByteArray(((w * h * 3) + 1) / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val chromaW = w / 2
        val chromaH = h / 2

        // Y
        var outOffset = 0
        outOffset = copyPlane(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = w,
            height = h,
            output = out,
            outputOffset = outOffset
        )

        // UV interleaved
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }

        for (r in 0 until chromaH) {
            val uRowStart = r * uRowStride
            val vRowStart = r * vRowStride
            for (c in 0 until chromaW) {
                val uIndex = uRowStart + c * uPixelStride
                val vIndex = vRowStart + c * vPixelStride
                out[outOffset++] = uBuf.get(uIndex)
                out[outOffset++] = vBuf.get(vIndex)
            }
        }

        return out
    }

    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int {
        var outPos = outputOffset
        val row = ByteArray(rowStride)

        // We must not disturb the original buffer position for CameraX; duplicate.
        val dup = buffer.duplicate()
        dup.rewind()

        for (r in 0 until height) {
            val rowStart = r * rowStride
            dup.position(rowStart)
            dup.get(row, 0, minOf(rowStride, dup.remaining()))

            if (pixelStride == 1) {
                // Fast path: contiguous
                System.arraycopy(row, 0, output, outPos, width)
                outPos += width
            } else {
                // Strided: pick every pixelStride byte
                var col = 0
                var inPos = 0
                while (col < width) {
                    output[outPos++] = row[inPos]
                    inPos += pixelStride
                    col++
                }
            }
        }

        return outPos
    }

    private fun ensureScratch(dstW: Int, dstH: Int) {
        val ySize = dstW * dstH
        val cSize = (dstW / 2) * (dstH / 2)

        if (scratchY?.size != ySize) scratchY = ByteArray(ySize)
        if (scratchU?.size != cSize) scratchU = ByteArray(cSize)
        if (scratchV?.size != cSize) scratchV = ByteArray(cSize)
    }

    private fun clampIndex(v: Int, maxExclusive: Int): Int {
        return when {
            v < 0 -> 0
            v >= maxExclusive -> maxExclusive - 1
            else -> v
        }
    }

    /**
     * Downsample planes from [image] (YUV_420_888) into tightly-packed I420 planes at (dstW,dstH).
     * Uses nearest-neighbor scaling.
     */
    private fun downsampleToI420Planes(image: ImageProxy, dstW: Int, dstH: Int): Triple<ByteArray, ByteArray, ByteArray> {
        ensureScratch(dstW, dstH)
        val outY = scratchY!!
        val outU = scratchU!!
        val outV = scratchV!!

        val srcW = image.width
        val srcH = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        // ---- Y (full res) ----
        for (dy in 0 until dstH) {
            val sy = clampIndex(dy * srcH / dstH, srcH)
            val yRowStart = sy * yRowStride
            val outRowStart = dy * dstW
            for (dx in 0 until dstW) {
                val sx = clampIndex(dx * srcW / dstW, srcW)
                val inIndex = yRowStart + sx * yPixStride
                outY[outRowStart + dx] = yBuf.get(inIndex)
            }
        }

        // ---- UV (half res) ----
        val srcCW = srcW / 2
        val srcCH = srcH / 2
        val dstCW = dstW / 2
        val dstCH = dstH / 2

        // NOTE: We assume the common CameraX layout where planes[1] is U and planes[2] is V.
        // (If a device swaps them, colors will be wrong but size will still be correct.)
        for (dy in 0 until dstCH) {
            val sy = clampIndex(dy * srcCH / dstCH, srcCH)
            val uRowStart = sy * uRowStride
            val vRowStart = sy * vRowStride
            val outRowStart = dy * dstCW
            for (dx in 0 until dstCW) {
                val sx = clampIndex(dx * srcCW / dstCW, srcCW)
                val uIndex = uRowStart + sx * uPixStride
                val vIndex = vRowStart + sx * vPixStride
                outU[outRowStart + dx] = uBuf.get(uIndex)
                outV[outRowStart + dx] = vBuf.get(vIndex)
            }
        }

        return Triple(outY, outU, outV)
    }

    private fun buildI420Frame(dstW: Int, dstH: Int, y: ByteArray, u: ByteArray, v: ByteArray): ByteArray {
        val out = ByteArray(((dstW * dstH * 3) + 1) / 2)
        var off = 0
        System.arraycopy(y, 0, out, off, y.size)
        off += y.size
        System.arraycopy(u, 0, out, off, u.size)
        off += u.size
        System.arraycopy(v, 0, out, off, v.size)
        return out
    }

    private fun buildNV12Frame(dstW: Int, dstH: Int, y: ByteArray, u: ByteArray, v: ByteArray): ByteArray {
        val out = ByteArray(((dstW * dstH * 3) + 1) / 2)
        var off = 0
        System.arraycopy(y, 0, out, off, y.size)
        off += y.size

        // Interleave U,V
        for (i in u.indices) {
            out[off++] = u[i]
            out[off++] = v[i]
        }
        return out
    }

    fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            Log.e(TAG, "Release failed: ${e.message}", e)
        }
    }

    fun getCodecType(): String = codecType
    fun getBitrateBps(): Int = bitRate
    fun getFrameRate(): Int = frameRate

    companion object {
        private const val TAG = "VideoEncoder"
    }
}
