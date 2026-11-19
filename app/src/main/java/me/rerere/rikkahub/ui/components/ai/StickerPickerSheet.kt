package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
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
    val files: FilesManager = koinInject()
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
                    var added = 0
                    store.update { current ->
                        val merged = (current.chatStickers + imported).distinctBy(ChatSticker::url)
                        added = merged.size - current.chatStickers.size
                        current.copy(chatStickers = merged)
                    }
                    notice = "已读取 ${imported.size} 张，新增 ${added} 张，其余已在库中"
                }
            }) { Text(if (busy) "处理中…" else "导入旧插件链接") }
            if (notice.isNotBlank()) Text(notice)
            if (settings.chatStickers.isEmpty()) Text("点「添加」，从相册选图，填写名称、分类和描述。", Modifier.padding(vertical = 24.dp))
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
        var category by remember { mutableStateOf(editing?.category.orEmpty()) }
        var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
        var useLink by remember { mutableStateOf(false) }
        var address by remember { mutableStateOf(editing?.url?.takeIf { ChatSticker.localFilename(it) == null }.orEmpty()) }
        val remote = if (useLink) ChatSticker.fromInput(name, description, address) else null
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) { photo = uri; useLink = false }
        }
        AlertDialog(onDismissRequest = { if (!busy) editorOpen = false }, title = { Text("编辑表情包") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val preview = photo?.toString() ?: editing?.url
                    if (preview != null) StickerImage(preview, name, Modifier.size(88.dp))
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (preview == null) "从手机相册选图" else "更换图片")
                    }
                    TextButton(onClick = { useLink = !useLink }) { Text(if (useLink) "收起链接" else "使用已有图片链接") }
                    if (useLink) OutlinedTextField(address, { address = it }, label = { Text("图片直链") }, singleLine = true)
                    OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(description, { description = it }, label = { Text("描述（AI 据此选图）") })
                    if (notice.isNotEmpty()) Text(notice)
                }
            }, confirmButton = {
                TextButton(enabled = name.isNotBlank() && (if (useLink) remote != null else photo != null || editing != null) && !busy, onClick = {
                    change {
                        val old = editing
                        val saved = photo?.takeUnless { useLink }?.let { files.saveUploadFromUri(it, displayName = "sticker") }
                        val link = saved?.let { ChatSticker.LOCAL_PREFIX + files.getFile(it).name } ?: remote?.url ?: requireNotNull(old).url
                        val sticker = ChatSticker(name.trim(), description.trim(), link, category.trim())
                        try {
                            require(saved == null || saved.sizeBytes > 0) { "图片读取失败，请重新选择" }
                            store.update { current ->
                                require(current.chatStickers.none { it.url == sticker.url && it.url != old?.url }) { "这条链接已经在库里了" }
                                current.copy(chatStickers = if (old == null) current.chatStickers + sticker
                                else current.chatStickers.map { if (it.url == old.url) sticker else it })
                            }
                        } catch (e: Exception) {
                            saved?.let { files.delete(it.id) }
                            throw e
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
    val pluginId = "com.orangechat.plugin.sticker"
    check(loader.getLoadedPlugin(pluginId) != null) { "请先在当前版本的插件管理中安装并启用旧表情插件" }
    suspend fun call(name: String, args: JsonObject): JsonObject {
        val result = loader.callTool(pluginId, name, args).getOrThrow() as? JsonObject
            ?: error("旧插件未返回表情数据")
        result["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
        return result
    }
    val result = call("list_stickers", buildJsonObject {})
    val list = result["stickers"] as? JsonArray
    if (list != null) {
        return list.map { row ->
            val item = row.jsonObject
            val id = item.getValue("id").jsonPrimitive.content
            val reply = call("send_sticker", buildJsonObject { put("sticker_id", id) })
            val markdown = reply.getValue("markdown").jsonPrimitive.content
            val url = markdown.substringAfter("](", "").removeSuffix(")")
            ChatSticker.fromInput(id, item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), url)
                ?: error("旧插件没有提供有效图片地址（$id）")
        }
    }
    val groups = result["data"] as? JsonObject ?: error("无法识别旧插件返回的表情清单")
    return groups.flatMap { (category, entries) ->
        entries.jsonArray.map { entry ->
            val item = entry.jsonObject
            val description = item["description"]?.jsonPrimitive?.contentOrNull
                ?: (item["tags"] as? JsonArray)?.joinToString("、") { it.jsonPrimitive.content }.orEmpty()
            ChatSticker.fromInput(item.getValue("name").jsonPrimitive.content, description,
                item.getValue("url").jsonPrimitive.content)?.copy(category = category)
                ?: error("旧插件返回了无效图片地址")
        }
    }
}
