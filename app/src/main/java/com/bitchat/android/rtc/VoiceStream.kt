package com.bitchat.android.rtc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Voice stream implementation:
 * - capture PCM16 from microphone
 * - encode + send via [BluetoothMeshService]
 * - receive + decode + jitter-buffer + playback
 */
class VoiceStream(
    private val context: Context? = null,
    private val sampleRate: Int = AppConstants.VoiceCall.DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = AppConstants.VoiceCall.DEFAULT_CHANNEL_COUNT,
    private val bitrate: Int = AppConstants.VoiceCall.DEFAULT_BITRATE_BPS,
    private val frameSamples: Int = AppConstants.VoiceCall.FRAME_SAMPLES_60_MS,
    private val encoderFactory: () -> AudioEncoder = { OpusEncoder(sampleRate, channels, bitrate) },
    private val inputDeviceFactory: () -> AudioInputDevice = { AudioInputDevice(sampleRate, channels, frameSamples) },
    private val audioOutputDevice: AudioOutputDevice = AudioOutputDevice(sampleRate, channels),
    private val audioDecoder: AudioDecoder = OpusDecoder(sampleRate, channels),
    private var meshService: BluetoothMeshService? = null
) {
    companion object {
        private const val TAG = "VoiceStream"
        private const val LATENCY_TAG = "latency"
    }

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioEncoder: AudioEncoder? = null
    private var audioInputDevice: AudioInputDevice? = null

    private var seqNumber: Int = 0
    private val jitterBuffer = ArrayDeque<Packet>()
    private val bufferLock = Any()
    private val bufferMsTarget = AppConstants.VoiceCall.JITTER_BUFFER_TARGET_MS
    private val bufferMsMax = AppConstants.VoiceCall.JITTER_BUFFER_MAX_MS
    private val bufferMsMin = AppConstants.VoiceCall.JITTER_BUFFER_MIN_MS

    private data class Packet(val pcm: ShortArray, val seq: Int)

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshService = meshService
    }

    val isSending: Boolean
        get() = recordingJob != null

    @SuppressLint("MissingPermission")
    fun startSending(senderId: String, recipientId: String?) {
        Log.d(TAG, "📞 startSending: senderId=$senderId recipientId=$recipientId")
        if (recordingJob != null) {
            Log.d(TAG, "startSending: already active, ignoring")
            return
        }

        // If we have context, verify RECORD_AUDIO permission + AppOps before starting
        if (context != null) {
            val ok = hasRecordAudioPermissionWithAppOps(context)
            if (!ok) {
                Log.e(TAG, "Cannot start sending: RECORD_AUDIO permission or AppOps denied")
                return
            }
        } else {
            Log.w(TAG, "No Context provided to VoiceStream; unable to check RECORD_AUDIO AppOps. Proceeding (may fail at runtime)")
        }

        // Ensure runtime permission explicitly before initializing encoder
        if (context != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing RECORD_AUDIO permission before encoder init")
            return
        }

        try {
            audioEncoder = encoderFactory.invoke()
            audioInputDevice = inputDeviceFactory.invoke()
            Log.d(TAG, "Audio encoder initialized (sampleRate=$sampleRate channels=$channels bitrate=$bitrate)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio components: ${e.message}", e)
            audioEncoder?.release()
            audioEncoder = null
            return
        }

        recordingJob = scope.launch {
            Log.d(TAG, "Recording coroutine started")

            // Explicit runtime permission check to satisfy lint/static analyzers
            if (context != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing RECORD_AUDIO permission at recording start")
                return@launch
            }

            val inputDevice = audioInputDevice ?: inputDeviceFactory.invoke().also { audioInputDevice = it }
            val recorder = inputDevice.createRecorder()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return@launch
            }

            Log.d(TAG, "AudioRecord initialized, starting recording")
            recorder.startRecording()
            val shortBuffer = ShortArray(frameSamples)

            try {
                while (isActive) {
                    val currentSeq = seqNumber and 0xFFFF
                    Log.d(LATENCY_TAG, "🎙️ Start capture for seq=$currentSeq")
                    val ok = inputDevice.readFrame(recorder, shortBuffer)
                    if (!ok) {
                        Log.w(TAG, "Failed to read audio frame")
                        continue
                    }
                    Log.d(LATENCY_TAG, "🎙️ Captured audio frame for seq=$currentSeq")
                    sendEncodedFrame(shortBuffer, recipientId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop failed: ${e.message}", e)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                Log.d(TAG, "Recorder stopped and released")
            }
        }
    }

    fun stopSending() {
        Log.d(TAG, "stopSending: cancelling recording job and cleaning up encoder")
        recordingJob?.cancel()
        recordingJob = null
        audioEncoder?.release()
        audioEncoder = null
        Log.d(TAG, "Encoder destroyed")
    }

    fun stop() {
        stopSending()
        stopReceiving()
    }

    fun handleIncomingAudio(packet: BitchatPacket) {
        val payload = packet.payload
        if (payload.size < 2) {
            Log.w(TAG, "Received payload too short to contain seq header, dropping")
            return
        }

        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        Log.d(LATENCY_TAG, "🔊 handleIncomingAudio: seq=$seq, payloadSize=${payload.size}")

        // Send VOICE_ACK
        meshService?.sendVoiceAck(packet.senderID.toHexString(), seq)

        Log.d(LATENCY_TAG, "🔍 Decoding started for seq=$seq")
        val decoded = audioDecoder.decode(data)

        if (decoded == null || decoded.isEmpty()) {
            Log.w(TAG, "❌ Decoded PCM empty for seq=$seq")
            return
        }

        Log.d(LATENCY_TAG, "✅ Decoded voice frame: seq=$seq, pcmSize=${decoded.size}")
        enqueuePcm(decoded, seq)
        startPlaybackLoopIfNeeded()
    }

    fun handleVoiceAck(packet: BitchatPacket) {
        val payload = packet.payload
        if (payload.size < 2) {
            Log.w(TAG, "Received payload too short to contain seq header, dropping")
            return
        }
        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        Log.d(LATENCY_TAG, "🗣️ Received VOICE_ACK for seq=$seq from ${packet.senderID.toHexString()}")
    }

    private fun stopReceiving() {
        synchronized(bufferLock) { jitterBuffer.clear() }
        playbackJob?.cancel()
        playbackJob = null
        audioOutputDevice.stop()
    }

    private fun sendEncodedFrame(pcm: ShortArray, recipientId: String?) {
        val seq = seqNumber and 0xFFFF
        Log.d(LATENCY_TAG, "🎤 sendEncodedFrame: pcm size=${pcm.size} seq=$seq")

        Log.d(LATENCY_TAG, "⏱️ Encoding started for seq=$seq")
        val encoded = audioEncoder?.encode(pcm)
        Log.d(LATENCY_TAG, "⏱️ Encoding finished for seq=$seq")

        if (encoded == null) {
            Log.w(TAG, "Audio encoder returned null for frame size=${pcm.size}")
            return
        }

        // seqNumber is 0-based for tracking, but wire format uses 2 bytes
        val payload = ByteArray(encoded.size + 2)
        payload[0] = ((seq shr 8) and 0xFF).toByte()
        payload[1] = (seq and 0xFF).toByte()
        System.arraycopy(encoded, 0, payload, 2, encoded.size)
        seqNumber = (seq + 1) and 0xFFFF

        Log.d(LATENCY_TAG, "📦 Encoded voice frame: seq=$seq, size=${encoded.size}, payload=${payload.size}")

        try {
            val ms = meshService
            if (ms == null) {
                Log.w(TAG, "No BluetoothMeshService attached — call attachMeshService(meshService) before startSending")
                return
            }
            Log.d(LATENCY_TAG, "📲 Calling meshService.sendVoice with seq=$seq")
            ms.sendVoice(recipientId, payload)
            Log.d(LATENCY_TAG, "📲 meshService.sendVoice returned for seq=$seq")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hand encoded audio to mesh service: ${e.message}")
        }
    }

    private fun enqueuePcm(pcm: ShortArray, seq: Int) {
        synchronized(bufferLock) {
            val newPkt = Packet(pcm, seq)
            if (jitterBuffer.isEmpty()) {
                jitterBuffer.addLast(newPkt)
            } else {
                val lastSeq = jitterBuffer.last().seq and 0xFFFF
                val expected = (lastSeq + 1) and 0xFFFF
                if (seq == expected) {
                    jitterBuffer.addLast(newPkt)
                } else {
                    val diffFromLast = (seq - lastSeq) and 0xFFFF
                    if (diffFromLast in 1 until 0x8000) {
                        jitterBuffer.addLast(newPkt)
                    } else {
                        val list = jitterBuffer.toMutableList()
                        var insertAt = -1
                        for (i in list.indices.reversed()) {
                            val curSeq = list[i].seq and 0xFFFF
                            if (curSeq == seq) {
                                Log.w(TAG, "Dropping duplicate packet seq=$seq")
                                return
                            }
                            val diff = (seq - curSeq) and 0xFFFF
                            if (diff in 1 until 0x8000) {
                                insertAt = i + 1
                                break
                            }
                        }
                        if (insertAt == -1) {
                            Log.w(TAG, "Dropping too old packet seq=$seq")
                            return
                        }
                        list.add(insertAt, newPkt)
                        jitterBuffer.clear()
                        jitterBuffer.addAll(list)
                    }
                }
            }

            Log.d(LATENCY_TAG, "📥 Enqueued packet seq=$seq, buffer size=${jitterBuffer.size}")

            if (bufferDurationMsLocked() > bufferMsMax) {
                while (bufferDurationMsLocked() > bufferMsTarget && jitterBuffer.isNotEmpty()) {
                    val dropped = jitterBuffer.removeFirst()
                    Log.w(TAG, "Dropping old packet seq=${dropped.seq} to maintain buffer")
                }
            }
        }
    }

    private fun bufferDurationMsLocked(): Int {
        var totalSamples = 0
        for (p in jitterBuffer) {
            totalSamples += p.pcm.size
        }
        return ((totalSamples.toDouble() / channels) * 1000.0 / sampleRate).toInt()
    }

    private fun startPlaybackLoopIfNeeded() {
        if (playbackJob != null) return
        Log.d(TAG, "▶️ Starting playback loop")
        playbackJob = scope.launch {
            var warmed = false
            while (isActive) {
                var packet: Packet? = null
                var bufferMs: Int
                synchronized(bufferLock) {
                    bufferMs = bufferDurationMsLocked()
                    if (!warmed && bufferMs < bufferMsTarget) {
                        packet = null
                    } else {
                        warmed = true
                        if (jitterBuffer.isNotEmpty()) {
                            packet = jitterBuffer.removeFirst()
                            Log.d(LATENCY_TAG, "📤 Dequeued packet seq=${packet?.seq}, buffer size=${jitterBuffer.size}")
                        }
                    }
                }

                if (packet == null) {
                    delay(20)
                    continue
                }

                Log.d(LATENCY_TAG, "🔔 Playback started for seq=${packet!!.seq}")
                audioOutputDevice.play(packet!!.pcm, packet!!.seq)
                Log.d(LATENCY_TAG, "🔈 Played PCM seq=${packet!!.seq}")

                synchronized(bufferLock) { bufferMs = bufferDurationMsLocked() }
                if (bufferMs < bufferMsMin) {
                    val deficitMs = bufferMsMin - bufferMs
                    if (deficitMs > 0) {
                        val silenceSamples = (sampleRate * channels * deficitMs) / 1000
                        if (silenceSamples > 0) {
                            Log.d(TAG, "Playing ${deficitMs}ms of silence to compensate for buffer under-run")
                            audioOutputDevice.play(ShortArray(silenceSamples), -1)
                        }
                        delay(deficitMs.toLong())
                    }
                } else {
                    delay(20)
                }
            }
        }
    }

    // Permission + AppOps check
    private fun hasRecordAudioPermissionWithAppOps(ctx: Context): Boolean {
        val granted =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "hasRecordAudioPermissionWithAppOps: runtime permission granted=$granted")
        if (!granted) return false

        try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: run {
                Log.d(TAG, "hasRecordAudioPermissionWithAppOps: AppOpsManager not available, allowing")
                return true
            }
            val uid = Process.myUid()
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, ctx.packageName)
            }
            Log.d(TAG, "hasRecordAudioPermissionWithAppOps: appops mode=$mode")
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "AppOps check failed: ${e.message}")
            return true
        }
    }
}
