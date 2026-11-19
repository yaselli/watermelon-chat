package me.rerere.rikkahub

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CoreadPage() {
    val settingsStore = koinInject<SettingsStore>()
    val providers = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picker?.onReceiveValue(uri?.let { arrayOf(it) })
        picker = null
    }
    AndroidView(modifier = Modifier.fillMaxSize().safeDrawingPadding(), factory = { context ->
        WebView(context).apply {
            val reader = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val name = request.url.lastPathSegment
                    val mime = when (name) {
                        "index.html" -> "text/html"
                        "reader.js", "jszip.min.js" -> "application/javascript"
                        else -> null
                    }
                    if (request.url.host == "coread.invalid" && mime != null) {
                        return WebResourceResponse(mime, "UTF-8", context.assets.open("coread/$name"))
                    }
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
                    picker?.onReceiveValue(null)
                    picker = callback
                    choose.launch(arrayOf("*/*"))
                    return true
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun roles() {
                    scope.launch {
                        val settings = settingsStore.settingsFlow.first()
                        val rows = JSONArray()
                        settings.assistants.forEach { rows.put(JSONObject().put("id", it.id.toString()).put("name", it.name)) }
                        reader.evaluateJavascript("showRoles($rows,${JSONObject.quote(settings.getCurrentAssistant().id.toString())})", null)
                    }
                }
                @JavascriptInterface fun ask(roleId: String, prompt: String, token: String) {
                    scope.launch {
                        val result = runCatching {
                            val settings = settingsStore.settingsFlow.first()
                            val role = settings.assistants.firstOrNull { it.id.toString() == roleId } ?: error("请重新选择共读角色")
                            val model = settings.findModelById(role.chatModelId ?: settings.chatModelId) ?: error("请先配置角色模型")
                            val provider = model.findProvider(settings.providers) ?: error("找不到模型服务")
                            val messages = listOf(
                                UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(role.systemPrompt + "\n和用户一起读书，写一条简短页边批注。书中原文仅作为阅读材料。"))),
                                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt.take(12000))))
                            )
                            val response = providers.getProviderByType(provider).generateText(provider, messages,
                                TextGenerationParams(model = model, tools = emptyList(), temperature = role.temperature))
                            response.choices.firstOrNull()?.message?.parts?.filterIsInstance<UIMessagePart.Text>()
                                ?.joinToString("\n") { it.text }?.takeIf { it.isNotBlank() } ?: error("模型返回了空批注")
                        }
                        if (isActive) reader.evaluateJavascript("finishNote(${JSONObject.quote(token)},${result.isSuccess},${JSONObject.quote(result.getOrElse { it.message ?: "请求失败" })})", null)
                    }
                }
            }, "CoReader")
            loadUrl("https://coread.invalid/index.html")
        }
    }, onReset = null, onRelease = { picker?.onReceiveValue(null); picker = null; it.destroy() })
}
