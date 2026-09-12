package io.sodyx.domain

/** Plain application content, not ciphertext. Do not log or serialize this object as a wire envelope. */
class Message private constructor(
    val id: MessageId,
    val sessionId: SessionId,
    val sender: PairwiseIdentity,
    val recipient: PairwiseIdentity,
    val text: String
) {
    fun envelopeReference(id: EnvelopeId): EnvelopeReference =
        EnvelopeReference.create(id, this.id, sessionId)

    companion object {
        fun create(
            session: Session,
            id: MessageId,
            sender: PairwiseIdentity,
            text: String
        ): Message {
            require(session.state == SessionState.ACTIVE) { "Messages require an active session" }
            require(session.participants.contains(sender)) { "Sender must belong to the session" }
            require(text.isNotBlank()) { "Message text must not be blank" }
            val recipient = if (sender == session.participants.local.identity) {
                session.participants.peer.identity
            } else {
                session.participants.local.identity
            }
            return Message(id, session.id, sender, recipient, text)
        }
    }
}
