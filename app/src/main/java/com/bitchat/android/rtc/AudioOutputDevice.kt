package com.bitchat.android.rtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.bitchat.android.util.AppConstants
import kotlin.math.max

class AudioOutputDevice(
    private val sampleRate: Int = AppConstants.VoiceCall.DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = AppConstants.VoiceCall.DEFAULT_CHANNEL_COUNT
) {
    companion object {
        private const val TAG = "AudioOutputDevice"
        private const val LATENCY_TAG = "latency"
    }

    private var audioTrack: AudioTrack? = null

    private fun ensureAudioTrack(): AudioTrack? {
        if (audioTrack != null) return audioTrack
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferMs = AppConstants.VoiceCall.AUDIO_TRACK_BUFFER_MS
        val bytesForBuffer = (sampleRate * bufferMs * channels * 2) / 1000
        val bufferSize = max(minBuf, bytesForBuffer)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack?.play()
            Log.d(TAG, "AudioTrack started (sampleRate=$sampleRate channels=$channels bufferSize=$bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack: ${e.message}")
            audioTrack = null
        }
        return audioTrack
    }

    fun play(pcm: ShortArray, seq: Int) {
        val track = ensureAudioTrack() ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            try { track.play() } catch (e: Exception) { Log.w(TAG, "AudioTrack play failed: ${e.message}") }
        }
        Log.d(LATENCY_TAG, "▶️ Playing PCM for seq=$seq, size=${pcm.size}")
        val written = try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PCM: ${e.message}")
            -1
        }
        if (written in 0 until pcm.size) {
            Log.w(TAG, "Partial audio write: requested=${pcm.size} written=$written")
        } else if (written < 0) {
            Log.e(TAG, "AudioTrack write error: $written")
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}