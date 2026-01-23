package com.bitchat.android.mesh

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Central scheduling choke point for *all* outbound BLE packets.
 *
 * Now supports per-device scheduling and simple prioritization:
 * - Higher priority: client writes (WRITE_NO_RESPONSE preferred)
 * - Lower priority: server notifications
 *
 * Each device address is serialized, and devices are round-robined for fairness.
 */
class BluetoothBroadcasterScheduler(
    private val scope: CoroutineScope,
    private val sender: suspend (
        targetAddress: String,
        routed: com.bitchat.android.model.RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothBroadcasterScheduler"
        // Don’t spam logcat: log some state at most once per interval.
        private const val LOG_INTERVAL_MS = 2000L
        private const val LOG_LARGE_QUEUE_THRESHOLD = 50
        private const val SENDER_SLOW_WARN_MS = 750L
    }

    @Volatile
    private var lastLogAtMs: Long = 0L

    private fun maybeLog(nowMs: Long, msg: String, force: Boolean = false) {
        if (!force) {
            val last = lastLogAtMs
            if (nowMs - last < LOG_INTERVAL_MS) return
            lastLogAtMs = nowMs
        }
        Log.d(TAG, msg)
    }

    enum class Priority {
        CLIENT_WRITE,
        SERVER_NOTIFY
    }

    private data class SendRequest(
        val targetAddress: String,
        val priority: Priority,
        val routed: com.bitchat.android.model.RoutedPacket,
        val gattServer: BluetoothGattServer?,
        val characteristic: BluetoothGattCharacteristic?
    )

    private data class DeviceQueues(
        val high: ArrayDeque<SendRequest> = ArrayDeque(),
        val low: ArrayDeque<SendRequest> = ArrayDeque(),
        var enqueuedInActiveList: Boolean = false
    )

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Commands to the scheduler loop.
    private sealed interface Cmd {
        data class Enqueue(val req: SendRequest) : Cmd
        data class DropDevice(val address: String) : Cmd
    }

    private val cmds = Channel<Cmd>(capacity = Channel.UNLIMITED)

    init {
        schedulerScope.launch {
            val perDevice = HashMap<String, DeviceQueues>()
            val active = ArrayDeque<String>()

            suspend fun drainOnceIfPossible() {
                // Drain one request from the next active device (round-robin).
                val addr = active.removeFirstOrNull() ?: return
                val q = perDevice[addr] ?: return

                val beforeHigh = q.high.size
                val beforeLow = q.low.size

                val req = q.high.removeFirstOrNull() ?: q.low.removeFirstOrNull()
                if (req != null) {
                    val now = System.currentTimeMillis()
                    // Always log if queues are getting large; otherwise rate-limit.
                    val force = (beforeHigh + beforeLow) >= LOG_LARGE_QUEUE_THRESHOLD
                    maybeLog(
                        now,
                        "dequeue addr=$addr priority=${req.priority} remainingHigh=${q.high.size} remainingLow=${q.low.size} activeDevices=${active.size + 1}",
                        force = force
                    )

                    // Run send; never let exceptions kill the scheduler loop.
                    val start = System.currentTimeMillis()
                    try {
                        sender(req.targetAddress, req.routed, req.gattServer, req.characteristic)
                    } catch (t: Throwable) {
                        Log.e(TAG, "sender failed for addr=$addr priority=${req.priority}: ${t.message}", t)
                    } finally {
                        val took = System.currentTimeMillis() - start
                        if (took >= SENDER_SLOW_WARN_MS) {
                            Log.w(TAG, "sender slow: ${took}ms addr=$addr priority=${req.priority}")
                        }
                    }

                    // Let other coroutines run (esp. BLE callbacks / flow control) before next dequeue.
                    yield()
                }

                // Re-activate device if it still has backlog.
                val stillHas = q.high.isNotEmpty() || q.low.isNotEmpty()
                if (stillHas) {
                    active.addLast(addr)
                    q.enqueuedInActiveList = true
                } else {
                    q.enqueuedInActiveList = false
                    // Opportunistic cleanup.
                    perDevice.remove(addr)
                }
            }

            // Scheduler loop:
            // - Prioritize processing of existing backlog.
            // - Interleave with incoming commands.
            while (true) {
                // Prefer work if available, otherwise block for next command.
                if (active.isNotEmpty()) {
                    // Non-blocking: process any pending enqueues/drops first to keep latency low.
                    while (true) {
                        val cmd = cmds.tryReceive().getOrNull() ?: break
                        when (cmd) {
                            is Cmd.Enqueue -> {
                                val q = perDevice.getOrPut(cmd.req.targetAddress) { DeviceQueues() }
                                when (cmd.req.priority) {
                                    Priority.CLIENT_WRITE -> q.high.addLast(cmd.req)
                                    Priority.SERVER_NOTIFY -> q.low.addLast(cmd.req)
                                }
                                if (!q.enqueuedInActiveList) {
                                    active.addLast(cmd.req.targetAddress)
                                    q.enqueuedInActiveList = true
                                }

                                val now = System.currentTimeMillis()
                                val total = q.high.size + q.low.size
                                val force = total >= LOG_LARGE_QUEUE_THRESHOLD
                                maybeLog(
                                    now,
                                    "enqueue addr=${cmd.req.targetAddress} priority=${cmd.req.priority} qHigh=${q.high.size} qLow=${q.low.size} activeDevices=${active.size}",
                                    force = force
                                )
                            }

                            is Cmd.DropDevice -> {
                                perDevice.remove(cmd.address)
                                active.removeAll { it == cmd.address }
                                maybeLog(System.currentTimeMillis(), "dropDevice addr=${cmd.address} activeDevices=${active.size}")
                            }
                        }
                    }

                    drainOnceIfPossible()
                    continue
                }

                // No active work: await commands.
                when (val cmd = cmds.receiveCatching().getOrNull() ?: return@launch) {
                    is Cmd.Enqueue -> {
                        val q = perDevice.getOrPut(cmd.req.targetAddress) { DeviceQueues() }
                        when (cmd.req.priority) {
                            Priority.CLIENT_WRITE -> q.high.addLast(cmd.req)
                            Priority.SERVER_NOTIFY -> q.low.addLast(cmd.req)
                        }
                        if (!q.enqueuedInActiveList) {
                            active.addLast(cmd.req.targetAddress)
                            q.enqueuedInActiveList = true
                        }
                        val now = System.currentTimeMillis()
                        val total = q.high.size + q.low.size
                        val force = total >= LOG_LARGE_QUEUE_THRESHOLD
                        maybeLog(
                            now,
                            "enqueue(addrIdle) addr=${cmd.req.targetAddress} priority=${cmd.req.priority} qHigh=${q.high.size} qLow=${q.low.size} activeDevices=${active.size}",
                            force = force
                        )
                    }

                    is Cmd.DropDevice -> {
                        perDevice.remove(cmd.address)
                        active.removeAll { it == cmd.address }
                        maybeLog(System.currentTimeMillis(), "dropDevice addr=${cmd.address} activeDevices=${active.size}")
                    }
                }
            }
        }
    }

    fun schedule(
        targetAddress: String,
        priority: Priority,
        routed: com.bitchat.android.model.RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val req = SendRequest(targetAddress, priority, routed, gattServer, characteristic)
        if (!cmds.trySend(Cmd.Enqueue(req)).isSuccess) {
            scope.launch { cmds.send(Cmd.Enqueue(req)) }
        }
    }

    fun dropDevice(address: String) {
        if (!cmds.trySend(Cmd.DropDevice(address)).isSuccess) {
            scope.launch { cmds.send(Cmd.DropDevice(address)) }
        }
    }

    fun shutdown() {
        runCatching { cmds.close() }
        schedulerScope.cancel()
    }
}
