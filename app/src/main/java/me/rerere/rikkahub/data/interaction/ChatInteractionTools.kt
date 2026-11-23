package me.rerere.rikkahub.data.interaction

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

fun buildChatInteractionTools(
    assistant: Assistant,
    onAvatar: suspend () -> String,
    onReaction: suspend (String, String, Boolean, String) -> Unit,
    onUpdateOwnAvatar: (suspend (String?, String?) -> Assistant)? = null,
): List<Tool> = buildList {
    if (assistant.enableAvatarInteractions) add(Tool(
        name = "avatar_interaction",
        description = "Gently interact with the user's avatar using their configured action and suffix. " +
            "The app displays a centered notice. Use naturally, do not repeatedly spam this tool.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        execute = {
            val text = onAvatar()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("actor", "assistant"); put("target", "user"); put("text", text)
            }.toString()))
        },
    ))
    if (assistant.enableAvatarInteractions && onUpdateOwnAvatar != null) add(Tool(
        name = "update_avatar_interaction",
        description = "修改你自己被用户双击头像（拍一拍）时显示的动作与后缀，并保存到当前角色设置。" +
            "例如动作「揉了揉」、后缀「的脑袋」会显示为「你揉了揉角色名的脑袋」。" +
            "至少提供一项，省略的项保持当前值；空后缀表示清空。按用户要求或聊天情境自然使用。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 20)
                    put("description", "动作，例如拍了拍、揉了揉，1–20 个字")
                })
                put("suffix", buildJsonObject {
                    put("type", "string")
                    put("maxLength", 80)
                    put("description", "接在你名字后面的文案，最多 80 个字；空字符串清空")
                })
            }, required = emptyList())
        },
        execute = { input ->
            val values = input.jsonObject
            require(values["action"]?.let { it is JsonPrimitive && it.isString } != false) { "动作需为文本" }
            require(values["suffix"]?.let { it is JsonPrimitive && it.isString } != false) { "后缀需为文本" }
            val action = values["action"]?.jsonPrimitive?.contentOrNull
            val suffix = values["suffix"]?.jsonPrimitive?.contentOrNull
            require(action != null || suffix != null) { "请提供动作或后缀" }
            require(action == null || action.isNotBlank() && action.length <= 20) { "互动动作需为 1–20 个字" }
            require(suffix == null || suffix.length <= 80) { "互动后缀最多 80 个字" }
            val updated = onUpdateOwnAvatar(action?.trim(), suffix?.trim())
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("action", updated.userInteractionAction)
                put("suffix", updated.userInteractionSuffix)
                put("preview", interactionText("你", updated.name.ifBlank { "AI" },
                    updated.userInteractionAction, updated.userInteractionSuffix))
            }.toString()))
        },
    ))
    if (assistant.enableMessageReactions) add(Tool(
        name = "message_reaction",
        description = "Add or remove YOUR emoji or kaomoji reaction underneath one specific message. " +
            "This is independent of avatar interactions. You cannot remove the user's reactions. " +
            "Use last_user, last_assistant, by_quote (with a unique sentence), or an exact message ID.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact ID, last_user, last_assistant, or by_quote; default last_user")
                })
                put("quote", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional exact sentence to react to within a split message")
                })
                put("emoji", buildJsonObject {
                    put("type", "string"); put("enum", buildJsonArray { CHAT_REACTIONS.forEach { add(it) } })
                })
                put("operation", buildJsonObject {
                    put("type", "string"); put("enum", buildJsonArray { add("add"); add("remove") })
                })
            }, required = listOf("emoji"))
        },
        execute = { input ->
            val values = input.jsonObject
            val target = values["message_id"]?.jsonPrimitive?.contentOrNull ?: "last_user"
            val emoji = values["emoji"]?.jsonPrimitive?.contentOrNull ?: error("emoji is required")
            val operation = values["operation"]?.jsonPrimitive?.contentOrNull ?: "add"
            require(operation == "add" || operation == "remove")
            onReaction(target, emoji, operation == "add", values["quote"]?.jsonPrimitive?.contentOrNull.orEmpty())
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true) }.toString()))
        },
    ))
}
