package com.bitchat.android.rtc

/** Abstraction for audio encoders to support swapping codecs. */
interface AudioEncoder {
    fun encode(pcm: ShortArray): ByteArray?
    fun release()
}

/** Abstraction for audio decoders to support swapping codecs. */
interface AudioDecoder {
    fun decode(data: ByteArray): ShortArray?
}

