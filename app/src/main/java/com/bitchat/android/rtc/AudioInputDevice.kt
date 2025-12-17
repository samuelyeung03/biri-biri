package com.bitchat.android.rtc

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.bitchat.android.util.AppConstants

/** Captures PCM16 audio frames from the mic. */
class AudioInputDevice(
    private val sampleRate: Int = AppConstants.Rtc.DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = AppConstants.Rtc.DEFAULT_CHANNEL_COUNT,
    private val frameSamples: Int = AppConstants.Rtc.FRAME_SAMPLES_60_MS
) {
    companion object {
        private const val TAG = "AudioInputDevice"
        private const val BYTES_PER_SAMPLE = 2
    }

    fun createRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(frameSamples * 2)

        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
    }

    fun readFrame(recorder: AudioRecord, buffer: ShortArray): Boolean {
        var read = 0
        while (read < frameSamples) {
            val r = recorder.read(buffer, read, frameSamples - read)
            if (r < 0) {
                Log.e(TAG, "AudioRecord read error: $r")
                return false
            }
            read += r
        }
        return true
    }
}
