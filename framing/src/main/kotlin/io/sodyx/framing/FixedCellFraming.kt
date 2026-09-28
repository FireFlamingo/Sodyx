package io.sodyx.framing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.LinkedHashMap

/**
 * Fixed wire-format properties. A cell-size change changes the parsing boundary,
 * so it requires a new [VERSION] rather than a configuration switch.
 */
object FrameFormat {
    const val VERSION: Int = 1
    const val CELL_SIZE_BYTES: Int = 1024
    const val HEADER_SIZE_BYTES: Int = 80
    const val PAYLOAD_SIZE_BYTES: Int = CELL_SIZE_BYTES - HEADER_SIZE_BYTES
    const val MESSAGE_ID_SIZE_BYTES: Int = 16
    const val MAX_BINDING_SIZE_BYTES: Int = 32
    const val MAX_CIPHERTEXT_SIZE_BYTES: Int = 1_048_576
    const val MAX_CELL_COUNT: Int =
        (MAX_CIPHERTEXT_SIZE_BYTES + PAYLOAD_SIZE_BYTES - 1) / PAYLOAD_SIZE_BYTES

    private val magic =
        byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'Y'.code.toByte(), 'X'.code.toByte())

    internal fun writeMagic(target: ByteBuffer) = target.put(magic)

    internal fun hasExpectedMagic(source: ByteBuffer): Boolean {
        val candidate = ByteArray(magic.size)
        source.get(candidate)
        return candidate.contentEquals(magic)
    }
}

/** Opaque output of the E2EE layer. This module never decrypts [ciphertext]. */
class OpaqueEncryptedEnvelope(ciphertext: ByteArray, e2eeBinding: ByteArray = ByteArray(0)) {
    val ciphertext: ByteArray = ciphertext.copyOf()
    val e2eeBinding: ByteArray = e2eeBinding.copyOf()

    init {
        require(this.ciphertext.isNotEmpty()) { "Ciphertext must not be empty." }
        require(this.ciphertext.size <= FrameFormat.MAX_CIPHERTEXT_SIZE_BYTES) {
            "Ciphertext exceeds the framing maximum."
        }
        require(this.e2eeBinding.size <= FrameFormat.MAX_BINDING_SIZE_BYTES) {
            "E2EE binding exceeds the framing maximum."
        }
    }
}

/**
 * The E2EE owner verifies that [binding] and [ciphertext] belong together before
 * the ciphertext is released. It is intentionally an interface, not a crypto
 * implementation.
 */
fun interface EnvelopeAuthenticator {
    fun verify(binding: ByteArray, ciphertext: ByteArray): Boolean
}

class CellMessageId(value: ByteArray) {
    val value: ByteArray = value.copyOf()

