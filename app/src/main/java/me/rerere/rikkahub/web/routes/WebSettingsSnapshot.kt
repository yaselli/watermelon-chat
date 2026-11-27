package me.rerere.rikkahub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Only primitive values and primitive lists may pass through as leaves.
private class Fields(val leaves: Set<String>, val children: Map<String, Fields>)

private fun fields(names: String, vararg children: Pair<String, Fields>) =
    Fields(names.split(" ").toSet(), children.toMap())

private val avatar = fields("type url content")
private val settingsFields = fields(
    "dynamicColor themeId developerMode enableWebSearch favoriteModels chatModelId assistantId searchServiceSelected webServerJwtEnabled",
    "displaySetting" to fields(
        "userNickname showUserAvatar showModelIcon showModelName showTokenUsage showThinkingContent autoCloseThinking " +
            "codeBlockAutoWrap codeBlockAutoCollapse showLineNumbers sendOnEnter enableAutoScroll fontSizeRatio " +
            "pasteLongTextAsFile pasteLongTextThreshold", "userAvatar" to avatar
    ),
    "providers" to fields("id name enabled", "models" to fields(
        "id modelId displayName type inputModalities outputModalities abilities", "tools" to fields("type")
    )),
    "assistants" to fields(
        "id name tags chatModelId reasoningLevel mcpServers modeInjectionIds lorebookIds quickMessageIds useAssistantAvatar",
        "avatar" to avatar
    ),
    "assistantTags" to fields("id name"),
    "modeInjections" to fields("id name enabled"),
    "lorebooks" to fields("id name description enabled"),
    "quickMessages" to fields("id title content"),
    "searchServices" to fields("id type"),
    "mcpServers" to fields("id type", "commonOptions" to fields(
        "name enable", "tools" to fields("enable name description needsApproval")
    ))
)

internal fun webSettingsSnapshot(settings: JsonElement): JsonElement = settingsFields.select(settings)

private fun Fields.select(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map { select(it) })
    is JsonObject -> buildJsonObject {
        for ((key, item) in value) {
            val child = children[key]
            if (child != null && (item is JsonObject || item is JsonArray)) {
                put(key, child.select(item))
            } else if (key in leaves && (item is JsonPrimitive || item is JsonArray && item.all { it is JsonPrimitive })) {
                put(key, item)
            }
        }
    }
    is JsonPrimitive -> kotlinx.serialization.json.JsonNull
}
