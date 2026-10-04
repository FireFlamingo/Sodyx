package io.sodyx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sodyx.app.data.CoreContactState
import io.sodyx.app.data.CoreMessengerRepository
import io.sodyx.app.network.HttpRelayTransport
import io.sodyx.app.network.RelayEndpoint
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only when an actual relay is ADB-reversed to localhost:18080. */
@RunWith(AndroidJUnit4::class)
class CoreLiveRelayTest {
    @Test
    fun twoEndpointsExchangeThroughActualHttpRelay(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sodyx.liveRelay") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = listOf("live-alice-${UUID.randomUUID()}.db", "live-bob-${UUID.randomUUID()}.db")
        val peers = names.map { name ->
            CoreMessengerRepository(context, name) {
                HttpRelayTransport(RelayEndpoint.loopbackFixtureForTests("http://127.0.0.1:18080"))
            }
        }
        val alice = peers[0]
        val bob = peers[1]
        try {
            val a = alice.create("Bob", "https://fixture.invalid")
            val b = bob.create("Alice", "https://fixture.invalid")
            alice.connect(a.contact.id, b.myCard)
            bob.connect(b.contact.id, a.myCard)
            alice.send(a.contact.id, "over the actual relay")
            assertEquals(
                "over the actual relay",
                bob.sync(b.contact.id).messages.single().plaintext.decodeToString()
            )
            bob.send(b.contact.id, "reply over HTTP")
            assertEquals(
                "reply over HTTP",
                alice.sync(a.contact.id).messages.last().plaintext.decodeToString()
            )
        } finally {
            peers.forEach { repository ->
                repository.contacts().filter { it.state != CoreContactState.Closed }
                    .forEach { repository.close(it.id) }
                repository.dispose()
            }
            names.forEach { context.deleteDatabase(it) }
        }
    }
}
