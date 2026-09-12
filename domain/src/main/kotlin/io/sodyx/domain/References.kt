package io.sodyx.domain

import java.util.UUID

// Caller-supplied opaque references. No generation, key material, or wire format is defined here.
@JvmInline
value class LocalDeviceIdentityRef(val value: UUID) {
    override fun toString() = "LocalDeviceIdentityRef(redacted)"
}

@JvmInline
value class RelationshipId(val value: UUID) {
    override fun toString() = "RelationshipId(redacted)"
}

@JvmInline
value class PairwiseIdentityId(val value: UUID) {
    override fun toString() = "PairwiseIdentityId(redacted)"
}

@JvmInline
value class SessionId(val value: UUID) {
    override fun toString() = "SessionId(redacted)"
}

@JvmInline
value class MessageId(val value: UUID) {
    override fun toString() = "MessageId(redacted)"
}

@JvmInline
value class EnvelopeId(val value: UUID) {
    override fun toString() = "EnvelopeId(redacted)"
}

@JvmInline
value class InvitationId(val value: UUID) {
    override fun toString() = "InvitationId(redacted)"
}

@JvmInline
value class DisplayAlias(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Alias must have visible text" }
        require(value.length <= 64 && value.none { it.isISOControl() }) {
            "Alias must fit a single display label of at most 64 UTF-16 code units"
        }
    }

    override fun toString() = "DisplayAlias(redacted)"
}

/** Local association between one message and its session, with no transport semantics. */
class EnvelopeReference private constructor(
    val id: EnvelopeId,
    val messageId: MessageId,
    val sessionId: SessionId
) {
    // Deliberately not a data class: a public copy() would allow callers to forge
    // an association while bypassing the Message.envelopeReference() entry point.
    override fun equals(other: Any?): Boolean = other is EnvelopeReference &&
        id == other.id && messageId == other.messageId && sessionId == other.sessionId

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + messageId.hashCode()) + sessionId.hashCode()

    override fun toString(): String = "EnvelopeReference(redacted)"

    internal companion object {
        fun create(id: EnvelopeId, messageId: MessageId, sessionId: SessionId): EnvelopeReference =
            EnvelopeReference(id, messageId, sessionId)
    }
}
