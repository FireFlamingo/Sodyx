package io.sodyx.app.security

import io.sodyx.security.EncryptedMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureWireEnvelopeTest {
    @Test
    fun roundTripPreservesOnlyCiphertextTypeAndBytes() {
        val original = EncryptedMessage(3, byteArrayOf(0, 1, 2, 3))
        val encoded = SecureWireEnvelope.encode(original)
        val decoded = SecureWireEnvelope.decode(encoded)
        assertEquals(3, decoded.type)
        assertArrayEquals(original.serialized, decoded.serialized)
        assertEquals(13, encoded.size)
    }

    @Test
    fun malformedLengthAndVersionAreRejected() {
        val original = SecureWireEnvelope.encode(EncryptedMessage(2, byteArrayOf(9)))
        val badVersion = original.copyOf().also { it[3] = 2 }
        val badLength = original.copyOf().also { it[8] = 2 }
        assertThrows(IllegalArgumentException::class.java) { SecureWireEnvelope.decode(badVersion) }
        assertThrows(IllegalArgumentException::class.java) { SecureWireEnvelope.decode(badLength) }
        assertThrows(IllegalArgumentException::class.java) {
            SecureWireEnvelope.decode(byteArrayOf(1))
        }
    }
}
