package io.sodyx.domain

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationPayloadTest {
    private val id = InvitationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val token = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val pseudonym =
        PairwiseIdentityId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    private val payload = InvitationPayload(id, token, pseudonym, 1_000L, 2_000L)

    @Test fun canonicalPayloadRoundTripsAndRedactsToken() {
        assertEquals(payload, InvitationPayload.parse(payload.encode()))
        assertTrue(payload.toString().contains("token=<redacted>"))
        assertFalse(payload.toString().contains(token.toString()))
    }

    @Test fun parserRejectsMalformedNoncanonicalAndOutOfRangePayloads() {
        val raw = payload.encode()
        val invalid = listOf(
            "", raw.replace("sodyx-invite-v1", "sodyx-invite-v2"),
            raw.replace("|token=", "|extra=x|token="), raw.replace("|id=", "|ID="),
            raw.replace(
                id.value.toString(),
                "not-a-uuid"
            ),
            raw.replace("created=1000", "created=-1"),
            raw.replace(
                "created=1000",
                "created=1000 "
            ),
            raw.replace("expires=2000", "expires=1000"),
            raw + "|extra=x", raw + "\n"
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                InvitationPayload.parse(candidate)
            }
        }
    }

    @Test fun invitationValidityUsesCreatedInclusiveExpiryExclusiveBoundary() {
        val invitation =
            Invitation(
                id,
                LocalDeviceIdentityRef(UUID.randomUUID()),
                PairwiseIdentity(pseudonym),
                java.time.Instant.ofEpochMilli(1_000),
                java.time.Instant.ofEpochMilli(2_000)
            )
        assertTrue(invitation.isValidAt(java.time.Instant.ofEpochMilli(1_000)))
        assertTrue(invitation.isValidAt(java.time.Instant.ofEpochMilli(1_999)))
        assertFalse(invitation.isValidAt(java.time.Instant.ofEpochMilli(2_000)))
        assertThrows(IllegalArgumentException::class.java) {
            InvitationPayload(id, token, pseudonym, 2_000, 2_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvitationPayload(id, token, pseudonym, -1, 2_000)
        }
    }
}
