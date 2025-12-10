package com.bitchat.android.mesh

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object NetworkMetricsManager {

    private const val TAG = "NetworkMetricsManager"

    private data class RTT(
        val sendTimestamps: MutableMap<String, Long> = ConcurrentHashMap(),
        val values: MutableList<Int> = mutableListOf()
    )

    private val rtts = ConcurrentHashMap<String, RTT>()

    // New: separate storage for peer-specific RTT tracking
    private val peerRtts = ConcurrentHashMap<String, RTT>()

    fun startPing(peerId: String) {
        // Initialize or reset RTT for the given peer (both normal and peer-specific buckets)
        rtts[peerId] = RTT()
        peerRtts[peerId] = RTT()
        Log.d(TAG, "startPing: Initialized RTT and peerRTT for peerId=$peerId")
    }

    fun clearPing(peerId: String) {
        // Clear the RTT data for the given peer (both buckets)
        rtts[peerId]?.let {
            it.sendTimestamps.clear()
            it.values.clear()
            Log.d(TAG, "clearPing: Cleared RTT data for peerId=$peerId")
        } ?: Log.e(TAG, "clearPing: No RTT data found for peerId=$peerId")

        peerRtts[peerId]?.let {
            it.sendTimestamps.clear()
            it.values.clear()
            Log.d(TAG, "clearPing: Cleared peerRTT data for peerId=$peerId")
        }
    }

    fun registerSendTimestamp(peerId: String, messageId: String) {
        // Record the current time for a message sent to the peer (normal bucket)
        rtts[peerId]?.sendTimestamps?.set(messageId, System.currentTimeMillis())
        Log.d(TAG, "registerSendTimestamp: Recorded timestamp for messageId=$messageId, peerId=$peerId")
        peerRtts[peerId]?.sendTimestamps?.set(messageId, System.currentTimeMillis())
        Log.d(TAG, "registerSendPeerTimestamp: Recorded peer timestamp for messageId=$messageId, peerId=$peerId")
    }

    // New: register a send timestamp into the peer-specific bucket
    fun registerSendPeerTimestamp(peerId: String, messageId: String) {
        peerRtts[peerId]?.sendTimestamps?.set(messageId, System.currentTimeMillis())
        Log.d(TAG, "registerSendPeerTimestamp: Recorded peer timestamp for messageId=$messageId, peerId=$peerId")
    }

    fun recordPong(peerId: String, messageId: String): Int? {
        // Record the round-trip time for the given message (normal bucket)
        val rtt = rtts[peerId]
        if (rtt == null) {
            Log.e(TAG, "recordPong: No RTT data found for peerId=$peerId")
            return null
        }

        val sent = rtt.sendTimestamps.remove(messageId)
        if (sent == null) {
            Log.e(TAG, "recordPong: No sent timestamp found for messageId=$messageId, peerId=$peerId")
            return null
        }

        val diff = (System.currentTimeMillis() - sent).toInt()
        rtt.values.add(diff)
        Log.d(TAG, "recordPong: Recorded RTT=$diff ms for messageId=$messageId, peerId=$peerId")
        return diff
    }

    fun recordPongByPeer(peerId: String): Int? {
        // First try peer-specific RTTs
        val peerRtt = peerRtts[peerId]
        if (peerRtt != null) {
            val entry = peerRtt.sendTimestamps.entries.minByOrNull { it.value }
            if (entry != null) {
                val messageId = entry.key
                val sent = peerRtt.sendTimestamps.remove(messageId) ?: return null
                val diff = (System.currentTimeMillis() - sent).toInt()
                peerRtt.values.add(diff)
                Log.d(TAG, "recordPongByPeer: Matched peer messageId=$messageId RTT=$diff ms for peerId=$peerId (peerRtts)")
                return diff
            } else {
                Log.d(TAG, "recordPongByPeer: No outstanding peer send timestamps for peerId=$peerId in peerRtts")
            }
        } else {
        Log.d(TAG, "recordPongByPeer: did not find peerRtt for peerId=$peerId")
        }
        return -1
    }

    fun getPingResult(peerId: String): PingResult? {
        // Aggregate RTT values across both normal and peer-specific buckets
        val normal = rtts[peerId]
        val peerSpecific = peerRtts[peerId]

        val averageRTT = normal?.values?.average() ?: 0.0
        val averageRTTPeer = if (peerSpecific != null && peerSpecific.values.isNotEmpty()) {
            peerSpecific.values.average()
        } else {
            0.0
        }
        val packetLost = (normal?.sendTimestamps?.size ?: 0)
        Log.d(TAG, "getPingResult: Calculated averageRTT=$averageRTT ms,peerRtt=$averageRTTPeer, packetLost=$packetLost for peerId=$peerId")
        clearPing(peerId)
        return PingResult(averageRTT, averageRTTPeer,packetLost)
    }

    // Data class to represent ping results
    data class PingResult(
        val averageRTT: Double,
        val averageRTTPeer: Double,
        val packetLost: Int
    )
}