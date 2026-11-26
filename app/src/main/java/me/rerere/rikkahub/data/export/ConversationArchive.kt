package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

@Serializable
private data class ConversationArchiveManifest(
    val format: String = "xiguaban-conversation-archive",
    val version: Int = 1,
    val conversation: Conversation,
    val attachments: Map<String, String>,
)

/** Only local attachments are copied; remote links remain links, without downloading untrusted URLs. */
object ConversationArchive {
    suspend fun export(context: Context, conversation: Conversation, destination: Uri) = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("conversation-archive-", ".zip", context.cacheDir)
        try {
            val attachments = conversation.files.distinct().filter { it.scheme in listOf("file", "content") }
                .mapIndexed { index, uri ->
                    val extension = uri.lastPathSegment?.substringAfterLast('.', "")
                        ?.takeIf { Regex("[a-zA-Z0-9]{1,10}").matches(it) } ?: "bin"
                    uri.toString() to "media/$index.$extension"
                }.toMap()
            val manifest = ConversationArchiveManifest(conversation = conversation, attachments = attachments)
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("conversation.json"))
                zip.write(JsonInstant.encodeToString(manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("聊天记录.md"))
                zip.write(buildString {
                    appendLine("# ${conversation.title}")
                    prepareChatExportMessages(conversation.currentMessages).forEach {
                        appendLine("\n## ${it.role} · ${it.createdAt}\n")
                        appendLine(it.toText())
                    }
                    appendLine("\n附件保存在 media 文件夹；远程图片链接仍需要网络。")
                }.toByteArray())
                zip.closeEntry()
                for ((uri, path) in attachments) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(path))
                    val input = context.contentResolver.openInputStream(Uri.parse(uri))
                        ?: error("有附件无法读取，原对话未清理")
                    input.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val expected = temp.inputStream().use { digest(it) }
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("无法写入归档文件，原对话未清理")
            output.use { target -> temp.inputStream().use { it.copyTo(target) } }
            val actual = context.contentResolver.openInputStream(destination)?.use { digest(it) }
            check(expected == actual) { "归档文件回读校验失败，原对话未清理" }
        } finally { temp.delete() }
    }

    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Restore as a separate chat with fresh IDs; never overwrite the active chat or extract ZIP paths. */
    suspend fun restore(context: Context, uri: Uri, assistantId: Uuid,
        files: FilesManager, repository: ConversationRepository): Uuid = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("conversation-import-", ".zip", context.cacheDir)
        val created = mutableListOf<Uri>()
        var committed = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { copyBounded(input, it, 512L * 1024 * 1024) }
            } ?: error("无法读取归档")
            ZipFile(temp).use { zip ->
                require(zip.size() <= 20_000) { "归档条目过多" }
                val entry = requireNotNull(zip.getEntry("conversation.json")) { "不是西瓜瓣聊天归档" }
                val manifestText = java.io.ByteArrayOutputStream().also { output ->
                    zip.getInputStream(entry).use { copyBounded(it, output, 32L * 1024 * 1024) }
                }.toString(Charsets.UTF_8.name())
                val manifest = JsonInstant.decodeFromString<ConversationArchiveManifest>(manifestText)
                require(manifest.format == "xiguaban-conversation-archive" && manifest.version == 1)
                val replacements = mutableMapOf<String, String>()
                var total = 0L
                for ((original, path) in manifest.attachments) {
                    currentCoroutineContext().ensureActive()
                    require(Regex("media/[0-9]+[.][a-zA-Z0-9]{1,10}").matches(path)) { "归档附件路径无效" }
                    val media = requireNotNull(zip.getEntry(path)) { "归档缺少附件" }
                    val file = File.createTempFile("archive-media-", "." + path.substringAfterLast('.'), context.cacheDir)
                    try {
                        zip.getInputStream(media).use { input -> file.outputStream().use {
                            total += copyBounded(input, it, 512L * 1024 * 1024 - total)
                        } }
                        val copied = files.createChatFilesByContents(listOf(file.toUri())).singleOrNull()
                            ?: error("附件导入失败")
                        created += copied
                        replacements[original] = copied.toString()
                    } finally { file.delete() }
                }
                fun rewrite(element: JsonElement): JsonElement = when (element) {
                    is JsonObject -> JsonObject(element.mapValues { rewrite(it.value) })
                    is JsonArray -> JsonArray(element.map { rewrite(it) })
                    is JsonPrimitive -> if (element.isString && element.content in replacements)
                        JsonPrimitive(replacements.getValue(element.content)) else element
                    else -> element
                }
                val restored = JsonInstant.decodeFromJsonElement<Conversation>(rewrite(
                    JsonInstant.encodeToJsonElement(manifest.conversation)
                )).let { source -> source.copy(
                    id = Uuid.random(), assistantId = assistantId, title = "归档 · ${source.title}",
                    folderId = null, config = null, contextCompaction = null, workspaceCwd = null,
                    messageNodes = source.messageNodes.map { node -> node.copy(id = Uuid.random(),
                        messages = node.messages.map { it.copy(id = Uuid.random(), localMetadata = null) }) },
                ) }
                // Source archive paths must never grant access to unrelated local files on this device.
                require(restored.files.filter { it.scheme in listOf("file", "content") }.all { it in created }) {
                    "归档包含未打包的本地附件"
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    repository.insertConversation(restored)
                    committed = true
                }
                restored.id
            }
        } finally {
            temp.delete()
            if (!committed) files.deleteChatFiles(created)
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "归档过大，无法安全导入" }
            output.write(buffer, 0, count)
        }
    }
}
