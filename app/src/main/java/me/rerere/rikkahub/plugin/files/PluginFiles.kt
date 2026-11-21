package me.rerere.rikkahub.plugin.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

internal fun requirePluginId(id: String): String {
    require(id.isNotBlank() && id != "." && id != ".." &&
        id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) {
        "Invalid plugin ID"
    }
    return id
}

/** Canonical containment also rejects sibling-prefix paths and links pointing outside the root. */
internal fun resolvePluginFile(
    root: File,
    path: String,
    allowRoot: Boolean = false,
    allowAbsolute: Boolean = false,
): File {
    require(path.none { it == '\\' || it.isISOControl() }) { "Invalid plugin file path" }
    require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Invalid plugin file path" }
    val input = File(path)
    require(allowAbsolute || !input.isAbsolute) { "Plugin file path must be relative" }
    val base = root.canonicalFile
    val target = (if (input.isAbsolute) input else File(base, path)).canonicalFile
    require(target.toPath().startsWith(base.toPath()) && (allowRoot || target != base)) {
        "Plugin file path is outside its directory"
    }
    return target
}

internal fun pluginDirectory(root: File, id: String): File {
    val name = requirePluginId(id)
    val directory = resolvePluginFile(root, name)
    require(directory == File(root.canonicalFile, name)) { "Plugin directory cannot alias another directory" }
    return directory
}

internal const val MAX_PLUGIN_ARCHIVE_BYTES = 64L * 1024 * 1024
private const val MAX_PLUGIN_FILE_BYTES = 64L * 1024 * 1024
private const val MAX_PLUGIN_METADATA_BYTES = 1024L * 1024
private const val MAX_PLUGIN_EXPANDED_BYTES = 256L * 1024 * 1024
private const val MAX_PLUGIN_ENTRIES = 4096

internal fun copyPluginBytes(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var written = 0L
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return written
        require(count <= limit - written) { "插件文件或解压总量超过大小限制" }
        output.write(buffer, 0, count)
        written += count
    }
}

/** Both preview and installation use this before reading any manifest or executing plugin code. */
internal fun extractPluginZip(zipFile: File, destination: File) {
    require(zipFile.length() <= MAX_PLUGIN_ARCHIVE_BYTES) { "插件压缩包超过 64 MiB" }
    check(destination.isDirectory || destination.mkdirs()) { "Cannot create plugin extraction directory" }
    ZipInputStream(zipFile.inputStream().buffered()).use { input ->
        var entries = 0
        var expanded = 0L
        var entry = input.nextEntry
        while (entry != null) {
            require(++entries <= MAX_PLUGIN_ENTRIES) { "插件文件和目录超过 4096 项" }
            val target = resolvePluginFile(destination, entry.name, allowRoot = entry.isDirectory)
            if (entry.isDirectory) {
                require(input.read() == -1) { "插件目录条目含有文件数据" }
                check(target.isDirectory || target.mkdirs()) { "Cannot create plugin directory" }
            } else {
                val parent = requireNotNull(target.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create plugin directory" }
                val fileLimit = when (target.name.lowercase()) {
                    "manifest.json", ".integrity" -> MAX_PLUGIN_METADATA_BYTES
                    else -> MAX_PLUGIN_FILE_BYTES
                }
                val limit = minOf(fileLimit, MAX_PLUGIN_EXPANDED_BYTES - expanded)
                expanded += target.outputStream().use { copyPluginBytes(input, it, limit) }
            }
            input.closeEntry()
            entry = input.nextEntry
        }
    }
}
