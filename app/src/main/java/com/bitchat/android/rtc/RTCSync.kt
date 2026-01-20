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
    val mode: Mode = Mode.TWO_WAY,
    val videoParams: VideoParams? = null
) {
    data class VideoParams(
        val codec: String = com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_CODEC,
        val width: Int = com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_WIDTH,
        val height: Int = com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_HEIGHT,
        val bitrateBps: Int = com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_BITRATE_BPS
    )

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
        val header = ((syncType.bits and 0b11) shl 6) or
            ((callType.bit and 0b1) shl 5) or
            ((mode.bit and 0b1) shl 4)

        // Legacy payload is 1 byte. Only append extensions for VIDEO when params provided.
        val vp = videoParams
        if (callType != CallType.VIDEO || vp == null) {
            return byteArrayOf(header.toByte())
        }

        // TLV section:
        // [0]=header, [1]=0x01 (ext version)
        // then repeated: [tag][len][value...]
        // tags:
        //  1 codec (utf-8)
        //  2 width (u16)
        //  3 height (u16)
        //  4 bitrate (u32)
        val codecBytes = vp.codec.toByteArray(Charsets.UTF_8)
        fun u16(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        fun u32(v: Int) = byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )

        val out = ArrayList<Byte>(1 + 1 + 2 + codecBytes.size + 2 + 2 + 2 + 2 + 2 + 4)
        out.add(header.toByte())
        out.add(0x01) // ext version

        // codec
        out.add(0x01)
        out.add(codecBytes.size.toByte())
        codecBytes.forEach { out.add(it) }
        // width
        out.add(0x02)
        out.add(0x02)
        u16(vp.width).forEach { out.add(it) }
        // height
        out.add(0x03)
        out.add(0x02)
        u16(vp.height).forEach { out.add(it) }
        // bitrate
        out.add(0x04)
        out.add(0x04)
        u32(vp.bitrateBps).forEach { out.add(it) }

        return out.toByteArray()
    }

    companion object {
        fun decode(payload: ByteArray?): RTCSync? {
            if (payload == null || payload.isEmpty()) return null
            val b = payload[0].toInt() and 0xFF
            val syncBits = (b ushr 6) and 0b11
            val callBit = (b ushr 5) and 0b1
            val modeBit = (b ushr 4) and 0b1

            val callType = CallType.fromBit(callBit)
            val base = RTCSync(
                syncType = SyncType.fromBits(syncBits),
                callType = callType,
                mode = Mode.fromBit(modeBit),
                videoParams = null
            )

            // Legacy: header-only.
            if (payload.size <= 1 || callType != CallType.VIDEO) return base

            // Extensions: versioned TLV.
            val extVersion = payload[1].toInt() and 0xFF
            if (extVersion != 0x01) return base

            var codec: String? = null
            var width: Int? = null
            var height: Int? = null
            var bitrate: Int? = null

            var i = 2
            while (i + 2 <= payload.size) {
                val tag = payload[i].toInt() and 0xFF
                val len = payload[i + 1].toInt() and 0xFF
                i += 2
                if (i + len > payload.size) break
                val value = payload.copyOfRange(i, i + len)
                when (tag) {
                    0x01 -> codec = runCatching { value.toString(Charsets.UTF_8) }.getOrNull()
                    0x02 -> if (len == 2) width = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                    0x03 -> if (len == 2) height = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                    0x04 -> if (len == 4) bitrate =
                        ((value[0].toInt() and 0xFF) shl 24) or
                            ((value[1].toInt() and 0xFF) shl 16) or
                            ((value[2].toInt() and 0xFF) shl 8) or
                            (value[3].toInt() and 0xFF)
                    else -> {
                        // ignore unknown
                    }
                }
                i += len
            }

            val vp = if (codec != null || width != null || height != null || bitrate != null) {
                VideoParams(
                    codec = codec ?: com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_CODEC,
                    width = width ?: com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_WIDTH,
                    height = height ?: com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_HEIGHT,
                    bitrateBps = bitrate ?: com.bitchat.android.util.AppConstants.VideoCall.DEFAULT_BITRATE_BPS
                )
            } else {
                null
            }

            return base.copy(videoParams = vp)
        }
    }
}
