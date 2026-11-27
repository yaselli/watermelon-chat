package me.rerere.rikkahub.data.backup

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import me.rerere.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupAttachmentPathsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun acceptsExistingFlatAttachmentFormat() {
        val file = BackupAttachmentPaths.resolve(temp.root, "聊天图片 1.png")
        assertEquals(File(temp.root.canonicalFile, FileFolders.UPLOAD + "/聊天图片 1.png"), file)
    }

    @Test fun detectsInvalidLateAttachmentBeforeRestore() {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("settings.json", FileFolders.UPLOAD + "/nested/file.png")) {
                zip.putNextEntry(ZipEntry(name))
                zip.closeEntry()
            }
        }
        ZipFile(file).use { archive ->
            val error = runCatching { BackupAttachmentPaths.validate(archive, temp.root) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }
}
