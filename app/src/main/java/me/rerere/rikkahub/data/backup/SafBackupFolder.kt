package me.rerere.rikkahub.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException

class SafBackupFolder(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun grant(folder: Uri) {
        resolver.takePersistableUriPermission(
            folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        requireAccess(folder.toString())
    }

    fun requireAccess(folder: String) {
        val uri = Uri.parse(folder)
        check(DocumentsContract.isTreeUri(uri)) { "请选择文件夹" }
        check(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            "备份文件夹授权已失效，请重新选择"
        }
    }

    fun create(folder: String): String {
        requireAccess(folder)
        val tree = Uri.parse(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "Xiguaban-auto-${java.time.LocalDate.now()}-${java.util.UUID.randomUUID()}.zip"
        return (DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("不能在所选文件夹创建备份")).toString()
    }

    fun output(uri: String) = resolver.openOutputStream(Uri.parse(uri), "wt")
        ?: throw IOException("无法写入备份文件")

    fun input(uri: String) = resolver.openInputStream(Uri.parse(uri))
        ?: throw IOException("无法读回校验备份文件")

    fun delete(uri: String) {
        val document = Uri.parse(uri)
        deleteTrackedBackup(
            delete = { DocumentsContract.deleteDocument(resolver, document) },
            confirmMissing = { isMissingFromBackupFolder(document) },
        )
    }

    private fun isMissingFromBackupFolder(document: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(document)) return false
        val documentId = DocumentsContract.getDocumentId(document)
        val folderId = DocumentsContract.getTreeDocumentId(document)
        if (documentId == folderId) return false
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(document, folderId)
        val idColumn = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        return resolver.query(children, arrayOf(idColumn), null, null, null)?.use { cursor ->
            val extras = cursor.extras
            // A provider can return a partial directory while loading or reporting an error.
            if (extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                extras.containsKey(DocumentsContract.EXTRA_ERROR) ||
                extras.containsKey(DocumentsContract.EXTRA_INFO)
            ) return@use false
            val index = cursor.getColumnIndexOrThrow(idColumn)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(index)
                if (childId == null || childId == documentId) return@use false
            }
            true
        } ?: false
    }
}

internal fun deleteTrackedBackup(delete: () -> Boolean, confirmMissing: () -> Boolean) {
    val deleted = try {
        delete()
    } catch (_: FileNotFoundException) {
        false
    } catch (error: SecurityException) {
        // A moved or missing document can fail the provider's tree-membership check.
        if (confirmMissing()) return
        throw error
    }
    if (!deleted && !confirmMissing()) throw IOException("未能清理自动备份文件")
}
