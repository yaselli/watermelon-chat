package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ChatSticker

internal fun stickerTool(stickers: List<ChatSticker>) = Tool(
    name = "find_chat_sticker",
    description = "从本地表情库按名称或描述搜索。选择合适的一条，将返回的完整 Markdown 原样单独放在回复的一行，即可发送表情。",
    parameters = {
        InputSchema.Obj(buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "简短关键词，留空浏览") })
        })
    },
    execute = { args ->
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val matches = stickers.asSequence().filter {
            it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }.take(8).map { it.markdown() }.joinToString("\n")
        listOf(UIMessagePart.Text(matches.ifEmpty { "没有匹配的表情包" }))
    },
)
