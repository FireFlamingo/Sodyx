package io.sodyx.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sodyx.app.data.RedemptionResult
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.InvitationId
import io.sodyx.domain.InvitationPayload
import io.sodyx.domain.PairwiseIdentityId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String
    private lateinit var repository: SQLiteSodyxRepository
    private val databaseRule = object : ExternalResource() {
        override fun before() {
            name = "sodyx-invite-ui-${UUID.randomUUID()}.db"
            repository = SQLiteSodyxRepository(context, name)
            MainActivity.repositoryOverride = repository
        }
        override fun after() {
            MainActivity.repositoryOverride = null
            repository.close()
            context.deleteDatabase(name)
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(databaseRule).around(compose)

    @Test fun createInvitationShowsQrPayloadAndPersists() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithText("Create one-time invitation").performClick()
        compose.onNodeWithText("Create invitation").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Invitation QR representation")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Invitation QR representation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Invitation payload").assertIsDisplayed()
        assertEquals(1, repository.listInvitations().size)
    }

    @Test fun localScanConfirmCreatesAliasAndPseudonymRelationship() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithText("Create one-time invitation").performClick()
        compose.onNodeWithText("Create invitation").performClick()
        compose.onNodeWithText("Use in local scan").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Connection alias")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Generated pseudonym").assertIsDisplayed()
        compose.onNodeWithText("PX-", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection alias").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connection alias").performTextInput("Copper Sky")
        compose.onNodeWithText("Create relationship").performClick()
        compose.onNodeWithText("Copper Sky").assertIsDisplayed()
        assertTrue(repository.listRelationships().single().peerPseudonym.isNotBlank())
        assertEquals("REDEEMED", repository.listInvitations().single().state.name)
    }

    @Test fun repeatRedemptionReportsAlreadyUsedWithoutSecondRelationship() {
        val invitation = repository.createInvitation(1_000, 60_000)
        repository.redeemInvitation(invitation.payload.encode(), DisplayAlias("First"), 2_000)
        assertEquals(1, repository.listRelationships().size)
        assert(
            repository.redeemInvitation(
                invitation.payload.encode(),
                DisplayAlias("Second"),
                2_001
            ) ==
                RedemptionResult.AlreadyRedeemed
        )
        assertEquals(1, repository.listRelationships().size)
    }

    @Test fun malformedScanShowsInvalidMessageWithoutCreatingRelationship() {
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithText("Scan invitation").performClick()
        compose.onNodeWithContentDescription("Invitation payload").performTextInput("not-a-payload")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Invitation is invalid or not recognized.").assertIsDisplayed()
        assertEquals(0, repository.listRelationships().size)
    }

    @Test fun expiredScanShowsExpiredMessageWithoutCreatingRelationship() {
        val expired = InvitationPayload(
            InvitationId(UUID.randomUUID()),
            UUID.randomUUID(),
            PairwiseIdentityId(UUID.randomUUID()),
            1,
            2
        ).encode()
        compose.onNodeWithContentDescription("Add connection").performClick()
        compose.onNodeWithText("Scan invitation").performClick()
        compose.onNodeWithContentDescription("Invitation payload").performTextInput(expired)
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Invitation expired. Create a new one.").assertIsDisplayed()
        assertEquals(0, repository.listRelationships().size)
    }
}
