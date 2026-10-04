package io.sodyx.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.data.CoreContactState
import io.sodyx.app.data.CoreMessengerRepository
import io.sodyx.app.security.ContactCardCodec
import io.sodyx.app.ui.design.Action
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun CoreMessengerApp(repository: CoreMessengerRepository, resumeGeneration: Int = 0) {
    val scope = rememberCoroutineScope()
    val controller = remember(repository, scope) { CoreMessengerController(repository, scope) }
    val state = controller.state
    LaunchedEffect(resumeGeneration) { controller.refresh() }
    LaunchedEffect(state.conversation?.contact?.id, state.page) {
        if (state.page == CorePage.Chat) {
            controller.sync(false)
            while (isActive) {
                delay(10_000)
                controller.sync(false)
            }
        }
    }
    BackHandler(state.page != CorePage.Connections) { controller.back() }
    Box(
        Modifier.fillMaxSize().background(SodyxColor.Background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = SodyxSpace.ContentWidth).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (state.page) {
                    CorePage.Connections -> CoreConnectionList(state, controller)
                    CorePage.Create -> CoreCreateScreen(state.busy, controller)
                    CorePage.MyCard -> CoreCardScreen(state, controller)
                    CorePage.Verify -> CoreVerificationScreen(state, controller)
                    CorePage.Chat -> CoreChatScreen(state, controller)
                    CorePage.Close -> CoreCloseScreen(state, controller)
                }
            }
            if (state.busy) Copy("Working…", Modifier.padding(SodyxSpace.Normal), SodyxType.Caption)
            state.error?.let {
                Copy(it, Modifier.padding(SodyxSpace.Normal), SodyxType.Caption, SodyxColor.Danger)
            }
        }
    }
}

@Composable
private fun CoreConnectionList(state: CoreUiState, controller: CoreMessengerController) {
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            Copy("sodyx", style = SodyxType.Name)
            Spacer(Modifier.height(SodyxSpace.Section))
            PageTitle("Connections", "Private conversations for two.")
            Spacer(Modifier.height(SodyxSpace.Large))
            Action(
                "Add connection",
                controller::newConnection,
                primary = true,
                enabled = !state.busy
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            if (state.contacts.isEmpty()) {
                Copy(
                    "Exchange a contact card to begin.",
                    color = SodyxColor.Secondary
                )
            }
        }
        items(state.contacts, key = { it.id }) { contact ->
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                    controller.open(contact)
                }
                    .padding(vertical = SodyxSpace.Large)
            ) {
                Copy(contact.alias, style = SodyxType.Name)
                Copy(
                    if (contact.state ==
                        CoreContactState.Ready
                    ) {
                        "Verified connection"
                    } else {
                        "Finish pairing"
                    },
                    style = SodyxType.Caption,
                    color = SodyxColor.Secondary
                )
            }
            Rule()
        }
    }
}

@Composable
private fun CoreCreateScreen(busy: Boolean, controller: CoreMessengerController) {
    var alias by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, controller::back, !busy)
            PageTitle("A connection for two.", "Each connection gets a separate identity.")
            Spacer(Modifier.height(SodyxSpace.Large))
            CoreTextField("Connection name", alias, { if (it.length <= 64) alias = it }, !busy)
            CoreTextField("Relay HTTPS address", relayUrl, {
                if (it.length <=
                    2048
                ) {
                    relayUrl = it
                }
            }, !busy)
            Copy(
                "Use the HTTPS address of your Sodyx relay. No account or phone number is required.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action(
                "Create my contact card",
                { controller.create(alias, relayUrl.trim()) },
                primary = true,
                enabled = !busy && alias.isNotBlank() && relayUrl.length > 8
            )
        }
    }
}

@Composable
private fun CoreCardScreen(state: CoreUiState, controller: CoreMessengerController) {
    val conversation = state.conversation ?: return
    val context = LocalContext.current
    var peerCard by remember(conversation.contact.id) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, controller::back, !state.busy)
            PageTitle("Exchange contact cards.", conversation.contact.alias)
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy("My verification code", style = SodyxType.Caption)
            SelectionContainer { Copy(conversation.myVerificationCode, style = SodyxType.Caption) }
            Spacer(Modifier.height(SodyxSpace.Normal))
            Action("Share my card", {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, conversation.myCard)
                }
                context.startActivity(Intent.createChooser(intent, "Share with this person"))
            }, enabled = !state.busy)
            Copy(
                "Share only with this person. Both of you create a card and verify the other's code.",
                color = SodyxColor.Secondary
            )
            if (conversation.contact.state == CoreContactState.Pairing) {
                Spacer(Modifier.height(SodyxSpace.Large))
                CoreTextField("Paste their contact card", peerCard, {
                    if (it.length <=
                        11_000
                    ) {
                        peerCard = it
                    }
                }, !state.busy, singleLine = false)
                Action(
                    "Review their card",
                    {
                        controller.previewCard(peerCard)
                    },
                    primary = true,
                    enabled =
                    !state.busy && peerCard.isNotBlank()
                )
            } else {
                Action(
                    "Back to conversation",
                    controller::showChat,
                    primary = true,
                    enabled = !state.busy
                )
            }
            Copy(
                "Expires ${DateFormat.getDateTimeInstance().format(
                    Date(conversation.expiresAtEpochMillis)
                )}",
                style = SodyxType.Caption,
                color = SodyxColor.Secondary
            )
        }
    }
}

