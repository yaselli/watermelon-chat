package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationHistoryPlanTest {
    private fun conversation(turns: Int = 300) = Conversation(assistantId = Uuid.random(),
        messageNodes = (1..turns).flatMap { listOf(MessageNode.of(UIMessage.user("Q$it")),
            MessageNode.of(UIMessage.assistant("A$it"))) })

    @Test fun `trim preserves summary and exact recent messages across subsequent compaction`() {
        val source = conversation()
        val compacted = source.copy(contextCompaction = source.planCompaction(200).result("old facts"))
        val trimmed = compacted.withHistoryRemoved(compacted.planHistoryCleanup(200))
        assertEquals(200, trimmed.messageNodes.size)
        assertEquals(source.messageNodes.takeLast(200), trimmed.messageNodes)
        val context = selectCompactedContext(trimmed.currentMessages, trimmed.contextCompaction)
        assertEquals("old facts", context.summary)
        assertEquals(trimmed.currentMessages, context.messages)
        assertTrue(trimmed.contextCompaction!!.includesArchivedHistory)
        val next = trimmed.planCompaction(100)
        assertEquals("old facts", next.previousSummary)
        val again = trimmed.copy(contextCompaction = next.result("merged facts"))
            .withHistoryRemoved(trimmed.planHistoryCleanup(100))
        assertEquals("merged facts", selectCompactedContext(again.currentMessages, again.contextCompaction).summary)
        assertEquals(100, again.messageNodes.size)
    }

    @Test fun `summary covering retained messages keeps its valid boundary`() {
        val source = conversation()
        val compacted = source.copy(contextCompaction = source.planCompaction(100).result("facts"))
        val trimmed = compacted.withHistoryRemoved(compacted.planHistoryCleanup(200))
        assertEquals(100, trimmed.contextCompaction!!.coveredMessageCount)
        assertTrue(requireNotNull(trimmed.contextCompaction).matches(trimmed.currentMessages))
        assertEquals(100, selectCompactedContext(trimmed.currentMessages, trimmed.contextCompaction).messages.size)
    }

    @Test fun `uncovered or edited history cannot be removed`() {
        val source = conversation()
        val plan = source.planHistoryCleanup(200)
        assertTrue(runCatching { source.withHistoryRemoved(plan) }.isFailure)
        val short = source.copy(contextCompaction = source.planCompaction(300).result("partial"))
        assertTrue(runCatching { short.withHistoryRemoved(plan) }.isFailure)
        val compacted = source.copy(contextCompaction = source.planCompaction(200).result("facts"))
        val edited = compacted.copy(messageNodes = compacted.messageNodes.toMutableList().apply {
            this[0] = MessageNode.of(UIMessage.user("edited"))
        })
        assertTrue(runCatching { edited.withHistoryRemoved(plan) }.isFailure)
    }

    @Test fun `cut retreats to whole turn and refuses unfinished tools`() {
        val source = conversation().let { it.copy(messageNodes = it.messageNodes + MessageNode.of(UIMessage.user("new"))) }
        assertEquals(201, source.planHistoryCleanup(200).keepCount)
        val pending = conversation().let { it.copy(messageNodes = it.messageNodes.toMutableList().apply {
            this[1] = MessageNode.of(UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Tool("pending", "tap", "{}"))))
        }) }
        assertTrue(runCatching { pending.planHistoryCleanup(200) }.isFailure)
    }

    @Test fun `reminders obey threshold snooze and persistent opt out regardless of tokens`() {
        val source = conversation()
        assertTrue(source.shouldRemindHistory())
        assertFalse(source.copy(config = ConversationConfig(historyReminderThreshold = 700)).shouldRemindHistory())
        assertTrue(source.copy(config = ConversationConfig(historyReminderThreshold = 500)).shouldRemindHistory())
        assertFalse(source.copy(config = ConversationConfig(historyReminderAfter = 1200)).shouldRemindHistory())
        assertFalse(source.copy(config = ConversationConfig(historyReminderDisabled = true)).shouldRemindHistory())
        assertTrue(runCatching { source.planHistoryCleanup(0) }.isFailure)
    }
}
