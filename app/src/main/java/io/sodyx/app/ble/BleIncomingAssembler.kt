package io.sodyx.app.ble

import java.util.LinkedHashMap

/**
 * Reassembles bounded frames and remembers completed transfer identifiers until
 * expiry. Completion means only that a ciphertext was reconstructed; callers
 * must verify the authenticated application envelope before any message effect.
 */
class BleIncomingAssembler(
    private val maxActiveTransfers: Int = 4,
    private val maxCompletedTransfers: Int = 256
) {
    private val active = LinkedHashMap<TransferKey, Assembly>()
    private val completed = LinkedHashMap<TransferKey, Long>()
    private val rejected = LinkedHashMap<TransferKey, Long>()

    init {
        require(maxActiveTransfers > 0) { "At least one active transfer is required" }
        require(maxCompletedTransfers > 0) { "At least one replay entry is required" }
    }

    fun accept(frameBytes: ByteArray, nowEpochMillis: Long): BleReceiveResult {
        expire(nowEpochMillis)
        val frame =
            BleTransferProtocol.decode(frameBytes)
                ?: return BleReceiveResult.Rejected.MalformedFrame
        if (frame.expiresAtEpochMillis <= nowEpochMillis) return BleReceiveResult.Rejected.Expired

        val key = TransferKey(frame.transferId)
        if (completed.containsKey(key)) return BleReceiveResult.Rejected.Replay
        if (rejected.containsKey(key)) return BleReceiveResult.Rejected.InconsistentFrame

        val assembly = active[key] ?: createAssembly(key, frame)
            ?: return BleReceiveResult.Rejected.CapacityExceeded
        if (!assembly.matches(frame)) {
            reject(key, frame.expiresAtEpochMillis)
            return BleReceiveResult.Rejected.InconsistentFrame
        }
        return assembly.add(frame).also { result ->
            when (result) {
                is BleReceiveResult.Completed -> {
                    active.remove(key)
                    completed[key] = frame.expiresAtEpochMillis
                    trimCompleted()
                }
                BleReceiveResult.Rejected.InconsistentFrame -> reject(
                    key,
                    frame.expiresAtEpochMillis
                )
                else -> Unit
            }
        }
    }

    fun expire(nowEpochMillis: Long) {
        active.entries.removeIf { (_, assembly) -> assembly.expiresAtEpochMillis <= nowEpochMillis }
        completed.entries.removeIf { (_, expiry) -> expiry <= nowEpochMillis }
        rejected.entries.removeIf { (_, expiry) -> expiry <= nowEpochMillis }
    }

    private fun createAssembly(key: TransferKey, frame: DecodedBleFrame): Assembly? {
        if (active.size >= maxActiveTransfers) return null
        return Assembly(frame).also { active[key] = it }
    }

    private fun trimCompleted() {
        while (completed.size >
            maxCompletedTransfers
        ) {
            completed.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    private fun reject(key: TransferKey, expiresAtEpochMillis: Long) {
        active.remove(key)
        rejected[key] = expiresAtEpochMillis
        while (rejected.size > maxCompletedTransfers) {
            rejected.entries.iterator().run {
                next()
                remove()
            }
        }
    }
}

sealed interface BleReceiveResult {
    data object InProgress : BleReceiveResult

    data class Completed(val envelope: ReceivedBleEnvelope) : BleReceiveResult

    sealed interface Rejected : BleReceiveResult {
        data object MalformedFrame : Rejected

        data object Expired : Rejected

        data object Replay : Rejected

        data object CapacityExceeded : Rejected

        data object InconsistentFrame : Rejected
    }
}

private class TransferKey(bytes: ByteArray) {
    private val copy = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is TransferKey && copy.contentEquals(other.copy)

    override fun hashCode(): Int = copy.contentHashCode()
}

private class Assembly(firstFrame: DecodedBleFrame) {
    val expiresAtEpochMillis = firstFrame.expiresAtEpochMillis
    private val totalLength = firstFrame.totalLength
    private val fragmentCount = firstFrame.fragmentCount
    private val fragments = arrayOfNulls<ByteArray>(fragmentCount)
    private var receivedBytes = 0
    private var receivedFragments = 0

    fun matches(frame: DecodedBleFrame): Boolean =
        frame.expiresAtEpochMillis == expiresAtEpochMillis &&
            frame.totalLength == totalLength &&
            frame.fragmentCount == fragmentCount

    fun add(frame: DecodedBleFrame): BleReceiveResult {
        val existing = fragments[frame.fragmentIndex]
        if (existing != null) {
            return if (existing.contentEquals(frame.payload)) {
                BleReceiveResult.InProgress
            } else {
                BleReceiveResult.Rejected.InconsistentFrame
            }
        }
        if (receivedBytes + frame.payload.size > totalLength) {
            return BleReceiveResult.Rejected.InconsistentFrame
        }
        fragments[frame.fragmentIndex] = frame.payload
        receivedBytes += frame.payload.size
        receivedFragments += 1
        if (receivedFragments != fragmentCount) return BleReceiveResult.InProgress
        if (receivedBytes != totalLength) return BleReceiveResult.Rejected.InconsistentFrame

        val ciphertext = ByteArray(totalLength)
        var offset = 0
        fragments.forEach { fragment ->
            val value = checkNotNull(fragment)
            value.copyInto(ciphertext, destinationOffset = offset)
            offset += value.size
        }
        return BleReceiveResult.Completed(ReceivedBleEnvelope(ciphertext, expiresAtEpochMillis))
    }
}
