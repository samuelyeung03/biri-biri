package com.bitchat.android.rtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.max

class AudioPlayer(private val sampleRate: Int = 48000, private val channels: Int = 1) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null

    // Add jitter buffer related fields
    // Use a regular class instead of data class to avoid array equals/hashCode warnings for ShortArray
    // timestampMs was unused — removed to silence warnings
    private class Packet(val pcm: ShortArray, val seq: Int)

    private val jitterBuffer = ArrayDeque<Packet>()
    private val bufferLock = Any()

    // Buffer targets in milliseconds
    private val bufferMsTarget = 120        // warm-up target before draining
    private val bufferMsMax = 240           // max buffer, drop oldest when exceeded
    private val bufferMsMin = 60            // min buffer to avoid underrun

    private val timeout = 3000
    private var timeoutCounter = 0

    @Volatile
    private var playbackThread: Thread? = null

    @Volatile
    private var running = false

    // maximum decoded samples to request from Opus decoder
    private val maxSamples = 5760

    fun start() {
        if (audioTrack != null) return
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        // Ensure at least 100ms of buffer to reduce jitter. Calculate in bytes: samples = sampleRate * (ms/1000)
        // bytes = samples * channels * 2 (2 bytes per PCM_16BIT sample)
        val bufferMs = 300
        val bytesFor100Ms = (sampleRate * bufferMs * channels * 2) / 1000
        val bufferSize = max(minBuf, bytesFor100Ms)

        audioTrack = AudioTrack.Builder()
            // Use MEDIA usage to route to speaker by default (voice communication may route to earpiece/SCO)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack?.play()
            Log.d(TAG, "AudioTrack started (sampleRate=$sampleRate channels=$channels bufferSize=$bufferSize)")
            try {
                val state = audioTrack?.state
                val playState = audioTrack?.playState
                Log.d(TAG, "AudioTrack state=$state playState=$playState")
            } catch (_: Exception) { }
            startPlaybackThread()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack: ${e.message}")
            audioTrack = null
        }
    }

    fun stop() {
        try {
            // stop playback thread first
            stopPlaybackThread()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) { }
        audioTrack = null
    }


    // New: accept an Opus-encoded frame, decode it and forward to PCM enqueue
    fun enqueuePacket(payload: ByteArray) {
        if (payload.isEmpty()) return

        if (payload.size < 2) {
            Log.w(TAG, "Received payload too short to contain seq header, dropping")
            return
        }

        // Extract 16-bit sequence number (big-endian) from first two bytes
        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        if (audioTrack == null) start ()

        try {
            val decoded = OpusWrapper.decode(data, sampleRate, channels)
            // forward decoded PCM to jitter buffer with seq
            if (decoded!= null){
                enqueuePcm(decoded, seq)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode Opus packet for seq=$seq: ${e.message}")
        }
    }

    // Simplified: assume seq is never null. Check last first; if not newer, scan from end to insert.
    private fun enqueuePcm(pcm: ShortArray, seq: Int) {
        synchronized(bufferLock) {
            val newPkt = Packet(pcm, seq)

            // Empty buffer -> just add
            if (jitterBuffer.isEmpty()) {
                jitterBuffer.addLast(newPkt)
                return
            }

            val lastSeq = jitterBuffer.last().seq and 0xFFFF
            val expected = ((lastSeq + 1) and 0xFFFF)

            // If exactly the expected next sequence number -> append (fast path)
            if (seq == expected) {
                jitterBuffer.addLast(newPkt)
            } else {
                // unsigned distance from last to seq
                val diffFromLast = (seq - lastSeq) and 0xFFFF

                if (diffFromLast in 1 until 0x8000) {
                    // seq is newer than last (but not contiguous). Append to end.
                    jitterBuffer.addLast(newPkt)
                } else {
                    // Out-of-order or too-old packet: find insertion point by scanning from end.
                    val list = jitterBuffer.toMutableList()
                    var insertAt = -1
                    for (i in list.indices.reversed()) {
                        val curSeq = list[i].seq and 0xFFFF
                        if (curSeq == seq) {
                            // Duplicate -> drop
                            Log.w(TAG, "Dropping duplicate packet seq=$seq")
                            return
                        }
                        val diff = (seq - curSeq) and 0xFFFF
                        if (diff in 1 until 0x8000) {
                            // new seq is newer than list[i], so insert after it
                            insertAt = i + 1
                            break
                        }
                    }

                    if (insertAt == -1) {
                        // too old compared to all entries -> drop
                        Log.w(TAG, "Dropping too old packet seq=$seq")
                        return
                    }

                    list.add(insertAt, newPkt)
                    jitterBuffer.clear()
                    jitterBuffer.addAll(list)
                }
            }

            // Trim buffer to max duration if necessary
            if (bufferDurationMsLocked() > bufferMsMax) {
                while (bufferDurationMsLocked() > bufferMsTarget){
                    val dropped = jitterBuffer.removeFirst()
                    Log.w(TAG, "Dropping old packet seq=${dropped.seq} to keep jitter buffer <= ${bufferMsMax}ms")
                }
            }
        }
    }

    // Calculate current buffer duration in milliseconds (must be called under bufferLock)
    private fun bufferDurationMsLocked(): Int {
        var totalSamples = 0
        for (p in jitterBuffer) {
            totalSamples += p.pcm.size
        }
        // totalSamples includes channel samples; duration ms = (samples / channels) * 1000 / sampleRate
        return ((totalSamples.toDouble() / channels) * 1000.0 / sampleRate).toInt()
    }

    private fun startPlaybackThread() {
        if (playbackThread != null) return
        running = true
        playbackThread = thread(start = true, name = "AudioPlayer-JitterPlayback") {
            try {
                // Wait for initial warm-up (bufferMsTarget) or until a short max wait to avoid never-starting when only small packets arrive
                while (running) {
                    val currentMs = synchronized(bufferLock) { bufferDurationMsLocked() }
                    if (currentMs >= bufferMsTarget){
                        break
                    }
                    Thread.sleep(10)
                }

                while (running) {
                    var pkt: Packet? = null
                    synchronized(bufferLock) {
                        if (jitterBuffer.isNotEmpty()) {
                            pkt = jitterBuffer.removeFirst()
                        }
                        val remainingBufferMs = bufferDurationMsLocked()
                        Log.d(TAG,"$remainingBufferMs ms left")
                        if (remainingBufferMs < bufferMsMin) {
                            Log.w(TAG, "Jitter buffer below minimum (${remainingBufferMs}ms < ${bufferMsMin}ms), writing silence to AudioTrack")
                            val pcm = ShortArray((sampleRate * channels * (bufferMsMin - remainingBufferMs)) / 1000)
                            audioTrack?.write(pcm, 0, pcm.size)
                            Thread.sleep((bufferMsMin - remainingBufferMs).toLong())
                        }
                    }
                    if (pkt == null) {
                        if (timeoutCounter >= timeout) stopPlaybackThread()
                        timeoutCounter += 60
                        Thread.sleep(60)
                        continue
                    }
                    timeoutCounter = 0
                    val p = pkt
                    try {
                        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            Log.w(TAG, "AudioTrack not playing (playState=${audioTrack?.playState}), attempting to play()")
                            try { audioTrack?.play() } catch (_: Exception) { }
                        }
                        val written = audioTrack?.write(p.pcm, 0, p.pcm.size) ?: -1
                        if (written <= 0) {
                            Log.w(TAG, "AudioTrack.write returned $written for requested=${p.pcm.size} for seq=${p.seq}")
                        } else {
                            Log.d(TAG, "Wrote audio samples=$written (requested=${p.pcm.size}) for seq=${p.seq}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write PCM to AudioTrack in playback thread: ${e.message}")
                    }
                    Thread.sleep(60)
                }
            } catch (_: InterruptedException) {
                // restore interrupt status and exit
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Playback thread exception: ${e.message}")
            }
        }
    }

    private fun stopPlaybackThread() {
        running = false
        playbackThread?.interrupt()
        playbackThread = null
        // clear buffer if desired (optional): keep or clear. We'll clear to avoid stale packets
        synchronized(bufferLock) {
            jitterBuffer.clear()
        }
    }
}

