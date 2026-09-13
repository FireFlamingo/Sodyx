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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.sodyx.app.data.SodyxRepository
import io.sodyx.app.ui.design.Copy
import io.sodyx.app.ui.design.PreviewNote
import io.sodyx.app.ui.design.Rule
import io.sodyx.app.ui.design.SodyxColor
import io.sodyx.app.ui.design.SodyxMotion
import io.sodyx.app.ui.design.SodyxSpace
import io.sodyx.app.ui.design.SodyxType

private enum class Page { Conversations, Chat, AddContact, Settings, EndSession }

@Composable
internal fun SodyxApp(repository: SodyxRepository, resumeGeneration: Int = 0) {
    val scope = rememberCoroutineScope()
    val controller = remember(repository, scope) { LocalSessionController(repository, scope) }
    val state = controller.state
    var page by remember { mutableStateOf(Page.Conversations) }
    LaunchedEffect(resumeGeneration) { controller.refresh() }
    BackHandler(page != Page.Conversations) {
        if (!state.busy) {
            page =
                if (page == Page.EndSession) Page.Chat else Page.Conversations
        }
    }
    Box(
        Modifier.fillMaxSize().background(SodyxColor.Background).safeDrawingPadding().imePadding(),
        Alignment.TopCenter
    ) {
        Column(Modifier.widthIn(max = SodyxSpace.ContentWidth).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(
                    targetState = page,
                    animationSpec = tween(
                        if (state.reducedMotion) 0 else SodyxMotion.SCREEN_MILLIS
                    ),
                    label = "Page"
                ) { destination ->
                    when (destination) {
                        Page.Conversations -> ConversationList(
                            state.relationships,
                            onAdd = { if (!state.busy) page = Page.AddContact },
                            onOpen = { row -> controller.open(row) { page = Page.Chat } }
                        )
                        Page.Chat -> ConversationScreen(
                            state.selected,
                            state.session,
                            state.messages,
                            state.draft,
                            controller::setDraft,
                            { if (!state.busy) page = Page.Conversations },
                            { if (!state.busy) controller.startSession {} },
                            { if (!state.busy) page = Page.EndSession },
                            { controller.saveDraft {} },
                            state.busy
                        )
                        Page.AddContact -> AddContactScreen(
                            onBack = { if (!state.busy) page = Page.Conversations },
                            onCreate = { alias ->
                                controller.createRelationship(alias) { page = Page.Conversations }
                            },
                            busy = state.busy
                        )
                        Page.Settings -> SettingsScreen(
                            state.reducedMotion,
                            controller::setReducedMotion
                        )
                        Page.EndSession -> EndSessionScreen(
                            state.selected?.alias?.value.orEmpty(),
                            { if (!state.busy) page = Page.Chat },
                            {
                                if (!state.busy) {
                                    controller.endSession { page = Page.Conversations }
                                }
                            },
                            state.busy
                        )
                    }
                }
            }
            state.error?.let {
                Copy(
                    it,
                    Modifier.padding(SodyxSpace.Small),
                    SodyxType.Caption,
                    SodyxColor.Danger
                )
            }
            PreviewNote()
            if (page == Page.Conversations || page == Page.Settings) {
                Rule()
                Row(Modifier.fillMaxWidth()) {
                    listOf(Page.Conversations, Page.Settings).forEach { tab ->
                        val selected = page == tab
                        Row(
                            Modifier.weight(1f).clickable(enabled = !state.busy, role = Role.Tab) {
                                page =
                                    tab
                            }.semantics {
                                this.selected = selected
                            }.padding(SodyxSpace.Normal),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Copy(
                                if (tab ==
                                    Page.Settings
                                ) {
                                    "Settings"
                                } else {
                                    "Conversations"
                                },
                                style = SodyxType.Caption,
                                color = if (selected) SodyxColor.Accent else SodyxColor.Secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
