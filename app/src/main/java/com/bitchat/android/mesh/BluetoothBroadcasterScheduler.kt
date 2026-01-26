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
 * Supports per-device scheduling and simple prioritization:
 * - Higher priority: client writes (WRITE_NO_RESPONSE preferred)
 * - Lower priority: server notifications
 *
 * Semantics:
 * - For SERVER_NOTIFY: best-effort, treated as "immediate" (no completion callback)
 * - For CLIENT_WRITE: windowed in-flight scheduling; caller must notify completion via
 *   [onClientWriteComplete] so we can actively drain queued packets.
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

        // NOTICE: Allow a small burst window.
        // Some stacks still only accept 1 in-flight write; those will reject extra writes quickly.
        // We rely on the broadcaster's retry to handle those failures without dropping.
        private const val CLIENT_WRITE_WINDOW = 4
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
        var enqueuedInActiveList: Boolean = false,
        var inFlightClientWrites: Int = 0
    )

    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Commands to the scheduler loop.
    private sealed interface Cmd {
        data class Enqueue(val req: SendRequest, val atFront: Boolean = false) : Cmd
        data class DropDevice(val address: String) : Cmd
        data class ClientWriteComplete(val address: String) : Cmd
    }

    private val cmds = Channel<Cmd>(capacity = Channel.UNLIMITED)

    init {
        schedulerScope.launch {
            val perDevice = HashMap<String, DeviceQueues>()
            val active = ArrayDeque<String>()

            fun enqueueActiveIfNeeded(addr: String, q: DeviceQueues) {
                if (!q.enqueuedInActiveList) {
                    active.addLast(addr)
                    q.enqueuedInActiveList = true
                }
            }

            suspend fun tryIssueFromDevice(addr: String, q: DeviceQueues) {
                // Try to start writes/notifications for this device as much as capacity allows.
                // - CLIENT_WRITE: allow up to CLIENT_WRITE_WINDOW in flight
                // - SERVER_NOTIFY: always allowed (no callback)

                while (true) {
                    val req = q.high.firstOrNull() ?: q.low.firstOrNull() ?: break

                    // Enforce window for client writes.
                    if (req.priority == Priority.CLIENT_WRITE && q.inFlightClientWrites >= CLIENT_WRITE_WINDOW) {
                        break
                    }

                    // Dequeue now (optimistic). If send throws, we treat it as failure and requeue.
                    val next = if (q.high.isNotEmpty()) q.high.removeFirst() else q.low.removeFirst()

                    val now = System.currentTimeMillis()
                    val force = (q.high.size + q.low.size) >= LOG_LARGE_QUEUE_THRESHOLD
                    maybeLog(
                        now,
                        "dequeue addr=$addr priority=${next.priority} remainingHigh=${q.high.size} remainingLow=${q.low.size} inFlight=${q.inFlightClientWrites}",
                        force = force
                    )

                    if (next.priority == Priority.CLIENT_WRITE) {
                        q.inFlightClientWrites += 1
                    }

                    val start = System.currentTimeMillis()
                    var success = true
                    try {
                        sender(next.targetAddress, next.routed, next.gattServer, next.characteristic)
                    } catch (t: Throwable) {
                        success = false
                        Log.e(TAG, "sender failed for addr=$addr priority=${next.priority}: ${t.message}", t)
                    } finally {
                        val took = System.currentTimeMillis() - start
                        if (took >= SENDER_SLOW_WARN_MS) {
                            Log.w(TAG, "sender slow: ${took}ms addr=$addr priority=${next.priority}")
                        }
                    }

                    if (next.priority == Priority.SERVER_NOTIFY) {
                        // No callback: treat as completed immediately.
                    } else {
                        // For client writes, completion must be reported via onClientWriteComplete().
                        // If sender failed synchronously, roll back the in-flight count and requeue.
                        if (!success) {
                            q.inFlightClientWrites = (q.inFlightClientWrites - 1).coerceAtLeast(0)
                            q.high.addFirst(next) // preserve ordering on failure
                        }
                    }

                    // Let BLE callbacks run before we attempt more.
                    yield()
                }
            }

            suspend fun drainRoundRobinOnce() {
                val addr = active.removeFirstOrNull() ?: return
                val q = perDevice[addr] ?: return

                // Make sure we try to drain this device as much as possible.
                tryIssueFromDevice(addr, q)

                val stillHasQueue = q.high.isNotEmpty() || q.low.isNotEmpty()
                val shouldStayActive = stillHasQueue && (q.inFlightClientWrites < CLIENT_WRITE_WINDOW)

                if (shouldStayActive) {
                    active.addLast(addr)
                    q.enqueuedInActiveList = true
                } else {
                    q.enqueuedInActiveList = false
                    // Opportunistic cleanup when idle.
                    if (!stillHasQueue && q.inFlightClientWrites == 0) {
                        perDevice.remove(addr)
                    }
                }
            }

            while (true) {
                // Prefer work if available, otherwise block for next command.
                if (active.isNotEmpty()) {
                    while (true) {
                        val cmd = cmds.tryReceive().getOrNull() ?: break
                        when (cmd) {
                            is Cmd.Enqueue -> {
                                val q = perDevice.getOrPut(cmd.req.targetAddress) { DeviceQueues() }
                                when (cmd.req.priority) {
                                    Priority.CLIENT_WRITE -> if (cmd.atFront) q.high.addFirst(cmd.req) else q.high.addLast(cmd.req)
                                    Priority.SERVER_NOTIFY -> if (cmd.atFront) q.low.addFirst(cmd.req) else q.low.addLast(cmd.req)
                                }
                                enqueueActiveIfNeeded(cmd.req.targetAddress, q)

                                val now = System.currentTimeMillis()
                                val total = q.high.size + q.low.size
                                val force = total >= LOG_LARGE_QUEUE_THRESHOLD
                                maybeLog(
                                    now,
                                    "enqueue addr=${cmd.req.targetAddress} priority=${cmd.req.priority} atFront=${cmd.atFront} qHigh=${q.high.size} qLow=${q.low.size} inFlight=${q.inFlightClientWrites} activeDevices=${active.size}",
                                    force = force
                                )
                            }

                            is Cmd.DropDevice -> {
                                perDevice.remove(cmd.address)
                                active.removeAll { it == cmd.address }
                                maybeLog(System.currentTimeMillis(), "dropDevice addr=${cmd.address} activeDevices=${active.size}")
                            }

                            is Cmd.ClientWriteComplete -> {
                                val q = perDevice.getOrPut(cmd.address) { DeviceQueues() }
                                q.inFlightClientWrites = (q.inFlightClientWrites - 1).coerceAtLeast(0)
                                enqueueActiveIfNeeded(cmd.address, q)
                                maybeLog(System.currentTimeMillis(), "clientWriteComplete addr=${cmd.address} inFlight=${q.inFlightClientWrites}")
                            }
                        }
                    }

                    drainRoundRobinOnce()
                    continue
                }

                // No active work: await commands.
                when (val cmd = cmds.receiveCatching().getOrNull() ?: return@launch) {
                    is Cmd.Enqueue -> {
                        val q = perDevice.getOrPut(cmd.req.targetAddress) { DeviceQueues() }
                        when (cmd.req.priority) {
                            Priority.CLIENT_WRITE -> if (cmd.atFront) q.high.addFirst(cmd.req) else q.high.addLast(cmd.req)
                            Priority.SERVER_NOTIFY -> if (cmd.atFront) q.low.addFirst(cmd.req) else q.low.addLast(cmd.req)
                        }
                        enqueueActiveIfNeeded(cmd.req.targetAddress, q)
                        val now = System.currentTimeMillis()
                        val total = q.high.size + q.low.size
                        val force = total >= LOG_LARGE_QUEUE_THRESHOLD
                        maybeLog(
                            now,
                            "enqueue(addrIdle) addr=${cmd.req.targetAddress} priority=${cmd.req.priority} atFront=${cmd.atFront} qHigh=${q.high.size} qLow=${q.low.size} inFlight=${q.inFlightClientWrites} activeDevices=${active.size}",
                            force = force
                        )
                    }

                    is Cmd.DropDevice -> {
                        perDevice.remove(cmd.address)
                        active.removeAll { it == cmd.address }
                        maybeLog(System.currentTimeMillis(), "dropDevice addr=${cmd.address} activeDevices=${active.size}")
                    }

                    is Cmd.ClientWriteComplete -> {
                        val q = perDevice.getOrPut(cmd.address) { DeviceQueues() }
                        q.inFlightClientWrites = (q.inFlightClientWrites - 1).coerceAtLeast(0)
                        enqueueActiveIfNeeded(cmd.address, q)
                        maybeLog(System.currentTimeMillis(), "clientWriteComplete(addrIdle) addr=${cmd.address} inFlight=${q.inFlightClientWrites}")
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

    fun scheduleFront(
        targetAddress: String,
        priority: Priority,
        routed: com.bitchat.android.model.RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val req = SendRequest(targetAddress, priority, routed, gattServer, characteristic)
        if (!cmds.trySend(Cmd.Enqueue(req, atFront = true)).isSuccess) {
            scope.launch { cmds.send(Cmd.Enqueue(req, atFront = true)) }
        }
    }

    /**
        * Must be called when a client write finishes (success or failure).
        * This drives active draining of the per-device queue.
        */
    fun onClientWriteComplete(address: String) {
        if (!cmds.trySend(Cmd.ClientWriteComplete(address)).isSuccess) {
            scope.launch { cmds.send(Cmd.ClientWriteComplete(address)) }
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
