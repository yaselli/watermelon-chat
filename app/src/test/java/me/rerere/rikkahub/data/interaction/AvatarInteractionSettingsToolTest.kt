package me.rerere.rikkahub.data.interaction

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.ToolDiscoveryPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AvatarInteractionSettingsToolTest {
    private var current = Assistant(
        name = "沉渡",
        enableAvatarInteractions = true,
        userInteractionAction = "拍了拍",
        userInteractionSuffix = "的肩膀",
        assistantInteractionAction = "抱了抱",
        assistantInteractionSuffix = "并说晚安",
    )
    private var writes = 0

    private fun tools(assistant: Assistant = current) = buildChatInteractionTools(
        assistant,
        onAvatar = { "" },
        onReaction = { _, _, _, _ -> },
        onUpdateOwnAvatar = { action, suffix ->
            writes++
            current = current.copy(
                userInteractionAction = action ?: current.userInteractionAction,
                userInteractionSuffix = suffix ?: current.userInteractionSuffix,
            )
            current
        },
    )

    @Test
    fun `own settings tool is registered and resident with tool discovery enabled`() {
        val assistant = current.copy(toolDiscoveryEnabled = true)
        val available = tools(assistant)
        assertTrue(available.any { it.name == "update_avatar_interaction" })
        assertTrue("update_avatar_interaction" in ToolDiscoveryPolicy.residentNames(assistant, Settings(), available))
    }

    @Test
    fun `changes own received interaction and previews it without changing outgoing interaction`() = runBlocking {
        val tool = tools().single { it.name == "update_avatar_interaction" }
        val result = tool.execute(Json.parseToJsonElement("""{"action":"揉了揉","suffix":"的脑袋"}"""))
        val text = (result.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertEquals("你揉了揉沉渡的脑袋", Json.parseToJsonElement(text).jsonObject["preview"]!!.jsonPrimitive.content)
        assertEquals("揉了揉", current.userInteractionAction)
        assertEquals("的脑袋", current.userInteractionSuffix)
        assertEquals("抱了抱", current.assistantInteractionAction)
        assertEquals("并说晚安", current.assistantInteractionSuffix)
        assertEquals(1, writes)
    }

    @Test
    fun `partial update keeps latest action and accepts clearing suffix`() = runBlocking {
        val tool = tools().single { it.name == "update_avatar_interaction" }
        current = current.copy(userInteractionAction = "摸了摸")
        tool.execute(Json.parseToJsonElement("""{"suffix":""}"""))
        assertEquals("摸了摸", current.userInteractionAction)
        assertEquals("", current.userInteractionSuffix)
    }

    @Test
    fun `invalid input never reaches persistence`() = runBlocking {
        val tool = tools().single { it.name == "update_avatar_interaction" }
        for (input in listOf("{}", """{"action":true}""", """{"suffix":123}""", """{"action":" "}""",
            """{"action":"${"a".repeat(21)}"}""", """{"suffix":"${"a".repeat(81)}"}""")) {
            try {
                tool.execute(Json.parseToJsonElement(input))
                fail("Invalid input was accepted")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, writes)
            }
        }
    }

    @Test
    fun `disabled interactions hide settings tool`() {
        assertTrue(tools(current.copy(enableAvatarInteractions = false)).none { it.name == "update_avatar_interaction" })
    }
}