    init {
        require(this.value.size == FrameFormat.MESSAGE_ID_SIZE_BYTES) {
            "Message IDs must be exactly ${FrameFormat.MESSAGE_ID_SIZE_BYTES} bytes."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CellMessageId && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CellMessageId(redacted)"
}

data class FrameRequest(val messageId: CellMessageId, val expiresAtEpochMillis: Long)

data class FramedCell(val wireBytes: ByteArray) {
    init {
        require(wireBytes.size == FrameFormat.CELL_SIZE_BYTES) {
            "Cells must have the fixed wire size."
        }
    }
}

sealed interface FrameError {
    data object InvalidWireSize : FrameError
    data object InvalidMagic : FrameError
    data object UnsupportedVersion : FrameError
    data object InvalidReservedBytes : FrameError
    data object InvalidBindingLength : FrameError
    data object InvalidCellCount : FrameError
    data object InvalidCellIndex : FrameError
    data object InvalidCiphertextLength : FrameError
    data object InvalidPadding : FrameError
    data object Expired : FrameError
    data object DuplicateConflict : FrameError
    data object MetadataConflict : FrameError
    data object CapacityExceeded : FrameError
    data object AuthenticationFailed : FrameError
}

sealed interface ReassemblyResult {
    data class Accepted(
        val messageId: CellMessageId,
        val receivedCellCount: Int,
        val totalCellCount: Int
    ) : ReassemblyResult

    data class Duplicate(val messageId: CellMessageId) : ReassemblyResult

    data class Completed(val envelope: CompletedEnvelope) : ReassemblyResult

    data class Rejected(val error: FrameError) : ReassemblyResult
}

class CompletedEnvelope internal constructor(
    val messageId: CellMessageId,
    ciphertext: ByteArray,
    e2eeBinding: ByteArray
) {
    val ciphertext: ByteArray = ciphertext.copyOf()
    val e2eeBinding: ByteArray = e2eeBinding.copyOf()
}

data class ReassemblyLimits(
    val maximumIncompleteMessages: Int = 16,
    val maximumBufferedPayloadBytes: Int = 2 * FrameFormat.MAX_CIPHERTEXT_SIZE_BYTES
) {
    init {
        require(maximumIncompleteMessages > 0) {
            "At least one incomplete message must be allowed."
        }
        require(maximumBufferedPayloadBytes >= FrameFormat.PAYLOAD_SIZE_BYTES) {
            "Buffer limit is too small for one cell."
        }
    }
}

/** Serializes opaque encrypted envelopes into exactly-sized cells. */
object FixedCellEncoder {
    fun encode(envelope: OpaqueEncryptedEnvelope, request: FrameRequest): List<FramedCell> {
        require(request.expiresAtEpochMillis > 0) {
            "Expiry must be a positive Unix-millisecond value."
        }
        val cellCount = cellCountFor(envelope.ciphertext.size)
        return List(cellCount) { index ->
            val wire = ByteArray(FrameFormat.CELL_SIZE_BYTES)
            val buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
            FrameFormat.writeMagic(buffer)
            buffer.put(FrameFormat.VERSION.toByte())
            buffer.put(envelope.e2eeBinding.size.toByte())
            buffer.putShort(0)
            buffer.put(request.messageId.value)
            buffer.putInt(index)
            buffer.putInt(cellCount)
            buffer.putInt(envelope.ciphertext.size)
            buffer.putLong(request.expiresAtEpochMillis)
            buffer.put(envelope.e2eeBinding)
            buffer.position(
                buffer.position() + FrameFormat.MAX_BINDING_SIZE_BYTES - envelope.e2eeBinding.size
            )
            buffer.putInt(0)

            val start = index * FrameFormat.PAYLOAD_SIZE_BYTES
            val end = minOf(start + FrameFormat.PAYLOAD_SIZE_BYTES, envelope.ciphertext.size)
            buffer.put(envelope.ciphertext, start, end - start)
            FramedCell(wire)
        }
    }

    private fun cellCountFor(ciphertextSize: Int): Int =
        (ciphertextSize + FrameFormat.PAYLOAD_SIZE_BYTES - 1) / FrameFormat.PAYLOAD_SIZE_BYTES
}

/**
 * In-memory bounded reassembler. Create one per active session/transport scope;
 * do not share it across unrelated relationships.
 */
class FixedCellReassembler(
    private val authenticator: EnvelopeAuthenticator,
    private val limits: ReassemblyLimits = ReassemblyLimits()
) {
    private val messages = LinkedHashMap<CellMessageId, PartialMessage>()
    private var bufferedPayloadBytes = 0

    fun accept(wireBytes: ByteArray, nowEpochMillis: Long): ReassemblyResult {
        expire(nowEpochMillis)
        return when (val parsed = parse(wireBytes)) {
            is ParseResult.Failure -> ReassemblyResult.Rejected(parsed.error)
            is ParseResult.Success -> acceptParsed(parsed.cell, nowEpochMillis)
        }
    }

    /** Removes incomplete values whose sender-selected deadline has passed. */
    fun expire(nowEpochMillis: Long): Int {
        val expiredIds = messages.filterValues {
            it.expiresAtEpochMillis <= nowEpochMillis
        }.keys.toList()
        expiredIds.forEach { remove(it) }
        return expiredIds.size
    }

    fun incompleteMessageCount(): Int = messages.size

    fun bufferedPayloadByteCount(): Int = bufferedPayloadBytes

    private fun acceptParsed(cell: ParsedCell, nowEpochMillis: Long): ReassemblyResult {
        if (cell.expiresAtEpochMillis <=
            nowEpochMillis
        ) {
            return ReassemblyResult.Rejected(FrameError.Expired)
        }

        val existing = messages[cell.messageId]
        if (existing == null && messages.size >= limits.maximumIncompleteMessages) {
            return ReassemblyResult.Rejected(FrameError.CapacityExceeded)
        }
        if (existing == null &&
            bufferedPayloadBytes + cell.payload.size > limits.maximumBufferedPayloadBytes
        ) {
            return ReassemblyResult.Rejected(FrameError.CapacityExceeded)
        }

        val partial = existing ?: PartialMessage.from(cell).also { messages[cell.messageId] = it }
        if (!partial.matches(cell)) {
            remove(cell.messageId)
            return ReassemblyResult.Rejected(FrameError.MetadataConflict)
        }

        val prior = partial.cells[cell.index]
        if (prior != null) {
            return if (prior.contentEquals(cell.payload)) {
                ReassemblyResult.Duplicate(cell.messageId)
            } else {
                remove(cell.messageId)
                ReassemblyResult.Rejected(FrameError.DuplicateConflict)
            }
        }
        if (bufferedPayloadBytes + cell.payload.size > limits.maximumBufferedPayloadBytes) {
            return ReassemblyResult.Rejected(FrameError.CapacityExceeded)
        }

        partial.cells[cell.index] = cell.payload
        bufferedPayloadBytes += cell.payload.size
        if (partial.cells.size != partial.cellCount) {
            return ReassemblyResult.Accepted(cell.messageId, partial.cells.size, partial.cellCount)
        }

        val ciphertext = partial.reassemble()
        remove(cell.messageId)
        return if (authenticator.verify(partial.e2eeBinding.copyOf(), ciphertext.copyOf())) {
            ReassemblyResult.Completed(
                CompletedEnvelope(cell.messageId, ciphertext, partial.e2eeBinding)
            )
        } else {
            ReassemblyResult.Rejected(FrameError.AuthenticationFailed)
        }
    }

    private fun remove(messageId: CellMessageId) {
        val removed = messages.remove(messageId) ?: return
        bufferedPayloadBytes -= removed.cells.values.sumOf { it.size }
    }

    private sealed interface ParseResult {
        data class Success(val cell: ParsedCell) : ParseResult

        data class Failure(val error: FrameError) : ParseResult
    }

    private fun parse(wireBytes: ByteArray): ParseResult {
        if (wireBytes.size !=
            FrameFormat.CELL_SIZE_BYTES
        ) {
            return ParseResult.Failure(FrameError.InvalidWireSize)
        }
        val buffer = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN)
        if (!FrameFormat.hasExpectedMagic(
                buffer
            )
        ) {
            return ParseResult.Failure(FrameError.InvalidMagic)
        }
        if (buffer.get().toInt() and 0xff !=
            FrameFormat.VERSION
        ) {
            return ParseResult.Failure(FrameError.UnsupportedVersion)
        }
        val bindingLength = buffer.get().toInt() and 0xff
        if (bindingLength >
            FrameFormat.MAX_BINDING_SIZE_BYTES
        ) {
            return ParseResult.Failure(FrameError.InvalidBindingLength)
        }
        if (buffer.short.toInt() != 0) return ParseResult.Failure(FrameError.InvalidReservedBytes)

        val messageId =
            CellMessageId(ByteArray(FrameFormat.MESSAGE_ID_SIZE_BYTES).also(buffer::get))
        val index = buffer.int
        val cellCount = buffer.int
        val ciphertextLength = buffer.int
        val expiresAtEpochMillis = buffer.long
        val paddedBinding = ByteArray(FrameFormat.MAX_BINDING_SIZE_BYTES).also(buffer::get)
        if (buffer.int != 0) return ParseResult.Failure(FrameError.InvalidReservedBytes)
        if (index < 0 || cellCount <= 0 || cellCount > FrameFormat.MAX_CELL_COUNT ||
            index >= cellCount
        ) {
            return ParseResult.Failure(
                if (cellCount <= 0 ||
                    cellCount > FrameFormat.MAX_CELL_COUNT
                ) {
                    FrameError.InvalidCellCount
                } else {
                    FrameError.InvalidCellIndex
                }
            )
        }
        if (ciphertextLength <= 0 || ciphertextLength > FrameFormat.MAX_CIPHERTEXT_SIZE_BYTES) {
            return ParseResult.Failure(FrameError.InvalidCiphertextLength)
        }
        if (cellCount !=
            cellCountFor(ciphertextLength)
        ) {
            return ParseResult.Failure(FrameError.InvalidCellCount)
        }
        if (expiresAtEpochMillis <= 0) return ParseResult.Failure(FrameError.Expired)
        if (paddedBinding.copyOfRange(bindingLength, paddedBinding.size).any { it != 0.toByte() }) {
            return ParseResult.Failure(FrameError.InvalidPadding)
        }

        val expectedPayloadSize = payloadSizeFor(index, ciphertextLength)
        val payloadAndPadding = ByteArray(FrameFormat.PAYLOAD_SIZE_BYTES).also(buffer::get)
        if (payloadAndPadding.copyOfRange(expectedPayloadSize, payloadAndPadding.size).any {
                it !=
                    0.toByte()
            }
        ) {
            return ParseResult.Failure(FrameError.InvalidPadding)
        }
        return ParseResult.Success(
            ParsedCell(
                messageId = messageId,
                index = index,
                cellCount = cellCount,
                ciphertextLength = ciphertextLength,
                expiresAtEpochMillis = expiresAtEpochMillis,
                e2eeBinding = paddedBinding.copyOf(bindingLength),
                payload = payloadAndPadding.copyOf(expectedPayloadSize)
            )
        )
    }

    private fun cellCountFor(ciphertextSize: Int): Int =
        (ciphertextSize + FrameFormat.PAYLOAD_SIZE_BYTES - 1) / FrameFormat.PAYLOAD_SIZE_BYTES

    private fun payloadSizeFor(index: Int, ciphertextSize: Int): Int = minOf(
        FrameFormat.PAYLOAD_SIZE_BYTES,
        ciphertextSize - index * FrameFormat.PAYLOAD_SIZE_BYTES
    )

    private data class ParsedCell(
        val messageId: CellMessageId,
        val index: Int,
        val cellCount: Int,
        val ciphertextLength: Int,
        val expiresAtEpochMillis: Long,
        val e2eeBinding: ByteArray,
        val payload: ByteArray
    )

    private class PartialMessage(
        val messageId: CellMessageId,
        val cellCount: Int,
        val ciphertextLength: Int,
        val expiresAtEpochMillis: Long,
        val e2eeBinding: ByteArray
    ) {
        val cells = HashMap<Int, ByteArray>()

        fun matches(cell: ParsedCell): Boolean = messageId == cell.messageId &&
            cellCount == cell.cellCount &&
            ciphertextLength == cell.ciphertextLength &&
            expiresAtEpochMillis == cell.expiresAtEpochMillis &&
            e2eeBinding.contentEquals(cell.e2eeBinding)

        fun reassemble(): ByteArray {
            val result = ByteArray(ciphertextLength)
            for (index in 0 until cellCount) {
                val payload =
                    checkNotNull(cells[index]) { "All cells must exist before reassembly." }
                payload.copyInto(result, destinationOffset = index * FrameFormat.PAYLOAD_SIZE_BYTES)
            }
            return result
        }

        companion object {
            fun from(cell: ParsedCell): PartialMessage = PartialMessage(
                messageId = cell.messageId,
                cellCount = cell.cellCount,
                ciphertextLength = cell.ciphertextLength,
                expiresAtEpochMillis = cell.expiresAtEpochMillis,
                e2eeBinding = cell.e2eeBinding.copyOf()
            )
        }
    }
}
