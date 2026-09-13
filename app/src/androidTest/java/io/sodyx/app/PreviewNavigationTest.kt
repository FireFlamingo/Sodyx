package io.sodyx.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.domain.DisplayAlias
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private lateinit var repository: SQLiteSodyxRepository
    private val databaseRule = object : ExternalResource() {
        override fun before() {
            databaseName = "sodyx-ui-${UUID.randomUUID()}.db"
            repository = SQLiteSodyxRepository(context, databaseName)
            repository.createLocalTestRelationship(DisplayAlias("Quiet Falcon"))
            val copper = repository.createLocalTestRelationship(DisplayAlias("Copper Sky"))
            val session = repository.startSession(copper)
            repository.appendLocalMessage(session.id, "Copper survives locally")
            MainActivity.repositoryOverride = repository
        }
        override fun after() {
            MainActivity.repositoryOverride = null
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(databaseRule).around(composeRule)

    @Test fun cancelingSessionStartKeepsSavedMessages() {
        composeRule.onNodeWithText("Copper Sky").performClick()
        composeRule.onNodeWithText("Copper survives locally").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to conversations").performClick()
        composeRule.onNodeWithText("Copper Sky").performClick()
        composeRule.onNodeWithText("Copper survives locally").assertIsDisplayed()
    }

    @Test fun savePersistsAcrossActivityRecreationAndDraftIsNotRestored() {
        composeRule.onNodeWithText("Quiet Falcon").performClick()
        composeRule.onNodeWithText("Start new session").performClick()
        composeRule.onNodeWithContentDescription("Message draft").performTextInput("saved locally")
        composeRule.onNodeWithContentDescription("Save locally").performClick()
        composeRule.onNodeWithText("saved locally").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Quiet Falcon").performClick()
        composeRule.onNodeWithText("saved locally").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Message draft").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
    }

    @Test fun endingSessionDeletesMessagesAndFreshSessionStartsEmptyAfterRecreation() {
        composeRule.onNodeWithText("Copper Sky").performClick()
        composeRule.onNodeWithContentDescription("End session").performClick()
        composeRule.onNodeWithText("End local session").performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Copper Sky").performClick()
        composeRule.onNodeWithText("Start new session").performClick()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Copper survives locally").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithContentDescription("Message draft").assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class EmptyConnectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private lateinit var repository: SQLiteSodyxRepository
    private val databaseRule = object : ExternalResource() {
        override fun before() {
            databaseName = "sodyx-empty-${UUID.randomUUID()}.db"
            repository = SQLiteSodyxRepository(context, databaseName)
            MainActivity.repositoryOverride = repository
        }
        override fun after() {
            MainActivity.repositoryOverride = null
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(databaseRule).around(composeRule)

    @Test fun emptyDatabaseCreatesAndPersistsTestConnection() {
        composeRule.onNodeWithText("Create test connection").performClick()
        composeRule.onNodeWithText(
            "Connection name"
        ).performClick().performTextInput("New connection")
        composeRule.onNodeWithText("Create test connection").performClick()
        composeRule.onNodeWithText("New connection").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("New connection").assertIsDisplayed()
    }
}
