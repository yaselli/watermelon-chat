package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ChatSticker
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.ui.theme.MaterialModalBottomSheet
import me.rerere.rikkahub.ui.theme.MaterialSurface
import org.koin.compose.koinInject

@Composable
internal fun StickerPickerSheet(onDismiss: () -> Unit, onStickerSelected: (String) -> Unit) {
    val store: SettingsStore = koinInject()
    val loader: PluginLoader = koinInject()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatSticker?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    fun change(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = ""
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: "保存失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    MaterialModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 620.dp).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("表情包", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { managing = !managing }) { Text(if (managing) "完成" else "管理") }
                TextButton(onClick = { editing = null; editorOpen = true }) { Text("添加") }
            }
            if (managing) TextButton(enabled = !busy, onClick = {
                change {
                    val imported = importLegacyStickers(loader)
                    store.update { it.copy(chatStickers = (it.chatStickers + imported).distinctBy(ChatSticker::url)) }
                    notice = "已读取旧库链接，重复项已跳过"
                }
            }) { Text("导入旧插件链接") }
            if (notice.isNotBlank()) Text(notice)
            if (settings.chatStickers.isEmpty()) Text("点「添加」，填入图片直链、名称和描述。", Modifier.padding(vertical = 24.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(settings.chatStickers, key = { it.url }) { sticker ->
                    MaterialSurface(onClick = {
                        if (managing) { editing = sticker; editorOpen = true } else onStickerSelected(sticker.markdown())
                    }, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(4.dp)) {
                            StickerImage(sticker.url, sticker.name, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(sticker.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (editorOpen) key(editing) {
        var name by remember { mutableStateOf(editing?.name.orEmpty()) }
        var description by remember { mutableStateOf(editing?.description.orEmpty()) }
        var link by remember { mutableStateOf(editing?.url.orEmpty()) }
        val sticker = ChatSticker.fromInput(name, description, link)
        AlertDialog(onDismissRequest = { if (!busy) editorOpen = false }, title = { Text("编辑表情包") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(description, { description = it }, label = { Text("描述（AI 据此选图）") })
                    OutlinedTextField(link, { link = it }, label = { Text("图片直链") }, singleLine = true)
                    if (notice.isNotEmpty()) Text(notice)
                }
            }, confirmButton = {
                TextButton(enabled = sticker != null && !busy, onClick = {
                    if (sticker != null) change {
                        val old = editing
                        store.update { current ->
                            require(current.chatStickers.none { it.url == sticker.url && it.url != old?.url }) { "这条链接已经在库里了" }
                            current.copy(chatStickers = if (old == null) current.chatStickers + sticker
                                else current.chatStickers.map { if (it.url == old.url) sticker else it })
                        }
                        editorOpen = false
                    }
                }) { Text("保存") }
            }, dismissButton = {
                Row {
                    if (editing != null) TextButton(enabled = !busy, onClick = {
                        change {
                            val url = editing?.url
                            store.update { it.copy(chatStickers = it.chatStickers.filterNot { s -> s.url == url }) }
                            editorOpen = false
                        }
                    }) { Text("移除") }
                    TextButton(onClick = { editorOpen = false }, enabled = !busy) { Text("取消") }
                }
            })
    }
}

private suspend fun importLegacyStickers(loader: PluginLoader): List<ChatSticker> {
    val result = loader.callTool("com.orangechat.plugin.sticker", "list_stickers", buildJsonObject {}).getOrThrow().jsonObject
    val groups = result["data"] as? JsonObject ?: error("旧插件未能读取表情库，请检查插件配置或稍后重试")
    return groups.flatMap { (category, entries) ->
        entries.jsonArray.mapNotNull { entry ->
            val item = entry.jsonObject
            ChatSticker.fromInput(item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item["description"]?.jsonPrimitive?.contentOrNull ?: category,
                item["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
