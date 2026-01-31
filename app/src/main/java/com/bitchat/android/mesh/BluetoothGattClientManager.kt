package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.Job
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.DebugScanResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages GATT client operations, scanning, and client-side connections
 */
class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {

    companion object {
        private const val TAG = "BluetoothGattClientManager"
        // NOTICE:
        // We allow a small in-flight burst window (see scheduler) and use callbacks to actively drain.
        private const val WRITE_BURST_LIMIT = 8
        private const val WRITE_BURST_TIMEOUT_MS = 20L
        private const val WNR_AUTO_RELEASE_DELAY_MS = 10L
    }

    // Optional callback to notify higher layers that a write completed.
    // status == BluetoothGatt.GATT_SUCCESS means success, otherwise failure.
    private var onClientWriteCallback: ((deviceAddress: String, status: Int) -> Unit)? = null

    fun setOnClientWriteCallback(cb: ((deviceAddress: String, status: Int) -> Unit)?) {
        onClientWriteCallback = cb
    }

    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    // Per-device flow control for GATT writes.
    // Android BLE supports one in-flight write per BluetoothGatt, but in practice some stacks
    // may not deliver onCharacteristicWrite for WRITE_NO_RESPONSE.
    // We implement a burst-based gate to avoid deadlocks:
    // - allow up to WRITE_BURST_LIMIT outstanding "permits" per device
    // - if unavailable for WRITE_BURST_TIMEOUT_MS, timeout and proceed (best-effort)
    private val writePermits = mutableMapOf<String, Semaphore>()
    private val writePermitsLock = Mutex()

    private suspend fun getPermit(deviceAddress: String): Semaphore {
        writePermitsLock.lock()
        try {
            return writePermits.getOrPut(deviceAddress) { Semaphore(WRITE_BURST_LIMIT) }
        } finally {
            writePermitsLock.unlock()
        }
    }

    suspend fun awaitWritePermit(deviceAddress: String): Boolean {
        val permit = getPermit(deviceAddress)
        return try {
            withTimeout(WRITE_BURST_TIMEOUT_MS) {
                permit.acquire()
                try {
                    Log.d(TAG, "awaitWritePermit acquired for $deviceAddress available=${permit.availablePermits}")
                } catch (_: Exception) { }
                true
            }
        } catch (_: Exception) {
            Log.w(TAG, "awaitWritePermit timeout for $deviceAddress after ${WRITE_BURST_TIMEOUT_MS}ms")
            false
        }
    }

