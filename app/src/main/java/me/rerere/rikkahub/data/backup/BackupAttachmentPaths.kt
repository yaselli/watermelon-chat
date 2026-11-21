package me.rerere.rikkahub.data.backup

import java.io.File
import java.util.zip.ZipFile
import me.rerere.rikkahub.data.files.FileFolders

internal object BackupAttachmentPaths {
    fun validate(archive: ZipFile, filesDir: File) {
        val seen = HashSet<String>()
        val prefix = FileFolders.UPLOAD + "/"
        for (entry in archive.entries()) {
            require(seen.add(entry.name)) { "Duplicate backup entry" }
            if (entry.name.startsWith(prefix) && entry.name != prefix) {
                resolve(filesDir, entry.name.removePrefix(prefix))
            }
        }
    }

    fun resolve(filesDir: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid backup attachment name" }
        val root = File(filesDir.canonicalFile, FileFolders.UPLOAD)
        val target = File(root, name)
        require(target.canonicalFile == target && (!target.exists() || target.isFile)) {
            "Invalid backup attachment destination"
        }
        return target
    }
}
