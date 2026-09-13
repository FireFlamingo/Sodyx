package io.sodyx.domain

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainInvariantsTest {
    private enum class Carrier { BLUETOOTH, RELAY }
    private fun uuid(n: Int) = UUID(0L, n.toLong())
    private fun local(n: Int) = LocalDeviceIdentityRef(uuid(n))
    private fun relationship(n: Int) = RelationshipId(uuid(n))
    private fun pair(n: Int) = PairwiseIdentity(PairwiseIdentityId(uuid(n)))
    private fun session(n: Int) = SessionId(uuid(n))
    private fun alias(value: String) = DisplayAlias(value)

    private fun conversation(): Conversation {
        val owner = local(1)
        val identity = LocalIdentity(owner)
            .addRelationship(relationship(10), PairwiseParticipants(pair(20), pair(21)))
        return Conversation(identity.relationships.single())
    }

    @Test fun oneLocalIdentityOwnsManyRelationshipsAndSnapshotsAreDefensive() {
        val owner = local(1)
        val aggregate = LocalIdentity(owner)
            .addRelationship(relationship(10), PairwiseParticipants(pair(20), pair(21)))
            .addRelationship(relationship(11), PairwiseParticipants(pair(22), pair(23)))

        assertEquals(owner, aggregate.relationships[0].owner)
        assertEquals(owner, aggregate.relationships[1].owner)
        assertEquals(2, aggregate.relationships.size)
        val snapshot = aggregate.relationships
        assertNotSame(snapshot, aggregate.relationships)
        (snapshot as MutableList).clear()
        assertEquals(2, aggregate.relationships.size)
    }

    @Test fun restoringSessionWithDuplicateParticipantsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Session.restore(
                session(40),
                relationship(10),
                pair(20),
                pair(20),
                alias("Local"),
                alias("Peer"),
                SessionState.ACTIVE
            )
        }
    }

    @Test fun pairwiseIdentitiesCannotCollideAcrossRelationshipsOrRoles() {
        val owner = local(1)
        val first = LocalIdentity(owner)
            .addRelationship(relationship(10), PairwiseParticipants(pair(20), pair(21)))
        assertThrows(IllegalArgumentException::class.java) {
            first.addRelationship(relationship(11), PairwiseParticipants(pair(21), pair(22)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.addRelationship(relationship(11), PairwiseParticipants(pair(22), pair(20)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairwiseParticipants(pair(20), pair(20))
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.addRelationship(relationship(10), PairwiseParticipants(pair(22), pair(23)))
        }
    }

    @Test fun sessionsBelongToRelationshipAndAlwaysHaveTwoDistinctParticipants() {
        val conversation = conversation()
        val created = conversation.startSession(session(30), alias("Local"), alias("Peer"))
        val value = created.sessions.single()
        assertEquals(conversation.relationship.id, value.relationshipId)
        assertEquals(2, listOf(value.participants.local, value.participants.peer).size)
        assertTrue(value.participants.local.identity != value.participants.peer.identity)
        assertThrows(IllegalArgumentException::class.java) {
            SessionParticipants(
                SessionParticipant(pair(20), alias("A")),
                SessionParticipant(pair(20), alias("B"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            created.startSession(session(30), alias("Again"), alias("Peer"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            created.transitionSession(session(999), SessionState.ACTIVE)
        }
    }

    @Test fun lifecycleAllowsExactlyDeclaredEdgesAndConversationUpdatesOneSession() {
        val allowed = setOf(
            SessionState.CREATED to SessionState.ACTIVE,
            SessionState.CREATED to SessionState.ENDING,
            SessionState.ACTIVE to SessionState.ENDING,
            SessionState.ENDING to SessionState.CLOSED,
            SessionState.CLOSED to SessionState.DESTROYED
        )
        val base = conversation().startSession(
            session(30),
            alias("A"),
            alias("B")
        ).sessions.single()
        for (from in SessionState.values()) {
            for (to in SessionState.values()) {
                val actual = when (from) {
                    SessionState.CREATED -> base
                    SessionState.ACTIVE -> base.transitionTo(SessionState.ACTIVE)
                    SessionState.ENDING -> base.transitionTo(SessionState.ENDING)
                    SessionState.CLOSED -> base.transitionTo(
                        SessionState.ENDING
                    ).transitionTo(SessionState.CLOSED)
                    SessionState.DESTROYED -> base.transitionTo(
                        SessionState.ENDING
                    ).transitionTo(SessionState.CLOSED).transitionTo(SessionState.DESTROYED)
                }
                if ((from to to) in allowed) {
                    assertEquals(to, actual.transitionTo(to).state)
                } else {
                    assertThrows(IllegalArgumentException::class.java) { actual.transitionTo(to) }
                }
            }
        }
        val changed = conversation()
            .startSession(session(30), alias("A"), alias("B"))
            .startSession(session(31), alias("C"), alias("D"))
            .transitionSession(session(30), SessionState.ACTIVE)
        assertEquals(SessionState.ACTIVE, changed.sessions[0].state)
        assertEquals(SessionState.CREATED, changed.sessions[1].state)
    }

    @Test fun messageRequiresActiveMemberAndDerivesTheOtherParticipantBothWays() {
        val c = conversation().startSession(session(30), alias("A"), alias("B"))
        val created = c.sessions.single()
        assertThrows(IllegalArgumentException::class.java) {
            Message.create(created, MessageId(uuid(40)), pair(20), "hello")
        }
        val active = created.transitionTo(SessionState.ACTIVE)
        val outbound = Message.create(active, MessageId(uuid(40)), pair(20), "hello")
        val inbound = Message.create(active, MessageId(uuid(41)), pair(21), "reply")
        assertEquals(pair(20), outbound.sender)
        assertEquals(pair(21), outbound.recipient)
        assertEquals(pair(21), inbound.sender)
        assertEquals(pair(20), inbound.recipient)
        assertThrows(IllegalArgumentException::class.java) {
            Message.create(active, MessageId(uuid(42)), pair(999), "hello")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Message.create(active, MessageId(uuid(43)), pair(20), "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Message.create(
                active.transitionTo(SessionState.ENDING),
                MessageId(uuid(44)),
                pair(20),
                "hello"
            )
        }
    }

    @Test fun transportCarriersDoNotChangeMessageSemantics() {
        data class CarrierEnvelope(
            val route: String,
            val reference: EnvelopeReference,
            val message: Message
        )
        fun deliver(carrier: Carrier, envelope: CarrierEnvelope): CarrierEnvelope = when (carrier) {
            Carrier.BLUETOOTH -> envelope.copy(route = "bluetooth")
            Carrier.RELAY -> envelope.copy(route = "relay")
        }
        val active = conversation().startSession(
            session(30),
            alias("A"),
            alias("B")
        ).sessions.single()
            .transitionTo(SessionState.ACTIVE)
        val original = Message.create(active, MessageId(uuid(40)), pair(20), "same content")
        val reference = original.envelopeReference(EnvelopeId(uuid(41)))
        assertEquals(original.id, reference.messageId)
        assertEquals(original.sessionId, reference.sessionId)
        assertEquals(reference, original.envelopeReference(EnvelopeId(uuid(41))))
        for (carrier in Carrier.values()) {
            val received = deliver(carrier, CarrierEnvelope("route", reference, original))
            assertEquals(carrier.name.lowercase(), received.route)
            assertEquals(reference, received.reference)
            assertEquals(original.id, received.message.id)
            assertEquals(original.sessionId, received.message.sessionId)
            assertEquals(original.sender, received.message.sender)
            assertEquals(original.recipient, received.message.recipient)
            assertEquals(original.text, received.message.text)
        }
    }

    @Test fun invitationValidityIsInclusiveAtCreationAndExclusiveAtExpiry() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val expiry = start.plusSeconds(60)
        val invitation = Invitation(InvitationId(uuid(50)), local(1), pair(20), start, expiry)
        assertTrue(invitation.isValidAt(start))
        assertTrue(invitation.isValidAt(start.plusSeconds(59)))
        assertFalse(invitation.isValidAt(expiry))
        assertFalse(invitation.isValidAt(start.minusNanos(1)))
        assertThrows(IllegalArgumentException::class.java) {
            Invitation(InvitationId(uuid(51)), local(1), pair(20), start, start)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation(InvitationId(uuid(52)), local(1), pair(20), expiry, start)
        }
    }

    @Test fun aliasesEnforceDisplayBoundariesAndSessionStoresImmutableSnapshots() {
        assertEquals(64, DisplayAlias("x".repeat(64)).value.length)
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias("") }
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias(" leading") }
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias("trailing ") }
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias("x\n") }
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias("x".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { DisplayAlias("ok\u0000") }
        val localAlias = alias("Local")
        val peerAlias = alias("Peer")
        val value = conversation().startSession(
            session(30),
            localAlias,
            peerAlias
        ).sessions.single()
        assertEquals(localAlias, value.participants.local.alias)
        assertEquals(peerAlias, value.participants.peer.alias)
        assertEquals("Local", value.participants.local.alias.value)
        assertEquals("Peer", value.participants.peer.alias.value)
        val rotated = conversation().startSession(
            session(31),
            alias("Rotated local"),
            alias("Rotated peer")
        ).sessions.single()
        assertEquals(value.participants.local.identity, rotated.participants.local.identity)
        assertEquals(value.participants.peer.identity, rotated.participants.peer.identity)
        assertEquals("Rotated local", rotated.participants.local.alias.value)
    }
}
