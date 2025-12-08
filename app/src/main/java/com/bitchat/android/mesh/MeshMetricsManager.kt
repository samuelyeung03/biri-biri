package com.bitchat.android.mesh

import java.util.concurrent.ConcurrentHashMap

object MeshMetricsManager{

    private val pingTimestamps = ConcurrentHashMap<String, Long>()

    fun recordPing(messageID : String) {
        pingTimestamps[messageID] = System.currentTimeMillis()
    }

    fun getRtt(messageID : String): Long? {
        val timestamp = pingTimestamps[messageID] ?: return null
        return System.currentTimeMillis() - timestamp
    }

    fun clearPing() {
        pingTimestamps.clear()
    }


}