package io.sodyx.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sodyx.app.data.InvitationRow
import io.sodyx.app.data.MessageRow
import io.sodyx.app.data.RedemptionResult
import io.sodyx.app.data.RelationshipRow
import io.sodyx.app.data.SessionRow
import io.sodyx.app.data.SodyxRepository
import io.sodyx.domain.DisplayAlias
import java.lang.System.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocalSessionUiState(
    val relationships: List<RelationshipRow> = emptyList(),
    val selected: RelationshipRow? = null,
    val session: SessionRow? = null,
    val messages: List<MessageRow> = emptyList(),
    val draft: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val reducedMotion: Boolean = false,
    val invitations: List<InvitationRow> = emptyList(),
    val invitationResult: RedemptionResult? = null
)

internal class LocalSessionController(
    private val repository: SodyxRepository,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf(LocalSessionUiState())
        private set

    private var refreshQueued = false

    fun setDraft(value: String) {
        if (!state.busy && value.length <= 1000) state = state.copy(draft = value)
    }

    fun setReducedMotion(value: Boolean) {
        state = state.copy(reducedMotion = value)
    }

    fun clearInvitationResult() {
        state = state.copy(invitationResult = null)
    }

    fun refresh() {
        if (state.busy) {
            refreshQueued = true
            return
        }
        val selectedId = state.selected?.id
        runExclusive("Could not load local data.", {
            val rows = repository.listRelationships()
            val selected = rows.firstOrNull { it.id == selectedId }
            val active = selected?.let { repository.activeSession(it.id) }
            val messages = active?.let { repository.loadMessages(it.id) }.orEmpty()
            RefreshResult(rows, selected, active, messages, repository.listInvitations())
        }) { result ->
            state =
                state.copy(
                    relationships = result.rows,
                    selected = result.selected,
                    session = result.session,
                    messages = result.messages,
                    invitations = result.invitations
                )
        }
    }

    fun createInvitation(onCreated: () -> Unit) {
        runExclusive("Could not create the local invitation.", {
            repository.createInvitation(currentTimeMillis())
        }) { invitation ->
            state = state.copy(invitations = state.invitations + invitation)
            onCreated()
        }
    }

    fun redeemInvitation(
        payload: String,
        alias: DisplayAlias,
        onResult: (RedemptionResult) -> Unit
    ) {
        if (state.busy) return
        runExclusive("Could not use this local invitation.", {
            repository.redeemInvitation(payload, alias, currentTimeMillis())
        }) { result ->
            state = state.copy(invitationResult = result)
            onResult(result)
            refresh()
        }
    }

    fun open(row: RelationshipRow, onOpened: () -> Unit) {
        if (state.busy) return
        val relationshipId = row.id
        runExclusive("Could not open this local session.", {
            val current =
                repository.listRelationships().firstOrNull { it.id == relationshipId } ?: row
            val active = repository.activeSession(relationshipId)
            val messages = active?.let { repository.loadMessages(it.id) }.orEmpty()
            Triple(current, active, messages)
        }) { (current, active, messages) ->
            state =
                state.copy(selected = current, session = active, messages = messages, draft = "")
            onOpened()
        }
    }

    fun startSession(onOpened: () -> Unit) {
        val relationshipId = state.selected?.id ?: return
        runExclusive("Could not start this local session.", {
            val session = repository.startSession(relationshipId)
            Triple(session, repository.loadMessages(session.id), repository.listRelationships())
        }) { (session, messages, rows) ->
            state =
                state.copy(relationships = rows, session = session, messages = messages, draft = "")
            onOpened()
        }
    }

    fun saveDraft(onSaved: () -> Unit) {
        val sessionId = state.session?.id ?: return
        val text = state.draft
        if (text.isBlank() || state.busy) return
        runExclusive("Could not save this draft.", {
            val saved = repository.appendLocalMessage(sessionId, text)
            saved
        }) { saved ->
            state = state.copy(messages = state.messages + saved, draft = "")
            onSaved()
        }
    }

    fun createRelationship(alias: DisplayAlias, onCreated: () -> Unit) {
        if (alias.value.isBlank() || state.busy) return
        runExclusive("Could not create the local connection.", {
            repository.createLocalTestRelationship(alias)
            Unit
        }) {
            onCreated()
            refresh()
        }
    }

    fun endSession(onEnded: () -> Unit) {
        val sessionId = state.session?.id ?: return
        runExclusive("Could not end this local session.", {
            repository.endSession(sessionId)
            Unit
        }) {
            state = state.copy(session = null, messages = emptyList(), draft = "")
            onEnded()
            refresh()
        }
    }

    private data class RefreshResult(
        val rows: List<RelationshipRow>,
        val selected: RelationshipRow?,
        val session: SessionRow?,
        val messages: List<MessageRow>,
        val invitations: List<InvitationRow>
    )

    private fun <T> runExclusive(message: String, work: () -> T, apply: (T) -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        scope.launch {
            var cancelled = false
            try {
                val result = withContext(Dispatchers.IO) { work() }
                apply(result)
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            } catch (_: Throwable) {
                state = state.copy(error = message)
            } finally {
                if (!cancelled) state = state.copy(busy = false)
                if (!cancelled && refreshQueued && scope.isActive) {
                    refreshQueued = false
                    refresh()
                }
            }
        }
    }
}
