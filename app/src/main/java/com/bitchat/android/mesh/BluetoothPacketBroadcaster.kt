package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.util.LatencyLog

/**
 * Handles packet broadcasting to connected devices using actor pattern for serialization
 * 
 * In Bluetooth Low Energy (BLE):
 *
 * Peripheral (server):
 * Advertises.
 * Accepts connections.
 * Hosts a GATT server.
 * Remote devices read/write/subscribe to characteristics.
 *
 *  Central (client):
 * Scans.
 * Initiates connections.
 * Hosts a GATT client.
 * Reads/writes to the peripheral’s characteristics.
 */
class BluetoothPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val fragmentManager: FragmentManager?
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null } }

    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val CLEANUP_DELAY = com.bitchat.android.util.AppConstants.Mesh.BROADCAST_CLEANUP_DELAY_MS

        // Async failure (we got onCharacteristicWrite with non-success): small backoff.
        private const val CLIENT_WRITE_RETRY_DELAY_MS = 10L

        // Sync failure (writeCharacteristic() returned false): retry ASAP.
        // This helps recover quickly when the stack momentarily rejects because it's busy.
        private const val CLIENT_WRITE_SYNC_RETRY_DELAY_MS = 5L
    }
    // Optional nickname resolver injected by higher layer (peerID -> nickname?)
    private var nicknameResolver: ((String) -> String?)? = null

    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }
    
    /**
     * Debug logging helper - can be easily removed/disabled for production
     */
    private fun logPacketRelay(
        typeName: String,
        senderPeerID: String,
        senderNick: String?,
        incomingPeer: String?,
        incomingAddr: String?,
        toPeer: String?,
        toDeviceAddress: String,
        ttl: UByte
    ) {
        try {
            val fromNick = incomingPeer?.let { nicknameResolver?.invoke(it) }
            val toNick = toPeer?.let { nicknameResolver?.invoke(it) }
            val isRelay = (incomingAddr != null || incomingPeer != null)
            
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().logPacketRelayDetailed(
                packetType = typeName,
                senderPeerID = senderPeerID,
                senderNickname = senderNick,
                fromPeerID = incomingPeer,
                fromNickname = fromNick,
                fromDeviceAddress = incomingAddr,
                toPeerID = toPeer,
                toNickname = toNick,
                toDeviceAddress = toDeviceAddress,
                ttl = ttl,
                isRelay = isRelay
            )
        } catch (_: Exception) { 
            // Silently ignore debug logging failures
        }
    }
    
    // Coroutine scope for aux jobs (e.g., transfer fragmentation job tracking)
    private val broadcasterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transferJobs = ConcurrentHashMap<String, Job>()

    // Central scheduler: all outbound sends funnel through here.
    private val scheduler = BluetoothBroadcasterScheduler(
        scope = connectionScope,
        sender = { targetAddress, routed, gattServer, characteristic ->
            sendSingleToTargetInternal(targetAddress, routed, gattServer, characteristic)
        }
    )

    // Track the most recent in-flight client write per device so we can retry on async GATT failure.
    // This is best-effort: because we allow a small burst window, the same device may have multiple
    // writes in flight. We keep a FIFO queue per device to preserve order.
    private val inFlightClientWrites = ConcurrentHashMap<String, ArrayDeque<RoutedPacket>>()

    private fun markClientWriteInFlight(deviceAddress: String, routed: RoutedPacket) {
        val q = inFlightClientWrites.getOrPut(deviceAddress) { ArrayDeque() }
        synchronized(q) { q.addLast(routed) }
    }

    private fun popClientWriteInFlight(deviceAddress: String): RoutedPacket? {
        val q = inFlightClientWrites[deviceAddress] ?: return null
        synchronized(q) {
            val v = q.removeFirstOrNull()
            if (q.isEmpty()) {
                inFlightClientWrites.remove(deviceAddress)
            }
            return v
        }
    }

    // Optional hook: GATT client manager can notify us when a write completes, so we can
    // actively drain the scheduler (burst window).
    fun onClientWriteComplete(deviceAddress: String, status: Int) {
        // Always release one in-flight slot and drain.
        scheduler.onClientWriteComplete(deviceAddress)

        // Pop the completed/failed write from our in-flight FIFO.
        val routed = popClientWriteInFlight(deviceAddress)

        // If the write failed, retry quickly and do NOT drop.
        if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS && routed != null) {
            Log.w(TAG, "Client write failed (status=$status) -> retry in ${CLIENT_WRITE_RETRY_DELAY_MS}ms addr=$deviceAddress")
            rescheduleClientWriteRetry(deviceAddress, routed)
        }
    }

    // Optional flow control hook for client writes (GATT writeCharacteristic)
    // Returns true if a permit was acquired, false if it timed out (best-effort).
    private var clientWriteAwaiter: (suspend (deviceAddress: String) -> Boolean)? = null

    // Optional hook invoked after a WRITE_NO_RESPONSE is issued (some stacks won't fire onCharacteristicWrite).
    private var clientWriteWithoutResponseIssuer: ((deviceAddress: String) -> Unit)? = null

    fun setClientWriteAwaiter(awaiter: suspend (deviceAddress: String) -> Boolean) {
        clientWriteAwaiter = awaiter
    }

    fun setClientWriteWithoutResponseIssuer(issuer: (deviceAddress: String) -> Unit) {
        clientWriteWithoutResponseIssuer = issuer
    }

    fun broadcastPacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val packet = routed.packet
        val isFile = packet.type == MessageType.FILE_TRANSFER.value
        if (isFile) {
            Log.d(TAG, "📤 Broadcasting FILE_TRANSFER: ${packet.payload.size} bytes")
        }

        // Explicit small-packet fast path: if the already-encoded packet fits within the
        // fragmentation threshold, don't invoke the fragmentation pipeline.
        // This avoids cases where unpadding inflates the "fullData" size and causes
        // unnecessary FRAGMENT encoding for small logical messages.
        val encodedSize = packet.toBinaryData()?.size
        val fragThreshold = com.bitchat.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD
        val shouldSkipFragmentation = encodedSize != null && encodedSize <= fragThreshold

        // Prefer caller-provided transferId (e.g., for encrypted media), else derive for FILE_TRANSFER
        val transferId = routed.transferId ?: (if (isFile) sha256Hex(packet.payload) else null)

        // Latency: entering broadcaster (before fragmentation).
        runCatching {
            val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
            LatencyLog.d(
                "bcast_in",
                "type" to typeName,
                "transferId" to transferId,
                "payloadBytes" to packet.payload.size,
                "ttl" to packet.ttl
            )
        }

        // Check if we need to fragment
        if (!shouldSkipFragmentation && fragmentManager != null) {
            val fragments = try {
                fragmentManager.createFragments(packet)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Fragment creation failed: ${e.message}", e)
                if (isFile) {
                    Log.e(TAG, "❌ File fragmentation failed for ${packet.payload.size} byte file")
                }
                return
            }
            if (fragments.size > 1) {
                LatencyLog.d(
                    "frag_needed",
                    "transferId" to transferId,
                    "count" to fragments.size,
                    "type" to (MessageType.fromValue(packet.type)?.name ?: packet.type.toString())
                )

                // Best-effort: decode fragment header for logging.
                runCatching {
                    val fp = FragmentPayload.decode(fragments.first().payload)
                    if (fp != null) {
                        LatencyLog.d(
                            "frag_set",
                            "transferId" to transferId,
                            "fragId" to fp.getFragmentIDString(),
                            "tot" to fp.total,
                            "origType" to fp.originalType
                        )
                    }
                }

                // Transfer progress: start with total fragment count (for UI progress).
                if (transferId != null) {
                    TransferProgressManager.start(transferId, fragments.size)
                }

                val job = connectionScope.launch {
                    // For client connections (we are the GATT client writing to remote server),
                    // we must respect Android's 1-in-flight-write flow control.
                    // Serialize per device: each device gets its own sequential fragment send.

                    val subscribedDevices = connectionTracker.getSubscribedDevices()
                    val connectedDevices = connectionTracker.getConnectedDevices().values
                        .filter { it.isClient && it.gatt != null && it.characteristic != null }

                    // 1) Server-side notifications
                    subscribedDevices.forEach { _ ->
                        if (!isActive) return@launch
                        if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch
                        fragments.forEach { fragment ->
                            if (!isActive) return@launch
                            if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch
                            runCatching {
                                val fp = FragmentPayload.decode(fragment.payload)
                                if (fp != null) {
                                    LatencyLog.d(
                                        "ble_ntf_call",
                                        "transferId" to transferId,
                                        "fragId" to fp.getFragmentIDString(),
                                        "idx" to fp.index,
                                        "tot" to fp.total,
                                        "bytes" to (fragment.toBinaryData()?.size ?: -1)
                                    )
                                }
                            }
                            broadcastSinglePacket(RoutedPacket(fragment, transferId = transferId), gattServer, characteristic)
                        }
                    }

                    // 2) Client-side writes
                    val awaiter = clientWriteAwaiter
                    connectedDevices.forEach { deviceConn ->
                        if (!isActive) return@launch
                        if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                        fragments.forEach { fragment ->
                            if (!isActive) return@launch
                            if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                            // Wait until previous write completes for this device (if available).
                            if (awaiter != null) {
                                try {
                                    awaiter(deviceConn.device.address)
                                } catch (_: Exception) {
                                    // If await fails, fall through and attempt write; broadcaster write call may fail.
                                }
                            }

                            runCatching {
                                val fp = FragmentPayload.decode(fragment.payload)
                                if (fp != null) {
                                    LatencyLog.d(
                                        "ble_tx_call",
                                        "transferId" to transferId,
                                        "addr" to deviceConn.device.address,
                                        "fragId" to fp.getFragmentIDString(),
                                        "idx" to fp.index,
                                        "tot" to fp.total,
                                        "bytes" to (fragment.toBinaryData()?.size ?: -1)
                                    )
                                }
                            }

                            broadcastSinglePacket(RoutedPacket(fragment, transferId = transferId), gattServer, characteristic)
                        }
                    }

                    if (transferId != null) {
                        // We treat progress as fragments produced/scheduled (same behavior as before).
                        TransferProgressManager.complete(transferId, fragments.size)
                    }
                }

                if (transferId != null) {
                    transferJobs[transferId] = job
                    job.invokeOnCompletion { transferJobs.remove(transferId) }
                }
                return
            }
        }
        
        // Send single packet if no fragmentation needed
        if (transferId != null) {
            TransferProgressManager.start(transferId, 1)
        }

        // Latency: single packet path.
        runCatching {
            LatencyLog.d(
                "ble_send_single",
                "transferId" to transferId,
                "type" to (MessageType.fromValue(packet.type)?.name ?: packet.type.toString()),
                "bytes" to (packet.toBinaryData()?.size ?: -1)
            )
        }

        broadcastSinglePacket(routed, gattServer, characteristic)
        if (transferId != null) {
            TransferProgressManager.progress(transferId, 1, 1)
            TransferProgressManager.complete(transferId, 1)
        }
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    /**
     * Send a packet to a specific peer only, without broadcasting.
     * Returns true if a direct path was found and used.
     */
    fun sendPacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        val data = packet.toBinaryData() ?: return false
        val isFile = packet.type == MessageType.FILE_TRANSFER.value
        if (isFile) {
            Log.d(TAG, "📤 Broadcasting FILE_TRANSFER: ${packet.payload.size} bytes")
        }
        // Prefer caller-provided transferId (e.g., for encrypted media), else derive for FILE_TRANSFER
        val transferId = routed.transferId ?: (if (isFile) sha256Hex(packet.payload) else null)
        if (transferId != null) {
            TransferProgressManager.start(transferId, 1)
        }
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }

        // NOTICE: A peer may map to multiple device addresses. Pick the best *single* path.
        val bestClientTarget = connectionTracker.getConnectedDevices().values
            .asSequence()
            .filter { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
            .filter { it.isClient && it.gatt != null && it.characteristic != null }
            .sortedByDescending { connectionTracker.getBestRSSI(it.device.address) ?: Int.MIN_VALUE }
            .firstOrNull()

        if (bestClientTarget != null) {
            if (writeToDeviceConn(bestClientTarget, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, bestClientTarget.device.address, packet.ttl)
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                }
                return true
            }
        }

        // Only use server-side notify when we don't have a client connection.
        val bestServerTarget = connectionTracker.getSubscribedDevices()
            .asSequence()
            .filter { connectionTracker.addressPeerMap[it.address] == targetPeerID }
            .sortedByDescending { connectionTracker.getBestRSSI(it.address) ?: Int.MIN_VALUE }
            .firstOrNull()

        if (bestServerTarget != null) {
            if (notifyDevice(bestServerTarget, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, bestServerTarget.address, packet.ttl)
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                }
                return true
            }
        }

        return false
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }

    
    /**
     * Public entry point for broadcasting - now schedules per target device.
     */
    fun broadcastSinglePacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        if (routed.packet.type.toInt() == 17) {
            debugManager?.measureRTT(0)
        }

        val packet = routed.packet
        packet.toBinaryData() ?: return

        // If not broadcast, try to schedule only to the intended recipient.
        // If we can't resolve the recipient to a direct peer, fall back to broadcast.
        if (packet.recipientID != SpecialRecipients.BROADCAST) {
            val recipientID = packet.recipientID?.let { String(it).replace("\u0000", "").trim() } ?: ""
            val target = resolveTargetByPeerId(recipientID)
            if (target != null) {
                scheduleToTarget(target, routed, gattServer, characteristic)
                return
            }
            // else: unknown recipient -> broadcast fallback
        }

        // Broadcast: build a per-device target list and schedule. Dedupe by address.
        val targets = resolveBroadcastTargets(
            excludeAddress = routed.relayAddress,
            senderId = String(packet.senderID).replace("\u0000", "")
        )
        targets.forEach { t ->
            scheduleToTarget(t, routed, gattServer, characteristic)
        }
    }

    private data class Target(
        val address: String,
        val kind: Kind,
        val device: BluetoothDevice? = null,
        val deviceConn: BluetoothConnectionTracker.DeviceConnection? = null
    ) {
        enum class Kind { CLIENT_WRITE, SERVER_NOTIFY }
    }

    private fun resolveTargetByPeerId(peerId: String): Target? {
        // Prefer client connection (write) over server subscription (notify).
        // NOTICE: pick best RSSI when multiple addresses map to same peer.
        val client = connectionTracker.getConnectedDevices().values
            .asSequence()
            .filter { connectionTracker.addressPeerMap[it.device.address] == peerId }
            .filter { it.isClient && it.gatt != null && it.characteristic != null }
            .sortedByDescending { connectionTracker.getBestRSSI(it.device.address) ?: Int.MIN_VALUE }
            .firstOrNull()
        if (client != null) {
            return Target(address = client.device.address, kind = Target.Kind.CLIENT_WRITE, deviceConn = client)
        }

        val server = connectionTracker.getSubscribedDevices()
            .asSequence()
            .filter { connectionTracker.addressPeerMap[it.address] == peerId }
            .sortedByDescending { connectionTracker.getBestRSSI(it.address) ?: Int.MIN_VALUE }
            .firstOrNull()
        if (server != null) {
            return Target(address = server.address, kind = Target.Kind.SERVER_NOTIFY, device = server)
        }

        return null
    }

    private fun resolveBroadcastTargets(excludeAddress: String?, senderId: String?): List<Target> {
        // NOTICE: Deduplicate by peerId so we only send once to each peer,
        // even if that peer is reachable via multiple device addresses.
        // Unknown peers (peerId == null) are deduped by address (safe fallback).

        val chosenByPeer = LinkedHashMap<String, Target>()
        val chosenByUnknownAddr = LinkedHashMap<String, Target>()

        fun shouldSkip(addr: String): Boolean {
            if (excludeAddress != null && addr == excludeAddress) return true
            if (senderId != null && connectionTracker.addressPeerMap[addr] == senderId) return true
            return false
        }

        fun maybeChoose(peerId: String?, candidate: Target) {
            if (peerId == null) {
                // Peer unknown: de-dupe only by address.
                if (!chosenByUnknownAddr.containsKey(candidate.address)) {
                    chosenByUnknownAddr[candidate.address] = candidate
                }
                return
            }

            val existing = chosenByPeer[peerId]
            if (existing == null) {
                chosenByPeer[peerId] = candidate
                return
            }

            // Prefer client write over server notify.
            if (existing.kind == Target.Kind.SERVER_NOTIFY && candidate.kind == Target.Kind.CLIENT_WRITE) {
                chosenByPeer[peerId] = candidate
                return
            }
            if (existing.kind == Target.Kind.CLIENT_WRITE && candidate.kind == Target.Kind.SERVER_NOTIFY) {
                return
            }

            // Same kind: prefer higher RSSI if available.
            val existingRssi = connectionTracker.getBestRSSI(existing.address) ?: Int.MIN_VALUE
            val candRssi = connectionTracker.getBestRSSI(candidate.address) ?: Int.MIN_VALUE
            if (candRssi > existingRssi) {
                chosenByPeer[peerId] = candidate
            }
        }

        // 1) Consider client connections first (high priority path).
        connectionTracker.getConnectedDevices().values
            .filter { it.isClient && it.gatt != null && it.characteristic != null }
            .forEach { dc ->
                val addr = dc.device.address
                if (shouldSkip(addr)) return@forEach
                val peerId = connectionTracker.addressPeerMap[addr]
                maybeChoose(peerId, Target(address = addr, kind = Target.Kind.CLIENT_WRITE, deviceConn = dc))
            }

        // 2) Consider subscribed devices (notify) only when we don't have client connection for that peer.
        connectionTracker.getSubscribedDevices().forEach { d ->
            val addr = d.address
            if (shouldSkip(addr)) return@forEach
            val peerId = connectionTracker.addressPeerMap[addr]
            maybeChoose(peerId, Target(address = addr, kind = Target.Kind.SERVER_NOTIFY, device = d))
        }

        // Combine deterministic: peers first (stable insertion), then unknown-by-address.
        return chosenByPeer.values.toList() + chosenByUnknownAddr.values.toList()
    }

    private fun rescheduleClientWriteRetry(targetAddress: String, routed: RoutedPacket) {
        // Default retry (async failure) uses a tiny backoff.
        rescheduleClientWriteRetry(targetAddress, routed, CLIENT_WRITE_RETRY_DELAY_MS)
    }

    private fun rescheduleClientWriteRetry(targetAddress: String, routed: RoutedPacket, delayMs: Long) {
        // Re-enqueue at the front. The scheduler is per-device serialized.
        // This ensures we resend this packet before any other already-queued packets for the same address.
        connectionScope.launch {
            if (delayMs > 0) delay(delayMs)
            scheduler.scheduleFront(
                targetAddress = targetAddress,
                priority = BluetoothBroadcasterScheduler.Priority.CLIENT_WRITE,
                routed = routed,
                gattServer = null,
                characteristic = null
            )
        }
    }

    /**
     * Sender invoked by scheduler.
     * - If we have a client connection for targetAddress, we always prefer it.
     * - Only if we do NOT have a client connection do we attempt a server-side notification.
     */
    private suspend fun sendSingleToTargetInternal(
        targetAddress: String,
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val data = routed.packet.toBinaryData() ?: return

        // 1) Prefer client write.
        val deviceConn = connectionTracker.getDeviceConnection(targetAddress)
        if (deviceConn != null && deviceConn.isClient && deviceConn.gatt != null && deviceConn.characteristic != null) {
            // Burst + timeout flow control (if provided). Even if we time out, we still attempt write.
            val awaiter = clientWriteAwaiter
            if (awaiter != null) {
                try { awaiter(targetAddress) } catch (_: Exception) { }
            }

            // Mark in-flight BEFORE issuing the write so we can retry on async failure.
            markClientWriteInFlight(targetAddress, routed)

            val ok = writeToDeviceConn(deviceConn, data)
            if (!ok) {
                // Sync reject: remove in-flight marker (no callback will arrive) and retry ASAP.
                // NOTE: We remove from the *front* only if this is the next expected completion.
                val popped = popClientWriteInFlight(targetAddress)
                if (popped == null || popped !== routed) {
                    if (popped != null) {
                        val q = inFlightClientWrites.getOrPut(targetAddress) { ArrayDeque() }
                        synchronized(q) { q.addFirst(popped) }
                    }
                }

                Log.d(TAG, "Retrying client write after ${CLIENT_WRITE_SYNC_RETRY_DELAY_MS}ms addr=$targetAddress")
                rescheduleClientWriteRetry(targetAddress, routed, CLIENT_WRITE_SYNC_RETRY_DELAY_MS)
            }
            return
        }

        // 2) Fall back to notify only when we don't have a client connection.
        if (gattServer == null || characteristic == null) return
        val device = connectionTracker.getSubscribedDevices().firstOrNull { it.address == targetAddress } ?: return
        notifyDevice(device, data, gattServer, characteristic)
    }

    /**
     * Send data to a single device (server->client)
     */
    private fun notifyDevice(
        device: BluetoothDevice,
        data: ByteArray,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        return try {
            characteristic?.let { char ->
                char.value = data
                try {
                    gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
                } catch (_: SecurityException) {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.removeSubscribedDevice(device)
                connectionTracker.addressPeerMap.remove(device.address)
            }
            false
        }
    }

    /**
     * Send data to a single device (client->server)
     */
    private fun writeToDeviceConn(
        deviceConn: BluetoothConnectionTracker.DeviceConnection,
        data: ByteArray
    ): Boolean {
        return try {
            deviceConn.characteristic?.let { char ->
                char.value = data

                val supportsWnr = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val useWnr = supportsWnr
                char.writeType = if (useWnr) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }

                val result = try {
                    deviceConn.gatt?.writeCharacteristic(char) ?: false
                } catch (_: SecurityException) {
                    false
                }

                if (!result) {
                    // IMPORTANT: We likely already consumed a permit (awaitWritePermit) before calling into here.
                    // If the stack rejects the write (returns false), there will be no callback and no WNR
                    // auto-release, so we must release the permit best-effort to avoid deadlocks.
                    Log.w(TAG, "Client writeCharacteristic() returned false for ${deviceConn.device.address} writeType=${char.writeType}")
                    try { clientWriteWithoutResponseIssuer?.invoke(deviceConn.device.address) } catch (_: Exception) {}
                    return@let false
                }

                if (useWnr) {
                    // Some Android stacks don't deliver onCharacteristicWrite() for WNR.
                    // Notify the client manager so it can auto-release permits.
                    try { clientWriteWithoutResponseIssuer?.invoke(deviceConn.device.address) } catch (_: Exception) {}
                }

                result
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to client connection ${deviceConn.device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                connectionTracker.cleanupDeviceConnection(deviceConn.device.address)
            }
            false
        }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Broadcaster Debug Info ===")
            appendLine("Broadcaster Scope Active: ${broadcasterScope.isActive}")
            appendLine("Connection Scope Active: ${connectionScope.isActive}")
        }
    }

    /**
     * Shutdown the broadcaster actor gracefully
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down BluetoothPacketBroadcaster")
        scheduler.shutdown()
        broadcasterScope.cancel()
        Log.d(TAG, "BluetoothPacketBroadcaster shutdown complete")
    }

    private fun scheduleToTarget(
        target: Target,
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        when (target.kind) {
            Target.Kind.CLIENT_WRITE -> {
                scheduler.schedule(
                    targetAddress = target.address,
                    priority = BluetoothBroadcasterScheduler.Priority.CLIENT_WRITE,
                    routed = routed,
                    gattServer = null,
                    characteristic = null
                )
            }

            Target.Kind.SERVER_NOTIFY -> {
                if (gattServer == null || characteristic == null) return
                scheduler.schedule(
                    targetAddress = target.address,
                    priority = BluetoothBroadcasterScheduler.Priority.SERVER_NOTIFY,
                    routed = routed,
                    gattServer = gattServer,
                    characteristic = characteristic
                )
            }
        }
    }
}
