package io.sodyx.app.ui

/** Design fixtures only. These are not identity, relationship, or session domain models. */
internal data class PreviewConversation(
    val alias: String,
    val initials: String,
    val excerpt: String,
    val time: String,
    val unread: Boolean = false
)

internal val sampleConversations = listOf(
    PreviewConversation("Quiet Falcon", "QF", "The long way home, then.", "Now", true),
    PreviewConversation("Copper Sky", "CS", "A little room to think.", "14:32"),
    PreviewConversation("Frozen Lake", "FL", "No active session", "Yesterday")
)

internal data class PreviewMessage(val text: String, val outgoing: Boolean, val time: String)

internal val sampleMessages = listOf(
    PreviewMessage("Do you ever take the longer way home?", false, "16:04"),
    PreviewMessage("When I need a little more time between one thing and the next.", true, "16:05"),
    PreviewMessage("There's a path along the river. No traffic. Just trees.", false, "16:06"),
    PreviewMessage("That sounds like the right kind of quiet.", true, "16:08"),
    PreviewMessage("The long way home, then.", false, "16:09")
)
