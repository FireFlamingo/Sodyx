package io.sodyx.app.ble

import java.security.SecureRandom

/**
 * A connected GATT characteristic supplied by the Android-specific integration.
 * A successful write is only a local link outcome; it is never a message-delivery
 * acknowledgement.
 */
fun interface BleGattFrameSink {
    fun write(frame: ByteArray): BleGattWriteResult
}

sealed interface BleGattWriteResult {
    data object AcceptedByGatt : BleGattWriteResult

    data class Rejected(val reason: String) : BleGattWriteResult
}

/**
 * Direct transfer coordinator. Discovery and advertising are intentionally not
 * implemented here because a private rotating-recognition key contract has not
 * yet been reviewed for Sodyx.
 */
class BleDirectTransport(
    private val assembler: BleIncomingAssembler = BleIncomingAssembler(),
    private val random: SecureRandom = SecureRandom()
) {
    val mode: BleTransportMode = BleTransportMode.ManualPeerOnly

    fun send(
        envelope: OpaqueBleEnvelope,
        negotiatedMtu: Int,
        nowEpochMillis: Long,
        sink: BleGattFrameSink
    ): BleSendResult {
        val frames = try {
            BleTransferProtocol.fragment(envelope, negotiatedMtu, nowEpochMillis, random)
        } catch (exception: IllegalArgumentException) {
            return BleSendResult.Rejected(exception.message ?: "Invalid BLE transfer")
        }
        frames.forEachIndexed { index, frame ->
            when (val result = sink.write(frame)) {
                BleGattWriteResult.AcceptedByGatt -> Unit
                is BleGattWriteResult.Rejected -> return BleSendResult.WriteFailed(
                    index,
                    result.reason
                )
            }
        }
        return BleSendResult.QueuedToGatt(frames.size)
    }

    fun receive(frame: ByteArray, nowEpochMillis: Long): BleReceiveResult =
        assembler.accept(frame, nowEpochMillis)
}

enum class BleTransportMode {
    /** Explicit, non-advertising manual GATT connection used for device validation only. */
    ManualPeerOnly
}

sealed interface BleSendResult {
    /** Frames were accepted by the local GATT stack; peer receipt is not proven. */
    data class QueuedToGatt(val frameCount: Int) : BleSendResult

    data class WriteFailed(val frameIndex: Int, val reason: String) : BleSendResult

    data class Rejected(val reason: String) : BleSendResult
}
