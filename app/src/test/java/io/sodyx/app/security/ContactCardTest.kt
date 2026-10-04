package io.sodyx.app.security

import io.sodyx.security.ProtocolAddress
import io.sodyx.security.PublicPreKeyBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactCardTest {
    private fun card(capability: String = "A".repeat(43)) = ContactCard(
        "https://relay.example",
        capability,
        1_900_000_000_000,
        PublicPreKeyBundle(
            ProtocolAddress("pairwise-address", 1), 9, 1, byteArrayOf(1),
            2, byteArrayOf(2), byteArrayOf(3), byteArrayOf(4),
            3, byteArrayOf(5), byteArrayOf(6)
        )
    )

    @Test
    fun publicCardRoundTripAndCodeBindEveryField() {
        val original = card()
        val decoded = ContactCardCodec.decode(ContactCardCodec.encode(original))
        assertEquals(original.relayUrl, decoded.relayUrl)
        assertEquals(original.deliveryCapability, decoded.deliveryCapability)
        assertArrayEquals(
            original.bundle.serializedKyberPreKey,
            decoded.bundle.serializedKyberPreKey
        )
        assertEquals(
            ContactCardCodec.verificationCode(original),
            ContactCardCodec.verificationCode(decoded)
        )
        assertNotEquals(
            ContactCardCodec.verificationCode(original),
            ContactCardCodec.verificationCode(card("B".repeat(43)))
        )
    }

    @Test
    fun cleartextRelayAndMalformedCardsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ContactCardCodec.encode(
                ContactCard("http://relay.example", "A".repeat(43), 1, card().bundle)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContactCardCodec.decode("sodyx1:====")
        }
        assertThrows(Exception::class.java) { ContactCardCodec.decode("sodyx1:AQ") }
        assertThrows(IllegalArgumentException::class.java) {
            ContactCardCodec.decode(ContactCardCodec.encode(card()) + "=")
        }
    }
}