    /**
     * For WRITE_NO_RESPONSE we may never get onCharacteristicWrite().
     * Call this after issuing the write to prevent the permit pool from draining permanently.
     *
     * Also: the broadcaster scheduler typically waits for a completion callback to drain
     * the next queued packet. For WNR this callback may never arrive, so we send a
     * best-effort synthetic completion after a short delay.
     */
    fun noteWriteWithoutResponseIssued(deviceAddress: String) {
        connectionScope.launch {
            delay(WNR_AUTO_RELEASE_DELAY_MS)
            try {
                releaseWritePermit(deviceAddress, reason = "WNR_AUTO")
            } catch (_: Exception) {
            }

            // Best-effort: unblock scheduler drain when no callback will come.
            // If onCharacteristicWrite() also fires, the semaphore release is capped and the
            // broadcaster should handle duplicate completions safely.
            try {
                onClientWriteCallback?.invoke(deviceAddress, BluetoothGatt.GATT_SUCCESS)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun releaseWritePermit(deviceAddress: String, reason: String = "UNKNOWN") {
        writePermitsLock.lock()
        try {
            val sem = writePermits[deviceAddress]
            if (sem != null) {
                // Don't let releases inflate the semaphore beyond the configured capacity.
                // Both onCharacteristicWrite and WNR_AUTO can fire for nearby writes.
                if (sem.availablePermits < WRITE_BURST_LIMIT) {
                    sem.release()
                }
                try {
                    Log.d(TAG, "releaseWritePermit($reason) for $deviceAddress available=${sem.availablePermits}")
                } catch (_: Exception) { }
            } else {
                Log.d(TAG, "releaseWritePermit($reason) skipped; no semaphore for $deviceAddress")
            }
        } finally {
            writePermitsLock.unlock()
        }
    }

    private suspend fun removeWritePermit(deviceAddress: String) {
        writePermitsLock.lock()
        try {
            writePermits.remove(deviceAddress)
        } finally {
            writePermitsLock.unlock()
        }
    }

    /**
     * Public: Connect to a device by MAC address (for debug UI)
     */
    fun connectToAddress(deviceAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            false
        }
    }

    // Scan management
    private var scanCallback: ScanCallback? = null
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts
    
    // RSSI monitoring state
    private var rssiMonitoringJob: Job? = null
    
    // State management
    private var isActive = false
    
    // If a scan-start was delayed due to rate limiting, keep a handle so we can cancel it.
    private var delayedScanStartJob: Job? = null

    /**
     * Start client manager
     */
    fun start(): Boolean {
        // Respect debug setting
        try {
            if (!com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value) {
                Log.i(TAG, "Client start skipped: GATT Client disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }
        
        isActive = true

        connectionScope.launch {
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Using power-aware duty cycling")
            } else {
                startScanning()
            }

            // Start RSSI monitoring
            startRSSIMonitoring()
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            return
        }

        isActive = false
        
        connectionScope.launch {
            // Disconnect all client connections decisively
            try {
                val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
                conns.forEach { dc ->
                    try { dc.gatt?.disconnect() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped")
        }
    }
    
    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start periodic RSSI monitoring for all client connections
     */
    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {
                    // Request RSSI from all client connections
                    val connectedDevices = connectionTracker.getConnectedDevices()
                    connectedDevices.values.filter { it.isClient && it.gatt != null }.forEach { deviceConn ->
                        try {
                            Log.d(TAG, "Requesting RSSI from ${deviceConn.device.address}")
                            deviceConn.gatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to request RSSI from ${deviceConn.device.address}: ${e.message}")
                        }
                    }
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in RSSI monitoring: ${e.message}")
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }
    
    /**
     * Stop RSSI monitoring
     */
    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        // Respect debug setting
        val enabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        val autoScanEnabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().autoScanEnabled.value } catch (_: Exception) { true }
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive || !enabled || !autoScanEnabled) return

        // Cancel any previous delayed starts; we're attempting a real start now.
        delayedScanStartJob?.cancel()
        delayedScanStartJob = null

        // Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            return
        }
        
        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")

            // Schedule delayed scan start (cancelable)
            delayedScanStartJob?.cancel()
            delayedScanStartJob = connectionScope.launch {
                delay(remainingWait)
                val autoScanEnabledDelayed = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().autoScanEnabled.value } catch (_: Exception) { true }
                if (isActive && !isCurrentlyScanning && autoScanEnabledDelayed) {
                    startScanning()
                }
            }
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .build()
        
        val scanFilters = listOf(scanFilter) 
        
        Log.d(TAG, "Starting BLE scan with target service UUID: ${AppConstants.Mesh.Gatt.SERVICE_UUID}")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Log.d(TAG, "Scan result received: ${result.device.address}")
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results received: ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()

                when (errorCode) {
                    1 -> Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
                    2 -> Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED") 
                    3 -> Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
                    4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED")
                    5 -> Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                    6 -> {
                        Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                        Log.w(TAG, "Scan failed due to rate limiting - will retry after delay")
                        connectionScope.launch {
                            delay(10000) // Wait 10 seconds before retrying
                            if (isActive) {
                                startScanning()
                            }
                        }
                    }
                    else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
                }
            }
        }
        
        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true
            
            bleScanner.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
            Log.d(TAG, "BLE scan started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isCurrentlyScanning = false
        }
    }
    
