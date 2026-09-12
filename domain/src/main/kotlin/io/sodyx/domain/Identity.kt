package io.sodyx.domain

/** An opaque relationship-scoped identity reference, not a key or verification assertion. */
data class PairwiseIdentity(val id: PairwiseIdentityId)

/** Exactly two distinct identity references. Neither endpoint is a global device identifier. */
data class PairwiseParticipants(val local: PairwiseIdentity, val peer: PairwiseIdentity) {
    init {
        require(local != peer) { "Participants must have distinct pairwise identities" }
    }

    fun contains(identity: PairwiseIdentity): Boolean = identity == local || identity == peer
}

class Relationship internal constructor(
    val id: RelationshipId,
    val owner: LocalDeviceIdentityRef,
    val participants: PairwiseParticipants
)

/** Local-only immutable aggregate. All relationships for this identity must be added here. */
class LocalIdentity private constructor(
    val reference: LocalDeviceIdentityRef,
    private val entries: List<Relationship>
) {
    constructor(reference: LocalDeviceIdentityRef) : this(reference, emptyList())

    // A defensive snapshot: callers cannot mutate the aggregate through a collection cast.
    val relationships: List<Relationship> get() = entries.toList()

    fun addRelationship(id: RelationshipId, participants: PairwiseParticipants): LocalIdentity {
        require(entries.none { it.id == id }) { "Relationship reference already exists" }
        require(
            entries.none {
                it.participants.contains(participants.local) ||
                    it.participants.contains(participants.peer)
            }
        ) { "A pairwise identity cannot be reused across relationships" }
        return LocalIdentity(reference, entries + Relationship(id, reference, participants))
    }
}
