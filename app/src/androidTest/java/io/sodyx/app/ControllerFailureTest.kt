package io.sodyx.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sodyx.app.data.MessageRow
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.app.data.SodyxRepository
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.RelationshipId
import io.sodyx.domain.SessionId
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

private class FailureRepository(private val delegate: SodyxRepository) :
    SodyxRepository by delegate {
    @Volatile var failAppend = false

    @Volatile var failEnd = false
    override fun appendLocalMessage(sessionId: SessionId, text: String): MessageRow {
        check(!failAppend) { "test failure" }
        return delegate.appendLocalMessage(sessionId, text)
    }
    override fun endSession(sessionId: SessionId) {
        check(!failEnd) { "test failure" }
        delegate.endSession(sessionId)
    }
}

@RunWith(AndroidJUnit4::class)
class ControllerFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String
    private lateinit var repository: FailureRepository
    private var relationship: RelationshipId? = null
    private val dbRule = object : ExternalResource() {
        override fun before() {
            name = "sodyx-controller-${UUID.randomUUID()}.db"
            val real = SQLiteSodyxRepository(context, name)
            relationship = real.createLocalTestRelationship(DisplayAlias("Failure case"))
            repository = FailureRepository(real)
            MainActivity.repositoryOverride = repository
        }
        override fun after() {
            MainActivity.repositoryOverride = null
            repository.close()
            context.deleteDatabase(name)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(dbRule).around(compose)

    @Test fun appendFailureShowsGenericErrorAndPreservesDraft() {
        compose.onNodeWithText("Failure case").performClick()
        compose.onNodeWithText("Start new session").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(
                "Message draft"
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Message draft").assertIsDisplayed()
        repository.failAppend = true
        compose.onNodeWithContentDescription("Message draft").performTextInput("keep draft")
        compose.onNodeWithContentDescription("Save locally").performClick()
        compose.onNodeWithText("Could not save this draft.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message draft").assertTextEquals("keep draft")
    }

    @Test fun endFailureLeavesMessageAndAllowsCancel() {
        val session = repository.startSession(requireNotNull(relationship))
        repository.appendLocalMessage(session.id, "keep message")
        compose.onNodeWithText("Failure case").performClick()
        compose.onNodeWithText("keep message").assertIsDisplayed()
        repository.failEnd = true
        compose.onNodeWithContentDescription("End session").performClick()
        compose.onNodeWithText("End local session").performClick()
        compose.onNodeWithText("Could not end this local session.").assertIsDisplayed()
        compose.onNodeWithText("Keep this session").performClick()
        compose.onNodeWithText("keep message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to conversations").performClick()
    }
}
