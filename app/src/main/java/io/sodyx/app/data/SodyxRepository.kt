package io.sodyx.app.data

import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.LocalDeviceIdentityRef
import io.sodyx.domain.MessageId
import io.sodyx.domain.RelationshipId
import io.sodyx.domain.SessionId

data class RelationshipRow(
    val id: RelationshipId,
    val alias: DisplayAlias,
    val activeSessionId: SessionId?
)

data class SessionRow(
    val id: SessionId,
    val relationshipId: RelationshipId,
    val localAlias: DisplayAlias,
    val peerAlias: DisplayAlias
)

data class MessageRow(
    val id: MessageId,
    val sessionId: SessionId,
    val senderLocal: Boolean,
    val text: String
) {
    override fun toString(): String =
        "MessageRow(id=$id, sessionId=$sessionId, senderLocal=$senderLocal, text=<redacted>)"
}

interface SodyxRepository : AutoCloseable {
    fun localIdentity(): LocalDeviceIdentityRef
    fun listRelationships(): List<RelationshipRow>
    fun createLocalTestRelationship(alias: DisplayAlias): RelationshipId
    fun startSession(relationshipId: RelationshipId): SessionRow
    fun activeSession(relationshipId: RelationshipId): SessionRow?
    fun loadMessages(sessionId: SessionId): List<MessageRow>
    fun appendLocalMessage(sessionId: SessionId, text: String): MessageRow
    fun endSession(sessionId: SessionId)
}