    /**
     * Stop scanning
     */
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        // Always cancel scheduled delayed scan starts when we stop.
        delayedScanStartJob?.cancel()
        delayedScanStartJob = null

        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null) return
        
        if (isCurrentlyScanning) {
            try {
                scanCallback?.let { 
                    bleScanner.stopScan(it)
                    Log.d(TAG, "BLE scan stopped successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }
            
            isCurrentlyScanning = false
            lastScanStopTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord

        // CRITICAL: Only process devices that have our service UUID
        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == AppConstants.Mesh.Gatt.SERVICE_UUID } == true
        if (!hasOurService) {
            return
        }

        // Store RSSI regardless so debug UI can show it
        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        // Publish scan result to debug UI buffer
        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = device.name,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = null // peerID unknown at scan time
                )
            )
        } catch (_: Exception) { }

        // If "Auto connect" is disabled, stop here (still keep scan results / RSSI).
        val autoConnectEnabled = try { DebugSettingsManager.getInstance().autoConnectEnabled.value } catch (_: Exception) { true }
        if (!autoConnectEnabled) {
            return
        }

        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            // Even if we skip connecting, still publish scan result to debug UI
            try {
                val pid: String? = null // We don't know peerID until packet exchange
                DebugSettingsManager.getInstance().addScanResult(
                    DebugScanResult(
                        deviceName = device.name,
                        deviceAddress = deviceAddress,
                        rssi = rssi,
                        peerID = pid
                    )
                )
            } catch (_: Exception) { }
            return
        }
        
        // Check if already connected OR already attempting to connect
        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }
        
        // Check if connection attempt is allowed
        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
            return
        }
        
        if (connectionTracker.isConnectionLimitReached()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            return
        }
        
        // Add pending connection and start connection
        if (connectionTracker.addPendingConnection(deviceAddress)) {
            connectToDevice(device, rssi)
        }
    }
    
    /**
     * Connect to a device as GATT client
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int) {
        if (!permissionManager.hasBluetoothPermissions()) return

        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress")
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: Characteristic write successful to $deviceAddress")

                    // If this write was a ping packet, try to record RTT (best-effort)
                    try {
                        val charValue = characteristic?.value
                        if (charValue != null) {
                            val pkt = BitchatPacket.fromBinaryData(charValue)
                            if (pkt != null && pkt.type == MessageType.PING.value) {
                                // Find peer id for this device address
                                val peerId = connectionTracker.addressPeerMap[deviceAddress]
                                if (peerId != null) {
                                    val rtt = NetworkMetricsManager.recordPongByPeer(peerId)
                                    if (rtt != null) {
                                        Log.i(TAG, "Client: RTT recorded for peer $peerId: ${rtt} ms")
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) { /* ignore */ }

                } else {
                    Log.e(
                        TAG,
                        "Client: Characteristic write failed to $deviceAddress, status: $status"
                    )
                }

                // Release flow-control permit regardless of success/failure.
                connectionScope.launch {
                    try {
                        releaseWritePermit(deviceAddress, reason = "onCharacteristicWrite")
                    } catch (_: Exception) {
                    }
                }

                // Notify the write completion to the registered callback
                onClientWriteCallback?.invoke(deviceAddress, status)
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    // Request a larger MTU. Must be done before any data transfer.
                    connectionScope.launch {
                        delay(200) // A small delay can improve reliability of MTU request.
                        gatt.requestMtu(517)
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                    }

                    // On disconnect, drop permit state to avoid leaking.
                    connectionScope.launch {
                        try { removeWritePermit(deviceAddress) } catch (_: Exception) {}
                    }

                    // Notify higher layers about device disconnection to update direct flags
                    delegate?.onDeviceDisconnected(gatt.device)

                    connectionScope.launch {
                        delay(500) // CLEANUP_DELAY
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing GATT: ${e.message}")
                        }
                    }
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deviceAddress = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $deviceAddress to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU successfully negotiated for $deviceAddress. Discovering services.")
                    
                    // Now that MTU is set, connection is fully ready.
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    //connectionTracker.removePendingConnection(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }
                            
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                
                                connectionScope.launch {
                                    delay(200)
                                    Log.i(TAG, "Client: Connection setup complete for $deviceAddress")
                                    delegate?.onDeviceConnected(device)
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $deviceAddress")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $deviceAddress")
                    gatt.disconnect()
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.i(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                }
            }
            
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val deviceAddress = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $deviceAddress: $rssi dBm")
                    
                    // Update the connection tracker with new RSSI value
                    connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $deviceAddress, status: $status")
                }
            }
        }
        
        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
                // connectionTracker.removePendingConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
            // connectionTracker.removePendingConnection(deviceAddress)
        }
    }
    
    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        // Respect debug setting
        val enabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
        val autoScanEnabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().autoScanEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) return

        // If auto scan is disabled, make sure we stop and don't restart.
        if (!autoScanEnabled) {
            stopScanningForDebugToggle()
            return
        }

        connectionScope.launch {
            stopScanning()
            delay(1000) // Extra delay to avoid rate limiting

            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")
                // Duty cycle will handle scanning
            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                startScanning()
            }
        }
    }

    /**
     * Public: Immediately stop any active scanning (used when debug toggles disable auto scan).
     * Safe to call even if not currently scanning.
     */
    fun stopScanningForDebugToggle() {
        connectionScope.launch {
            // Ensure any delayed scan restarts are cancelled too.
            delayedScanStartJob?.cancel()
            delayedScanStartJob = null

            stopScanning()
        }
    }
}
