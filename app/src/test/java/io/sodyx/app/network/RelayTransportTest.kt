package io.sodyx.app.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayTransportTest {
    private val now = 1_700_000_000_000L
    private val expiry = now + 86_400_000L
    private val retrieval = RelayRetrievalMailbox("R".repeat(43), expiry)
    private val clock = object : RelayClock {
        override fun currentTimeMillis(): Long = now
    }

    @Test
    fun parsesActualRelayJsonAndAcknowledgesWithRetrievalCapability(): Unit = runBlocking {
        val requests = mutableListOf<RelayHttpRequest>()
        val responses = ArrayDeque(
            listOf(
                RelayHttpResponse(
                    200,
                    "{\"envelopes\":[{\"envelope\":\"AAECAw\",\"id\":\"${"A".repeat(22)}\"}]}"
                ),
                RelayHttpResponse(204, "")
            )
        )
        val executor = object : RelayHttpExecutor {
            override fun execute(
                endpoint: RelayEndpoint,
                request: RelayHttpRequest
            ): RelayHttpResponse {
                requests += request
                return responses.removeFirst()
            }
        }
        val relay =
            HttpRelayTransport(RelayEndpoint.production("https://relay.example"), executor, clock)
        val messages = relay.receive(retrieval)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), messages.single().envelope.copyBytes())
        relay.acknowledge(retrieval, messages.single().id)
        assertEquals("DELETE", requests.last().method)
        assertEquals(retrieval.authorizationValue(), requests.last().authorizationCapability)
        assertFalse(requests.last().toString().contains(retrieval.authorizationValue()))
    }

    @Test
    fun invalidEncodingAndDuplicateIdsFailClosed(): Unit = runBlocking {
        fun client(body: String) = HttpRelayTransport(
            RelayEndpoint.production("https://relay.example"),
            object : RelayHttpExecutor {
                override fun execute(endpoint: RelayEndpoint, request: RelayHttpRequest) =
                    RelayHttpResponse(200, body)
            },
            clock
        )
        val id = "A".repeat(22)
        assertThrows(RelayProtocolException::class.java) {
            runBlocking {
                client("{\"envelopes\":[{\"id\":\"$id\",\"envelope\":\"AB\"}]}").receive(retrieval)
            }
        }
        val entry = "{\"id\":\"$id\",\"envelope\":\"AA\"}"
        assertThrows(RelayProtocolException::class.java) {
            runBlocking { client("{\"envelopes\":[$entry,$entry]}").receive(retrieval) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayEndpoint.production("http://relay.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayEndpoint.production("https://user:pass@relay.example")
        }
        Unit
    }
}
