package com.bitchat.android.mesh

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Central scheduling choke point for *all* outbound BLE packets.
 *
 * Today this is a simple FIFO queue (single worker), but routing all sends through here
 * makes it easy to add prioritization, bounded queues, and per-device scheduling later.
 */
class BluetoothBroadcasterScheduler(
    private val scope: CoroutineScope,
    private val sender: suspend (routed: com.bitchat.android.model.RoutedPacket, gattServer: BluetoothGattServer?, characteristic: BluetoothGattCharacteristic?) -> Unit
) {
    private data class SendRequest(
        val routed: com.bitchat.android.model.RoutedPacket,
        val gattServer: BluetoothGattServer?,
        val characteristic: BluetoothGattCharacteristic?
    )

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<SendRequest>(capacity = Channel.UNLIMITED)

    init {
        schedulerScope.launch {
            for (req in queue) {
                sender(req.routed, req.gattServer, req.characteristic)
            }
        }
    }

    fun schedule(
        routed: com.bitchat.android.model.RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        // Try fast path: enqueue without suspending.
        if (!queue.trySend(SendRequest(routed, gattServer, characteristic)).isSuccess) {
            // Fallback: suspend enqueue in caller scope.
            scope.launch {
                queue.send(SendRequest(routed, gattServer, characteristic))
            }
        }
    }

    fun shutdown() {
        runCatching { queue.close() }
        schedulerScope.cancel()
    }
}

