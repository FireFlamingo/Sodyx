package io.sodyx.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.LineIcon
import io.sodyx.app.ui.design.PreviewNote
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxMotion
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType
import io.sodyx.app.ui.design.Symbol

private enum class PreviewPage { Conversations, Chat, AddContact, Settings, EndSession }

@Composable
internal fun SodyxApp() {
    val expandedText = LocalDensity.current.fontScale >= 1.3f
    // Memory only: drafts and demonstration choices never enter saved-instance state or storage.
    var page by remember { mutableStateOf(PreviewPage.Conversations) }
    var contact by remember { mutableStateOf(sampleConversations.first()) }
    var samples by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(emptySet<String>()) }
    var reducedMotion by remember { mutableStateOf(false) }
    val back = {
        page =
            if (page == PreviewPage.EndSession) PreviewPage.Chat else PreviewPage.Conversations
    }
    BackHandler(page != PreviewPage.Conversations, back)

    Box(
        Modifier.fillMaxSize().background(SodyxColor.Background).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = SodyxSpace.ContentWidth).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(
                    page,
                    animationSpec = tween(if (reducedMotion) 0 else SodyxMotion.SCREEN_MILLIS),
                    label = "Page"
                ) { destination ->
                    when (destination) {
                        PreviewPage.Conversations -> ConversationList(samples, ended, {
                            page =
                                PreviewPage.AddContact
                        }) {
                            contact = it
                            page = PreviewPage.Chat
                        }
                        PreviewPage.Chat -> ConversationScreen(
                            contact,
                            contact.alias in ended,
                            back
                        ) {
                            page =
                                PreviewPage.EndSession
                        }
                        PreviewPage.AddContact -> AddContactScreen(back)
                        PreviewPage.Settings -> SettingsScreen(samples, reducedMotion, {
                            samples =
                                it
                        }, { reducedMotion = it }) {
                            ended = emptySet()
                            samples = true
                        }
                        PreviewPage.EndSession -> EndSessionScreen(contact.alias, back) {
                            ended =
                                ended + contact.alias
                            page = PreviewPage.Conversations
                        }
                    }
                }
            }
            PreviewNote()
            if (page == PreviewPage.Conversations || page == PreviewPage.Settings) {
                Rule()
                Row(Modifier.fillMaxWidth()) {
                    listOf(PreviewPage.Conversations, PreviewPage.Settings).forEach { tab ->
                        val active = page == tab
                        Row(
                            Modifier.weight(1f).semantics { selected = active }
                                .clickable(role = Role.Tab) { page = tab }
                                .padding(
                                    horizontal = SodyxSpace.Small,
                                    vertical = SodyxSpace.Normal
                                ),
                            horizontalArrangement = Arrangement.spacedBy(
                                SodyxSpace.Small,
                                Alignment.CenterHorizontally
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tint = if (active) SodyxColor.Accent else SodyxColor.Secondary
                            if (!expandedText) {
                                LineIcon(
                                    if (tab ==
                                        PreviewPage.Settings
                                    ) {
                                        Symbol.Settings
                                    } else {
                                        Symbol.Conversations
                                    },
                                    tint
                                )
                            }
                            Copy(
                                if (tab ==
                                    PreviewPage.Settings
                                ) {
                                    "Settings"
                                } else {
                                    "Conversations"
                                },
                                style = SodyxType.Caption,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    }
}
