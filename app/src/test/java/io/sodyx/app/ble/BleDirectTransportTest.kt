package io.sodyx.app.ble

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDirectTransportTest {
    private val now = 1_700_000_000_000L
    private val expiry = now + 60_000L

    @Test
    fun `manual transport fragments and reassembles opaque ciphertext`() {
        val sender = BleDirectTransport(random = deterministicRandom())
        val receiver = BleDirectTransport()
        val frames = mutableListOf<ByteArray>()
        val ciphertext = ByteArray(400) { it.toByte() }

        val sent = sender.send(
            envelope = OpaqueBleEnvelope(ciphertext, expiry),
            negotiatedMtu = 80,
            nowEpochMillis = now,
            sink = BleGattFrameSink { frame ->
                frames += frame
                BleGattWriteResult.AcceptedByGatt
            }
        )

        assertTrue(sent is BleSendResult.QueuedToGatt)
        assertTrue(frames.size > 1)
        val complete = frames.map {
            receiver.receive(it, now)
        }.filterIsInstance<BleReceiveResult.Completed>().single()
        assertArrayEquals(ciphertext, complete.envelope.ciphertext)
        assertEquals(expiry, complete.envelope.expiresAtEpochMillis)
    }

    @Test
    fun `expired envelope is rejected before it reaches GATT`() {
        val transport = BleDirectTransport(random = deterministicRandom())

        val result = transport.send(
            envelope = OpaqueBleEnvelope(byteArrayOf(1), now),
            negotiatedMtu = 80,
            nowEpochMillis = now,
            sink = BleGattFrameSink { error("expired envelope must not be written") }
        )

        assertTrue(result is BleSendResult.Rejected)
    }

    @Test
    fun `completed transfer is rejected when replayed`() {
        val frames = fragments(ByteArray(100) { 7 })
        val receiver = BleIncomingAssembler()

        frames.forEach { receiver.accept(it, now) }

        assertEquals(BleReceiveResult.Rejected.Replay, receiver.accept(frames.first(), now))
    }

    @Test
    fun `inconsistent fragment aborts its transfer`() {
        val frames = fragments(ByteArray(120) { it.toByte() })
        val receiver = BleIncomingAssembler()
        val corrupted = frames[1].copyOf().also { it[it.lastIndex] = 99 }

        assertEquals(BleReceiveResult.InProgress, receiver.accept(frames.first(), now))
        assertEquals(BleReceiveResult.InProgress, receiver.accept(frames[1], now))
        assertEquals(BleReceiveResult.Rejected.InconsistentFrame, receiver.accept(corrupted, now))
        assertEquals(BleReceiveResult.Rejected.InconsistentFrame, receiver.accept(frames[2], now))
    }

    @Test
    fun `small MTU and failed GATT write produce honest outcomes`() {
        val transport = BleDirectTransport(random = deterministicRandom())
        val envelope = OpaqueBleEnvelope(byteArrayOf(1, 2, 3), expiry)

        assertTrue(
            transport.send(
                envelope,
                23,
                now,
                BleGattFrameSink {
                    BleGattWriteResult.AcceptedByGatt
                }
            )
                is BleSendResult.Rejected
        )
        assertEquals(
            BleSendResult.WriteFailed(0, "disconnected"),
            transport.send(
                envelope,
                80,
                now,
                BleGattFrameSink {
                    BleGattWriteResult.Rejected("disconnected")
                }
            )
        )
    }

    private fun fragments(ciphertext: ByteArray): List<ByteArray> = BleTransferProtocol.fragment(
        OpaqueBleEnvelope(ciphertext, expiry),
        negotiatedMtu = 80,
        nowEpochMillis = now,
        random = deterministicRandom()
    )

    private fun deterministicRandom(): SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply {
        setSeed(4L)
    }
}
