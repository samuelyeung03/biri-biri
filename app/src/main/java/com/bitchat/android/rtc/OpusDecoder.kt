package com.bitchat.android.rtc

import android.util.Log

/** Opus implementation of AudioDecoder backed by the native OpusWrapper. */
class OpusDecoder(
    private val sampleRate: Int,
    private val channels: Int
) : AudioDecoder {
    companion object {
        private const val TAG = "OpusDecoder"
    }

    override fun decode(data: ByteArray): ShortArray? {
        return try {
            OpusWrapper.decode(data, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "Opus decode failed: ${e.message}")
            null
        }
    }
}

