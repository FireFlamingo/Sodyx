package io.sodyx.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sodyx.app.data.CoreContactState
import io.sodyx.app.data.CoreMessengerRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreMessengerUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = listOf("ui-core-${UUID.randomUUID()}.db", "ui-peer-${UUID.randomUUID()}.db")
    private val relay = MemoryRelay()
    private val local = CoreMessengerRepository(context, names[0]) { relay }
    private val peer = CoreMessengerRepository(context, names[1]) { relay }
    private val fixture = object : ExternalResource() {
        override fun before() {
            MainActivity.repositoryOverride = null
            MainActivity.coreRepositoryOverride = local
        }

        override fun after() {
            MainActivity.coreRepositoryOverride = null
            runBlocking {
                listOf(local, peer).forEach { repository ->
                    repository.contacts().filter { it.state != CoreContactState.Closed }
                        .forEach { repository.close(it.id) }
                    repository.dispose()
                }
            }
            names.forEach { context.deleteDatabase(it) }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test
    fun userPairsSendsReadsRestoresAndCloses() {
        compose.onNodeWithText("Add connection").performClick()
        compose.onNodeWithContentDescription("Connection name").performTextInput("Bob")
        compose.onNodeWithContentDescription(
            "Relay HTTPS address"
        ).performTextInput("https://relay.example")
        compose.onNodeWithText("Create my contact card").performClick()
        waitFor("My verification code")
        val remote = runBlocking { peer.create("Alice", "https://relay.example") }
        val contact = runBlocking { local.contacts().single() }
        val myCard = runBlocking { local.conversation(contact.id).myCard }
        compose.onNodeWithContentDescription(
            "Paste their contact card"
        ).performTextInput(remote.myCard)
        compose.onNodeWithText("Review their card").performClick()
        compose.onNodeWithText("The codes match").performClick()
        waitFor("End-to-end encrypted")
        runBlocking { peer.connect(remote.contact.id, myCard) }
        compose.onNodeWithContentDescription("Message draft").performTextInput("from the screen")
        compose.onNodeWithContentDescription("Send encrypted message").performClick()
        waitFor("Relay accepted")
        assertEquals(
            "from the screen",
            runBlocking {
                peer.sync(remote.contact.id).messages.single().plaintext.decodeToString()
            }
        )
        runBlocking { peer.send(remote.contact.id, "reply to the screen") }
        compose.onNodeWithText("Refresh").performClick()
        waitFor("reply to the screen")
        compose.activityRule.scenario.recreate()
        waitFor("Bob")
        compose.onNodeWithText("Bob").performClick()
        waitFor("reply to the screen")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val callsWhileStopped = relay.receiveCalls
        // Observe beyond the production polling interval while the activity is stopped.
        Thread.sleep(11_000)
        assertEquals(callsWhileStopped, relay.receiveCalls)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(
            compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )
        compose.onNodeWithContentDescription("Close connection").performClick()
        compose.onNodeWithText("Erase and close").performClick()
        waitFor("Exchange a contact card to begin.")
        assertEquals(CoreContactState.Closed, runBlocking { local.contacts().single().state })
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
