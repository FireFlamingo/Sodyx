package io.sodyx.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cancellingSessionEndRetainsSampleConversation() {
        compose.onNodeWithText("Quiet Falcon").performClick()
        compose.onNodeWithContentDescription("End session").performClick()
        compose.onNodeWithText("Keep this session").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Message draft").assertIsDisplayed()
    }

    @Test
    fun closingOneSampleSessionPreservesOtherConnections() {
        compose.onNodeWithText("Copper Sky").performClick()
        compose.onNodeWithContentDescription("End session").performClick()
        compose.onNodeWithText("End sample session").performScrollTo().performClick()
        compose.onNodeWithText("Copper Sky").performClick()
        compose.onNodeWithContentDescription("Message draft").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to conversations").performClick()
        compose.onNodeWithText("Quiet Falcon").performClick()
        compose.onNodeWithContentDescription("Message draft").assertIsDisplayed()
    }

    @Test
    fun emptyListStillOffersAnInvitationEntryPoint() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Sample conversations").performScrollTo().performClick()
        compose.onNodeWithText("Conversations").performClick()
        compose.onNodeWithText("Add your first connection").performScrollTo().performClick()
        compose.onNodeWithText("Create invitation").performScrollTo().performClick()
        compose.onNodeWithText(
            "Invitations will be single-use and short-lived. This preview does not create an invitation."
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun draftIsNeverSentAndIsNotRestoredAfterRecreation() {
        compose.onNodeWithText("Quiet Falcon").performClick()
        compose.onNodeWithContentDescription(
            "Message draft"
        ).performTextInput("Local preview draft")
        compose.onNodeWithContentDescription("Preview send").performClick()
        compose.onNodeWithText("Preview only. Your message was not sent.").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Quiet Falcon").performClick()
        compose.onNodeWithContentDescription("Message draft").assertIsDisplayed()
        compose.onNodeWithText("Local preview draft").assertDoesNotExist()
    }
}
