package com.bitchat.android.rtc

/**
 * Compact RTC synchronization header.
 *
 * Layout (1 byte):
 * - bits 7..6: sync type (2 bits)
 * - bit 5: call type (0=voice, 1=video)
 * - bit 4: mode (0=one-way, 1=two-way)
 * - bits 3..0: reserved (0)
 */
data class RTCSync(
    val syncType: SyncType,
    val callType: CallType = CallType.VOICE,
    val mode: Mode = Mode.TWO_WAY
) {
    enum class SyncType(val bits: Int) {
        INVITE(0b00),
        ACCEPT(0b01),
        HANGUP(0b10);

        companion object {
            fun fromBits(bits: Int): SyncType = when (bits and 0b11) {
                0b00 -> INVITE
                0b01 -> ACCEPT
                0b10 -> HANGUP
                else -> INVITE
            }
        }
    }

    enum class CallType(val bit: Int) {
        VOICE(0),
        VIDEO(1);

        companion object {
            fun fromBit(bit: Int): CallType = if ((bit and 0b1) == 1) VIDEO else VOICE
        }
    }

    enum class Mode(val bit: Int) {
        ONE_WAY(0),
        TWO_WAY(1);

        companion object {
            fun fromBit(bit: Int): Mode = if ((bit and 0b1) == 1) TWO_WAY else ONE_WAY
        }
    }

    fun encode(): ByteArray {
        val b = ((syncType.bits and 0b11) shl 6) or
            ((callType.bit and 0b1) shl 5) or
            ((mode.bit and 0b1) shl 4)
        return byteArrayOf(b.toByte())
    }

    companion object {
        fun decode(payload: ByteArray?): RTCSync? {
            if (payload == null || payload.isEmpty()) return null
            val b = payload[0].toInt() and 0xFF
            val syncBits = (b ushr 6) and 0b11
            val callBit = (b ushr 5) and 0b1
            val modeBit = (b ushr 4) and 0b1
            return RTCSync(
                syncType = SyncType.fromBits(syncBits),
                callType = CallType.fromBit(callBit),
                mode = Mode.fromBit(modeBit)
            )
        }
    }
}

