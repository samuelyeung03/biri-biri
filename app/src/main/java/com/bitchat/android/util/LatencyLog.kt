package com.bitchat.android.util

import android.os.SystemClock
import android.util.Log

/**
 * Structured latency logging.
 *
 * Always logs with tag "latency" so you can capture with:
 *   adb logcat -s latency
 *
 * Format is key=value pairs to make it easy to parse.
 */
object LatencyLog {
    const val TAG: String = "latency"

    /** Monotonic timestamp (best for latency math). */
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    /** Convenience to log an event with optional fields. */
    fun d(ev: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder(64)
        sb.append("ev=").append(ev)
        sb.append(" t=").append(nowNs())
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=')
            sb.append(v.toString())
        }
        Log.d(TAG, sb.toString())
    }
}

