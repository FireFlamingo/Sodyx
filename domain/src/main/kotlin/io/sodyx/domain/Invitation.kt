package io.sodyx.domain

import java.time.Instant
import java.util.UUID

/** Local invitation intent. Its token is an opaque QR reference, not an authenticity or secrecy claim. */
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

enum class InvitationState { OPEN, REDEEMED, EXPIRED }
enum class InvitationDirection { OUTBOUND, IMPORTED }

data class InvitationPayload(
    val id: InvitationId,
    val token: UUID,
    val pseudonym: PairwiseIdentityId,
    val createdEpochMillis: Long,
    val expiresEpochMillis: Long
) {
    init {
        require(createdEpochMillis >= 0 && expiresEpochMillis > createdEpochMillis)
    }
    fun encode(): String =
        "sodyx-invite-v1|id=${id.value}|token=$token|pseudonym=${pseudonym.value}|created=$createdEpochMillis|expires=$expiresEpochMillis"
    override fun toString(): String =
        "InvitationPayload(id=$id, token=<redacted>, pseudonym=$pseudonym, createdEpochMillis=$createdEpochMillis, expiresEpochMillis=$expiresEpochMillis)"
    companion object {
        fun parse(raw: String): InvitationPayload {
            require(
                raw.isNotEmpty() && raw.length <= 512 && raw.none {
                    it.isISOControl()
                }
            ) { "Invalid invitation payload" }
            val parts = raw.split('|')
            require(parts.size == 6 && parts[0] == "sodyx-invite-v1") {
                "Invalid invitation payload"
            }
            val keys = listOf("id", "token", "pseudonym", "created", "expires")
            require(
                parts.drop(1).map {
                    it.substringBefore('=')
                } == keys
            ) { "Invalid invitation payload" }
            fun value(i: Int): String {
                val p = parts[i].split('=', limit = 2)
                require(p.size == 2 && p[1].isNotEmpty())
                return p[1]
            }
            val id = InvitationId(UUID.fromString(value(1)))
            val token = UUID.fromString(value(2))
            val pseudonym = PairwiseIdentityId(UUID.fromString(value(3)))
            val created =
                value(4).toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid invitation payload")
            val expires =
                value(5).toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid invitation payload")
            val result = InvitationPayload(id, token, pseudonym, created, expires)
            require(result.encode() == raw) { "Noncanonical invitation payload" }
            return result
        }
    }
}
