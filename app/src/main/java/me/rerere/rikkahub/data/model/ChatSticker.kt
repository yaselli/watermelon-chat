package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ChatSticker(val name: String, val description: String, val url: String) {
    fun markdown(): String {
        val label = listOf(name, description).filter { it.isNotBlank() }.joinToString(" · ")
            .replace(Regex("""[\r\n\\\[\]]"""), " ")
        val destination = url.replace("(", "%28").replace(")", "%29")
        return "![$PREFIX$label]($destination)"
    }

    companion object {
        const val PREFIX = "西瓜瓣表情 · "

        fun fromInput(name: String, description: String, link: String): ChatSticker? {
            val url = link.trim().toHttpUrlOrNull() ?: return null
            if (name.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return ChatSticker(name.trim(), description.trim(), url.toString())
        }
    }
}
