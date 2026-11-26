package me.rerere.rikkahub.data.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class AutoBackupService(
    private val store: AutoBackupStore,
    private val folder: SafBackupFolder,
    private val exporter: WebDavSync,
) {
    private val lock = Mutex()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    suspend fun backup(force: Boolean) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        var archive: File? = null
        var candidate: String? = null
        try {
            _running.value = true
            val state = store.current()
            val config = state.config
            if (!force && !config.enabled) return@withContext
            check(config.folder.isNotBlank()) { "请先选择备份文件夹" }
            folder.requireAccess(config.folder)
            val now = Instant.now()
            if (!force && state.lastSuccess > 0 &&
                now.isBefore(nextAutoBackupAt(now, state.lastSuccess, config, ZoneId.systemDefault()))
            ) {
                cleanupRetired(config.folder)
                return@withContext
            }
            val prepared = exporter.prepareBackupFile(WebDavConfig(), requireCompletePlugins = true)
            archive = prepared
            val job = currentCoroutineContext()
            VerifiedBackupWriter.validateArchive(prepared) { job.ensureActive() }
            // Never overwrite the known-good file. Persist a recovery pointer before writing the candidate.
            val uri = folder.create(config.folder)
            candidate = uri
            store.stage(uri, config.revision)
            VerifiedBackupWriter.copyAndVerify(
                prepared, { folder.output(uri) }, { folder.input(uri) },
            ) { job.ensureActive() }
            job.ensureActive()
            withContext(NonCancellable) {
                store.publish(uri, config.revision, System.currentTimeMillis())
                candidate = null
            }
            cleanupRetired(config.folder)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            store.error(when (error) {
                is SecurityException -> "文件夹授权失效，请重新选择文件夹"
                else -> error.message?.take(160) ?: "备份失败，原备份已保留"
            })
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    candidate?.let { uri ->
                        if (store.current().latestFile != uri) {
                            runCatching {
                                folder.delete(uri)
                                store.clearPending(uri)
                            }
                        }
                    }
                } finally {
                    archive?.delete()
                    _running.value = false
                    lock.unlock()
                }
            }
        }
    }

    private suspend fun cleanupRetired(folderUri: String) {
        val state = try {
            store.current()
        } catch (_: IOException) {
            return
        }
        if (state.config.folder != folderUri) return
        for (uri in state.retiredFiles) {
            if (uri == state.latestFile || uri == state.pendingFile) continue
            try {
                folder.delete(uri)
                store.retiredRemoved(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep it tracked for a later retry. Cleanup never changes the backup result.
            }
        }
    }
}
