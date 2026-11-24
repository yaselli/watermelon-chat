package me.rerere.tts.provider.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.common.network.HttpAccess
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Lists TTS-capable models independently of speech generation. */
class ElevenLabsModelCatalog(private val client: OkHttpClient = sharedClient) {
    suspend fun fetch(baseUrl: String, apiKey: String): List<String> {
        val call = client.newCall(elevenLabsModelsRequest(baseUrl, apiKey))
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val models = response.use {
                            if (!it.isSuccessful) throw ElevenLabsModelsHttpException(it.code)
                            parseElevenLabsSpeechModels(it.body.string())
                        }
                        if (continuation.isActive) continuation.resume(models)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        val defaults = listOf(
            "eleven_v4", "eleven_v3", "eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_turbo_v2_5",
        )
        private val sharedClient by lazy {
            HttpAccess.configure(OkHttpClient.Builder())
                .followRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

class ElevenLabsModelsHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

internal fun elevenLabsModelsRequest(baseUrl: String, apiKey: String): Request = Request.Builder()
    .url(baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder().addPathSegment("models").build())
    .apply { if (apiKey.isNotBlank()) header("xi-api-key", apiKey.trim()) }
    .get()
    .build()

internal fun parseElevenLabsSpeechModels(body: String): List<String> {
    val models = Json.parseToJsonElement(body) as? JsonArray ?: throw IllegalArgumentException("Invalid model list")
    return models.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val speech = model["can_do_text_to_speech"] as? JsonPrimitive ?: return@mapNotNull null
        if (speech.isString || speech.booleanOrNull != true) return@mapNotNull null
        val id = model["model_id"] as? JsonPrimitive ?: return@mapNotNull null
        id.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
        .distinct()
}
