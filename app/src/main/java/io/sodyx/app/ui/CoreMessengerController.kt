package io.sodyx.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sodyx.app.data.CoreContact
import io.sodyx.app.data.CoreContactState
import io.sodyx.app.data.CoreConversation
import io.sodyx.app.data.CoreMessengerRepository
import io.sodyx.app.security.ContactCard
import io.sodyx.app.security.ContactCardCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class CorePage { Connections, Create, MyCard, Verify, Chat, Close }

internal data class CoreUiState(
    val page: CorePage = CorePage.Connections,
    val contacts: List<CoreContact> = emptyList(),
    val conversation: CoreConversation? = null,
    val pendingCard: ContactCard? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null
)

internal class CoreMessengerController(
    private val repository: CoreMessengerRepository,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf(CoreUiState())
        private set

    fun refresh() {
        scope.launch {
            try {
                val contacts = repository.contacts().filter { it.state != CoreContactState.Closed }
                state = state.copy(contacts = contacts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(error = "Could not load connections.")
            }
        }
    }

    fun newConnection() {
        state = state.copy(page = CorePage.Create, error = null)
    }
    fun showCard() {
        state = state.copy(page = CorePage.MyCard, error = null)
    }
    fun showClose() {
        state = state.copy(page = CorePage.Close, error = null)
    }
    fun showChat() {
        state = state.copy(page = CorePage.Chat, error = null)
    }

    fun back() {
        if (state.busy) return
        state = when (state.page) {
            CorePage.Verify -> state.copy(page = CorePage.MyCard, pendingCard = null, error = null)
            CorePage.Close -> state.copy(page = CorePage.Chat, error = null)
            else -> state.copy(
                page = CorePage.Connections,
                conversation = null,
                pendingCard = null,
                draft = "",
                error = null
            )
        }
        if (state.page == CorePage.Connections) refresh()
    }

    fun setDraft(value: String) {
        if (value.length <= 8192) state = state.copy(draft = value)
    }

    fun create(alias: String, relayUrl: String) =
        run("Could not create the connection. Check the relay address.") {
            val conversation = repository.create(alias, relayUrl)
            state = state.copy(conversation = conversation, page = CorePage.MyCard)
            refresh()
        }

    fun open(contact: CoreContact) = run("Could not open this connection.") {
        val conversation = repository.conversation(contact.id)
        state = state.copy(
            conversation = conversation,
            draft = "",
            page = if (contact.state == CoreContactState.Ready) CorePage.Chat else CorePage.MyCard
        )
    }

    fun previewCard(text: String) {
        try {
            val card = ContactCardCodec.decode(text.trim())
            require(card.expiresAtEpochMillis > System.currentTimeMillis())
            state = state.copy(pendingCard = card, page = CorePage.Verify, error = null)
        } catch (_: Exception) {
            state = state.copy(error = "This contact card is invalid or expired.")
        }
    }

    fun confirmPeer() {
        val id = state.conversation?.contact?.id ?: return
        val card = state.pendingCard ?: return
        run("Could not verify this connection. Create fresh contact cards if it expired.") {
            val conversation = repository.connect(id, ContactCardCodec.encode(card))
            state =
                state.copy(conversation = conversation, pendingCard = null, page = CorePage.Chat)
            refresh()
        }
    }

    fun send() {
        val conversation = state.conversation ?: return
        val text = state.draft
        if (text.isBlank()) return
        run("Message is queued if saved. Use Refresh to retry delivery.") {
            try {
                val updated = repository.send(conversation.contact.id, text)
                state = state.copy(conversation = updated, draft = "")
            } catch (error: Exception) {
                val updated = repository.conversation(conversation.contact.id)
                state = state.copy(
                    conversation = updated,
                    draft = if (
                        updated.messages.count { it.senderLocal } >
                        conversation.messages.count { it.senderLocal }
                    ) {
                        ""
                    } else {
                        text
                    }
                )
                throw error
            }
        }
    }

    fun sync(manual: Boolean = true) {
        val id = state.conversation?.contact?.id ?: return
        if (state.busy || state.syncing ||
            state.conversation?.contact?.state != CoreContactState.Ready
        ) {
            return
        }
        state = state.copy(syncing = true, error = if (manual) null else state.error)
        scope.launch {
            try {
                val updated = repository.sync(id)
                if (state.conversation?.contact?.id ==
                    id
                ) {
                    state = state.copy(conversation = updated)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manual && state.conversation?.contact?.id == id) {
                    state =
                        state.copy(
                            error = "Could not refresh. Check the connection or session expiry."
                        )
                }
            } finally {
                state = state.copy(syncing = false)
            }
        }
    }

    fun close() {
        val id = state.conversation?.contact?.id ?: return
        run("Could not finish closing. Retry before leaving this screen.") {
            repository.close(id)
            state = state.copy(
                page = CorePage.Connections,
                conversation = null,
                draft = "",
                pendingCard = null
            )
            refresh()
        }
    }

    private fun run(errorMessage: String, action: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(error = errorMessage)
            } finally {
                state = state.copy(busy = false)
            }
        }
    }
}