@Composable
private fun CoreVerificationScreen(state: CoreUiState, controller: CoreMessengerController) {
    val card = state.pendingCard ?: return
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            IconAction("Back", Symbol.Back, controller::back, !state.busy)
            PageTitle(
                "Compare this code.",
                "Ask the other person to read their own verification code."
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            SelectionContainer {
                Copy(ContactCardCodec.verificationCode(card), style = SodyxType.Name)
            }
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "Compare in person or over a separate trusted call. A card received through an untrusted channel can be replaced.",
                color = SodyxColor.Secondary
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action(
                "The codes match",
                controller::confirmPeer,
                primary = true,
                enabled = !state.busy
            )
        }
    }
}

@Composable
private fun CoreChatScreen(state: CoreUiState, controller: CoreMessengerController) {
    val conversation = state.conversation ?: return
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(SodyxSpace.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconAction("Back to connections", Symbol.Back, controller::back, !state.busy)
            Column(Modifier.weight(1f)) {
                Copy(conversation.contact.alias, style = SodyxType.Name)
                Copy(
                    "End-to-end encrypted",
                    style = SodyxType.Caption,
                    color = SodyxColor.Secondary
                )
            }
            IconAction("Close connection", Symbol.Close, controller::showClose, !state.busy)
        }
        Rule()
        Row(
            Modifier.padding(horizontal = SodyxSpace.Normal),
            horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
        ) {
            Action(
                "Refresh",
                { controller.sync() },
                Modifier.weight(1f),
                enabled =
                !state.busy && !state.syncing
            )
            Action("My card", controller::showCard, Modifier.weight(1f), enabled = !state.busy)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = SodyxSpace.Large)) {
            if (conversation.messages.isEmpty()) {
                item {
                    Copy(
                        "Ready when you both finish pairing.",
                        Modifier.padding(vertical = SodyxSpace.Large),
                        color = SodyxColor.Secondary
                    )
                }
            }
            items(conversation.messages, key = { it.id }) { message ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Small)
                        .background(
                            if (message.senderLocal) SodyxColor.Raised else SodyxColor.Surface
                        )
                        .padding(SodyxSpace.Normal)
                ) {
                    Copy(message.plaintext.decodeToString())
                    Copy(
                        if (!message.senderLocal) {
                            "Received"
                        } else if (message.delivered) {
                            "Relay accepted"
                        } else {
                            "Queued"
                        },
                        style = SodyxType.Caption,
                        color = SodyxColor.Secondary
                    )
                }
            }
        }
        Rule()
        Row(Modifier.padding(SodyxSpace.Normal), verticalAlignment = Alignment.CenterVertically) {
            CoreTextField(
                "Message draft",
                state.draft,
                controller::setDraft,
                !state.busy,
                modifier = Modifier.weight(1f),
                singleLine = false
            )
            IconAction(
                "Send encrypted message",
                Symbol.Arrow,
                controller::send,
                !state.busy && state.draft.isNotBlank()
            )
        }
    }
}

@Composable
private fun CoreCloseScreen(state: CoreUiState, controller: CoreMessengerController) {
    LazyColumn(Modifier.fillMaxSize().padding(SodyxSpace.Large)) {
        item {
            PageTitle("Close this connection?", state.conversation?.contact?.alias.orEmpty())
            Spacer(Modifier.height(SodyxSpace.Large))
            Copy(
                "This erases this connection's messages and keys on this device. Relay ciphertext expires, and copies on another device remain."
            )
            Spacer(Modifier.height(SodyxSpace.Large))
            Action("Keep connection", controller::showChat, enabled = !state.busy)
            Action("Erase and close", controller::close, destructive = true, enabled = !state.busy)
        }
    }
}

@Composable
private fun CoreTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    Column(modifier.padding(vertical = SodyxSpace.Normal)) {
        Copy(label, style = SodyxType.Caption, color = SodyxColor.Secondary)
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth().background(SodyxColor.Surface).padding(SodyxSpace.Normal)
                .semantics { contentDescription = label },
            enabled = enabled,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 5,
            textStyle = SodyxType.Body.copy(color = SodyxColor.Ink),
            cursorBrush = SolidColor(SodyxColor.Accent)
        )
    }
}
