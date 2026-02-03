package com.bitchat.android.ui

/**
 * Snapshot of negotiated/configured video call parameters for display.
 */
data class VideoCallStats(
    val width: Int,
    val height: Int,
    val codec: String,
    val bitrateBps: Int
)

