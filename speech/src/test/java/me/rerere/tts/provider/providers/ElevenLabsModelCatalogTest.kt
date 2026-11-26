package me.rerere.tts.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ElevenLabsModelCatalogTest {
    @Test
    fun listsNewModelsWithoutUpdatingTheBuiltInCatalog() {
        val body = """[
            {"model_id":"eleven_v4","can_do_text_to_speech":true,"name":"Eleven v4"},
            {"model_id":"future_speech_model","can_do_text_to_speech":true},
            {"model_id":"scribe_v2","can_do_text_to_speech":false},
            {"model_id":"voice_conversion","can_do_voice_conversion":true}
        ]"""
        assertEquals(listOf("eleven_v4", "future_speech_model"), parseElevenLabsSpeechModels(body))
    }

    @Test
    fun ignoresMalformedEntriesAndDeduplicatesModelIds() {
        val body = """[
            null,
            {"model_id":" eleven_v4 ","can_do_text_to_speech":true},
            {"model_id":"eleven_v4","can_do_text_to_speech":true},
            {"model_id":" ","can_do_text_to_speech":true},
            {"model_id":123,"can_do_text_to_speech":true},
            {"model_id":"not_boolean","can_do_text_to_speech":"true"},
            {"can_do_text_to_speech":true}
        ]"""
        assertEquals(listOf("eleven_v4"), parseElevenLabsSpeechModels(body))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnErrorObjectInsteadOfTreatingItAsAModelList() {
        parseElevenLabsSpeechModels("""{"detail":{"status":"invalid_api_key"}}""")
    }

    @Test
    fun usesTheConfiguredEndpointAndKeyWithOrWithoutATrailingSlash() {
        for (baseUrl in listOf("https://example.com/proxy/v1", "https://example.com/proxy/v1/")) {
            val request = elevenLabsModelsRequest(baseUrl, " test-key ")
            assertEquals("https://example.com/proxy/v1/models", request.url.toString())
            assertEquals("test-key", request.header("xi-api-key"))
            assertEquals("GET", request.method)
        }
        assertEquals(null, elevenLabsModelsRequest("https://example.com/v1", "").header("xi-api-key"))
    }

    @Test
    fun reportsHttpStatusWithoutExposingTheResponseBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("sensitive-server-detail".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        try {
            var failure: ElevenLabsModelsHttpException? = null
            try {
                ElevenLabsModelCatalog(client).fetch("https://example.com/v1", "test-key")
            } catch (e: ElevenLabsModelsHttpException) {
                failure = e
            }
            assertEquals(403, failure?.statusCode)
            assertFalse(failure?.message.orEmpty().contains("sensitive-server-detail"))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellingTheRefreshCancelsTheHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.countDown()
            throw IOException("cancelled")
        }.build()
        try {
            val refresh = launch { ElevenLabsModelCatalog(client).fetch("https://example.com/v1", "test-key") }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            refresh.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun defaultsIncludeV4AndKeepExistingSpeechModels() {
        assertTrue(ElevenLabsModelCatalog.defaults.contains("eleven_v4"))
        assertTrue(ElevenLabsModelCatalog.defaults.contains("eleven_multilingual_v2"))
        assertTrue(ElevenLabsModelCatalog.defaults.contains("eleven_v3"))
    }
}
