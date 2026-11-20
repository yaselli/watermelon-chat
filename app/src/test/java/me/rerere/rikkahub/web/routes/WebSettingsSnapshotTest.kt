package me.rerere.rikkahub.web.routes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSettingsSnapshotTest {
    @Test
    fun returnsOnlyWebFieldsAtEveryLevel() {
        val source = Json.parseToJsonElement("""
            {
              "webServerAccessPassword":"password","webDavConfig":{"password":"secret"},
              "providers":[{"name":"My model","apiKey":"secret","models":[
                {"id":"model","providerOverwrite":{"apiKey":"secret"}}
              ]}],
              "mcpServers":[{"commonOptions":{"name":"Calendar","headers":["secret"],"oauth":{"accessToken":"secret"}}}],
              "searchServices":[{"type":"search","apiKey":"secret"}]
            }
        """.trimIndent())
        val expected = Json.parseToJsonElement("""
            {
              "providers":[{"name":"My model","models":[{"id":"model"}]}],
              "mcpServers":[{"commonOptions":{"name":"Calendar"}}],
              "searchServices":[{"type":"search"}]
            }
        """.trimIndent())
        assertEquals(expected, webSettingsSnapshot(source))
    }
}
