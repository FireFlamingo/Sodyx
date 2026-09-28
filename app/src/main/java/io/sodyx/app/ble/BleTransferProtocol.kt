package io.sodyx.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Bounded framing for an already encrypted application envelope carried over a
 * connected BLE GATT link. It deliberately has no discovery, advertising,
 * identity, or cryptographic-key API.
 */
internal object BleTransferProtocol {
    const val VERSION: Byte = 1
    const val TRANSFER_ID_SIZE = 16
    const val ATT_HEADER_BYTES = 3
    const val FRAME_HEADER_BYTES = 33
    const val MINIMUM_MTU = 64
    const val MAXIMUM_ENVELOPE_BYTES = 128 * 1024
    const val MAXIMUM_FRAGMENTS = 2048

    fun fragment(
        envelope: OpaqueBleEnvelope,
        negotiatedMtu: Int,
        nowEpochMillis: Long,
        random: SecureRandom = SecureRandom()
    ): List<ByteArray> {
        require(negotiatedMtu >= MINIMUM_MTU) { "BLE MTU is too small for bounded framing" }
        require(envelope.expiresAtEpochMillis > nowEpochMillis) { "Envelope has expired" }
        require(envelope.ciphertext.isNotEmpty()) { "Envelope ciphertext is empty" }
        require(envelope.ciphertext.size <= MAXIMUM_ENVELOPE_BYTES) { "Envelope exceeds BLE limit" }

        val payloadCapacity = negotiatedMtu - ATT_HEADER_BYTES - FRAME_HEADER_BYTES
        require(payloadCapacity > 0) { "BLE MTU has no frame payload capacity" }
        val fragmentCount = (envelope.ciphertext.size + payloadCapacity - 1) / payloadCapacity
        require(fragmentCount <= MAXIMUM_FRAGMENTS) { "Envelope needs too many BLE fragments" }

        val transferId = ByteArray(TRANSFER_ID_SIZE).also(random::nextBytes)
        return (0 until fragmentCount).map { fragmentIndex ->
            val start = fragmentIndex * payloadCapacity
            val end = minOf(start + payloadCapacity, envelope.ciphertext.size)
            encodeFrame(
                transferId = transferId,
                expiresAtEpochMillis = envelope.expiresAtEpochMillis,
                totalLength = envelope.ciphertext.size,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                payload = envelope.ciphertext.copyOfRange(start, end)
            )
        }
    }

    fun decode(frame: ByteArray): DecodedBleFrame? {
        if (frame.size <= FRAME_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get() != VERSION) return null
        val transferId = ByteArray(TRANSFER_ID_SIZE).also(buffer::get)
        val expiresAtEpochMillis = buffer.long
        val totalLength = buffer.int
        val fragmentIndex = buffer.short.toInt() and 0xffff
        val fragmentCount = buffer.short.toInt() and 0xffff
        if (
            expiresAtEpochMillis <= 0 ||
            totalLength !in 1..MAXIMUM_ENVELOPE_BYTES ||
            fragmentCount !in 1..MAXIMUM_FRAGMENTS ||
            fragmentIndex !in 0 until fragmentCount
        ) {
            return null
        }
        val payload = ByteArray(buffer.remaining()).also(buffer::get)
        if (payload.isEmpty()) return null
        return DecodedBleFrame(
            transferId = transferId,
            expiresAtEpochMillis = expiresAtEpochMillis,
            totalLength = totalLength,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            payload = payload
        )
    }

    private fun encodeFrame(
        transferId: ByteArray,
        expiresAtEpochMillis: Long,
        totalLength: Int,
        fragmentIndex: Int,
        fragmentCount: Int,
        payload: ByteArray
    ): ByteArray = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .put(VERSION)
        .put(transferId)
        .putLong(expiresAtEpochMillis)
        .putInt(totalLength)
        .putShort(fragmentIndex.toShort())
        .putShort(fragmentCount.toShort())
        .put(payload)
        .array()
}

internal data class DecodedBleFrame(
    val transferId: ByteArray,
    val expiresAtEpochMillis: Long,
    val totalLength: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payload: ByteArray
)

/** Ciphertext and expiry supplied by the application messaging layer. */
data class OpaqueBleEnvelope(val ciphertext: ByteArray, val expiresAtEpochMillis: Long) {
    init {
        require(ciphertext.isNotEmpty()) { "BLE ciphertext is empty" }
    }
}

/** A fully reassembled ciphertext that still requires application-layer verification. */
data class ReceivedBleEnvelope(val ciphertext: ByteArray, val expiresAtEpochMillis: Long)
