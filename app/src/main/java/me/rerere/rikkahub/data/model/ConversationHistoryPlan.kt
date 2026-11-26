package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole

data class ConversationHistoryPlan(val removeCount: Int, val keepCount: Int)

/** Keep complete turns, including their tool continuations and all alternative answers. */
fun Conversation.planHistoryCleanup(keep: Int): ConversationHistoryPlan {
    require(keep in listOf(100, 200, 300)) { "请选择保留 100、200 或 300 条" }
    val messages = currentMessages
    var end = (messages.size - keep).coerceAtLeast(0)
    while (end > 0 && (messages[end].role != MessageRole.USER ||
            messages[end - 1].role != MessageRole.ASSISTANT ||
            messages[end - 1].getTools().any { !it.isExecuted })) end--
    require(end > 0) { "没有可清理的完整旧对话" }
    require(messageNodes.take(end).all { node -> node.messages.all { message ->
        message.getTools().all { it.isExecuted }
    } }) { "旧对话中还有未完成的工具调用，请先处理或取消" }
    return ConversationHistoryPlan(end, messages.size - end)
}

fun Conversation.withHistoryRemoved(plan: ConversationHistoryPlan): Conversation {
    require(plan.removeCount > 0 && plan.keepCount + plan.removeCount == messageNodes.size)
    val old = requireNotNull(contextCompaction) { "请先生成旧对话摘要" }
    require(old.matches(currentMessages) && old.coveredMessageCount >= plan.removeCount) {
        "摘要未覆盖待清理的对话，请先压缩上下文"
    }
    val remaining = messageNodes.drop(plan.removeCount)
    val covered = old.coveredMessageCount - plan.removeCount
    return copy(messageNodes = remaining, contextCompaction = old.copy(
        coveredMessageCount = covered,
        coveredPrefixHash = prefixHash(remaining.map { it.currentMessage }, covered),
        includesArchivedHistory = true,
    ), config = config?.copy(historyReminderAfter = 0))
}

fun Conversation.shouldRemindHistory(): Boolean {
    val threshold = config?.historyReminderThreshold?.takeIf { it in listOf(500, 600, 700) } ?: 600
    return config?.historyReminderDisabled != true && messageNodes.size >= threshold &&
        messageNodes.size >= (config?.historyReminderAfter ?: 0)
}
