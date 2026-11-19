package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ChatSticker(val name: String, val description: String, val url: String, val category: String = "") {
    fun markdown(): String {
        val label = listOf(name, category, description).filter { it.isNotBlank() }.joinToString(" · ")
            .replace(Regex("""[\r\n\\\[\]]"""), " ")
        val destination = url.replace("(", "%28").replace(")", "%29")
        return "![$PREFIX$label]($destination)"
    }

    companion object {
        const val PREFIX = "西瓜瓣表情 · "

        const val LOCAL_PREFIX = "xiguaban-sticker:"
        private val localFile = Regex("""[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\.[a-z0-9]+""")

        fun localFilename(link: String): String? = if (link.startsWith(LOCAL_PREFIX)) {
            link.removePrefix(LOCAL_PREFIX).takeIf(localFile::matches)
        } else null

        fun fromInput(name: String, description: String, link: String): ChatSticker? {
            val url = link.trim().toHttpUrlOrNull() ?: return null
            if (name.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return ChatSticker(name.trim(), description.trim(), url.toString())
        }
    }
}
