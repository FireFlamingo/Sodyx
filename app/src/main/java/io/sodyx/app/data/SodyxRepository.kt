package io.sodyx.app.data

import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.InvitationDirection
import io.sodyx.domain.InvitationPayload
import io.sodyx.domain.InvitationState
import io.sodyx.domain.LocalDeviceIdentityRef
import io.sodyx.domain.MessageId
import io.sodyx.domain.RelationshipId
import io.sodyx.domain.SessionId

data class RelationshipRow(
    val id: RelationshipId,
    val alias: DisplayAlias,
    val activeSessionId: SessionId?,
    val peerPseudonym: String = ""
)

data class InvitationRow(
    val payload: InvitationPayload,
    val state: InvitationState,
    val direction: InvitationDirection
)
sealed class RedemptionResult {
    data class Redeemed(val relationshipId: RelationshipId) : RedemptionResult()
    data object AlreadyRedeemed : RedemptionResult()
    data object Expired : RedemptionResult()
    data object Invalid : RedemptionResult()
}

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
    fun createInvitation(nowEpochMillis: Long, ttlMillis: Long = 600_000L): InvitationRow
    fun listInvitations(): List<InvitationRow>
    fun redeemInvitation(
        payload: String,
        alias: DisplayAlias,
        nowEpochMillis: Long
    ): RedemptionResult
}
