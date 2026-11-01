package me.rerere.rikkahub.data.ai.tools

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.CacheRequestDiagnostics
import org.junit.Assert.*
import org.junit.Test

class ProactiveWakeupToolTest {
    private val model = Model()

    @Test
    fun `phone timezone changes and rebuilding the tool do not rewrite its prompt or schema`() {
        val originalZone = TimeZone.getDefault()
        var executions = 0
        fun definition() = proactiveWakeupTool {
            executions++
            emptyList()
        }
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val first = definition()
            val prompt = first.systemPrompt(model, emptyList())
            val schema = first.parameters()
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val rebuilt = definition()
            assertEquals(prompt, first.systemPrompt(model, listOf(UIMessage.user("明晚来找我"))))
            assertEquals(prompt, rebuilt.systemPrompt(model, emptyList()))
            assertEquals(schema, rebuilt.parameters())
            assertEquals(0, executions)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `fresh clock results can be appended without rewriting the previous request prefix`() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        var now = Instant.parse("2026-11-01T15:59:00Z")
        val tool = proactiveWakeupTool {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("current_time", now.atZone(zone).toString())
                put("timezone", zone.id)
            }.toString()))
        }
        fun message(role: String, text: String) = buildJsonObject {
            put("role", role)
            put("content", text)
        }
        fun request(rows: List<JsonObject>) = buildJsonObject {
            put("messages", JsonArray(rows))
            put("tools", buildJsonArray { add(tool.name) })
        }
        val args = buildJsonObject { put("action", "get") }
        val firstClock = (tool.execute(args).single() as UIMessagePart.Text).text
        val firstHistory = listOf(
            message("system", tool.systemPrompt(model, emptyList())),
            message("user", "查当前时间"),
            message("assistant", "调用 get"),
            message("tool", firstClock),
        )
        val tracker = CacheRequestDiagnostics()
        tracker.compare("wakeup", request(firstHistory))
        now = now.plusSeconds(120)
        val nextClock = (tool.execute(args).single() as UIMessagePart.Text).text
        assertNotEquals(firstClock, nextClock)
        val nextHistory = firstHistory + listOf(
            message("user", "再查一次"),
            message("assistant", "调用 get"),
            message("tool", nextClock),
        )
        val comparison = tracker.compare("wakeup", request(nextHistory))
        assertFalse(comparison.systemChanged)
        assertFalse(comparison.toolsChanged)
        assertNull(comparison.firstChangedRole)
        assertEquals(firstHistory.size, comparison.unchangedMessages)
        assertEquals(firstClock, firstHistory.last()["content"]!!.jsonPrimitive.content)
        val clock = Json.parseToJsonElement(nextClock).jsonObject
        assertEquals("Asia/Shanghai", clock["timezone"]!!.jsonPrimitive.content)
        assertTrue(clock["current_time"]!!.jsonPrimitive.content.startsWith("2026-11-02T00:01"))
    }

    @Test
    fun `get schedule and cancel retain their schema and execution callback`() = runBlocking {
        val seen = mutableListOf<JsonElement>()
        val output = listOf<UIMessagePart>(UIMessagePart.Text("saved result"))
        val tool = proactiveWakeupTool {
            seen += it
            output
        }
        val schema = tool.parameters() as InputSchema.Obj
        val actions = schema.properties["action"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("schedule", "get", "cancel"), actions)
        assertEquals(listOf("action"), schema.required)
        for (action in actions) {
            val args = buildJsonObject {
                put("action", action)
                if (action == "schedule") {
                    put("at", "2026-11-02 21:30")
                    put("reason", "约好来聊天")
                }
            }
            assertSame(output, tool.execute(args))
            assertEquals(args, seen.last())
        }
        assertEquals(actions.size, seen.size)
    }
}
