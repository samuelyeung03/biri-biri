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
                if (isFile) {
                    Log.d(TAG, "🔀 File needs ${fragments.size} fragments")
                }
                Log.d(TAG, "Fragmenting packet into ${fragments.size} fragments")
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

                    // 1) Server-side notifications: best-effort (no write callback). Keep existing behavior.
                    subscribedDevices.forEach { _ ->
                        if (!isActive) return@launch
                        if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch
                        fragments.forEach { fragment ->
                            if (!isActive) return@launch
                            if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch
                            broadcastSinglePacket(RoutedPacket(fragment, transferId = transferId), gattServer, characteristic)
                        }
                    }

                    // 2) Client-side writes: sequential per device with await.
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

        // Prefer client writes (Write Without Response) if we have a client connection.
        val clientTarget = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == targetPeerID && it.isClient && it.gatt != null && it.characteristic != null }
        if (clientTarget != null) {
            if (writeToDeviceConn(clientTarget, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, clientTarget.device.address, packet.ttl)
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                }
                return true
            }
        }

        // Only use server-side notify when we don't have a client connection.
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (serverTarget != null) {
            if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, serverTarget.address, packet.ttl)
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
        val client = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == peerId && it.isClient && it.gatt != null && it.characteristic != null }
        if (client != null) {
            return Target(address = client.device.address, kind = Target.Kind.CLIENT_WRITE, deviceConn = client)
        }

        val server = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == peerId }
        if (server != null) {
            return Target(address = server.address, kind = Target.Kind.SERVER_NOTIFY, device = server)
        }

        return null
    }

    private fun resolveBroadcastTargets(excludeAddress: String?, senderId: String?): List<Target> {
        val out = LinkedHashMap<String, Target>()

        // 1) Client connections first (high priority path).
        connectionTracker.getConnectedDevices().values
            .filter { it.isClient && it.gatt != null && it.characteristic != null }
            .forEach { dc ->
                val addr = dc.device.address
                if (excludeAddress != null && addr == excludeAddress) return@forEach
                if (senderId != null && connectionTracker.addressPeerMap[addr] == senderId) return@forEach
                out[addr] = Target(address = addr, kind = Target.Kind.CLIENT_WRITE, deviceConn = dc)
            }

        // 2) Subscribed devices (notify) only when we don't have a client connection.
        connectionTracker.getSubscribedDevices().forEach { d ->
            val addr = d.address
            if (excludeAddress != null && addr == excludeAddress) return@forEach
            if (senderId != null && connectionTracker.addressPeerMap[addr] == senderId) return@forEach
            if (out.containsKey(addr)) return@forEach
            out[addr] = Target(address = addr, kind = Target.Kind.SERVER_NOTIFY, device = d)
        }

        return out.values.toList()
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
            writeToDeviceConn(deviceConn, data)
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

                if (result && useWnr) {
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
}
