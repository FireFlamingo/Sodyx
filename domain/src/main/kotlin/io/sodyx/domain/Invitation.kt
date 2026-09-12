package io.sodyx.domain

import java.time.Instant

/** Local invitation intent. No redeemable secret, public device identity, or QR encoding exists yet. */
class Invitation(
    val id: InvitationId,
    val owner: LocalDeviceIdentityRef,
    val offeredIdentity: PairwiseIdentity,
    val createdAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(expiresAt > createdAt) { "Invitation expiry must follow creation" }
    }

    fun isValidAt(instant: Instant): Boolean = instant >= createdAt && instant < expiresAt
}
