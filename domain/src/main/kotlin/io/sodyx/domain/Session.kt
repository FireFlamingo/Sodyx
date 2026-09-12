package io.sodyx.domain

enum class SessionState {
    CREATED,
    ACTIVE,
    ENDING,
    CLOSED,
    DESTROYED
}

/** Aliases are session display snapshots. Rotating one does not change a relationship identity. */
data class SessionParticipant(val identity: PairwiseIdentity, val alias: DisplayAlias)

data class SessionParticipants(val local: SessionParticipant, val peer: SessionParticipant) {
    init {
        require(local.identity != peer.identity) { "A session requires two distinct participants" }
    }

    fun contains(identity: PairwiseIdentity): Boolean =
        identity == local.identity || identity == peer.identity
}

class Session private constructor(
    val id: SessionId,
    val relationshipId: RelationshipId,
    val participants: SessionParticipants,
    val state: SessionState
) {
    /** Records a modeled transition only. It performs no delivery, cleanup, or key destruction. */
    fun transitionTo(next: SessionState): Session {
        val allowed = when (state) {
            SessionState.CREATED -> next == SessionState.ACTIVE || next == SessionState.ENDING
            SessionState.ACTIVE -> next == SessionState.ENDING
            SessionState.ENDING -> next == SessionState.CLOSED
            SessionState.CLOSED -> next == SessionState.DESTROYED
            SessionState.DESTROYED -> false
        }
        require(allowed) { "Invalid session lifecycle transition: $state to $next" }
        return Session(id, relationshipId, participants, next)
    }

    companion object {
        internal fun create(
            id: SessionId,
            relationship: Relationship,
            localAlias: DisplayAlias,
            peerAlias: DisplayAlias
        ): Session = Session(
            id,
            relationship.id,
            SessionParticipants(
                SessionParticipant(relationship.participants.local, localAlias),
                SessionParticipant(relationship.participants.peer, peerAlias)
            ),
            SessionState.CREATED
        )
    }
}

/** One relationship's session collection; not a database, message history, or global directory. */
class Conversation private constructor(
    val relationship: Relationship,
    private val entries: List<Session>
) {
    constructor(relationship: Relationship) : this(relationship, emptyList())

    val sessions: List<Session> get() = entries.toList()

    fun startSession(
        id: SessionId,
        localAlias: DisplayAlias,
        peerAlias: DisplayAlias
    ): Conversation {
        require(entries.none { it.id == id }) { "Session reference already exists" }
        return Conversation(
            relationship,
            entries + Session.create(id, relationship, localAlias, peerAlias)
        )
    }

    fun transitionSession(id: SessionId, next: SessionState): Conversation {
        val current = entries.singleOrNull { it.id == id }
        requireNotNull(current) { "Session does not belong to this conversation" }
        val updated = current.transitionTo(next)
        return Conversation(relationship, entries.map { if (it.id == id) updated else it })
    }
}
