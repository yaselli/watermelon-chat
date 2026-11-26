package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.export.ConversationArchive
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.planHistoryCleanup
import me.rerere.rikkahub.data.model.shouldRemindHistory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.theme.MaterialAlertDialog as AlertDialog
import org.koin.compose.koinInject

@Composable
fun ConversationHistoryReminder(conversation: Conversation) {
    var dismissed by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val service: ChatService = koinInject()
    val job by service.getGenerationJobStateFlow(conversation.id).collectAsState(initial = null)
    LaunchedEffect(conversation.messageNodes.size) {
        if (conversation.messageNodes.size < (conversation.config?.historyReminderThreshold ?: 600)) dismissed = false
    }
    if (!dismissed && job?.isActive != true && conversation.shouldRemindHistory()) {
        ConversationHistoryDialog(conversation, onDismiss = { dismissed = true })
    }
}

@Composable
fun ConversationHistoryDialog(conversation: Conversation, onDismiss: () -> Unit) {
    val service: ChatService = koinInject()
    val files: FilesManager = koinInject()
    val repository: ConversationRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by rememberSaveable(conversation.id) { mutableIntStateOf(200) }
    var threshold by rememberSaveable(conversation.id) {
        mutableIntStateOf(conversation.config?.historyReminderThreshold ?: 600)
    }
    var disabled by rememberSaveable(conversation.id) {
        mutableStateOf(conversation.config?.historyReminderDisabled == true)
    }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Conversation?>(null) }
    val plan = remember(conversation.messageNodes, keep) { runCatching { conversation.planHistoryCleanup(keep) } }
    val job by service.getGenerationJobStateFlow(conversation.id).collectAsState(initial = null)
    val generating = job?.isActive == true
    suspend fun savePreferences(snooze: Boolean) {
        service.updateConversationConfig(conversation.id) { it.copy(
            historyReminderThreshold = threshold, historyReminderDisabled = disabled,
            historyReminderAfter = if (snooze) conversation.messageNodes.size + threshold else 0,
        ) }
    }
    fun cleanup(snapshot: Conversation, uri: Uri?) {
        busy = true
        error = null
        scope.launch {
            try {
                service.organizeConversationHistory(snapshot, keep, uri).getOrThrow()
                savePreferences(false)
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "整理失败，原记录未清理" }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = pending
        pending = null
        if (uri != null && snapshot != null) cleanup(snapshot, uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    ConversationArchive.restore(context, uri, conversation.assistantId, files, repository)
                    android.widget.Toast.makeText(context, "已导入为独立聊天，可在聊天列表查看", android.widget.Toast.LENGTH_LONG).show()
                    onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "归档导入失败" }
                finally { busy = false }
            }
        }
    }
    fun dismiss() {
        if (busy) return
        scope.launch {
            try { savePreferences(true); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "提醒设置保存失败，请重试" }
        }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("整理聊天历史") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前保存 ${conversation.messageNodes.size} 条消息。整理可以减轻加载负担，也可以继续聊天。")
                Text("达到多少条时提醒")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500, 600, 700).forEach { count -> FilterChip(selected = threshold == count,
                        onClick = { threshold = count }, enabled = !busy, label = { Text("$count") }) }
                }
                Text("保留最近的消息")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 300).forEach { count -> FilterChip(selected = keep == count,
                        onClick = { keep = count; confirmDelete = false }, enabled = !busy, label = { Text("$count") }) }
                }
                plan.getOrNull()?.let { Text("本次清理 ${it.removeCount} 条，保留 ${it.keepCount} 条。为保留完整对话轮次，数量可能略有调整。") }
                Text("清理前会保留聊天摘要；需要时调用压缩模型生成摘要，会产生模型用量。已有记忆及必要原文依据保留。")
                Text("导出文件包含旧聊天、可读文本和现有本地附件。已缺失的附件会在归档中标注；远程图片保留链接。共享附件不会在本次整理时删除。")
                Row {
                    Checkbox(checked = disabled, onCheckedChange = { disabled = it }, enabled = !busy)
                    Text("此对话不再提醒", Modifier.padding(top = 12.dp))
                }
                if (generating) Text("请等待当前回复结束后再整理。")
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在整理，请稍候…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { pending = conversation; export.launch("西瓜瓣聊天归档-${System.currentTimeMillis()}.zip") },
                    enabled = !busy && !generating && plan.isSuccess, modifier = Modifier.fillMaxWidth()) {
                    Text("导出并清理")
                }
                OutlinedButton(onClick = { confirmDelete = true },
                    enabled = !busy && !generating && plan.isSuccess, modifier = Modifier.fillMaxWidth()) {
                    Text("直接清理，不导出")
                }
                if (confirmDelete) {
                    Text("确认永久清理这 ${plan.getOrNull()?.removeCount ?: 0} 条旧聊天？未导出的原聊天无法恢复；摘要和记忆依据仍保留。")
                    TextButton(onClick = { confirmDelete = false; cleanup(conversation, null) },
                        enabled = !busy && !generating && plan.isSuccess) { Text("确认清理旧聊天") }
                }
                TextButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !busy) { Text("导入聊天归档为独立聊天") }
            }
        },
        confirmButton = { TextButton(onClick = ::dismiss, enabled = !busy) { Text("暂不处理 / 保存设置") } },
    )
}
