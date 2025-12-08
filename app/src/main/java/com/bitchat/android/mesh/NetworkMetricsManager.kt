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

    fun startPing(peerId: String) {
        // Initialize or reset RTT for the given peer
        rtts[peerId] = RTT()
        Log.d(TAG, "startPing: Initialized RTT for peerId=$peerId")
    }

    fun clearPing(peerId: String) {
        // Clear the RTT data for the given peer
        rtts[peerId]?.let {
            it.sendTimestamps.clear()
            it.values.clear()
            Log.d(TAG, "clearPing: Cleared RTT data for peerId=$peerId")
        } ?: Log.e(TAG, "clearPing: No RTT data found for peerId=$peerId")
    }

    fun registerSendTimestamp(peerId: String, messageId: String) {
        // Record the current time for a message sent to the peer
        rtts[peerId]?.sendTimestamps?.set(messageId, System.currentTimeMillis())
        Log.d(TAG, "registerSendTimestamp: Recorded timestamp for messageId=$messageId, peerId=$peerId")
    }

    fun recordPong(peerId: String, messageId: String): Int? {
        // Record the round-trip time for the given message
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

    fun getPingResult(peerId: String): PingResult? {
        // Calculate average RTT and packet loss for the given peer
        val rtt = rtts[peerId]
        if (rtt == null) {
            Log.e(TAG, "getPingResult: No RTT data found for peerId=$peerId")
            return null
        }

        if (rtt.values.isEmpty()) {
            Log.e(TAG, "getPingResult: No RTT values recorded for peerId=$peerId")
            return null
        }

        val averageRTT = rtt.values.average()
        val packetLost = rtt.sendTimestamps.size
        Log.d(TAG, "getPingResult: Calculated averageRTT=$averageRTT ms, packetLost=$packetLost for peerId=$peerId")
        return PingResult(averageRTT, packetLost)
    }

    // Data class to represent ping results
    data class PingResult(
        val averageRTT: Double,
        val packetLost: Int
    )
}