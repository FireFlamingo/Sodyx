package io.sodyx.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.data.MessageRow
import io.sodyx.app.data.RelationshipRow
import io.sodyx.app.data.SessionRow
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.Eyebrow
import io.sodyx.app.ui.design.IconAction
import io.sodyx.app.ui.design.PageTitle
import io.sodyx.app.ui.design.QuietEmpty
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol

@Composable
internal fun ConversationList(
    rows: List<RelationshipRow>,
    onAdd: () -> Unit,
    onOpen: (RelationshipRow) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SodyxSpace.Large)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = SodyxSpace.Normal),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Copy("sodyx", style = SodyxType.Name)
                IconAction("Add connection", Symbol.Plus, onAdd)
            }
            Rule()
            Spacer(Modifier.height(SodyxSpace.Section))
            PageTitle("Conversations", "A space for two.")
            Spacer(Modifier.height(SodyxSpace.Section))
        }
        if (rows.isEmpty()) {
            item {
                QuietEmpty(
                    "Room for someone.",
                    "Connections are stored only on this device.",
                    "Create test connection",
                    onAdd
                )
            }
        } else {
            item {
                Eyebrow("YOUR CONNECTIONS")
                Spacer(Modifier.height(SodyxSpace.Normal))
            }
            items(rows, key = { it.id.value }) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onOpen(row)
                    }.padding(vertical = SodyxSpace.Large),
                    horizontalArrangement = Arrangement.spacedBy(SodyxSpace.Normal)
                ) {
                    Box(
                        Modifier.size(SodyxSpace.Touch).background(SodyxColor.Surface),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Copy(
                            row.alias.value.take(2).uppercase(),
                            style = SodyxType.Caption,
                            color = SodyxColor.Secondary
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Copy(row.alias.value, style = SodyxType.Name)
                        if (row.peerPseudonym.isNotBlank()) {
                            Copy(
                                "PX-${row.peerPseudonym.replace("-", "").take(8).uppercase()}",
                                style = SodyxType.Caption,
                                color = SodyxColor.Secondary
                            )
                        }
                        Copy(
                            if (row.activeSessionId ==
                                null
                            ) {
                                "No active session"
                            } else {
                                "Local session"
                            },
                            style = SodyxType.Caption,
                            color = SodyxColor.Secondary
                        )
                    }
                }
                Rule()
            }
        }
    }
}

@Composable
internal fun ConversationScreen(
    row: RelationshipRow?,
    session: SessionRow?,
    messages: List<MessageRow>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onSave: () -> Unit,
    busy: Boolean = false
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(SodyxSpace.Small)) {
            IconAction("Back to conversations", Symbol.Back, onBack)
            Column(Modifier.weight(1f).padding(SodyxSpace.Small)) {
                Copy(row?.alias?.value.orEmpty(), style = SodyxType.Name)
                row?.peerPseudonym?.takeIf { it.isNotBlank() }?.let {
                    Copy(
                        "PX-${it.replace("-", "").take(8).uppercase()}",
                        style = SodyxType.Caption,
                        color = SodyxColor.Secondary
                    )
                }
                Copy(
                    "Local session · stored unencrypted",
                    style = SodyxType.Caption,
                    color = SodyxColor.Secondary
                )
            }
            if (session !=
                null
            ) {
                IconAction("End session", Symbol.Close, onEnd)
            }
        }
        Rule()
        LazyColumn(Modifier.weight(1f).padding(horizontal = SodyxSpace.Large)) {
            if (messages.isEmpty()) {
                item {
                    QuietEmpty(
                        if (session ==
                            null
                        ) {
                            "No active session."
                        } else {
                            "A fresh start."
                        },
                        if (session ==
                            null
                        ) {
                            "Start a new session to save messages on this device."
                        } else {
                            "Messages saved here stay on this device. Nothing is sent."
                        },
                        if (session ==
                            null
                        ) {
                            "Start new session"
                        } else {
                            "Back to conversations"
                        },
                        if (session ==
                            null
                        ) {
                            onStart
                        } else {
                            onBack
                        }
                    )
                }
            }
            items(messages, key = {
                it.id.value
            }) { message ->
                Box(
                    Modifier.fillMaxWidth().padding(
                        vertical = SodyxSpace.Small
                    ).background(
                        if (message.senderLocal) SodyxColor.Raised else SodyxColor.Surface
                    ).padding(SodyxSpace.Normal)
                ) {
                    Copy(message.text)
                }
            }
        }
        Rule()
        if (session !=
            null
        ) {
            Row(Modifier.fillMaxWidth().padding(SodyxSpace.Large)) {
                BasicTextField(
                    draft,
                    onDraftChange,
                    Modifier.weight(1f).semantics {
                        contentDescription =
                            "Message draft"
                    }.padding(SodyxSpace.Normal),
                    textStyle = SodyxType.Body.copy(color = SodyxColor.Ink),
                    cursorBrush = SolidColor(SodyxColor.Accent),
                    maxLines = 4,
                    enabled = !busy,
                    decorationBox = { field ->
                        if (draft.isEmpty()) {
                            Copy(
                                "Write a local message…",
                                color = SodyxColor.Secondary
                            )
                        }
                        field()
                    }
                )
                IconAction(
                    label = "Save locally",
                    symbol = Symbol.Arrow,
                    onClick = { if (!busy) onSave() },
                    enabled = !busy
                )
            }
        }
    }
}
