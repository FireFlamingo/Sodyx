package io.sodyx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.Eyebrow
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.LineIcon
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.QuietEmpty
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxShape
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol
import io.sodyx.app.ui.design.TextAction

@Composable
internal fun ConversationList(
    showSamples: Boolean,
    endedAliases: Set<String>,
    onAdd: () -> Unit,
    onOpen: (PreviewConversation) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Normal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Copy("sodyx", style = SodyxType.Name)
                IconAction("Add connection", Symbol.Plus, onAdd)
            }
            Rule()
            Spacer(Modifier.height(SodyxSpace.Section))
            PageTitle("Conversations", "A space for two.")
            Spacer(Modifier.height(SodyxSpace.Section))
        }
        if (showSamples) {
            item {
                Eyebrow("YOUR CONNECTIONS")
                Spacer(Modifier.height(SodyxSpace.Normal))
            }
            items(sampleConversations, key = { it.alias }) { conversation ->
                ConversationRow(conversation, conversation.alias in endedAliases) {
                    onOpen(conversation)
                }
            }
            item {
                Spacer(Modifier.height(SodyxSpace.Large))
                TextAction("New connection", onAdd)
                Spacer(Modifier.height(SodyxSpace.Wide))
                Rule(subtle = true)
                Copy(
                    "A name shared with one person.\nA different one with the next.",
                    Modifier.padding(vertical = SodyxSpace.Large),
                    color = SodyxColor.Secondary
                )
            }
        } else {
            item {
                QuietEmpty(
                    "Room for someone.",
                    "Connections begin with an invitation, not a public username.",
                    "Add your first connection",
                    onAdd
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: PreviewConversation,
    ended: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClick = onClick
        ).padding(vertical = SodyxSpace.Large),
        horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Normal),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(
                SodyxSpace.Touch
            ).background(SodyxColor.Surface).border(SodyxSpace.Tiny / 4, SodyxColor.Hairline),
            contentAlignment = Alignment.Center
        ) {
            Copy(conversation.initials, style = SodyxType.Caption, color = SodyxColor.Secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SodyxSpace.Small)) {
            Copy(conversation.alias, style = SodyxType.Name)
            Copy(
                if (ended) "Session ended" else conversation.excerpt,
                style = SodyxType.Caption,
                color = SodyxColor.Secondary
            )
            if (conversation.unread &&
                !ended
            ) {
                Copy("2 new messages", style = SodyxType.Caption, color = SodyxColor.Accent)
            }
        }
        Copy(
            if (ended) "Ended" else conversation.time,
            Modifier.padding(top = SodyxSpace.Tiny),
            SodyxType.Caption,
            SodyxColor.Secondary
        )
    }
    Rule()
}

@Composable
internal fun ConversationScreen(
    contact: PreviewConversation,
    ended: Boolean,
    onBack: () -> Unit,
    onEnd: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var sendNotice by remember { mutableStateOf(false) }
    val inactive = ended || contact == sampleConversations.last()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = SodyxSpace.Medium,
                vertical = SodyxSpace.Small
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconAction("Back to conversations", Symbol.Back, onBack)
            Column(Modifier.weight(1f).padding(horizontal = SodyxSpace.Small)) {
                Copy(contact.alias, style = SodyxType.Name)
                Copy(
                    if (inactive) "No active session" else "Sample session · 01",
                    style = SodyxType.Caption,
                    color = SodyxColor.Secondary
                )
            }
            if (!inactive) IconAction("End session", Symbol.Close, onEnd)
        }
        Rule()
        if (inactive) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = SodyxSpace.Large)) {
                item {
                    QuietEmpty(
                        "A fresh start,\nwhen you're ready.",
                        "This sample connection remains. The session has no messages to show.",
                        "Back to conversations",
                        onBack
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = SodyxSpace.Large),
                verticalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Large),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SodyxSpace.Small)
                    ) {
                        Eyebrow("TODAY")
                        Copy(
                            "A conversation between two people.",
                            style = SodyxType.Caption,
                            color = SodyxColor.Secondary
                        )
                    }
                }
                items(sampleMessages) { MessageRow(it) }
                item { Spacer(Modifier.height(SodyxSpace.Normal)) }
            }
            if (sendNotice) {
                Copy(
                    "Preview only. Your message was not sent.",
                    Modifier.fillMaxWidth().padding(
                        horizontal = SodyxSpace.Large,
                        vertical = SodyxSpace.Small
                    ).semantics {
                        liveRegion =
                            LiveRegionMode.Polite
                    },
                    SodyxType.Caption,
                    SodyxColor.Accent
                )
            }
            Rule()
            Row(
                Modifier.fillMaxWidth().padding(
                    horizontal = SodyxSpace.Large,
                    vertical = SodyxSpace.Medium
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        if (it.length <= 1000) draft = it
                        sendNotice = false
                    },
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Message draft"
                    }.padding(vertical = SodyxSpace.Medium),
                    textStyle = SodyxType.Body.copy(color = SodyxColor.Ink),
                    cursorBrush = SolidColor(SodyxColor.Accent),
                    maxLines = 4,
                    decorationBox = { field ->
                        Box {
                            if (draft.isEmpty()) {
                                Copy(
                                    "Write something…",
                                    color = SodyxColor.Secondary
                                )
                            }
                            field()
                        }
                    }
                )
                IconAction("Preview send", Symbol.Arrow) { sendNotice = true }
            }
        }
    }
}

@Composable
private fun MessageRow(message: PreviewMessage) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            Modifier.fillMaxWidth(0.88f).widthIn(max = SodyxSpace.ContentWidth)
                .background(
                    if (message.outgoing) SodyxColor.Raised else SodyxColor.Surface,
                    SodyxShape.Message
                )
                .padding(SodyxSpace.Normal)
        ) { Copy(message.text) }
        Copy(
            if (message.outgoing) "You · ${message.time}" else message.time,
            Modifier.padding(top = SodyxSpace.Tiny),
            SodyxType.Caption,
            SodyxColor.Secondary
        )
    }
}
