package com.bitchat.android.rtc

import android.util.Log

/** Opus implementation of AudioEncoder backed by the native OpusWrapper. */
class OpusEncoder(
    sampleRate: Int,
    channels: Int,
    bitrate: Int
) : AudioEncoder {
    companion object {
        private const val TAG = "OpusEncoder"
    }

    private val encoderPtr: Long = OpusWrapper.createEncoder(sampleRate, channels, bitrate)

    override fun encode(pcm: ShortArray): ByteArray? {
        if (encoderPtr == 0L) return null
        return try {
            OpusWrapper.encode(encoderPtr, pcm)
        } catch (e: Exception) {
            Log.w(TAG, "Opus encode failed: ${e.message}")
            null
        }
    }

    override fun release() {
        if (encoderPtr != 0L) {
            OpusWrapper.destroyEncoder(encoderPtr)
        }
    }
}

