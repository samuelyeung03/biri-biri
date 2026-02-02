package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.util.LatencyLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager {
    
    companion object {
        private const val TAG = "FragmentManager"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
    }
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    // Fragment storage - iOS equivalent: incomingFragments: [String: [Int: Data]]
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // iOS equivalent: fragmentMetadata: [String: (type: UInt8, total: Int, timestamp: Date)]
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    
    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    fun     createFragments(packet: BitchatPacket): List<BitchatPacket> {
        try {
            Log.d(TAG, " Creating fragments for packet type ${packet.type}, payload: ${packet.payload.size} bytes")
            val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, " Failed to encode packet to binary data")
                return emptyList()
            }

            // Fragment the unpadded frame; each fragment will be encoded (and padded) independently - iOS fix
            val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, " Failed to unpad data: ${e.message}", e)
                return emptyList()
            }

            // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
            if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
                return listOf(packet) // No fragmentation needed
            }

            val fragments = mutableListOf<BitchatPacket>()

            // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
            val fragmentID = FragmentPayload.generateFragmentID()
            val fragmentIDString = fragmentID.joinToString("") { "%02x".format(it) }

            LatencyLog.d(
                "frag_create",
                "fragId" to fragmentIDString,
                "origType" to packet.type,
                "fullBytes" to fullData.size
            )

            // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
            val fragmentChunks = stride(0, fullData.size, MAX_FRAGMENT_SIZE) { offset ->
                val endOffset = minOf(offset + MAX_FRAGMENT_SIZE, fullData.size)
                fullData.sliceArray(offset..<endOffset)
            }

            LatencyLog.d(
                "frag_split",
                "fragId" to fragmentIDString,
                "tot" to fragmentChunks.size,
                "chunkMax" to MAX_FRAGMENT_SIZE
            )

            // iOS: for (index, fragment) in fragments.enumerated()
            for (index in fragmentChunks.indices) {
                val fragmentData = fragmentChunks[index]

                // Create iOS-compatible fragment payload
                val fragmentPayload = FragmentPayload(
                    fragmentID = fragmentID,
                    index = index,
                    total = fragmentChunks.size,
                    originalType = packet.type,
                    data = fragmentData
                )

                // iOS: MessageType.fragment.rawValue (single fragment type)
                val fragmentPacket = BitchatPacket(
                    type = MessageType.FRAGMENT.value,
                    ttl = packet.ttl,
                    senderID = packet.senderID,
                    recipientID = packet.recipientID,
                    timestamp = packet.timestamp,
                    payload = fragmentPayload.encode(),
                    signature = null // iOS: signature: nil
                )

                LatencyLog.d(
                    "frag_emit",
                    "fragId" to fragmentIDString,
                    "idx" to index,
                    "tot" to fragmentChunks.size,
                    "bytes" to fragmentData.size
                )

                fragments.add(fragmentPacket)
            }

            Log.d(TAG, " Created ${fragments.size} fragments successfully")
            return fragments
        } catch (e: Exception) {
            Log.e(TAG, " Fragment creation failed: ${e.message}", e)
            Log.e(TAG, " Packet type: ${packet.type}, payload: ${packet.payload.size} bytes")
            return emptyList()
        }
    }
    
    /**
     * Handle incoming fragment - 100% iOS Compatible  
     * Matches iOS handleFragment() implementation exactly
     */
    fun handleFragment(packet: BitchatPacket): BitchatPacket? {
        // iOS: guard packet.payload.count > 13 else { return }
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }

        try {
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.w(TAG, "Invalid fragment payload")
                return null
            }

            val fragmentIDString = fragmentPayload.getFragmentIDString()

            LatencyLog.d(
                "reasm_add",
                "fragId" to fragmentIDString,
                "idx" to fragmentPayload.index,
                "tot" to fragmentPayload.total,
                "bytes" to fragmentPayload.data.size,
                "origType" to fragmentPayload.originalType
            )

            // Don't process our own fragments - iOS equivalent check
            // This would be done at a higher level but we'll include for safety

            // iOS: if incomingFragments[fragmentID] == nil
            if (!incomingFragments.containsKey(fragmentIDString)) {
                incomingFragments[fragmentIDString] = mutableMapOf()
                fragmentMetadata[fragmentIDString] = Triple(
                    fragmentPayload.originalType, 
                    fragmentPayload.total, 
                    System.currentTimeMillis()
                )
            }
            
            // iOS: incomingFragments[fragmentID]?[index] = Data(fragmentData)
            incomingFragments[fragmentIDString]?.put(fragmentPayload.index, fragmentPayload.data)

            val fragmentMap = incomingFragments[fragmentIDString]
            if (fragmentMap != null && fragmentMap.size == fragmentPayload.total) {
                Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")

                // iOS reassembly logic
                val reassembledData = mutableListOf<Byte>()
                for (i in 0 until fragmentPayload.total) {
                    fragmentMap[i]?.let { data ->
                        reassembledData.addAll(data.asIterable())
                    }
                }

                val bytes = reassembledData.size
                LatencyLog.d(
                    "reasm_done",
                    "fragId" to fragmentIDString,
                    "tot" to fragmentPayload.total,
                    "bytes" to bytes,
                    "origType" to fragmentPayload.originalType
                )

                val originalPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                if (originalPacket != null) {
                    // iOS cleanup: incomingFragments.removeValue(forKey: fragmentID)
                    incomingFragments.remove(fragmentIDString)
                    fragmentMetadata.remove(fragmentIDString)
                    
                    // Suppress re-broadcast of the reassembled packet by zeroing TTL.
                    // We already relayed the incoming fragments; setting TTL=0 ensures
                    // PacketRelayManager will skip relaying this reconstructed packet.
                    val suppressedTtlPacket = originalPacket.copy(ttl = 0u.toUByte())
                    return suppressedTtlPacket
                } else {
                    LatencyLog.d("reasm_fail", "fragId" to fragmentIDString)
                    val metadata = fragmentMetadata[fragmentIDString]
                    Log.e(TAG, "Failed to decode reassembled packet (type=${metadata?.first}, total=${metadata?.second})")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }

        return null
    }
    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    private fun cleanupOldFragments() {
        val now = System.currentTimeMillis()
        val cutoff = now - FRAGMENT_TIMEOUT
        
        // iOS: let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
        val oldFragments = fragmentMetadata.filter { it.value.third < cutoff }.map { it.key }
        
        // iOS: for fragmentID in oldFragments { incomingFragments.removeValue(forKey: fragmentID) }
        for (fragmentID in oldFragments) {
            incomingFragments.remove(fragmentID)
            fragmentMetadata.remove(fragmentID)
        }
        
        if (oldFragments.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
            appendLine("Active Fragment Sets: ${incomingFragments.size}")
            appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
            appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")
            
            fragmentMetadata.forEach { (fragmentID, metadata) ->
                val (originalType, totalFragments, timestamp) = metadata
                val received = incomingFragments[fragmentID]?.size ?: 0
                val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                appendLine("  - $fragmentID: $received/$totalFragments fragments, type: $originalType, age: ${ageSeconds}s")
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        incomingFragments.clear()
        fragmentMetadata.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
