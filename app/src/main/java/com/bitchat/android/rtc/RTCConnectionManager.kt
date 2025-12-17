package com.bitchat.android.rtc

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.util.AppConstants
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.util.*

/**
 * Real-time voice call manager:
 * - captures PCM16 @ 48kHz mono
 * - uses smallest Opus frame size (2.5ms -> 120 samples @ 48kHz)
 * - separate encode function to allow swapping codec
 * - fragments encoded frames into fragment packets with 469B payload size
 *
 * Usage:
 * val mgr = RTCConnectionManager(context, sendPacket = { packet -> /* send over mesh */ })
 * --OR--
 * val mgr = RTCConnectionManager(context, meshService = meshService)
 * mgr.startCall("sender", "recipient")
 * mgr.stopCall()
 */
class RTCConnectionManager(
    private val context: Context? = null,
    private val sampleRate: Int = AppConstants.Rtc.DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = AppConstants.Rtc.DEFAULT_CHANNEL_COUNT,
    private val bitrate: Int = AppConstants.Rtc.DEFAULT_BITRATE_BPS,
    private val frameSamples: Int = AppConstants.Rtc.FRAME_SAMPLES_60_MS,
    private val encoderFactory: () -> AudioEncoder = { OpusEncoder(sampleRate, channels, bitrate) },
    private val inputDeviceFactory: () -> AudioInputDevice = { AudioInputDevice(sampleRate, channels, frameSamples) },
    private val audioOutputDevice: AudioOutputDevice = AudioOutputDevice(sampleRate, channels),
    private val audioDecoder: AudioDecoder = OpusDecoder(sampleRate, channels)
) {
    companion object {
        private const val TAG = "RTCConnectionManager"
    }

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioEncoder: AudioEncoder? = null
    private var audioInputDevice: AudioInputDevice? = null

    private var seqNumber: Int = 0
    private val jitterBuffer = ArrayDeque<Packet>()
    private val bufferLock = Any()
    private val bufferMsTarget = AppConstants.Rtc.JITTER_BUFFER_TARGET_MS
    private val bufferMsMax = AppConstants.Rtc.JITTER_BUFFER_MAX_MS
    private val bufferMsMin = AppConstants.Rtc.JITTER_BUFFER_MIN_MS

    private data class Packet(val pcm: ShortArray, val seq: Int)

    // Keep an optional reference to BluetoothMeshService when constructed that way
    private var meshServiceRef: BluetoothMeshService? = null

    // Convenience constructor that takes BluetoothMeshService and uses it to send encoded frames
    constructor(
        context: Context? = null,
        meshService: BluetoothMeshService,
        sampleRate: Int = AppConstants.Rtc.DEFAULT_SAMPLE_RATE_HZ,
        channels: Int = AppConstants.Rtc.DEFAULT_CHANNEL_COUNT,
        bitrate: Int = AppConstants.Rtc.MIN_BITRATE_BPS
    ) : this(context, sampleRate, channels, bitrate) {
        this.meshServiceRef = meshService
    }

    fun attachMeshService(meshService: BluetoothMeshService) {
        this.meshServiceRef = meshService
    }

    @SuppressLint("MissingPermission")
    fun startCall(senderId: String, recipientId: String?) {
        Log.d(TAG, "startCall: senderId=$senderId recipientId=$recipientId")
        if (recordingJob != null) {
            Log.d(TAG, "startCall: recording already active, ignoring")
            return
        }

        // If we have context, verify RECORD_AUDIO permission + AppOps before starting
        if (context != null) {
            val ok = hasRecordAudioPermissionWithAppOps(context)
            if (!ok) {
                Log.e(TAG, "Cannot start call: RECORD_AUDIO permission or AppOps denied")
                return
            }
        } else {
            Log.w(TAG, "No Context provided to RTCConnectionManager; unable to check RECORD_AUDIO AppOps. Proceeding (may fail at runtime)")
        }

        // Ensure runtime permission explicitly before initializing encoder
        if (context != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
            if (context != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                    val ok = inputDevice.readFrame(recorder, shortBuffer)
                    if (!ok) {
                        Log.w(TAG, "Failed to read audio frame")
                        continue
                    }

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

    fun stopCall() {
        Log.d(TAG, "stopCall: cancelling recording job and cleaning up encoder")
        recordingJob?.cancel()
        recordingJob = null
        stopReceivingAudio()
        audioEncoder?.release()
        audioEncoder = null
        Log.d(TAG, "Encoder destroyed")
    }

    // Permission + AppOps check
    private fun hasRecordAudioPermissionWithAppOps(ctx: Context): Boolean {
        // Check runtime permission
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "hasRecordAudioPermissionWithAppOps: runtime permission granted=$granted")
        if (!granted) return false

        // Check AppOps (may block even when permission granted)
        try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: run {
                Log.d(TAG, "hasRecordAudioPermissionWithAppOps: AppOpsManager not available, allowing")
                return true
            }
            val uid = Process.myUid()
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, ctx.packageName)
            } else {
                // Fallback for older APIs
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, ctx.packageName)
            }
            Log.d(TAG, "hasRecordAudioPermissionWithAppOps: appops mode=$mode")
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "AppOps check failed: ${e.message}")
            return true
        }
    }

    fun handleIncomingAudio(packet: BitchatPacket) {
        val payload = packet.payload
        if (payload.size < 2) {
            Log.w(TAG, "Received payload too short to contain seq header, dropping")
            return
        }

        val seq = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val data = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

        val decoded = audioDecoder.decode(data)
        if (decoded == null || decoded.isEmpty()) {
            Log.w(TAG, "Decoded PCM empty for seq=$seq")
            return
        }

        enqueuePcm(decoded, seq)
        startPlaybackLoopIfNeeded()
    }

    private fun stopReceivingAudio() {
        synchronized(bufferLock) { jitterBuffer.clear() }
        playbackJob?.cancel()
        playbackJob = null
        audioOutputDevice.stop()
    }

    private fun sendEncodedFrame(pcm: ShortArray, recipientId: String?) {
        val encoded = audioEncoder?.encode(pcm)
        if (encoded == null) {
            Log.w(TAG, "Audio encoder returned null for frame size=${pcm.size}")
            return
        }

        val seq = seqNumber and 0xFFFF
        val payload = ByteArray(encoded.size + 2)
        payload[0] = ((seq shr 8) and 0xFF).toByte()
        payload[1] = (seq and 0xFF).toByte()
        System.arraycopy(encoded, 0, payload, 2, encoded.size)
        seqNumber = (seq + 1) and 0xFFFF

        try {
            meshServiceRef?.let { ms ->
                ms.sendVoice(recipientId, payload)
            } ?: run {
                Log.w(TAG, "No BluetoothMeshService attached — call attachMeshService(meshService) before startCall")
            }
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
        playbackJob = scope.launch {
            var warmed = false
            while (isActive) {
                var packet: Packet? = null
                var bufferMs = 0
                synchronized(bufferLock) {
                    bufferMs = bufferDurationMsLocked()
                    if (!warmed && bufferMs < bufferMsTarget) {
                        packet = null
                    } else {
                        warmed = true
                        if (jitterBuffer.isNotEmpty()) {
                            packet = jitterBuffer.removeFirst()
                        }
                    }
                }

                if (packet == null) {
                    delay(20)
                    continue
                }

                audioOutputDevice.play(packet!!.pcm)

                synchronized(bufferLock) { bufferMs = bufferDurationMsLocked() }
                if (bufferMs < bufferMsMin) {
                    val deficitMs = bufferMsMin - bufferMs
                    if (deficitMs > 0) {
                        val silenceSamples = (sampleRate * channels * deficitMs) / 1000
                        if (silenceSamples > 0) {
                            audioOutputDevice.play(ShortArray(silenceSamples))
                        }
                        delay(deficitMs.toLong())
                    }
                } else {
                    delay(20)
                }
            }
        }
    }
}