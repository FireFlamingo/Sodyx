package io.sodyx.app.security

import io.sodyx.app.network.RelayEndpoint
import io.sodyx.security.ProtocolAddress
import io.sodyx.security.PublicPreKeyBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Public setup material and a scoped delivery capability, shared only with the intended peer. */
internal class ContactCard(
    val relayUrl: String,
    val deliveryCapability: String,
    val expiresAtEpochMillis: Long,
    val bundle: PublicPreKeyBundle
) {
    override fun toString(): String = "ContactCard(redacted)"
}

internal object ContactCardCodec {
    private const val PREFIX = "sodyx1:"
    private const val MAX_BYTES = 8192
    private val capabilityPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun encode(card: ContactCard): String {
        validate(card)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { writer ->
                writer.writeByte(1)
                writer.writeUTF(card.relayUrl)
                writer.writeUTF(card.deliveryCapability)
                writer.writeLong(card.expiresAtEpochMillis)
                with(card.bundle) {
                    writer.writeUTF(address.name)
                    writer.writeInt(address.deviceId)
                    writer.writeInt(registrationId)
                    writer.writeInt(oneTimePreKeyId)
                    writer.writeBlob(serializedOneTimePreKey)
                    writer.writeInt(signedPreKeyId)
                    writer.writeBlob(serializedSignedPreKey)
                    writer.writeBlob(signedPreKeySignature)
                    writer.writeBlob(serializedIdentityKey)
                    writer.writeInt(kyberPreKeyId)
                    writer.writeBlob(serializedKyberPreKey)
                    writer.writeBlob(kyberPreKeySignature)
                }
            }
        }.toByteArray()
        require(bytes.size <= MAX_BYTES)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(value: String): ContactCard {
        require(value.startsWith(PREFIX) && value.length <= 11_000) { "Invalid contact card" }
        val encoded = value.removePrefix(PREFIX)
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size in 1..MAX_BYTES)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val card = input.use { reader ->
            require(reader.readUnsignedByte() == 1)
            val relayUrl = reader.readUTF()
            val capability = reader.readUTF()
            val expires = reader.readLong()
            val address = ProtocolAddress(reader.readUTF(), reader.readInt())
            val bundle = PublicPreKeyBundle(
                address,
                reader.readInt(),
                reader.readInt(),
                reader.readBlob(128),
                reader.readInt(),
                reader.readBlob(128),
                reader.readBlob(128),
                reader.readBlob(128),
                reader.readInt(),
                reader.readBlob(4096),
                reader.readBlob(128)
            )
            require(reader.available() == 0)
            ContactCard(relayUrl, capability, expires, bundle)
        }
        validate(card)
        return card
    }

    /** Comparing this full code authenticates the relay capability as well as the public keys. */
    fun verificationCode(card: ContactCard): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(card).encodeToByteArray())
        .joinToString("") { "%02X".format(it) }
        .chunked(4).joinToString(" ")

    private fun validate(card: ContactCard) {
        require(card.relayUrl.length <= 2048)
        RelayEndpoint.production(card.relayUrl)
        require(capabilityPattern.matches(card.deliveryCapability))
        require(card.expiresAtEpochMillis > 0)
        with(card.bundle) {
            require(address.name.length <= 64 && address.name.none(Char::isISOControl))
            require(address.deviceId == 1 && registrationId > 0)
            require(oneTimePreKeyId >= 0 && signedPreKeyId >= 0 && kyberPreKeyId >= 0)
            require(serializedOneTimePreKey.size in 1..128)
            require(serializedSignedPreKey.size in 1..128)
            require(signedPreKeySignature.size in 1..128)
            require(serializedIdentityKey.size in 1..128)
            require(serializedKyberPreKey.size in 1..4096)
            require(kyberPreKeySignature.size in 1..128)
        }
    }

    private fun DataOutputStream.writeBlob(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBlob(maximum: Int): ByteArray {
        val length = readInt()
        require(length in 1..maximum && length <= available())
        return ByteArray(length).also(::readFully)
    }
}
